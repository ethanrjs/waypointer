package com.babbur.waypointer.screen;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.catalog.CatalogPublication;
import com.babbur.waypointer.catalog.CatalogPublicationManager;
import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.CatalogRouteUpdate;
import com.babbur.waypointer.catalog.PublisherIdentity;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static com.babbur.waypointer.screen.CatalogPublishFormModel.DESCRIPTION_MAX;
import static com.babbur.waypointer.screen.CatalogPublishFormModel.DESCRIPTION_MIN;
import static com.babbur.waypointer.screen.CatalogPublishFormModel.TITLE_MAX;
import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.colored;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;

/** Edits a published route without changing its share link. */
final class PublishedRouteEditScreen extends Screen {
    private static final int PANEL_W = 440;
    private static final int PANEL_MARGIN = 12;
    private static final int LABEL_H = 12;
    private static final int DESCRIPTION_H = 70;

    private final PublishedRoutesScreen parent;
    private final RouteCatalogClient catalogClient;
    private final PublisherIdentity identity;
    private final CatalogPublicationRegistry registry;
    private final CatalogPublication publication;

    private String title;
    private String description;
    private CatalogPublishRequest.Visibility visibility;
    private boolean saving;
    private boolean screenActive;
    private boolean normalizingDescription;
    private Component status = Component.empty();
    private int statusColor = TEXT_DIM;

    private EditBox titleBox;
    private MultiLineEditBox descriptionBox;
    private Button unlistedButton;
    private Button publicButton;
    private Button saveButton;
    private Button updateButton;
    private Button backButton;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int statusY;

    PublishedRouteEditScreen(
            PublishedRoutesScreen parent, RouteCatalogClient catalogClient,
            PublisherIdentity identity, CatalogPublicationRegistry registry,
            CatalogPublication publication) {
        super(Component.translatable("waypointer.screen.route_edit.title"));
        this.parent = parent;
        this.catalogClient = catalogClient;
        this.identity = identity;
        this.registry = registry;
        this.publication = publication;
        this.title = publication.title();
        this.description = publication.description();
        this.visibility = publication.visibility();
    }

