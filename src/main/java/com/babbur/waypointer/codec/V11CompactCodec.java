package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class V11CompactCodec {

    static final String[] ROOMS = {
            "admin", "altar", "andesite", "archway", "arrow-trap", "atlas", "balcony", "banners",
            "basement", "beams", "big-red-flag", "black-flag", "blood", "blue-skulls", "boulder",
            "bridges", "buttons", "cage", "cages", "carpets", "cathedral", "catwalk", "cell", "chains",
            "chambers", "cobble-wall-pillar", "creeper-beams", "criss-cross", "crypt", "deathmite",
            "default", "diagonal", "dino-site", "dip", "dome", "doors", "double-diamond", "dragon",
            "drop", "dueces", "duncan", "end", "entrance", "fairy", "flags", "gold", "golden-oasis",
            "grand-library", "granite", "grass-ruin", "gravel", "hall", "hallway", "higher-blaze",
            "ice-fill", "ice-path", "jumping-skulls", "king-midas", "knight", "lava-pit",
            "lava-ravine", "layers", "leaves", "locked-away", "logs", "long-hall", "lots-of-floors",
            "lower-blaze", "mage", "market", "melon", "mines", "trinity", "mirror", "mossy",
            "multicolored", "mural", "museum", "mushroom", "new-trap", "old-trap", "overgrown",
            "overgrown-chains", "painting", "pedestal", "perch", "pipes", "pirate", "pit",
            "pressure-plates", "prison-cell", "purple-flags", "quad-lava", "quartz-knight", "quiz",
            "raccoon", "rail-track", "rails", "rare-carpets", "rare-overgrown", "rare-pillars",
            "red-blue", "red-green", "redstone-crypt", "redstone-key", "redstone-warrior", "ritual",
            "sand-dragon", "sarcophagus", "scaffolding", "shadow-assassin", "silver-sword", "skull",
            "slabs", "slime", "sloth", "small-stairs", "small-waterfall", "spider", "spikes",
            "staircase", "stairs", "steps", "stone-window", "supertall", "teleport-maze", "temple",
            "three-floors", "three-weirdos", "tic-tac-toe", "tombstone", "tomioka", "vinny-8-ball",
            "water", "water-board", "waterfall", "well", "withermancer", "wizard", "zodd"};

    private record Kind(long flags, int color, String radius, String style, boolean precise) {}

    private static final Object[][] BUILTIN_KINDS = {

            {0x00000073L, 0x8AA7BA, 0, ""},
            {0x00204810L, 0x6EE7B7, 1, ""},
            {0x00200810L, 0x4FE05A, 1, ""},
            {0x00001410L, 0x2EE0FF, 3, "Chest"},
            {0x00002210L, 0x9C2EFF, 2, ""},
            {0x00002310L, 0x9C2EFF, 2, ""},
            {0x00002200L, 0x9C2EFF, 2, ""},
            {0x00002300L, 0x9C2EFF, 2, ""},
            {0x00000210L, 0x2EFF2E, 4, "Exit"},
            {0x00041210L, 0xFFD800, 3, "Item"},
            {0x00000000L, 0x4FE05A, 6, ""},
            {0x00000800L, 0x4FE05A, 1, ""},
            {0x00008410L, 0xFFB300, 5, ""},
            {0x00004800L, 0x6EE7B7, 1, ""},
            {0x00081210L, 0x9C5A2E, 3, "Bat"},
            {0x00008400L, 0xFFB300, 5, ""},
            {0x00001400L, 0x2EE0FF, 3, "Chest"},
            {0x00041200L, 0xFFD800, 3, "Item"},
            {0x00001410L, 0xFFFFFF, 3, "Wither Essence"},
            {0x00081200L, 0x9C5A2E, 3, "Bat"},
            {0x00000200L, 0x2EFF2E, 4, "Exit"},
            {0x00000410L, 0xFF8A2E, 3, "Lever"},
            {0x00020013L, 0xC0C0FF, 5, ""},
            {0x00001400L, 0xFFFFFF, 3, "Wither Essence"},
            {0x00010210L, 0xC0C0FF, 5, ""},
            {0x00000400L, 0xFF8A2E, 3, "Lever"},
            {0x00001410L, 0xFF2E2E, 3, "Redstone Key"},
            {0x00001400L, 0xFF2E2E, 3, "Redstone Key"},
            {0x00010200L, 0xC0C0FF, 5, ""},
            {0x00000010L, 0x4FE05A, 6, ""},
            {0x00204910L, 0x6EE7B7, 1, ""},
            {0x00004900L, 0x6EE7B7, 1, ""},
            {0x00000210L, 0xFFC0CB, 4, "Prince"},
            {0x00000200L, 0xFFC0CB, 4, "Prince"},
    };

    static final String[] COMMON_STRINGS = {
            "FlameOfWar", "Waypoint", "Route", "Chest", "Item", "Bat", "Exit", "Lever",
            "Wither Essence", "Redstone Key", "Secret", "Etherwarp", "Superboom", "Stonk",
            "Ruby", "Amber", "Sapphire", "Jade", "Amethyst", "Topaz", "Jasper", "Opal", "Onyx",
            "Aquamarine", "Citrine", "Peridot", "Gemstones", "Mithril", "Titanium", "Umber",
            "Tungsten", "Glacite", "Fairy Soul", "Relic", "Start", "End", "Crystal Hollows",
            "Dwarven Mines", "Glacite Tunnels", "Mineshaft", "Commission", "Powder", "Treasure",
            "Hub", "Dungeon", "Boss", "Room", "Path", "Drop down", "Ledge", "Finish",
            "diamond_pickaxe", "prismarine_shard", "red_stained_glass"};

    private static final int CLASSES = 8;
    private static final int NEW_KIND_CLASS = 7;
    private static final int DEFAULT_COLOR = 0x4FE05A;
    private static final double DEFAULT_RADIUS = 3.0;
    private static final int MAX_STRING_BYTES = 4096;
    private static final int MAX_ROUTES = V11Codec.MAX_ROUTES;
    private static final int MAX_WAYPOINTS = V11Codec.MAX_TOTAL_WAYPOINTS;
    private static final int MAX_FOLDERS = RouteLibraryMetadata.MAX_FOLDERS;

    private static final Set<String> MODELED_ROUTE_KEYS = Set.of("name", "zone", "gradientMode",
            "loadMode", "routeKind", "defaultRadius", "skipAheadEnabled", "staticColor",
            "gradientStartColor", "gradientEndColor", "paintEnabled", "icon", "stylePresetId",
            "waypoints", "manualColors");
    private static final Set<String> MODELED_WAYPOINT_KEYS = Set.of("x", "y", "z", "name", "color",
            "flags", "radius", "preciseX", "preciseY", "preciseZ", "styleOverride");

    private static final int UINT = 64;
    private static final int SINT = 2 + UINT;
    private static int contexts;

    private static int alloc(int size) {
        int base = contexts;
        contexts += size;
        return base;
    }

    private static final int C_HAS_LABEL = alloc(1);
    private static final int C_ROUTE_COUNT = alloc(UINT);
    private static final int C_DUNGEON = alloc(1);
    private static final int C_SAME_ROUTE_NAME = alloc(2);
    private static final int C_ROOM = alloc(UINT);
    private static final int C_ZONE_UNKNOWN = alloc(1);
    private static final int C_ZONE_KNOWN = alloc(1);
    private static final int C_ZONE_INDEX = alloc(UINT);
    private static final int C_GRADIENT = alloc(2 * 3);
    private static final int C_LOAD = alloc(2);
    private static final int C_SKIP = alloc(2);
    private static final int C_RADIUS_DEFAULT = alloc(2);
    private static final int C_RADIUS_SIXTEENTHS = alloc(1);
    private static final int C_RADIUS = alloc(UINT);
    private static final int C_ROUTE_COLOR_DEFAULT = alloc(2 * 3);
    private static final int C_PAINT_ENABLED = alloc(2);
    private static final int C_HAS_ICON = alloc(2);
    private static final int C_HAS_PRESET = alloc(2);
    private static final int C_HAS_EXTRAS = alloc(2);
    private static final int C_WAYPOINT_COUNT = alloc(2 * UINT);
    private static final int C_KIND_RANK = alloc(CLASSES * UINT);
    private static final int C_KIND_FLAG = alloc(32);
    private static final int C_KIND_DEFAULT_COLOR = alloc(1);
    private static final int C_KIND_EXTENDED_COLOR = alloc(1);
    private static final int C_KIND_HAS_RADIUS = alloc(1);
    private static final int C_KIND_HAS_STYLE = alloc(1);
    private static final int C_KIND_PRECISE = alloc(1);
    private static final int C_FIRST = alloc(2 * 3 * SINT);
    private static final int C_DELTA = alloc(3 * CLASSES * 2 * SINT);
    private static final int C_PRECISE = alloc(3 * 16);
    private static final int C_HAS_NAME = alloc(CLASSES * 2);
    private static final int C_NAME_KIND = alloc(CLASSES);
    private static final int C_NAME_INDEX = alloc(CLASSES * 2);
    private static final int C_NAME_INCREMENT = alloc(CLASSES);
    private static final int C_NAME_NUMBER = alloc(CLASSES);
    private static final int C_NAME_NUMBER_VALUE = alloc(SINT);
    private static final int C_POOLED = alloc(1);
    private static final int C_POOL_RANK = alloc(UINT);
    private static final int C_STRING_LENGTH = alloc(UINT);
    private static final int C_STRING_BYTE = alloc(256 * 256);
    private static final int C_HAS_FOLDERS = alloc(1);
    private static final int C_FOLDER_COUNT = alloc(UINT);
    private static final int C_FOLDER_COLOR_DEFAULT = alloc(1);
    private static final int C_FOLDER_COLLAPSED = alloc(1);
    private static final int C_FOLDER_MEMBERS = alloc(UINT);
    private static final int C_FOLDER_MEMBER = alloc(SINT);
    private static final int C_DELTA_PREV = alloc(3 * 5 * 2 * SINT);
    private static final int C_KIND_RANK_PREV = alloc(17 * UINT);
    private static final int C_DELTA_OTHER = alloc(3 * 5 * SINT);
    private static final int C_DELTA_AXIS = alloc(3 * SINT);
    private static final int CONTEXTS = contexts;

    private static int mixers;

    private static int allocMixers(int size) {
        int base = mixers;
        mixers += size;
        return base;
    }

    private static final int M_DELTA = allocMixers(3 * SINT);
    private static final int M_KIND = allocMixers(UINT);
    private static final int MIXERS = mixers;

    private V11CompactCodec() {}

    static byte[] encode(List<WaypointGroup> groups, String label,
                         List<RouteLibraryMetadata.FolderDefinition> folders) throws IOException {
        JsonObject root = V11Codec.toJson(groups, label, folders);
        JsonArray routes = root.getAsJsonArray("routes");
        for (JsonElement route : routes) withManualColors(route.getAsJsonObject());
        V11Coder coder = start(V11Coder.encoder(CONTEXTS, MIXERS));
        new Grammar(coder).share(root);
        byte[] payload = coder.finish();
        byte[] body = new byte[payload.length + 1];
        body[0] = (byte) (V11Codec.WIRE_VERSION | V11Codec.KIND_COMPACT << 4);
        System.arraycopy(payload, 0, body, 1, payload.length);
        return body;
    }

    static V11Codec.Decoded decode(byte[] semantic) throws IOException {
        if (semantic.length < 1) throw new IOException("empty v11 compact body");
        byte[] payload = java.util.Arrays.copyOfRange(semantic, 1, semantic.length);
        V11Coder coder = start(V11Coder.decoder(CONTEXTS, MIXERS, payload));
        JsonObject root = new Grammar(coder).share(null);
        V11Codec.Decoded decoded;
        try {
            decoded = V11Codec.fromJson(root);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid v11 compact routes: " + invalid.getMessage(), invalid);
        }
        byte[] again = encode(decoded.groups(), decoded.label(), decoded.metadata().folders());
        if (!java.util.Arrays.equals(again, semantic)) throw new IOException("non-canonical v11 compact body");
        return decoded;
    }

    static final int CONFIDENCE = 6;

    static V11Model model = V11Model.load(CONTEXTS, MIXERS);

    static int contexts() {
        return CONTEXTS;
    }

    static int mixers() {
        return MIXERS;
    }

    private static V11Coder start(V11Coder coder) {
        handPriors(coder);
        V11Model current = model;
        if (current != null) coder.start(current.probabilities(), current.seen(), current.weights(), CONFIDENCE);
        return coder;
    }

    static V11Model train(List<JsonObject> shares) throws IOException {
        V11Model saved = model;
        model = null;
        try {
            V11Coder coder = start(V11Coder.encoder(CONTEXTS, MIXERS));
            for (JsonObject share : shares) {
                JsonObject copy = share.deepCopy();
                for (JsonElement route : copy.getAsJsonArray("routes")) withManualColors(route.getAsJsonObject());
                new Grammar(coder).share(copy);
            }
            return new V11Model(coder.probabilities().clone(), coder.seen().clone(), coder.weights().clone());
        } finally {
            model = saved;
        }
    }

    private static void handPriors(V11Coder c) {
        for (int i = 0; i < 32; i++) c.prior(C_KIND_FLAG + i, 0.08, 4);
        c.prior(C_KIND_DEFAULT_COLOR, 0.6, 2);
        c.prior(C_KIND_HAS_RADIUS, 0.05, 6);
        c.prior(C_KIND_HAS_STYLE, 0.03, 8);
        c.prior(C_KIND_PRECISE, 0.05, 6);
        c.prior(C_HAS_LABEL, 0.1, 4);
        c.prior(C_HAS_FOLDERS, 0.05, 6);
        for (int kind = 0; kind < 2; kind++) {
            c.prior(C_RADIUS_DEFAULT + kind, 0.9, 6);
            c.prior(C_PAINT_ENABLED + kind, 0.8, 4);
            c.prior(C_HAS_ICON + kind, 0.2, 3);
            c.prior(C_HAS_PRESET + kind, 0.03, 8);
            c.prior(C_HAS_EXTRAS + kind, 0.05, 8);
            for (int color = 0; color < 3; color++) c.prior(C_ROUTE_COLOR_DEFAULT + kind * 3 + color, 0.9, 6);
        }
        c.prior(C_DUNGEON, 0.3, 1);
    }

    private static void withManualColors(JsonObject route) {
        if (!route.has("manualColors")) return;
        JsonArray manualColors = route.getAsJsonArray("manualColors");
        JsonArray waypoints = route.getAsJsonArray("waypoints");
        for (int i = 0; i < waypoints.size() && i < manualColors.size(); i++) {
            waypoints.get(i).getAsJsonObject().add("color", manualColors.get(i));
        }
        route.remove("manualColors");
    }

    private static final class Grammar {
        private final V11Coder c;
        private final boolean enc;
        private final List<String> pool = new ArrayList<>();
        private final List<Integer> kindOrder = new ArrayList<>();
        private final List<Kind> kinds = new ArrayList<>();
        private final List<Integer> kindClass = new ArrayList<>();
        private final Map<Kind, Integer> kindIds = new HashMap<>();
        private final Map<Integer, String> lastNameByKind = new HashMap<>();
        private int totalWaypoints;

        Grammar(V11Coder coder) {
            this.c = coder;
            this.enc = coder.encoding();
            for (Object[] builtin : BUILTIN_KINDS) {
                addKind(new Kind((Long) builtin[0], (Integer) builtin[1], "", "", false), (Integer) builtin[2]);
                if (!((String) builtin[3]).isEmpty()) lastNameByKind.put(kinds.size() - 1, (String) builtin[3]);
            }
            for (int i = COMMON_STRINGS.length - 1; i >= 0; i--) pool.addFirst(COMMON_STRINGS[i]);
        }

        private void addKind(Kind kind, int cls) {
            int id = kinds.size();
            kinds.add(kind);
            kindClass.add(cls);
            kindIds.put(kind, id);
            kindOrder.add(id);
        }

        JsonObject share(JsonObject in) throws IOException {
            JsonObject out = new JsonObject();
            String label = in != null && in.has("label") ? in.get("label").getAsString() : "";
            if (c.flag(C_HAS_LABEL, !label.isEmpty())) out.addProperty("label", string(label));
            c.part = "share";
            JsonArray routesIn = in == null ? null : in.getAsJsonArray("routes");
            int count = uint(C_ROUTE_COUNT, routesIn == null ? 0 : routesIn.size() - 1) + 1;
            if (count > MAX_ROUTES) throw new IOException("too many routes");
            JsonArray routes = new JsonArray();
            String previousName = null;
            int[] previousFirst = null;
            for (int r = 0; r < count; r++) {
                JsonObject routeIn = routesIn == null ? null : routesIn.get(r).getAsJsonObject();
                JsonObject route = new JsonObject();
                previousFirst = route(routeIn, route, previousName, previousFirst);
                previousName = route.get("name").getAsString();
                routes.add(route);
            }
            out.add("routes", routes);
            JsonArray foldersIn = in != null && in.has("folders") ? in.getAsJsonArray("folders") : null;
            if (c.flag(C_HAS_FOLDERS, foldersIn != null)) out.add("folders", folders(foldersIn, count));
            return out;
        }

        private int[] route(JsonObject in, JsonObject out, String previousName, int[] previousFirst)
                throws IOException {
            c.part = "route";
            boolean dungeon = c.flag(C_DUNGEON, in != null
                    && "DUNGEON".equals(in.get("routeKind").getAsString()));
            int k = dungeon ? 1 : 0;
            String name = in == null ? "" : in.get("name").getAsString();
            boolean same = previousName != null
                    && c.flag(C_SAME_ROUTE_NAME + k, name.equals(previousName));
            out.addProperty("name", same ? previousName : string(name));
            out.addProperty("zone", zone(in == null ? "" : in.get("zone").getAsString(), dungeon));
            out.addProperty("routeKind", dungeon ? "DUNGEON" : "REGULAR");

            String gradient = in == null ? "" : in.get("gradientMode").getAsString();
            int g = symbol3(C_GRADIENT + k * 3, switch (gradient) {
                case "AUTO" -> 0;
                case "MANUAL" -> 1;
                default -> 2;
            });
            out.addProperty("gradientMode", g == 0 ? "AUTO" : g == 1 ? "MANUAL" : "STATIC");
            boolean sequence = c.flag(C_LOAD + k, in != null && "SEQUENCE".equals(in.get("loadMode").getAsString()));
            out.addProperty("loadMode", sequence ? "SEQUENCE" : "STATIC");
            out.addProperty("skipAheadEnabled", c.flag(C_SKIP + k,
                    in != null && in.get("skipAheadEnabled").getAsBoolean()));
            double radius = in == null ? DEFAULT_RADIUS : in.get("defaultRadius").getAsDouble();
            boolean defaultRadius = c.flag(C_RADIUS_DEFAULT + k, radius == DEFAULT_RADIUS);
            out.addProperty("defaultRadius", defaultRadius ? DEFAULT_RADIUS : radius(radius));
            String[] colorKeys = {"staticColor", "gradientStartColor", "gradientEndColor"};
            int[] colorDefaults = {DEFAULT_COLOR, 0x00BFFF, 0xFF3040};
            for (int i = 0; i < 3; i++) {
                int color = in == null ? 0 : in.get(colorKeys[i]).getAsInt();
                boolean isDefault = c.flag(C_ROUTE_COLOR_DEFAULT + k * 3 + i, color == colorDefaults[i]);
                out.addProperty(colorKeys[i], isDefault ? colorDefaults[i] : rgb(color));
            }
            out.addProperty("paintEnabled", c.flag(C_PAINT_ENABLED + k,
                    in != null && in.get("paintEnabled").getAsBoolean()));
            String icon = in != null && in.has("icon") ? in.get("icon").getAsString() : "";
            if (c.flag(C_HAS_ICON + k, !icon.isEmpty())) out.addProperty("icon", string(icon));
            String preset = in != null && in.has("stylePresetId") ? in.get("stylePresetId").getAsString() : "";
            if (c.flag(C_HAS_PRESET + k, !preset.isEmpty())) out.addProperty("stylePresetId", string(preset));
            String extras = in == null ? "" : extras(in, MODELED_ROUTE_KEYS);
            if (c.flag(C_HAS_EXTRAS + k, !extras.isEmpty())) mergeExtras(out, string(extras));

            c.part = "route";
            JsonArray waypointsIn = in == null ? null : in.getAsJsonArray("waypoints");
            int count = uint(C_WAYPOINT_COUNT + k * UINT, waypointsIn == null ? 0 : waypointsIn.size());
            totalWaypoints += count;
            if (totalWaypoints > MAX_WAYPOINTS) throw new IOException("too many waypoints");
            JsonArray waypoints = new JsonArray();
            int[] previous = null;
            int[] first = null;
            int previousClass = 6;
            int previousKind = -1;
            String previousWaypointName = "";
            for (int i = 0; i < count; i++) {
                JsonObject w = waypointsIn == null ? null : waypointsIn.get(i).getAsJsonObject();
                JsonObject wOut = new JsonObject();
                c.part = "kind";
                int kind = kind(w, previousClass, previousKind);
                int cls = kindClass.get(kind);
                Kind spec = kinds.get(kind);
                int[] position = new int[3];
                for (int axis = 0; axis < 3; axis++) {
                    c.part = (previous == null ? "first-" : "delta-") + "xyz".charAt(axis);
                    int value = w == null ? 0 : w.get(axis == 0 ? "x" : axis == 1 ? "y" : "z").getAsInt();
                    if (previous == null) {
                        int origin = previousFirst == null ? 0 : previousFirst[axis];
                        position[axis] = origin + sint(C_FIRST + ((k * 3 + axis) * SINT), value - origin);
                    } else {
                        int before = previous[3 + axis];
                        int zeroBefore = before == 0 ? 1 : 0;
                        int other = 0;
                        if (axis == 0) {
                            other = bucket(Math.max(Math.abs(previous[3]), Math.max(Math.abs(previous[4]), Math.abs(previous[5]))));
                        } else {
                            for (int a = 0; a < axis; a++) other = Math.max(other, bucket(Math.abs(position[a] - previous[a])));
                        }
                        int[] bases = this.bases;
                        bases[0] = C_DELTA + ((axis * CLASSES + cls) * 2 + zeroBefore) * SINT;
                        bases[1] = C_DELTA_PREV + ((axis * 5 + bucket(Math.abs(before))) * 2 + (before < 0 ? 1 : 0)) * SINT;
                        bases[2] = C_DELTA_OTHER + (axis * 5 + other) * SINT;
                        bases[3] = C_DELTA_AXIS + axis * SINT;
                        position[axis] = previous[axis] + msint(bases, 4, M_DELTA + axis * SINT, value - previous[axis]);
                    }
                }
                wOut.addProperty("x", position[0]);
                wOut.addProperty("y", position[1]);
                wOut.addProperty("z", position[2]);
                if (spec.precise) precise(w, wOut, position);
                c.part = "name";
                String waypointName = waypointName(w, wOut, kind, cls, i, previousWaypointName);
                if (!spec.style.isEmpty()) wOut.add("styleOverride", BoundedJson.parse(spec.style));
                wOut.addProperty("color", spec.color);
                if (spec.flags != 0) wOut.addProperty("flags", (int) spec.flags);
                if (!spec.radius.isEmpty()) wOut.addProperty("radius", Double.parseDouble(spec.radius));
                String wExtras = w == null ? "" : extras(w, MODELED_WAYPOINT_KEYS);
                if (!wExtras.isEmpty()) throw new IOException("waypoint has fields the compact form does not carry");
                waypoints.add(wOut);
                int[] next = new int[6];
                for (int axis = 0; axis < 3; axis++) {
                    next[axis] = position[axis];
                    next[3 + axis] = previous == null ? 1 : position[axis] - previous[axis];
                }
                if (first == null) first = position;
                previous = next;
                previousClass = cls;
                previousKind = kind;
                previousWaypointName = waypointName;
            }
            out.add("waypoints", waypoints);
            return first == null ? previousFirst : first;
        }

        private int kind(JsonObject w, int previousClass, int previousKind) throws IOException {
            bases[0] = C_KIND_RANK + previousClass * UINT;
            bases[1] = C_KIND_RANK_PREV + (previousKind < 0 ? 16 : Math.min(previousKind, 15)) * UINT;
            int id = -1;
            Kind spec = null;
            if (w != null) {
                spec = kindOf(w);
                id = kindIds.getOrDefault(spec, -1);
            }
            int rank = muint(bases, 2, M_KIND, 0, id < 0 ? kindOrder.size() : kindOrder.indexOf(id));
            if (rank > kindOrder.size()) throw new IOException("invalid waypoint kind");
            if (rank == kindOrder.size()) {
                spec = newKind(spec);
                if (kindIds.containsKey(spec)) throw new IOException("repeated waypoint kind");
                addKind(spec, spec.flags == 0 || spec.flags == 0x10 ? 6 : NEW_KIND_CLASS);
                id = kinds.size() - 1;
                kindOrder.remove(kindOrder.size() - 1);
            } else {
                id = kindOrder.remove(rank);
            }
            kindOrder.addFirst(id);
            return id;
        }

        private Kind newKind(Kind in) throws IOException {
            long flags = 0;
            for (int bit = 0; bit < 32; bit++) {
                flags |= (long) c.bit(C_KIND_FLAG + bit, in == null ? 0 : (int) (in.flags >>> bit) & 1) << bit;
            }
            boolean defaultColor = c.flag(C_KIND_DEFAULT_COLOR, in != null && in.color == DEFAULT_COLOR);
            int color = DEFAULT_COLOR;
            if (!defaultColor) {
                color = rgb(in == null ? 0 : in.color);

                if (c.flag(C_KIND_EXTENDED_COLOR, in != null && (in.color >>> 24) != 0)) {
                    int top = (int) c.rawBits(8, in == null ? 0 : in.color >>> 24);
                    if (top == 0) throw new IOException("non-canonical extended colour");
                    color |= top << 24;
                }
            }
            String radius = "";
            if (c.flag(C_KIND_HAS_RADIUS, in != null && !in.radius.isEmpty())) {
                radius = Double.toString(radius(in == null ? 0 : Double.parseDouble(in.radius)));
            }
            String style = "";
            if (c.flag(C_KIND_HAS_STYLE, in != null && !in.style.isEmpty())) {
                style = string(in == null ? "" : in.style);
                try {
                    if (!BoundedJson.parse(style).isJsonObject()) throw new IOException("invalid style");
                } catch (RuntimeException invalid) {
                    throw new IOException("invalid waypoint style", invalid);
                }
            }
            boolean precise = c.flag(C_KIND_PRECISE, in != null && in.precise);
            return new Kind(flags, color, radius, style, precise);
        }

        private static Kind kindOf(JsonObject w) {
            long flags = w.has("flags") ? Integer.toUnsignedLong(w.get("flags").getAsInt()) : 0;
            String radius = w.has("radius") ? Double.toString(w.get("radius").getAsDouble()) : "";
            String style = w.has("styleOverride") ? w.get("styleOverride").toString() : "";
            return new Kind(flags, w.get("color").getAsInt(), radius, style, w.has("preciseX"));
        }

        private void precise(JsonObject w, JsonObject out, int[] position) throws IOException {
            String[] keys = {"preciseX", "preciseY", "preciseZ"};
            for (int axis = 0; axis < 3; axis++) {
                int center = position[axis] * 16 + 8;
                int offset = w == null ? 0 : w.get(keys[axis]).getAsInt() - center;
                if (w != null && (offset < -8 || offset > 7)) throw new IOException("precise offset out of range");
                int value = 0;
                for (int b = 3; b >= 0; b--) {
                    int node = (1 << (3 - b)) + (value & ((1 << (3 - b)) - 1)) - 1;
                    int bit = c.bit(C_PRECISE + axis * 16 + Math.min(node, 15), ((offset + 8) >>> b) & 1);
                    value = value << 1 | bit;
                }
                out.addProperty(keys[axis], center + value - 8);
            }
        }

        private String waypointName(JsonObject w, JsonObject out, int kind, int cls, int index,
                                    String previousName) throws IOException {
            String name = w != null && w.has("name") ? w.get("name").getAsString() : "";
            boolean hadName = !previousName.isEmpty();
            if (!c.flag(C_HAS_NAME + cls * 2 + (hadName ? 1 : 0), !name.isEmpty())) return "";
            String kindName = lastNameByKind.get(kind);
            String result = null;
            if (kindName != null && c.flag(C_NAME_KIND + cls, name.equals(kindName))) result = kindName;
            String indexName = Integer.toString(index + 1);
            if (result == null && !indexName.equals(kindName)
                    && c.flag(C_NAME_INDEX + cls * 2 + (hadName ? 1 : 0), name.equals(indexName))) {
                result = indexName;
            }
            String incremented = increment(previousName);
            if (result == null && incremented != null && !incremented.equals(kindName)
                    && !incremented.equals(indexName)
                    && c.flag(C_NAME_INCREMENT + cls, name.equals(incremented))) {
                result = incremented;
            }
            if (result == null) {
                Integer number = canonicalNumber(name);
                boolean numeric = c.flag(C_NAME_NUMBER + cls, enc && number != null);
                if (numeric) {
                    long value = (long) index + 1 + sint(C_NAME_NUMBER_VALUE, enc ? number - (index + 1) : 0);
                    if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("invalid number name");
                    result = Long.toString(value);
                    if (result.equals(kindName) || result.equals(indexName) || result.equals(incremented)) {
                        throw new IOException("non-canonical number name");
                    }
                } else {
                    result = string(name);
                    if (result.isEmpty() || canonicalNumber(result) != null || result.equals(kindName)
                            || result.equals(indexName) || result.equals(incremented)) {
                        throw new IOException("non-canonical waypoint name");
                    }
                }
            }
            out.addProperty("name", result);
            lastNameByKind.put(kind, result);
            return result;
        }

        private static Integer canonicalNumber(String text) {
            if (text.isEmpty() || text.length() > 10) return null;
            if (text.length() > 1 && text.charAt(0) == '0') return null;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) < '0' || text.charAt(i) > '9') return null;
            }
            long value = Long.parseLong(text);
            return value > Integer.MAX_VALUE ? null : (int) value;
        }

        static String increment(String name) {
            int end = name.length();
            int start = end;
            while (start > 0 && Character.isDigit(name.charAt(start - 1)) && name.charAt(start - 1) < 128) start--;
            if (start == end || end - start > 9) return null;
            String digits = name.substring(start);
            if (digits.length() > 1 && digits.charAt(0) == '0') return null;
            return name.substring(0, start) + (Long.parseLong(digits) + 1);
        }

        private String zone(String zone, boolean dungeon) throws IOException {
            if (dungeon) {
                int index = -1;
                for (int i = 0; i < ROOMS.length; i++) if (ROOMS[i].equals(zone)) index = i;
                int coded = uint(C_ROOM, index < 0 ? ROOMS.length : index);
                if (coded > ROOMS.length) throw new IOException("invalid room");
                if (coded < ROOMS.length) return ROOMS[coded];
                String literal = string(zone);
                for (String room : ROOMS) if (room.equals(literal)) throw new IOException("non-canonical room");
                return literal;
            }
            if (c.flag(C_ZONE_UNKNOWN, "unknown".equals(zone))) return "unknown";
            int known = enc ? CodecZoneDictionary.indexOf(zone) : -1;
            if (c.flag(C_ZONE_KNOWN, known >= 0)) {
                int index = uint(C_ZONE_INDEX, known);
                if (!CodecZoneDictionary.isKnownIndex(index)) throw new IOException("invalid zone");
                return CodecZoneDictionary.idAt(index);
            }
            String literal = string(zone);
            if ("unknown".equals(literal) || CodecZoneDictionary.indexOf(literal) >= 0) {
                throw new IOException("non-canonical zone");
            }
            return literal;
        }

        private JsonArray folders(JsonArray in, int routeCount) throws IOException {
            int count = uint(C_FOLDER_COUNT, in == null ? 0 : in.size() - 1) + 1;
            if (count > MAX_FOLDERS) throw new IOException("too many folders");
            JsonArray out = new JsonArray();
            for (int f = 0; f < count; f++) {
                JsonObject folder = in == null ? null : in.get(f).getAsJsonObject();
                JsonObject o = new JsonObject();
                o.addProperty("name", string(folder == null ? "" : folder.get("name").getAsString()));
                int color = folder == null ? 0 : folder.get("color").getAsInt();
                boolean defaultColor = c.flag(C_FOLDER_COLOR_DEFAULT,
                        color == com.babbur.waypointer.core.RouteFolder.DEFAULT_COLOR);
                o.addProperty("color", defaultColor ? com.babbur.waypointer.core.RouteFolder.DEFAULT_COLOR : rgb(color));
                if (c.flag(C_FOLDER_COLLAPSED, folder != null && folder.has("collapsed"))) o.addProperty("collapsed", true);
                JsonArray members = folder == null ? null : folder.getAsJsonArray("routes");
                int memberCount = uint(C_FOLDER_MEMBERS, members == null ? 0 : members.size() - 1) + 1;
                if (memberCount > routeCount) throw new IOException("folder has too many routes");
                JsonArray m = new JsonArray();
                int previous = -1;
                for (int i = 0; i < memberCount; i++) {
                    int value = members == null ? 0 : members.get(i).getAsInt();
                    int decoded = previous + 1 + sint(C_FOLDER_MEMBER, value - previous - 1);
                    if (decoded < 0 || decoded >= routeCount) throw new IOException("invalid folder route");
                    m.add(decoded);
                    previous = decoded;
                }
                o.add("routes", m);
                out.add(o);
            }
            return out;
        }

        private String string(String value) throws IOException {
            int rank = enc ? pool.indexOf(value) : -1;
            if (c.flag(C_POOLED, rank >= 0)) {
                int coded = uint(C_POOL_RANK, rank);
                if (coded >= pool.size()) throw new IOException("invalid string reference");
                String found = pool.remove(coded);
                pool.addFirst(found);
                return found;
            }
            byte[] bytes = enc ? value.getBytes(StandardCharsets.UTF_8) : null;
            int length = uint(C_STRING_LENGTH, bytes == null ? 0 : bytes.length);
            if (length > MAX_STRING_BYTES) throw new IOException("string too long");
            byte[] decoded = new byte[length];
            int previous = 0;
            for (int i = 0; i < length; i++) {
                int node = 1;
                int value8 = bytes == null ? 0 : bytes[i] & 0xFF;
                for (int b = 7; b >= 0; b--) {
                    int bit = c.bit(C_STRING_BYTE + previous * 256 + node, (value8 >>> b) & 1);
                    node = node << 1 | bit;
                }
                decoded[i] = (byte) (node & 0xFF);
                previous = node & 0xFF;
            }
            String result;
            try {
                result = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(decoded)).toString();
            } catch (java.nio.charset.CharacterCodingException malformed) {
                throw new IOException("invalid UTF-8 in v11 share", malformed);
            }
            if (pool.contains(result)) throw new IOException("non-canonical repeated string");
            pool.addFirst(result);
            return result;
        }

        private int rgb(int value) throws IOException {
            return (int) c.rawBits(24, value & 0xFFFFFF);
        }

        private double radius(double value) throws IOException {
            boolean sixteenths = c.flag(C_RADIUS_SIXTEENTHS,
                    enc && value * 16 == Math.rint(value * 16) && value > 0 && value <= 100);
            if (sixteenths) {
                int coded = uint(C_RADIUS, enc ? (int) Math.rint(value * 16) - 1 : 0) + 1;
                if (coded > 1600) throw new IOException("radius out of range");
                return coded / 16.0;
            }
            long bits = c.rawBits(64, Double.doubleToRawLongBits(value));
            double decoded = Double.longBitsToDouble(bits);
            if (!Double.isFinite(decoded)) throw new IOException("invalid radius");
            return decoded;
        }

        private int symbol3(int base, int value) throws IOException {
            if (c.bit(base, value == 0 ? 0 : 1) == 0) return 0;
            int second = c.bit(base + 1, value == 1 ? 0 : 1);
            if (second == 0) return 1;
            return 2;
        }

        private int uint(int base, int value) throws IOException {
            if (enc && value < 0) throw new IllegalArgumentException("negative value");
            long m = (long) value + 1;
            int length = enc ? 63 - Long.numberOfLeadingZeros(m) : 0;
            int n = 0;
            while (true) {
                int more = c.bit(base + Math.min(n, 15), n < length ? 1 : 0);
                if (more == 0) break;
                if (++n > 31) throw new IOException("number too large");
            }
            long result = 1;
            for (int b = n - 1; b >= 0; b--) {
                int position = n - 1 - b;
                int bit;
                int want = (int) (m >>> b) & 1;
                if (position < 2) {
                    int node = position == 0 ? 0 : 1 + (int) (result & 1);
                    bit = c.bit(base + 16 + Math.min(n, 15) * 3 + node, want);
                } else {
                    bit = c.raw(want);
                }
                result = result << 1 | bit;
            }
            if (result - 1 > Integer.MAX_VALUE) throw new IOException("number too large");
            return (int) (result - 1);
        }

        private final int[] bases = new int[V11Coder.MAX_INPUTS];
        private final int[] ids = new int[V11Coder.MAX_INPUTS];

        private static int bucket(int magnitude) {
            return magnitude == 0 ? 0 : magnitude == 1 ? 1 : magnitude < 4 ? 2 : magnitude < 8 ? 3 : 4;
        }

        private int mbit(int[] bases, int count, int mixer, int offset, int value) throws IOException {
            for (int i = 0; i < count; i++) ids[i] = bases[i] + offset;
            return c.mix(ids, count, mixer + offset, value);
        }

        private int muint(int[] bases, int count, int mixer, int start, int value) throws IOException {
            long m = (long) value + 1;
            int length = enc ? 63 - Long.numberOfLeadingZeros(m) : 0;
            int n = 0;
            while (true) {
                int more = mbit(bases, count, mixer, start + Math.min(n, 15), n < length ? 1 : 0);
                if (more == 0) break;
                if (++n > 31) throw new IOException("number too large");
            }
            long result = 1;
            for (int b = n - 1; b >= 0; b--) {
                int position = n - 1 - b;
                int want = (int) (m >>> b) & 1;
                int bit;
                if (position < 2) {
                    int node = position == 0 ? 0 : 1 + (int) (result & 1);
                    bit = mbit(bases, count, mixer, start + 16 + Math.min(n, 15) * 3 + node, want);
                } else {
                    bit = c.raw(want);
                }
                result = result << 1 | bit;
            }
            if (result - 1 > Integer.MAX_VALUE) throw new IOException("number too large");
            return (int) (result - 1);
        }

        private int msint(int[] bases, int count, int mixer, int value) throws IOException {
            if (mbit(bases, count, mixer, 0, value == 0 ? 0 : 1) == 0) return 0;
            boolean negative = mbit(bases, count, mixer, 1, value < 0 ? 1 : 0) == 1;
            long magnitude = (long) muint(bases, count, mixer, 2, enc ? (int) (Math.abs((long) value) - 1) : 0) + 1;
            long result = negative ? -magnitude : magnitude;
            if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) throw new IOException("number too large");
            return (int) result;
        }

        private int sint(int base, int value) throws IOException {
            if (c.bit(base, value == 0 ? 0 : 1) == 0) return 0;
            boolean negative = c.flag(base + 1, value < 0);
            long magnitude = (long) uint(base + 2, enc ? (int) (Math.abs((long) value) - 1) : 0) + 1;
            long result = negative ? -magnitude : magnitude;
            if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) throw new IOException("number too large");
            return (int) result;
        }

        private static String extras(JsonObject object, Set<String> modeled) {
            JsonObject rest = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!modeled.contains(entry.getKey())) rest.add(entry.getKey(), entry.getValue());
            }
            return rest.isEmpty() ? "" : rest.toString();
        }

        private static void mergeExtras(JsonObject out, String extras) throws IOException {
            JsonElement parsed;
            try {
                parsed = BoundedJson.parse(extras);
            } catch (RuntimeException invalid) {
                throw new IOException("invalid route extras", invalid);
            }
            if (!parsed.isJsonObject() || parsed.getAsJsonObject().isEmpty()) {
                throw new IOException("invalid route extras");
            }
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                if (MODELED_ROUTE_KEYS.contains(entry.getKey())) throw new IOException("non-canonical route extras");
                out.add(entry.getKey(), entry.getValue());
            }
        }
    }
}
