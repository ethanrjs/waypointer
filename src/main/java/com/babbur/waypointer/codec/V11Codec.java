package com.babbur.waypointer.codec;

import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.zip.Deflater;

public final class V11Codec {

    static final int WIRE_VERSION = 11;
    static final int KIND_JSON = 0;
    static final int KIND_COMPACT = 1;
    static final int MAX_ROUTES = RouteLibraryMetadata.MAX_GROUPS;
    static final int MAX_TOTAL_WAYPOINTS = 50_000;

    public record Decoded(List<WaypointGroup> groups, String label, RouteLibraryMetadata metadata) {
        public Decoded {
            groups = List.copyOf(groups);
            label = label == null ? "" : label;
            metadata = metadata == null ? RouteLibraryMetadata.empty() : metadata;
        }
    }

    private V11Codec() {}

    public static boolean isPayload(String text) {
        return probe(text) != null;
    }

    private static V10Transport.CheckedFrame probe(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (!trimmed.startsWith(WaypointCodec.MAGIC)) return null;
        String transport = trimmed.substring(WaypointCodec.MAGIC.length());
        if (!V10Transport.looksLikeWireVersion(transport, WIRE_VERSION)) return null;
        try {
            V10Transport.CheckedFrame frame = V10Transport.probe(transport, WIRE_VERSION);
            return frame.contentKind() == KIND_JSON || frame.contentKind() == KIND_COMPACT ? frame : null;
        } catch (IOException | IllegalArgumentException notV11) {
            return null;
        }
    }

    public static Decoded decode(String text) {
        V10Transport.CheckedFrame frame = probe(text);
        if (frame == null) throw new IllegalArgumentException("not a v11 share");
        try {
            return switch (frame.contentKind()) {
                case KIND_JSON -> decodeJsonBody(frame.semantic());
                case KIND_COMPACT -> V11CompactCodec.decode(frame.semantic());
                default -> throw new IllegalArgumentException("unsupported v11 share kind " + frame.contentKind());
            };
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid v11 share: " + failure.getMessage(), failure);
        }
    }

    public static String encodeBest(List<WaypointGroup> groups, WaypointCodec.Options options,
                                    RouteLibraryMetadata metadata) {
        WaypointCodec.Options safe = options == null ? WaypointCodec.Options.FULL_FIDELITY : options;
        RouteLibraryMetadata library = metadata == null ? RouteLibraryMetadata.empty() : metadata;
        List<WaypointGroup> carried = new ArrayList<>(groups.size());
        for (WaypointGroup group : groups) carried.add(shareCopy(group));
        library.applyTo(carried);
        List<WaypointGroup> projected = project(carried, safe);
        String label = WaypointCodec.Options.sanitizeLabel(safe.label);
        List<RouteLibraryMetadata.FolderDefinition> folders = library.folders();

        String best = encodeV11(projected, label, folders);
        if (safe.hasAllExportFieldsOff() && folders.isEmpty()) {
            try {
                String v10 = RouteLibraryCodec.encode(groups, safe, library);
                if (v10.length() < best.length()
                        && v10RoundTrips(v10, fingerprint(projected, label, folders))) {
                    best = v10;
                }
            } catch (RuntimeException unsupported) {

            }
        }
        return best;
    }

    public static String encodeBestDungeon(Collection<WaypointGroup> routes) {
        return encodeV11(project(List.copyOf(routes), WaypointCodec.Options.FULL_FIDELITY), "", List.of());
    }

    static String encodeV11(List<WaypointGroup> groups, String label,
                            List<RouteLibraryMetadata.FolderDefinition> folders) {
        validate(groups);
        new RouteLibraryMetadata(List.of(), folders).validateForGroups(groups);
        try {
            byte[] compact = V11CompactCodec.encode(groups, label, folders);
            if (compact != null && fingerprint(groups, label, folders).equals(
                    fingerprint(V11CompactCodec.decode(compact)))) {
                return WaypointCodec.MAGIC + V10Transport.direct(compact).transport();
            }
        } catch (IOException | RuntimeException compactUnavailable) {

        }
        return WaypointCodec.MAGIC + frame(encodeJsonBody(groups, label, folders));
    }

