package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.CatalogPublishResult;
import com.babbur.waypointer.catalog.CatalogApiException;
import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.CatalogPublishLifecycle;
import com.babbur.waypointer.catalog.PublisherIdentity;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.i18n.LocalizedNumberFormatter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Files;
import java.text.Normalizer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;

public final class RoutePublishScreen extends Screen {
    private static final int PANEL_MARGIN = 16;
    private static final int LINE_H = 10;
    private static final int LABEL_H = 11;
    private static final int FIELD_STRIDE = LABEL_H + BTN_H + GAP_TIGHT;
    private static final int DESCRIPTION_H = 52;
    private static final int FORM_FIELDS_H = FIELD_STRIDE * 2 + LABEL_H + DESCRIPTION_H + GAP_TIGHT;

    private static final int TIGHT_HEIGHT = 340;

    private static final int FORM_W_TARGET = 320;
    private static final int FORM_W_MIN = 176;
    private static final int CARD_PAD = 5;
    private static final int STATUS_LINES = 2;
    private static final int COUNTER_AT_PERCENT = 75;

    private static final int TITLE_MAX = 80;
    private static final int DESCRIPTION_MIN = 10;
    private static final int DESCRIPTION_MAX = 500;
    private static final int DESCRIPTION_COUNTER_START = 100;

    private static final int STATUS_OK = 0xFF7ACB89;
    private static final int STATUS_ERROR = 0xFFE47B7B;

    private enum StatusKind { INFO, BUSY, OK, ERROR }

    private final Screen parent;
    private final WaypointerConfig config;
    private final WaypointGroup group;
    private final RouteCatalogClient catalogClient;
    private final PublisherIdentityStore identityStore;
    private final CatalogPublicationRegistry publicationRegistry;

    private String titleValue;
    private String descriptionValue = "";
    private String publisherName;
    private CatalogPublishRequest.Visibility visibility =
            CatalogPublishRequest.Visibility.UNLISTED;
    private String identityLabel;
    private boolean publishing;
    private boolean normalizingDescription;
    private CatalogPublishResult result;
    private String publishedPayload;
    private Component statusText = Component.empty();
    private StatusKind statusKind = StatusKind.INFO;
    private boolean initializing;
    private boolean screenActive;

    private EditBox titleBox;
    private MultiLineEditBox descriptionBox;
    private Button unlistedButton;
    private Button publicButton;
    private Button publishButton;
    private Button copyButton;
    private Button manageButton;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int titleY;
    private int subtitleY;
    private int formX;
    private int formY;
    private int formW;
    private int visibilityHelpY;
    private int cardY;
    private int cardH;
    private int statusX;
    private int statusY;
    private int statusMaxW;
    private int statusLines;
    private int footerY;
    private int backW;
    private int copyW;
    private int manageW;
    private int publishW;
    private boolean footerWrapped;
    private boolean showDetail;
    private boolean showCard;

    public RoutePublishScreen(
            Screen parent, WaypointerConfig config, WaypointGroup group,
            RouteCatalogClient catalogClient, PublisherIdentityStore identityStore) {
        this(parent, config, group, catalogClient, identityStore,
                CatalogPublicationRegistry.nextToIdentity(identityStore));
    }

    RoutePublishScreen(
            Screen parent, WaypointerConfig config, WaypointGroup group,
            RouteCatalogClient catalogClient, PublisherIdentityStore identityStore,
            CatalogPublicationRegistry publicationRegistry) {
        super(Component.translatable("waypointer.screen.route_publish.title"));
        this.parent = parent;
        this.config = config;
        this.group = group;
        this.catalogClient = catalogClient;
        this.identityStore = identityStore;
        this.publicationRegistry = publicationRegistry;
        this.titleValue = displayRouteName(group);
        loadExistingIdentity();
    }

