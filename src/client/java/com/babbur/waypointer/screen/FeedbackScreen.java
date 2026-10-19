package com.babbur.waypointer.screen;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.feedback.FeedbackClient;
import com.babbur.waypointer.feedback.FeedbackImage;
import com.babbur.waypointer.feedback.FeedbackImagePreview;
import com.babbur.waypointer.feedback.FeedbackQuota;
import com.babbur.waypointer.feedback.FeedbackSubmission;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static com.babbur.waypointer.screen.GuiTokens.*;

public final class FeedbackScreen extends Screen {
    private final Screen parent;
    private final WaypointerConfig config;
    private final FeedbackClient client = new FeedbackClient();
    private final List<FeedbackImage> images = new ArrayList<>();
    private final List<BodyWidget> bodyWidgets = new ArrayList<>();
    private final List<Caption> captions = new ArrayList<>();
    private final List<ImageTile> imageTiles = new ArrayList<>();
    private final Map<FeedbackImage, FeedbackImagePreview> imagePreviews = new IdentityHashMap<>();
    private final FeedbackQuota quota = FeedbackQuota.shared();
    private Category category = Category.COMMENT;
    private String message = "";
    private boolean anonymize = true;
    private boolean includeDebug;
    private DebugInspectScreen.FeedbackSnapshot snapshot;
    private boolean busy;
    private boolean sent;
    private boolean rebuild;
    private String status = "";
    private int scroll;
    private int contentHeight;
    private int left;
    private int formWidth;
    private int bottom;
    private int cursor;
    private int footerY;
    private Button sendButton;
    private static final int TOP = 30;

    enum Category {
        COMMENT("comment", "general", "[Comment] "),
        BUG("bug", "bug", "");

        final String key;
        final String wireType;
        final String prefix;

        Category(String key, String wireType, String prefix) {
            this.key = key;
            this.wireType = wireType;
            this.prefix = prefix;
        }

        String fallbackLabel() {
            return switch (this) {
                case COMMENT -> "Comment";
                case BUG -> "Bug report";
            };
        }
    }

    private record BodyWidget(AbstractWidget widget, int y) {}
    private record Caption(Component text, int x, int y, int color) {}
    private record ImageTile(FeedbackImage image, int x, int y, int width, int height) {}

    public FeedbackScreen(Screen parent, WaypointerConfig config) {
        super(tr("title", "Send Feedback"));
        this.parent = parent;
        this.config = config;
    }

