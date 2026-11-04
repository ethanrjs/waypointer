package com.babbur.waypointer.update;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.util.BoundedBody;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class WaypointerUpdates {

    public enum Phase { READY, UNAVAILABLE, DOWNLOADING, STAGED, FAILED }

    public record Snapshot(Phase phase, String installedVersion, String latestVersion,
                           Instant latestPublishedAt, int versionsBehind, List<String> changelog,
                           float progress, String failure) {
    }

    static final URI MODRINTH_API = URI.create("https://api.modrinth.com/v2/project/"
            + ModrinthVersionIndex.PROJECT_ID + "/version");
    static final String GITHUB_RELEASE_BY_TAG =
            "https://api.github.com/repos/ethanrjs/waypointer/releases/tags/v";
    private static final int MAX_LISTING_BYTES = 4 * 1024 * 1024;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Waypointer updater");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile String installedVersion = "";
    private static volatile AvailableUpdate available;
    private static volatile UpdateRelease release;
    private static volatile boolean releaseLookupDone;
    private static volatile StagedUpdate staged;
    private static volatile Phase phase = Phase.READY;
    private static volatile float progress;
    private static volatile String failure;
    private static volatile boolean lookupStarted;
    private static volatile boolean preview;

    private WaypointerUpdates() {
    }

    static void install(String currentVersion) {
        installedVersion = currentVersion;
        try {
            staged = StagedUpdate.resumeOrClean(stagingDir(), currentVersion).orElse(null);
            if (staged != null) phase = Phase.STAGED;
        } catch (RuntimeException unavailable) {
            Waypointer.LOGGER.debug("Could not check for a staged Waypointer update", unavailable);
        }
    }

    static void offer(AvailableUpdate update) {
        available = update;
        if (lookupStarted) return;
        lookupStarted = true;
        CompletableFuture.supplyAsync(WaypointerUpdates::lookupRelease, WORKER).whenComplete((found, error) -> {
            if (error != null) {
                Waypointer.LOGGER.debug("Could not read Waypointer releases from Modrinth", error);
                lookupStarted = false;
            } else {
                release = found.orElse(null);
            }
            releaseLookupDone = error == null;
        });
    }

    public static Optional<Snapshot> snapshot() {
        AvailableUpdate update = available;
        StagedUpdate pending = staged;
        if (update == null && pending == null) return Optional.empty();
        UpdateRelease target = release;
        String latest = pending != null ? pending.version
                : target != null ? target.version() : update.latestVersion();
        Phase current = phase;
        if (current == Phase.READY && (target == null || !preview && currentJar() == null)) {
            current = releaseLookupDone || target != null ? Phase.UNAVAILABLE : Phase.READY;
        }
        return Optional.of(new Snapshot(current, installedVersion, latest,
                update == null ? null : update.latestPublishedAt(),
                update == null ? 0 : update.versionsBehind(),
                target == null ? List.of() : target.changelog(),
                progress, failure));
    }

    public static URI releasePage() {
        return UpdateNotificationMessage.MODRINTH_VERSIONS;
    }

    public static boolean canInstall() {
        return phase != Phase.DOWNLOADING && phase != Phase.STAGED
                && release != null && (preview || currentJar() != null);
    }

    public static void preview(String installed, String latest, List<String> changelog, Phase shown) {
        preview = true;
        installedVersion = installed;
        available = new AvailableUpdate(installed, Instant.EPOCH, latest, Instant.EPOCH, 1);
        release = new UpdateRelease(latest, "waypointer-" + latest + ".jar",
                URI.create("https://cdn.modrinth.com/"), 1, "", List.copyOf(changelog));
        releaseLookupDone = true;
        lookupStarted = true;
        phase = shown;
        progress = 0.42f;
    }

    public static void startInstall() {
        UpdateRelease target = release;
        Path current = currentJar();
        if (preview || !canInstall() || target == null || current == null) return;
        phase = Phase.DOWNLOADING;
        progress = 0;
        failure = null;
        CompletableFuture.supplyAsync(() -> {
            try {
                return download(target, current);
            } catch (IOException | InterruptedException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException(error.getMessage(), error);
            }
        }, WORKER).whenComplete((result, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                Waypointer.LOGGER.warn("Waypointer update download failed", cause);
                failure = cause.getMessage();
                phase = Phase.FAILED;
                return;
            }
            staged = result;
            result.armOnExit();
            phase = Phase.STAGED;
        });
    }

    public static void cancelInstall() {
        StagedUpdate pending = staged;
        if (pending == null) return;
        pending.discard();
        staged = null;
        phase = Phase.READY;
    }

    private static Optional<UpdateRelease> lookupRelease() {
        try {
            String gameVersion = SharedConstants.getCurrentVersion().name();
            URI uri = URI.create(MODRINTH_API + "?loaders=" + encode("[\"fabric\"]")
                    + "&game_versions=" + encode("[\"" + gameVersion + "\"]"));
            String body = getText(uri);
            return ModrinthVersionIndex.newest(body, installedVersion, gameVersion);
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static StagedUpdate download(UpdateRelease target, Path current)
            throws IOException, InterruptedException {
        String expectedSha256 = GitHubReleaseIndex.assetSha256(
                        getText(URI.create(GITHUB_RELEASE_BY_TAG + target.version())),
                        target.version(), target.fileName(), target.size())
                .orElseThrow(() -> new IOException("GitHub has no matching release file"));

        Path dir = stagingDir();
        Files.createDirectories(dir);
        Path partial = dir.resolve("download.part");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(target.url())
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", userAgent())
                .GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        MessageDigest sha512 = digest("SHA-512");
        MessageDigest sha256 = digest("SHA-256");
        long written = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            if (response.statusCode() != 200) throw new IOException("Modrinth returned HTTP " + response.statusCode());
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                written += read;
                if (written > target.size()) throw new IOException("download is larger than expected");
                sha512.update(buffer, 0, read);
                sha256.update(buffer, 0, read);
                out.write(buffer, 0, read);
                progress = (float) written / target.size();
            }
        } catch (IOException error) {
            Files.deleteIfExists(partial);
            throw error;
        }
        try {
            if (written != target.size()) throw new IOException("download is incomplete");
            if (!HexFormat.of().formatHex(sha512.digest()).equals(target.sha512())) {
                throw new IOException("download does not match Modrinth's checksum");
            }
            if (!HexFormat.of().formatHex(sha256.digest()).equals(expectedSha256)) {
                throw new IOException("download does not match GitHub's checksum");
            }
            verifyModMetadata(partial, target.version());
            Path jar = dir.resolve(target.fileName());
            Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            StagedUpdate update = new StagedUpdate(dir, target.version(), jar, target.sha512(), current);
            update.save();
            return update;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    static void verifyModMetadata(Path jar, String expectedVersion) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null || entry.getSize() > 64 * 1024) throw new IOException("not a Waypointer jar");
            try (Reader reader = new java.io.InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                JsonObject metadata = root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
                if (!Waypointer.MOD_ID.equals(text(metadata, "id"))
                        || !expectedVersion.equals(text(metadata, "version"))) {
                    throw new IOException("jar is not Waypointer " + expectedVersion);
                }
            } catch (RuntimeException malformed) {
                throw new IOException("jar metadata is unreadable", malformed);
            }
        }
    }

    static Path currentJar() {
        try {
            return FabricLoader.getInstance().getModContainer(Waypointer.MOD_ID)
                    .map(container -> container.getOrigin())
                    .filter(origin -> origin.getKind() == ModOrigin.Kind.PATH && origin.getPaths().size() == 1)
                    .map(origin -> origin.getPaths().getFirst())
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .filter(path -> Files.isWritable(path.toAbsolutePath().getParent()))
                    .orElse(null);
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private static String getText(URI uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", userAgent())
                .GET();
        if ("api.github.com".equals(uri.getHost())) {
            request.header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
        }
        HttpResponse<byte[]> response = client.send(request.build(), BoundedBody.handler(
                MAX_LISTING_BYTES, () -> new IllegalStateException("response is too large")));
        if (response.statusCode() != 200) {
            throw new IOException(uri.getHost() + " returned HTTP " + response.statusCode());
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    static Path stagingDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(Waypointer.MOD_ID).resolve("update");
    }

    private static String userAgent() {
        return "ethanrjs/waypointer/" + installedVersion;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }
}