    public static void open(Screen parent, WaypointerConfig config, WaypointGroup group) {
        if (group == null || group.temp() || group.runtimeOnly()) return;
        MinecraftCompat.setScreen(Minecraft.getInstance(), new RoutePublishScreen(
                parent, config, group, RouteCatalogClient.production(),
                PublisherIdentityStore.defaultLocation(),
                CatalogPublicationRegistry.defaultLocation()));
    }

    @Override
    protected void init() {
        screenActive = true;
        initializing = true;

        computeLayout();

        int y = formY + LABEL_H;
        titleBox = editBox(y, TITLE_MAX, titleValue,
                Component.translatable("waypointer.screen.route_publish.field.title"),
                null);
        titleBox.setResponder(value -> {
            if (initializing) return;
            titleValue = value == null ? "" : value;
            onFormEdited();
        });
        y += FIELD_STRIDE;

        descriptionBox = MultiLineEditBox.builder()
                .setX(formX)
                .setY(y)
                .setPlaceholder(Component.translatable(
                        "waypointer.screen.route_publish.field.description.hint",
                        DESCRIPTION_MIN))
                .build(font, formW, DESCRIPTION_H,
                        Component.translatable(
                                "waypointer.screen.route_publish.field.description"));
        descriptionBox.setCharacterLimit(DESCRIPTION_MAX * 2);
        descriptionBox.setValue(descriptionValue);
        descriptionBox.setTooltip(Tooltip.create(Component.translatable(
                "waypointer.screen.route_publish.field.description.tooltip")));
        descriptionBox.setValueListener(value -> {
            if (initializing || normalizingDescription) return;
            String normalized = normalizeDescriptionInput(value);
            if (!normalized.equals(value)) {
                normalizingDescription = true;
                descriptionBox.setValue(normalized);
                normalizingDescription = false;
            }
            descriptionValue = normalized;
            onFormEdited();
        });
        addRenderableWidget(descriptionBox);
        y += LABEL_H + DESCRIPTION_H + GAP_TIGHT;

        buildVisibilityChoice(y);
        buildFooter();

        initializing = false;
        refreshControlState();
        setInitialFocus(titleBox.getValue().isEmpty() ? titleBox : publishButton);
    }

    private void computeLayout() {
        boolean tight = height < TIGHT_HEIGHT;
        int margin = tight ? GAP : PANEL_MARGIN;
        int pad = tight ? GAP : PAD_OUTER;
        showDetail = !tight;
        showCard = true;
        statusLines = tight ? 1 : STATUS_LINES;

        int availableW = Math.max(FORM_W_MIN, width - (margin + pad) * 2);
        formW = Math.min(FORM_W_TARGET, availableW);
        contentW = formW;

        measureFooter();

        int headerH = LINE_H * 2 + GAP;
        int footerH = footerWrapped ? BTN_H * 2 + GAP : BTN_H;
        int chromeH = pad * 2 + headerH + GAP + statusLines * LINE_H + GAP_TIGHT + footerH;
        int bodyH = formHeight();

        panelW = contentW + pad * 2;
        panelH = Math.min(Math.max(0, height - margin * 2), chromeH + bodyH);
        panelX = Math.max(0, (width - panelW) / 2);
        panelY = Math.max(0, (height - panelH) / 2);

        contentX = panelX + pad;
        formX = contentX;
        titleY = panelY + pad;
        subtitleY = titleY + LINE_H;
        formY = subtitleY + LINE_H + GAP;

        footerY = panelY + panelH - pad - BTN_H;
        int topFooterY = footerWrapped ? footerY - BTN_H - GAP : footerY;
        statusX = contentX;
        statusMaxW = contentW;
        statusY = topFooterY - GAP_TIGHT - statusLines * LINE_H;
        int bodyBottom = statusY - GAP;

        if (formY + formHeight() > bodyBottom) showDetail = false;
        if (formY + formHeight() > bodyBottom) {
            showCard = false;
        }
        visibilityHelpY = formY + FORM_FIELDS_H;
        cardY = visibilityHelpY + (showDetail ? LINE_H : 0) + GAP;
        cardH = showCard ? cardHeight() : 0;

    }

