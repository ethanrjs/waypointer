package com.babbur.waypointer.screen;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.api.ImportSummary;
import com.babbur.waypointer.catalog.CatalogInstallState;
import com.babbur.waypointer.catalog.CatalogRouteInstaller;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.catalog.InstallTokenStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.codec.CatalogShareLink;
import com.babbur.waypointer.codec.UniversalShareCodec;
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

import java.util.List;
import java.util.Objects;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
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

/**
 * Preview-and-install screen for one catalog route reached by reference: a
 * {@code WP:} catalog code or a {@code waypointermod.com/r/} link from chat,
 * the clipboard, or {@code /wp import}. Nothing is added until Install is
 * pressed, and unlisted routes work exactly like listed ones because the
 * catalog is asked for the route by id.
 */
public final class CatalogRouteInstallScreen extends Screen {

    private static final int PANEL_W = 400;
    private static final int PANEL_H = 236;
    private static final int PANEL_MARGIN = 12;
    private static final int LINE_H = 10;
    private static final int DESCRIPTION_LINES = 5;
    private static final int STATUS_OK = GuiTokens.SUCCESS;
    private static final int STATUS_ERROR = GuiTokens.DANGER;

    private enum Phase { LOADING, READY, INSTALLING, INSTALLED, FAILED }

    private final Screen parent;
    private final RouteCatalogClient catalogClient;
    private final ActiveGroupManager manager;
    private final String routeId;

    private Phase phase = Phase.LOADING;
    private boolean requested;
    private boolean screenActive;
    private CatalogRouteInstaller.PreparedRoute prepared;
    private boolean alreadyInstalled;
    private Component status = Component.translatable("waypointer.screen.catalog_install.loading");
    private int statusColor = TEXT_DIM;

    private Button installButton;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int bodyY;
    private int statusY;

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

    /** Route id when {@code text} is a catalog reference code or share link, else null. */
    public static String referenceRouteId(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(text);
            return decoded instanceof UniversalShareCodec.CatalogReference reference
                    ? reference.routeId() : null;
        } catch (RuntimeException notAShare) {
            return null;
        }
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
        bodyY = panelY + PAD_OUTER + LINE_H + GAP;
        int footerY = panelY + panelH - PAD_OUTER - BTN_H;
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
        catalogClient.getRoute(routeId)
                .thenApplyAsync(CatalogRouteInstaller::prepare)
                .whenComplete((result, failure) -> runOnClient(() -> onLoaded(result, failure)));
    }

    private void onLoaded(CatalogRouteInstaller.PreparedRoute result, Throwable failure) {
        if (!screenActive) return;
        if (failure != null || result == null) {
            phase = Phase.FAILED;
            status = failure == null
                    ? Component.translatable("waypointer.screen.route_catalog.error.request_failed")
                    : RouteCatalogScreen.friendlyFailure(failure);
            statusColor = STATUS_ERROR;
        } else {
            prepared = result;
            CatalogInstallState installState = CatalogInstallState.inspect(
                    catalogClient.apiRoot(), result.summary(), manager.allGroups());
            alreadyInstalled = !installState.canInstall();
            phase = Phase.READY;
            if (alreadyInstalled) {
                status = Component.translatable(
                        "waypointer.screen.catalog_install.status.already_installed");
                statusColor = TEXT_DIM;
            } else if ("unlisted".equals(result.summary().visibility())) {
                status = Component.translatable("waypointer.screen.catalog_install.unlisted");
                statusColor = TEXT_MUTED;
            } else {
                status = Component.empty();
            }
        }
        refreshInstallButton();
    }

    private void install() {
        if (phase != Phase.READY || prepared == null || alreadyInstalled) return;
        phase = Phase.INSTALLING;
        refreshInstallButton();
        ImportSummary summary;
        try {
            summary = CatalogRouteInstaller.install(manager, catalogClient.apiRoot(), prepared);
        } catch (RuntimeException failure) {
            phase = Phase.READY;
            alreadyInstalled = failure instanceof IllegalStateException;
            status = alreadyInstalled
                    ? Component.translatable(
                            "waypointer.screen.catalog_install.status.already_installed")
                    : RouteCatalogScreen.friendlyFailure(failure);
            statusColor = alreadyInstalled ? TEXT_DIM : STATUS_ERROR;
            refreshInstallButton();
            return;
        }
        phase = Phase.INSTALLED;
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
        return Component.translatable(phase == Phase.INSTALLING
                ? "waypointer.screen.catalog_install.action.installing"
                : "waypointer.screen.catalog_install.action.install");
    }

    private void refreshInstallButton() {
        if (installButton == null) return;
        installButton.setMessage(installLabel());
        installButton.active = phase == Phase.READY && !alreadyInstalled;
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, BORDER);
        graphics.text(font, font.plainSubstrByWidth(getTitle().getString(), contentW),
                contentX, panelY + PAD_OUTER, TEXT, false);

        int y = bodyY;
        CatalogRouteSummary summary = prepared == null ? null : prepared.summary();
        if (summary == null) {
            if (phase == Phase.LOADING) {
                graphics.text(font, Component.translatable(
                                "waypointer.screen.catalog_install.loading").getString(),
                        contentX, y, TEXT_DIM, false);
            }
        } else {
            graphics.text(font, font.plainSubstrByWidth(summary.title(), contentW),
                    contentX, y, ACCENT, false);
            y += LINE_H + GAP_TIGHT;
            String author = summary.authorName() == null ? "" : summary.authorName();
            if (!author.isBlank()) {
                graphics.text(font, font.plainSubstrByWidth(Component.translatable(
                        summary.publisherVerified()
                                ? "waypointer.screen.catalog_install.by_verified"
                                : "waypointer.screen.catalog_install.by",
                        author).getString(), contentW), contentX, y, TEXT_DIM, false);
                y += LINE_H;
            }
            graphics.text(font, font.plainSubstrByWidth(Component.translatable(
                    "waypointer.screen.catalog_install.summary",
                    RouteCatalogScreen.waypointCount(summary.waypointCount()),
                    groupCount(summary.groupCount()),
                    summary.zoneLabel()).getString(), contentW), contentX, y, TEXT_MUTED, false);
            y += LINE_H + GAP;
            String description = summary.description() == null ? "" : summary.description().trim();
            Component body = description.isEmpty()
                    ? Component.translatable("waypointer.screen.catalog_install.no_description")
                    : Component.literal(description);
            int maxLines = Math.max(1, Math.min(DESCRIPTION_LINES, (statusY - GAP - y) / LINE_H));
            List<FormattedCharSequence> lines = font.split(body, contentW);
            for (int index = 0; index < Math.min(lines.size(), maxLines); index++) {
                graphics.text(font, lines.get(index), contentX, y,
                        description.isEmpty() ? TEXT_MUTED : TEXT, false);
                y += LINE_H;
            }
        }

        if (!status.getString().isBlank()) {
            graphics.text(font, font.plainSubstrByWidth(status.getString(), contentW),
                    contentX, statusY, statusColor, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private static Component groupCount(long value) {
        return Component.translatable(value == 1
                ? "waypointer.screen.route_catalog.group_count.one"
                : "waypointer.screen.route_catalog.group_count.many", value);
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
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
