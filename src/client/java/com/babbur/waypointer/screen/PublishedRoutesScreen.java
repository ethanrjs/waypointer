package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogApiException;
import com.babbur.waypointer.catalog.CatalogPublication;
import com.babbur.waypointer.catalog.CatalogPublicationManager;
import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.PublisherIdentity;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.SELECTED;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;

public final class PublishedRoutesScreen extends Screen {
    private static final int PANEL_W = 440;
    private static final int PANEL_MARGIN = 12;
    private static final int ROW_H = 24;
    private static final int ROW_GAP = 2;
    private static final int STATUS_ERROR = 0xFFE47B7B;
    private static final int STATUS_OK = 0xFF7ACB89;

    private final Screen parent;
    private final RouteCatalogClient catalogClient;
    private final PublisherIdentityStore identityStore;
    private final CatalogPublicationRegistry publicationRegistry;

    private final PublishedRoutesModel model = new PublishedRoutesModel();
    private PublisherIdentity identity;
    private boolean loading = true;
    private boolean deleting;
    private boolean screenActive;
    private Component status = Component.translatable(
            "waypointer.screen.published_routes.status.loading");
    private int statusColor = TEXT_DIM;

    private Button previousButton;
    private Button nextButton;
    private Button copyButton;
    private Button deleteButton;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int rowsY;
    private int statusY;

    PublishedRoutesScreen(
            Screen parent, RouteCatalogClient catalogClient,
            PublisherIdentityStore identityStore,
            CatalogPublicationRegistry publicationRegistry) {
        super(Component.translatable("waypointer.screen.published_routes.title"));
        this.parent = parent;
        this.catalogClient = catalogClient;
        this.identityStore = identityStore;
        this.publicationRegistry = publicationRegistry;
    }

    static void open(
            Screen parent, RouteCatalogClient catalogClient,
            PublisherIdentityStore identityStore,
            CatalogPublicationRegistry publicationRegistry) {
        MinecraftCompat.setScreen(Minecraft.getInstance(), new PublishedRoutesScreen(
                parent, catalogClient, identityStore, publicationRegistry));
    }

