package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.RouteIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static com.babbur.waypointer.screen.GuiTokens.*;

public final class RouteIconPickerScreen extends Screen {
    private static final int CELL = 20;
    private static final int MAX_W = 372;
    private static final int MAX_H = 320;
    private static final int PREVIEW = 36;

    static final List<String> SUGGESTED = List.of(
            "minecraft:diamond_pickaxe", "minecraft:iron_pickaxe", "minecraft:prismarine_shard",
            "minecraft:amethyst_shard", "minecraft:red_stained_glass", "minecraft:orange_stained_glass",
            "minecraft:lime_stained_glass", "minecraft:light_blue_stained_glass", "minecraft:yellow_stained_glass",
            "minecraft:packed_ice", "minecraft:clay_ball", "minecraft:emerald", "minecraft:diamond",
            "minecraft:gold_ingot", "minecraft:wither_skeleton_skull", "minecraft:bone", "minecraft:ender_eye",
            "minecraft:blaze_rod", "minecraft:fishing_rod", "minecraft:nether_wart", "minecraft:wheat",
            "minecraft:oak_sapling", "minecraft:chest", "minecraft:compass");

    private record Entry(Item item, String id, String search) {}

    private final Screen parent;
    private final String zoneId;
    private final Consumer<String> onPicked;
    private List<Entry> all = List.of();
    private List<Entry> shown = List.of();
    private String selectedId;
    private boolean glint;
    private int scroll;
    private int suggestedCount;

    private EditBox searchBox;
    private StyledCheckbox glintBox;
    private int panelX, panelY, panelW, panelH;
    private int gridX, gridY, gridW, gridH, cols;
    private Entry hovered;

    public RouteIconPickerScreen(Screen parent, String currentSpec, String zoneId, Consumer<String> onPicked) {
        super(Component.translatable("waypointer.screen.route_icon.title"));
        this.parent = parent;
        this.zoneId = zoneId;
        this.onPicked = onPicked;
        String current = RouteIcon.normalize(currentSpec);
        this.selectedId = current.isEmpty() ? null : RouteIcon.itemId(current);
        this.glint = RouteIcon.glint(current);
    }

