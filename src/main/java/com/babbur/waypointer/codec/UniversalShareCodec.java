package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.config.WaypointerConfigCodec;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
import com.babbur.waypointer.dungeon.data.V10DungeonBodyCodec;

import java.io.IOException;
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
        try {
            return V10ConfigCodec.encode(config);
        } catch (IOException failure) {
            throw new IllegalStateException("v10 config encode failed", failure);
        }
    }

    public static String encodeDungeon(Collection<WaypointGroup> routes) {
        try {
            return V10DungeonCodec.encode(routes);
        } catch (IOException failure) {
            throw new IllegalStateException("v10 dungeon encode failed", failure);
        }
    }

    public static Decoded decode(String payload) {
        if (payload == null) throw new IllegalArgumentException("null payload");
        WaypointImporter.enforceTextPayloadLimit(payload);
        String normalized = WaypointImporter.stripMarkdownCodeFence(payload);
        if (normalized.startsWith(WaypointerConfigCodec.MAGIC)) {
            return new Configuration(WaypointerConfigCodec.decode(normalized));
        }
        V10Transport.CheckedFrame committedV10 = probeV10(normalized);
        if (committedV10 != null) {
            return decodeCommittedV10(normalized, committedV10);
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

    private static V10Transport.CheckedFrame probeV10(String normalized) {
        if (!normalized.startsWith(WaypointCodec.MAGIC)) return null;
        String transport = normalized.substring(WaypointCodec.MAGIC.length());
        if (!V10Transport.looksLikeV10(transport)) return null;
        try {
            return V10Transport.probe(transport);
        } catch (IOException | IllegalArgumentException uncommitted) {
            // About one legacy code in sixteen shares the version nibble; only
            // canonical text plus the header-bound checksum commits this dispatch.
            return null;
        }
    }

    private static Decoded decodeCommittedV10(String normalized,
                                              V10Transport.CheckedFrame frame) {
        try {
            return switch (frame.contentKind()) {
                case com.babbur.waypointer.config.V10ConfigBodyCodec.CONTENT_KIND ->
                        new Configuration(V10ConfigCodec.decode(frame));
                case V10DungeonBodyCodec.CONTENT_KIND -> {
                    V10DungeonBodyCodec.Decoded decoded = V10DungeonCodec.decode(frame);
                    yield new DungeonRoutes(new DungeonRouteImporter.Result(
                            decoded.routes(), decoded.waypointCount(), List.of(), 0,
                            DungeonRouteImporter.Format.WAYPOINTER));
                }
                case V10GeneralRouteCodec.CONTENT_KIND, 2, 5,
                     V10BareRoutePackCodec.CONTENT_KIND,
                     V10GeneralRouteCodec.LABELED_CONTENT_KIND ->
                        new Waypoints(WaypointImporter.importAny(normalized));
                default -> throw new IllegalArgumentException(
                        "unsupported committed v10 share kind " + frame.contentKind());
            };
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("share decode failed: committed v10 kind "
                    + frame.contentKind() + "=" + failure.getMessage(), failure);
        }
    }

}
