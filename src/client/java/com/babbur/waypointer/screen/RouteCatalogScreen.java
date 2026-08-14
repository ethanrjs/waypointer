package com.babbur.waypointer.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.api.ImportSummary;
import com.babbur.waypointer.api.WaypointerApi;
import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogApiException;
import com.babbur.waypointer.catalog.CatalogInstallRegistry;
import com.babbur.waypointer.catalog.CatalogRouteDetails;
import com.babbur.waypointer.catalog.CatalogRouteInstaller;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.i18n.LocalizedNumberFormatter;
import com.babbur.waypointer.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.HOVER;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.SELECTED;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;

public final class RouteCatalogScreen extends Screen {
    private static final int PANEL_MARGIN = 12;
    private static final int LINE_H = 10;
    private static final int DETAIL_LINE_H = 12;
    private static final int ROW_H = 36;
    private static final int ROW_PITCH = ROW_H;
    private static final int WIDE_BREAKPOINT = 640;
    private static final int TOOLBAR_STACK_BREAKPOINT = 260;
    private static final int REFRESH_W = 72;
    private static final int LOAD_MORE_W = 108;
    private static final int BACK_W = 64;
    private static final int PRIMARY_W = 84;
    private static final int SCROLLBAR_W = 3;
    private static final int SCROLLBAR_INSET = 2;
    private static final int DETAIL_HEADER_H = 32;
    private static final int SEARCH_DEBOUNCE_TICKS = 7;
    private static final long MANUAL_REFRESH_COOLDOWN_NANOS = 10_000_000_000L;

    private static final int STATUS_OK = 0xFF7ACB89;
    private static final int STATUS_ERROR = 0xFFE47B7B;

    private static final int ROW_TITLE_TOP = 6;
    private static final int ROW_META_TOP = ROW_H - LINE_H - 6;

    private final Screen parent;
    private final RouteCatalogClient catalogClient;
    private final WaypointerApi waypointerApi;
    private final CatalogInstallRegistry installRegistry;

    private List<CatalogRouteSummary> routes = List.of();
    private final Set<String> installedRouteIds;
    private String searchQuery = "";
    private String nextCursor;
    private String selectedRouteId;
    private int scrollOffset;
    private int requestGeneration;
    private int detailGeneration;
    private long installAttempt;
    private boolean listRequested;
    private boolean listLoading;
    private boolean appending;
    private boolean detailLoading;
    private boolean initializing;
    private boolean screenActive;
    private boolean lastLoadFailed;
    private Component statusText = Component.empty();
    private int statusColor = TEXT_DIM;
    private int searchDebounceTicks = -1;
    private boolean manualRefreshCooldownArmed;
    private long manualRefreshAllowedAtNanos;
    private int displayedRefreshCooldownSeconds = -1;

    private EditBox searchBox;
    private Button refreshButton;
    private Button loadMoreButton;
    private Button installButton;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int detailX;
    private int detailY;
    private int detailW;
    private int detailH;
    private int statusX;
    private int statusY;
    private int statusMaxW;

    public RouteCatalogScreen(Screen parent, RouteCatalogClient catalogClient) {
        this(parent, catalogClient, WaypointerClient.api(),
                CatalogInstallRegistry.defaultLocation());
    }

    RouteCatalogScreen(
            Screen parent, RouteCatalogClient catalogClient, WaypointerApi waypointerApi,
            CatalogInstallRegistry installRegistry) {
        super(Component.translatable("waypointer.screen.route_catalog.title"));
        this.parent = parent;
        this.catalogClient = catalogClient;
        this.waypointerApi = waypointerApi;
        this.installRegistry = installRegistry;
        this.installedRouteIds = new HashSet<>(installRegistry.load());
    }