    @Override
    protected void init() {
        screenActive = true;
        panelW = Math.min(PANEL_W, Math.max(240, width - PANEL_MARGIN * 2));
        panelH = Math.min(300, Math.max(220, height - PANEL_MARGIN * 2));
        panelX = Math.max(0, (width - panelW) / 2);
        panelY = Math.max(0, (height - panelH) / 2);
        contentX = panelX + PAD_OUTER;
        contentW = Math.max(1, panelW - PAD_OUTER * 2);
        int footerY = panelY + panelH - PAD_OUTER - BTN_H;
        statusY = footerY - 14;

        int y = panelY + 34 + LABEL_H;
        titleBox = new EditBox(font, contentX, y, contentW, BTN_H,
                Component.translatable("waypointer.screen.route_publish.field.title"));
        titleBox.setMaxLength(TITLE_MAX);
        titleBox.setValue(title);
        titleBox.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.route_publish.field.title.tooltip")));
        titleBox.setResponder(value -> { title = value == null ? "" : value; refreshButtons(); });
        addRenderableWidget(titleBox);
        y += BTN_H + GAP + LABEL_H;

        descriptionBox = MultiLineEditBox.builder()
                .setX(contentX)
                .setY(y)
                .setPlaceholder(Component.translatable(
                        "waypointer.screen.route_publish.field.description.hint", DESCRIPTION_MIN))
                .build(font, contentW, DESCRIPTION_H,
                        Component.translatable("waypointer.screen.route_publish.field.description"));
        descriptionBox.setValue(description);
        descriptionBox.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.route_publish.field.description.tooltip")));
        descriptionBox.setValueListener(value -> {
            if (normalizingDescription) return;
            String normalized = CatalogPublishFormModel.normalizeDescriptionInput(value);
            if (!normalized.equals(value)) {
                normalizingDescription = true;
                descriptionBox.setValue(normalized);
                normalizingDescription = false;
            }
            description = normalized;
            refreshButtons();
        });
        addRenderableWidget(descriptionBox);
        y += DESCRIPTION_H + GAP;

        int half = (contentW - GAP_TIGHT) / 2;
        unlistedButton = styledButton(contentX, y, half, BTN_H,
                Component.translatable("waypointer.screen.route_publish.visibility.unlisted"),
                button -> chooseVisibility(CatalogPublishRequest.Visibility.UNLISTED),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.visibility.unlisted.tooltip")));
        publicButton = styledButton(contentX + half + GAP_TIGHT, y, half, BTN_H,
                Component.translatable("waypointer.screen.route_publish.visibility.public"),
                button -> chooseVisibility(CatalogPublishRequest.Visibility.PUBLIC),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.visibility.public.tooltip")));
        addRenderableWidget(unlistedButton);
        addRenderableWidget(publicButton);

        backButton = styledButton(contentX, footerY, 64, BTN_H,
                Component.translatable("gui.back"), button -> onClose(), null);
        addRenderableWidget(backButton);
        int saveW = Math.min(120, Math.max(64, contentW / 3));
        saveButton = styledButton(contentX + contentW - saveW, footerY, saveW, BTN_H,
                Component.translatable("waypointer.screen.route_edit.action.save"),
                button -> save(),
                Tooltip.create(Component.translatable("waypointer.screen.route_edit.action.save.tooltip")));
        addRenderableWidget(saveButton);
        updateButton = styledButton(contentX + 64 + GAP, footerY,
                Math.max(1, contentW - 64 - saveW - GAP * 2), BTN_H,
                Component.translatable("waypointer.screen.route_edit.action.update"),
                button -> new RouteReuploadScreen(this, WaypointerClient.config(), catalogClient,
                        identity, registry, publication).openPicker(minecraft), null);
        addRenderableWidget(updateButton);
        refreshButtons();
    }

    private void chooseVisibility(CatalogPublishRequest.Visibility next) {
        if (saving) return;
        visibility = next;
        refreshButtons();
    }

    private boolean changed() {
        return !title.trim().equals(publication.title())
                || !description.trim().equals(publication.description().trim())
                || visibility != publication.visibility();
    }

    private Component validation() {
        if (title.isBlank()) {
            return Component.translatable("waypointer.screen.route_publish.validation.title_required");
        }
        String trimmed = description.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < DESCRIPTION_MIN) {
            return Component.translatable(
                    "waypointer.screen.route_publish.validation.description_min", DESCRIPTION_MIN);
        }
        if (length > DESCRIPTION_MAX) {
            return Component.translatable(
                    "waypointer.screen.route_publish.validation.description_max", DESCRIPTION_MAX);
        }
        return null;
    }

    private void refreshButtons() {
        boolean unlisted = visibility == CatalogPublishRequest.Visibility.UNLISTED;
        if (unlistedButton != null) {
            unlistedButton.setMessage(colored(Component.translatable(
                    "waypointer.screen.route_publish.visibility.unlisted"), unlisted ? ACCENT : TEXT_DIM));
            unlistedButton.active = !saving;
        }
        if (publicButton != null) {
            publicButton.setMessage(colored(Component.translatable(
                    "waypointer.screen.route_publish.visibility.public"), unlisted ? TEXT_DIM : ACCENT));
            publicButton.active = !saving;
        }
        if (saveButton != null) {
            saveButton.active = !saving && changed() && validation() == null;
            saveButton.setMessage(Component.translatable(saving
                    ? "waypointer.screen.route_edit.action.saving"
                    : "waypointer.screen.route_edit.action.save"));
        }
        if (updateButton != null) updateButton.active = !saving && identity != null && validation() == null;
        if (backButton != null) backButton.active = !saving;
        if (titleBox != null) titleBox.setEditable(!saving);
        if (!saving) {
            Component problem = validation();
            if (problem != null && changed()) {
                status = problem;
                statusColor = TEXT_DIM;
            } else if (statusColor != GuiTokens.DANGER) {
                status = Component.empty();
            }
        }
    }

    private void save() {
        if (saving || identity == null || !changed()) return;
        Component problem = validation();
        if (problem != null) {
            status = problem;
            statusColor = GuiTokens.DANGER;
            return;
        }
        saving = true;
        status = Component.translatable("waypointer.screen.route_edit.status.saving");
        statusColor = TEXT_DIM;
        refreshButtons();
        CatalogRouteUpdate update = metadataUpdate();
        CatalogPublicationManager.update(catalogClient, identity, registry, publication, update, null)
                .whenComplete((updated, failure) -> runOnClient(() -> {
                    if (!screenActive || minecraft == null) return;
                    saving = false;
                    if (failure != null) {
                        status = RoutePublishScreen.friendlyFailure(failure);
                        statusColor = GuiTokens.DANGER;
                        refreshButtons();
                        return;
                    }
                    parent.refreshFromRegistry(Component.translatable(
                            "waypointer.screen.route_edit.status.saved", updated.version()), GuiTokens.SUCCESS);
                    MinecraftCompat.setScreen(minecraft, parent);
                }));
    }

    CatalogRouteUpdate metadataUpdate() {
        return CatalogRouteUpdate.metadata(
                title.trim().equals(publication.title()) ? null : title.trim(),
                description.trim().equals(publication.description().trim()) ? null : description.trim(),
                visibility == publication.visibility() ? null : visibility);
    }

    void finishUpdate(Minecraft client, CatalogPublication updated) {
        parent.refreshFromRegistry(Component.translatable(
                "waypointer.screen.route_reupload.status.uploaded", updated.version()), GuiTokens.SUCCESS);
        MinecraftCompat.setScreen(client, parent);
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);
        graphics.text(font, font.plainSubstrByWidth(getTitle().getString(), contentW),
                contentX, panelY + PAD_OUTER, TEXT, false);
        graphics.text(font, Component.translatable("waypointer.screen.route_publish.field.title"),
                contentX, panelY + 34, TEXT_DIM, false);
        graphics.text(font, Component.translatable("waypointer.screen.route_publish.field.description"),
                contentX, panelY + 34 + LABEL_H + BTN_H + GAP, TEXT_DIM, false);
        if (!status.getString().isBlank()) {
            graphics.text(font, font.plainSubstrByWidth(status.getString(), contentW),
                    contentX, statusY, statusColor, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (saving) return;
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
