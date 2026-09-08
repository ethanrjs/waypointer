package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Downloads FlameOfWar's secret-room routes as normal editable Waypointer routes. */
public final class DungeonRouteDownloader {

    static final String ROUTES_COMMIT = "a6a88c831206df5c16ec49d416c9d7e6b5a533e0";
    static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    static final String SHA256 = "17d150a9e070642cbb6b34b3f2a88516faa7632340bf27d10193a3ee67e28750";
    public static final String PACK_NAME = "FlameOfWar";
    public static final String SOURCE_URL = "https://raw.githubusercontent.com/yourboykyle/SecretRoutes/"
            + ROUTES_COMMIT + "/fowroutes.json";
    public static final String ATTRIBUTION =
            "SecretRoutes by yourboykyle, wyannnnn (R-aMcC), Itplays, and FlameOfWar (GPL-3.0).";

    private DungeonRouteDownloader() {}

    /** Blocking download; callers must run it off the client thread. */
    public static DungeonRouteImporter.Result download() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(SOURCE_URL)
                .toURL().openConnection();
        connection.setConnectTimeout(6_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "waypointer-route-download");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("route download returned HTTP " + status);
            }
            try (InputStream body = connection.getInputStream()) {
                return parseDownloadedRoutes(readVerifiedPayload(body));
            }
        } finally {
            connection.disconnect();
        }
    }

    static DungeonRouteImporter.Result parseDownloadedRoutes(String payload) {
        DungeonRouteImporter.Result parsed = DungeonRouteImporter.parse(payload);
        var routes = parsed.groups().stream().filter(route -> {
            var room = DungeonRoomData.entry(route.zoneId());
            return room == null || room.secretCount() != 0;
        }).toList();
        return new DungeonRouteImporter.Result(routes, DungeonRoomShareCodec.waypointCount(routes),
                parsed.unmatchedRooms(), parsed.skippedVariants(), parsed.format());
    }

    static String readVerifiedPayload(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_DOWNLOAD_BYTES + 1);
        if (bytes.length > MAX_DOWNLOAD_BYTES) {
            throw new IOException("route download exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
        }
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
        if (!SHA256.equals(digest)) {
            throw new IOException("route download failed integrity verification");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
