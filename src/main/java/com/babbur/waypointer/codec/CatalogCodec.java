package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

public final class CatalogCodec {
    public static final int V11_CODEC_VERSION = V11Codec.WIRE_VERSION;

    private CatalogCodec() {}

    public static String encode(List<WaypointGroup> groups) {
        String v9 = WaypointCodec.encodeCatalog(groups);
        if (sameRoutes(groups, WaypointCodec.decodeCanonicalV9(v9))) return v9;
        return V11Codec.encodeBest(groups, WaypointCodec.Options.FULL_FIDELITY, RouteLibraryMetadata.empty());
    }

    public static boolean isV11(String payload) {
        return V11Codec.isPayload(payload);
    }

    public static V11Codec.Decoded decodeV11(String payload) {
        return V11Codec.decode(payload);
    }

    private static boolean sameRoutes(List<WaypointGroup> groups, WaypointCodec.Decoded v9) {
        return shown(groups, "").equals(shown(v9.groups(), v9.label()));
    }

    private static String shown(List<WaypointGroup> groups, String label) {
        String fingerprint = V11Codec.fingerprint(
                V11Codec.project(groups, WaypointCodec.Options.FULL_FIDELITY), label, List.of());
        JsonObject root = JsonParser.parseString(fingerprint).getAsJsonObject();
        for (JsonElement route : root.getAsJsonArray("routes")) {
            JsonObject object = route.getAsJsonObject();
            if (!"MANUAL".equals(object.get("gradientMode").getAsString())) object.remove("manualColors");
        }
        return root.toString();
    }
}
