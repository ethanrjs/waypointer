package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.*;

final class RouteFolderEditScreen extends Screen {
    private final Screen parent;
    private final ActiveGroupManager manager;
    private final String zoneId;
    private final RouteFolder folder;
    private final List<String> selectedGroupIds;
    private String nameValue;
    private String colorValue;
    private EditBox nameBox;
    private EditBox colorBox;
    private ColorSwatchButton colorPreview;
    private Button saveButton;
    private RouteFolderEditLayout.Layout layout;

    RouteFolderEditScreen(Screen parent, ActiveGroupManager manager, String zoneId,
                          RouteFolder folder, List<String> selectedGroupIds) {
        super(Component.translatable(folder == null
                ? "waypointer.screen.route_folder.create.title"
                : "waypointer.screen.route_folder.edit.title"));
        this.parent = parent;
        this.manager = manager;
        this.zoneId = zoneId;
        this.folder = folder;
        this.selectedGroupIds = selectedGroupIds == null ? List.of() : List.copyOf(selectedGroupIds);
        this.nameValue = folder == null ? nextFolderName(manager, zoneId) : folder.name();
        this.colorValue = formatColor(folder == null ? RouteFolder.DEFAULT_COLOR : folder.color());
    }

    @Override
    protected void init() {
        layout = RouteFolderEditLayout.calculate(
                width, height, folder != null, !selectedGroupIds.isEmpty());
        nameBox = new EditBox(
                font, layout.contentX(), layout.nameFieldY(),
                layout.contentWidth(), BTN_H,
                Component.translatable("waypointer.screen.route_folder.name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(nameValue);
        nameBox.setResponder(value -> {
            nameValue = value == null ? "" : value;
            refreshSaveState();
        });
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        colorBox = new EditBox(
                font, layout.colorFieldX(), layout.colorControlY(),
                layout.colorFieldWidth(), BTN_H,
                Component.translatable("waypointer.screen.route_folder.color"));
        colorBox.setMaxLength(7);
        colorBox.setValue(colorValue);
        colorBox.setResponder(value -> {
            colorValue = value == null ? "" : value;
            if (colorPreview != null) colorPreview.setColor(displayColor());
            refreshSaveState();
        });
        addRenderableWidget(colorBox);
        colorPreview = new ColorSwatchButton(
                layout.previewX(), layout.colorControlY(),
                layout.previewSize(), layout.previewSize(), "", displayColor(),
                this::openColorPicker);
        colorPreview.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.route_folder.color_picker.tooltip")));
        addRenderableWidget(colorPreview);
        addRenderableWidget(styledButton(
                layout.resetX(), layout.colorControlY(), layout.resetWidth(), BTN_H,
                Component.translatable("waypointer.screen.route_folder.color.default"),
                button -> {
                    colorBox.setValue(formatColor(RouteFolder.DEFAULT_COLOR));
                    colorBox.setFocused(true);
                }, null));

        saveButton = null;
        for (RouteFolderEditLayout.ActionPlacement placement : layout.actions()) {
            addActionButton(placement);
        }
        refreshSaveState();
    }

    private void addActionButton(RouteFolderEditLayout.ActionPlacement placement) {
        Component label;
        Button.OnPress onPress;
        switch (placement.action()) {
            case CANCEL -> {
                label = Component.translatable("gui.cancel");
                onPress = button -> onClose();
            }
            case SAVE -> {
                label = Component.translatable("waypointer.common.save");
                onPress = button -> save();
            }
            case DELETE -> {
                label = Component.translatable("waypointer.screen.route_folder.delete");
                onPress = button -> confirmDeleteFolder();
            }
            default -> throw new IllegalStateException(
                    "Unexpected folder action: " + placement.action());
        }
        Button button = styledButton(
                placement.x(), placement.y(), placement.width(), BTN_H,
                label, onPress, null);
        if (placement.action() == RouteFolderEditLayout.Action.SAVE) {
            saveButton = button;
        }
        addRenderableWidget(button);
    }

    private void save() {
        String name = nameValue.trim();
        Integer color = parseColor(colorValue);
        if (name.isEmpty() || color == null) return;
        RouteFolder target = folder;
        if (target == null) {
            target = manager.createFolder(name, zoneId, eligibleSelectedIds(), color);
        } else {
            manager.renameFolder(target.id(), name);
            manager.setFolderColor(target.id(), color);
            for (String groupId : eligibleSelectedIds()) {
                manager.assignGroupToFolder(groupId, target.id());
            }
        }
        manager.setFolderCollapsed(target.id(), false);
        onClose();
    }

    private void confirmDeleteFolder() {
        if (folder == null) return;
        ConfirmScreen confirm = new ConfirmScreen(confirmed -> {
            MinecraftCompat.setScreen(
                    minecraft == null ? Minecraft.getInstance() : minecraft, this);
            if (confirmed) deleteFolder();
        }, Component.translatable("waypointer.screen.route_folder.confirm.title"),
                Component.translatable(
                        "waypointer.screen.route_folder.confirm.message", folder.name()),
                Component.translatable("waypointer.screen.route_folder.delete"),
                Component.translatable("gui.cancel"));
        MinecraftCompat.setScreen(
                minecraft == null ? Minecraft.getInstance() : minecraft, confirm);
    }

    private void deleteFolder() {
        manager.deleteFolder(folder.id());
        onClose();
    }

    private List<String> eligibleSelectedIds() {
        return selectedGroupIds.stream().filter(id -> {
            WaypointGroup group = manager.get(id);
            return group != null && !group.temp() && !group.runtimeOnly()
                    && group.routeKind() == WaypointGroup.RouteKind.REGULAR
                    && zoneId.equals(group.zoneId());
        }).toList();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(layout.panelX(), layout.panelY(),
                layout.panelX() + layout.panelWidth(), layout.panelBottom(), SURFACE);
        int previewColor = displayColor();
        graphics.fill(layout.panelX(), layout.panelY(),
                layout.panelX() + layout.panelWidth(), layout.panelY() + 3, previewColor);
        graphics.text(font, font.plainSubstrByWidth(
                        getTitle().getString(), layout.contentWidth()),
                layout.contentX(), layout.titleY(), TEXT, false);
        if (layout.detailVisible()) {
            int routeCount = folder == null
                    ? selectedGroupIds.size()
                    : manager.groupIdsInFolder(folder.id()).size();
            String detail = folder == null && routeCount == 0
                    ? Component.translatable(
                            "waypointer.screen.route_folder.empty_selection").getString()
                    : Component.translatable(
                            RouteListPresentation.folderRouteCountKey(routeCount),
                            routeCount).getString();
            graphics.text(font, font.plainSubstrByWidth(
                            detail, layout.contentWidth()),
                    layout.contentX(), layout.detailY(), TEXT_DIM, false);
            graphics.fill(layout.contentX(), layout.sectionDividerY(),
                    layout.contentRight(), layout.sectionDividerY() + 1, BORDER);
        }
        if (layout.fieldLabelsVisible()) {
            graphics.text(font, Component.translatable("waypointer.screen.route_folder.name"),
                    layout.contentX(), layout.nameLabelY(), TEXT_DIM, false);
            graphics.text(font, Component.translatable("waypointer.screen.route_folder.color"),
                    layout.contentX(), layout.colorLabelY(), TEXT_DIM, false);
        }
        if (parseColor(colorValue) == null && layout.validationVisible()) {
            String validation = Component.translatable(
                    "waypointer.screen.route_folder.color.invalid").getString();
            graphics.text(font, font.plainSubstrByWidth(validation, layout.contentWidth()),
                    layout.contentX(), layout.validationY(), DANGER, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft == null ? Minecraft.getInstance() : minecraft, parent);
    }

    private static String nextFolderName(ActiveGroupManager manager, String zoneId) {
        for (int i = 1; ; i++) {
            String candidate = defaultFolderName(i);
            boolean taken = manager.foldersForZone(zoneId).stream()
                    .anyMatch(folder -> folder.name().equalsIgnoreCase(candidate));
            if (!taken) return candidate;
        }
    }

    static String defaultFolderName(int index) {
        return Component.translatableWithFallback(
                "waypointer.screen.route_folder.default_name", "Folder %s", index).getString();
    }

    static Integer parseColor(String value) {
        if (value == null) return null;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6) return null;
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String formatColor(int color) {
        return String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private int displayColor() {
        Integer color = parseColor(colorValue);
        return 0xFF000000 | (color == null ? RouteFolder.DEFAULT_COLOR : color);
    }

    private void openColorPicker() {
        ColorPickerScreen.open(
                this,
                Component.translatable("waypointer.screen.route_folder.color_picker"),
                displayColor(),
                color -> colorBox.setValue(formatColor(color)));
    }

    private void refreshSaveState() {
        if (saveButton != null) {
            saveButton.active = !nameValue.trim().isEmpty() && parseColor(colorValue) != null;
        }
    }
}