    static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("waypointer.screen.feedback." + key, fallback, args);
    }

    @Override
    protected void init() {
        bodyWidgets.clear();
        captions.clear();
        imageTiles.clear();
        formWidth = Math.min(450, Math.max(180, width - 32));
        left = (width - formWidth) / 2;
        footerY = height - 36 - font.split(emailFootnote(), formWidth).size() * 11;
        int statusHeight = status.isEmpty() ? 0 : Math.min(3, font.split(Component.literal(status), formWidth).size()) * 11 + 6;
        bottom = footerY - statusHeight - 8;
        cursor = 0;
        int half = (formWidth - GAP) / 2;
        Category[] categories = Category.values();
        int column = (formWidth - GAP * (categories.length - 1)) / categories.length;
        for (int i = 0; i < categories.length; i++) {
            Category option = categories[i];
            int x = left + i * (column + GAP);
            int w = i == categories.length - 1 ? formWidth - i * (column + GAP) : column;
            button(x, cursor, w, tr(option.key, option.fallbackLabel()), () -> chooseCategory(option));
            if (option == category) {
                bodyWidgets.getLast().widget().setMessage(
                        colored(tr(option.key, option.fallbackLabel()), ACCENT));
            }
        }
        cursor += 28;
        int remaining = quota.remainingToday();
        if (remaining == 0) {
            caption(tr("quota.exhausted", "You have used today's %s feedback sends. Try again tomorrow.",
                    FeedbackQuota.DAILY_LIMIT), WARNING);
        } else {
            caption(tr("quota", "%s of %s sends left today.", remaining, FeedbackQuota.DAILY_LIMIT), TEXT_DIM);
        }
        caption(tr("message", "Message (up to 10,000 characters)"), TEXT);
        MultiLineEditBox box = MultiLineEditBox.builder().setX(left).setY(TOP + cursor)
                .setPlaceholder(tr("message.hint", "What happened, or what would you like to improve?"))
                .build(font, formWidth, 80, tr("message", "Message (up to 10,000 characters)"));
        box.setCharacterLimit(messageLimit());
        box.setValue(message);
        box.setValueListener(value -> { message = value; updateSend(); });
        body(box, cursor);
        cursor += 90;
        int addWidth = Math.min(110, formWidth / 3);
        button(left, cursor, addWidth, tr("images.add", "Add image..."), this::chooseImage);
        bodyWidgets.getLast().widget().active = !busy && !sent && images.size() < FeedbackSubmission.MAX_IMAGES;
        Component imageLimit = tr("images.help", "Up to 4 images allowed, 10 MiB total.");
        captions.add(new Caption(imageLimit, left + addWidth + GAP, cursor + 5, TEXT_DIM));
        cursor += Math.max(26, font.split(imageLimit, formWidth - addWidth - GAP).size() * 11 + 10);
        int tileWidth = (formWidth - 3 * GAP) / 4;
        int tileHeight = Math.min(64, tileWidth);
        for (int i = 0; i < images.size(); i++) {
            int index = i;
            FeedbackImage image = images.get(i);
            int tileX = left + i * (tileWidth + GAP);
            imageTiles.add(new ImageTile(image, tileX, cursor, tileWidth, tileHeight));
            button(tileX, cursor + tileHeight + 4, tileWidth, tr("remove", "Remove"), () -> {
                if (!images.remove(image)) return;
                FeedbackImagePreview preview = imagePreviews.remove(image);
                if (preview != null) preview.close();
                rebuild = true;
            });
            bodyWidgets.getLast().widget().setTooltip(Tooltip.create(tr("images.item", "Image %s (%s KiB)",
                    i + 1, (image.size() + 1023) / 1024)));
        }
        if (!images.isEmpty()) cursor += tileHeight + BTN_H + 12;
        if (category == Category.BUG) {
            checkbox(tr("debug", "Attach a debug snapshot"), includeDebug, value -> {
                includeDebug = value;
                if (value && snapshot == null) capture();
                if (!value) snapshot = null;
                rebuild = true;
            }, true);
            if (includeDebug) {
                checkbox(tr("anonymize", "Anonymize Data"), anonymize,
                        value -> { anonymize = value; rebuild = true; }, true);
                button(left, cursor, half, tr("debug.preview", "Preview report"), () -> {
                    if (snapshot != null) MinecraftCompat.setScreen(minecraft,
                            new FeedbackReportPreviewScreen(this, snapshot.report(anonymize)));
                });
                button(left + half + GAP, cursor, half, tr("debug.refresh", "Refresh snapshot"), this::capture);
                cursor += 28;
            }
        }
        if (includeDebug) caption(images.isEmpty()
                ? tr("privacy.no_images", "'Anonymize Data' attempts to anonymize your debug snapshot. Your message is not redacted.")
                : tr("privacy", "'Anonymize Data' attempts to anonymize your debug snapshot. Your message and images are not redacted."), TEXT_DIM);
        caption(tr("network", "The server does not store your IP address with feedback. It is used to prevent spam."), TEXT_MUTED);
        if (!images.isEmpty()) caption(tr("images.privacy", "Image metadata and file names are removed."), TEXT_MUTED);
        contentHeight = cursor;
        positionBody();
        addRenderableWidget(styledButton(left, height - 28, half, BTN_H,
                Component.translatable("gui.back"), b -> onClose(), null));
        sendButton = addRenderableWidget(styledButton(left + half + GAP, height - 28, half, BTN_H,
                tr("send", "Send Feedback"), b -> send(), null));
        updateSend();
    }

    private void chooseCategory(Category next) {
        if (category == next) return;
        category = next;
        includeDebug = category == Category.BUG;
        snapshot = null;
        if (includeDebug) { anonymize = true; capture(); }
        rebuild = true;
    }

    private int messageLimit() {
        int longestPrefix = 0;
        for (Category option : Category.values()) longestPrefix = Math.max(longestPrefix, option.prefix.length());
        return FeedbackSubmission.MAX_MESSAGE - longestPrefix;
    }

    private void caption(Component text, int color) {
        captions.add(new Caption(text, left, cursor, color));
        cursor += font.split(text, formWidth).size() * 11 + 5;
    }

    private void checkbox(Component label, boolean selected, Consumer<Boolean> changed, boolean enabled) {
        var check = styledCheckbox(left, TOP + cursor, 18, label, selected, changed, Tooltip.create(label));
        check.active = enabled && !busy;
        body(check, cursor);
        captions.add(new Caption(label, left + 24, cursor + 4, TEXT));
        cursor += Math.max(25, font.split(label, formWidth - 24).size() * 11 + 8);
    }

    private void button(int x, int y, int w, Component label, Runnable action) {
        var button = styledButton(x, TOP + y, w, BTN_H, label, b -> action.run(), null);
        button.active = !busy && !sent;
        body(button, y);
    }

    private void body(AbstractWidget widget, int y) {
        if (busy || sent) widget.active = false;
        bodyWidgets.add(new BodyWidget(widget, y));
        addRenderableWidget(widget);
    }

    private void positionBody() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - (bottom - TOP))));
        for (BodyWidget row : bodyWidgets) {
            AbstractWidget widget = row.widget();
            widget.setY(TOP + row.y() - scroll);
            widget.visible = widget.getY() >= TOP && widget.getY() + widget.getHeight() <= bottom;
        }
    }

    private void capture() {
        try {
            snapshot = DebugInspectScreen.captureFeedbackSnapshot(WaypointerClient.manager(), config);
            status = "";
        } catch (RuntimeException e) {
            snapshot = null;
            includeDebug = false;
            status = tr("capture.failed", "Could not capture the report. You can still send feedback without it.").getString();
        }
        rebuild = true;
    }

    private void chooseImage() {
        if (busy || sent || images.size() >= FeedbackSubmission.MAX_IMAGES) return;
        String path;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(3);
            filters.put(stack.UTF8("*.png")).put(stack.UTF8("*.jpg")).put(stack.UTF8("*.jpeg")).flip();
            path = TinyFileDialogs.tinyfd_openFileDialog(tr("images.picker", "Choose feedback image").getString(),
                    null, filters, "PNG / JPEG", false);
        } catch (RuntimeException | LinkageError e) {
            status = tr("images.picker.failed", "The file picker could not open. Try again or send without images.").getString();
            rebuild = true;
            return;
        }
        if (path == null) return;
        busy = true;
        freezeDraft();
        status = tr("images.loading", "Preparing image...").getString();
        rebuild = true;
        CompletableFuture.supplyAsync(() -> {
            try { return FeedbackImage.read(Path.of(path)); }
            catch (Exception e) { throw new CompletionException(e); }
        }).whenComplete((image, error) -> minecraft.execute(() -> {
            busy = false;
            if (error != null) status = tr("images.failed", "Could not attach this image. Use a PNG/JPEG of 5 MiB or less and no more than 20 million pixels.").getString();
            else if (images.stream().mapToLong(FeedbackImage::size).sum() + image.size() > FeedbackSubmission.MAX_TOTAL_IMAGE_BYTES)
                status = tr("images.total", "Images must have a combined size of 10 MiB or less.").getString();
            else { images.add(image); status = ""; }
            rebuild = true;
        }));
    }

    private void updateSend() {
        if (sendButton == null) return;
        sendButton.active = !busy && !sent && !message.isBlank() && !quota.exhausted()
                && message.length() <= messageLimit() && (!includeDebug || snapshot != null);
        sendButton.setMessage(busy ? tr("sending", "Please wait...") : sent
                ? tr("sent", "Feedback sent") : tr("send", "Send Feedback"));
    }

    private void send() {
        if (busy || sent || sendButton == null || !sendButton.active) return;
        FeedbackSubmission submission;
        try {
            submission = new FeedbackSubmission(category.wireType, category.prefix + message, true, "",
                    includeDebug && snapshot != null ? snapshot.report(anonymize) : "",
                    includeDebug && anonymize, images);
        } catch (IllegalArgumentException e) { status = e.getMessage(); rebuild = true; return; }
        busy = true;
        freezeDraft();
        status = tr("uploading", "Sending feedback...").getString();
        rebuild = true;
        client.send(submission).whenComplete((ignored, error) -> minecraft.execute(() -> {
            busy = false;
            sent = error == null;
            if (sent) {
                quota.recordSend();
                status = tr("success", "Thank you. Your feedback was received.").getString();
            }
            else {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                status = cause instanceof IllegalStateException ? cause.getMessage()
                        : tr("failed", "Delivery was not confirmed. Your draft is kept. Check your connection before retrying.").getString();
            }
            rebuild = true;
        }));
    }

    @Override public void tick() {
        if (rebuild) { rebuild = false; rebuildWidgets(); }
    }

    private void freezeDraft() {
        for (BodyWidget row : bodyWidgets) row.widget().active = false;
        setFocused(null);
        updateSend();
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= TOP && y < bottom && super.mouseScrolled(x, y, horizontal, vertical)) return true;
        scroll -= (int) (vertical * 24);
        positionBody();
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            List<AbstractWidget> controls = children().stream()
                    .filter(child -> child instanceof AbstractWidget)
                    .map(child -> (AbstractWidget) child).filter(widget -> widget.active).toList();
            if (!controls.isEmpty()) {
                int direction = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
                int index = controls.indexOf(getFocused());
                AbstractWidget next = controls.get(Math.floorMod(index + direction, controls.size()));
                for (BodyWidget row : bodyWidgets) {
                    if (row.widget() != next) continue;
                    if (row.y() < scroll) scroll = row.y();
                    else if (row.y() + next.getHeight() > scroll + bottom - TOP)
                        scroll = row.y() + next.getHeight() - (bottom - TOP);
                    positionBody();
                    break;
                }
                setFocused(next);
                return true;
            }
        }
        if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN
                || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
            scroll += event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? bottom - TOP : TOP - bottom;
            positionBody();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, SURFACE);
        g.text(font, getTitle(), left, 10, TEXT, false);
        g.enableScissor(left, TOP, left + formWidth, bottom);
        for (Caption caption : captions) {
            int y = TOP + caption.y() - scroll;
            for (FormattedCharSequence line : font.split(caption.text(), left + formWidth - caption.x())) {
                g.text(font, line, caption.x(), y, caption.color(), false);
                y += 11;
            }
        }
        for (ImageTile tile : imageTiles) {
            int y = TOP + tile.y() - scroll;
            if (y + tile.height() <= TOP || y >= bottom) continue;
            imagePreviews.computeIfAbsent(tile.image(), FeedbackImagePreview::new)
                    .render(g, tile.x(), y, tile.width(), tile.height());
        }
        g.disableScissor();
        if (contentHeight > bottom - TOP) {
            int track = bottom - TOP;
            int thumb = Math.max(12, track * track / contentHeight);
            int thumbY = TOP + (track - thumb) * scroll / Math.max(1, contentHeight - track);
            g.fill(left + formWidth + 5, TOP, left + formWidth + 7, bottom, BORDER);
            g.fill(left + formWidth + 5, thumbY, left + formWidth + 7, thumbY + thumb, TEXT_MUTED);
        }
        int y = bottom + 4;
        for (FormattedCharSequence line : font.split(Component.literal(status), formWidth)) {
            if (y + 11 > footerY - 3) break;
            g.text(font, line, left, y, sent ? SUCCESS : TEXT_DIM, false);
            y += 11;
        }
        y = footerY;
        for (FormattedCharSequence line : font.split(emailFootnote(), formWidth)) {
            g.text(font, line, left, y, TEXT_DIM, false);
            y += 11;
        }
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    @Override public void onClose() { MinecraftCompat.setScreen(minecraft, parent); }
    @Override public void removed() {
        imagePreviews.values().forEach(FeedbackImagePreview::close);
        imagePreviews.clear();
        super.removed();
    }
    private static Component emailFootnote() {
        return tr("email", "All feedback is emailed directly to me, so pls don't spam :)");
    }
    @Override public boolean isPauseScreen() { return false; }
}
