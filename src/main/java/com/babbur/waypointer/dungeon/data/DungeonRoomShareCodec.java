package com.babbur.waypointer.dungeon.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import com.babbur.waypointer.codec.AsciiStreamCodec;

/**
 * Native share format for authored dungeon-room routes.
 *
 * <p>Normal waypoint exports carry world coordinates. Dungeon secrets must stay
 * room-local so the same imported data can rotate correctly in every Catacombs
 * run, so they get a tiny dedicated wrapper around the existing room JSON.
 */
public final class DungeonRoomShareCodec {
    public static final String MAGIC = "WPD:";
    /** A body starting with this character is raw-DEFLATE plus chat-safe ASCII. */
    private static final char COMPACT_BODY_PREFIX = '.';

    private static final int MAX_TEXT_PAYLOAD_CHARS = 8 * 1024 * 1024;
    private static final int MAX_DECODED_JSON_CHARS = 8 * 1024 * 1024;
    private static final int MAX_ROOMS_PER_IMPORT = 512;
    private static final int MAX_WAYPOINTS_PER_ROOM = 512;
    private static final int MAX_TOTAL_WAYPOINTS = 50_000;
    private static final int MAX_HIGHLIGHTS_PER_WAYPOINT = 256;
    private static final int MAX_TOTAL_HIGHLIGHTS = 100_000;

    private DungeonRoomShareCodec() {}

    public static String encode(Collection<DungeonRoomDefinition> definitions) {
        List<DungeonRoomDefinition> safe = definitions == null ? List.of() : new ArrayList<>(definitions);
        validateDefinitions(safe);
        String json = DungeonRoomData.toJson(safe);
        return MAGIC + COMPACT_BODY_PREFIX + AsciiStreamCodec.encode(deflate(json));
    }

    public static boolean isPayload(String payload) {
        if (payload == null) return false;
        return stripMarkdownCodeFence(payload.trim()).startsWith(MAGIC);
    }

    public static Decoded decode(String payload) {
        if (payload == null) throw new IllegalArgumentException("null dungeon route payload");
        String trimmed = stripMarkdownCodeFence(payload.trim());
        if (trimmed.length() > MAX_TEXT_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("dungeon route payload is too large (max "
                    + MAX_TEXT_PAYLOAD_CHARS + " chars)");
        }
        if (!trimmed.startsWith(MAGIC)) {
            throw new IllegalArgumentException("unrecognized dungeon route payload");
        }

        String body = removeWhitespace(trimmed.substring(MAGIC.length()));
        String json = body.startsWith(String.valueOf(COMPACT_BODY_PREFIX))
                ? inflateCompact(body.substring(1))
                : gunzip(body);
        Map<String, DungeonRoomDefinition> parsed;
        try {
            parsed = DungeonRoomData.parseDefinitions(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("dungeon route payload contained malformed JSON", e);
        }

        List<DungeonRoomDefinition> definitions = new ArrayList<>(parsed.values());
        validateDefinitions(definitions);
        return new Decoded(List.copyOf(definitions), waypointCount(definitions));
    }

    public static int waypointCount(Collection<DungeonRoomDefinition> definitions) {
        int total = 0;
        if (definitions == null) return total;
        for (DungeonRoomDefinition definition : definitions) {
            if (definition != null) total += definition.waypoints().size();
        }
        return total;
    }

    private static void validateDefinitions(Collection<DungeonRoomDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("dungeon route payload contained no rooms");
        }
        if (definitions.size() > MAX_ROOMS_PER_IMPORT) {
            throw new IllegalArgumentException("dungeon route payload contains too many rooms ("
                    + definitions.size() + " > " + MAX_ROOMS_PER_IMPORT + ")");
        }

        int totalWaypoints = 0;
        int totalHighlights = 0;
        boolean hasRoute = false;
        for (DungeonRoomDefinition definition : definitions) {
            if (definition == null) {
                throw new IllegalArgumentException("dungeon route payload contained a null room");
            }
            if (definition.id().isBlank()) {
                throw new IllegalArgumentException("dungeon route payload contained a room with no id");
            }
            int waypoints = definition.waypoints().size();
            if (waypoints > MAX_WAYPOINTS_PER_ROOM) {
                throw new IllegalArgumentException("dungeon room \"" + definition.displayName()
                        + "\" has too many waypoints (" + waypoints
                        + " > " + MAX_WAYPOINTS_PER_ROOM + ")");
            }
            totalWaypoints += waypoints;
            if (totalWaypoints > MAX_TOTAL_WAYPOINTS) {
                throw new IllegalArgumentException("dungeon route payload contains too many waypoints ("
                        + totalWaypoints + " > " + MAX_TOTAL_WAYPOINTS + ")");
            }
            if (waypoints > 0) hasRoute = true;

            for (var waypoint : definition.waypoints()) {
                int highlights = waypoint.highlights().size();
                if (highlights > MAX_HIGHLIGHTS_PER_WAYPOINT) {
                    throw new IllegalArgumentException("dungeon waypoint \"" + waypoint.id()
                            + "\" has too many highlights (" + highlights
                            + " > " + MAX_HIGHLIGHTS_PER_WAYPOINT + ")");
                }
                totalHighlights += highlights;
                if (totalHighlights > MAX_TOTAL_HIGHLIGHTS) {
                    throw new IllegalArgumentException("dungeon route payload contains too many highlights ("
                            + totalHighlights + " > " + MAX_TOTAL_HIGHLIGHTS + ")");
                }
            }
        }
        if (!hasRoute) {
            throw new IllegalArgumentException("dungeon route payload contained no secret routes");
        }
    }

    /**
     * The compact form avoids Base64 padding and uses the same printable ASCII
     * alphabet as route shares. The sentinel keeps existing WPD Base64+GZIP
     * payloads readable without changing their wire representation.
     */
    private static byte[] deflate(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(out, deflater)) {
            compressed.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("dungeon route export failed", e);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static String gunzip(String body) {
        byte[] compressed = decodeBase64Bytes(body);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_DECODED_JSON_CHARS) {
                    throw new IllegalArgumentException("decoded dungeon route JSON is too large (max "
                            + MAX_DECODED_JSON_CHARS + " bytes)");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("dungeon route payload failed to decode", e);
        }
    }

    private static String inflateCompact(String body) {
        byte[] compressed;
        try {
            compressed = AsciiStreamCodec.decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("dungeon route payload failed to decode", e);
        }
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_DECODED_JSON_CHARS) {
                    throw new IllegalArgumentException("decoded dungeon route JSON is too large (max "
                            + MAX_DECODED_JSON_CHARS + " bytes)");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("dungeon route payload failed to decode", e);
        }
    }

    private static byte[] decodeBase64Bytes(String s) {
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return Base64.getUrlDecoder().decode(s);
        }
    }

    private static String removeWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) out.append(c);
        }
        return out.toString();
    }

    private static String stripMarkdownCodeFence(String text) {
        if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) {
            return text;
        }
        int bodyStart = 3;
        int newline = text.indexOf('\n', bodyStart);
        if (newline >= 0) bodyStart = newline + 1;

        String body = text.substring(bodyStart, text.length() - 3).strip();
        return body.isEmpty() ? text : body;
    }

    public record Decoded(List<DungeonRoomDefinition> definitions, int waypointCount) {}
}
