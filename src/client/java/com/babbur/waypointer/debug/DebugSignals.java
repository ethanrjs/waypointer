package com.babbur.waypointer.debug;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.Direction;
import com.babbur.waypointer.dungeon.DungeonDetectionConfidence;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRoomZoneBridge;
import com.babbur.waypointer.dungeon.DungeonRouteSession;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import com.babbur.waypointer.location.HypixelApiZoneSource;
import com.babbur.waypointer.location.SidebarTexts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class DebugSignals {

    private DebugSignals() {}

    public static DungeonDebugSnapshot dungeonDebugSnapshot() {
        DungeonStateTracker tracker = WaypointerClient.dungeonTracker();
        DungeonRouteSession session = WaypointerClient.dungeonRouteSession();
        DungeonRoom room = tracker == null ? null : tracker.currentRoom();
        DungeonStateTracker.DebugSnapshot trackerSnapshot = tracker == null
                ? null
                : tracker.debugSnapshot();
        DungeonRouteSession.DebugSnapshot routeSnapshot = session == null
                ? null
                : session.debugSnapshot(room);
        return new DungeonDebugSnapshot(
                trackerSnapshot,
                DungeonRoomZoneBridge.debugSnapshot(),
                routeSnapshot,
                DebugEventLog.snapshot());
    }

    public static String dungeonRoomLine() {
        DungeonStateTracker tracker = WaypointerClient.dungeonTracker();
        if (tracker == null) return "not installed";

        return dungeonRoomLine(tracker.debugSnapshot());
    }

    static String dungeonRoomLine(DungeonStateTracker.DebugSnapshot snapshot) {
        if (snapshot == null) return "not installed";
        if (!snapshot.roomPresent) {
            return "inDungeon=" + snapshot.inDungeon
                    + ", room=(none)"
                    + ", directionOverride=" + snapshot.directionOverride;
        }
        return "inDungeon=" + snapshot.inDungeon
                + ", room=" + snapshot.roomName
                + ", id=" + snapshot.roomId
                + ", confidence=" + detectionConfidenceLabel(snapshot.confidence)
                + ", type=" + snapshot.roomType
                + ", shape=" + snapshot.roomShape
                + ", dir=" + snapshot.roomDirection
                + ", corner=" + snapshot.physicalCornerX + "," + snapshot.physicalCornerZ
                + ", segments=" + snapshot.roomSegments.size()
                + ", directionOverride=" + snapshot.directionOverride;
    }

    static String dungeonRoomLine(boolean inDungeon, DungeonRoom room, Direction directionOverride) {
        if (room == null) {
            return "inDungeon=" + inDungeon
                    + ", room=(none)"
                    + ", directionOverride=" + valueOrMissing(directionOverride);
        }
        String roomId = room.hasRoomId() ? room.roomId() : "<unmatched>";
        return "inDungeon=" + inDungeon
                + ", room=" + room.displayName()
                + ", id=" + roomId
                + ", confidence=" + detectionConfidenceLabel(room.confidence())
                + ", type=" + room.type()
                + ", shape=" + room.shape()
                + ", dir=" + room.direction()
                + ", corner=" + room.physicalCornerX() + "," + room.physicalCornerZ()
                + ", segments=" + room.segments().size()
                + ", directionOverride=" + valueOrMissing(directionOverride);
    }

    public static String detectionConfidenceLabel(DungeonDetectionConfidence confidence) {
        if (confidence == null) return "unknown";
        return switch (confidence) {
            case MAP_FALLBACK -> "map fallback (lower confidence)";
            case CORE_MATCHED -> "core matched";
            case CORE_CONFIRMED -> "core confirmed";
            case SKELETON_CONFIRMED -> "skeleton confirmed";
            case UNKNOWN -> "unknown";
        };
    }

    public static String dungeonBridgeLine() {
        return DungeonRoomZoneBridge.debugSnapshot().line;
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

    public static final class DungeonDebugSnapshot {
        public final DungeonStateTracker.DebugSnapshot tracker;
        public final DungeonRoomZoneBridge.DebugSnapshot bridge;
        public final DungeonRouteSession.DebugSnapshot routeSession;
        public final List<DebugEventLog.Entry> inputEvents;

        private DungeonDebugSnapshot(DungeonStateTracker.DebugSnapshot tracker,
                                     DungeonRoomZoneBridge.DebugSnapshot bridge,
                                     DungeonRouteSession.DebugSnapshot routeSession,
                                     List<DebugEventLog.Entry> inputEvents) {
            this.tracker = tracker;
            this.bridge = bridge;
            this.routeSession = routeSession;
            this.inputEvents = inputEvents == null ? List.of() : List.copyOf(inputEvents);
        }
    }
}
