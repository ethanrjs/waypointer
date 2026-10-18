package com.babbur.waypointer.screen;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.catalog.CatalogApiException;
import com.babbur.waypointer.catalog.CatalogProtocol;
import com.babbur.waypointer.catalog.CatalogPublication;
import com.babbur.waypointer.catalog.CatalogPublicationManager;
import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.catalog.CatalogRouteUpdate;
import com.babbur.waypointer.catalog.PublishedSources;
import com.babbur.waypointer.catalog.PublisherIdentity;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.babbur.waypointer.screen.GuiTokens.*;

public final class PublishedRoutesScreen extends Screen {
    private static final int PANEL_MARGIN = 12;
    private static final int ROW_H = CatalogRouteRowButton.HEIGHT;
    private static final int WIDE_BREAKPOINT = 420;
    private static final int LINE_H = 11;
    private static final int PUBLIC_COLOR = 0xFF6AA9FF;

    enum LinkState { UNKNOWN, NOT_LINKED, MISSING, SAME, CHANGED }

    private final Screen parent;
    private final RouteCatalogClient catalogClient;
    private final PublisherIdentityStore identityStore;
    private final CatalogPublicationRegistry publicationRegistry;

    private final PublishedRoutesModel model = new PublishedRoutesModel();
    private final Map<String, CatalogRouteSummary> summaries = new HashMap<>();
    private final Map<String, CatalogRoutePreview> previews = new HashMap<>();
    private final Set<String> requested = new HashSet<>();
    private final Map<String, LinkState> links = new HashMap<>();
    private PublisherIdentity identity;
    private boolean loading = true;
    private boolean busy;
    private boolean screenActive;
    private Component status = Component.empty();
    private int statusColor = TEXT_DIM;
    private int scroll;

    private EditBox searchBox;
    private Button copyButton;
    private Button editButton;
    private Button publishButton;
    private Button deleteButton;
    private int panelX, panelY, panelW, panelH, contentX, contentW;
    private int listX, listY, listW, listH, detailX, detailY, detailW, detailH;
    private int statusY;

    PublishedRoutesScreen(Screen parent, RouteCatalogClient catalogClient,
                          PublisherIdentityStore identityStore, CatalogPublicationRegistry publicationRegistry) {
        super(Component.translatable("waypointer.screen.published_routes.title"));
        this.parent = parent;
        this.catalogClient = catalogClient;
        this.identityStore = identityStore;
        this.publicationRegistry = publicationRegistry;
        model.setRowsPerPage(1000);
    }

    static void open(Screen parent, RouteCatalogClient catalogClient,
                     PublisherIdentityStore identityStore, CatalogPublicationRegistry publicationRegistry) {
        MinecraftCompat.setScreen(Minecraft.getInstance(), new PublishedRoutesScreen(
                parent, catalogClient, identityStore, publicationRegistry));
    }

    @Override
    protected void init() {
        screenActive = true;
        boolean compact = height < 300;
        int margin = compact ? GAP_TIGHT : PANEL_MARGIN;
        panelX = width > margin * 2 ? margin : 0;
        panelY = height > margin * 2 ? margin : 0;
        panelW = Math.max(1, width - panelX * 2);
        panelH = Math.max(1, height - panelY * 2);
        int pad = compact || panelW < 320 ? GAP : PAD_OUTER;
        contentX = panelX + pad;
        contentW = Math.max(40, panelW - pad * 2);
        int titleY = panelY + pad;

        int toolbarY = titleY + BTN_H + GAP;
        searchBox = new EditBox(font, contentX, toolbarY, contentW, BTN_H,
                Component.translatable("waypointer.screen.published_routes.search"));
        searchBox.setMaxLength(80);
        searchBox.setHint(Component.translatable("waypointer.screen.published_routes.search"));
        searchBox.setValue(model.query());
        searchBox.setResponder(query -> {
            model.setQuery(query);
            scroll = 0;
            rebuildWidgets();
            setFocused(searchBox);
        });
        addRenderableWidget(searchBox);

        int footerY = panelY + panelH - pad - BTN_H;
        statusY = footerY - 12;
        int bodyY = toolbarY + BTN_H + GAP;
        int bodyH = Math.max(40, statusY - GAP_TIGHT - bodyY);
        boolean wide = contentW >= WIDE_BREAKPOINT;
        listX = contentX;
        listY = bodyY;
        listW = RouteCatalogScreen.catalogListWidth(contentW);
        listH = bodyH;
        detailX = wide ? listX + listW + GAP : contentX;
        detailY = bodyY;
        detailW = wide ? contentX + contentW - detailX : 0;
        detailH = wide ? bodyH : 0;

        addRenderableWidget(styledButton(contentX, footerY, 64, BTN_H,
                Component.translatable("gui.back"), button -> onClose(), null));
        int right = contentX + contentW;
        publishButton = footerButton(right, footerY, publishLabel(), this::publishOrChoose);
        right = publishButton.getX() - GAP_TIGHT;
        editButton = footerButton(right, footerY,
                Component.translatable("waypointer.screen.published_routes.action.edit"), this::editSelected);
        right = editButton.getX() - GAP_TIGHT;
        copyButton = footerButton(right, footerY,
                Component.translatable("waypointer.screen.published_routes.action.copy_code"), this::copyCode);
        right = copyButton.getX() - GAP_TIGHT;
        deleteButton = footerButton(right, footerY, colored(Component.translatable(
                "waypointer.screen.published_routes.action.delete"), DANGER), this::confirmDelete);

        addRows();
        refreshButtons();
        if (loading) loadRecords();
    }

