package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;

import java.util.Collection;
import java.util.List;

/**
 * One entry point for every native Waypointer share payload.
 *
 * <p>The feature-specific codecs retain ownership of their wire formats. This
 * dispatcher only identifies the payload before decoding it, so callers such
 * as {@code /wp import} do not need separate paths for normal routes, settings,
 * and room-local dungeon routes. Unprefixed third-party route payloads still
 * use {@link WaypointImporter}; unprefixed dungeon JSON is tried only after
 * that route import fails.
 */
public final class UniversalShareCodec {

    public enum Type { WAYPOINTS, CONFIG, DUNGEON }

    public sealed interface Decoded permits Waypoints, Configuration, DungeonRoutes {
        Type type();
    }

    public record Waypoints(WaypointImporter.ImportResult result) implements Decoded {
        @Override
        public Type type() {
            return Type.WAYPOINTS;
        }
    }

    public record Configuration(WaypointerConfig config) implements Decoded {
        @Override
        public Type type() {
            return Type.CONFIG;
        }
    }

    public record DungeonRoutes(DungeonRouteImporter.Result result) implements Decoded {
        @Override
        public Type type() {
            return Type.DUNGEON;
        }
    }

    private UniversalShareCodec() {}

    public static String encodeWaypoints(List<com.babbur.waypointer.core.WaypointGroup> groups,
                                         WaypointCodec.Options options) {
        return WaypointCodec.encode(groups, options);
    }

    public static String encodeConfig(WaypointerConfig config) {
        return WaypointerConfigCodec.encode(config);
    }

    public static String encodeDungeon(Collection<DungeonRoomDefinition> definitions) {
        return DungeonRoomShareCodec.encode(definitions);
    }

    public static Decoded decode(String payload) {
        String normalized = stripMarkdownCodeFence(payload);
        if (normalized.startsWith(WaypointerConfigCodec.MAGIC)) {
            return new Configuration(WaypointerConfigCodec.decode(normalized));
        }
        if (DungeonRoomShareCodec.isPayload(normalized)) {
            return new DungeonRoutes(DungeonRouteImporter.parse(normalized));
        }

        try {
            return new Waypoints(WaypointImporter.importAny(normalized));
        } catch (IllegalArgumentException waypointFailure) {
            try {
                return new DungeonRoutes(DungeonRouteImporter.parse(normalized));
            } catch (IllegalArgumentException dungeonFailure) {
                waypointFailure.addSuppressed(dungeonFailure);
                throw waypointFailure;
            }
        }
    }

    private static String stripMarkdownCodeFence(String payload) {
        if (payload == null) throw new IllegalArgumentException("null payload");
        String trimmed = payload.trim();
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```") || trimmed.length() < 6) {
            return trimmed;
        }
        int bodyStart = 3;
        int newline = trimmed.indexOf('\n', bodyStart);
        if (newline >= 0) bodyStart = newline + 1;

        String body = trimmed.substring(bodyStart, trimmed.length() - 3).strip();
        return body.isEmpty() ? trimmed : body;
    }
}
