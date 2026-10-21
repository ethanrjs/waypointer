package com.babbur.waypointer.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneClickUpdateTest {

    private static final String HASH = "a".repeat(128);

    @Test
    void picksNewestStableReleaseForThisMinecraftVersion() {
        String json = "[" + version("1.12.2", "release", "26.2", "waypointer-1.12.2-mc26.2.jar", null)
                + "," + version("1.13.0", "beta", "26.2", "waypointer-1.13.0-mc26.2.jar", null)
                + "," + version("1.14.0", "release", "26.1.2", "waypointer-1.14.0-mc26.1.2.jar", null)
                + "," + version("1.12.0", "release", "26.2", "waypointer-1.12.0-mc26.2.jar", null) + "]";

        UpdateRelease release = ModrinthVersionIndex.newest(json, "1.12.1", "26.2").orElseThrow();

        assertEquals("1.12.2", release.version());
        assertEquals("waypointer-1.12.2-mc26.2.jar", release.fileName());
        assertEquals(HASH, release.sha512());
    }

    @Test
    void ignoresFilesThatAreNotOurUploads() {
        String otherHost = version("1.12.2", "release", "26.2", "waypointer-1.12.2-mc26.2.jar",
                "https://evil.example/data/VsST7Yka/versions/x/waypointer-1.12.2-mc26.2.jar");
        String otherProject = version("1.12.2", "release", "26.2", "waypointer-1.12.2-mc26.2.jar",
                "https://cdn.modrinth.com/data/AAAAAAAA/versions/x/waypointer-1.12.2-mc26.2.jar");
        String plainHttp = version("1.12.2", "release", "26.2", "waypointer-1.12.2-mc26.2.jar",
                "http://cdn.modrinth.com/data/VsST7Yka/versions/x/waypointer-1.12.2-mc26.2.jar");
        String otherName = version("1.12.2", "release", "26.2", "skyblocker-1.12.2.jar", null);

        for (String entry : List.of(otherHost, otherProject, plainHttp, otherName)) {
            assertTrue(ModrinthVersionIndex.newest("[" + entry + "]", "1.12.1", "26.2").isEmpty(), entry);
        }
    }

    @Test
    void neverOffersADowngradeOrAnUnknownInstall() {
        String json = "[" + version("1.12.1", "release", "26.2", "waypointer-1.12.1-mc26.2.jar", null) + "]";

        assertTrue(ModrinthVersionIndex.newest(json, "1.12.1", "26.2").isEmpty());
        assertTrue(ModrinthVersionIndex.newest(json, "1.12.1-dev", "26.2").isEmpty());
    }

    @Test
    void changelogKeepsHeadingsAndBulletsAsPlainText() {
        assertEquals(List.of("#Added", "- Toggle keybind", "- See docs"),
                ModrinthVersionIndex.changelogLines("## Added\n\n- **Toggle** keybind\n* See [docs](https://x)"));
    }

    @Test
    void readsGitHubDigestOnlyForTheMatchingStableAsset() {
        String release = """
                {"tag_name":"v1.12.2","draft":false,"prerelease":false,"assets":[
                  {"name":"waypointer-1.12.2-mc26.2.jar","size":10,"digest":"sha256:%s"}]}
                """.formatted("b".repeat(64));

        assertEquals(Optional.of("b".repeat(64)),
                GitHubReleaseIndex.assetSha256(release, "1.12.2", "waypointer-1.12.2-mc26.2.jar", 10));
        assertTrue(GitHubReleaseIndex.assetSha256(release, "1.12.2", "waypointer-1.12.2-mc26.2.jar", 11).isEmpty());
        assertTrue(GitHubReleaseIndex.assetSha256(release, "1.12.3", "waypointer-1.12.2-mc26.2.jar", 10).isEmpty());
        assertTrue(GitHubReleaseIndex.assetSha256(release.replace("\"prerelease\":false", "\"prerelease\":true"),
                "1.12.2", "waypointer-1.12.2-mc26.2.jar", 10).isEmpty());
    }

    @Test
    void acceptsOnlyAJarCarryingOurIdAndTheExpectedVersion(@TempDir Path dir) throws IOException {
        Path good = modJar(dir.resolve("good.jar"), "waypointer", "1.12.2");
        Path wrongId = modJar(dir.resolve("other.jar"), "othermod", "1.12.2");

        WaypointerUpdates.verifyModMetadata(good, "1.12.2");
        assertThrows(IOException.class, () -> WaypointerUpdates.verifyModMetadata(good, "1.12.3"));
        assertThrows(IOException.class, () -> WaypointerUpdates.verifyModMetadata(wrongId, "1.12.2"));
    }

    @Test
    void installerSwapsJarsAndKeepsTheOldOneAsBackup(@TempDir Path root) throws IOException {
        Path mods = Files.createDirectories(root.resolve("mods"));
        Path staging = Files.createDirectories(root.resolve("update"));
        Path current = Files.writeString(mods.resolve("waypointer-1.12.1-mc26.2.jar"), "old");
        Path staged = Files.writeString(staging.resolve("waypointer-1.12.2-mc26.2.jar"), "new");
        new StagedUpdate(staging, "1.12.2", staged, UpdateInstaller.sha512(staged), current).save();

        assertTrue(UpdateInstaller.install(staging, staged, UpdateInstaller.sha512(staged), current, 1));

        assertFalse(Files.exists(current));
        assertEquals("new", Files.readString(mods.resolve("waypointer-1.12.2-mc26.2.jar")));
        assertEquals("old", Files.readString(staging.resolve("backup").resolve(current.getFileName())));
        assertFalse(Files.exists(staging.resolve(UpdateInstaller.PENDING_FILE)));
        try (var listing = Files.list(mods)) {
            assertEquals(1, listing.count());
        }
    }

    @Test
    void installerDiscardsAStagedJarThatChangedAfterVerification(@TempDir Path root) throws IOException {
        Path mods = Files.createDirectories(root.resolve("mods"));
        Path staging = Files.createDirectories(root.resolve("update"));
        Path current = Files.writeString(mods.resolve("waypointer-1.12.1-mc26.2.jar"), "old");
        Path staged = Files.writeString(staging.resolve("waypointer-1.12.2-mc26.2.jar"), "new");
        String verified = UpdateInstaller.sha512(staged);
        Files.writeString(staged, "tampered");

        assertFalse(UpdateInstaller.install(staging, staged, verified, current, 1));

        assertEquals("old", Files.readString(current));
        assertFalse(Files.exists(staged));
        try (var listing = Files.list(mods)) {
            assertEquals(1, listing.count());
        }
    }

    @Test
    void stagedUpdateRoundTripsAndIsCleanedOnceInstalled(@TempDir Path root) throws IOException {
        Path staging = Files.createDirectories(root.resolve("update"));
        Path current = Files.writeString(root.resolve("waypointer-1.12.1-mc26.2.jar"), "old");
        Path staged = Files.writeString(staging.resolve("waypointer-1.12.2-mc26.2.jar"), "new");
        new StagedUpdate(staging, "1.12.2", staged, HASH, current).save();

        StagedUpdate loaded = StagedUpdate.load(staging).orElseThrow();
        assertEquals("1.12.2", loaded.version);
        assertEquals(staged, loaded.jar);
        assertEquals(current.toAbsolutePath(), loaded.currentJar);

        assertTrue(StagedUpdate.resumeOrClean(staging, "1.12.2").isEmpty());
        assertFalse(Files.exists(staging));
    }

    private static String version(String number, String type, String gameVersion, String fileName, String url) {
        String fileUrl = url != null ? url
                : "https://cdn.modrinth.com/data/VsST7Yka/versions/abc/" + fileName;
        return """
                {"project_id":"VsST7Yka","version_number":"%s","version_type":"%s",
                 "loaders":["fabric"],"game_versions":["%s"],"changelog":"## Fixed\\n- Things",
                 "files":[{"primary":true,"filename":"%s","url":"%s","size":1000,
                           "hashes":{"sha512":"%s","sha1":"x"}}]}
                """.formatted(number, type, gameVersion, fileName, fileUrl, HASH);
    }

    private static Path modJar(Path path, String id, String version) throws IOException {
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