    private Button footerButton(int right, int y, Component label, Runnable action) {
        int w = font.width(label) + 16;
        Button button = styledButton(right - w, y, w, BTN_H, label, b -> action.run(), null);
        addRenderableWidget(button);
        return button;
    }


    private void loadRecords() {
        loading = false;
        CompletableFuture.supplyAsync(() -> {
            if (!Files.isRegularFile(identityStore.file(), LinkOption.NOFOLLOW_LINKS)) {
                return new Loaded(null, List.<CatalogPublication>of());
            }
            PublisherIdentity loadedIdentity = identityStore.load();
            return new Loaded(loadedIdentity, PublishedRoutesUiState.forApiRoot(
                    publicationRegistry.listForPublisher(loadedIdentity.publisherId()), catalogClient.apiRoot()));
        }).whenComplete((loaded, failure) -> runOnClient(() -> {
            if (!screenActive) return;
            if (failure != null) {
                status = Component.translatable("waypointer.screen.published_routes.status.load_failed");
                statusColor = DANGER;
            } else {
                identity = loaded.identity();
                model.replace(loaded.publications());
                if (model.selected() == null && !model.publications().isEmpty()) {
                    model.select(model.publications().getFirst().routeId());
                }
                for (CatalogPublication publication : model.publications()) fetch(publication);
            }
            rebuildWidgets();
        }));
    }

    private void fetch(CatalogPublication publication) {
        String id = publication.routeId();
        if (!requested.add(id)) return;
        catalogClient.getRoute(id).whenComplete((details, failure) -> runOnClient(() -> {
            if (failure == null && details != null) summaries.put(id, details.summary());
        }));
        catalogClient.getRoutePreview(id).whenComplete((data, failure) -> runOnClient(() -> {
            if (failure == null && data != null) previews.put(id, CatalogRoutePreview.of(data));
        }));
        checkLink(publication);
    }

    private void checkLink(CatalogPublication publication) {
        List<WaypointGroup> sources = sources(publication);
        if (sources == null) {
            links.put(publication.routeId(), linkedIds(publication).isEmpty() ? LinkState.NOT_LINKED : LinkState.MISSING);
            return;
        }
        links.put(publication.routeId(), LinkState.UNKNOWN);
        List<WaypointGroup> snapshots = sources.stream().map(WaypointGroup::exportSnapshot).toList();
        CompletableFuture.supplyAsync(() -> CatalogProtocol.prepareCatalogPayload(snapshots).payloadSha256())
                .whenComplete((sha, failure) -> runOnClient(() -> {
                    links.put(publication.routeId(), failure != null ? LinkState.NOT_LINKED
                            : sha.equals(publication.payloadSha256()) ? LinkState.SAME : LinkState.CHANGED);
                    refreshButtons();
                }));
    }

    private static List<String> linkedIds(CatalogPublication publication) {
        PublishedSources store = PublishedSources.active();
        return store == null ? List.of() : store.groups(publication.routeId());
    }

