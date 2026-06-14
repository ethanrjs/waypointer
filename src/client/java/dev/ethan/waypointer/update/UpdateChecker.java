package dev.ethan.waypointer.update;

import dev.ethan.waypointer.Waypointer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;
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
    private static final URI RELEASES_PAGE_URI = URI.create(RELEASES_PAGE);

    /**
     * How long after construction to fire the first (and only) check.
     * Tuned to be past the typical Hypixel welcome chat but before the user
     * has had time to get deep into gameplay where a notice would annoy them.
     */
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(5);

    /** Rough "tag_name": "vX.Y.Z" pattern. Accepts the v-prefix optionally. */
    private static final Pattern TAG_PATTERN =
            Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9.]+(?:[-+][A-Za-z0-9.-]+)?)\"");
    private static final Pattern HTML_URL_PATTERN =
            Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"");
private static final Pattern DOWNLOAD_URL_PATTERN =
            Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"");
    private static final String WINDOWS_UPDATE_SCRIPT_PREFIX = "waypointer-update-";

    private final String localVersion;
    private final boolean enabled;

        public record CheckResult(String localVersion,
                              String latestVersion,
                              boolean updateAvailable,
                              URI releasePageUri,
                              URI downloadUri,
                              String failureMessage) {}

        private record ReleaseInfo(String latestVersion, URI releasePageUri, URI downloadUri) {}

        public record DownloadResult(boolean success,
                                 Path downloadedJar,
                                 Path disabledCurrentJar,
                                 String message) {}

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

        CheckResult result;
        try {
            result = checkLatest(localVersion);
        } catch (Exception e) {
            Waypointer.LOGGER.debug("Update check skipped: {}", e.toString());
            return;
        }

        if (result.updateAvailable()) {
            postNotice(result);
        }
    }

        public static String currentModVersion() {
        var container = FabricLoader.getInstance().getModContainer(Waypointer.MOD_ID);
        if (container.isPresent()) {
            return container.get().getMetadata().getVersion().getFriendlyString();
        }
        return "0.0.0";
    }

        public static CompletableFuture<CheckResult> checkLatestAsync(String localVersion) {
        return CompletableFuture.supplyAsync(
                                () -> {
            try {
                return checkLatest(localVersion);
            } catch (Exception e) {
                Waypointer.LOGGER.debug("Manual update check failed: {}", e.toString());
                return new CheckResult(localVersion, null, false,
                        RELEASES_PAGE_URI, RELEASES_PAGE_URI,
                "Could not check GitHub releases.");
            }
        });
    }

        public static CompletableFuture<DownloadResult> downloadLatestJarAsync(CheckResult result) {
        return CompletableFuture.supplyAsync(
                                () -> {
            try {
                return installLatestJar(result);
            } catch (Exception e) {
                Waypointer.LOGGER.debug("Update download failed: {}", e.toString());
                return new DownloadResult(false, null, null,
                        "Could not download update. Try again.");
            }
        });
    }

        private static DownloadResult installLatestJar(CheckResult result) throws Exception {
        if (result == null || !result.updateAvailable()) {
            return new DownloadResult(false, null, null, "No update is ready to download.");
        }
        URI downloadUri = result.downloadUri();
        if (!isJarDownloadUri(downloadUri)) {
            return new DownloadResult(false, null, null, "Release has no jar asset.");
        }

        Path modsDir = FabricLoader.getInstance().getGameDir()
                .resolve("mods")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(modsDir);

        Path currentJar = currentWaypointerJarPath();
        Path target = updateJarTargetPath(modsDir, downloadUri, result.latestVersion());
        if (currentJar != null && samePath(target, currentJar)) {
            target = updateJarTargetPath(modsDir,
                    URI.create("https://github.com/ethanrjs/waypointer/releases/download/v"
                            + safeFileSegment(result.latestVersion()) + "/waypointer-"
                            + safeFileSegment(result.latestVersion()) + ".jar"),
                    result.latestVersion());
        }

        Path temp = Files.createTempFile(modsDir, "waypointer-update-", ".jar.part");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(downloadUri)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "waypointer-update-download")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(req, HttpResponse.BodyHandlers.ofFile(
                    temp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE));
            if (response.statusCode() / 100 != 2) {
                return new DownloadResult(false, null, null, "Download failed. Try again.");
            }
            if (!Files.exists(temp) || Files.size(temp) <= 0L) {
                return new DownloadResult(false, null, null, "Downloaded jar was empty.");
            }

            moveReplacing(temp, target);

            Path disabledPath = null;
            if (currentJar != null
                    && currentJar.getParent() != null
                    && samePath(currentJar.getParent(), modsDir)
                    && !samePath(currentJar, target)) {
                disabledPath = uniqueDisabledPath(currentJar);
                try {
                    disableCurrentJarForNextLaunch(currentJar, disabledPath);
                } catch (java.io.IOException e) {
                    Waypointer.LOGGER.debug("Downloaded update but could not disable old jar: {}",
                            e.toString());
                    return new DownloadResult(false, target, disabledPath,
                            "Downloaded, but remove old Waypointer jar before restart.");
                }
            }

            return new DownloadResult(true, target, disabledPath,
                    "Downloaded. Restart Minecraft to update.");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

        public static boolean isJarDownloadUri(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getPath() == null) return false;
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        return uri.getPath().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

        private static Path currentWaypointerJarPath() {
        var container = FabricLoader.getInstance().getModContainer(Waypointer.MOD_ID);
        if (container.isEmpty()) return null;
        ModOrigin origin = container.get().getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH) return null;
        for (Path path : origin.getPaths()) {
            Path normalized = path.toAbsolutePath().normalize();
            String fileName = normalized.getFileName() == null ? "" : normalized.getFileName().toString();
            if (Files.isRegularFile(normalized)
                    && fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return normalized;
            }
        }
        return null;
    }

        private static Path updateJarTargetPath(Path modsDir, URI downloadUri, String latestVersion) {
        String path = downloadUri == null ? "" : downloadUri.getPath();
        int slash = path == null ? -1 : path.lastIndexOf('/');
        String rawName = slash >= 0 ? path.substring(slash + 1) : path;
        String safeName = safeFileSegment(rawName);
        if (safeName.isBlank() || !safeName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            safeName = "waypointer-" + safeFileSegment(latestVersion) + ".jar";
        }
        Path target = modsDir.resolve(safeName).toAbsolutePath().normalize();
        if (!samePath(target.getParent(), modsDir)) {
            throw new IllegalArgumentException("update target escaped mods directory");
        }
        return target;
    }

        private static String safeFileSegment(String raw) {
        if (raw == null) return "latest";
        String trimmed = raw.trim();
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        while (!out.isEmpty() && out.charAt(0) == '.') {
            out.deleteCharAt(0);
        }
        return out.isEmpty() ? "latest" : out.toString();
    }

        private static void moveReplacing(Path source, Path target) throws java.io.IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

        private static Path uniqueDisabledPath(Path currentJar) {
        Path parent = currentJar.getParent();
        String base = currentJar.getFileName().toString() + ".disabled";
        Path candidate = parent.resolve(base);
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(base + "." + counter);
            counter++;
        }
        return candidate;
    }

        private static void disableCurrentJarForNextLaunch(Path currentJar, Path disabledPath)
            throws java.io.IOException {
        if (!isWindows()) {
            Files.move(currentJar, disabledPath, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        Path script = currentJar.getParent().resolve(WINDOWS_UPDATE_SCRIPT_PREFIX
                + ProcessHandle.current().pid() + ".cmd");
        String body = """
                @echo off
                setlocal
                set "PID=%d"
                set "OLD=%s"
                set "DISABLED=%s"
                :wait
                tasklist /FI "PID eq %%PID%%" 2>NUL | findstr /R /C:"%%PID%%" >NUL
                if not errorlevel 1 (
                  timeout /T 1 /NOBREAK >NUL
                  goto wait
                )
                if exist "%%OLD%%" move /Y "%%OLD%%" "%%DISABLED%%" >NUL
                del /F /Q "%%~f0" >NUL 2>NUL
                """.formatted(
                ProcessHandle.current().pid(),
                currentJar.toString().replace("\"", ""),
                disabledPath.toString().replace("\"", ""));
        Files.writeString(script, body, StandardCharsets.UTF_8);
        new ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "/min", script.toString()).start();
    }

        private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

        private static boolean samePath(Path a, Path b) {
        return a != null
                && b != null
                && a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

        private static CheckResult checkLatest(String localVersion) throws Exception {
        ReleaseInfo release = fetchLatestRelease();
        if (release == null) {
            throw new IllegalStateException("latest release response did not include a tag");
        }
        boolean updateAvailable = compareSemver(localVersion, release.latestVersion()) < 0;
        return new CheckResult(localVersion, release.latestVersion(), updateAvailable,
                release.releasePageUri(), release.downloadUri(), null);
    }

        private static ReleaseInfo fetchLatestRelease() throws Exception {
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

        return parseReleaseInfo(resp.body());
    }

        private static ReleaseInfo parseReleaseInfo(String body) {
        if (body == null) return null;
        Matcher tagMatcher = TAG_PATTERN.matcher(body);
        if (!tagMatcher.find()) return null;

        URI releasePage = RELEASES_PAGE_URI;
        Matcher pageMatcher = HTML_URL_PATTERN.matcher(body);
        if (pageMatcher.find()) {
            releasePage = URI.create(pageMatcher.group(1));
        }

        URI download = null;
        Matcher downloadMatcher = DOWNLOAD_URL_PATTERN.matcher(body);
        if (downloadMatcher.find()) {
            download = URI.create(downloadMatcher.group(1));
        }

        return new ReleaseInfo(tagMatcher.group(1), releasePage, download);
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

        private void postNotice(CheckResult result) {
        // Hop onto the render thread before poking the chat gui. HttpClient's
        // callback runs on a pool thread, and touching Minecraft state from
        // there is undefined behaviour.
        Minecraft mc = Minecraft.getInstance();
        mc.execute(
                                () -> {
            if (mc.gui == null) return;
            MutableComponent prefix = Component.literal("[Waypointer] ")
                    .withStyle(ChatFormatting.AQUA);
            MutableComponent body = Component.literal(
                    "A newer version is available: v" + result.latestVersion() + " (you have v"
                    + localVersion + "). Click for release page.")
                    .withStyle(Style.EMPTY
                            .withColor(ChatFormatting.YELLOW)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenUrl(result.releasePageUri())));
            mc.gui.getChat().addMessage(prefix.append(body));
        });
    }
}
