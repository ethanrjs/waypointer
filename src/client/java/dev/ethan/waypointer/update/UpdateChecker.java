package dev.ethan.waypointer.update;

import dev.ethan.waypointer.Waypointer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Background update checker. Fetches the latest release tag from GitHub,
 * compares it to the mod's packaged version, and posts a chat notice when a
 * newer tag exists.
 *
 * <p>Workflow:
 * <ul>
 *   <li>Scheduled on a daemon thread (async HTTP client) so neither the client
 *       tick loop nor initialisation blocks on GitHub's response time.</li>
 *   <li>Starts after a 5-second delay so the notice doesn't land during the
 *       noisy first seconds of world load where it would be buried in
 *       Hypixel's welcome spam / mod init logs.</li>
 *   <li>On a hit, posts a single clickable chat line: clicking opens the
 *       release page in the user's browser. No toast, no nag window.</li>
 * </ul>
 *
 * <p>Failure modes (network down, GitHub 5xx, malformed JSON, unknown local
 * version) are all silent. The user either sees a notice or they don't -- a
 * failed update check is never a useful piece of chat spam.
 */
public final class UpdateChecker {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/ethanrjs/waypointer/releases/latest";
    private static final String RELEASES_PAGE =
            "https://github.com/ethanrjs/waypointer/releases/latest";

    /**
     * How long after construction to fire the first (and only) check.
     * Tuned to be past the typical Hypixel welcome chat but before the user
     * has had time to get deep into gameplay where a notice would annoy them.
     */
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(5);

    /** Rough "tag_name": "vX.Y.Z" pattern. Accepts the v-prefix optionally. */
    private static final Pattern TAG_PATTERN =
            Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9.]+(?:[-+][A-Za-z0-9.-]+)?)\"");

    private final String localVersion;
    private final boolean enabled;

    public UpdateChecker(String localVersion, boolean enabled) {
        this.localVersion = localVersion;
        this.enabled = enabled;
    }

    /**
     * Fire-and-forget. Returns immediately; the actual HTTP work happens on a
     * background daemon thread and the chat notice is posted from the client
     * tick thread via {@link Minecraft#execute(Runnable)} to stay safe.
     */
    public void start() {
        if (!enabled) return;

        Thread t = new Thread(this::runOnce, "Waypointer-UpdateCheck");
        t.setDaemon(true);
        t.start();
    }

    private void runOnce() {
        try {
            Thread.sleep(INITIAL_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        String latest;
        try {
            latest = fetchLatestTag();
        } catch (Exception e) {
            Waypointer.LOGGER.debug("Update check skipped: {}", e.toString());
            return;
        }
        if (latest == null) return;

        if (compareSemver(localVersion, latest) < 0) {
            postNotice(latest);
        }
    }

    /** @return the tag_name from the releases endpoint (without leading 'v'), or null if unparseable. */
    private static String fetchLatestTag() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "waypointer-update-check")
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) return null;

        Matcher m = TAG_PATTERN.matcher(resp.body());
        return m.find() ? m.group(1) : null;
    }

    /**
     * Semver-ish comparison. Handles the common {@code X.Y.Z} case and ignores
     * pre-release / build metadata by stripping everything after the first
     * {@code -} or {@code +}. Returns negative when {@code a < b}, zero when
     * equal, positive when {@code a > b}.
     *
     * <p>Unknown or malformed versions sort as "oldest" so a garbage local
     * version would trigger a notice rather than silently suppress one. That
     * biases toward "bother the user" which is the correct default for a check
     * that's explicitly opt-in via config.
     */
    static int compareSemver(String a, String b) {
        int[] ap = parseNumeric(a);
        int[] bp = parseNumeric(b);
        int len = Math.max(ap.length, bp.length);
        for (int i = 0; i < len; i++) {
            int av = i < ap.length ? ap[i] : 0;
            int bv = i < bp.length ? bp[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] parseNumeric(String v) {
        if (v == null) return new int[]{0, 0, 0};
        // Strip leading 'v', trailing pre-release/build metadata.
        String stripped = v.trim();
        if (stripped.startsWith("v")) stripped = stripped.substring(1);
        int cut = stripped.length();
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '-' || c == '+') { cut = i; break; }
        }
        stripped = stripped.substring(0, cut);
        String[] parts = stripped.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { out[i] = 0; }
        }
        return out;
    }

    private void postNotice(String latest) {
        // Hop onto the render thread before poking the chat gui. HttpClient's
        // callback runs on a pool thread, and touching Minecraft state from
        // there is undefined behaviour.
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui == null) return;
            MutableComponent prefix = Component.literal("[Waypointer] ")
                    .withStyle(ChatFormatting.AQUA);
            MutableComponent body = Component.literal(
                    "A newer version is available: v" + latest + " (you have v"
                    + localVersion + "). Click for release page.")
                    .withStyle(Style.EMPTY
                            .withColor(ChatFormatting.YELLOW)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(RELEASES_PAGE))));
            mc.gui.getChat().addMessage(prefix.append(body));
        });
    }
}
