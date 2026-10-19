package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.api.ImportSummary;
import com.babbur.waypointer.catalog.CatalogInstallState;
import com.babbur.waypointer.catalog.CatalogRouteDetails;
import com.babbur.waypointer.catalog.CatalogRouteInstaller;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.catalog.InstallTokenStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.codec.CatalogShareLink;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.ChatFormatting;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;

public final class CatalogRouteInstallScreen extends Screen {

    private static final int PANEL_W = 560;
    private static final int PANEL_H = 320;
    private static final int PANEL_MARGIN = 12;
    private static final int LINE_H = 11;
    private static final int SIDE_BY_SIDE_W = 420;
    private static final int HEADER_ICON = 36;
    private static final int STATUS_OK = GuiTokens.SUCCESS;
    private static final int STATUS_ERROR = GuiTokens.DANGER;
    private static final int MAX_REFERENCE_PROBE_CHARS = 256;

    private enum Phase { LOADING, READY, INSTALLING, INSTALLED, FAILED }

    private final Screen parent;
    private final RouteCatalogClient catalogClient;
    private final ActiveGroupManager manager;
    private final String routeId;

    private Phase phase = Phase.LOADING;
    private boolean requested;
    private boolean screenActive;
    private long fetchGeneration;
    private CatalogRouteInstaller.PreparedRoute prepared;
    private CatalogRoutePreview preview;
    private CatalogInstallState installState;
    private Component status = Component.empty();
    private int statusColor = TEXT_DIM;

    private Button installButton;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int statusY;
    private int footerY;
    private int descriptionScroll;
    private int descriptionMaxScroll;
    private int descriptionX1, descriptionY1, descriptionX2, descriptionY2;

    CatalogRouteInstallScreen(Screen parent, RouteCatalogClient catalogClient,
                              ActiveGroupManager manager, String routeId) {
        super(Component.translatable("waypointer.screen.catalog_install.title"));
        this.parent = parent;
        this.catalogClient = Objects.requireNonNull(catalogClient, "catalogClient");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.routeId = Objects.requireNonNull(routeId, "routeId");
    }

    public static void open(Screen parent, String routeId) {
        open(parent, RouteCatalogClient.production(), WaypointerClient.manager(), routeId);
    }

