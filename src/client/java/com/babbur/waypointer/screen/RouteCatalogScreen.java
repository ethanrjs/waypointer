package com.babbur.waypointer.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.api.ImportSummary;
import com.babbur.waypointer.catalog.CatalogPage;
import com.babbur.waypointer.catalog.CatalogApiException;
import com.babbur.waypointer.catalog.CatalogInstallState;
import com.babbur.waypointer.catalog.CatalogProtocol;
import com.babbur.waypointer.catalog.CatalogRouteDetails;
import com.babbur.waypointer.catalog.CatalogRouteInstaller;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.i18n.LocalizedNumberFormatter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
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
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;

public final class RouteCatalogScreen extends Screen {
    private static final int PANEL_MARGIN = 12;
    private static final int LINE_H = 10;
    private static final int DETAIL_LINE_H = 11;
    private static final int ROW_H = CatalogRouteRowButton.HEIGHT;
    private static final int ROW_PITCH = ROW_H;
    private static final int WIDE_BREAKPOINT = 420;
    private static final int LOAD_MORE_W = 108;
    private static final int BACK_W = 64;
    private static final int PRIMARY_W = 84;
    private static final int SCROLLBAR_W = 3;
    private static final int SCROLLBAR_INSET = 2;
    private static final int ICON_BTN_W = 24;
    // Four client ticks debounce uncached searches by roughly 200 ms.
    private static final int SEARCH_DEBOUNCE_TICKS = 4;
    private static final int SEARCH_CACHE_LIMIT = 32;
    // Previews load once a route has stayed selected for a moment, so arrowing through is cheap.
    /** A short pause so holding an arrow key doesn't request every route on the way. */
    private static final int PREVIEW_DELAY_TICKS = 1;
    /** Previews of the routes on screen load ahead, a few at a time. */
    private static final int PREVIEW_PREFETCH = 16;
    private static final int PREVIEW_PARALLEL = 3;
    private static final int PREVIEW_CACHE_LIMIT = 24;
    private static final int ZONE_ROW_H = GuiTokens.ROW_H;
    private static final int ZONE_DROPDOWN_MIN_W = 150;
    private static final long MANUAL_REFRESH_COOLDOWN_NANOS = 10_000_000_000L;

    private static final int STATUS_OK = GuiTokens.SUCCESS;
    private static final int STATUS_ERROR = GuiTokens.DANGER;

    private final Screen parent;
    private final RouteCatalogClient catalogClient;
    private final ActiveGroupManager manager;
    private final CatalogBrowserModel browser = new CatalogBrowserModel();
    private final CatalogScreenRequestTracker requests = new CatalogScreenRequestTracker();

    private boolean listRequested;
    private boolean listLoading;
    private boolean appending;
    private boolean restartAppendOnReentry;
    private boolean detailLoading;
    private boolean initializing;
    private boolean lastLoadFailed;
    private Component statusText = Component.empty();
    private int statusColor = TEXT_DIM;
    private int searchDebounceTicks;
    private boolean searchViewDirty;
    private boolean manualRefreshCooldownArmed;
    private long manualRefreshAllowedAtNanos;
    private int displayedRefreshCooldownSeconds = -1;
    private final LinkedHashMap<String, CatalogPage> searchCache =
            boundedPageCache(SEARCH_CACHE_LIMIT);
    private boolean refocusSearchAfterRebuild;
    private int pendingSearchCursor = -1;
    private int pendingSearchHighlight = -1;
    private boolean zoneDropdownOpen;
    private boolean onlyNotInstalled;
    private int zoneDropdownScroll;
    private List<String> zoneDropdownIds = List.of();
    private final ContextMenuOverlay sortMenu = new ContextMenuOverlay();
    /** Narrow screens show the list and one route's details as separate pages. */
    private boolean showingDetail;
    private boolean wideLayout;

    private final LinkedHashMap<String, CatalogRoutePreview> previews = boundedPageCache(PREVIEW_CACHE_LIMIT);
    private final java.util.Set<String> previewFailures = new java.util.HashSet<>();
    private String previewWantedId;
    private final java.util.Set<String> previewLoading = new java.util.HashSet<>();
    private final java.util.ArrayDeque<CatalogRouteSummary> previewQueue = new java.util.ArrayDeque<>();
    private int previewDelayTicks;
    private int previewTicket;

    private EditBox searchBox;
    private static final int MIN_SEARCH_W = 110;
    private static final int COMFORTABLE_SEARCH_W = 180;
    private Button zoneFilterButton;
    private Button sortButton;
    private Button refreshButton;
    private Button loadMoreButton;
    private Button installButton;
    private Button copyCodeButton;
    private CatalogFilterChip hideInstalledChip;
    private CatalogFilterChip miningCultChip;
    private int headerTextWidth;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentRight;
    private int chipsY;
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
    private int dropdownBottom;
    private int descriptionScroll;
    private int descriptionMaxScroll;

    public RouteCatalogScreen(Screen parent, RouteCatalogClient catalogClient) {
        this(parent, catalogClient, WaypointerClient.manager());
    }

    RouteCatalogScreen(
            Screen parent, RouteCatalogClient catalogClient, ActiveGroupManager manager) {
        super(Component.translatable("waypointer.screen.route_catalog.title.community"));
        this.parent = parent;
        this.catalogClient = catalogClient;
        this.manager = manager;
        browser.setZoneFilter(initialZoneFilterId(
                manager == null ? null : manager.currentZone()));
    }

    /** Browse the complete catalog on every fresh open. */
    static String initialZoneFilterId(Zone zone) {
        return null;
    }

