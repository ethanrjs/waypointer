package dev.ethan.waypointer.debug;

import dev.ethan.waypointer.WaypointerClient;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.DungeonRoom;
import dev.ethan.waypointer.dungeon.DungeonRoomZoneBridge;
import dev.ethan.waypointer.dungeon.DungeonStateTracker;
import dev.ethan.waypointer.location.HypixelApiZoneSource;
import dev.ethan.waypointer.location.SidebarTexts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

public final class DebugSignals {

    private DebugSignals() {}

    public static String dungeonRoomLine() {
        DungeonStateTracker tracker = WaypointerClient.dungeonTracker();
        if (tracker == null) return "not installed";

        DungeonRoom room = tracker.currentRoom();
        if (room == null) {
            return "inDungeon=" + tracker.inDungeon()
                    + ", room=(none)"
                    + ", directionOverride=" + valueOrMissing(tracker.directionOverride());
        }

        String roomId = room.hasRoomId() ? room.roomId() : "<unmatched>";
        return "inDungeon=" + tracker.inDungeon()
                + ", room=" + room.displayName()
                + ", id=" + roomId
                + ", type=" + room.type()
                + ", shape=" + room.shape()
                + ", dir=" + room.direction()
                + ", corner=" + room.physicalCornerX() + "," + room.physicalCornerZ()
                + ", segments=" + room.segments().size()
                + ", directionOverride=" + valueOrMissing(tracker.directionOverride());
    }

    public static String dungeonBridgeLine() {
        return DungeonRoomZoneBridge.debugLine();
    }

    public static String dungeonConfigLine() {
        boolean legacyFeatureFlag = WaypointerClient.config() != null
                && WaypointerClient.config().dungeonWaypointsFeatureEnabled();
        String dungeonEnabled = WaypointerClient.dungeonConfig() == null
                ? "(not loaded)"
                : String.valueOf(WaypointerClient.dungeonConfig().enabled());
        return "legacyFeatureFlag=" + legacyFeatureFlag
                + ", dungeonConfigEnabled=" + dungeonEnabled;
    }

    public static String hypixelApiLine() {
        HypixelApiZoneSource.DebugSnapshot snapshot = HypixelApiZoneSource.debugSnapshot();
        if (snapshot == null) return "no packet/refine snapshot yet";

        return "serverType=" + valueOrMissing(snapshot.serverType())
                + ", map=" + valueOrMissing(snapshot.map())
                + ", mode=" + valueOrMissing(snapshot.mode())
                + ", raw=" + describeZone(snapshot.rawZone())
                + ", refined=" + describeZone(snapshot.refinedZone())
                + ", at=" + snapshot.capturedAt();
    }

    public static String scoreboardLine() {
        String scoreboardText = SidebarTexts.collectColorStripped(Minecraft.getInstance());
        return compactMultiline(scoreboardText);
    }

    public static String tabListLine() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) return "(unavailable)";

        StringBuilder out = new StringBuilder();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            String text = tabText(info);
            if (text == null || text.isBlank()) continue;
            if (!out.isEmpty()) out.append(" | ");
            out.append(compactInline(text));
        }
        return out.isEmpty() ? "(empty)" : out.toString();
    }

    private static String tabText(PlayerInfo info) {
        Component displayName = info.getTabListDisplayName();
        if (displayName != null) return displayName.getString();
        return info.getProfile().name();
    }

    private static String describeZone(Zone zone) {
        if (zone == null) return "(none)";
        return zone.displayName() + " (" + zone.id() + ")";
    }

    private static String valueOrMissing(Object value) {
        if (value == null) return "(none)";
        String text = String.valueOf(value);
        return text.isBlank() ? "(blank)" : text;
    }

    private static String compactMultiline(String text) {
        if (text == null) return "(unavailable)";
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder();
        int start = 0;
        while (start <= normalized.length()) {
            int end = normalized.indexOf('\n', start);
            if (end < 0) end = normalized.length();
            String line = compactInline(normalized.substring(start, end));
            if (!line.isBlank()) {
                if (!out.isEmpty()) out.append(" | ");
                out.append(line);
            }
            if (end == normalized.length()) break;
            start = end + 1;
        }
        return out.isEmpty() ? "(empty)" : out.toString();
    }

    private static String compactInline(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = !out.isEmpty();
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString().trim();
    }
}
