package com.babbur.waypointer.i18n;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TranslationCatalogValidator {
    private static final String ENGLISH = "/assets/waypointer/lang/en_us.json";
    private static final Pattern FORMAT_TOKEN = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%])");
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_VALUE_LENGTH = 16_384;

    private final Map<String, String> english;

    TranslationCatalogValidator() throws IOException {
        try (var stream = TranslationCatalogValidator.class.getResourceAsStream(ENGLISH)) {
            if (stream == null) throw new IOException("Bundled English catalog is missing");
            english = parse(stream.readAllBytes(), "en_us");
        }
    }

    void validate(byte[] bytes, RemoteLocaleManifest.Entry entry, String locale) throws IOException {
        if (bytes.length != entry.bytes()) throw new IOException("Locale byte count does not match the manifest");
        byte[] actual = sha256(bytes);
        byte[] expected = java.util.HexFormat.of().parseHex(entry.sha256());
        if (!MessageDigest.isEqual(actual, expected)) throw new IOException("Locale digest does not match the manifest");

        Map<String, String> translated = parse(bytes, locale);
        if (translated.isEmpty()) throw new IOException("Locale catalog is empty");
        for (Map.Entry<String, String> value : translated.entrySet()) {
            String englishValue = english.get(value.getKey());
            if (englishValue == null) throw new IOException("Locale contains an unknown key: " + value.getKey());
            if (!placeholderSignature(englishValue).equals(placeholderSignature(value.getValue()))) {
                throw new IOException("Locale changes placeholders for key: " + value.getKey());
            }
        }
    }

    private static Map<String, String> parse(byte[] bytes, String locale) throws IOException {
        String text = decode(bytes);
        Map<String, String> entries = new LinkedHashMap<>();
        try (JsonReader json = new JsonReader(new StringReader(text))) {
            json.setStrictness(Strictness.STRICT);
            require(json.peek() == JsonToken.BEGIN_OBJECT, locale + " must contain one JSON object");
            json.beginObject();
            while (json.hasNext()) {
                String key = json.nextName();
                require(key.length() <= MAX_KEY_LENGTH, locale + " contains an oversized key");
                require(!entries.containsKey(key), locale + " contains a duplicate key: " + key);
                require(json.peek() == JsonToken.STRING, locale + " contains a non-string value: " + key);
                String value = json.nextString();
                require(!value.isBlank(), locale + " contains a blank value: " + key);
                require(value.length() <= MAX_VALUE_LENGTH, locale + " contains an oversized value: " + key);
                require(value.chars().noneMatch(character -> character == 0), locale + " contains a null character");
                entries.put(key, value);
            }
            json.endObject();
            require(json.peek() == JsonToken.END_DOCUMENT, locale + " contains trailing JSON content");
        } catch (IllegalStateException failure) {
            throw new IOException(locale + " is not a valid translation catalog", failure);
        }
        return Map.copyOf(entries);
    }

    private static String decode(byte[] bytes) throws IOException {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            throw new IOException("Locale contains a UTF-8 byte-order mark");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("Locale is not valid UTF-8", failure);
        }
    }

    private static List<Integer> placeholderSignature(String value) throws IOException {
        List<Integer> arguments = new ArrayList<>();
        Matcher matcher = FORMAT_TOKEN.matcher(value);
        int nextImplicitIndex = 1;
        while (matcher.find()) {
            String conversion = matcher.group(2);
            if ("%".equals(conversion)) continue;
            if (!"s".equals(conversion)) throw new IOException("Locale uses an unsupported placeholder");
            int index = matcher.group(1) == null ? nextImplicitIndex++ : Integer.parseInt(matcher.group(1));
            if (index <= 0) throw new IOException("Locale uses a zero-indexed placeholder");
            arguments.add(index);
        }
        Collections.sort(arguments);
        return List.copyOf(arguments);
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