    private static List<WaypointGroup> sources(CatalogPublication publication) {
        List<String> ids = linkedIds(publication);
        var manager = WaypointerClient.manager();
        if (ids.isEmpty() || manager == null) return null;
        List<WaypointGroup> groups = new ArrayList<>(ids.size());
        for (String id : ids) {
            WaypointGroup group = manager.get(id);
            if (group == null) return null;
            groups.add(group);
        }
        return groups;
    }

    private LinkState link(CatalogPublication publication) {
        return publication == null ? LinkState.UNKNOWN : links.getOrDefault(publication.routeId(), LinkState.UNKNOWN);
    }


    private int visibleRows() {
        return Math.max(1, listH / ROW_H);
    }

    private void addRows() {
        List<CatalogPublication> rows = model.publications();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows())));
        for (int row = 0; row < visibleRows(); row++) {
            int index = scroll + row;
            if (index >= rows.size()) break;
            CatalogPublication publication = rows.get(index);
            boolean selected = publication.routeId().equals(model.selectedRouteId());
            boolean nextSelected = index + 1 < rows.size()
                    && rows.get(index + 1).routeId().equals(model.selectedRouteId());
            addRenderableWidget(new Row(listX, listY + row * ROW_H, listW, ROW_H, publication,
                    selected, !selected && !nextSelected));
        }
    }

    private final class Row extends AbstractButton {
        private final CatalogPublication publication;
        private final boolean selected;
        private final boolean divider;

        Row(int x, int y, int w, int h, CatalogPublication publication, boolean selected, boolean divider) {
            super(x, y, w, h, Component.literal(publication.title()));
            this.publication = publication;
            this.selected = selected;
            this.divider = divider;
        }

        @Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            select(publication.routeId());
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
            int background = selected ? SELECTED : isHoveredOrFocused() ? HOVER : 0;
            if (background != 0) g.fill(x1, y1, x2, y2, background);
            if (divider) g.fill(x1 + GAP, y2 - 1, x2 - GAP, y2, BORDER);
            if (selected) g.fill(x1, y1, x1 + 2, y2, ACCENT);
            Font font = Minecraft.getInstance().font;
            int textX = x1 + GAP, right = x2 - GAP;

            LinkState state = link(publication);
            Component changed = Component.translatable("waypointer.screen.published_routes.link.changed_short");
            int stateW = state == LinkState.CHANGED ? font.width(changed) + GAP : 0;
            if (stateW > 0) g.text(font, changed, right - font.width(changed), y1 + 6, WARNING, false);
            g.text(font, GuiTokens.ellipsize(font, publication.title(), Math.max(20, right - stateW - textX)),
                    textX, y1 + 6, TEXT, false);

            CatalogRouteSummary live = summaries.get(publication.routeId());
            String facts = Component.translatable("waypointer.screen.published_routes.version", publication.version())
                    .getString() + (live == null ? "" : "  ·  "
                    + RouteCatalogScreen.installCount(live.downloads()).getString());
            int factsW = font.width(facts);
            g.text(font, facts, right - factsW, y1 + 18, TEXT_MUTED, false);
            String zone = GuiTokens.ellipsize(font, Zone.fromId(publication.zoneId()).displayName(),
                    Math.max(20, (right - factsW - GAP - textX) * 3 / 5));
            g.text(font, zone, textX, y1 + 18, ACCENT, false);
            boolean isPublic = publication.visibility() == CatalogPublishRequest.Visibility.PUBLIC;
            String visibility = "  ·  " + Component.translatable(isPublic
                    ? "waypointer.screen.route_publish.visibility.public"
                    : "waypointer.screen.route_publish.visibility.unlisted").getString();
            int room = right - factsW - GAP - textX - font.width(zone);
            if (room > font.width(visibility)) {
                g.text(font, visibility, textX + font.width(zone), y1 + 18, isPublic ? PUBLIC_COLOR : TEXT_MUTED, false);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private void select(String routeId) {
        if (!model.select(routeId)) return;
        status = Component.empty();
        rebuildWidgets();
    }


    private Component publishLabel() {
        LinkState state = link(model.selected());
        return Component.translatable(state == LinkState.NOT_LINKED || state == LinkState.MISSING
                ? "waypointer.screen.published_routes.action.choose_routes"
                : "waypointer.screen.published_routes.action.publish_changes");
    }

    private void refreshButtons() {
        CatalogPublication selected = model.selected();
        boolean ready = selected != null && identity != null && !busy;
        LinkState state = link(selected);
        if (copyButton != null) copyButton.active = selected != null && !busy;
        if (editButton != null) editButton.active = ready;
        if (deleteButton != null) deleteButton.active = ready;
        if (publishButton != null) {
            publishButton.setMessage(publishLabel());
            publishButton.active = ready && state != LinkState.SAME && state != LinkState.UNKNOWN;
        }
        if (searchBox != null) searchBox.active = !busy;
    }

    private void copyCode() {
        CatalogPublication selected = model.selected();
        if (selected == null || minecraft == null) return;
        minecraft.keyboardHandler.setClipboard(UniversalShareCodec.encodeCatalogReference(selected.routeId()));
        status = Component.translatable("waypointer.screen.published_routes.status.copied_code");
        statusColor = SUCCESS;
    }

    private void editSelected() {
        CatalogPublication selected = model.selected();
        if (selected == null || identity == null || busy) return;
        MinecraftCompat.setScreen(minecraft, new PublishedRouteEditScreen(
                this, catalogClient, identity, publicationRegistry, selected));
    }

    private void publishOrChoose() {
        CatalogPublication selected = model.selected();
        if (selected == null || identity == null || busy) return;
        LinkState state = link(selected);
        if (state == LinkState.NOT_LINKED || state == LinkState.MISSING) {
            PublishedRouteEditScreen edit = new PublishedRouteEditScreen(
                    this, catalogClient, identity, publicationRegistry, selected);
            new RouteReuploadScreen(edit, WaypointerClient.config(), catalogClient, identity,
                    publicationRegistry, selected).openPicker(minecraft);
            return;
        }
        if (state != LinkState.CHANGED) return;
        List<WaypointGroup> sources = sources(selected);
        if (sources == null) return;
        ConfirmScreen confirm = new ConfirmScreen(confirmed -> {
            MinecraftCompat.setScreen(minecraft, this);
            if (confirmed) publishChanges(selected, sources);
        }, Component.translatable("waypointer.screen.published_routes.confirm_update.title"),
                Component.translatable("waypointer.screen.published_routes.confirm_update.message",
                        selected.title(), selected.version() + 1),
                Component.translatable("waypointer.screen.published_routes.action.publish_changes"),
                Component.translatable("gui.cancel"));
        MinecraftCompat.setScreen(minecraft, confirm);
    }

    private void publishChanges(CatalogPublication publication, List<WaypointGroup> sources) {
        busy = true;
        status = Component.translatable("waypointer.screen.route_reupload.status.uploading");
        statusColor = TEXT_DIM;
        refreshButtons();
        List<WaypointGroup> snapshots = sources.stream().map(WaypointGroup::exportSnapshot).toList();
        String zoneId = RouteReuploadScreen.zoneIdFor(snapshots);
        CompletableFuture.supplyAsync(() -> CatalogProtocol.prepareCatalogPayload(snapshots))
                .thenCompose(prepared -> CatalogPublicationManager.update(catalogClient, identity,
                        publicationRegistry, publication,
                        CatalogRouteUpdate.replacePayload(prepared.payload(), zoneId), prepared.payloadSha256()))
                .whenComplete((updated, failure) -> runOnClient(() -> {
                    busy = false;
                    if (failure != null) {
                        status = RoutePublishScreen.friendlyFailure(failure);
                        statusColor = DANGER;
                    } else {
                        requested.remove(publication.routeId());
                        refreshFromRegistry(Component.translatable(
                                "waypointer.screen.route_reupload.status.uploaded", updated.version()), SUCCESS);
                        return;
                    }
                    rebuildWidgets();
                }));
    }

    private void confirmDelete() {
        CatalogPublication selected = model.selected();
        if (selected == null || identity == null || busy) return;
        ConfirmScreen confirm = new ConfirmScreen(confirmed -> {
            MinecraftCompat.setScreen(minecraft, this);
            if (confirmed) delete(selected);
        }, Component.translatable("waypointer.screen.published_routes.confirm.title"),
                Component.translatable("waypointer.screen.published_routes.confirm.message", selected.title()),
                Component.translatable("waypointer.screen.published_routes.action.delete"),
                Component.translatable("gui.cancel"));
        MinecraftCompat.setScreen(minecraft, confirm);
    }

    private void delete(CatalogPublication selected) {
        busy = true;
        status = Component.translatable("waypointer.screen.published_routes.status.deleting");
        statusColor = TEXT_DIM;
        refreshButtons();
        CatalogPublicationManager.delete(catalogClient, identity, publicationRegistry, selected)
                .whenComplete((ignored, failure) -> runOnClient(() -> {
                    busy = false;
                    if (failure != null) {
                        status = friendlyDeleteFailure(failure);
                        statusColor = DANGER;
                    } else {
                        PublishedSources store = PublishedSources.active();
                        if (store != null) store.forget(selected.routeId());
                        refreshFromRegistry(Component.translatable(
                                "waypointer.screen.published_routes.status.deleted"), SUCCESS);
                        return;
                    }
                    rebuildWidgets();
                }));
    }

    void refreshFromRegistry(Component message, int color) {
        if (identity == null) return;
        String selected = model.selectedRouteId();
        model.replace(PublishedRoutesUiState.forApiRoot(
                publicationRegistry.listForPublisher(identity.publisherId()), catalogClient.apiRoot()));
        if (selected != null) model.select(selected);
        if (model.selected() == null && !model.publications().isEmpty()) {
            model.select(model.publications().getFirst().routeId());
        }
        for (CatalogPublication publication : model.publications()) {
            requested.remove(publication.routeId());
            fetch(publication);
        }
        status = message == null ? Component.empty() : message;
        statusColor = color;
        if (screenActive) rebuildWidgets();
    }

    private static Component friendlyDeleteFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof CatalogApiException api && api.status() == 429) {
            return Component.translatable("waypointer.screen.published_routes.status.rate_limited");
        }
        return Component.translatable("waypointer.screen.published_routes.status.delete_failed");
    }


    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, GuiTokens.panel(SURFACE));
        int titleY = searchBox == null ? panelY + PAD_OUTER : searchBox.getY() - BTN_H - GAP;
        g.text(font, getTitle(), contentX, titleY + 6, TEXT, false);
        String count = Component.translatable(model.publications().size() == 1
                ? "waypointer.screen.published_routes.count.one" : "waypointer.screen.published_routes.count.many",
                model.publications().size()).getString();
        if (!model.publications().isEmpty()) g.text(font, count, contentX + contentW - font.width(count), titleY + 6, TEXT_MUTED, false);

        g.fill(listX, listY, listX + listW, listY + listH, SURFACE_SUBTLE);
        if (model.publications().isEmpty() && !loading) {
            Component empty = Component.translatable(model.query().isBlank()
                    ? "waypointer.screen.published_routes.status.empty"
                    : "waypointer.screen.published_routes.status.no_matches");
            g.text(font, empty, listX + (listW - font.width(empty)) / 2, listY + listH / 2 - 4, TEXT_DIM, false);
        }
        CatalogRoutePreview.Point hovered = null;
        if (detailW > 0) {
            g.fill(detailX, detailY, detailX + detailW, detailY + detailH, SURFACE_SUBTLE);
            hovered = renderDetails(g, model.selected(), mouseX, mouseY);
        }
        if (!status.getString().isBlank()) {
            g.text(font, GuiTokens.ellipsize(font, status.getString(), contentW), contentX, statusY, statusColor, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partial);
        if (hovered != null) {
            g.setTooltipForNextFrame(font, CatalogRoutePreview.tooltip(hovered).stream()
                    .map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    private CatalogRoutePreview.Point renderDetails(GuiGraphicsExtractor g, CatalogPublication publication,
                                                    int mouseX, int mouseY) {
        int pad = GAP + 2;
        int x = detailX + pad, right = detailX + detailW - pad, available = Math.max(20, right - x);
        int bottom = detailY + detailH - pad;
        if (publication == null) {
            Component hint = Component.translatable("waypointer.screen.route_catalog.details.select");
            g.text(font, hint, detailX + (detailW - font.width(hint)) / 2, detailY + detailH / 2 - 4, TEXT_DIM, false);
            return null;
        }
        int y = detailY + pad;
        g.text(font, Component.literal(GuiTokens.ellipsize(font, publication.title(), available))
                .withStyle(net.minecraft.ChatFormatting.BOLD), x, y, TEXT, false);
        y += LINE_H + 3;

        boolean isPublic = publication.visibility() == CatalogPublishRequest.Visibility.PUBLIC;
        String zone = Zone.fromId(publication.zoneId()).displayName();
        g.text(font, zone, x, y, ACCENT, false);
        g.text(font, "  ·  " + Component.translatable(isPublic
                        ? "waypointer.screen.route_publish.visibility.public"
                        : "waypointer.screen.route_publish.visibility.unlisted").getString(),
                x + font.width(zone), y, isPublic ? PUBLIC_COLOR : TEXT_MUTED, false);
        y += LINE_H + 1;

        CatalogRouteSummary live = summaries.get(publication.routeId());
        List<String> facts = new ArrayList<>();
        facts.add(Component.translatable("waypointer.screen.published_routes.version", publication.version()).getString());
        if (live != null) {
            facts.add(RouteCatalogScreen.waypointCount(live.waypointCount()).getString());
            facts.add(RouteCatalogScreen.installCount(live.downloads()).getString());
        }
        String when = live != null && !live.updatedAt().isBlank() ? live.updatedAt() : publication.serverCreatedAt();
        Component age = RouteCatalogScreen.ageLabel(when);
        if (age != null) facts.add(age.getString());
        String factLine = String.join("  ·  ", facts);
        g.text(font, GuiTokens.ellipsize(font, factLine, available), x, y, TEXT_MUTED, false);
        if (age != null && font.width(factLine) <= available && mouseY >= y - 1 && mouseY < y + 9
                && mouseX >= x + font.width(factLine) - font.width(age) && mouseX < x + font.width(factLine)) {
            String exact = RouteCatalogScreen.exactTime(when);
            if (exact != null) g.setTooltipForNextFrame(font, Component.translatable(
                    "waypointer.screen.route_catalog.updated_at", exact), mouseX, mouseY);
        }
        y += LINE_H + GAP_TIGHT;

        LinkState state = link(publication);
        List<WaypointGroup> sources = sources(publication);
        String names = sources == null ? "" : String.join(", ", sources.stream().map(WaypointGroup::name).toList());
        Component linkLine = switch (state) {
            case SAME -> Component.translatable("waypointer.screen.published_routes.link.same", names);
            case CHANGED -> Component.translatable("waypointer.screen.published_routes.link.changed", names);
            case MISSING -> Component.translatable("waypointer.screen.published_routes.link.missing");
            case NOT_LINKED -> Component.translatable("waypointer.screen.published_routes.link.none");
            case UNKNOWN -> Component.empty();
        };
        int linkColor = switch (state) {
            case SAME -> SUCCESS;
            case CHANGED -> WARNING;
            default -> TEXT_MUTED;
        };

        int mapH = Math.max(0, Math.min(200, (bottom - y - LINE_H * 4) * 60 / 100));
        CatalogRoutePreview.Point hovered = null;
        CatalogRoutePreview preview = previews.get(publication.routeId());
        if (mapH >= 40) {
            if (preview != null) {
                hovered = preview.render(g, font, x, y, right, y + mapH, mouseX, mouseY);
            } else {
                g.fill(x, y, right, y + mapH, 0x40000000);
                Component loadingMap = Component.translatable("waypointer.screen.route_catalog.preview.loading");
                g.text(font, loadingMap, x + (available - font.width(loadingMap)) / 2, y + mapH / 2 - 4, TEXT_MUTED, false);
            }
            y += mapH + GAP;
        }
        if (!linkLine.getString().isBlank()) {
            for (var line : font.split(linkLine, available)) {
                if (y + LINE_H > bottom) break;
                g.text(font, line, x, y, linkColor, false);
                y += LINE_H;
            }
            y += GAP_TIGHT;
        }
        String description = publication.description().isBlank() ? "" : publication.description();
        for (var line : font.split(Component.literal(description), available)) {
            if (y + LINE_H > bottom) break;
            g.text(font, line, x, y, TEXT_DIM, false);
            y += LINE_H;
        }
        return hovered;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            int max = Math.max(0, model.publications().size() - visibleRows());
            int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(vertical)));
            if (next != scroll) {
                scroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void tick() {
        super.tick();
        refreshButtons();
    }

    @Override
    public void onClose() {
        if (busy) return;
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

    private void runOnClient(Runnable action) {
        Minecraft client = minecraft == null ? Minecraft.getInstance() : minecraft;
        client.execute(action);
    }

    private record Loaded(PublisherIdentity identity, List<CatalogPublication> publications) {}
}