    public static void open(Screen parent, String currentSpec, String zoneId, Consumer<String> onPicked) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new RouteIconPickerScreen(parent, currentSpec, zoneId, onPicked));
    }

    @Override
    protected void init() {
        if (all.isEmpty()) all = entries();
        panelW = Math.min(MAX_W, width - PAD_OUTER * 2);
        panelH = Math.min(MAX_H, height - PAD_OUTER * 2);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        int inner = panelX + PAD_OUTER;
        int innerW = panelW - PAD_OUTER * 2;

        int searchY = panelY + PAD_OUTER + PREVIEW + GAP;
        String query = searchBox == null ? "" : searchBox.getValue();
        searchBox = new EditBox(font, inner, searchY, innerW, BTN_H,
                Component.translatable("waypointer.screen.route_icon.search"));
        searchBox.setHint(Component.translatable("waypointer.screen.route_icon.search"));
        searchBox.setMaxLength(64);
        searchBox.setValue(query);
        searchBox.setResponder(value -> {
            scroll = 0;
            filter();
        });
        addRenderableWidget(searchBox);

        int footerY = panelY + panelH - PAD_OUTER - BTN_H;
        gridX = inner;
        gridY = searchY + BTN_H + GAP;
        gridW = innerW;
        gridH = Math.max(CELL, footerY - GAP - gridY);
        cols = Math.max(1, (gridW - 6) / CELL);

        Component shimmer = Component.translatable("waypointer.screen.route_icon.glint");
        glintBox = styledCheckbox(inner, footerY, BTN_H, shimmer, glint, on -> glint = on, null);
        addRenderableWidget(glintBox);

        int btnW = 64;
        Component zoneLabel = Component.translatable("waypointer.screen.route_icon.zone_default");
        int zoneW = font.width(zoneLabel) + 16;
        int right = panelX + panelW - PAD_OUTER;
        addRenderableWidget(styledButton(right - btnW, footerY, btnW, BTN_H,
                Component.translatable("waypointer.screen.color_picker.save"), b -> save(), null));
        addRenderableWidget(styledButton(right - btnW * 2 - GAP_TIGHT, footerY, btnW, BTN_H,
                Component.translatable("gui.cancel"), b -> onClose(), null));
        addRenderableWidget(styledButton(right - btnW * 2 - GAP_TIGHT * 2 - zoneW, footerY, zoneW, BTN_H,
                zoneLabel, b -> {
                    onPicked.accept(RouteIcon.NONE);
                    onClose();
                }, null));
        filter();
        setInitialFocus(searchBox);
    }

    private static List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        for (Item item : RouteIcons.allItems()) {
            String id = RouteIcons.id(item);
            String name = new ItemStack(item).getHoverName().getString();
            String path = id.startsWith("minecraft:") ? id.substring(10) : id;
            list.add(new Entry(item, id, (name + " " + path.replace('_', ' ') + " " + id).toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(list);
    }

    private void filter() {
        String query = searchBox == null ? "" : searchBox.getValue().strip().toLowerCase(Locale.ROOT);
        List<Entry> result = new ArrayList<>();
        suggestedCount = 0;
        if (query.isEmpty()) {
            for (String id : SUGGESTED) {
                for (Entry entry : all) {
                    if (entry.id().equals(id)) {
                        result.add(entry);
                        suggestedCount++;
                        break;
                    }
                }
            }
            for (Entry entry : all) {
                if (!SUGGESTED.contains(entry.id())) result.add(entry);
            }
        } else {
            String[] words = query.split("\\s+");
            for (Entry entry : all) {
                boolean match = true;
                for (String word : words) match &= entry.search().contains(word);
                if (match) result.add(entry);
            }
        }
        shown = result;
        scroll = Math.min(scroll, maxScroll());
    }

    private int suggestedRows() {
        boolean searching = searchBox != null && !searchBox.getValue().isBlank();
        return searching || suggestedCount == 0 ? 0 : (suggestedCount + cols - 1) / cols;
    }

    private int cell(int i) {
        int suggested = suggestedRows() > 0 ? suggestedCount : 0;
        return i < suggested ? i : i + suggestedRows() * cols - suggested;
    }

    private int contentHeight() {
        int rows = shown.isEmpty() ? 0 : cell(shown.size() - 1) / cols + 1;
        return rows * CELL + (suggestedRows() > 0 ? GAP : 0);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - gridH);
    }

    private int rowY(int row) {
        int gap = suggestedRows() > 0 && row >= suggestedRows() ? GAP : 0;
        return gridY + row * CELL + gap - scroll;
    }

    private String currentSpec() {
        return selectedId == null ? RouteIcon.NONE : RouteIcon.of(selectedId, glint);
    }

    private void save() {
        onPicked.accept(currentSpec());
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, GuiTokens.panel(SURFACE));

        int inner = panelX + PAD_OUTER;
        int top = panelY + PAD_OUTER;
        String spec = selectedId == null ? RouteIcon.zoneDefault(zoneId) : currentSpec();
        RouteIcons.slotted(g, spec, inner, top, PREVIEW, 32);
        int textX = inner + PREVIEW + GAP;
        g.text(font, title, textX, top + 5, TEXT, false);
        Component name = selectedId == null
                ? Component.translatable("waypointer.screen.route_icon.using_zone")
                : RouteIcons.stack(currentSpec()).getHoverName();
        g.text(font, GuiTokens.ellipsize(font, name.getString(), panelX + panelW - PAD_OUTER - textX),
                textX, top + 19, TEXT_DIM, false);

        hovered = null;
        g.fill(gridX, gridY, gridX + gridW, gridY + gridH, 0x28000000);
        g.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        int suggested = suggestedRows();
        if (suggested > 0) {
            int lineY = rowY(suggested) - GAP / 2 - 1;
            g.fill(gridX + 3, lineY, gridX + gridW - 9, lineY + 1, BORDER);
        }
        for (int i = 0; i < shown.size(); i++) {
            int cell = cell(i);
            int y = rowY(cell / cols) + 3;
            if (y > gridY + gridH) break;
            if (y + CELL < gridY) continue;
            int x = gridX + 3 + (cell % cols) * CELL;
            Entry entry = shown.get(i);
            boolean over = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL
                    && mouseY >= gridY && mouseY < gridY + gridH;
            boolean chosen = entry.id().equals(selectedId);
            if (chosen) {
                g.fill(x, y, x + CELL, y + CELL, 0x404FB3C4);
                outline(g, x, y, x + CELL, y + CELL, ACCENT);
            } else if (over) {
                g.fill(x, y, x + CELL, y + CELL, HOVER);
            }
            if (over) hovered = entry;
            RouteIcons.draw(g, chosen && glint ? entry.id() + "*" : entry.id(), x + 2, y + 2, 16);
        }
        g.disableScissor();
        if (shown.isEmpty()) {
            Component none = Component.translatable("waypointer.screen.route_icon.no_results");
            g.text(font, none, gridX + (gridW - font.width(none)) / 2, gridY + gridH / 2 - 4, TEXT_MUTED, false);
        }
        drawScrollbar(g);
        if (glintBox != null) {
            g.text(font, Component.translatable("waypointer.screen.route_icon.glint"),
                    glintBox.getX() + BTN_H + GAP_TIGHT, opticalTextY(glintBox.getY(), BTN_H), TEXT, false);
        }

        super.extractRenderState(g, mouseX, mouseY, partial);
        if (hovered != null) {
            g.setTooltipForNextFrame(font, List.of(
                    new ItemStack(hovered.item()).getHoverName().getVisualOrderText(),
                    Component.literal(hovered.id()).withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
                            .getVisualOrderText()), mouseX, mouseY);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        int max = maxScroll();
        if (max <= 0) return;
        int trackX = gridX + gridW - 4;
        int thumbH = Math.max(16, gridH * gridH / contentHeight());
        int thumbY = gridY + (gridH - thumbH) * scroll / max;
        g.fill(trackX, gridY + 2, trackX + 2, gridY + gridH - 2, 0x18FFFFFF);
        g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x60FFFFFF);
    }

    private static void outline(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && hovered != null) {
            boolean same = hovered.id().equals(selectedId);
            selectedId = hovered.id();
            if (doubleClick && same) save();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(vertical * CELL * 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }
}
