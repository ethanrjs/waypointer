package dev.ethan.waypointer.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    @Test
    void parseReleaseInfoReadsStructuredGitHubReleaseJson() {
        String json = """
                {
                  "tag_name": "v1.8.0+26.1",
                  "html_url": "https://github.com/ethanrjs/waypointer/releases/tag/v1.8.0",
                  "assets": [
                    {
                      "name": "notes.txt",
                      "browser_download_url": "https://github.com/ethanrjs/waypointer/releases/download/v1.8.0/notes.txt"
                    },
                    {
                      "name": "waypointer-1.8.0.jar",
                      "browser_download_url": "https://github.com/ethanrjs/waypointer/releases/download/v1.8.0/waypointer-1.8.0.jar",
                      "digest": "sha256:2151b604e3429bff440b9fbc03eb3617bc2603cda96c95b9bb05277f9ddba255"
                    }
                  ]
                }
                """;

        UpdateChecker.ReleaseInfo info = UpdateChecker.parseReleaseInfo(json);

        assertEquals("1.8.0+26.1", info.latestVersion());
        assertEquals(URI.create("https://github.com/ethanrjs/waypointer/releases/tag/v1.8.0"),
                info.releasePageUri());
        assertEquals(URI.create("https://github.com/ethanrjs/waypointer/releases/download/v1.8.0/waypointer-1.8.0.jar"),
                info.downloadUri());
        assertEquals("2151b604e3429bff440b9fbc03eb3617bc2603cda96c95b9bb05277f9ddba255",
                info.downloadSha256());
    }

    @Test
    void parseReleaseInfoFallsBackWhenOptionalUrlsAreInvalid() {
        String json = """
                {
                  "tag_name": "V2.0.0",
                  "html_url": "not a url with spaces",
                  "assets": [
                    {
                      "browser_download_url": "https://github.com/ethanrjs/waypointer/releases/download/v2.0.0/source.zip"
                    },
                    {
                      "browser_download_url": "not a uri"
                    }
                  ]
                }
                """;

        UpdateChecker.ReleaseInfo info = UpdateChecker.parseReleaseInfo(json);

        assertEquals("2.0.0", info.latestVersion());
        assertEquals(URI.create("https://github.com/ethanrjs/waypointer/releases/latest"),
                info.releasePageUri());
        assertNull(info.downloadUri());
    }

    @Test
    void parseReleaseInfoUsesTopLevelTagInsteadOfNestedText() {
        String json = """
                {
                  "body": {
                    "tag_name": "v99.0.0"
                  },
                  "tag_name": "v1.8.0",
                  "html_url": "https://github.com/ethanrjs/waypointer/releases/tag/v1.8.0",
                  "assets": [
                    {
                      "browser_download_url": "ftp://example.test/waypointer-99.0.0.jar"
                    },
                    {
                      "browser_download_url": "https://github.com/ethanrjs/waypointer/releases/download/v1.8.0/waypointer-1.8.0.jar",
                      "digest": "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                    }
                  ]
                }
                """;

        UpdateChecker.ReleaseInfo info = UpdateChecker.parseReleaseInfo(json);

        assertEquals("1.8.0", info.latestVersion());
        assertEquals(URI.create("https://github.com/ethanrjs/waypointer/releases/download/v1.8.0/waypointer-1.8.0.jar"),
                info.downloadUri());
        assertEquals("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                info.downloadSha256());
    }

    @Test
    void parseReleaseInfoRejectsMissingTagAndMalformedJson() {
        assertNull(UpdateChecker.parseReleaseInfo("{\"assets\": []}"));
        assertNull(UpdateChecker.parseReleaseInfo("{\"tag_name\": 123}"));
        assertNull(UpdateChecker.parseReleaseInfo("{\"tag_name\": \"latest\"}"));
        assertNull(UpdateChecker.parseReleaseInfo("{this is not json"));
        assertNull(UpdateChecker.parseReleaseInfo(null));
    }

    @Test
    void jarDownloadUrisMustBeHttpsJarAssets() {
        assertTrue(UpdateChecker.isJarDownloadUri(URI.create(
                "https://github.com/ethanrjs/waypointer/releases/download/v1.8.0/waypointer-1.8.0.jar")));
        assertFalse(UpdateChecker.isJarDownloadUri(URI.create(
                "http://github.com/ethanrjs/waypointer/releases/download/v1.8.0/waypointer-1.8.0.jar")));
        assertFalse(UpdateChecker.isJarDownloadUri(URI.create(
                "https://example.com/ethanrjs/waypointer/releases/download/v1.8.0/waypointer-1.8.0.jar")));
        assertFalse(UpdateChecker.isJarDownloadUri(URI.create(
                "https://github.com/someone/waypointer/releases/download/v1.8.0/waypointer-1.8.0.jar")));
        assertFalse(UpdateChecker.isJarDownloadUri(URI.create(
                "https://github.com/ethanrjs/waypointer/releases/download/v1.8.0/waypointer-1.8.0-sources.jar")));
        assertFalse(UpdateChecker.isJarDownloadUri(URI.create(
                "https://github.com/ethanrjs/waypointer/releases/download/v1.8.0/source.zip")));
    }

    @Test
    void sha256DigestValidationAcceptsGitHubAssetDigestFormatOnly() {
        assertTrue(UpdateChecker.hasSha256Digest(
                "sha256:2151b604e3429bff440b9fbc03eb3617bc2603cda96c95b9bb05277f9ddba255"));
        assertTrue(UpdateChecker.hasSha256Digest(
                "2151b604e3429bff440b9fbc03eb3617bc2603cda96c95b9bb05277f9ddba255"));
        assertFalse(UpdateChecker.hasSha256Digest(null));
        assertFalse(UpdateChecker.hasSha256Digest("sha1:2151b604e3429bff440b9fbc03eb3617bc2603cda96c95b9bb05277f9ddba255"));
        assertFalse(UpdateChecker.hasSha256Digest("sha256:not-a-real-digest"));
    }

    @Test
    void compareSemverOrdersPrereleasesBeforeStableReleases() {
        assertTrue(UpdateChecker.compareSemver("1.8.0-beta", "1.8.0") < 0);
        assertTrue(UpdateChecker.compareSemver("1.8.0", "1.8.0-beta") > 0);
        assertTrue(UpdateChecker.compareSemver("1.8.0-beta.2", "1.8.0-beta.10") < 0);
        assertEquals(0, UpdateChecker.compareSemver("1.8.0+26.1", "1.8.0"));
    }

    @Test
    void downloadedJarVerifierAcceptsMatchingWaypointerJar(@TempDir Path tempDir) throws Exception {
        Path jar = writeJar(tempDir, """
                {
                  "schemaVersion": 1,
                  "id": "waypointer",
                  "version": "1.8.0+26.1"
                }
                """);

        assertNull(UpdateChecker.verifyDownloadedJar(jar, "1.8.0+26.1"));
    }

    @Test
    void downloadedJarVerifierRejectsWrongModIdOrVersion(@TempDir Path tempDir) throws Exception {
        Path wrongId = writeJar(tempDir, """
                {
                  "schemaVersion": 1,
                  "id": "other-mod",
                  "version": "1.8.0+26.1"
                }
                """);
        Path wrongVersion = writeJar(tempDir, "wrong-version.jar", """
                {
                  "schemaVersion": 1,
                  "id": "waypointer",
                  "version": "1.7.0"
                }
                """);

        assertEquals("Downloaded jar is not a Waypointer mod.",
                UpdateChecker.verifyDownloadedJar(wrongId, "1.8.0+26.1"));
        assertEquals("Downloaded jar version did not match release.",
                UpdateChecker.verifyDownloadedJar(wrongVersion, "1.8.0+26.1"));
    }

    @Test
    void downloadedJarVerifierRejectsMissingOrUnreadableMetadata(@TempDir Path tempDir) throws Exception {
        Path missingMetadata = tempDir.resolve("missing-metadata.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(missingMetadata))) {
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write("not metadata".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path notZip = tempDir.resolve("not-a-zip.jar");
        Files.writeString(notZip, "not a jar", StandardCharsets.UTF_8);

        assertEquals("Downloaded jar is missing fabric.mod.json.",
                UpdateChecker.verifyDownloadedJar(missingMetadata, "1.8.0+26.1"));
        assertEquals("Downloaded jar metadata could not be read.",
                UpdateChecker.verifyDownloadedJar(notZip, "1.8.0+26.1"));
    }

    private static Path writeJar(Path tempDir, String metadataJson) throws Exception {
        return writeJar(tempDir, "waypointer-test.jar", metadataJson);
    }

    private static Path writeJar(Path tempDir, String fileName, String metadataJson) throws Exception {
        Path jar = tempDir.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(metadataJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
}