    static void open(Screen parent, RouteCatalogClient catalogClient,
                     ActiveGroupManager manager, String routeId) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new CatalogRouteInstallScreen(parent, catalogClient, manager, routeId));
    }

    public static String referenceRouteId(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        String candidate = stripMarkdownCodeFence(trimmed);
        if (candidate.length() > MAX_REFERENCE_PROBE_CHARS) return null;

        Optional<String> linkedRoute = CatalogShareLink.routeIdFromLink(candidate);
        if (linkedRoute.isPresent()) {
            String routeId = linkedRoute.get();
            return RouteCatalogClient.isValidRouteId(routeId) ? routeId : null;
        }

        if (!WaypointCodec.isCodecString(candidate)) return null;
        try {
            UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(candidate);
            if (!(decoded instanceof UniversalShareCodec.CatalogReference reference)) return null;
            return RouteCatalogClient.isValidRouteId(reference.routeId())
                    ? reference.routeId() : null;
        } catch (RuntimeException notAShare) {
            return null;
        }
    }

    private static String stripMarkdownCodeFence(String text) {
        if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) {
            return text;
        }
        int bodyStart = 3;
        int newline = text.indexOf('\n', bodyStart);
        if (newline >= 0) bodyStart = newline + 1;
        String body = text.substring(bodyStart, text.length() - 3).strip();
        return body.isEmpty() ? text : body;
    }

    @Override
    protected void init() {
        screenActive = true;
        panelW = Math.min(PANEL_W, Math.max(240, width - PANEL_MARGIN * 2));
        panelH = Math.min(PANEL_H, Math.max(160, height - PANEL_MARGIN * 2));
        panelX = Math.max(0, (width - panelW) / 2);
        panelY = Math.max(0, (height - panelH) / 2);
        contentX = panelX + PAD_OUTER;
        contentW = Math.max(1, panelW - PAD_OUTER * 2);
        footerY = panelY + panelH - PAD_OUTER - BTN_H;
        statusY = footerY - LINE_H - GAP_TIGHT;

        addRenderableWidget(styledButton(contentX, footerY, 64, BTN_H,
                Component.translatable("gui.back"), button -> onClose(), null));

        int actionW = Math.min(88, Math.max(56, (contentW - 64 - GAP * 3) / 3));
        installButton = styledButton(contentX + contentW - actionW, footerY, actionW, BTN_H,
                installLabel(), button -> install(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_catalog.install.tooltip")));
        addRenderableWidget(installButton);
        addRenderableWidget(styledButton(contentX + contentW - actionW * 2 - GAP, footerY,
                actionW, BTN_H,
                Component.translatable("waypointer.screen.catalog_install.action.copy_link"),
                button -> copy(CatalogShareLink.forRouteId(routeId),
                        "waypointer.screen.catalog_install.status.copied_link"),
                null));
        addRenderableWidget(styledButton(contentX + contentW - actionW * 3 - GAP * 2, footerY,
                actionW, BTN_H,
                Component.translatable("waypointer.screen.catalog_install.action.copy_code"),
                button -> copy(UniversalShareCodec.encodeCatalogReference(routeId),
                        "waypointer.screen.catalog_install.status.copied_code"),
                null));
        refreshInstallButton();
        if (!requested) fetch();
    }

    private void fetch() {
        requested = true;
        long generation = ++fetchGeneration;
        try {
            CompletableFuture<CatalogRouteDetails> route = catalogClient.getRoute(routeId);
            route.thenApplyAsync(details -> {
                        CatalogRouteInstaller.PreparedRoute ready = CatalogRouteInstaller.prepare(details);
                        return new Loaded(ready, CatalogRoutePreview.of(ready.previewGroups()));
                    })
                    .whenComplete((result, failure) -> runOnClient(() -> onLoaded(
                            generation, result, failure)));
        } catch (RuntimeException failure) {
            onLoaded(generation, null, failure);
        }
    }

    private record Loaded(CatalogRouteInstaller.PreparedRoute route, CatalogRoutePreview preview) {}

    private void onLoaded(long generation, Loaded result, Throwable failure) {
        if (!screenActive || generation != fetchGeneration) return;
        if (failure != null || result == null) {
            phase = Phase.FAILED;
            if (!RouteCatalogClient.isValidRouteId(routeId)) {
                status = Component.translatable(
                        "waypointer.screen.catalog_install.error.unsupported_reference");
            } else {
                if (failure != null) {
                    Waypointer.LOGGER.warn(
                            "Could not open catalog route {}", routeId, failure);
                }
                status = failure == null
                        ? Component.translatable("waypointer.screen.route_catalog.error.request_failed")
                        : RouteCatalogScreen.friendlyFailure(failure);
            }
            statusColor = STATUS_ERROR;
        } else {
            prepared = result.route();
            preview = result.preview();
            phase = Phase.READY;
            refreshInstallState();
            if ("unlisted".equals(prepared.summary().visibility())) {
                status = Component.translatable("waypointer.screen.catalog_install.unlisted");
                statusColor = TEXT_MUTED;
            } else {
                status = Component.empty();
            }
        }
        refreshInstallButton();
    }

    private void refreshInstallState() {
        installState = prepared == null ? null : CatalogInstallState.inspect(
                catalogClient.apiRoot(), prepared.summary(), manager.allGroups());
    }

    private boolean canInstall() {
        return phase == Phase.READY && installState != null && installState.canInstall();
    }

    private void install() {
        if (!canInstall()) return;
        phase = Phase.INSTALLING;
        refreshInstallButton();
        ImportSummary summary;
        try {
            summary = CatalogRouteInstaller.install(manager, catalogClient.apiRoot(), prepared);
        } catch (RuntimeException failure) {
            Waypointer.LOGGER.warn("Could not install catalog route {}", routeId, failure);
            phase = Phase.READY;
            refreshInstallState();
            status = RouteCatalogScreen.friendlyFailure(failure);
            statusColor = STATUS_ERROR;
            refreshInstallButton();
            return;
        }
        phase = Phase.INSTALLED;
        refreshInstallState();
        status = Component.translatable("waypointer.screen.catalog_install.status.installed",
                prepared.summary().title());
        statusColor = STATUS_OK;
        refreshInstallButton();
        catalogClient.recordInstall(routeId, null, InstallTokenStore.shared().tokenFor(routeId));
        WaypointGroup focus = RouteCatalogScreen.installedFocus(manager, summary);
        if (focus != null) {
            WaypointerScreen.openFocused(manager, WaypointerClient.config(), focus);
        }
    }

    private void copy(String text, String statusKey) {
        if (minecraft == null) return;
        minecraft.keyboardHandler.setClipboard(text);
        status = Component.translatable(statusKey);
        statusColor = STATUS_OK;
    }

    private Component installLabel() {
        if (phase == Phase.INSTALLING) {
            return Component.translatable("waypointer.screen.catalog_install.action.installing");
        }
        CatalogInstallState.Action action = installState == null
                ? CatalogInstallState.Action.INSTALL : installState.action();
        return Component.translatable(switch (action) {
            case INSTALLED, LOCAL_NEWER -> "waypointer.screen.route_catalog.installed";
            case UPDATE -> "waypointer.screen.route_catalog.update";
            case REPAIR -> "waypointer.screen.route_catalog.repair";
            default -> "waypointer.screen.catalog_install.action.install";
        });
    }

    private void refreshInstallButton() {
        if (installButton == null) return;
        installButton.setMessage(installLabel());
        installButton.active = canInstall();
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, BORDER);

        CatalogRoutePreview.Point hovered = null;
        CatalogRouteSummary summary = prepared == null ? null : prepared.summary();
        int top = panelY + PAD_OUTER;
        boolean statusShown = !status.getString().isBlank();
        int bottom = statusShown ? statusY - GAP : footerY - GAP;
        descriptionMaxScroll = 0;
        if (summary == null) {
            if (phase == Phase.LOADING) {
                drawCentered(graphics, Component.translatable("waypointer.screen.catalog_install.loading"),
                        top, bottom, TEXT_DIM);
            }
        } else {
            int y = renderHeader(graphics, summary, top, mouseX, mouseY);
            hovered = renderBody(graphics, summary, y, bottom, mouseX, mouseY);
        }

        if (statusShown) {
            boolean centered = phase == Phase.FAILED && summary == null;
            String shown = GuiTokens.ellipsize(font, status.getString(), contentW);
            if (centered) {
                drawCentered(graphics, Component.literal(shown), top, bottom, statusColor);
            } else {
                graphics.text(font, shown, contentX, statusY, statusColor, false);
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, CatalogRoutePreview.tooltip(hovered).stream()
                    .map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    private int renderHeader(GuiGraphicsExtractor graphics, CatalogRouteSummary summary, int y,
                             int mouseX, int mouseY) {
        int right = contentX + contentW;
        int iconTop = y - 1;
        RouteIcons.slotted(graphics, summary.displayIcon(), contentX, iconTop, HEADER_ICON, 32);
        int x = contentX + HEADER_ICON + GAP;
        int available = Math.max(20, right - x);
        boolean miningCult = summary.hasTag(CatalogRouteSummary.TAG_MINING_CULT);
        int badgeW = miningCult ? CatalogRouteRowButton.miningCultWidth(font) + GAP : 0;
        Component title = Component.literal(GuiTokens.ellipsize(font, summary.title(), available - badgeW))
                .withStyle(ChatFormatting.BOLD);
        graphics.text(font, title, x, y, TEXT, false);
        if (miningCult) {
            int pillX = right - badgeW + GAP;
            CatalogRouteRowButton.drawMiningCult(graphics, font, pillX, y);
            CatalogRouteRowButton.miningCultTooltip(graphics, font, pillX, y, mouseX, mouseY);
        }
        y += LINE_H + 3;
        CatalogRouteRowButton.drawZoneAndPublisher(graphics, font, summary, x, y, available);
        y += LINE_H + 1;
        String facts = RouteCatalogScreen.detailFacts(summary);
        String shownFacts = GuiTokens.ellipsize(font, facts, available);
        graphics.text(font, shownFacts, x, y, TEXT_MUTED, false);
        RouteCatalogScreen.dateTooltip(graphics, font, summary,
                facts.equals(shownFacts) ? x + font.width(facts) : -1, y, mouseX, mouseY);
        y = Math.max(y + LINE_H, iconTop + HEADER_ICON);
        Component stateLine = installState == null ? null
                : RouteCatalogScreen.installStateLine(installState, summary);
        if (stateLine != null) {
            y += GAP_TIGHT;
            boolean attention = installState.action() == CatalogInstallState.Action.UPDATE
                    || installState.action() == CatalogInstallState.Action.REPAIR;
            graphics.text(font, GuiTokens.ellipsize(font, stateLine.getString(), contentW), contentX, y,
                    attention ? GuiTokens.WARNING : GuiTokens.SUCCESS, false);
            y += LINE_H;
        }
        return y + GAP;
    }

    private CatalogRoutePreview.Point renderBody(GuiGraphicsExtractor graphics, CatalogRouteSummary summary,
                                                 int top, int bottom, int mouseX, int mouseY) {
        int right = contentX + contentW;
        CatalogRoutePreview.Point hovered = null;
        if (bottom - top < LINE_H) return null;
        if (contentW >= SIDE_BY_SIDE_W) {
            int mapRight = contentX + contentW * 11 / 20;
            if (preview != null && bottom - top >= 40) {
                hovered = preview.render(graphics, font, contentX, top, mapRight, bottom, mouseX, mouseY);
            }
            drawDescription(graphics, summary, mapRight + GAP + 2, top, right, bottom);
        } else {
            int mapH = Math.min(160, (bottom - top) * 55 / 100);
            if (preview != null && mapH >= 40) {
                hovered = preview.render(graphics, font, contentX, top, right, top + mapH, mouseX, mouseY);
                top += mapH + GAP;
            }
            drawDescription(graphics, summary, contentX, top, right, bottom);
        }
        return hovered;
    }

    private void drawDescription(GuiGraphicsExtractor graphics, CatalogRouteSummary summary,
                                 int x1, int y1, int x2, int y2) {
        int height = (y2 - y1) / LINE_H * LINE_H;
        descriptionX1 = x1;
        descriptionY1 = y1;
        descriptionX2 = x2;
        descriptionY2 = y1 + height;
        if (height < LINE_H) return;
        String description = summary.description() == null ? "" : summary.description().strip();
        Component body = description.isEmpty()
                ? Component.translatable("waypointer.screen.route_catalog.description.empty")
                : Component.literal(description);
        List<FormattedCharSequence> lines = font.split(body, Math.max(1, x2 - x1 - GAP));
        int contentHeight = lines.size() * LINE_H;
        descriptionMaxScroll = Math.max(0, contentHeight - height);
        descriptionScroll = Math.min(descriptionScroll, descriptionMaxScroll);
        graphics.enableScissor(x1, y1, x2, y1 + height);
        for (int i = descriptionScroll / LINE_H; i < lines.size(); i++) {
            int lineY = y1 + i * LINE_H - descriptionScroll;
            if (lineY >= y1 + height) break;
            graphics.text(font, lines.get(i), x1, lineY, description.isEmpty() ? TEXT_MUTED : TEXT_DIM, false);
        }
        graphics.disableScissor();
        int[] thumb = RouteCatalogScreen.dropdownThumb(height, contentHeight, descriptionScroll, descriptionMaxScroll);
        if (thumb != null) {
            graphics.fill(x2 - 2, y1 + thumb[0], x2, y1 + thumb[0] + thumb[1], TEXT_MUTED);
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, Component text, int top, int bottom, int color) {
        String shown = GuiTokens.ellipsize(font, text.getString(), contentW);
        graphics.text(font, shown, contentX + (contentW - font.width(shown)) / 2,
                top + (bottom - top - 8) / 2, color, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (descriptionMaxScroll > 0 && mouseX >= descriptionX1 && mouseX < descriptionX2
                && mouseY >= descriptionY1 && mouseY < descriptionY2) {
            descriptionScroll = Math.clamp(descriptionScroll - (int) Math.round(scrollY * LINE_H * 2),
                    0, descriptionMaxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void runOnClient(Runnable action) {
        Minecraft client = minecraft == null ? Minecraft.getInstance() : minecraft;
        client.execute(action);
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public void removed() {
        screenActive = false;
        if (phase == Phase.LOADING) {
            requested = false;
            fetchGeneration++;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