    private static boolean v10RoundTrips(String v10, String expected) {
        try {
            WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(v10);
            return expected.equals(fingerprint(decoded.groups(), decoded.label(),
                    decoded.metadata().folders()));
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    static String frame(byte[] semantic) {
        try {
            V10Transport.Outbound best = V10Transport.direct(semantic);
            for (int strategy : new int[]{Deflater.DEFAULT_STRATEGY, Deflater.FILTERED}) {
                V10Transport.Outbound deflated = V10Transport.deflated(semantic, strategy);
                if (deflated.compareTo(best) < 0) best = deflated;
            }
            return best.transport();
        } catch (IOException failure) {
            throw new IllegalStateException("v11 framing failed", failure);
        }
    }

    static byte[] encodeJsonBody(List<WaypointGroup> groups, String label,
                                 List<RouteLibraryMetadata.FolderDefinition> folders) {
        byte[] json = toJson(groups, label, folders).toString().getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[json.length + 1];
        body[0] = (byte) (WIRE_VERSION | KIND_JSON << 4);
        System.arraycopy(json, 0, body, 1, json.length);
        return body;
    }

    static Decoded decodeJsonBody(byte[] semantic) throws IOException {
        try {
            String json = new String(semantic, 1, semantic.length - 1, StandardCharsets.UTF_8);
            return fromJson(BoundedJson.parse(json).getAsJsonObject());
        } catch (RuntimeException invalid) {
            throw new IOException("invalid v11 routes: " + invalid.getMessage(), invalid);
        }
    }

    static Decoded fromJson(JsonObject root) {
        List<WaypointGroup> groups = new ArrayList<>();
        for (JsonElement route : root.getAsJsonArray("routes")) {
            groups.add(Storage.routeFromShareJson(route.getAsJsonObject()));
        }
        validate(groups);
        List<RouteLibraryMetadata.FolderDefinition> folders = new ArrayList<>();
        if (root.has("folders")) {
            for (JsonElement element : root.getAsJsonArray("folders")) {
                JsonObject folder = element.getAsJsonObject();
                List<Integer> members = new ArrayList<>();
                for (JsonElement member : folder.getAsJsonArray("routes")) members.add(member.getAsInt());
                folders.add(new RouteLibraryMetadata.FolderDefinition(folder.get("name").getAsString(),
                        folder.get("color").getAsInt(), folder.has("collapsed")
                        && folder.get("collapsed").getAsBoolean(), members));
            }
        }
        String label = root.has("label") ? WaypointCodec.Options.sanitizeLabel(root.get("label").getAsString()) : "";
        RouteLibraryMetadata metadata = new RouteLibraryMetadata(List.of(), folders);
        metadata.validateForGroups(groups);
        return new Decoded(groups, label, metadata);
    }

    static JsonObject toJson(List<WaypointGroup> groups, String label,
                             List<RouteLibraryMetadata.FolderDefinition> folders) {
        JsonObject root = new JsonObject();
        if (label != null && !label.isEmpty()) root.addProperty("label", label);
        JsonArray routes = new JsonArray();
        for (WaypointGroup group : groups) routes.add(Storage.routeToShareJson(group));
        root.add("routes", routes);
        if (folders != null && !folders.isEmpty()) {
            JsonArray encoded = new JsonArray();
            for (RouteLibraryMetadata.FolderDefinition folder : folders) {
                JsonObject object = new JsonObject();
                object.addProperty("name", folder.name());
                object.addProperty("color", folder.color());
                if (folder.collapsed()) object.addProperty("collapsed", true);
                JsonArray members = new JsonArray();
                folder.memberOrdinals().forEach(members::add);
                object.add("routes", members);
                encoded.add(object);
            }
            root.add("folders", encoded);
        }
        return root;
    }

    static String fingerprint(List<WaypointGroup> groups, String label,
                              List<RouteLibraryMetadata.FolderDefinition> folders) {
        JsonObject root = toJson(groups, label, folders);
        for (JsonElement route : root.getAsJsonArray("routes")) {
            JsonObject object = route.getAsJsonObject();
            if (!object.has("paint")) object.remove("paintEnabled");
        }
        return root.toString();
    }

    static String fingerprint(Decoded decoded) {
        return fingerprint(decoded.groups(), decoded.label(), decoded.metadata().folders());
    }

    private static void validate(List<WaypointGroup> groups) {
        if (groups == null || groups.isEmpty()) throw new IllegalArgumentException("nothing to share");
        if (groups.size() > MAX_ROUTES) throw new IllegalArgumentException("too many routes to share");
        long total = 0;
        for (WaypointGroup group : groups) total += group.size();
        if (total > MAX_TOTAL_WAYPOINTS) throw new IllegalArgumentException("too many waypoints to share");
    }

    static WaypointGroup shareCopy(WaypointGroup original) {
        return Storage.routeFromShareJson(Storage.routeToShareJson(original));
    }

    static List<WaypointGroup> project(List<WaypointGroup> groups, WaypointCodec.Options options) {
        List<WaypointGroup> out = new ArrayList<>(groups.size());
        for (WaypointGroup source : groups) {
            WaypointGroup copy = shareCopy(source);
            JsonObject route = Storage.routeToShareJson(copy);
            boolean dungeon = copy.routeKind() == WaypointGroup.RouteKind.DUNGEON;
            if (dungeon) {
                route.addProperty("zone", com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry
                        .normalizeId(copy.zoneId()));
            } else if (!options.includeZone) {
                route.addProperty("zone", Zone.UNKNOWN.id());
            }
            if (options.hasAllExportFieldsOff()) route.addProperty("name", "");
            if (!options.includeGroupMeta) {
                route.addProperty("loadMode", WaypointGroup.LoadMode.SEQUENCE.name());
                route.addProperty("skipAheadEnabled", true);
                route.addProperty("defaultRadius", Waypoint.DEFAULT_REACH_RADIUS);
                route.remove("icon");
                route.remove("stylePresetId");
                route.remove("styleOverride");
            }
            if (!options.includeColors || !options.includeGroupMeta) {
                route.addProperty("gradientMode", WaypointGroup.GradientMode.MANUAL.name());
                route.addProperty("staticColor", WaypointCodec.DEFAULT_STATIC_COLOR);
                route.addProperty("gradientStartColor", WaypointCodec.DEFAULT_GRADIENT_START_COLOR);
                route.addProperty("gradientEndColor", WaypointCodec.DEFAULT_GRADIENT_END_COLOR);
                route.remove("paint");
                route.addProperty("paintEnabled", false);
            }
            if (!options.includeColors) route.remove("manualColors");
            JsonArray waypoints = route.getAsJsonArray("waypoints");
            for (int i = 0; i < waypoints.size(); i++) {
                JsonObject w = waypoints.get(i).getAsJsonObject();
                Waypoint original = copy.get(i);
                int flags = WaypointCodec.exportedWaypointFlags(original, options);
                if (flags == 0) w.remove("flags");
                else w.addProperty("flags", flags);
                if (!options.includeNames) w.remove("name");
                if (!options.includeColors) {
                    w.addProperty("color", Waypoint.DEFAULT_COLOR);
                    w.remove("styleOverride");
                }
                if (!options.includeRadii) w.remove("radius");
                if (!WaypointCodec.shouldExportPrecisePosition(original, options)) {
                    w.remove("preciseX");
                    w.remove("preciseY");
                    w.remove("preciseZ");
                }
            }
            out.add(Storage.routeFromShareJson(route));
        }
        return out;
    }
}