    public static void open(Screen parent) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new RouteCatalogScreen(parent, RouteCatalogClient.production()));
    }

    @Override
    protected void init() {
        requests.activate();
        initializing = true;
        browser.filterInstalled(catalogClient.apiRoot(), manager, onlyNotInstalled);

        // Short screens (high GUI scales) give the list every pixel they can.
        boolean compact = height < 300;
        int margin = compact ? GAP_TIGHT : PANEL_MARGIN;
        panelX = width > margin * 2 ? margin : 0;
        panelY = height > margin * 2 ? margin : 0;
        panelW = Math.max(1, width - panelX * 2);
        panelH = Math.max(1, height - panelY * 2);
        int panelPad = panelPad();
        int contentX = panelX + panelPad;
        int contentW = Math.max(40, panelW - panelPad * 2);
        contentRight = contentX + contentW;
        wideLayout = contentW >= WIDE_BREAKPOINT;
        if (wideLayout || selectedRoute() == null) showingDetail = false;

        // Header: title, then My routes and an icon-only refresh on the right.
        int titleY = panelY + panelPad;
        Component refreshLabel = refreshButtonLabel(System.nanoTime());
        GuiTokens.StyledButton refresh = (GuiTokens.StyledButton) styledButton(
                contentRight - ICON_BTN_W, titleY, ICON_BTN_W, BTN_H, refreshLabel,
                button -> requestManualRefresh(),
                Tooltip.create(Component.translatable("waypointer.screen.route_catalog.refresh.tooltip")),
                Icons.Icon.RESET);
        refresh.setIconOnly(true);
        refreshButton = refresh;
        refreshButton.active = refreshCooldownSeconds(System.nanoTime()) == 0 && !listLoading;
        addRenderableWidget(refreshButton);
        Component mineLabel = Component.translatable("waypointer.screen.route_publish.action.manage");
        int mineW = Math.min(contentW / 2, GuiTokens.iconButtonWidth(font, mineLabel, Icons.Icon.UPLOAD));
        addRenderableWidget(styledButton(contentRight - ICON_BTN_W - GAP_TIGHT - mineW, titleY, mineW, BTN_H,
                mineLabel, button -> PublishedRoutesScreen.open(this, catalogClient,
                        PublisherIdentityStore.defaultLocation(), CatalogPublicationRegistry.defaultLocation()),
                Tooltip.create(Component.translatable("waypointer.screen.route_publish.action.manage.tooltip")),
                Icons.Icon.UPLOAD));
        headerTextWidth = Math.max(1, contentW - mineW - ICON_BTN_W - GAP * 2);

        // Toolbar: search, zone, sort. Zone and sort drop under the search box when space is short.
        int toolbarY = titleY + BTN_H + GAP;
        int zoneW = Math.max(96, Math.min(150, widestZoneOptionWidth() + 28));
        int sortW = widestSortLabelWidth() + 28;
        if (contentW - zoneW - sortW - GAP * 2 < COMFORTABLE_SEARCH_W) {
            zoneW = 96;
            sortW = Math.min(sortW, 96);
        }
        boolean stacked = contentW - zoneW - sortW - GAP * 2 < MIN_SEARCH_W;
        int searchW = stacked ? contentW : contentW - zoneW - sortW - GAP * 2;
        int clearW = BTN_H + 2;
        int inputW = Math.max(20, searchW - clearW - GAP_TIGHT);
        searchBox = new EditBox(font, contentX, toolbarY, inputW, BTN_H,
                Component.translatable("waypointer.screen.route_catalog.search"));
        searchBox.setMaxLength(80);
        Component searchHint = Component.translatable("waypointer.screen.route_catalog.search.hint");
        searchBox.setHint(font.width(searchHint) + 8 <= inputW ? searchHint
                : Component.translatable("waypointer.screen.route_catalog.search"));
        searchBox.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.route_catalog.search.tooltip")));
        searchBox.setValue(browser.query());
        searchBox.setResponder(this::handleSearchEdit);
        addRenderableWidget(searchBox);
        GuiTokens.StyledButton clearSearch = (GuiTokens.StyledButton) styledButton(
                contentX + inputW + GAP_TIGHT, toolbarY, clearW, BTN_H,
                Component.translatable("waypointer.common.clear"),
                button -> {
                    searchBox.setValue("");
                    runPendingSearch();
                    focusSearchBox();
                }, Tooltip.create(Component.translatable("waypointer.screen.main.search.clear.tooltip")),
                Icons.Icon.CLOSE);
        clearSearch.setIconOnly(true);
        clearSearch.active = !browser.query().isEmpty();
        addRenderableWidget(clearSearch);

        int filtersY = stacked ? toolbarY + BTN_H + GAP_TIGHT : toolbarY;
        if (stacked) {
            sortW = Math.min(sortW, contentW / 2);
            zoneW = contentW - sortW - GAP;
        }
        int zoneX = stacked ? contentX : contentX + searchW + GAP;
        zoneFilterButton = new ZoneFilterButton(zoneX, filtersY, zoneW, BTN_H, zoneFilterLabel());
        zoneFilterButton.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.route_catalog.filter.tooltip")));
        addRenderableWidget(zoneFilterButton);
        sortButton = new SortButton(zoneX + zoneW + GAP, filtersY, sortW, BTN_H, sortLabel());
        addRenderableWidget(sortButton);

        // Filter chips, with the result count on the right of the same line.
        chipsY = filtersY + BTN_H + GAP_TIGHT + 1;
        hideInstalledChip = new CatalogFilterChip(contentX, chipsY,
                Component.translatable("waypointer.screen.route_catalog.filter.hide_installed"),
                onlyNotInstalled, ACCENT, null, () -> {
                    onlyNotInstalled = !onlyNotInstalled;
                    applyFilters();
                });
        hideInstalledChip.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.route_catalog.not_installed.tooltip")));
        addRenderableWidget(hideInstalledChip);
        miningCultChip = null;
        if (browser.anyLoadedRouteHas(CatalogRouteSummary.TAG_MINING_CULT) || browser.tagFilter() != null) {
            boolean on = CatalogRouteSummary.TAG_MINING_CULT.equals(browser.tagFilter());
            miningCultChip = new CatalogFilterChip(contentX + hideInstalledChip.getWidth() + GAP_TIGHT, chipsY,
                    Component.translatable("waypointer.screen.route_catalog.tag.mining_cult"),
                    on, CatalogRouteRowButton.MINING_CULT, Icons.Icon.BADGE_GEM, () -> {
                        browser.setTagFilter(on ? null : CatalogRouteSummary.TAG_MINING_CULT);
                        refetchForServerFilters();
                    });
            miningCultChip.setTooltip(Tooltip.create(Component.translatable(
                    "waypointer.screen.route_catalog.tag.mining_cult.tooltip")));
            addRenderableWidget(miningCultChip);
        }

        // Body: list beside details, or one of them on narrow screens.
        int bodyY = chipsY + CatalogFilterChip.HEIGHT + GAP;
        int footerY = panelY + panelH - panelPad - BTN_H;
        dropdownBottom = footerY - GAP_TIGHT;
        int bodyH = Math.max(40, footerY - GAP - bodyY);
        if (wideLayout) {
            listX = contentX;
            listY = bodyY;
            listW = catalogListWidth(contentW);
            listH = bodyH;
            detailX = listX + listW + GAP;
            detailY = bodyY;
            detailW = contentRight - detailX;
            detailH = bodyH;
        } else if (showingDetail) {
            listX = contentX;
            listY = bodyY;
            listW = contentW;
            listH = 0;
            detailX = contentX;
            detailY = bodyY;
            detailW = contentW;
            detailH = bodyH;
        } else {
            listX = contentX;
            listY = bodyY;
            listW = contentW;
            listH = bodyH;
            detailX = contentX;
            detailY = bodyY;
            detailW = contentW;
            detailH = 0;
        }

        loadMoreButton = null;
        if (listH > 0) addRouteRows();

        // Footer: Back on the left, the selected route's actions on the right.
        Component copyLabel = Component.translatable("waypointer.screen.published_routes.action.copy_code");
        int copyW = GuiTokens.iconButtonWidth(font, copyLabel, Icons.Icon.SHARE);
        int installW = Math.max(PRIMARY_W, GuiTokens.iconButtonWidth(font, installButtonLabel(), Icons.Icon.DOWNLOAD));
        addRenderableWidget(styledButton(contentX, footerY, BACK_W, BTN_H,
                Component.translatable("gui.back"), button -> onBack(), null));
        boolean actionsShown = wideLayout || showingDetail;
        copyCodeButton = styledButton(contentRight - installW - GAP - copyW, footerY,
                copyW, BTN_H, copyLabel, button -> copySelectedCode(),
                Tooltip.create(Component.translatable("waypointer.screen.route_catalog.copy.tooltip")),
                Icons.Icon.SHARE);
        installButton = styledButton(contentRight - installW, footerY, installW, BTN_H,
                installButtonLabel(), button -> installSelectedRoute(), null, Icons.Icon.DOWNLOAD);
        copyCodeButton.visible = actionsShown;
        installButton.visible = actionsShown;
        addRenderableWidget(copyCodeButton);
        addRenderableWidget(installButton);
        statusX = contentX + BACK_W + GAP;
        statusY = GuiTokens.opticalTextY(footerY, BTN_H);
        statusMaxW = Math.max(0, (actionsShown ? copyCodeButton.getX() - GAP : contentRight) - statusX);
        refreshPrimaryButtons();

        initializing = false;
        if (!listRequested) {
            boolean restartAppend = restartAppendOnReentry
                    && browser.nextCursor() != null;
            restartAppendOnReentry = false;
            if (restartAppend) {
                requestPage(browser.nextCursor(), true);
            } else {
                refreshCatalog();
            }
        }
    }

    private int panelPad() {
        return height < 300 || panelW < 320 ? GAP : PAD_OUTER;
    }

    private void applyFilters() {
        browser.filterInstalled(catalogClient.apiRoot(), manager, onlyNotInstalled);
        requests.selectionChanged();
        descriptionScroll = 0;
        if (browser.selectedRoute() == null && !browser.routes().isEmpty()) {
            browser.select(browser.routes().getFirst());
            wantPreview(browser.selectedRoute());
        }
        prefetchPreviews(browser.routes());
        updateListStatus();
        rebuildWidgets();
    }

    private void onBack() {
        if (showingDetail) {
            showingDetail = false;
            rebuildWidgets();
            return;
        }
        onClose();
    }

    static int catalogListWidth(int contentWidth) {
        return contentWidth < WIDE_BREAKPOINT ? contentWidth
                : Math.max(224, (contentWidth - GAP_SECTION) * 54 / 100);
    }

    // Preserve the caret and selection across widget rebuilds.
    @Override
    protected void rebuildWidgets() {
        rememberSearchFocus();
        super.rebuildWidgets();
    }

    @Override
    protected void setInitialFocus() {
        if (refocusSearchAfterRebuild && searchBox != null) {
            refocusSearchAfterRebuild = false;
            setInitialFocus(searchBox);
            restoreSearchCaret();
            return;
        }
        super.setInitialFocus();
    }

    private void restoreSearchCaret() {
        if (pendingSearchCursor >= 0) {
            searchBox.setCursorPosition(pendingSearchCursor);
            searchBox.setHighlightPos(pendingSearchHighlight >= 0
                    ? pendingSearchHighlight : pendingSearchCursor);
        }
        pendingSearchCursor = -1;
        pendingSearchHighlight = -1;
    }

    private void rememberSearchFocus() {
        if (searchBox == null || !searchBox.isFocused()) return;
        markSearchRefocus();
    }

    private void markSearchRefocus() {
        if (searchBox == null) return;
        refocusSearchAfterRebuild = true;
        pendingSearchCursor = searchBox.getCursorPosition();
        pendingSearchHighlight = searchHighlightAnchor(
                searchBox.getValue(), pendingSearchCursor, searchBox.getHighlighted());
    }

    /** Recover the selection anchor, which EditBox does not expose, from its highlighted text. */
    static int searchHighlightAnchor(String value, int cursor, String highlighted) {
        if (highlighted == null || highlighted.isEmpty()) return cursor;
        if (value != null
                && value.regionMatches(cursor, highlighted, 0, highlighted.length())) {
            return cursor + highlighted.length();
        }
        return Math.max(0, cursor - highlighted.length());
    }

    /** Bounded per-query response cache; the least recently used entry falls out. */
    static <V> LinkedHashMap<String, V> boundedPageCache(int limit) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > limit;
            }
        };
    }

    static String searchCacheKey(String zone, String query) {
        return (zone == null ? "" : zone) + '\n' + (query == null ? "" : query);
    }

    /** Server-sorted or tag-filtered pages are cached apart from the plain listing. */
    static String searchCacheKey(String zone, String query, String tag, CatalogBrowserModel.Sort sort) {
        String key = searchCacheKey(zone, query);
        boolean plain = tag == null && (sort == null || sort == CatalogBrowserModel.Sort.RECOMMENDED);
        return plain ? key : key + '\n' + (tag == null ? "" : tag) + '\n' + sort.id;
    }

    private void handleSearchEdit(String value) {
        if (initializing) return;
        applySearchEdit(browser, value, () -> {
            requests.invalidateForSearch();
            listLoading = false;
            appending = false;
            detailLoading = false;
            lastLoadFailed = false;
            searchDebounceTicks = SEARCH_DEBOUNCE_TICKS;
            statusText = Component.empty();
            statusColor = TEXT_DIM;
        }, () -> searchViewDirty = true);
    }

    static boolean applySearchEdit(
            CatalogBrowserModel browser, String value,
            Runnable updateState, Runnable scheduleRebuild) {
        if (!browser.editSearch(value)) return false;
        updateState.run();
        // EditBox calls its responder before finishing selection updates. Rebuilding
        // here reads a stale selection and can also replace the box mid-keystroke.
        scheduleRebuild.run();
        return true;
    }

    private void focusSearchBox() {
        if (searchBox == null) return;
        setFocused(searchBox);
        searchBox.setFocused(true);
    }

    private void addRouteRows() {
        List<CatalogRouteSummary> filtered = browser.routes();
        int visibleRows = visibleRowCount();
        browser.clampScroll(visibleRows);
        boolean scrollable = filtered.size() > visibleRows;
        int rowW = scrollable ? listW - SCROLLBAR_W - SCROLLBAR_INSET * 2 : listW;
        for (int row = 0; row < visibleRows; row++) {
            int index = browser.scrollOffset() + row;
            if (index >= filtered.size()) break;
            CatalogRouteSummary route = filtered.get(index);
            int y = listY + row * ROW_PITCH;
            CatalogRouteRowButton button = new CatalogRouteRowButton(
                    listX, y, rowW, ROW_H, route,
                    route.id().equals(browser.selectedRouteId()),
                    installState(route).action(),
                    () -> openRoute(route));
            addRenderableWidget(button);
        }
        if (browser.nextCursor() != null || appending) {
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
            loadMoreButton.active = !listLoading && !appending
                    && browser.nextCursor() != null;
            addRenderableWidget(loadMoreButton);
        }
    }

    /** Selects a route; on narrow screens this also opens its detail page. */
    private void openRoute(CatalogRouteSummary route) {
        boolean already = route.id().equals(browser.selectedRouteId());
        if (!wideLayout) showingDetail = true;
        if (already) {
            rebuildWidgets();
            return;
        }
        selectRoute(route);
    }

    /** Unconditional network reload of the first page (initial load, refresh). */
    private void refreshCatalog() {
        searchDebounceTicks = 0;
        browser.submitPendingSearch();
        requestPage(null, false);
    }

    /** Reuse cached results before requesting the pending search. */
    private void runPendingSearch() {
        searchDebounceTicks = 0;
        browser.submitPendingSearch();
        CatalogPage cached = cachedSearchPage(searchCache, browser.zoneFilter(),
                browser.normalizedQuery(), browser.tagFilter(), browser.sort());
        if (cached != null) {
            showCachedPage(cached);
            return;
        }
        requestPage(null, false);
    }

    static CatalogPage cachedSearchPage(Map<String, CatalogPage> cache, String zone, String query) {
        return cachedSearchPage(cache, zone, query, null, CatalogBrowserModel.Sort.RECOMMENDED);
    }

    /**
     * An exact cached page, or one cut from a complete cached listing. Sorting and tag
     * filtering of a complete listing happen in the browser model, so any order can reuse it.
     */
    static CatalogPage cachedSearchPage(Map<String, CatalogPage> cache, String zone, String query,
                                        String tag, CatalogBrowserModel.Sort sort) {
        CatalogPage exact = cache.get(searchCacheKey(zone, query, tag, sort));
        if (exact != null) return exact;
        CatalogPage complete = cache.get(searchCacheKey(zone, ""));
        if (complete == null || complete.hasMore()) {
            complete = cache.get(searchCacheKey(null, ""));
        }
        if (complete == null || complete.hasMore()) return null;
        return new CatalogPage(complete.routes().stream()
                .filter(route -> zone == null || zone.equals(route.zoneId()))
                .filter(route -> tag == null || route.hasTag(tag))
                .filter(route -> route.matches(query)).toList(), false, null);
    }

    private void showCachedPage(CatalogPage page) {
        requests.invalidateForSearch();
        listRequested = true;
        listLoading = false;
        appending = false;
        lastLoadFailed = false;
        applyPage(page, false);
        rebuildWidgets();
    }

    private String emptyListKey() {
        if (onlyNotInstalled) return "waypointer.screen.route_catalog.empty.not_installed";
        return emptyListKey(browser.query().isBlank(), browser.zoneFilter() != null);
    }

    static String emptyListKey(boolean queryBlank, boolean zoneFiltered) {
        if (!queryBlank) return "waypointer.screen.route_catalog.empty.search";
        return zoneFiltered
                ? "waypointer.screen.route_catalog.empty.zone"
                : "waypointer.screen.route_catalog.empty";
    }

    private Component zoneFilterLabel() {
        return browser.zoneFilter() == null
                ? Component.translatable("waypointer.screen.route_catalog.filter.all_zones")
                : Component.literal(Zone.fromId(browser.zoneFilter()).displayName());
    }

    /** Returns {labelX, labelMaxW, caretCenterX, caretCenterY}, aligned with dropdown rows. */
    static int[] filterSelectGeometry(int x, int y, int w, int h) {
        return new int[] {x + GAP, Math.max(12, w - 27), x + w - 13, y + h / 2 - 1};
    }

    private final class ZoneFilterButton extends GuiTokens.StyledButton {
        ZoneFilterButton(int x, int y, int width, int height, Component label) {
            super(x, y, width, height, label, button -> toggleZoneDropdown());
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            boolean highlighted = active && isHoveredOrFocused();
            GuiTokens.drawControlFrame(g, getX(), getY(), getWidth(), getHeight(),
                    active, highlighted, isFocused());
            int[] geo = filterSelectGeometry(getX(), getY(), getWidth(), getHeight());
            g.text(font, font.plainSubstrByWidth(getMessage().getString(), geo[1]),
                    geo[0], GuiTokens.opticalTextY(getY(), getHeight()),
                    active ? TEXT : TEXT_MUTED, false);
            GuiTokens.drawDirectionGlyph(g,
                    zoneDropdownOpen ? GuiTokens.Direction.UP : GuiTokens.Direction.DOWN,
                    geo[2], geo[3],
                    zoneDropdownOpen ? ACCENT : highlighted ? TEXT : TEXT_DIM);
        }
    }

    private final class SortButton extends GuiTokens.StyledButton {
        SortButton(int x, int y, int width, int height, Component label) {
            super(x, y, width, height, label, button -> toggleSortMenu());
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            boolean highlighted = active && isHoveredOrFocused();
            GuiTokens.drawControlFrame(g, getX(), getY(), getWidth(), getHeight(),
                    active, highlighted, isFocused());
            int[] geo = filterSelectGeometry(getX(), getY(), getWidth(), getHeight());
            g.text(font, font.plainSubstrByWidth(getMessage().getString(), geo[1]),
                    geo[0], GuiTokens.opticalTextY(getY(), getHeight()), active ? TEXT : TEXT_MUTED, false);
            GuiTokens.drawDirectionGlyph(g,
                    sortMenu.isOpen() ? GuiTokens.Direction.UP : GuiTokens.Direction.DOWN,
                    geo[2], geo[3], sortMenu.isOpen() ? ACCENT : highlighted ? TEXT : TEXT_DIM);
        }
    }

    private static String sortKey(CatalogBrowserModel.Sort sort) {
        return "waypointer.screen.route_catalog.sort." + sort.id;
    }

    private Component sortLabel() {
        return Component.translatable(sortKey(browser.sort()));
    }

    private int widestSortLabelWidth() {
        int widest = 0;
        for (CatalogBrowserModel.Sort sort : CatalogBrowserModel.Sort.values()) {
            widest = Math.max(widest, font.width(Component.translatable(sortKey(sort))));
        }
        return widest;
    }

    private void toggleSortMenu() {
        if (sortMenu.isOpen()) {
            sortMenu.close();
            return;
        }
        closeZoneDropdown();
        List<ContextMenuOverlay.Item> items = new ArrayList<>();
        for (CatalogBrowserModel.Sort sort : CatalogBrowserModel.Sort.values()) {
            ContextMenuOverlay.Item item = ContextMenuOverlay.Item.of(
                    Component.translatable(sortKey(sort)), () -> chooseSort(sort));
            items.add(sort == browser.sort() ? item.withIcon(Icons.Icon.CHECK) : item);
        }
        sortMenu.openAt(font, items, sortButton.getX(), sortButton.getY() + BTN_H + GAP_TIGHT,
                panelX + GAP, panelY, panelX + panelW - GAP, dropdownBottom);
    }

    private void chooseSort(CatalogBrowserModel.Sort sort) {
        sortMenu.close();
        if (!browser.setSort(sort)) return;
        refetchForServerFilters();
    }

    /** Sort and tag apply on the server, so a partial listing has to start over. */
    private void refetchForServerFilters() {
        requests.invalidateForSearch();
        listLoading = false;
        appending = false;
        detailLoading = false;
        lastLoadFailed = false;
        descriptionScroll = 0;
        runPendingSearch();
    }

    private int widestZoneOptionWidth() {
        int widest = 0;
        for (String id : zoneFilterDropdownIds()) {
            widest = Math.max(widest, font.width(zoneOptionLabel(id)));
        }
        return widest;
    }

    private String zoneOptionLabel(String id) {
        return id.isEmpty()
                ? Component.translatable(
                        "waypointer.screen.route_catalog.filter.all_zones").getString()
                : Zone.fromId(id).displayName();
    }

    /** Every canonical zone by display name, behind an all-zones sentinel ({@code ""}). */
    static List<String> zoneFilterDropdownIds() {
        List<String> ids = new ArrayList<>();
        for (Zone zone : Zone.knownZones()) ids.add(zone.id());
        ids.sort(Comparator
                .comparing((String id) -> Zone.fromId(id).displayName()
                        .toLowerCase(Locale.ROOT))
                .thenComparing(id -> id));
        List<String> out = new ArrayList<>(ids.size() + 1);
        out.add("");
        out.addAll(ids);
        return List.copyOf(out);
    }

    private void toggleZoneDropdown() {
        if (zoneDropdownOpen) {
            closeZoneDropdown();
            return;
        }
        sortMenu.close();
        if (searchBox != null && searchBox.isFocused()) markSearchRefocus();
        zoneDropdownIds = zoneFilterDropdownIds();
        zoneDropdownOpen = true;
        zoneDropdownScroll = zoneDropdownScrollForSelection();
    }

    private void closeZoneDropdown() {
        zoneDropdownOpen = false;
        if (refocusSearchAfterRebuild && searchBox != null) {
            refocusSearchAfterRebuild = false;
            setFocused(searchBox);
            searchBox.setFocused(true);
            restoreSearchCaret();
        }
    }

    private void chooseZoneFilter(String id) {
        closeZoneDropdown();
        String next = id == null || id.isEmpty() ? null : id;
        if (!browser.setZoneFilter(next)) return;
        requests.invalidateForSearch();
        listLoading = false;
        appending = false;
        detailLoading = false;
        lastLoadFailed = false;
        runPendingSearch();
    }

    private int zoneDropdownScrollForSelection() {
        String selected = browser.zoneFilter() == null ? "" : browser.zoneFilter();
        ZoneDropdownGeometry geo = zoneDropdownGeometry(zoneDropdownIds.size());
        return centeredDropdownScroll(zoneDropdownIds.indexOf(selected),
                zoneDropdownIds.size(), geo.rowsBottom() - geo.rowsTop());
    }

    static int centeredDropdownScroll(int index, int rowCount, int viewport) {
        if (index <= 0) return 0;
        int target = index * ZONE_ROW_H - Math.max(0, viewport / 2 - ZONE_ROW_H / 2);
        return Math.min(Math.max(0, target),
                WaypointerScreen.maxDropdownScroll(rowCount, viewport));
    }

    record ZoneDropdownGeometry(
            int x1, int y1, int x2, int y2, int rowsTop, int rowsBottom) {
    }

    private ZoneDropdownGeometry zoneDropdownGeometry(int rowCount) {
        int anchorX = zoneFilterButton == null ? panelX + GAP : zoneFilterButton.getX();
        int anchorBottom = zoneFilterButton == null
                ? panelY + PAD_OUTER : zoneFilterButton.getY() + BTN_H;
        int buttonW = zoneFilterButton == null ? 0 : zoneFilterButton.getWidth();
        return zoneDropdownGeometry(
                anchorX, anchorBottom, buttonW, panelX, panelW, dropdownBottom, rowCount);
    }

    /** Pure dropdown layout: anchored under its button, clipped to the panel. */
    static ZoneDropdownGeometry zoneDropdownGeometry(
            int anchorX, int anchorBottom, int buttonW,
            int panelX, int panelW, int statusY, int rowCount) {
        int w = Math.min(Math.max(ZONE_DROPDOWN_MIN_W, buttonW),
                Math.max(60, panelW - GAP * 2));
        int x1 = Math.max(panelX + 1, Math.min(anchorX, panelX + panelW - 1 - w));
        int x2 = x1 + w;
        int y1 = anchorBottom + GAP_TIGHT;
        int maxBottom = Math.max(y1 + ZONE_ROW_H + 2, statusY - GAP_TIGHT);
        int rowsTop = y1 + 1;
        int availableRows = Math.max(1, (maxBottom - rowsTop - 1) / ZONE_ROW_H);
        int visibleRows = Math.max(1, Math.min(rowCount, availableRows));
        int y2 = rowsTop + visibleRows * ZONE_ROW_H + 1;
        return new ZoneDropdownGeometry(x1, y1, x2, y2, rowsTop, y2 - 1);
    }

    /** Scrollbar thumb as {top offset, height}, or {@code null} when everything fits. */
    static int[] dropdownThumb(int viewport, int contentHeight, int scroll, int maxScroll) {
        if (contentHeight <= viewport) return null;
        int thumbH = Math.max(8, viewport * viewport / contentHeight);
        int travel = viewport - thumbH;
        return new int[] {maxScroll == 0 ? 0 : scroll * travel / maxScroll, thumbH};
    }

    private void renderZoneDropdown(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (zoneDropdownIds.isEmpty()) return;
        ZoneDropdownGeometry geo = zoneDropdownGeometry(zoneDropdownIds.size());
        GuiTokens.drawTooltipPanel(g, geo.x1(), geo.y1(), geo.x2(), geo.y2());
        int viewport = geo.rowsBottom() - geo.rowsTop();
        zoneDropdownScroll = WaypointerScreen.dropdownScrollAfterWheel(
                zoneDropdownScroll, 0, zoneDropdownIds.size(), viewport);
        String selected = browser.zoneFilter() == null ? "" : browser.zoneFilter();
        g.enableScissor(geo.x1(), geo.rowsTop(), geo.x2(), geo.rowsBottom());
        for (int i = 0; i < zoneDropdownIds.size(); i++) {
            int rowY = geo.rowsTop() - zoneDropdownScroll + i * ZONE_ROW_H;
            if (rowY + ZONE_ROW_H <= geo.rowsTop()) continue;
            if (rowY >= geo.rowsBottom()) break;
            String id = zoneDropdownIds.get(i);
            boolean isSelected = id.equals(selected);
            boolean hovered = mouseX >= geo.x1() && mouseX < geo.x2()
                    && mouseY >= Math.max(rowY, geo.rowsTop())
                    && mouseY < Math.min(rowY + ZONE_ROW_H, geo.rowsBottom());
            int background = isSelected ? SELECTED : hovered ? GuiTokens.HOVER : 0;
            if (background != 0) {
                g.fill(geo.x1() + 1, rowY, geo.x2() - 1, rowY + ZONE_ROW_H, background);
            }
            if (isSelected) {
                g.fill(geo.x1() + 1, rowY, geo.x1() + 3, rowY + ZONE_ROW_H, ACCENT);
            }
            int labelMaxW = Math.max(12, geo.x2() - geo.x1() - GAP * 2);
            g.text(font, font.plainSubstrByWidth(zoneOptionLabel(id), labelMaxW),
                    geo.x1() + GAP, rowY + 7,
                    isSelected || hovered ? TEXT : TEXT_DIM, false);
            g.fill(geo.x1() + 1, rowY + ZONE_ROW_H - 1,
                    geo.x2() - 1, rowY + ZONE_ROW_H, BORDER);
        }
        g.disableScissor();
        int[] thumb = dropdownThumb(viewport, zoneDropdownIds.size() * ZONE_ROW_H,
                zoneDropdownScroll,
                WaypointerScreen.maxDropdownScroll(zoneDropdownIds.size(), viewport));
        if (thumb != null) {
            g.fill(geo.x2() - 3, geo.rowsTop() + thumb[0],
                    geo.x2() - 1, geo.rowsTop() + thumb[0] + thumb[1], TEXT_MUTED);
        }
    }

    private boolean handleZoneDropdownClick(double mx, double my) {
        ZoneDropdownGeometry geo = zoneDropdownGeometry(zoneDropdownIds.size());
        boolean inside = mx >= geo.x1() && mx < geo.x2()
                && my >= geo.y1() && my < geo.y2();
        if (!inside) {
            // Consume outside clicks so the filter button cannot reopen at once.
            closeZoneDropdown();
            return true;
        }
        int index = WaypointerScreen.dropdownRowIndexAt(
                my, geo.rowsTop(), geo.rowsBottom(),
                zoneDropdownScroll, zoneDropdownIds.size());
        if (index >= 0) chooseZoneFilter(zoneDropdownIds.get(index));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (sortMenu.isOpen()) {
            if (sortMenu.mouseClicked(event.x(), event.y())) return true;
            sortMenu.close();
            if (sortButton != null && sortButton.isMouseOver(event.x(), event.y())) return true;
        }
        if (zoneDropdownOpen && handleZoneDropdownClick(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubleClick);
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
        searchCache.clear();
        refreshCatalog();
    }

    private void loadMore() {
        if (browser.nextCursor() == null || listLoading || appending) return;
        requestPage(browser.nextCursor(), true);
    }

    private void requestPage(String cursor, boolean append) {
        int ticket = requests.beginList(!append);
        listRequested = true;
        listLoading = true;
        appending = append;
        statusText = Component.translatable(append
                ? "waypointer.screen.route_catalog.status.loading_more"
                : "waypointer.screen.route_catalog.status.loading");
        statusColor = TEXT_DIM;
        if (!append) {
            browser.beginRefresh();
        }
        rebuildWidgets();

        String query = browser.normalizedQuery();
        String zone = browser.zoneFilter();
        String tag = browser.tagFilter();
        CatalogBrowserModel.Sort sort = browser.sort();
        catalogClient.listRoutes(query, zone, tag, sort.id, cursor)
                .whenComplete((page, failure) -> runOnClient(() -> {
            if (!requests.acceptsList(ticket)) return;
            listLoading = false;
            appending = false;
            if (failure != null) {
                lastLoadFailed = true;
                statusText = friendlyFailure(failure);
                statusColor = STATUS_ERROR;
                browser.markLoadFailed(append);
            } else {
                lastLoadFailed = false;
                if (!append) searchCache.put(searchCacheKey(zone, query, tag, sort), page);
                applyPage(page, append);
            }
            rebuildWidgets();
        }));
    }

    private void applyPage(CatalogPage page, boolean append) {
        browser.applyPage(page, append);
        browser.filterInstalled(catalogClient.apiRoot(), manager, onlyNotInstalled);
        if (browser.selectedRoute() == null && !browser.routes().isEmpty()) {
            browser.select(browser.routes().getFirst());
            descriptionScroll = 0;
        }
        wantPreview(browser.selectedRoute());
        prefetchPreviews(browser.routes());
        updateListStatus();
    }

    /** Counts live beside the filter chips; the footer line is for messages only. */
    private void updateListStatus() {
        if (!lastLoadFailed && !detailLoading) {
            statusText = Component.empty();
            statusColor = TEXT_DIM;
        }
    }

    private void selectRoute(CatalogRouteSummary route) {
        if (!browser.select(route)) return;
        descriptionScroll = 0;
        requests.selectionChanged();
        statusText = Component.empty();
        statusColor = TEXT_DIM;
        wantPreview(route);
        rebuildWidgets();
    }

    private void wantPreview(CatalogRouteSummary route) {
        previewWantedId = route == null ? null : route.id();
        previewDelayTicks = PREVIEW_DELAY_TICKS;
    }

    /** Queues the previews of a page's first routes so they are ready before they are clicked. */
    private void prefetchPreviews(List<CatalogRouteSummary> routes) {
        previewQueue.clear();
        for (CatalogRouteSummary route : routes) {
            if (previewQueue.size() >= PREVIEW_PREFETCH) break;
            if (!previews.containsKey(route.id()) && !previewFailures.contains(route.id())) previewQueue.add(route);
        }
    }

    /** The selected route's preview goes first; the queued ones fill the remaining slots. */
    private void tickPreview() {
        CatalogRouteSummary route = selectedRoute();
        if (route != null && route.id().equals(previewWantedId) && needsPreview(route)
                && previewDelayTicks-- <= 0) {
            loadPreview(route);
        }
        while (previewLoading.size() < PREVIEW_PARALLEL && !previewQueue.isEmpty()) {
            CatalogRouteSummary next = previewQueue.poll();
            if (needsPreview(next)) loadPreview(next);
        }
    }

    private boolean needsPreview(CatalogRouteSummary route) {
        return !previews.containsKey(route.id()) && !previewFailures.contains(route.id())
                && !previewLoading.contains(route.id());
    }

    private void loadPreview(CatalogRouteSummary route) {
        String id = route.id();
        int ticket = previewTicket;
        previewLoading.add(id);
        catalogClient.getRoutePreview(id)
                .thenApply(CatalogRoutePreview::of)
                // Catalogs without the preview endpoint still have the full route.
                .exceptionallyCompose(missing -> catalogClient.getRoute(id)
                        .thenApply(details -> validateDetails(route, details))
                        .thenApplyAsync(details -> CatalogRoutePreview.of(
                                CatalogRouteInstaller.decodeForPreview(details))))
                .whenComplete((preview, failure) -> runOnClient(() -> {
                    if (ticket != previewTicket) return;
                    previewLoading.remove(id);
                    if (failure != null || preview == null) {
                        previewFailures.add(id);
                    } else {
                        previews.put(id, preview);
                    }
                }));
    }

    private void copySelectedCode() {
        CatalogRouteSummary selected = selectedRoute();
        if (selected == null || minecraft == null) return;
        minecraft.keyboardHandler.setClipboard(UniversalShareCodec.encodeCatalogReference(selected.id()));
        statusText = Component.translatable("waypointer.screen.published_routes.status.copied_code");
        statusColor = STATUS_OK;
    }

    private void installSelectedRoute() {
        CatalogRouteSummary route = selectedRoute();
        if (route == null || detailLoading) return;
        CatalogInstallState installState = installState(route);
        if (!installState.canInstall()) {
            statusText = Component.translatable(
                    "waypointer.screen.route_catalog.status.already_installed");
            statusColor = TEXT_DIM;
            refreshPrimaryButtons();
            return;
        }

        CatalogScreenRequestTracker.InstallTicket ticket = requests.beginInstall();
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
                        finishInstall(ticket, route,
                                prepared, failure)));
    }

    private void finishInstall(
            CatalogScreenRequestTracker.InstallTicket ticket,
            CatalogRouteSummary requestedRoute,
            CatalogRouteInstaller.PreparedRoute prepared,
            Throwable preparationFailure) {
        if (!requests.latestInstallAttempt(ticket)) return;
        detailLoading = false;

        String requestedRouteId = requestedRoute.id();
        boolean currentRequest = requests.acceptsInstall(
                ticket, sameInstallTarget(requestedRoute, selectedRoute()));
        if (!currentRequest) {
            if (requests.active()) refreshPrimaryButtons();
            return;
        }

        ImportSummary summary = null;
        Throwable failure = preparationFailure;
        if (failure == null) {
            try {
                summary = CatalogRouteInstaller.install(
                        manager, catalogClient.apiRoot(), prepared);
            } catch (RuntimeException installFailure) {
                failure = installFailure;
            }
        }

        if (failure != null) {
            statusText = friendlyFailure(failure);
            statusColor = STATUS_ERROR;
        } else {
            showInstalled(summary);
            catalogClient.recordInstall(requestedRouteId, null,
                    com.babbur.waypointer.catalog.InstallTokenStore.shared()
                            .tokenFor(requestedRouteId));
            WaypointGroup focus = installedFocus(manager, summary);
            if (focus != null) {
                WaypointerScreen.openFocused(
                        manager, WaypointerClient.config(), focus);
                return;
            }
        }
        rebuildWidgets();
    }

    private static boolean sameInstallTarget(
            CatalogRouteSummary requested, CatalogRouteSummary selected) {
        return CatalogBrowserModel.sameRouteContract(requested, selected);
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
        try {
            return CatalogProtocol.validateDetails(requested, details);
        } catch (IllegalArgumentException changed) {
            throw new IllegalArgumentException(
                    Component.translatable(
                            "waypointer.screen.route_catalog.error.route_changed").getString(),
                    changed);
        }
    }

    private CatalogRouteSummary selectedRoute() {
        return browser.selectedRoute();
    }

    private void refreshPrimaryButtons() {
        CatalogRouteSummary selected = selectedRoute();
        long now = System.nanoTime();
        int seconds = refreshCooldownSeconds(now);
        RouteCatalogUiState.Controls controls = RouteCatalogUiState.controls(
                selected != null,
                selected != null && installState(selected).canInstall(),
                detailLoading,
                listLoading,
                appending,
                browser.nextCursor() != null,
                seconds);
        if (copyCodeButton != null) copyCodeButton.active = selected != null;
        if (installButton != null) {
            installButton.active = controls.installEnabled();
            installButton.setMessage(installButtonLabel());
        }
        if (refreshButton != null) {
            refreshButton.active = controls.refreshEnabled();
            refreshButton.setMessage(refreshButtonLabel(now));
        }
        if (loadMoreButton != null) {
            loadMoreButton.active = controls.loadMoreEnabled();
        }
    }

    private int visibleRowCount() {
        return Math.max(1, routeRowsHeight() / ROW_PITCH);
    }

    private int routeRowsHeight() {
        return Math.max(ROW_H, listH - (browser.nextCursor() != null || appending
                ? BTN_H + GAP : 0));
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        // Suppress underlying hover states and tooltips while a dropdown is open.
        boolean overlay = zoneDropdownOpen || sortMenu.isOpen();
        int backgroundMouseX = overlay ? -1 : mouseX;
        int backgroundMouseY = overlay ? -1 : mouseY;
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);

        int panelPad = panelPad();
        int contentX = panelX + panelPad;
        int titleY = panelY + panelPad;
        graphics.text(font, font.plainSubstrByWidth(getTitle().getString(), headerTextWidth),
                contentX, GuiTokens.opticalTextY(titleY, BTN_H), TEXT, false);

        Component count = countLabel();
        if (count != null) {
            String text = count.getString();
            int chipsRight = (miningCultChip != null ? miningCultChip.getX() + miningCultChip.getWidth()
                    : hideInstalledChip == null ? contentX : hideInstalledChip.getX() + hideInstalledChip.getWidth());
            String clipped = font.plainSubstrByWidth(text, Math.max(0, contentRight - chipsRight - GAP));
            graphics.text(font, clipped, contentRight - font.width(clipped), chipsY + 3, TEXT_MUTED, false);
        }

        if (listH > 0) {
            graphics.fill(listX, listY, listX + listW, listY + listH, SURFACE_SUBTLE);
            renderListState(graphics);
            renderListScrollbar(graphics);
        }

        CatalogRoutePreview.Point hovered = null;
        if (detailH > 0) {
            CatalogRouteSummary selected = selectedRoute();
            graphics.fill(detailX, detailY, detailX + detailW, detailY + detailH, SURFACE_SUBTLE);
            hovered = renderDetails(graphics, selected, backgroundMouseX, backgroundMouseY);
        }

        if (!statusText.getString().isBlank() && statusMaxW > 0) {
            String clipped = GuiTokens.ellipsize(font, statusText.getString(), statusMaxW);
            graphics.text(font, clipped, statusX, statusY, statusColor, false);
        }
        super.extractRenderState(graphics, backgroundMouseX, backgroundMouseY, partial);
        if (zoneDropdownOpen) renderZoneDropdown(graphics, mouseX, mouseY);
        if (sortMenu.isOpen()) sortMenu.render(graphics, font, mouseX, mouseY);
        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, CatalogRoutePreview.tooltip(hovered).stream()
                    .map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    /** "12 routes", or "50+ routes" while more pages exist; nothing while the first page loads. */
    private Component countLabel() {
        if (listLoading && !appending) return null;
        if (browser.routes().isEmpty()) return null;
        return browser.nextCursor() != null
                ? Component.translatable("waypointer.screen.route_catalog.count_more",
                        formatCount(browser.routes().size()))
                : routeCount(browser.routes().size());
    }

    private void renderListState(GuiGraphicsExtractor graphics) {
        if (listLoading && !appending) {
            drawCentered(graphics,
                    Component.translatable("waypointer.screen.route_catalog.status.loading"),
                    listX, listY, listW, routeRowsHeight(), TEXT_DIM);
            return;
        }
        if (!browser.routes().isEmpty()) return;

        if (lastLoadFailed) {
            drawCentered(graphics, Component.translatable(
                            "waypointer.screen.route_catalog.empty.error"),
                    listX, listY, listW, listH, TEXT_MUTED);
            return;
        }
        Component empty = Component.translatable(emptyListKey());
        drawCentered(graphics, empty, listX, listY, listW, listH, TEXT_DIM);
        if (!browser.query().isBlank()) {
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
        int total = browser.routes().size();
        int visible = visibleRowCount();
        if (total <= visible) return;
        int trackX = listX + listW - SCROLLBAR_W - SCROLLBAR_INSET;
        int trackY = listY + SCROLLBAR_INSET;
        int trackH = Math.max(1, routeRowsHeight() - SCROLLBAR_INSET * 2);
        int thumbH = Math.max(10, trackH * visible / total);
        int travel = Math.max(0, trackH - thumbH);
        int max = Math.max(1, total - visible);
        int thumbY = trackY + travel * browser.scrollOffset() / max;
        graphics.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, BORDER);
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, TEXT_MUTED);
    }

    private CatalogRoutePreview.Point renderDetails(GuiGraphicsExtractor graphics, CatalogRouteSummary route,
                                                    int mouseX, int mouseY) {
        descriptionMaxScroll = 0;
        if (detailH <= 0) return null;
        int pad = GAP + 2;
        int x = detailX + pad;
        int right = detailX + detailW - pad;
        int available = Math.max(20, right - x);
        int bottom = detailY + detailH - pad;
        if (route == null) {
            drawCentered(graphics,
                    Component.translatable(listLoading
                            ? "waypointer.screen.route_catalog.details.loading"
                            : "waypointer.screen.route_catalog.details.select"),
                    detailX, detailY, detailW, detailH, TEXT_DIM);
            return null;
        }

        // Title with the Mining Cult badge, then zone and publisher, then size and age.
        int y = detailY + pad;
        boolean miningCult = route.hasTag(CatalogRouteSummary.TAG_MINING_CULT);
        int badgeW = miningCult ? CatalogRouteRowButton.miningCultWidth(font) + GAP : 0;
        Component title = Component.literal(GuiTokens.ellipsize(font, route.title(), available - badgeW))
                .withStyle(net.minecraft.ChatFormatting.BOLD);
        graphics.text(font, title, x, y, TEXT, false);
        if (miningCult) {
            int pillX = right - badgeW + GAP;
            CatalogRouteRowButton.drawMiningCult(graphics, font, pillX, y);
            CatalogRouteRowButton.miningCultTooltip(graphics, font, pillX, y, mouseX, mouseY);
        }
        y += DETAIL_LINE_H + 3;
        CatalogRouteRowButton.drawZoneAndPublisher(graphics, font, route, x, y, available);
        y += DETAIL_LINE_H + 1;
        graphics.text(font, GuiTokens.ellipsize(font, detailFacts(route), available), x, y, TEXT_MUTED, false);
        y += DETAIL_LINE_H + GAP_TIGHT;

        // Install state, when the route is already here.
        CatalogInstallState state = installState(route);
        Component stateLine = installStateLine(state, route);
        int stateH = stateLine == null ? 0 : DETAIL_LINE_H + GAP_TIGHT;

        // Map preview: roughly half the pane, leaving room for a few description lines.
        int descriptionMin = DETAIL_LINE_H * 3;
        int mapH = Math.max(0, Math.min(220, Math.max(56, (bottom - y - stateH) * 55 / 100)));
        if (bottom - y - stateH - mapH - GAP < descriptionMin) mapH = Math.max(0, bottom - y - stateH - GAP - descriptionMin);
        CatalogRoutePreview.Point hovered = null;
        if (mapH >= 40) {
            hovered = renderPreview(graphics, route, x, y, right, y + mapH, mouseX, mouseY);
            y += mapH + GAP;
        }
        if (stateLine != null) {
            graphics.text(font, GuiTokens.ellipsize(font, stateLine.getString(), available), x, y,
                    state.action() == CatalogInstallState.Action.UPDATE
                            || state.action() == CatalogInstallState.Action.REPAIR
                            ? GuiTokens.WARNING : GuiTokens.SUCCESS, false);
            y += stateH;
        }

        int bodyMaxH = Math.max(0, bottom - y);
        if (bodyMaxH >= DETAIL_LINE_H) {
            Component body = route.description().isBlank()
                    ? Component.translatable("waypointer.screen.route_catalog.description.empty")
                    : Component.literal(route.description());
            drawWrapped(graphics, body, x, y, available, bodyMaxH,
                    route.description().isBlank() ? TEXT_MUTED : TEXT_DIM);
        }
        return hovered;
    }

    private CatalogRoutePreview.Point renderPreview(GuiGraphicsExtractor graphics, CatalogRouteSummary route,
                                                    int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        CatalogRoutePreview preview = previews.get(route.id());
        if (preview != null) return preview.render(graphics, font, x1, y1, x2, y2, mouseX, mouseY);
        graphics.fill(x1, y1, x2, y2, 0x40000000);
        graphics.fill(x1, y1, x2, y1 + 1, BORDER);
        graphics.fill(x1, y2 - 1, x2, y2, BORDER);
        graphics.fill(x1, y1, x1 + 1, y2, BORDER);
        graphics.fill(x2 - 1, y1, x2, y2, BORDER);
        Component message = Component.translatable(previewFailures.contains(route.id())
                ? "waypointer.screen.route_catalog.preview.unavailable"
                : "waypointer.screen.route_catalog.preview.loading");
        drawCentered(graphics, message, x1, y1, x2 - x1, y2 - y1, TEXT_MUTED);
        return null;
    }

    /** "16 waypoints · 2 installs · updated 3 days ago". */
    private String detailFacts(CatalogRouteSummary route) {
        List<String> parts = new ArrayList<>();
        parts.add(waypointCount(route.waypointCount()).getString());
        if (route.groupCount() > 1) parts.add(groupCount(route.groupCount()).getString());
        parts.add(installCount(route.downloads()).getString());
        Component age = ageLabel(route.updatedAt().isBlank() ? route.createdAt() : route.updatedAt());
        if (age != null) parts.add(age.getString());
        return String.join("  \u00B7  ", parts);
    }

    private static Component installStateLine(CatalogInstallState state, CatalogRouteSummary route) {
        return switch (state.action()) {
            case INSTALLED -> Component.translatable("waypointer.screen.route_catalog.state.installed_line");
            case LOCAL_NEWER -> Component.translatable("waypointer.screen.route_catalog.state.local_newer_line");
            case UPDATE -> Component.translatable("waypointer.screen.route_catalog.state.update_line",
                    state.localVersion(), route.version());
            case REPAIR -> Component.translatable("waypointer.screen.route_catalog.state.repair_line");
            default -> null;
        };
    }

    /** "updated 3 days ago" from an ISO-8601 timestamp, or {@code null} when it cannot be read. */
    static Component ageLabel(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        java.time.Instant then;
        try {
            then = java.time.OffsetDateTime.parse(timestamp).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            try {
                then = java.time.Instant.parse(timestamp);
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
        long days = Math.max(0, java.time.Duration.between(then, java.time.Instant.now()).toDays());
        String base = "waypointer.screen.route_catalog.age.";
        if (days == 0) return Component.translatable(base + "today");
        if (days == 1) return Component.translatable(base + "yesterday");
        if (days < 30) return Component.translatable(base + "days", days);
        if (days < 365) {
            long months = Math.max(1, days / 30);
            return months == 1 ? Component.translatable(base + "month")
                    : Component.translatable(base + "months", months);
        }
        long years = days / 365;
        return years == 1 ? Component.translatable(base + "year")
                : Component.translatable(base + "years", years);
    }

    private void drawWrapped(GuiGraphicsExtractor graphics, Component text,
                             int x, int y, int width, int maxHeight, int color) {
        maxHeight = Math.max(DETAIL_LINE_H, maxHeight / DETAIL_LINE_H * DETAIL_LINE_H);
        List<net.minecraft.util.FormattedCharSequence> split = font.split(
                text, Math.max(1, width - GAP));
        int contentHeight = split.size() * DETAIL_LINE_H;
        descriptionMaxScroll = Math.max(0, contentHeight - maxHeight);
        descriptionScroll = Math.min(descriptionScroll, descriptionMaxScroll);
        graphics.enableScissor(x, y, x + width, y + maxHeight);
        for (int i = descriptionScroll / DETAIL_LINE_H; i < split.size(); i++) {
            int lineY = y + i * DETAIL_LINE_H - descriptionScroll;
            if (lineY >= y + maxHeight) break;
            graphics.text(font, split.get(i), x, lineY, color, false);
        }
        graphics.disableScissor();
        int[] thumb = dropdownThumb(maxHeight, contentHeight,
                descriptionScroll, descriptionMaxScroll);
        if (thumb != null) graphics.fill(x + width - 2, y + thumb[0],
                x + width, y + thumb[0] + thumb[1], TEXT_MUTED);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double horizontal, double vertical) {
        if (sortMenu.isOpen()) return true;
        if (zoneDropdownOpen) {
            ZoneDropdownGeometry geo = zoneDropdownGeometry(zoneDropdownIds.size());
            if (mouseX >= geo.x1() && mouseX < geo.x2()
                    && mouseY >= geo.y1() && mouseY < geo.y2()) {
                zoneDropdownScroll = WaypointerScreen.dropdownScrollAfterWheel(
                        zoneDropdownScroll, vertical, zoneDropdownIds.size(),
                        geo.rowsBottom() - geo.rowsTop());
                return true;
            }
            return true;
        }
        if (detailH > 0 && mouseX >= detailX && mouseX < detailX + detailW
                && mouseY >= detailY && mouseY < detailY + detailH) {
            descriptionScroll = Math.max(0, Math.min(descriptionMaxScroll,
                    descriptionScroll - (int) Math.signum(vertical) * DETAIL_LINE_H * 2));
            return true;
        }
        if (mouseX < listX || mouseX > listX + listW
                || mouseY < listY || mouseY > listY + listH) {
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        if (!browser.scrollBy(-(int) Math.signum(vertical), visibleRowCount())) return false;
        rebuildWidgets();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (sortMenu.isOpen()) {
            if (event.key() == GLFW_KEY_ESCAPE) sortMenu.close();
            else sortMenu.keyPressed(event.key());
            return true;
        }
        if (zoneDropdownOpen && event.key() == GLFW_KEY_ESCAPE) {
            closeZoneDropdown();
            return true;
        }
        if (showingDetail && event.key() == GLFW_KEY_ESCAPE) {
            onBack();
            return true;
        }
        if (event.key() == GLFW_KEY_F && controlDown() && searchBox != null) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if (event.key() == GLFW_KEY_F5) {
            requestManualRefresh();
            return true;
        }
        if (zoneDropdownOpen) return super.keyPressed(event);
        if (event.key() == GLFW_KEY_DOWN) return moveSelection(1);
        if (event.key() == GLFW_KEY_UP) return moveSelection(-1);
        if (event.key() == GLFW_KEY_PAGE_DOWN) return moveSelection(visibleRowCount());
        if (event.key() == GLFW_KEY_PAGE_UP) return moveSelection(-visibleRowCount());
        if (searchBox == null || !searchBox.isFocused()) {
            if (event.key() == GLFW_KEY_HOME) return moveSelection(-browser.routes().size());
            if (event.key() == GLFW_KEY_END) return moveSelection(browser.routes().size());
        }
        boolean enter = event.key() == GLFW_KEY_ENTER || event.key() == GLFW_KEY_KP_ENTER;
        if (enter && searchBox != null && searchBox.isFocused()) {
            // Displayed results are never refetched; only a pending edit runs.
            if (browser.searchPending()) runPendingSearch();
            return true;
        }
        if (enter && selectedRoute() != null && !detailLoading
                && (getFocused() == null || getFocused() instanceof CatalogRouteRowButton row
                        && row.route.id().equals(browser.selectedRouteId()))) {
            if (!wideLayout && !showingDetail) {
                openRoute(selectedRoute());
            } else {
                installSelectedRoute();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean moveSelection(int delta) {
        if (browser.routes().isEmpty()) return false;
        int current = -1;
        for (int index = 0; index < browser.routes().size(); index++) {
            if (browser.routes().get(index).id().equals(browser.selectedRouteId())) {
                current = index;
                break;
            }
        }
        int next = selectionIndex(current, delta, browser.routes().size());
        refocusSearchAfterRebuild = false;
        if (searchBox != null) searchBox.setFocused(false);
        int priorScroll = browser.scrollOffset();
        browser.scrollIntoView(next, visibleRowCount());
        if (next != current) {
            selectRoute(browser.routes().get(next));
        } else if (browser.scrollOffset() != priorScroll) {
            rebuildWidgets();
        }
        for (var child : children()) {
            if (child instanceof CatalogRouteRowButton row
                    && row.route.id().equals(browser.selectedRouteId())) {
                setFocused(row);
                break;
            }
        }
        return true;
    }

    static int selectionIndex(int current, int delta, int count) {
        if (count <= 0) return -1;
        return current < 0 ? (delta > 0 ? 0 : count - 1)
                : Math.max(0, Math.min(count - 1, current + delta));
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public void removed() {
        requests.deactivate();
        previewTicket++;
        previewLoading.clear();
        previewQueue.clear();
        if (listLoading || appending) {
            // Retry interrupted requests on return, keeping loaded rows and cache.
            restartAppendOnReentry = appending && browser.nextCursor() != null;
            listRequested = false;
            listLoading = false;
            appending = false;
        }
        if (detailLoading) {
            detailLoading = false;
            statusText = Component.empty();
            statusColor = TEXT_DIM;
        }
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
        if (searchViewDirty) {
            searchViewDirty = false;
            if (cachedSearchPage(searchCache, browser.zoneFilter(), browser.normalizedQuery(),
                    browser.tagFilter(), browser.sort()) != null) {
                runPendingSearch();
            } else {
                rebuildWidgets();
            }
        }
        tickPreview();
        int before = searchDebounceTicks;
        searchDebounceTicks = advanceSearchDebounce(before);
        if (searchDebounceFired(before, searchDebounceTicks)) runPendingSearch();
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
        CatalogRouteSummary selected = selectedRoute();
        CatalogInstallState.Action action = selected == null ? CatalogInstallState.Action.INSTALL
                : installState(selected).action();
        return Component.translatable(switch (action) {
            case INSTALLED, LOCAL_NEWER -> "waypointer.screen.route_catalog.installed";
            case UPDATE -> "waypointer.screen.route_catalog.update";
            case REPAIR -> "waypointer.screen.route_catalog.repair";
            default -> "waypointer.screen.route_catalog.install";
        });
    }

    private CatalogInstallState installState(CatalogRouteSummary route) {
        if (route == null) {
            return new CatalogInstallState(
                    CatalogInstallState.Action.INSTALL, 0, List.of());
        }
        return browser.installState(catalogClient.apiRoot(), manager, route);
    }

    private boolean installedState(CatalogRouteSummary route) {
        CatalogInstallState.Action action = installState(route).action();
        return action == CatalogInstallState.Action.INSTALLED
                || action == CatalogInstallState.Action.LOCAL_NEWER;
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

    static int advanceSearchDebounce(int ticksRemaining) {
        return Math.max(0, ticksRemaining - 1);
    }

    static boolean searchDebounceFired(int before, int after) {
        return before > 0 && after == 0;
    }

    static int refreshCooldownSeconds(
            boolean armed, long nowNanos, long allowedAtNanos) {
        if (!armed || nowNanos - allowedAtNanos >= 0) return 0;
        long remaining = allowedAtNanos - nowNanos;
        return (int) Math.min(10,
                (remaining + 999_999_999L) / 1_000_000_000L);
    }

    static Component friendlyFailure(Throwable failure) {
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

    static Component waypointCount(long value) {
        return pluralCount(
                "waypointer.screen.route_catalog.waypoint_count.one",
                "waypointer.screen.route_catalog.waypoint_count.many", value);
    }

    static Component installCount(long value) {
        return pluralCount(
                "waypointer.screen.route_catalog.install_count.one",
                "waypointer.screen.route_catalog.install_count.many", value);
    }

    private static Component routeCount(long value) {
        return pluralCount(
                "waypointer.screen.route_catalog.count.one",
                "waypointer.screen.route_catalog.count.many", value);
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

}