    private int formHeight() {
        return FORM_FIELDS_H
                + (showDetail ? LINE_H : 0)
                + (showCard ? GAP + cardHeight() : 0);
    }

    private int cardHeight() {
        int lines = 4 + (result == null ? 0 : 1);
        return CARD_PAD * 2 + lines * LINE_H;
    }

    private void measureFooter() {
        backW = footerButtonWidth(Component.translatable("gui.back"));
        manageW = footerButtonWidth(Component.translatable(
                "waypointer.screen.route_publish.action.manage"));
        copyW = footerButtonWidth(copyLabel());
        publishW = Math.max(footerButtonWidth(Component.translatable(
                        "waypointer.screen.route_publish.action.publishing")),
                Math.max(footerButtonWidth(Component.translatable(
                                "waypointer.screen.route_publish.action.publish")),
                        footerButtonWidth(Component.translatable(
                                "waypointer.screen.route_publish.action.publish_again"))));
        footerWrapped = backW + GAP + manageW + GAP_SECTION
                + copyW + GAP + publishW > contentW;
    }

    private int footerButtonWidth(Component label) {
        return Math.max(60, font.width(label) + 16);
    }

    private EditBox editBox(
            int y, int maximum, String value, Component name, Component tooltip) {
        EditBox box = new EditBox(font, formX, y, formW, BTN_H, name);
        box.setMaxLength(maximum);
        box.setValue(value);
        if (tooltip != null) box.setTooltip(Tooltip.create(tooltip));
        addRenderableWidget(box);
        return box;
    }

