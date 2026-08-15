package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.i18n.LocalizedNumberFormatter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.HOVER;
import static com.babbur.waypointer.screen.GuiTokens.SELECTED;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;

final class CatalogRouteRowButton extends AbstractButton {
    private static final int STATUS_OK = GuiTokens.SUCCESS;
    private static final int ROW_TITLE_TOP = 6;
    private static final int ROW_META_TOP = 20;

    final CatalogRouteSummary route;
    private final boolean selected;
    private final boolean installed;
    private final Runnable onPress;

    CatalogRouteRowButton(
            int x, int y, int width, int height, CatalogRouteSummary route,
            boolean selected, boolean installed, Runnable onPress) {
        super(x, y, width, height, Component.literal(route.title()));
        this.route = route;
        this.selected = selected;
        this.installed = installed;
        this.onPress = onPress;
    }

    @Override
    public void onPress(net.minecraft.client.input.InputWithModifiers input) {
        onPress.run();
    }

    @Override
    protected void extractContents(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + getWidth();
        int y2 = y1 + getHeight();
        int background = selected ? SELECTED : isHoveredOrFocused() ? HOVER : 0;
        if (background != 0) graphics.fill(x1, y1, x2, y2, background);
        graphics.fill(x1, y2 - 1, x2, y2, BORDER);
        if (selected) graphics.fill(x1, y1, x1 + 2, y2, ACCENT);
        if (isFocused()) drawFocusBorder(graphics, x1, y1, x2, y2);

        var font = Minecraft.getInstance().font;
        int textX = x1 + GAP;
        int textWidth = Math.max(20, getWidth() - GAP * 2);
        String installedTag = installed ? Component.translatable(
                "waypointer.screen.route_catalog.row.installed_tag").getString() : "";
        String verifiedTag = route.publisherVerified() ? Component.translatable(
                "waypointer.screen.route_catalog.publisher.verified").getString().trim() : "";
        int installedTagW = font.width(installedTag);
        int verifiedTagW = font.width(verifiedTag);
        int stateW = installedTagW + verifiedTagW
                + (installedTagW > 0 && verifiedTagW > 0 ? GAP : 0);
        int titleAvailable = Math.max(20, textWidth - (stateW == 0 ? 0 : stateW + GAP));

        graphics.text(font, font.plainSubstrByWidth(route.title(), titleAvailable),
                textX, y1 + ROW_TITLE_TOP, TEXT, false);
        renderMetadata(graphics, textX, textWidth, x2, y1);
        renderStateTags(graphics, textX, x2, y1,
                installedTag, installedTagW, verifiedTag, verifiedTagW);
    }

    private static void drawFocusBorder(
            GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2) {
        graphics.fill(x1, y1, x2, y1 + 1, ACCENT);
        graphics.fill(x1, y2 - 1, x2, y2, ACCENT);
        graphics.fill(x1, y1, x1 + 1, y2, ACCENT);
        graphics.fill(x2 - 1, y1, x2, y2, ACCENT);
    }

    private void renderMetadata(
            GuiGraphicsExtractor graphics, int textX, int textWidth, int x2, int y1) {
        var font = Minecraft.getInstance().font;
        Component author = route.authorName().isBlank()
                ? Component.translatable("waypointer.screen.route_catalog.publisher.unknown")
                : Component.literal(route.authorName());
        int columnW = Math.max(1, textWidth / 3);
        graphics.text(font, font.plainSubstrByWidth(
                        route.zoneLabel(), Math.max(1, columnW - GAP_TIGHT)),
                textX, y1 + ROW_META_TOP, ACCENT, false);
        String stats = Component.translatable(
                "waypointer.screen.route_catalog.row.stats",
                compactWaypoints(route.waypointCount()),
                compactInstalls(route.downloads())).getString();
        graphics.text(font, font.plainSubstrByWidth(
                        stats, Math.max(1, columnW - GAP_TIGHT)),
                textX + columnW, y1 + ROW_META_TOP, TEXT_MUTED, false);
        String publisher = Component.translatable(
                "waypointer.screen.route_catalog.publisher.by", author).getString();
        String clippedPublisher = font.plainSubstrByWidth(
                publisher, Math.max(1, textWidth - columnW * 2));
        graphics.text(font, clippedPublisher,
                x2 - GAP - font.width(clippedPublisher), y1 + ROW_META_TOP,
                TEXT_MUTED, false);
    }

    private static void renderStateTags(
            GuiGraphicsExtractor graphics, int textX, int x2, int y1,
            String installedTag, int installedTagW,
            String verifiedTag, int verifiedTagW) {
        var font = Minecraft.getInstance().font;
        int stateX = x2 - GAP;
        if (installedTagW > 0) {
            graphics.text(font, installedTag,
                    stateX - installedTagW, y1 + ROW_TITLE_TOP, STATUS_OK, false);
            stateX -= installedTagW + GAP;
        }
        if (verifiedTagW > 0 && stateX - verifiedTagW >= textX + 20) {
            graphics.text(font, verifiedTag,
                    stateX - verifiedTagW, y1 + ROW_TITLE_TOP, ACCENT, false);
        }
    }

    /** Row copy stays compact: full numbers below 1000, then 1.2k / 3M. */
    static String compactCount(long value, LocalizedNumberFormatter numbers) {
        if (value < 1_000) return numbers.integer(Math.max(0, value));
        if (value < 1_000_000) return scaledCount(value, 1_000, "k", numbers);
        return scaledCount(value, 1_000_000, "M", numbers);
    }

    private static String scaledCount(
            long value, long unit, String suffix, LocalizedNumberFormatter numbers) {
        long tenths = value * 10 / unit;
        if (tenths >= 100 || tenths % 10 == 0) {
            return numbers.integer(tenths / 10) + suffix;
        }
        return numbers.oneDecimal(tenths / 10.0) + suffix;
    }

    private static Component compactWaypoints(long waypoints) {
        return Component.translatable(
                "waypointer.screen.route_catalog.row.waypoint_count",
                compactCount(waypoints, LocalizedNumberFormatter.active()));
    }

    private static Component compactInstalls(long downloads) {
        return Component.translatable(downloads == 1
                        ? "waypointer.screen.route_catalog.install_count.one"
                        : "waypointer.screen.route_catalog.install_count.many",
                compactCount(downloads, LocalizedNumberFormatter.active()));
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        Component author = route.authorName().isBlank()
                ? Component.translatable("waypointer.screen.route_catalog.publisher.unknown")
                : Component.literal(route.authorName());
        MutableComponent description = Component.translatable(
                "waypointer.screen.route_catalog.row.narration",
                route.title(), route.zoneLabel(),
                RouteCatalogScreen.waypointCount(route.waypointCount()), author);
        description.append(Component.translatable(
                "waypointer.screen.route_catalog.row.downloads_narration",
                RouteCatalogScreen.installCount(route.downloads())));
        if (route.publisherVerified()) {
            description.append(Component.translatable(
                    "waypointer.screen.route_catalog.publisher.verified"));
        }
        if (installed) {
            description.append(Component.translatable(
                    "waypointer.screen.route_catalog.row.installed_narration"));
        }
        return selected
                ? Component.translatable(
                        "waypointer.screen.route_catalog.row.narration.selected", description)
                : description;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
