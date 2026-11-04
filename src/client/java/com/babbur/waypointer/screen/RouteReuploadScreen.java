package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.PublishedSources;
import com.babbur.waypointer.i18n.Counts;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.catalog.CatalogProtocol;
import com.babbur.waypointer.catalog.CatalogPublication;
import com.babbur.waypointer.catalog.CatalogPublicationManager;
import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.CatalogRouteUpdate;
import com.babbur.waypointer.catalog.PublisherIdentity;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;

final class RouteReuploadScreen extends Screen implements RoutePickerHost {
    private static final int PANEL_W = 440;
    private static final int PANEL_MARGIN = 12;
    private static final int MAX_LISTED = 8;

    private final PublishedRouteEditScreen parent;
    private final WaypointerConfig config;
    private final RouteCatalogClient catalogClient;
    private final PublisherIdentity identity;
    private final CatalogPublicationRegistry registry;
    private final CatalogPublication publication;
    private final List<WaypointGroup> routes = new ArrayList<>();

    private boolean uploading;
    private boolean screenActive;
    private Component status = Component.translatable("waypointer.screen.route_reupload.status.empty");
    private int statusColor = TEXT_DIM;

    private Button addButton;
    private Button clearButton;
    private Button uploadButton;
    private Button backButton;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int listY;
    private int statusY;

    RouteReuploadScreen(
            PublishedRouteEditScreen parent, WaypointerConfig config, RouteCatalogClient catalogClient,
            PublisherIdentity identity, CatalogPublicationRegistry registry,
            CatalogPublication publication) {
        super(Component.translatable("waypointer.screen.route_reupload.title"));
        this.parent = parent;
        this.config = config;
        this.catalogClient = catalogClient;
        this.identity = identity;
        this.registry = registry;
        this.publication = publication;
    }

