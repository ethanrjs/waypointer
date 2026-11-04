package com.babbur.waypointer.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

final class ModrinthVersionIndex {

    static final String PROJECT_ID = "VsST7Yka";
    static final long MAX_JAR_BYTES = 32L * 1024 * 1024;
    private static final String CDN_HOST = "cdn.modrinth.com";
    private static final Pattern SHA512 = Pattern.compile("^[0-9a-f]{128}$");
    private static final int MAX_CHANGELOG_LINES = 40;

    private ModrinthVersionIndex() {
    }

    static Optional<UpdateRelease> newest(String json, String currentVersion, String minecraftVersion) {
        Optional<StableVersion> current = StableVersion.parse(currentVersion);
        if (current.isEmpty()) return Optional.empty();

        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) throw new IllegalArgumentException("Modrinth version list is not an array");

        StableVersion bestVersion = null;
        UpdateRelease best = null;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject version = element.getAsJsonObject();
            if (!PROJECT_ID.equals(string(version, "project_id"))) continue;
            if (!"release".equals(string(version, "version_type"))) continue;
            if (!contains(version, "loaders", "fabric")) continue;
            if (!contains(version, "game_versions", minecraftVersion)) continue;
            Optional<StableVersion> parsed = StableVersion.parse(string(version, "version_number"));
            if (parsed.isEmpty() || parsed.get().compareTo(current.get()) <= 0) continue;
            if (bestVersion != null && parsed.get().compareTo(bestVersion) <= 0) continue;

            UpdateRelease release = primaryJar(version, parsed.get().toString());
            if (release == null) continue;
            bestVersion = parsed.get();
            best = release;
        }
        return Optional.ofNullable(best);
    }

    static boolean expectedFileName(String fileName, String version) {
        return fileName != null && fileName.matches(
                "waypointer-" + Pattern.quote(version) + "-mc[0-9][0-9.]*\\.jar");
    }

    static boolean expectedDownloadUrl(URI url) {
        return url != null
                && "https".equals(url.getScheme())
                && CDN_HOST.equals(url.getHost())
                && url.getPort() == -1
                && url.getRawQuery() == null
                && url.getRawPath() != null
                && url.getRawPath().startsWith("/data/" + PROJECT_ID + "/versions/")
                && !url.getRawPath().contains("..");
    }

    private static UpdateRelease primaryJar(JsonObject version, String versionNumber) {
        JsonArray files = array(version, "files");
        if (files == null) return null;
        for (JsonElement element : files) {
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            if (!bool(file, "primary")) continue;
            String fileName = string(file, "filename");
            if (!expectedFileName(fileName, versionNumber)) return null;
            URI url;
            try {
                url = URI.create(string(file, "url"));
            } catch (RuntimeException invalid) {
                return null;
            }
            if (!expectedDownloadUrl(url) || !url.getRawPath().endsWith("/" + fileName)) return null;
            long size = file.has("size") ? file.get("size").getAsLong() : -1;
            if (size <= 0 || size > MAX_JAR_BYTES) return null;
            JsonObject hashes = file.has("hashes") && file.get("hashes").isJsonObject()
                    ? file.getAsJsonObject("hashes") : null;
            String sha512 = hashes == null ? null : string(hashes, "sha512");
            if (sha512 == null) return null;
            sha512 = sha512.toLowerCase(Locale.ROOT);
            if (!SHA512.matcher(sha512).matches()) return null;
            return new UpdateRelease(versionNumber, fileName, url, size, sha512,
                    changelogLines(string(version, "changelog")));
        }
        return null;
    }

    static List<String> changelogLines(String markdown) {
        List<String> out = new ArrayList<>();
        if (markdown == null) return out;
        for (String raw : markdown.replace("\r\n", "\n").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                line = "#" + line.replaceFirst("^#+\\s*", "");
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                line = "- " + line.substring(2).strip();
            }
            line = line.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")
                    .replace("**", "").replace("__", "").replace("`", "");
            line = line.replaceAll("\\p{Cntrl}", "");
            out.add(line);
            if (out.size() == MAX_CHANGELOG_LINES) break;
        }
        return List.copyOf(out);
    }

    private static boolean contains(JsonObject object, String key, String wanted) {
        JsonArray values = array(object, key);
        if (values == null || wanted == null) return false;
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && wanted.equals(value.getAsString())) return true;
        }
        return false;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }
}
