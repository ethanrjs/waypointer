package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogInstallState;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.i18n.LocalizedNumberFormatter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;

/** One community route in the list: title and badges, then zone, publisher and size. */
final class CatalogRouteRowButton extends AbstractButton {
    static final int HEIGHT = 32;
    private static final int TITLE_TOP = 6;
    private static final int META_TOP = 18;

    /** Mining Cult purple. */
    static final int MINING_CULT = 0xFF9B6BFF;
    private static final int MINING_CULT_FILL = 0xFF3A2470;
    private static final int MINING_CULT_TEXT = 0xFFEADFFF;

    final CatalogRouteSummary route;
    private final boolean selected;
    private final CatalogInstallState.Action installAction;
    private final Runnable onPress;

    CatalogRouteRowButton(
            int x, int y, int width, int height, CatalogRouteSummary route,
            boolean selected, CatalogInstallState.Action installAction, Runnable onPress) {
        super(x, y, width, height, Component.literal(route.title()));
        this.route = route;
        this.selected = selected;
        this.installAction = installAction;
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
        graphics.fill(x1 + GAP, y2 - 1, x2 - GAP, y2, BORDER);
        if (selected) graphics.fill(x1, y1, x1 + 2, y2, ACCENT);
        if (isFocused() && !selected) graphics.fill(x1, y1, x1 + 1, y2, ACCENT);

        Font font = Minecraft.getInstance().font;
        int textX = x1 + GAP;
        int right = x2 - GAP;

        // Line 1: title, Mining Cult badge, install state on the right.
        int stateW = drawInstallState(graphics, font, installAction, right, y1 + TITLE_TOP, true);
        int titleRight = right - (stateW > 0 ? stateW + GAP : 0);
        int badgeW = route.hasTag(CatalogRouteSummary.TAG_MINING_CULT) ? miningCultWidth(font) + GAP_TIGHT : 0;
        String title = GuiTokens.ellipsize(font, route.title(), Math.max(20, titleRight - textX - badgeW));
        graphics.text(font, title, textX, y1 + TITLE_TOP, TEXT, false);
        if (badgeW > 0) {
            int pillX = textX + font.width(title) + GAP_TIGHT;
            drawMiningCult(graphics, font, pillX, y1 + TITLE_TOP);
            miningCultTooltip(graphics, font, pillX, y1 + TITLE_TOP, mouseX, mouseY);
        }

        // Line 2: zone · publisher (✓), size on the right.
        String stats = Component.translatable("waypointer.screen.route_catalog.row.stats",
                compactWaypoints(route.waypointCount()), compactInstalls(route.downloads())).getString();
        int statsW = font.width(stats);
        int metaRight = right - statsW - GAP;
        if (metaRight - textX < 60) {
            statsW = 0;
            metaRight = right;
        } else {
            graphics.text(font, stats, right - statsW, y1 + META_TOP, TEXT_MUTED, false);
        }
        drawZoneAndPublisher(graphics, font, route, textX, y1 + META_TOP, metaRight - textX);
    }

    /** Zone in the accent color, then "by publisher" and a check for verified publishers. */
    static void drawZoneAndPublisher(GuiGraphicsExtractor graphics, Font font, CatalogRouteSummary route,
                                     int x, int y, int maxWidth) {
        String zone = GuiTokens.ellipsize(font, route.zoneLabel(), Math.max(20, maxWidth * 3 / 5));
        graphics.text(font, zone, x, y, ACCENT, false);
        int used = font.width(zone);
        String separator = "  ·  ";
        String publisher = Component.translatable("waypointer.screen.route_catalog.publisher.by",
                route.authorName().isBlank()
                        ? Component.translatable("waypointer.screen.route_catalog.publisher.unknown")
                        : Component.literal(route.authorName())).getString();
        int checkW = route.publisherVerified() ? 9 : 0;
        int room = maxWidth - used - font.width(separator) - checkW;
        if (room < 24) return;
        graphics.text(font, separator, x + used, y, TEXT_MUTED, false);
        used += font.width(separator);
        String shown = GuiTokens.ellipsize(font, publisher, room);
        graphics.text(font, shown, x + used, y, TEXT_DIM, false);
        used += font.width(shown);
        if (checkW > 0) Icons.drawAt(graphics, Icons.Icon.BADGE_CHECK, x + used + 6, y + 4, ACCENT);
    }