    private void buildVisibilityChoice(int y) {
        int leftW = formW / 2;
        int rightX = formX + leftW;
        int rightW = formX + formW - rightX;
        unlistedButton = styledButton(formX, y, leftW, BTN_H,
                visibilityOption(CatalogPublishRequest.Visibility.UNLISTED),
                button -> chooseVisibility(CatalogPublishRequest.Visibility.UNLISTED),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.visibility.unlisted.tooltip")));
        publicButton = styledButton(rightX, y, rightW, BTN_H,
                visibilityOption(CatalogPublishRequest.Visibility.PUBLIC),
                button -> chooseVisibility(CatalogPublishRequest.Visibility.PUBLIC),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.visibility.public.tooltip")));
        addRenderableWidget(unlistedButton);
        addRenderableWidget(publicButton);
    }

    private void buildFooter() {
        int contentRight = contentX + contentW;
        int backY = footerWrapped ? footerY - BTN_H - GAP : footerY;
        addRenderableWidget(styledButton(contentX, backY, backW, BTN_H,
                Component.translatable("gui.back"), button -> onClose(), null));
        manageButton = styledButton(contentX + backW + GAP, backY, manageW, BTN_H,
                Component.translatable("waypointer.screen.route_publish.action.manage"),
                button -> PublishedRoutesScreen.open(
                        this, catalogClient, identityStore, publicationRegistry),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.action.manage.tooltip")));
        addRenderableWidget(manageButton);

        copyButton = styledButton(contentRight - publishW - GAP - copyW, footerY, copyW, BTN_H,
                copyLabel(), button -> copyPublishedCode(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.action.copy.tooltip")));
        addRenderableWidget(copyButton);

        publishButton = styledButton(contentRight - publishW, footerY, publishW, BTN_H,
                publishButtonLabel(), button -> publish(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.action.publish.tooltip")));
        addRenderableWidget(publishButton);
    }

    private static Component copyLabel() {
        return Component.translatable("waypointer.screen.route_publish.action.copy");
    }

    private void chooseVisibility(CatalogPublishRequest.Visibility next) {
        if (visibility == next) return;
        visibility = next;
        unlistedButton.setMessage(
                visibilityOption(CatalogPublishRequest.Visibility.UNLISTED));
        publicButton.setMessage(visibilityOption(CatalogPublishRequest.Visibility.PUBLIC));
        onFormEdited();
    }

    private Component visibilityOption(CatalogPublishRequest.Visibility option) {
        boolean chosen = visibility == option;
        MutableComponent label = Component.translatable(
                option == CatalogPublishRequest.Visibility.PUBLIC
                        ? "waypointer.screen.route_publish.visibility.public"
                        : "waypointer.screen.route_publish.visibility.unlisted");
        return chosen ? label.withStyle(ChatFormatting.AQUA) : label;
    }

    private Component visibilityHelp() {
        return Component.translatable(visibility == CatalogPublishRequest.Visibility.PUBLIC
                ? "waypointer.screen.route_publish.visibility.public.help"
                : "waypointer.screen.route_publish.visibility.unlisted.help");
    }

    private Component publishButtonLabel() {
        if (publishing) {
            return Component.translatable(
                    "waypointer.screen.route_publish.action.publishing");
        }
        return Component.translatable(result == null
                ? "waypointer.screen.route_publish.action.publish"
                : "waypointer.screen.route_publish.action.publish_again");
    }

    private void onFormEdited() {
        result = null;
        publishedPayload = null;
        statusText = Component.empty();
        statusKind = StatusKind.INFO;
        refreshControlState();
    }

    private void publish() {
        if (publishing || !validInput()) return;
        publishing = true;
        result = null;
        publishedPayload = null;
        statusText = Component.translatable(
                "waypointer.screen.route_publish.status.preparing");
        statusKind = StatusKind.BUSY;
        refreshControlState();

        CompletableFuture.supplyAsync(identityStore::loadOrCreate)
                .whenComplete((identity, failure) -> runOnClient(() -> {
                    if (!screenActive || minecraft == null
                            || MinecraftCompat.screen(minecraft) != this) return;
                    if (failure != null) {
                        publishing = false;
                        statusText = friendlyFailure(failure);
                        statusKind = StatusKind.ERROR;
                        rebuildWidgets();
                        return;
                    }
                    identityLabel = identity.shortPublisherId();
                    publisherName = identity.publisherName();
                    publishing = false;
                    if (publisherName == null) {
                        statusText = Component.empty();
                        statusKind = StatusKind.INFO;
                        PublisherNameScreen.open(this, defaultPublisherName(),
                                name -> publishWithIdentity(identity, name));
                    } else {
                        publishWithIdentity(identity, null);
                    }
                }));
    }

    private void publishWithIdentity(PublisherIdentity identity, String requestedName) {
        if (!screenActive || publishing || !validInput()) return;
        publishing = true;
        result = null;
        publishedPayload = null;
        statusText = Component.translatable(
                "waypointer.screen.route_publish.status.preparing");
        statusKind = StatusKind.BUSY;
        refreshControlState();

        WaypointGroup snapshot = group.exportSnapshot();
        String title = titleValue.trim();
        String description = descriptionValue.trim();
        CatalogPublishRequest.Visibility requestedVisibility = visibility;

        CompletableFuture.supplyAsync(() -> {
            String payload = WaypointCodec.encodeCatalog(List.of(snapshot));
            CatalogPublishRequest request = new CatalogPublishRequest(
                    payload, title, description, requestedVisibility,
                    snapshot.zoneId(), requestedName);
            return CatalogPublishLifecycle.publishAndPersist(
                            catalogClient, request, identity, identityStore,
                            publicationRegistry)
                    .thenApply(completed -> new CompletedPublish(
                            completed.result(), payload, completed.identity(),
                            completed.nameSaveFailed(),
                            completed.publicationSaveFailed()));
        }).thenCompose(future -> future)
                .whenComplete((completed, failure) -> runOnClient(() -> {
                    if (!screenActive || minecraft == null
                            || MinecraftCompat.screen(minecraft) != this) return;
                    publishing = false;
                    if (failure != null) {
                        statusText = friendlyFailure(failure);
                        statusKind = StatusKind.ERROR;
                    } else {
                        result = completed.result();
                        publishedPayload = completed.payload();
                        identityLabel = completed.identity().shortPublisherId();
                        publisherName = completed.identity().publisherName();
                        if (completed.publicationSaveFailed()) {
                            statusText = Component.translatable(
                                    "waypointer.screen.route_publish.status.record_not_saved");
                            statusKind = StatusKind.ERROR;
                        } else if (completed.nameSaveFailed()) {
                            statusText = Component.translatable(
                                    "waypointer.screen.route_publish.status.name_not_saved");
                            statusKind = StatusKind.ERROR;
                        } else {
                            statusText = Component.translatable(
                                    "waypointer.screen.route_publish.status.published",
                                    LocalizedNumberFormatter.active().integer(
                                            result.route().version()));
                            statusKind = StatusKind.OK;
                        }
                    }
                    rebuildWidgets();
                }));
    }

    private void copyPublishedCode() {
        if (publishedPayload == null || minecraft == null) return;
        minecraft.keyboardHandler.setClipboard(publishedPayload);
        statusText = Component.translatable(
                "waypointer.screen.route_publish.status.copied");
        statusKind = StatusKind.OK;
    }

    private boolean validInput() {
        return validationHint() == null;
    }

    private Component validationHint() {
        if (group == null || group.isEmpty()) {
            return Component.translatable(
                    "waypointer.screen.route_publish.validation.empty_route");
        }
        if (group.temp() || group.runtimeOnly()) {
            return Component.translatable(
                    "waypointer.screen.route_publish.validation.temporary");
        }
        if (titleValue == null || titleValue.trim().isEmpty()) {
            return Component.translatable(
                    "waypointer.screen.route_publish.validation.title_required");
        }
        String description = descriptionValue == null ? "" : descriptionValue.trim();
        int descriptionLength = description.codePointCount(0, description.length());
        if (descriptionLength < DESCRIPTION_MIN) {
            return Component.translatable(
                    "waypointer.screen.route_publish.validation.description_min",
                    DESCRIPTION_MIN);
        }
        if (descriptionLength > DESCRIPTION_MAX) {
            return Component.translatable(
                    "waypointer.screen.route_publish.validation.description_max",
                    DESCRIPTION_MAX);
        }
        return null;
    }

    private void refreshControlState() {
        boolean editable = !publishing;
        if (publishButton != null) {
            publishButton.active = validInput() && !publishing;
            publishButton.setMessage(publishButtonLabel());
        }
        if (titleBox != null) titleBox.active = editable;
        if (descriptionBox != null) descriptionBox.active = editable;
        if (unlistedButton != null) unlistedButton.active = editable;
        if (publicButton != null) publicButton.active = editable;
        if (copyButton != null) {
            boolean hasCode = result != null && publishedPayload != null;
            copyButton.visible = hasCode;
            copyButton.active = hasCode;
        }
    }

    private void loadExistingIdentity() {
        if (!Files.isRegularFile(identityStore.file())) return;
        CompletableFuture.supplyAsync(identityStore::load)
                .whenComplete((identity, failure) -> runOnClient(() -> {
                    if (failure != null) return;
                    identityLabel = identity.shortPublisherId();
                    publisherName = identity.publisherName();
                    if (screenActive && minecraft != null
                            && MinecraftCompat.screen(minecraft) == this) {
                        rebuildWidgets();
                    }
                }));
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);

        graphics.text(font, font.plainSubstrByWidth(getTitle().getString(), contentW),
                contentX, titleY, TEXT, false);
        renderRouteSummary(graphics);

        drawFieldLabel(graphics,
                Component.translatable("waypointer.screen.route_publish.field.title"),
                formY, titleValue, TITLE_MAX);
        drawFieldLabel(graphics,
                Component.translatable("waypointer.screen.route_publish.field.description"),
                formY + FIELD_STRIDE,
                null, 0);
        drawFieldLabel(graphics,
                Component.translatable("waypointer.screen.route_publish.field.visibility"),
                formY + FIELD_STRIDE + LABEL_H + DESCRIPTION_H + GAP_TIGHT, null, 0);

        if (showDetail) {
            graphics.text(font, font.plainSubstrByWidth(
                            visibilityHelp().getString(), formW),
                    formX, visibilityHelpY, TEXT_MUTED, false);
        }
        renderIdentityCard(graphics);
        renderStatus(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        renderVisibilitySelection(graphics);
        renderDescriptionCounter(graphics);
    }

    private void renderVisibilitySelection(GuiGraphicsExtractor graphics) {
        Button selected = visibility == CatalogPublishRequest.Visibility.PUBLIC
                ? publicButton : unlistedButton;
        if (selected == null || !selected.visible) return;
        graphics.fill(selected.getX() + 1,
                selected.getY() + selected.getHeight() - 2,
                selected.getX() + selected.getWidth() - 1,
                selected.getY() + selected.getHeight() - 1,
                GuiTokens.ACCENT);
    }

    private void renderDescriptionCounter(GuiGraphicsExtractor graphics) {
        if (descriptionBox == null) return;
        int remaining = descriptionCharactersRemaining(descriptionValue);
        if (remaining < 0) return;
        String counter = Component.translatable(
                "waypointer.screen.route_publish.field.remaining",
                LocalizedNumberFormatter.active().integer(remaining)).getString();
        int counterX = descriptionBox.getX() + descriptionBox.getWidth() - GAP_TIGHT
                - font.width(counter);
        int counterY = descriptionBox.getY() + descriptionBox.getHeight() - LINE_H - GAP_TIGHT;
        int backdropLeft = counterX - 3;
        int backdropRight = descriptionBox.getX() + descriptionBox.getWidth() - 2;
        int backdropTop = counterY - 2;
        int backdropBottom = counterY + LINE_H;
        graphics.fill(backdropLeft, backdropTop, backdropRight, backdropBottom, 0xFF10151A);
        graphics.fill(backdropLeft, backdropTop, backdropRight, backdropTop + 1, 0x30FFFFFF);
        graphics.text(font, counter, counterX, counterY,
                remaining == 0 ? STATUS_ERROR : TEXT_MUTED, false);
    }

    private void drawFieldLabel(GuiGraphicsExtractor graphics, Component label, int y,
                                String value, int maximum) {
        int labelMaxW = formW;
        int used = value == null ? 0 : value.length();
        if (maximum > 0 && used * 100 >= maximum * COUNTER_AT_PERCENT) {
            String counter = Component.translatable(
                    "waypointer.screen.route_publish.field.counter",
                    LocalizedNumberFormatter.active().integer(used),
                    LocalizedNumberFormatter.active().integer(maximum)).getString();
            int counterW = font.width(counter);
            graphics.text(font, counter, formX + formW - counterW, y,
                    used >= maximum ? STATUS_ERROR : TEXT_MUTED, false);
            labelMaxW = Math.max(0, formW - counterW - GAP);
        }
        graphics.text(font, font.plainSubstrByWidth(label.getString(), labelMaxW), formX, y,
                TEXT_DIM, false);
    }

    private void renderIdentityCard(GuiGraphicsExtractor graphics) {
        if (!showCard || formW <= 0) return;
        graphics.fill(formX, cardY, formX + formW, cardY + cardH, SURFACE_SUBTLE);
        graphics.fill(formX, cardY, formX + 1, cardY + cardH, GuiTokens.ACCENT);

        int innerX = formX + CARD_PAD + 1;
        int innerW = Math.max(0, formW - (CARD_PAD + 1) * 2);
        int y = cardY + CARD_PAD;
        boolean known = identityLabel != null;
        drawCaptionValue(graphics,
                Component.translatable("waypointer.screen.route_publish.identity.publisher_id"),
                known ? Component.literal(identityLabel) : Component.translatable(
                        "waypointer.screen.route_publish.identity.created_on_publish"),
                innerX, y, innerW, known ? TEXT : TEXT_MUTED);
        y += LINE_H;
        if (result != null) {
            drawCaptionValue(graphics,
                    Component.translatable("waypointer.screen.route_publish.identity.route_id"),
                    Component.literal(result.route().id()),
                    innerX, y, innerW, TEXT);
            y += LINE_H;
        }
        List<FormattedCharSequence> lines = font.split(Component.translatable(
                "waypointer.screen.route_publish.identity.device_help"), innerW);
        for (int index = 0; index < Math.min(3, lines.size()); index++) {
            graphics.text(font, lines.get(index), innerX, y + index * LINE_H,
                    TEXT_MUTED, false);
        }
    }

    private void drawCaptionValue(
            GuiGraphicsExtractor graphics, Component caption, Component value,
                                  int x, int y, int maxW, int valueColor) {
        String clippedCaption = font.plainSubstrByWidth(caption.getString(), maxW);
        graphics.text(font, clippedCaption, x, y, TEXT_MUTED, false);
        int valueX = x + font.width(clippedCaption) + GAP;
        int valueMaxW = x + maxW - valueX;
        if (valueMaxW <= 0) return;
        graphics.text(font, font.plainSubstrByWidth(value.getString(), valueMaxW), valueX, y,
                valueColor, false);
    }

    private void renderStatus(GuiGraphicsExtractor graphics) {
        Component validation = validationHint();
        Component message = statusText.getString().isBlank() ? validation : statusText;
        if (message == null || message.getString().isEmpty()) return;

        int color = statusColor();
        int textX = statusX;
        String marker = statusMarker();
        if (!marker.isEmpty()) {
            graphics.text(font, marker, textX, statusY, color, false);
            textX += font.width(marker) + GAP_TIGHT;
        }
        int wrapW = Math.max(20, statusX + statusMaxW - textX);
        List<FormattedCharSequence> lines = font.split(message, wrapW);
        int shown = Math.min(lines.size(), statusLines);
        for (int i = 0; i < shown; i++) {
            graphics.text(font, lines.get(i), textX, statusY + i * LINE_H, color, false);
        }
    }

    private int statusColor() {
        return switch (statusKind) {
            case OK -> STATUS_OK;
            case ERROR -> STATUS_ERROR;
            case INFO, BUSY -> TEXT_DIM;
        };
    }

    private String statusMarker() {
        if (statusText.getString().isBlank()) return "";
        return switch (statusKind) {
            case OK -> "✓";
            case ERROR -> "!";
            case INFO, BUSY -> "";
        };
    }

    private String previewName() {
        String trimmed = titleValue == null ? "" : titleValue.trim();
        return trimmed.isEmpty() ? displayRouteName(group) : trimmed;
    }

    private void renderRouteSummary(GuiGraphicsExtractor graphics) {
        String zone = Zone.fromId(group.zoneId()).displayName();
        String count = waypointCount(group.size()).getString();
        String clippedZone = font.plainSubstrByWidth(zone,
                Math.max(0, contentW - font.width(count) - GAP_SECTION));
        graphics.text(font, clippedZone, contentX, subtitleY, GuiTokens.ACCENT, false);
        graphics.text(font, count, contentX + contentW - font.width(count),
                subtitleY, TEXT_MUTED, false);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean enter = event.key() == GLFW_KEY_ENTER || event.key() == GLFW_KEY_KP_ENTER;
        if (enter && publishButton != null && publishButton.active && !anyEditBoxFocused()) {
            publish();
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean anyEditBoxFocused() {
        return (titleBox != null && titleBox.isFocused())
                || (descriptionBox != null && descriptionBox.isFocused());
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

    private static String displayRouteName(WaypointGroup group) {
        String name = group.name() == null ? "" : group.name().trim();
        return name.isEmpty()
                ? Component.translatable(
                        "waypointer.screen.route_publish.untitled_route").getString()
                : name;
    }

    private static String defaultPublisherName() {
        try {
            String name = Minecraft.getInstance().getUser().getName();
            return name == null || name.isBlank()
                    ? Component.translatable(
                            "waypointer.screen.route_publish.default_publisher").getString()
                    : name;
        } catch (RuntimeException ignored) {
            return Component.translatable(
                    "waypointer.screen.route_publish.default_publisher").getString();
        }
    }

    @Override
    public Component getNarrationMessage() {
        Component outcome = statusText.getString().isBlank() ? validationHint() : statusText;
        if (outcome == null) {
            outcome = Component.translatable(
                    "waypointer.screen.route_publish.narration.ready");
        }
        return Component.translatable(
                "waypointer.screen.route_publish.narration",
                getTitle(), previewName(), outcome);
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
                case "publishing_disabled" ->
                        "waypointer.screen.route_publish.error.publishing_disabled";
                case "rate_limited" ->
                        "waypointer.screen.route_publish.error.rate_limited";
                case "duplicate_route" ->
                        "waypointer.screen.route_publish.error.duplicate";
                case "empty_route" ->
                        "waypointer.screen.route_publish.validation.empty_route";
                case "zone_required" ->
                        "waypointer.screen.route_publish.error.zone_required";
                case "payload_too_large", "request_too_large", "route_too_large" ->
                        "waypointer.screen.route_publish.error.too_large";
                case "invalid_route" ->
                        "waypointer.screen.route_publish.error.invalid_route";
                case "invalid_publisher_name" ->
                        "waypointer.screen.route_publish.error.publisher_name_invalid";
                case "publisher_name_required" ->
                        "waypointer.screen.route_publish.error.publisher_name_required";
                case "publisher_name_taken" ->
                        "waypointer.screen.route_publish.error.publisher_name_taken";
                case "publisher_name_locked" ->
                        "waypointer.screen.route_publish.error.publisher_name_locked";
                case "invalid_signature_headers", "invalid_publisher", "invalid_timestamp",
                        "expired_request", "invalid_nonce", "invalid_public_key",
                        "invalid_signature", "replayed_request" ->
                        "waypointer.screen.route_publish.error.identity";
                default -> "waypointer.screen.route_publish.error.failed";
            };
            return Component.translatable(key);
        }
        return Component.translatable("waypointer.screen.route_publish.error.failed");
    }

    private static Component waypointCount(long value) {
        return Component.translatable(value == 1
                        ? "waypointer.screen.route_publish.waypoint_count.one"
                        : "waypointer.screen.route_publish.waypoint_count.many",
                LocalizedNumberFormatter.active().integer(value));
    }

    static boolean descriptionLengthValid(String description) {
        String trimmed = description == null ? "" : description.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        return length >= DESCRIPTION_MIN && length <= DESCRIPTION_MAX;
    }

    static String normalizeDescriptionInput(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = Normalizer.normalize(
                value.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(Math.min(normalized.length(), DESCRIPTION_MAX));
        int count = 0;
        for (int offset = 0; offset < normalized.length() && count < DESCRIPTION_MAX;) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint != '\n' && (codePoint < 0x20 || codePoint == 0x7f)) continue;
            result.appendCodePoint(codePoint);
            count++;
        }
        return result.toString();
    }

    static int descriptionCharactersRemaining(String description) {
        int length = description == null ? 0
                : description.codePointCount(0, description.length());
        return length < DESCRIPTION_COUNTER_START
                ? -1 : Math.max(0, DESCRIPTION_MAX - length);
    }

    private record CompletedPublish(
            CatalogPublishResult result,
            String payload,
            PublisherIdentity identity,
            boolean nameSaveFailed,
            boolean publicationSaveFailed) {
    }
}