    @Override
    protected void init() {
        screenActive = true;
        panelW = Math.min(PANEL_W, Math.max(240, width - PANEL_MARGIN * 2));
        panelH = Math.min(300, Math.max(200, height - PANEL_MARGIN * 2));
        panelX = Math.max(0, (width - panelW) / 2);
        panelY = Math.max(0, (height - panelH) / 2);
        contentX = panelX + PAD_OUTER;
        contentW = Math.max(1, panelW - PAD_OUTER * 2);
        int footerY = panelY + panelH - PAD_OUTER - BTN_H;
        statusY = footerY - 14;
        int actionsY = panelY + 34 + 24;
        listY = actionsY + BTN_H + GAP;

        int half = (contentW - GAP) / 2;
        addButton = styledButton(contentX, actionsY, half, BTN_H,
                Component.translatable("waypointer.screen.route_reupload.action.add_routes"),
                button -> openPicker(minecraft),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_reupload.action.add_routes.tooltip")));
        clearButton = styledButton(contentX + half + GAP, actionsY, half, BTN_H,
                Component.translatable("waypointer.screen.route_reupload.action.clear"),
                button -> { routes.clear(); refreshButtons(); }, null);
        addRenderableWidget(addButton);
        addRenderableWidget(clearButton);

        backButton = styledButton(contentX, footerY, 64, BTN_H,
                Component.translatable("gui.back"), button -> onClose(), null);
        addRenderableWidget(backButton);
        int uploadW = Math.min(140, Math.max(72, contentW / 3));
        uploadButton = styledButton(contentX + contentW - uploadW, footerY, uploadW, BTN_H,
                Component.translatable("waypointer.screen.route_reupload.action.upload"),
                button -> upload(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_reupload.action.upload.tooltip")));
        addRenderableWidget(uploadButton);
        refreshButtons();
    }

    void openPicker(Minecraft client) {
        if (uploading) return;
        WaypointerScreen picker = new WaypointerScreen(WaypointerClient.manager(), config);
        picker.publishDraft = this;
        picker.selectedZoneId = routeKind() == WaypointGroup.RouteKind.DUNGEON
                ? WaypointerZoneCatalog.DUNGEON_ROOMS_ZONE_ID
                : routes.isEmpty() ? publication.zoneId() : routes.getFirst().zoneId();
        MinecraftCompat.setScreen(client, picker);
    }

    @Override
    public Screen screen() {
        return this;
    }

    @Override
    public boolean accepts(WaypointGroup group) {
        return CatalogPublishFormModel.eligible(group)
                && (routes.isEmpty() || group.routeKind() == routes.getFirst().routeKind());
    }

    @Override
    public boolean contains(WaypointGroup group) {
        return group != null && routes.stream().anyMatch(existing -> existing.id().equals(group.id()));
    }

    @Override
    public WaypointGroup.RouteKind routeKind() {
        return routes.isEmpty() ? null : routes.getFirst().routeKind();
    }

    @Override
    public int routeCount() {
        return routes.size();
    }

    @Override
    public void addRoutes(List<WaypointGroup> additions) {
        if (uploading || additions == null) return;
        for (WaypointGroup group : additions) {
            if (accepts(group) && !contains(group)) routes.add(group);
        }
        refreshButtons();
    }

    @Override
    public void removeRoutes(List<WaypointGroup> removals) {
        if (uploading || removals == null) return;
        List<String> ids = removals.stream().map(WaypointGroup::id).toList();
        routes.removeIf(group -> ids.contains(group.id()));
        refreshButtons();
    }

    static String zoneIdFor(List<WaypointGroup> routes) {
        if (routes.isEmpty()) return null;
        if (routes.getFirst().routeKind() == WaypointGroup.RouteKind.DUNGEON) return "dungeon";
        return routes.stream().map(WaypointGroup::zoneId).distinct().count() == 1
                ? routes.getFirst().zoneId() : "multiple";
    }

    private void refreshButtons() {
        if (addButton != null) addButton.active = !uploading;
        if (clearButton != null) clearButton.active = !uploading && !routes.isEmpty();
        if (uploadButton != null) {
            uploadButton.active = !uploading && !routes.isEmpty() && identity != null;
            uploadButton.setMessage(Component.translatable(uploading
                    ? "waypointer.screen.route_reupload.action.uploading"
                    : "waypointer.screen.route_reupload.action.upload"));
        }
        if (backButton != null) backButton.active = !uploading;
        if (!uploading && statusColor != GuiTokens.DANGER) {
            long waypoints = routes.stream().mapToLong(WaypointGroup::size).sum();
            status = routes.isEmpty()
                    ? Component.translatable("waypointer.screen.route_reupload.status.empty")
                    : Component.translatable("waypointer.screen.route_reupload.status.ready",
                            Counts.of("route", routes.size()), Counts.of("waypoint", waypoints));
            statusColor = TEXT_DIM;
        }
    }

    static CatalogRouteUpdate replacementUpdate(CatalogRouteUpdate metadata, String payload, String zoneId) {
        return new CatalogRouteUpdate(metadata.title(), metadata.description(), metadata.visibility(),
                payload, zoneId);
    }

    private void upload() {
        if (uploading || routes.isEmpty() || identity == null) return;
        uploading = true;
        statusColor = TEXT_DIM;
        status = Component.translatable("waypointer.screen.route_reupload.status.uploading");
        refreshButtons();
        List<WaypointGroup> chosen = List.copyOf(routes);
        String zoneId = zoneIdFor(chosen);
        CatalogRouteUpdate metadata = parent.metadataUpdate();
        CompletableFuture.supplyAsync(() -> CatalogProtocol.prepareCatalogPayload(chosen))
                .thenCompose(prepared -> CatalogPublicationManager.update(
                        catalogClient, identity, registry, publication,
                        replacementUpdate(metadata, prepared.payload(), zoneId),
                        prepared.payloadSha256()))
                .whenComplete((updated, failure) -> runOnClient(() -> {
                    if (!screenActive || minecraft == null) return;
                    uploading = false;
                    if (failure != null) {
                        status = RoutePublishScreen.friendlyFailure(failure);
                        statusColor = GuiTokens.DANGER;
                        refreshButtons();
                        return;
                    }
                    var sources = PublishedSources.active();
                    if (sources != null) {
                        sources.remember(publication.routeId(), chosen.stream().map(WaypointGroup::id).toList());
                    }
                    parent.finishUpdate(minecraft, updated);
                }));
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);
        graphics.text(font, font.plainSubstrByWidth(getTitle().getString(), contentW),
                contentX, panelY + PAD_OUTER, TEXT, false);
        graphics.text(font, font.plainSubstrByWidth(Component.translatable(
                        "waypointer.screen.route_reupload.subtitle", publication.title()).getString(), contentW),
                contentX, panelY + 34, TEXT_DIM, false);
        int y = listY;
        int limit = Math.min(routes.size(), MAX_LISTED);
        for (int index = 0; index < limit && y + font.lineHeight <= statusY - GAP; index++) {
            WaypointGroup group = routes.get(index);
            String line = (index + 1) + ". " + group.name().trim() + "  \u00b7  " + group.size();
            graphics.text(font, font.plainSubstrByWidth(line, contentW), contentX, y, TEXT_DIM, false);
            y += font.lineHeight + 2;
        }
        if (routes.size() > limit && y + font.lineHeight <= statusY - GAP) {
            graphics.text(font, Component.translatable(
                    "waypointer.screen.route_reupload.more", routes.size() - limit), contentX, y, TEXT_MUTED, false);
        }
        if (!status.getString().isBlank()) {
            graphics.text(font, font.plainSubstrByWidth(status.getString(), contentW),
                    contentX, statusY, statusColor, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (uploading) return;
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
}
