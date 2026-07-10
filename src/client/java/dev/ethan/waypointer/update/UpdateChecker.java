package dev.ethan.waypointer.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ethan.waypointer.Waypointer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.security.MessageDigest;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    private static final String RELEASE_ASSET_HOST = "github.com";
    private static final String RELEASE_ASSET_PATH_PREFIX =
            "/ethanrjs/waypointer/releases/download/";

    /**
     * How long after construction to fire the first (and only) check.
     * Tuned to be past the typical Hypixel welcome chat but before the user
     * has had time to get deep into gameplay where a notice would annoy them.
     */
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(5);

    private static final Pattern VERSION_TAG_PATTERN =
            Pattern.compile("[0-9]+(?:\\.[0-9]+)*(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?");
    private static final Pattern SHA256_DIGEST_PATTERN =
            Pattern.compile("[0-9a-f]{64}");
    private static final String WINDOWS_UPDATE_SCRIPT_PREFIX = "waypointer-update-";

    private final String localVersion;
    private final boolean enabled;

        public record CheckResult(String localVersion,
                              String latestVersion,
                              boolean updateAvailable,
                              URI releasePageUri,
                              URI downloadUri,
                              String downloadSha256,
                              String failureMessage) {}

        record ReleaseInfo(String latestVersion, URI releasePageUri, URI downloadUri, String downloadSha256) {}

        private record JarAsset(URI downloadUri, String sha256) {}

        public record DownloadResult(boolean success,
                                 Path downloadedJar,
                                 Path disabledCurrentJar,
                                 String message) {}

        private record ParsedVersion(int[] numeric, String prerelease) {}

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
                        RELEASES_PAGE_URI, RELEASES_PAGE_URI, null,
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
        String expectedSha256 = normalizedSha256Digest(result.downloadSha256());
        if (expectedSha256 == null) {
            return new DownloadResult(false, null, null,
                    "Release jar has no SHA-256 digest; update was not installed.");
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
            String actualSha256 = sha256Hex(temp);
            if (!expectedSha256.equals(actualSha256)) {
                return new DownloadResult(false, null, null,
                        "Downloaded jar failed SHA-256 verification.");
            }
            String verificationError = verifyDownloadedJar(temp, result.latestVersion());
            if (verificationError != null) {
                return new DownloadResult(false, null, null, verificationError);
            }

            Path disabledPath = null;
            if (currentJar != null
                    && currentJar.getParent() != null
                    && samePath(currentJar.getParent(), modsDir)
                    && !samePath(currentJar, target)) {
                disabledPath = uniqueDisabledPath(currentJar);
                if (isWindows()) {
                    Path pending = uniquePendingPath(target);
                    moveReplacing(temp, pending);
                    try {
                        stageWindowsUpdate(currentJar, disabledPath, pending, target);
                    } catch (java.io.IOException e) {
                        Files.deleteIfExists(pending);
                        Waypointer.LOGGER.debug("Could not stage Windows update: {}", e.toString());
                        return new DownloadResult(false, null, disabledPath,
                                "Downloaded, but the update could not be safely staged.");
                    }
                    return new DownloadResult(true, target, disabledPath,
                            "Update staged. Restart Minecraft to finish installing it.");
                }

                Files.move(currentJar, disabledPath, StandardCopyOption.REPLACE_EXISTING);
                try {
                    moveReplacing(temp, target);
                } catch (java.io.IOException e) {
                    Files.move(disabledPath, currentJar, StandardCopyOption.REPLACE_EXISTING);
                    throw e;
                }
            } else {
                moveReplacing(temp, target);
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
        if (!scheme.equals("https")) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!RELEASE_ASSET_HOST.equals(host)) return false;
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        if (!path.startsWith(RELEASE_ASSET_PATH_PREFIX)) return false;
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        return fileName.startsWith("waypointer-")
                && fileName.endsWith(".jar")
                && !fileName.contains("-sources")
                && !fileName.contains("-dev")
                && !fileName.contains("-javadoc");
    }

        public static boolean hasSha256Digest(String digest) {
        return normalizedSha256Digest(digest) != null;
    }

        static String verifyDownloadedJar(Path jar, String expectedVersion) {
        String expected = expectedVersion == null ? "" : expectedVersion.trim();
        if (expected.isBlank()) return "Release did not include an expected jar version.";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry metadataEntry = zip.getEntry("fabric.mod.json");
            if (metadataEntry == null) return "Downloaded jar is missing fabric.mod.json.";
            try (Reader reader = new InputStreamReader(
                    zip.getInputStream(metadataEntry), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) return "Downloaded jar metadata could not be read.";
                JsonObject metadata = parsed.getAsJsonObject();
                String id = normalizedString(metadata.get("id"));
                if (!Waypointer.MOD_ID.equals(id)) return "Downloaded jar is not a Waypointer mod.";
                String version = normalizedString(metadata.get("version"));
                if (version == null || version.isBlank()) return "Downloaded jar has no version.";
                if (!expected.equals(version)) return "Downloaded jar version did not match release.";
                return null;
            }
        } catch (Exception e) {
            return "Downloaded jar metadata could not be read.";
        }
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

        private static String sha256Hex(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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

    static Path uniquePendingPath(Path target) {
        Path parent = target.getParent();
        String base = target.getFileName().toString() + ".pending";
        Path candidate = parent.resolve(base);
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(base + "." + counter);
            counter++;
        }
        return candidate;
    }

    private static void stageWindowsUpdate(Path currentJar, Path disabledPath,
                                           Path pendingJar, Path targetJar)
            throws java.io.IOException {
        Path script = Files.createTempFile(currentJar.getParent(),
                WINDOWS_UPDATE_SCRIPT_PREFIX + ProcessHandle.current().pid() + "-", ".cmd");
        Path failedPath = disabledPath.resolveSibling(disabledPath.getFileName() + ".failed.txt");
        try {
            String body = windowsUpdateScriptBody(ProcessHandle.current().pid(), currentJar,
                    disabledPath, pendingJar, targetJar, failedPath);
            Files.writeString(script, body, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            String startCommand = "start \"\" /min \"" + script.toString().replace("\"", "") + "\"";
            new ProcessBuilder("cmd.exe", "/c", startCommand).start();
        } catch (java.io.IOException e) {
            Files.deleteIfExists(script);
            throw e;
        }
    }

    static String windowsUpdateScriptBody(long pid, Path currentJar, Path disabledPath,
                                          Path pendingJar, Path targetJar, Path failedPath) {
        String body = """
                @echo off
                setlocal
                set "PID=%d"
                set "OLD=%s"
                set "DISABLED=%s"
                set "PENDING=%s"
                set "TARGET=%s"
                set "FAILED=%s"
                :wait
                tasklist /FI "PID eq %%PID%%" 2>NUL | findstr /R /C:"%%PID%%" >NUL
                if not errorlevel 1 (
                  timeout /T 1 /NOBREAK >NUL
                  goto wait
                )
                if exist "%%OLD%%" (
                  move /Y "%%OLD%%" "%%DISABLED%%" >NUL
                  if errorlevel 1 goto fail_disable
                )
                move /Y "%%PENDING%%" "%%TARGET%%" >NUL
                if errorlevel 1 goto fail_activate
                del /F /Q "%%FAILED%%" >NUL 2>NUL
                goto cleanup
                :fail_activate
                if exist "%%DISABLED%%" move /Y "%%DISABLED%%" "%%OLD%%" >NUL
                echo Failed to activate new Waypointer jar; the old jar was restored.>"%%FAILED%%"
                goto cleanup
                :fail_disable
                echo Failed to disable old Waypointer jar; the update remains inactive.>"%%FAILED%%"
                :cleanup
                del /F /Q "%%~f0" >NUL 2>NUL
                """.formatted(
                pid,
                batchPath(currentJar),
                batchPath(disabledPath),
                batchPath(pendingJar),
                batchPath(targetJar),
                batchPath(failedPath));
        return body;
    }

    private static String batchPath(Path path) {
        return path.toString().replace("\"", "").replace("%", "%%");
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
                release.releasePageUri(), release.downloadUri(), release.downloadSha256(), null);
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

        static ReleaseInfo parseReleaseInfo(String body) {
        if (body == null || body.isBlank()) return null;

        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) return null;
            root = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }

        String latestVersion = normalizedVersionTag(root.get("tag_name"));
        if (latestVersion == null) return null;

        URI releasePage = safeUri(normalizedString(root.get("html_url")), RELEASES_PAGE_URI);
        JarAsset asset = firstJarAsset(root.get("assets"));
        return new ReleaseInfo(latestVersion, releasePage,
                asset == null ? null : asset.downloadUri(),
                asset == null ? null : asset.sha256());
    }

        private static JarAsset firstJarAsset(JsonElement assetsElement) {
        if (assetsElement == null || !assetsElement.isJsonArray()) return null;
        JsonArray assets = assetsElement.getAsJsonArray();
        for (JsonElement assetElement : assets) {
            if (!assetElement.isJsonObject()) continue;
            JsonObject asset = assetElement.getAsJsonObject();
            URI candidate = safeUri(normalizedString(asset.get("browser_download_url")), null);
            if (isJarDownloadUri(candidate)) {
                return new JarAsset(candidate, normalizedSha256Digest(asset.get("digest")));
            }
        }
        return null;
    }

        private static URI safeUri(String raw, URI fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

        private static String normalizedSha256Digest(JsonElement element) {
        return normalizedSha256Digest(normalizedString(element));
    }

        private static String normalizedSha256Digest(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("sha256:")) {
            value = value.substring("sha256:".length()).trim();
        }
        if (!SHA256_DIGEST_PATTERN.matcher(value).matches()) return null;
        return value;
    }

        private static String normalizedVersionTag(JsonElement element) {
        String value = normalizedString(element);
        if (value == null) return null;
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1).trim();
        }
        if (!VERSION_TAG_PATTERN.matcher(value).matches()) return null;
        return value;
    }

        private static String normalizedString(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        if (!element.getAsJsonPrimitive().isString()) return null;
        try {
            String value = element.getAsString();
            return value == null ? null : value.trim();
        } catch (ClassCastException | IllegalStateException e) {
            return null;
        }
    }

    static int compareSemver(String a, String b) {
        ParsedVersion av = parseVersion(a);
        ParsedVersion bv = parseVersion(b);
        int[] ap = av.numeric();
        int[] bp = bv.numeric();
        int len = Math.max(ap.length, bp.length);
        for (int i = 0; i < len; i++) {
            int ai = i < ap.length ? ap[i] : 0;
            int bi = i < bp.length ? bp[i] : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        boolean aPrerelease = !av.prerelease().isBlank();
        boolean bPrerelease = !bv.prerelease().isBlank();
        if (!aPrerelease && !bPrerelease) return 0;
        if (!aPrerelease) return 1;
        if (!bPrerelease) return -1;
        return comparePrerelease(av.prerelease(), bv.prerelease());
    }

    private static ParsedVersion parseVersion(String v) {
        if (v == null) return new ParsedVersion(new int[]{0, 0, 0}, "");
        String stripped = v.trim();
        if (stripped.startsWith("v") || stripped.startsWith("V")) {
            stripped = stripped.substring(1);
        }
        int build = stripped.indexOf('+');
        if (build >= 0) stripped = stripped.substring(0, build);
        String prerelease = "";
        int prereleaseStart = stripped.indexOf('-');
        if (prereleaseStart >= 0) {
            prerelease = stripped.substring(prereleaseStart + 1);
            stripped = stripped.substring(0, prereleaseStart);
        }
        return new ParsedVersion(parseNumeric(stripped), prerelease == null ? "" : prerelease);
    }

    private static int[] parseNumeric(String v) {
        if (v == null) return new int[]{0, 0, 0};
        String stripped = v.trim();
        String[] parts = stripped.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { out[i] = 0; }
        }
        return out;
    }

    private static int comparePrerelease(String a, String b) {
        String[] ap = a.split("\\.", -1);
        String[] bp = b.split("\\.", -1);
        int len = Math.min(ap.length, bp.length);
        for (int i = 0; i < len; i++) {
            int cmp = comparePrereleaseIdentifier(ap[i], bp[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(ap.length, bp.length);
    }

    private static int comparePrereleaseIdentifier(String a, String b) {
        boolean aNumeric = isNumericIdentifier(a);
        boolean bNumeric = isNumericIdentifier(b);
        if (aNumeric && bNumeric) {
            return new BigInteger(a).compareTo(new BigInteger(b));
        }
        if (aNumeric) return -1;
        if (bNumeric) return 1;
        return a.compareTo(b);
    }

    private static boolean isNumericIdentifier(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
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
            mc.gui.getChat().addClientSystemMessage(prefix.append(body));
        });
    }
}