    private static final int PILL_PAD = 3;
    private static final int LOGO_W = 11;

    static int miningCultWidth(Font font) {
        return PILL_PAD + LOGO_W + PILL_PAD + font.width(Component.translatable(
                "waypointer.screen.route_catalog.tag.mining_cult.short")) + PILL_PAD;
    }

    /** The purple Mining Cult pill: the Discord logo and "MC", sitting on a text baseline at (x, y). */
    static int drawMiningCult(GuiGraphicsExtractor graphics, Font font, int x, int y) {
        String label = Component.translatable("waypointer.screen.route_catalog.tag.mining_cult.short").getString();
        int w = miningCultWidth(font);
        graphics.fill(x, y - 2, x + w, y + 9, MINING_CULT_FILL);
        graphics.fill(x, y - 2, x + w, y - 1, MINING_CULT);
        graphics.fill(x, y + 8, x + w, y + 9, MINING_CULT);
        graphics.fill(x, y - 2, x + 1, y + 9, MINING_CULT);
        graphics.fill(x + w - 1, y - 2, x + w, y + 9, MINING_CULT);
        // The 11x8 glyph sits 2 px into its 16 px cell, so its cell centre is 6 px right of its left edge.
        Icons.drawAt(graphics, Icons.Icon.BADGE_DISCORD, x + PILL_PAD + 6, y + 4, MINING_CULT_TEXT);
        graphics.text(font, label, x + PILL_PAD + LOGO_W + PILL_PAD, y, MINING_CULT_TEXT, false);
        return w;
    }

    /** Shows where Mining Cult routes come from when the pointer is on the pill. */
    static void miningCultTooltip(GuiGraphicsExtractor graphics, Font font, int pillX, int y, int mouseX, int mouseY) {
        if (mouseX < pillX || mouseX >= pillX + miningCultWidth(font) || mouseY < y - 2 || mouseY >= y + 9) return;
        graphics.setTooltipForNextFrame(font, font.split(Component.translatable(
                "waypointer.screen.route_catalog.tag.mining_cult.hover"), 200), mouseX, mouseY);
    }

    /**
     * Draws the install state right-aligned at {@code right} and returns its width:
     * a check for installed routes, "Update" when the catalog has a newer version.
     */
    static int drawInstallState(GuiGraphicsExtractor graphics, Font font, CatalogInstallState.Action action,
                                int right, int y, boolean compact) {
        if (action == null) return 0;
        String key;
        int color;
        switch (action) {
            case INSTALLED, LOCAL_NEWER -> {
                key = "waypointer.screen.route_catalog.state.installed";
                color = GuiTokens.SUCCESS;
            }
            case UPDATE -> {
                key = "waypointer.screen.route_catalog.state.update";
                color = GuiTokens.WARNING;
            }
            case REPAIR -> {
                key = "waypointer.screen.route_catalog.state.repair";
                color = GuiTokens.WARNING;
            }
            default -> {
                return 0;
            }
        }
        String text = Component.translatable(key).getString();
        if (compact && (action == CatalogInstallState.Action.INSTALLED
                || action == CatalogInstallState.Action.LOCAL_NEWER)) {
            Icons.drawAt(graphics, Icons.Icon.BADGE_CHECK, right - 4, y + 3, color);
            return 8;
        }
        int w = font.width(text);
        graphics.text(font, text, right - w, y, color, false);
        return w;
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
        if (route.hasTag(CatalogRouteSummary.TAG_MINING_CULT)) {
            description.append(Component.literal(". ")).append(Component.translatable(
                    "waypointer.screen.route_catalog.tag.mining_cult"));
        }
        if (installAction == CatalogInstallState.Action.INSTALLED
                || installAction == CatalogInstallState.Action.LOCAL_NEWER) {
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