    public static void open(Screen parent) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new RouteCatalogScreen(parent, RouteCatalogClient.production()));
    }

    @Override
    protected void init() {
        screenActive = true;
        initializing = true;

        panelX = width > PANEL_MARGIN * 2 ? PANEL_MARGIN : 0;
        panelY = height > PANEL_MARGIN * 2 ? PANEL_MARGIN : 0;
        panelW = Math.max(1, width - panelX * 2);
        panelH = Math.max(1, height - panelY * 2);
        int panelPad = panelW >= 320 ? PAD_OUTER : GAP;
        int contentX = panelX + panelPad;
        int contentW = Math.max(40, panelW - panelPad * 2);
        int contentRight = contentX + contentW;

        int titleY = panelY + panelPad;
        int subtitleY = titleY + LINE_H;
        int searchY = subtitleY + LINE_H + GAP;
        boolean toolbarStacked = contentW < TOOLBAR_STACK_BREAKPOINT;
        int bodyY = searchY + BTN_H + (toolbarStacked ? BTN_H + GAP_TIGHT : 0) + GAP;
        int footerY = panelY + panelH - panelPad - BTN_H;
        statusY = footerY - LINE_H - GAP_TIGHT;
        statusX = contentX;
        statusMaxW = contentW;
        int bodyH = Math.max(60, statusY - GAP - bodyY);
        boolean wide = contentW >= WIDE_BREAKPOINT;

        if (wide) {
            listX = contentX;
            listY = bodyY;
            listW = Math.max(240, (contentW - GAP_SECTION) * 44 / 100);
            listH = bodyH;
            detailX = listX + listW + GAP_SECTION;
            detailY = bodyY;
            detailW = contentRight - detailX;
            detailH = bodyH;
        } else {
            listX = contentX;
            listY = bodyY;
            listW = contentW;
            listH = Math.max(ROW_H, (bodyH - GAP_SECTION) * 62 / 100);
            detailX = contentX;
            detailY = listY + listH + GAP_SECTION;
            detailW = contentW;
            detailH = Math.max(32, statusY - GAP - detailY);
        }

        Component refreshLabel = refreshButtonLabel(System.nanoTime());
        int refreshW = Math.min(contentW, Math.max(REFRESH_W, font.width(refreshLabel) + 16));
        int searchW = toolbarStacked ? contentW : Math.max(40, contentW - refreshW - GAP);
        searchBox = new EditBox(font, contentX, searchY, searchW, BTN_H,
                Component.translatable("waypointer.screen.route_catalog.search"));
        searchBox.setMaxLength(80);
        searchBox.setHint(Component.translatable(
                "waypointer.screen.route_catalog.search.hint"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(value -> {
            if (initializing) return;
            String next = value == null ? "" : value;
            if (next.equals(searchQuery)) return;
            searchQuery = next;
            requestGeneration++;
            searchDebounceTicks = SEARCH_DEBOUNCE_TICKS;
            statusText = Component.translatable(
                    "waypointer.screen.route_catalog.status.search_wait");
            statusColor = TEXT_DIM;
        });
        addRenderableWidget(searchBox);

        int refreshY = toolbarStacked ? searchY + BTN_H + GAP_TIGHT : searchY;
        refreshButton = styledButton(contentRight - refreshW, refreshY, refreshW, BTN_H,
                refreshLabel,
                button -> requestManualRefresh(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_catalog.refresh.tooltip")));
        refreshButton.active = refreshCooldownSeconds(System.nanoTime()) == 0 && !listLoading;
        addRenderableWidget(refreshButton);

        addRouteRows();

        addRenderableWidget(styledButton(contentX, footerY, BACK_W, BTN_H,
                Component.translatable("gui.back"), button -> onClose(), null));
        int installW = Math.min(Math.max(PRIMARY_W, font.width(installButtonLabel()) + 16),
                Math.max(PRIMARY_W, contentW - BACK_W - GAP_SECTION));
        installButton = styledButton(contentRight - installW, footerY, installW, BTN_H,
                installButtonLabel(),
                button -> installSelectedRoute(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_catalog.install.tooltip")));
        refreshPrimaryButtons();
        addRenderableWidget(installButton);

        initializing = false;
        if (!listRequested) {
            refreshCatalog();
        } else {
            setInitialFocus(searchBox);
        }
    }

    private void addRouteRows() {
        List<CatalogRouteSummary> filtered = routes;
        int visibleRows = visibleRowCount();
        int maximumStart = Math.max(0, filtered.size() - visibleRows);
        scrollOffset = MathUtil.clamp(scrollOffset, 0, maximumStart);
        boolean scrollable = filtered.size() > visibleRows;
        int rowW = scrollable ? listW - SCROLLBAR_W - SCROLLBAR_INSET * 2 : listW;
        for (int row = 0; row < visibleRows; row++) {
            int index = scrollOffset + row;
            if (index >= filtered.size()) break;
            CatalogRouteSummary route = filtered.get(index);
            int y = listY + row * ROW_PITCH;
            CatalogRowButton button = new CatalogRowButton(
                    listX, y, rowW, ROW_H, route,
                    route.id().equals(selectedRouteId),
                    installedRouteIds.contains(route.id()),
                    () -> selectRoute(route));
            if (!route.description().isBlank()) {
                button.setTooltip(Tooltip.create(Component.literal(route.description())));
            }
            addRenderableWidget(button);
        }
        if (nextCursor != null || appending) {
            int buttonW = Math.min(LOAD_MORE_W, Math.max(60, listW - GAP * 2));
            loadMoreButton = styledButton(
                    listX + (listW - buttonW) / 2,
                    listY + listH - BTN_H - GAP_TIGHT,
                    buttonW, BTN_H,
                    Component.translatable(appending
                            ? "waypointer.screen.route_catalog.load_more.loading"
                            : "waypointer.screen.route_catalog.load_more"),
                    button -> loadMore(),
                    Tooltip.create(Component.translatable(
                            "waypointer.screen.route_catalog.load_more.tooltip")));
            loadMoreButton.active = !listLoading && !appending && nextCursor != null;
            addRenderableWidget(loadMoreButton);
        }
    }

    private void refreshCatalog() {
        searchDebounceTicks = -1;
        requestPage(null, false);
    }

    private void requestManualRefresh() {
        if (listLoading) return;
        long now = System.nanoTime();
        int remaining = refreshCooldownSeconds(now);
        if (remaining > 0) {
            statusText = Component.translatable(
                    "waypointer.screen.route_catalog.refresh.cooldown", formatCount(remaining));
            statusColor = TEXT_DIM;
            refreshPrimaryButtons();
            return;
        }
        manualRefreshCooldownArmed = true;
        manualRefreshAllowedAtNanos = now + MANUAL_REFRESH_COOLDOWN_NANOS;
        displayedRefreshCooldownSeconds = -1;
        refreshCatalog();
    }

    private void loadMore() {
        if (nextCursor == null || listLoading || appending) return;
        requestPage(nextCursor, true);
    }

    private void requestPage(String cursor, boolean append) {
        int generation = ++requestGeneration;
        listRequested = true;
        listLoading = true;
        appending = append;
        statusText = Component.translatable(append
                ? "waypointer.screen.route_catalog.status.loading_more"
                : "waypointer.screen.route_catalog.status.loading");
        statusColor = TEXT_DIM;
        if (!append) {
            detailGeneration++;
            routes = List.of();
            nextCursor = null;
            selectedRouteId = null;
            scrollOffset = 0;
        }
        rebuildWidgets();

        String query = searchQuery.trim();
        catalogClient.listRoutes(query, null, cursor)
                .whenComplete((page, failure) -> runOnClient(() -> {
            if (!screenActive || generation != requestGeneration) return;
            listLoading = false;
            appending = false;
            if (failure != null) {
                lastLoadFailed = true;
                statusText = friendlyFailure(failure);
                statusColor = STATUS_ERROR;
                if (!append) routes = List.of();
            } else {
                lastLoadFailed = false;
                applyPage(page, append);
            }
            rebuildWidgets();
        }));
    }

    private void applyPage(CatalogPage page, boolean append) {
        if (append) {
            LinkedHashMap<String, CatalogRouteSummary> combined = new LinkedHashMap<>();
            for (CatalogRouteSummary route : routes) combined.put(route.id(), route);
            for (CatalogRouteSummary route : page.routes()) combined.put(route.id(), route);
            routes = List.copyOf(combined.values());
        } else {
            routes = page.routes();
        }
        nextCursor = page.hasMore() && page.nextCursor() != null
                && !page.nextCursor().isBlank() ? page.nextCursor() : null;
        if (routes.isEmpty()) {
            statusText = Component.translatable(searchQuery.isBlank()
                    ? "waypointer.screen.route_catalog.empty"
                    : "waypointer.screen.route_catalog.empty.search");
            statusColor = TEXT_DIM;
        } else if (nextCursor != null) {
            statusText = Component.translatable(
                    "waypointer.screen.route_catalog.status.count_more",
                    formatCount(routes.size()));
            statusColor = TEXT_MUTED;
        } else {
            statusText = routeCount(routes.size());
            statusColor = TEXT_MUTED;
        }
        reconcileSelection();
    }

    private void selectRoute(CatalogRouteSummary route) {
        if (route.id().equals(selectedRouteId)) return;
        detailGeneration++;
        selectedRouteId = route.id();
        statusText = Component.translatable(
                "waypointer.screen.route_catalog.status.selection_help");
        statusColor = TEXT_DIM;
        rebuildWidgets();
    }

    private void installSelectedRoute() {
        CatalogRouteSummary route = selectedRoute();
        if (route == null || detailLoading) return;
        if (installedRouteIds.contains(route.id())) {
            statusText = Component.translatable(
                    "waypointer.screen.route_catalog.status.already_installed");
            statusColor = TEXT_DIM;
            refreshPrimaryButtons();
            return;
        }

        int generation = ++detailGeneration;
        long attempt = ++installAttempt;
        String requestedRouteId = route.id();
        detailLoading = true;
        statusText = Component.translatable(
                "waypointer.screen.route_catalog.status.preparing_install");
        statusColor = TEXT_DIM;
        rebuildWidgets();
        catalogClient.getRoute(route.id())
                .thenApply(details -> validateDetails(route, details))
                .thenApplyAsync(CatalogRouteInstaller::prepare)
                .whenComplete((prepared, failure) -> runOnClient(() ->
                        finishInstall(attempt, generation, route,
                                prepared, failure)));
    }

    private void finishInstall(
            long attempt,
            int generation,
            CatalogRouteSummary requestedRoute,
            CatalogRouteInstaller.PreparedRoute prepared,
            Throwable preparationFailure) {
        if (attempt != installAttempt) return;
        detailLoading = false;

        String requestedRouteId = requestedRoute.id();
        boolean currentRequest = screenActive
                && generation == detailGeneration
                && sameInstallTarget(requestedRoute, selectedRoute());
        if (!currentRequest) {
            if (screenActive) refreshPrimaryButtons();
            return;
        }

        ImportSummary summary = null;
        Throwable failure = preparationFailure;
        if (failure == null) {
            try {
                summary = CatalogRouteInstaller.install(waypointerApi, prepared);
            } catch (RuntimeException installFailure) {
                failure = installFailure;
            }
        }

        if (failure != null) {
            statusText = friendlyFailure(failure);
            statusColor = STATUS_ERROR;
        } else {
            installedRouteIds.add(requestedRouteId);
            try {
                installRegistry.record(requestedRouteId);
            } catch (RuntimeException registryFailure) {
                Waypointer.LOGGER.warn(
                        "Route installed, but its catalog ID could not be saved",
                        registryFailure);
            }
            showInstalled(summary);
            catalogClient.recordInstall(requestedRouteId);
            WaypointGroup focus = installedFocus(WaypointerClient.manager(), summary);
            if (focus != null) {
                WaypointerScreen.openFocused(
                        WaypointerClient.manager(), WaypointerClient.config(), focus);
                return;
            }
        }
        rebuildWidgets();
    }

    private static boolean sameInstallTarget(
            CatalogRouteSummary requested, CatalogRouteSummary selected) {
        return requested != null
                && selected != null
                && requested.id().equals(selected.id())
                && requested.version() == selected.version()
                && requested.codecVersion() == selected.codecVersion()
                && requested.groupCount() == selected.groupCount()
                && requested.waypointCount() == selected.waypointCount()
                && requested.zoneId().equals(selected.zoneId());
    }

    private void showInstalled(ImportSummary summary) {
        Component groups = groupCount(summary.groupCount());
        Component waypoints = waypointCount(summary.waypointCount());
        statusText = Component.translatable(
                "waypointer.screen.route_catalog.status.installed", groups, waypoints);
        statusColor = STATUS_OK;
    }

    static WaypointGroup installedFocus(
            ActiveGroupManager manager, ImportSummary summary) {
        if (manager == null || summary == null) return null;
        for (String groupId : summary.groupIds()) {
            WaypointGroup group = manager.get(groupId);
            if (group != null) return group;
        }
        return null;
    }

    private CatalogRouteDetails validateDetails(
            CatalogRouteSummary requested, CatalogRouteDetails details) {
        CatalogRouteSummary actual = details.summary();
        if (!requested.id().equals(actual.id())
                || requested.groupCount() != actual.groupCount()
                || requested.waypointCount() != actual.waypointCount()
                || requested.codecVersion() != actual.codecVersion()
                || requested.version() != actual.version()
                || !requested.zoneId().equals(actual.zoneId())) {
            throw new IllegalArgumentException(
                    Component.translatable(
                            "waypointer.screen.route_catalog.error.route_changed").getString());
        }
        return details;
    }

    private void reconcileSelection() {
        if (selectedRouteId == null) return;
        if (routes.stream().noneMatch(route -> route.id().equals(selectedRouteId))) {
            selectedRouteId = null;
        }
    }

    private CatalogRouteSummary selectedRoute() {
        if (selectedRouteId == null) return null;
        for (CatalogRouteSummary route : routes) {
            if (route.id().equals(selectedRouteId)) return route;
        }
        return null;
    }

    private void refreshPrimaryButtons() {
        boolean hasSelection = selectedRoute() != null && !detailLoading;
        if (installButton != null) {
            installButton.active = hasSelection
                    && !installedRouteIds.contains(selectedRouteId);
            installButton.setMessage(installButtonLabel());
        }
        if (refreshButton != null) {
            long now = System.nanoTime();
            int seconds = refreshCooldownSeconds(now);
            refreshButton.active = !listLoading && seconds == 0;
            refreshButton.setMessage(refreshButtonLabel(now));
        }
        if (loadMoreButton != null) {
            loadMoreButton.active = !listLoading && !appending && nextCursor != null;
        }
    }

    private int visibleRowCount() {
        return Math.max(1, routeRowsHeight() / ROW_PITCH);
    }

    private int routeRowsHeight() {
        return Math.max(ROW_H, listH - (nextCursor != null || appending
                ? BTN_H + GAP : 0));
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);

        int panelPad = panelW >= 320 ? PAD_OUTER : GAP;
        int contentX = panelX + panelPad;
        int contentW = Math.max(40, panelW - panelPad * 2);
        int titleY = panelY + panelPad;
        graphics.text(font, getTitle(), contentX, titleY, TEXT, false);
        graphics.text(font, font.plainSubstrByWidth(
                        Component.translatable(
                                "waypointer.screen.route_catalog.subtitle").getString(), contentW),
                contentX, titleY + LINE_H, TEXT_MUTED, false);

        graphics.fill(listX, listY, listX + listW, listY + listH, SURFACE_SUBTLE);
        renderListState(graphics);
        renderListScrollbar(graphics);

        graphics.fill(detailX, detailY, detailX + detailW, detailY + detailH,
                SURFACE_SUBTLE);
        renderDetails(graphics, selectedRoute());

        if (!statusText.getString().isBlank()) {
            String clipped = font.plainSubstrByWidth(statusText.getString(), statusMaxW);
            graphics.text(font, clipped, statusX, statusY, statusColor, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partial);
    }

    private void renderListState(GuiGraphicsExtractor graphics) {
        if (listLoading) {
            drawCentered(graphics,
                    Component.translatable("waypointer.screen.route_catalog.status.loading"),
                    listX, listY, listW, routeRowsHeight(), TEXT_DIM);
            return;
        }
        if (!routes.isEmpty()) return;

        if (lastLoadFailed) {
            drawCentered(graphics, Component.translatable(
                            "waypointer.screen.route_catalog.empty.error"),
                    listX, listY, listW, listH, TEXT_MUTED);
            return;
        }
        Component empty = Component.translatable(searchQuery.isBlank()
                ? "waypointer.screen.route_catalog.empty"
                : "waypointer.screen.route_catalog.empty.search");
        drawCentered(graphics, empty, listX, listY, listW, listH, TEXT_DIM);
        if (!searchQuery.isBlank()) {
            drawCenteredLine(graphics, Component.translatable(
                            "waypointer.screen.route_catalog.empty.search_hint"),
                    listX, listW, listY + listH / 2 + LINE_H, TEXT_MUTED);
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, Component text,
                              int x, int y, int w, int h, int color) {
        drawCenteredLine(graphics, text, x, w, y + (h - LINE_H) / 2, color);
    }

    private void drawCenteredLine(GuiGraphicsExtractor graphics, Component text,
                                  int x, int w, int y, int color) {
        String clipped = font.plainSubstrByWidth(
                text.getString(), Math.max(20, w - GAP * 2));
        graphics.text(font, clipped, x + (w - font.width(clipped)) / 2, y, color, false);
    }

    private void renderListScrollbar(GuiGraphicsExtractor graphics) {
        int total = routes.size();
        int visible = visibleRowCount();
        if (total <= visible) return;
        int trackX = listX + listW - SCROLLBAR_W - SCROLLBAR_INSET;
        int trackY = listY + SCROLLBAR_INSET;
        int trackH = Math.max(1, listH - SCROLLBAR_INSET * 2);
        int thumbH = Math.max(10, trackH * visible / total);
        int travel = Math.max(0, trackH - thumbH);
        int max = Math.max(1, total - visible);
        int thumbY = trackY + travel * scrollOffset / max;
        graphics.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, 0x40000000);
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, TEXT_MUTED);
    }

    private void renderDetails(GuiGraphicsExtractor graphics, CatalogRouteSummary route) {
        int x = detailX + GAP;
        int y = detailY + GAP;
        int available = Math.max(20, detailW - GAP * 2);
        if (route == null) {
            drawCentered(graphics,
                    Component.translatable(listLoading
                            ? "waypointer.screen.route_catalog.details.loading"
                            : "waypointer.screen.route_catalog.details.select"),
                    detailX, detailY, detailW, detailH, TEXT_DIM);
            return;
        }

        int headerBottom = Math.min(detailY + detailH, detailY + DETAIL_HEADER_H);
        graphics.fill(detailX, detailY, detailX + detailW, headerBottom, SELECTED);
        graphics.fill(detailX, detailY, detailX + detailW, detailY + 1, ACCENT);

        graphics.text(font, font.plainSubstrByWidth(route.title(), available),
                x, y, TEXT, false);

        Component author = route.authorName().isBlank()
                ? Component.translatable("waypointer.screen.route_catalog.publisher.unknown")
                : Component.literal(route.authorName());
        String publisher = Component.translatable(
                "waypointer.screen.route_catalog.publisher.by", author).getString();
        int publisherX = x;
        int publisherY = y + LINE_H + 2;
        String verified = route.publisherVerified()
                ? Component.translatable(
                        "waypointer.screen.route_catalog.publisher.verified").getString().trim()
                : "";
        int verifiedW = verified.isEmpty() ? 0 : font.width(verified) + GAP_TIGHT;
        graphics.text(font, font.plainSubstrByWidth(
                        publisher, Math.max(20, available - verifiedW)),
                publisherX, publisherY, TEXT_DIM, false);
        if (!verified.isEmpty()) {
            graphics.text(font, verified, detailX + detailW - GAP - font.width(verified),
                    publisherY, ACCENT, false);
        }

        int statsY = detailY + detailH - GAP - LINE_H;
        int statsDividerY = statsY - GAP;
        boolean showStats = statsDividerY >= detailY + DETAIL_HEADER_H + LINE_H;
        if (showStats) {
            graphics.fill(x, statsDividerY, x + available, statsDividerY + 1, BORDER);
            int columnW = Math.max(1, available / 3);
            renderDetailStat(graphics, route.zoneLabel(), x, statsY, columnW, ACCENT);
            renderDetailStat(graphics, waypointCount(route.waypointCount()).getString(),
                    x + columnW, statsY, columnW, TEXT_DIM);
            renderDetailStat(graphics, installCount(route.downloads()).getString(),
                    x + columnW * 2, statsY, available - columnW * 2, TEXT_DIM);
        }

        int bodyY = detailY + DETAIL_HEADER_H + GAP;
        int bodyBottom = showStats ? statsDividerY - GAP : detailY + detailH - GAP;
        int bodyMaxH = Math.max(0, bodyBottom - bodyY);
        if (bodyMaxH >= DETAIL_LINE_H) {
            Component body = route.description().isBlank()
                    ? Component.translatable(
                            "waypointer.screen.route_catalog.description.empty")
                    : Component.literal(route.description());
            drawWrapped(graphics, body, x, bodyY, available, bodyMaxH,
                    route.description().isBlank() ? TEXT_MUTED : TEXT_DIM);
        }
    }

    private void renderDetailStat(
            GuiGraphicsExtractor graphics, String value, int x, int y, int width, int color) {
        int innerW = Math.max(1, width - GAP_TIGHT);
        graphics.text(font, font.plainSubstrByWidth(value, innerW), x, y, color, false);
    }

    private void drawWrapped(GuiGraphicsExtractor graphics, Component text,
                             int x, int y, int width, int maxHeight, int color) {
        int lines = Math.max(1, maxHeight / DETAIL_LINE_H);
        List<net.minecraft.util.FormattedCharSequence> split = font.split(
                text, width);
        int shown = split.size() <= lines ? split.size() : Math.max(0, lines - 1);
        int cursor = y;
        for (int i = 0; i < shown; i++) {
            graphics.text(font, split.get(i), x, cursor, color, false);
            cursor += DETAIL_LINE_H;
        }
        if (shown < split.size()) {
            String more = pluralCount(
                    "waypointer.screen.route_catalog.more_lines.one",
                    "waypointer.screen.route_catalog.more_lines.many",
                    split.size() - shown).getString();
            graphics.text(font, font.plainSubstrByWidth(more, width),
                    x, cursor, TEXT_MUTED, false);
        }
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX < listX || mouseX > listX + listW
                || mouseY < listY || mouseY > listY + listH) {
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        int maximum = Math.max(0, routes.size() - visibleRowCount());
        int next = MathUtil.clamp(scrollOffset - (int) Math.signum(vertical), 0, maximum);
        if (next == scrollOffset) return false;
        scrollOffset = next;
        rebuildWidgets();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW_KEY_F && controlDown() && searchBox != null) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if (event.key() == GLFW_KEY_F5) {
            requestManualRefresh();
            return true;
        }
        if (event.key() == GLFW_KEY_DOWN) return moveSelection(1);
        if (event.key() == GLFW_KEY_UP) return moveSelection(-1);
        boolean enter = event.key() == GLFW_KEY_ENTER || event.key() == GLFW_KEY_KP_ENTER;
        if (enter && selectedRoute() != null && !detailLoading && !unselectedRowFocused()) {
            installSelectedRoute();
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean moveSelection(int delta) {
        if (routes.isEmpty()) return false;
        int current = -1;
        for (int index = 0; index < routes.size(); index++) {
            if (routes.get(index).id().equals(selectedRouteId)) {
                current = index;
                break;
            }
        }
        int next = current < 0
                ? (delta > 0 ? 0 : routes.size() - 1)
                : MathUtil.clamp(current + delta, 0, routes.size() - 1);
        int priorScroll = scrollOffset;
        scrollIntoView(next, routes.size());
        if (next != current) {
            selectRoute(routes.get(next));
        } else if (scrollOffset != priorScroll) {
            rebuildWidgets();
        }
        return true;
    }

    private void scrollIntoView(int index, int total) {
        int visible = visibleRowCount();
        int maximum = Math.max(0, total - visible);
        int start = MathUtil.clamp(scrollOffset, 0, maximum);
        if (index < start) {
            start = index;
        } else if (index >= start + visible) {
            start = index - visible + 1;
        }
        scrollOffset = MathUtil.clamp(start, 0, maximum);
    }

    private boolean unselectedRowFocused() {
        return getFocused() instanceof CatalogRowButton row
                && !row.route.id().equals(selectedRouteId);
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public void removed() {
        screenActive = false;
        requestGeneration++;
        detailGeneration++;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void runOnClient(Runnable action) {
        Minecraft client = minecraft == null ? Minecraft.getInstance() : minecraft;
        client.execute(action);
    }

    @Override
    public void tick() {
        super.tick();
        if (searchDebounceTicks >= 0 && searchDebounceTicks-- == 0) refreshCatalog();
        int seconds = refreshCooldownSeconds(System.nanoTime());
        if (seconds != displayedRefreshCooldownSeconds) {
            displayedRefreshCooldownSeconds = seconds;
            if (seconds == 0) manualRefreshCooldownArmed = false;
            refreshPrimaryButtons();
        }
    }

    @Override
    public Component getNarrationMessage() {
        Component context;
        CatalogRouteSummary selected = selectedRoute();
        if (selected == null) {
            context = statusText.getString().isBlank()
                    ? Component.translatable("waypointer.screen.route_catalog.narration.browse")
                    : statusText;
        } else {
            context = Component.translatable(
                    "waypointer.screen.route_catalog.narration.selected",
                    selected.title(), waypointCount(selected.waypointCount()));
        }
        return Component.translatable(
                "waypointer.screen.route_catalog.narration", getTitle(), context);
    }

    private Component installButtonLabel() {
        return Component.translatable(selectedRouteId != null
                && installedRouteIds.contains(selectedRouteId)
                ? "waypointer.screen.route_catalog.installed"
                : "waypointer.screen.route_catalog.install");
    }

    private Component refreshButtonLabel(long nowNanos) {
        int seconds = refreshCooldownSeconds(nowNanos);
        return seconds == 0
                ? Component.translatable("waypointer.screen.route_catalog.refresh")
                : Component.translatable("waypointer.screen.route_catalog.refresh.cooldown",
                        formatCount(seconds));
    }

    private int refreshCooldownSeconds(long nowNanos) {
        return refreshCooldownSeconds(
                manualRefreshCooldownArmed, nowNanos, manualRefreshAllowedAtNanos);
    }

    static int refreshCooldownSeconds(
            boolean armed, long nowNanos, long allowedAtNanos) {
        if (!armed || nowNanos - allowedAtNanos >= 0) return 0;
        long remaining = allowedAtNanos - nowNanos;
        return (int) Math.min(10,
                (remaining + 999_999_999L) / 1_000_000_000L);
    }

    private static Component friendlyFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof CatalogApiException api) {
            String key = switch (api.code()) {
                case "not_found" -> "waypointer.screen.route_catalog.error.not_found";
                case "rate_limited" -> "waypointer.screen.route_catalog.error.rate_limited";
                case "invalid_cursor" -> "waypointer.screen.route_catalog.error.invalid_cursor";
                case "route_id_mismatch" -> "waypointer.screen.route_catalog.error.route_changed";
                case "payload_too_large", "request_too_large", "route_too_large" ->
                        "waypointer.screen.route_catalog.error.too_large";
                case "publishing_disabled" ->
                        "waypointer.screen.route_catalog.error.unavailable";
                default -> "waypointer.screen.route_catalog.error.request_failed";
            };
            return Component.translatable(key);
        }
        return Component.translatable("waypointer.screen.route_catalog.error.request_failed");
    }

    private static String formatCount(long value) {
        return LocalizedNumberFormatter.active().integer(value);
    }

    private static Component waypointCount(long value) {
        return pluralCount(
                "waypointer.screen.route_catalog.waypoint_count.one",
                "waypointer.screen.route_catalog.waypoint_count.many", value);
    }

    private static Component installCount(long value) {
        return pluralCount(
                "waypointer.screen.route_catalog.install_count.one",
                "waypointer.screen.route_catalog.install_count.many", value);
    }

    private static Component routeCount(long value) {
        return pluralCount(
                "waypointer.screen.route_catalog.status.count.one",
                "waypointer.screen.route_catalog.status.count.many", value);
    }

    private static Component groupCount(long value) {
        return pluralCount(
                "waypointer.screen.route_catalog.group_count.one",
                "waypointer.screen.route_catalog.group_count.many", value);
    }

    private static Component pluralCount(String oneKey, String manyKey, long value) {
        return Component.translatable(value == 1 ? oneKey : manyKey, formatCount(value));
    }

    private static String displayGroupName(WaypointGroup group) {
        String name = group.name() == null ? "" : group.name().trim();
        return name.isEmpty()
                ? Component.translatable("waypointer.group.unnamed").getString()
                : name;
    }

    private static boolean controlDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_CONTROL);
    }

    private static final class CatalogRowButton extends AbstractButton {
        private final CatalogRouteSummary route;
        private final boolean selected;
        private final boolean installed;
        private final Runnable onPress;

        private CatalogRowButton(
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
            if (selected) {
                graphics.fill(x1, y1, x1 + 1, y2, ACCENT);
            }
            if (isFocused()) {
                graphics.fill(x1, y1, x2, y1 + 1, ACCENT);
                graphics.fill(x1, y2 - 1, x2, y2, ACCENT);
                graphics.fill(x1, y1, x1 + 1, y2, ACCENT);
                graphics.fill(x2 - 1, y1, x2, y2, ACCENT);
            }
            var font = Minecraft.getInstance().font;
            int textX = x1 + GAP;
            int textWidth = Math.max(20, getWidth() - GAP * 2);

            String installedTag = installed ? Component.translatable(
                    "waypointer.screen.route_catalog.row.installed_tag").getString() : "";
            int installedTagW = installedTag.isEmpty() ? 0 : font.width(installedTag);
            String verifiedTag = route.publisherVerified()
                    ? Component.translatable(
                            "waypointer.screen.route_catalog.publisher.verified").getString().trim()
                    : "";
            int verifiedTagW = verifiedTag.isEmpty() ? 0 : font.width(verifiedTag);
            int stateW = installedTagW + verifiedTagW
                    + (installedTagW > 0 && verifiedTagW > 0 ? GAP : 0);
            int titleAvailable = Math.max(20, textWidth
                    - (stateW == 0 ? 0 : stateW + GAP));

            graphics.text(font, font.plainSubstrByWidth(route.title(), titleAvailable),
                    textX, y1 + ROW_TITLE_TOP, TEXT, false);

            Component author = route.authorName().isBlank()
                    ? Component.translatable(
                            "waypointer.screen.route_catalog.publisher.unknown")
                    : Component.literal(route.authorName());
            int columnW = Math.max(1, textWidth / 3);
            graphics.text(font, font.plainSubstrByWidth(
                            route.zoneLabel(), Math.max(1, columnW - GAP_TIGHT)),
                    textX, y1 + ROW_META_TOP, ACCENT, false);
            String waypoints = waypointCount(route.waypointCount()).getString();
            graphics.text(font, font.plainSubstrByWidth(
                            waypoints, Math.max(1, columnW - GAP_TIGHT)),
                    textX + columnW, y1 + ROW_META_TOP, TEXT_MUTED, false);
            String publisher = Component.translatable(
                    "waypointer.screen.route_catalog.publisher.by", author).getString();
            String clippedPublisher = font.plainSubstrByWidth(
                    publisher, Math.max(1, textWidth - columnW * 2));
            graphics.text(font, clippedPublisher,
                    x2 - GAP - font.width(clippedPublisher), y1 + ROW_META_TOP,
                    TEXT_MUTED, false);

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

        @Override
        protected MutableComponent createNarrationMessage() {
            Component author = route.authorName().isBlank()
                    ? Component.translatable(
                            "waypointer.screen.route_catalog.publisher.unknown")
                    : Component.literal(route.authorName());
            MutableComponent description = Component.translatable(
                    "waypointer.screen.route_catalog.row.narration",
                    route.title(), route.zoneLabel(),
                    waypointCount(route.waypointCount()), author);
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
                            "waypointer.screen.route_catalog.row.narration.selected",
                            description)
                    : description;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
