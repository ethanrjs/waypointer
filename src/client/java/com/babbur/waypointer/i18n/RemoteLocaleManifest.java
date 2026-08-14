package com.babbur.waypointer.i18n;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class RemoteLocaleManifest {
    static final String RESOURCE = "/assets/waypointer/i18n/remote-locales.json";
    static final String REPOSITORY = "ethanrjs/waypointer";
    static final String PATH_TEMPLATE = "translations/lang/{locale}.json";
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern LOCALE = Pattern.compile("[a-z0-9]+(?:_[a-z0-9]+)*");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    record Entry(String sha256, int bytes) {
        Entry {
            if (!SHA256.matcher(sha256).matches()) throw new IllegalArgumentException("Invalid locale digest");
            if (bytes <= 0 || bytes > RemoteLocaleDownloader.MAX_BYTES) {
                throw new IllegalArgumentException("Invalid locale byte count");
            }
        }
    }

    private final boolean enabled;
    private final String commit;
    private final Map<String, Entry> locales;

    private RemoteLocaleManifest(boolean enabled, String commit, Map<String, Entry> locales) {
        this.enabled = enabled;
        this.commit = commit;
        this.locales = Map.copyOf(locales);
    }

    static RemoteLocaleManifest load() throws IOException {
        try (InputStream stream = RemoteLocaleManifest.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Remote locale manifest is missing");
            try (JsonReader json = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                json.setStrictness(Strictness.STRICT);
                return read(json);
            }
        }
    }

    static RemoteLocaleManifest read(JsonReader json) throws IOException {
        require(json.peek() == JsonToken.BEGIN_OBJECT, "Manifest must be one JSON object");
        json.beginObject();
        Set<String> fields = new LinkedHashSet<>();
        Integer schema = null;
        Boolean enabled = null;
        String repository = null;
        String commit = null;
        String pathTemplate = null;
        Map<String, Entry> locales = null;
        while (json.hasNext()) {
            String name = json.nextName();
            require(fields.add(name), "Manifest contains duplicate field: " + name);
            switch (name) {
                case "schema" -> schema = json.nextInt();
                case "enabled" -> enabled = json.nextBoolean();
                case "repository" -> repository = json.nextString();
                case "commit" -> commit = json.nextString();
                case "pathTemplate" -> pathTemplate = json.nextString();
                case "locales" -> locales = readLocales(json);
                default -> throw new IOException("Manifest contains unknown field: " + name);
            }
        }
        json.endObject();
        require(json.peek() == JsonToken.END_DOCUMENT, "Manifest contains trailing content");
        require(Integer.valueOf(1).equals(schema), "Unsupported manifest schema");
        require(enabled != null, "Manifest enabled field is missing");
        require(REPOSITORY.equals(repository), "Unexpected manifest repository");
        require(PATH_TEMPLATE.equals(pathTemplate), "Unexpected manifest path template");
        require(commit != null && (commit.isEmpty() || COMMIT.matcher(commit).matches()), "Invalid manifest commit");
        require(locales != null, "Manifest locale map is missing");
        require(!enabled || (!commit.isEmpty() && !locales.isEmpty()), "Enabled manifest has no trusted catalogs");
        require(enabled || commit.isEmpty(), "Disabled manifest must not name a commit");
        return new RemoteLocaleManifest(enabled, commit, locales);
    }

    private static Map<String, Entry> readLocales(JsonReader json) throws IOException {
        require(json.peek() == JsonToken.BEGIN_OBJECT, "Manifest locales must be an object");
        json.beginObject();
        Map<String, Entry> result = new LinkedHashMap<>();
        while (json.hasNext()) {
            String locale = json.nextName();
            require(LOCALE.matcher(locale).matches() && !"en_us".equals(locale), "Unsafe locale name: " + locale);
            require(!result.containsKey(locale), "Duplicate locale: " + locale);
            json.beginObject();
            Set<String> fields = new LinkedHashSet<>();
            String sha256 = null;
            Integer bytes = null;
            while (json.hasNext()) {
                String field = json.nextName();
                require(fields.add(field), "Duplicate locale field: " + field);
                switch (field) {
                    case "sha256" -> sha256 = json.nextString();
                    case "bytes" -> bytes = json.nextInt();
                    default -> throw new IOException("Unknown locale field: " + field);
                }
            }
            json.endObject();
            require(sha256 != null && bytes != null, "Incomplete locale entry: " + locale);
            try {
                result.put(locale, new Entry(sha256, bytes));
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid locale entry: " + locale, failure);
            }
        }
        json.endObject();
        return result;
    }

    boolean enabled() {
        return enabled;
    }

    String commit() {
        return commit;
    }

    Entry entry(String locale) {
        return locales.get(locale);
    }

    Map<String, Entry> locales() {
        return locales;
    }

    URI uri(String locale) {
        if (!enabled || !LOCALE.matcher(locale).matches() || !locales.containsKey(locale)) {
            throw new IllegalArgumentException("Locale is not in the trusted manifest");
        }
        URI uri = URI.create("https://raw.githubusercontent.com/" + REPOSITORY + "/"
                + commit + "/translations/lang/" + locale + ".json");
        if (!"https".equals(uri.getScheme())
                || !"raw.githubusercontent.com".equals(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException("Unsafe locale download URI");
        }
        return uri;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