    @Override
    protected void init() {
        screenActive = true;
        panelW = Math.min(PANEL_W, Math.max(240, width - PANEL_MARGIN * 2));
        panelH = Math.min(270, Math.max(190, height - PANEL_MARGIN * 2));
        panelX = Math.max(0, (width - panelW) / 2);
        panelY = Math.max(0, (height - panelH) / 2);
        contentX = panelX + PAD_OUTER;
        contentW = Math.max(1, panelW - PAD_OUTER * 2);
        rowsY = panelY + 46;
        int footerY = panelY + panelH - PAD_OUTER - BTN_H;
        statusY = footerY - 14;
        int pagerY = statusY - BTN_H - GAP;
        model.setRowsPerPage(PublishedRoutesLayout.rowsPerPage(
                rowsY, pagerY, ROW_H, ROW_GAP, GAP));

        addRenderableWidget(styledButton(contentX, footerY, 64, BTN_H,
                Component.translatable("gui.back"), button -> onClose(), null));

        int actionW = Math.min(88, Math.max(68, contentW / 4));
        deleteButton = styledButton(contentX + contentW - actionW, footerY,
                actionW, BTN_H,
                Component.translatable(deleting
                        ? "waypointer.screen.published_routes.action.deleting"
                        : "waypointer.screen.published_routes.action.delete"),
                button -> confirmDelete(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.published_routes.action.delete.tooltip")));
        addRenderableWidget(deleteButton);
        copyButton = styledButton(contentX + contentW - actionW * 2 - GAP,
                footerY, actionW, BTN_H,
                Component.translatable("waypointer.screen.published_routes.action.copy"),
                button -> copyLink(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.published_routes.action.copy.tooltip")));
        addRenderableWidget(copyButton);

        int pageW = 28;
        previousButton = styledButton(contentX, pagerY,
                pageW, BTN_H, Component.literal("<"), button -> changePage(-1), null);
        nextButton = styledButton(contentX + contentW - pageW, pagerY,
                pageW, BTN_H, Component.literal(">"), button -> changePage(1), null);
        addRenderableWidget(previousButton);
        addRenderableWidget(nextButton);

        addRows();
        refreshButtons();
        if (loading) loadRecords();
    }

    private void loadRecords() {
        loading = false;
        CompletableFuture.supplyAsync(() -> {
            if (!Files.isRegularFile(identityStore.file(), LinkOption.NOFOLLOW_LINKS)) {
                return new Loaded(null, List.of());
            }
            PublisherIdentity loadedIdentity = identityStore.load();
            List<CatalogPublication> records = PublishedRoutesUiState.forApiRoot(
                    publicationRegistry.listForPublisher(loadedIdentity.publisherId()),
                    catalogClient.apiRoot());
            return new Loaded(loadedIdentity, records);
        }).whenComplete((loaded, failure) -> runOnClient(() -> {
            if (!screenActive || minecraft == null
                    || MinecraftCompat.screen(minecraft) != this) return;
            if (failure != null) {
                status = Component.translatable(
                        "waypointer.screen.published_routes.status.load_failed");
                statusColor = STATUS_ERROR;
            } else {
                identity = loaded.identity();
                model.replace(loaded.publications());
                status = Component.translatable(model.publications().isEmpty()
                        ? "waypointer.screen.published_routes.status.empty"
                        : "waypointer.screen.published_routes.status.ready",
                        model.publications().size());
                statusColor = model.publications().isEmpty() ? TEXT_DIM : TEXT_MUTED;
            }
            rebuildWidgets();
        }));
    }

    private void addRows() {
        List<CatalogPublication> visible = model.visiblePublications();
        for (int index = 0; index < visible.size(); index++) {
            CatalogPublication publication = visible.get(index);
            int rowY = rowsY + index * (ROW_H + ROW_GAP);
            String title = font.plainSubstrByWidth(
                    publication.title(), Math.max(40, contentW - 92));
            Component label = Component.literal(title + "  " + publication.routeId());
            Button row = styledButton(contentX, rowY, contentW, ROW_H, label,
                    button -> select(publication.routeId()),
                    Tooltip.create(Component.literal(publication.shareUrl())));
            if (publication.routeId().equals(model.selectedRouteId())) {
                row.setMessage(label.copy().withStyle(net.minecraft.ChatFormatting.AQUA));
            }
            addRenderableWidget(row);
        }
    }

    private void select(String routeId) {
        if (!model.select(routeId)) return;
        status = Component.translatable(
                "waypointer.screen.published_routes.status.selected");
        statusColor = TEXT_DIM;
        rebuildWidgets();
    }

    private void changePage(int delta) {
        if (model.changePage(delta)) rebuildWidgets();
    }

    private void copyLink() {
        CatalogPublication selected = selected();
        if (selected == null || minecraft == null) return;
        minecraft.keyboardHandler.setClipboard(selected.shareUrl());
        status = Component.translatable(
                "waypointer.screen.published_routes.status.copied");
        statusColor = STATUS_OK;
    }

    private void confirmDelete() {
        CatalogPublication selected = selected();
        if (selected == null || identity == null || deleting) return;
        ConfirmScreen confirm = new ConfirmScreen(confirmed -> {
            MinecraftCompat.setScreen(minecraft, this);
            if (confirmed) delete(selected);
        }, Component.translatable("waypointer.screen.published_routes.confirm.title"),
                Component.translatable("waypointer.screen.published_routes.confirm.message",
                        selected.title()),
                Component.translatable("waypointer.screen.published_routes.action.delete"),
                Component.translatable("gui.cancel"));
        MinecraftCompat.setScreen(minecraft, confirm);
    }

    private void delete(CatalogPublication selected) {
        deleting = true;
        status = Component.translatable(
                "waypointer.screen.published_routes.status.deleting");
        statusColor = TEXT_DIM;
        refreshButtons();
        CatalogPublicationManager.delete(
                        catalogClient, identity, publicationRegistry, selected)
                .whenComplete((ignored, failure) -> runOnClient(() -> {
                    if (!screenActive || minecraft == null
                            || MinecraftCompat.screen(minecraft) != this) return;
                    deleting = false;
                    if (failure != null) {
                        status = friendlyDeleteFailure(failure);
                        statusColor = STATUS_ERROR;
                    } else {
                        model.replace(PublishedRoutesUiState.forApiRoot(
                                publicationRegistry.listForPublisher(identity.publisherId()),
                                catalogClient.apiRoot()));
                        status = Component.translatable(
                                "waypointer.screen.published_routes.status.deleted");
                        statusColor = STATUS_OK;
                    }
                    rebuildWidgets();
                }));
    }

    private void refreshButtons() {
        PublishedRoutesUiState.Controls controls = PublishedRoutesUiState.controls(
                model, identity != null, deleting);
        if (deleteButton != null) {
            deleteButton.active = controls.deleteEnabled();
            deleteButton.setMessage(Component.translatable(deleting
                    ? "waypointer.screen.published_routes.action.deleting"
                    : "waypointer.screen.published_routes.action.delete"));
        }
        if (copyButton != null) copyButton.active = controls.copyEnabled();
        if (previousButton != null) previousButton.active = controls.previousEnabled();
        if (nextButton != null) {
            nextButton.active = controls.nextEnabled();
        }
    }

    private CatalogPublication selected() {
        return model.selected();
    }

    private static Component friendlyDeleteFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof CatalogApiException api && api.status() == 429) {
            return Component.translatable(
                    "waypointer.screen.published_routes.status.rate_limited");
        }
        return Component.translatable(
                "waypointer.screen.published_routes.status.delete_failed");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);
        graphics.text(font, font.plainSubstrByWidth(getTitle().getString(), contentW),
                contentX, panelY + PAD_OUTER, TEXT, false);
        graphics.text(font, font.plainSubstrByWidth(Component.translatable(
                        "waypointer.screen.published_routes.subtitle").getString(), contentW),
                contentX, panelY + PAD_OUTER + 12, TEXT_DIM, false);
        if (model.selectedRouteId() != null) {
            graphics.fill(contentX, rowsY - 2, contentX + 2,
                    PublishedRoutesLayout.rowsBottom(
                            rowsY, model.rowsPerPage(), ROW_H, ROW_GAP), SELECTED);
        }
        String pageText = (model.page() + 1) + "/" + (model.maximumPage() + 1);
        graphics.text(font, pageText,
                contentX + (contentW - font.width(pageText)) / 2,
                statusY - BTN_H - GAP + 6, ACCENT, false);
        graphics.text(font, font.plainSubstrByWidth(status.getString(), contentW),
                contentX, statusY, statusColor, false);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
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

    private void runOnClient(Runnable action) {
        Minecraft client = minecraft == null ? Minecraft.getInstance() : minecraft;
        client.execute(action);
    }

    private record Loaded(
            PublisherIdentity identity,
            List<CatalogPublication> publications) {
    }
}
