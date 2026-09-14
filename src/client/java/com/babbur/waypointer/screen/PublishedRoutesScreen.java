package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogApiException;
import com.babbur.waypointer.catalog.CatalogPublication;
import com.babbur.waypointer.catalog.CatalogPublicationManager;
import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.PublisherIdentity;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.Zone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;

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
    private static final int PANEL_MARGIN = 12;
    private static final int CARD_H = 76;
    private static final int CARD_GAP = 8;
    private static final int STATUS_ERROR = GuiTokens.DANGER;
    private static final int STATUS_OK = GuiTokens.SUCCESS;
    private static final int PUBLIC_COLOR = 0xFF6AA9FF;

    private final Screen parent;
    private final RouteCatalogClient catalogClient;
    private final PublisherIdentityStore identityStore;
    private final CatalogPublicationRegistry publicationRegistry;

    private final PublishedRoutesModel model = new PublishedRoutesModel();
    private PublisherIdentity identity;
    private boolean loading = true;
    private boolean deleting;
    private boolean screenActive;
    private boolean showEmptyState;
    private Component status = Component.translatable(
            "waypointer.screen.published_routes.status.loading");
    private int statusColor = TEXT_DIM;

    private Button previousButton;
    private Button nextButton;
    private Button copyCodeButton;
    private Button deleteButton;
    private Button editButton;
    private EditBox searchBox;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int rowsY;
    private int pagerY;
    private int statusY;
    private int columns;
    private int cardW;
    private int cardH;

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
        panelX = width > PANEL_MARGIN * 2 ? PANEL_MARGIN : 0;
        panelY = height > PANEL_MARGIN * 2 ? PANEL_MARGIN : 0;
        panelW = Math.max(1, width - panelX * 2);
        panelH = Math.max(1, height - panelY * 2);
        contentX = panelX + PAD_OUTER;
        contentW = Math.max(1, panelW - PAD_OUTER * 2);
        int searchY = panelY + 34;
        rowsY = searchY + BTN_H + GAP;
        int footerY = panelY + panelH - PAD_OUTER - BTN_H;
        statusY = footerY - 14;
        pagerY = statusY - BTN_H - GAP;
        columns = PublishedRoutesLayout.columns(contentW, CARD_GAP);
        cardW = PublishedRoutesLayout.cardWidth(contentW, columns, CARD_GAP);
        cardH = Math.max(34, Math.min(CARD_H, pagerY - GAP - rowsY));
        model.setRowsPerPage(columns * PublishedRoutesLayout.rowsPerPage(
                rowsY, pagerY, cardH, CARD_GAP, GAP));

        searchBox = new EditBox(font, contentX, searchY, contentW, BTN_H,
                Component.translatable("waypointer.screen.published_routes.search"));
        searchBox.setMaxLength(80);
        searchBox.setHint(Component.translatable(
                "waypointer.screen.published_routes.search"));
        searchBox.setValue(model.query());
        searchBox.setResponder(query -> {
            int cursor = searchBox.getCursorPosition();
            model.setQuery(query);
            showEmptyState = model.publications().isEmpty();
            rebuildWidgets();
            setFocused(searchBox);
            searchBox.setCursorPosition(cursor);
        });
        searchBox.active = !deleting;
        addRenderableWidget(searchBox);

        addRenderableWidget(styledButton(contentX, footerY, 64, BTN_H,
                Component.translatable("gui.back"), button -> onClose(), null));

        int actionW = Math.min(88, Math.max(44, (contentW - 64 - GAP * 3) / 3));
        editButton = styledButton(contentX + contentW - actionW * 3 - GAP * 2, footerY,
                actionW, BTN_H,
                Component.translatable("waypointer.screen.published_routes.action.edit"),
                button -> editSelected(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.published_routes.action.edit.tooltip")));
        addRenderableWidget(editButton);
        deleteButton = styledButton(contentX + contentW - actionW, footerY,
                actionW, BTN_H,
                Component.translatable(deleting
                        ? "waypointer.screen.published_routes.action.deleting"
                        : "waypointer.screen.published_routes.action.delete"),
                button -> confirmDelete(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.published_routes.action.delete.tooltip")));
        addRenderableWidget(deleteButton);
        copyCodeButton = styledButton(contentX + contentW - actionW * 2 - GAP,
                footerY, actionW, BTN_H,
                Component.translatable("waypointer.screen.published_routes.action.copy_code"),
                button -> copyCode(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.published_routes.action.copy_code.tooltip")));
        addRenderableWidget(copyCodeButton);

        int pageW = 28;
        previousButton = styledButton(contentX, pagerY,
                pageW, BTN_H, Component.literal("\u25c0"), button -> changePage(-1), null);
        nextButton = styledButton(contentX + contentW - pageW, pagerY,
                pageW, BTN_H, Component.literal("\u25b6"), button -> changePage(1), null);
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
                showEmptyState = false;
                status = Component.translatable(
                        "waypointer.screen.published_routes.status.load_failed");
                statusColor = STATUS_ERROR;
            } else {
                identity = loaded.identity();
                model.replace(loaded.publications());
                showEmptyState = model.publications().isEmpty();
                status = Component.empty();
                statusColor = TEXT_MUTED;
            }
            rebuildWidgets();
        }));
    }

    private void addRows() {
        List<CatalogPublication> visible = model.visiblePublications();
        for (int index = 0; index < visible.size(); index++) {
            CatalogPublication publication = visible.get(index);
            int cardX = contentX + index % columns * (cardW + CARD_GAP);
            int cardY = rowsY + index / columns * (cardH + CARD_GAP);
            PublicationRow row = new PublicationRow(cardX, cardY, cardW, cardH,
                    publication, publication.routeId().equals(model.selectedRouteId()),
                    () -> select(publication.routeId()));
            addRenderableWidget(row);
        }
    }

    private static final class PublicationRow
            extends net.minecraft.client.gui.components.AbstractButton {
        private final CatalogPublication publication;
        private final boolean selected;
        private final Runnable onPress;

        PublicationRow(int x, int y, int width, int height,
                       CatalogPublication publication, boolean selected, Runnable onPress) {
            super(x, y, width, height, Component.literal(publication.title()));
            this.publication = publication;
            this.selected = selected;
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
            int background = selected ? SELECTED : isHoveredOrFocused() ? GuiTokens.HOVER : SURFACE;
            graphics.fill(x1, y1, x2, y2, background);
            graphics.fill(x1, y1, x2, y1 + 1, GuiTokens.BORDER);
            graphics.fill(x1, y2 - 1, x2, y2, GuiTokens.BORDER);
            graphics.fill(x1, y1, x1 + 1, y2, GuiTokens.BORDER);
            graphics.fill(x2 - 1, y1, x2, y2, GuiTokens.BORDER);
            if (selected) graphics.fill(x1, y1, x1 + 2, y2, ACCENT);
            if (isFocused()) {
                graphics.fill(x1, y1, x2, y1 + 1, ACCENT);
                graphics.fill(x1, y2 - 1, x2, y2, ACCENT);
                graphics.fill(x1, y1, x1 + 1, y2, ACCENT);
                graphics.fill(x2 - 1, y1, x2, y2, ACCENT);
            }

            var font = Minecraft.getInstance().font;
            int textY = y1 + 6;
            String date = PublishedRoutesUiState.publishedDate(
                    publication.serverCreatedAt());
            int dateW = font.width(date);
            int dateX = x2 - GAP - dateW;
            int titleX = x1 + GAP + 2;
            String title = font.plainSubstrByWidth(
                    publication.title(), Math.max(24, x2 - GAP - titleX));
            graphics.text(font, title, titleX, textY, selected ? TEXT : TEXT_DIM, false);
            boolean isPublic = publication.visibility() == CatalogPublishRequest.Visibility.PUBLIC;
            Component visibility = Component.translatable(isPublic
                    ? "waypointer.screen.route_publish.visibility.public"
                    : "waypointer.screen.published_routes.visibility.private");
            int textW = Math.max(1, x2 - GAP - titleX);
            if (getHeight() >= 60) {
                graphics.text(font, font.plainSubstrByWidth(
                                Zone.fromId(publication.zoneId()).displayName(), textW),
                        titleX, textY + 13, TEXT_MUTED, false);
                if (!publication.description().isBlank()) {
                    graphics.text(font, font.plainSubstrByWidth(publication.description(), textW),
                            titleX, textY + 27, TEXT_DIM, false);
                }
                graphics.text(font, Language.getInstance().getVisualOrder(font.substrByWidth(
                                visibility, Math.max(1, dateX - GAP - titleX))),
                        titleX, y2 - 14, isPublic ? PUBLIC_COLOR : TEXT_MUTED, false);
                graphics.text(font, date, dateX, y2 - 14, TEXT_MUTED, false);
            } else {
                graphics.text(font, font.plainSubstrByWidth(
                                Zone.fromId(publication.zoneId()).displayName(), textW),
                        titleX, textY + 13, TEXT_MUTED, false);
            }
        }

        @Override
        protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
            String date = PublishedRoutesUiState.publishedDate(
                    publication.serverCreatedAt());
            Component base = Component.literal(date.isEmpty()
                    ? publication.title()
                    : publication.title() + ". " + date);
            return selected
                    ? Component.translatable(
                            "waypointer.screen.route_catalog.row.narration.selected", base)
                    : base.copy();
        }

        @Override
        protected void updateWidgetNarration(
                net.minecraft.client.gui.narration.NarrationElementOutput output) {
            defaultButtonNarrationText(output);
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

    /** Returns a catalog reference code, including for unlisted routes. */
    private void copyCode() {
        CatalogPublication selected = selected();
        if (selected == null || minecraft == null) return;
        minecraft.keyboardHandler.setClipboard(
                UniversalShareCodec.encodeCatalogReference(selected.routeId()));
        status = Component.translatable(
                "waypointer.screen.published_routes.status.copied_code");
        statusColor = STATUS_OK;
    }

    private void editSelected() {
        CatalogPublication selected = selected();
        if (selected == null || identity == null || deleting) return;
        MinecraftCompat.setScreen(minecraft, new PublishedRouteEditScreen(
                this, catalogClient, identity, publicationRegistry, selected));
    }

    /** Reloads this device's records after an edit made elsewhere, keeping the selection. */
    void refreshFromRegistry(Component message, int color) {
        if (identity == null) return;
        model.replace(PublishedRoutesUiState.forApiRoot(
                publicationRegistry.listForPublisher(identity.publisherId()),
                catalogClient.apiRoot()));
        showEmptyState = model.publications().isEmpty();
        status = message == null ? Component.empty() : message;
        statusColor = color;
        if (screenActive) rebuildWidgets();
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
                        showEmptyState = model.publications().isEmpty();
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
        boolean hasRoutes = !model.publications().isEmpty();
        if (deleteButton != null) {
            deleteButton.visible = hasRoutes;
            deleteButton.active = controls.deleteEnabled();
            deleteButton.setMessage(Component.translatable(deleting
                    ? "waypointer.screen.published_routes.action.deleting"
                    : "waypointer.screen.published_routes.action.delete"));
        }
        if (copyCodeButton != null) {
            copyCodeButton.visible = hasRoutes;
            copyCodeButton.active = controls.copyEnabled();
        }
        if (editButton != null) {
            editButton.visible = hasRoutes;
            editButton.active = controls.editEnabled();
        }
        if (previousButton != null) {
            previousButton.visible = hasRoutes;
            previousButton.active = controls.previousEnabled();
        }
        if (nextButton != null) {
            nextButton.visible = hasRoutes;
            nextButton.active = controls.nextEnabled();
        }
        if (searchBox != null) searchBox.active = !deleting;
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
        if (!model.publications().isEmpty()) {
            String pageText = (model.page() + 1) + "/" + (model.maximumPage() + 1);
            graphics.text(font, pageText,
                    contentX + (contentW - font.width(pageText)) / 2,
                    pagerY + 6, ACCENT, false);
        }
        if (showEmptyState) {
            String empty = Component.translatable(
                    model.query().isBlank()
                            ? "waypointer.screen.published_routes.status.empty"
                            : "waypointer.screen.published_routes.status.no_matches").getString();
            int emptyY = rowsY + Math.max(0, pagerY - rowsY - font.lineHeight) / 2;
            graphics.text(font, empty,
                    contentX + (contentW - font.width(empty)) / 2,
                    emptyY, TEXT_DIM, false);
        }
        if (!status.getString().isBlank()) {
            graphics.text(font, font.plainSubstrByWidth(status.getString(), contentW),
                    contentX, statusY, statusColor, false);
        }
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
