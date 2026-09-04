package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.CatalogApiException;
import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.PublisherIdentity;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.i18n.LocalizedNumberFormatter;
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

import java.util.List;
import java.util.concurrent.CompletionException;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.BORDER;
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

    private static final int FORM_W_TARGET = 336;
    private static final int FORM_W_MIN = 176;
    private static final int STATUS_LINES = 2;
    private static final int COUNTER_AT_PERCENT = 75;

    private static final int TITLE_MAX = CatalogPublishFormModel.TITLE_MAX;
    private static final int DESCRIPTION_MIN = CatalogPublishFormModel.DESCRIPTION_MIN;
    private static final int DESCRIPTION_MAX = CatalogPublishFormModel.DESCRIPTION_MAX;

    private static final int STATUS_OK = GuiTokens.SUCCESS;
    private static final int STATUS_ERROR = GuiTokens.DANGER;

    private enum StatusKind { INFO, BUSY, OK, ERROR }

    private final Screen parent;
    private final WaypointerConfig config;
    private final WaypointGroup group;
    private final RouteCatalogClient catalogClient;
    private final PublisherIdentityStore identityStore;
    private final CatalogPublicationRegistry publicationRegistry;
    private final CatalogPublishSession session;
    private final CatalogPublishFormModel form;

    private CatalogPublishSession.Snapshot publishState;
    private boolean normalizingDescription;
    private Component statusText = Component.empty();
    private StatusKind statusKind = StatusKind.INFO;
    private boolean initializing;
    private boolean screenActive;
    private long publisherPromptAttempt = -1L;
    private Runnable removeSessionListener = () -> { };

    private EditBox titleBox;
    private MultiLineEditBox descriptionBox;
    private Button unlistedButton;
    private Button publicButton;
    private Button publishButton;
    private Button backButton;
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
    private int paintNoticeY;
    private int statusX;
    private int statusY;
    private int statusMaxW;
    private int statusLines;
    private int footerY;
    private int copyY;
    private int backW;
    private int copyW;
    private int manageW;
    private int publishW;
    private boolean showDetail;
    private boolean showPaintNotice;

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
        this.session = new CatalogPublishSession(
                group, catalogClient, identityStore, publicationRegistry);
        this.form = session.form();
        if (form.title().isBlank()) form.setTitle(displayRouteName(group));
        this.publishState = session.snapshot();
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
        applySessionState(session.snapshot());

        computeLayout();

        int y = formY + LABEL_H;
        titleBox = editBox(y, TITLE_MAX, form.title(),
                Component.translatable("waypointer.screen.route_publish.field.title"),
                Component.translatable(
                        "waypointer.screen.route_publish.field.title.tooltip"));
        titleBox.setResponder(value -> {
            if (initializing) return;
            form.setTitle(value);
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
        // The listener enforces the code-point limit; Minecraft counts UTF-16 units.
        descriptionBox.setValue(form.description());
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
            form.setDescription(normalized);
        });
        addRenderableWidget(descriptionBox);
        y += LABEL_H + DESCRIPTION_H + GAP_TIGHT;

        buildVisibilityChoice(y);
        buildFooter();

        initializing = false;
        removeSessionListener.run();
        removeSessionListener = session.addListener(this::sessionChanged);
        refreshControlState();
        setInitialFocus(titleBox.getValue().isEmpty() ? titleBox : publishButton);
    }

    private void computeLayout() {
        boolean tight = height < TIGHT_HEIGHT;
        int margin = tight ? GAP : PANEL_MARGIN;
        int pad = tight ? GAP : PAD_OUTER;
        showDetail = !tight;
        showPaintNotice = customPaintOmitted(group);
        statusLines = tight ? 1 : STATUS_LINES;

        int availableW = Math.max(FORM_W_MIN, width - (margin + pad) * 2);
        formW = Math.min(FORM_W_TARGET, availableW);
        contentW = formW;

        measureFooter();

        int headerH = LINE_H * 2 + GAP;
        int copyRowH = publishState.result() == null ? 0 : BTN_H + GAP;
        int footerH = BTN_H + copyRowH;
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
        copyY = footerY - BTN_H - GAP;
        int topFooterY = footerY - copyRowH;
        statusX = contentX;
        statusMaxW = contentW;
        statusY = topFooterY - GAP_TIGHT - statusLines * LINE_H;
        int bodyBottom = statusY - GAP;

        if (formY + formHeight() > bodyBottom) showDetail = false;
        if (formY + formHeight() > bodyBottom) showPaintNotice = false;
        visibilityHelpY = formY + FORM_FIELDS_H;
        paintNoticeY = visibilityHelpY + (showDetail ? LINE_H : 0);
    }

    private int formHeight() {
        return FORM_FIELDS_H + (showDetail ? LINE_H : 0)
                + (showPaintNotice ? LINE_H : 0);
    }

    private void measureFooter() {
        int columnWidth = Math.max(1, (contentW - GAP * 2) / 3);
        backW = columnWidth;
        manageW = columnWidth;
        publishW = columnWidth;
        copyW = columnWidth;
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
        int leftW = (formW - GAP_TIGHT) / 2;
        int rightX = formX + leftW + GAP_TIGHT;
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
        int gridWidth = backW + manageW + publishW + GAP * 2;
        int gridX = contentX + Math.max(0, (contentW - gridWidth) / 2);
        backButton = styledButton(gridX, footerY, backW, BTN_H,
                Component.translatable("gui.back"), button -> onClose(), null);
        backButton.active = canNavigateBack(publishState.phase());
        addRenderableWidget(backButton);
        int manageX = gridX + backW + GAP;
        manageButton = styledButton(manageX, footerY, manageW, BTN_H,
                Component.translatable("waypointer.screen.route_publish.action.manage"),
                button -> PublishedRoutesScreen.open(
                        this, catalogClient, identityStore, publicationRegistry),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.action.manage.tooltip")));
        addRenderableWidget(manageButton);

        copyButton = styledButton(manageX, copyY, copyW, BTN_H,
                copyLabel(), button -> copyPublishedCode(),
                Tooltip.create(Component.translatable(
                        "waypointer.screen.route_publish.action.copy.tooltip")));
        addRenderableWidget(copyButton);

        Component publishTooltip = Component.translatable(
                "waypointer.screen.route_publish.action.publish.tooltip");
        if (customPaintOmitted(group)) {
            publishTooltip = Component.empty().append(publishTooltip)
                    .append("\n").append(paintOmittedNotice());
        }
        publishButton = styledButton(manageX + manageW + GAP, footerY, publishW, BTN_H,
                publishButtonLabel(), button -> publish(),
                Tooltip.create(publishTooltip));
        addRenderableWidget(publishButton);
    }

    private static Component copyLabel() {
        return Component.translatable("waypointer.screen.route_publish.action.copy");
    }

    private void chooseVisibility(CatalogPublishRequest.Visibility next) {
        if (form.visibility() == next) return;
        form.setVisibility(next);
        unlistedButton.setMessage(
                visibilityOption(CatalogPublishRequest.Visibility.UNLISTED));
        publicButton.setMessage(visibilityOption(CatalogPublishRequest.Visibility.PUBLIC));
        refreshControlState();
    }

    private Component visibilityOption(CatalogPublishRequest.Visibility option) {
        boolean chosen = form.visibility() == option;
        MutableComponent label = Component.translatable(
                option == CatalogPublishRequest.Visibility.PUBLIC
                        ? "waypointer.screen.route_publish.visibility.public"
                        : "waypointer.screen.route_publish.visibility.unlisted");
        return chosen ? GuiTokens.colored(label, GuiTokens.ACCENT) : label;
    }

    private Component visibilityHelp() {
        return Component.translatable(form.visibility() == CatalogPublishRequest.Visibility.PUBLIC
                ? "waypointer.screen.route_publish.visibility.public.help"
                : "waypointer.screen.route_publish.visibility.unlisted.help");
    }

    private Component publishButtonLabel() {
        if (publishState.phase().busy()) {
            return GuiTokens.colored(Component.translatable(
                    "waypointer.screen.route_publish.action.publishing"), GuiTokens.ACCENT);
        }
        return GuiTokens.colored(Component.translatable(publishState.result() == null
                ? "waypointer.screen.route_publish.action.publish"
                : "waypointer.screen.route_publish.action.publish_again"), GuiTokens.ACCENT);
    }

    private void publish() {
        switch (RoutePublishUiState.primaryAction(publishState.phase())) {
            case NONE -> {
            }
            case REQUEST_PUBLISHER_NAME -> {
                publisherPromptAttempt = -1L;
                showPublisherNamePrompt(publishState);
            }
            case BEGIN_PUBLISH -> session.beginPublish();
        }
    }

    private void sessionChanged(CatalogPublishSession.Snapshot ignored) {
        runOnClient(() -> applySessionState(session.snapshot()));
    }

    private void applySessionState(CatalogPublishSession.Snapshot next) {
        boolean resultLayoutChanged = RoutePublishUiState.resultLayoutChanged(
                publishState, next);
        publishState = next;
        switch (next.phase()) {
            case IDLE, NEEDS_PUBLISHER_NAME -> {
                statusText = Component.empty();
                statusKind = StatusKind.INFO;
            }
            case LOADING_IDENTITY, PUBLISHING -> {
                statusText = Component.translatable(
                        "waypointer.screen.route_publish.status.preparing");
                statusKind = StatusKind.BUSY;
            }
            case FAILED -> {
                statusText = friendlyFailure(next.failure());
                statusKind = StatusKind.ERROR;
            }
            case SUCCEEDED -> applyPublishedStatus(next);
        }
        if (!initializing && screenActive && minecraft != null
                && MinecraftCompat.screen(minecraft) == this) {
            if (next.phase() == CatalogPublishSession.Phase.NEEDS_PUBLISHER_NAME) {
                showPublisherNamePrompt(next);
            } else if (resultLayoutChanged) {
                rebuildWidgets();
            } else {
                refreshControlState();
            }
        }
    }

    private void applyPublishedStatus(CatalogPublishSession.Snapshot next) {
        if (next.copied()) {
            statusText = Component.translatable(
                    "waypointer.screen.route_publish.status.copied");
            statusKind = StatusKind.OK;
        } else if (next.publicationSaveFailed()) {
            statusText = Component.translatable(
                    "waypointer.screen.route_publish.status.record_not_saved");
            statusKind = StatusKind.ERROR;
        } else if (next.nameSaveFailed()) {
            statusText = Component.translatable(
                    "waypointer.screen.route_publish.status.name_not_saved");
            statusKind = StatusKind.ERROR;
        } else {
            statusText = Component.translatable(
                    "waypointer.screen.route_publish.status.published",
                    LocalizedNumberFormatter.active().integer(
                            next.result().route().version()));
            statusKind = StatusKind.OK;
        }
    }

    private void showPublisherNamePrompt(CatalogPublishSession.Snapshot state) {
        if (!RoutePublishUiState.shouldPromptPublisherName(
                publisherPromptAttempt, state)) return;
        publisherPromptAttempt = state.attempt();
        PublisherNameScreen.open(this, promptedPublisherName(state),
                session::confirmPublisherName);
    }

    /**
     * A catalog reset can forget a name this identity already confirmed; suggest
     * the stored claim first so re-registering it is a single confirmation.
     */
    private String promptedPublisherName(CatalogPublishSession.Snapshot state) {
        PublisherIdentity identity = state.identity();
        return identity != null && identity.publisherName() != null
                ? identity.publisherName()
                : defaultPublisherName();
    }

    /**
     * Copies the short catalog-reference code rather than the full payload: it
     * installs the published route from the catalog on any size of route and
     * works for unlisted publications too.
     */
    private void copyPublishedCode() {
        if (publishState.publishedPayload() == null || minecraft == null) return;
        String code = publishState.result() != null && publishState.result().route() != null
                ? UniversalShareCodec.encodeCatalogReference(publishState.result().route().id())
                : publishState.publishedPayload();
        minecraft.keyboardHandler.setClipboard(code);
        session.markCopied();
    }

    private boolean validInput() {
        return form.valid();
    }

    private Component validationHint() {
        CatalogPublishFormModel.Validation validation = form.validation();
        if (validation == null) return null;
        return switch (validation) {
            case DUNGEON_ROUTE -> Component.translatable(
                    "waypointer.screen.route_publish.validation.dungeon");
            case EMPTY_ROUTE -> Component.translatable(
                    "waypointer.screen.route_publish.validation.empty_route");
            case TEMPORARY_ROUTE -> Component.translatable(
                    "waypointer.screen.route_publish.validation.temporary");
            case UNPUBLISHABLE_ZONE -> Component.translatable(
                    "waypointer.screen.route_publish.validation.zone");
            case TITLE_REQUIRED -> Component.translatable(
                    "waypointer.screen.route_publish.validation.title_required");
            case DESCRIPTION_TOO_SHORT -> Component.translatable(
                    "waypointer.screen.route_publish.validation.description_min",
                    DESCRIPTION_MIN);
            case DESCRIPTION_TOO_LONG -> Component.translatable(
                    "waypointer.screen.route_publish.validation.description_max",
                    DESCRIPTION_MAX);
        };
    }

    private void refreshControlState() {
        RoutePublishUiState.Controls controls = RoutePublishUiState.controls(
                publishState, validInput());
        if (publishButton != null) {
            publishButton.active = controls.publishEnabled();
            publishButton.setMessage(publishButtonLabel());
        }
        if (backButton != null) {
            backButton.active = controls.backEnabled();
        }
        if (manageButton != null) manageButton.active = controls.manageEnabled();
        if (titleBox != null) titleBox.active = controls.editable();
        if (descriptionBox != null) descriptionBox.active = controls.editable();
        if (unlistedButton != null) unlistedButton.active = controls.editable();
        if (publicButton != null) publicButton.active = controls.editable();
        if (copyButton != null) {
            copyButton.visible = controls.copyVisible();
            copyButton.active = controls.copyEnabled();
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);

        graphics.text(font, font.plainSubstrByWidth(getTitle().getString(), contentW),
                contentX, titleY, TEXT, false);
        renderRouteSummary(graphics);
        graphics.fill(contentX, formY - GAP_TIGHT,
                contentX + contentW, formY - GAP_TIGHT + 1, BORDER);

        drawFieldLabel(graphics,
                Component.translatable("waypointer.screen.route_publish.field.title"),
                formY, form.title(), TITLE_MAX);
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
        if (showPaintNotice) {
            graphics.text(font, font.plainSubstrByWidth(
                            paintOmittedNotice().getString(), formW),
                    formX, paintNoticeY, GuiTokens.WARNING, false);
        }
        renderStatus(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        renderVisibilitySelection(graphics);
        renderDescriptionCounter(graphics);
    }

    private void renderVisibilitySelection(GuiGraphicsExtractor graphics) {
        Button selected = form.visibility() == CatalogPublishRequest.Visibility.PUBLIC
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
        int remaining = descriptionCharactersRemaining(form.description());
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
        graphics.fill(backdropLeft, backdropTop, backdropRight, backdropTop + 1, BORDER);
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

    private void renderStatus(GuiGraphicsExtractor graphics) {
        Component validation = validationHint();
        boolean showsValidation = statusText.getString().isBlank() && validation != null;
        Component message = showsValidation ? validation : statusText;
        if (message == null || message.getString().isEmpty()) return;

        int color = showsValidation ? STATUS_ERROR : statusColor();
        int textX = statusX;
        String marker = showsValidation ? "!" : statusMarker();
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
        String trimmed = form.previewName();
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
        if (!canNavigateBack(publishState.phase())) return;
        MinecraftCompat.setScreen(minecraft, parent);
    }

    static boolean canNavigateBack(CatalogPublishSession.Phase phase) {
        return RoutePublishUiState.canNavigateBack(phase);
    }

    @Override
    public void removed() {
        screenActive = false;
        removeSessionListener.run();
        removeSessionListener = () -> { };
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

    static boolean customPaintOmitted(WaypointGroup group) {
        return group != null && group.paint() != null && group.paintEnabled();
    }

    private static Component paintOmittedNotice() {
        return Component.translatable("waypointer.screen.route_publish.paint_omitted");
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
        if (customPaintOmitted(group)) {
            outcome = Component.empty().append(paintOmittedNotice()).append(" ").append(outcome);
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
                case "daily_limit" ->
                        "waypointer.screen.route_publish.error.daily_limit";
                case "content_flagged" ->
                        "waypointer.screen.route_publish.error.content_flagged";
                case "title_taken" -> "waypointer.screen.route_publish.error.title_taken";
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
        return CatalogPublishFormModel.descriptionLengthValid(description);
    }

    static String normalizeDescriptionInput(String value) {
        return CatalogPublishFormModel.normalizeDescriptionInput(value);
    }

    static int descriptionCharactersRemaining(String description) {
        return CatalogPublishFormModel.descriptionCharactersRemaining(description);
    }
}
