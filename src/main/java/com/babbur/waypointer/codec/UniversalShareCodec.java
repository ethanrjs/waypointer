package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;

import java.util.Collection;
import java.util.List;

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

    public static String encodeWaypoints(
            List<com.babbur.waypointer.core.WaypointGroup> groups,
            WaypointCodec.Options options,
            RouteLibraryMetadata metadata) {
        return RouteLibraryCodec.encode(groups, options, metadata);
    }

    public static String encodeConfig(WaypointerConfig config) {
        return WaypointerConfigCodec.encode(config);
    }

    public static String encodeDungeon(Collection<WaypointGroup> routes) {
        return DungeonRoomShareCodec.encode(routes);
    }

    public static Decoded decode(String payload) {
        if (payload == null) throw new IllegalArgumentException("null payload");
        WaypointImporter.enforceTextPayloadLimit(payload);
        String normalized = WaypointImporter.stripMarkdownCodeFence(payload);
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

}
