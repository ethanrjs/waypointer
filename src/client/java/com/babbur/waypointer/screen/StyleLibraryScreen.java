package com.babbur.waypointer.screen;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointStyle;
import com.babbur.waypointer.crystal.CrystalHollowsProjection;
import com.babbur.waypointer.crystal.CrystalHollowsStructure;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.*;

/** Personal reusable styles and persistent structure-type overrides. */
public final class StyleLibraryScreen extends Screen {
    private final Screen parent;
    private final WaypointerConfig config;
    private final boolean structures;
    private String selected = "";
    private int page;
    private int left, top, contentWidth, bottom;
    private EditBox name;
    private String draftName = "";

    public StyleLibraryScreen(Screen parent, WaypointerConfig config, boolean structures) {
        super(Component.translatable(structures
                ? "waypointer.screen.styles.structures" : "waypointer.screen.styles.title"));
        this.parent = parent;
        this.config = config;
        this.structures = structures;
        if (structures) selected = CrystalHollowsStructure.values()[0].id();
    }

    @Override
    protected void init() {
        contentWidth = Math.min(560, Math.max(200, width - PAD_OUTER * 2));
        left = (width - contentWidth) / 2;
        top = PAD_OUTER;
        bottom = height - PAD_OUTER;
        int rowsTop = top + BTN_H + GAP;
        if (!structures) {
            name = new EditBox(font, left, rowsTop, contentWidth - 80 - GAP, BTN_H,
                    Component.translatable("waypointer.screen.styles.name"));
            name.setMaxLength(64);
            name.setValue(draftName);
            name.setHint(Component.translatable("waypointer.screen.styles.name"));
            addRenderableWidget(name);
            var create = styledButton(left + contentWidth - 80, rowsTop, 80, BTN_H,
                    Component.translatable("waypointer.screen.styles.create"), b -> createStyle(), null);
            create.active = !draftName.isBlank() && !config.namedStyles().containsKey(draftName.trim());
            name.setResponder(value -> {
                draftName = value;
                create.active = !value.isBlank() && !config.namedStyles().containsKey(value.trim());
            });
            addRenderableWidget(create);
            rowsTop += BTN_H + GAP;
        }
        int footer = bottom - BTN_H;
        int actions = footer - BTN_H - GAP;
        int pager = actions - BTN_H - GAP;
        int perPage = Math.max(1, (pager - GAP - rowsTop) / (BTN_H + GAP_TIGHT));
        List<String> ids = ids();
        int maxPage = Math.max(0, (ids.size() - 1) / perPage);
        page = Math.clamp(page, 0, maxPage);
        for (int i = page * perPage; i < Math.min(ids.size(), (page + 1) * perPage); i++) {
            String id = ids.get(i);
            String label = label(id);
            if (!structures && id.equals(config.preferredStyleId())) label += " ("
                    + Component.translatable("waypointer.screen.styles.preferred").getString() + ")";
            final String text = label;
            var row = styledButton(left, rowsTop + (i % perPage) * (BTN_H + GAP_TIGHT),
                    contentWidth, BTN_H, Component.literal(text), b -> {
                        selected = id;
                        rebuildWidgets();
                    }, null);
            if (id.equals(selected)) row.setMessage(colored(Component.literal(text), ACCENT));
            addRenderableWidget(row);
        }
        var previous = styledButton(left, pager, 80, BTN_H, Component.translatable("waypointer.screen.styles.previous"), b -> {
            page--;
            rebuildWidgets();
        }, null);
        previous.active = page > 0;
        addRenderableWidget(previous);
        var next = styledButton(left + contentWidth - 80, pager, 80, BTN_H, Component.translatable("waypointer.screen.styles.next"), b -> {
            page++;
            rebuildWidgets();
        }, null);
        next.active = page < maxPage;
        addRenderableWidget(next);
        int actionWidth = (contentWidth - GAP * 2) / 3;
        var edit = styledButton(left, actions, actionWidth, BTN_H,
                Component.translatable("waypointer.screen.styles.edit"), b -> editSelected(), null);
        edit.active = structures || config.namedStyles().containsKey(selected);
        addRenderableWidget(edit);
        var use = styledButton(left + actionWidth + GAP, actions, actionWidth, BTN_H,
                Component.translatable(structures ? "waypointer.screen.styles.inherit"
                        : "waypointer.screen.styles.prefer"), b -> {
                    if (structures) saveStructureStyle(selected, WaypointStyle.EMPTY);
                    else config.setPreferredStyleId(selected);
                    rebuildWidgets();
                }, null);
        addRenderableWidget(use);
        var delete = styledButton(left + 2 * (actionWidth + GAP), actions, actionWidth, BTN_H,
                Component.translatable("waypointer.screen.styles.delete"), b -> confirmDelete(), null);
        delete.active = !structures && config.namedStyles().containsKey(selected);
        delete.visible = !structures;
        addRenderableWidget(delete);
        addRenderableWidget(styledButton(left, footer, contentWidth, BTN_H,
                Component.translatable("gui.back"), b -> onClose(), null));
    }

    private List<String> ids() {
        if (structures) return java.util.Arrays.stream(CrystalHollowsStructure.values())
                .map(CrystalHollowsStructure::id).toList();
        List<String> result = new ArrayList<>();
        result.add("");
        result.addAll(config.namedStyles().keySet());
        return result;
    }

    private String label(String id) {
        if (!structures) return id.isEmpty()
                ? Component.translatable("waypointer.screen.styles.global").getString() : id;
        for (var structure : CrystalHollowsStructure.values()) {
            if (structure.id().equals(id)) return structure.displayName();
        }
        return id;
    }

    private void createStyle() {
        String id = name.getValue().trim();
        if (id.isEmpty() || config.namedStyles().containsKey(id)) return;
        WaypointAppearanceScreen.openStyle(this, config, WaypointStyle.EMPTY, style -> {
                    config.putNamedStyle(id, style);
                    selected = id;
                    draftName = "";
                }, id);
    }

    private void editSelected() {
        if (!structures && !config.namedStyles().containsKey(selected)) return;
        String id = selected;
        WaypointStyle initial = structures ? config.crystalHollowsStyle(id) : config.namedStyles().get(id);
        WaypointerConfig inherited = structures ? WaypointStyles.resolve(config, null, null) : config;
        WaypointAppearanceScreen.openStyle(this, inherited, initial, style -> {
            if (structures) saveStructureStyle(id, style);
            else config.putNamedStyle(id, style);
        }, label(id));
    }

    private void saveStructureStyle(String id, WaypointStyle style) {
        config.setCrystalHollowsStyle(id, style);
        var manager = WaypointerClient.manager();
        if (manager == null) return;
        CrystalHollowsProjection.refreshStyles(manager, config);
    }

    private void confirmDelete() {
        String id = selected;
        if (!config.namedStyles().containsKey(id)) return;
        MinecraftCompat.setScreen(minecraft, new ConfirmScreen(confirmed -> {
            if (confirmed) {
                config.removeNamedStyle(id);
                selected = "";
            }
            MinecraftCompat.setScreen(minecraft, this);
        }, Component.translatable("waypointer.screen.styles.delete"),
                Component.translatable("waypointer.screen.styles.delete_confirm", id)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, OVERLAY);
        graphics.text(font, getTitle(), left, top, TEXT, false);
        super.extractRenderState(graphics, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() { MinecraftCompat.setScreen(minecraft, parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
