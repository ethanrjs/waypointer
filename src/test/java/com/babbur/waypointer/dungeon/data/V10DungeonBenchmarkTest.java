package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.codec.AsciiStreamCodec;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locked final-wire comparison against the exact production WPD schema-2 writer. */
class V10DungeonBenchmarkTest {

    @Test
    void boundedCoordinatePortfolioAblationStaysWithLocalRaw() throws Exception {
        int deltaWins = 0;
        int localWins = 0;
        int ties = 0;
        for (int index = 0; index < 200; index++) {
            Scenario scenario = new Scenario("search-" + index,
                    1 + (index * 17 % 64), 2 + (index * 29 % 63),
                    0xD00D0000L + index, 0, 0, 0);
            List<WaypointGroup> groups = groups(scenario);
            int local = finalWire(V10DungeonBodyCodec.encode(
                    groups, V10DungeonBodyCodec.CoordinatePolicy.LOCAL_RAW)).length();
            int delta = finalWire(V10DungeonBodyCodec.encode(
                    groups, V10DungeonBodyCodec.CoordinatePolicy.FORCE_DELTA)).length();
            if (delta < local) {
                deltaWins++;
            } else if (local < delta) {
                localWins++;
            } else {
                ties++;
            }
        }
        assertEquals(0, deltaWins);
        assertEquals(200, localWins);
        assertEquals(0, ties);
    }

    @Test
    void finalWireBeatsExactWpdAcrossLockedDungeonWorkloads() throws Exception {
        List<Scenario> scenarios = List.of(
                new Scenario("small-party-pack", 4, 12, 0x51A11L, 1_449, 633, 659),
                new Scenario("floor-pack", 16, 16, 0xF7002L, 5_593, 1_659, 1_829),
                new Scenario("large-library", 64, 24, 0xC0DEC0DEL, 27_233, 5_026, 5_582));

        for (Scenario scenario : scenarios) {
            List<WaypointGroup> groups = groups(scenario);
            String wpd = DungeonRoomShareCodec.encode(groups);
            String v10 = UniversalShareCodec.encodeDungeon(groups);
            assertEquals(groups.stream().mapToInt(WaypointGroup::size).sum(),
                    ((UniversalShareCodec.DungeonRoutes) UniversalShareCodec.decode(v10))
                            .result().waypointCount());
            assertEquals(scenario.expectedWpdChars(), wpd.length(), scenario.name());
            assertEquals(scenario.expectedV10Chars(), v10.length(), scenario.name());
            assertEquals('B', v10.charAt(WaypointCodec.MAGIC.length()), scenario.name());
            assertTrue(v10.length() < wpd.length(), scenario.name());

            String local = finalWire(V10DungeonBodyCodec.encode(
                    groups, V10DungeonBodyCodec.CoordinatePolicy.LOCAL_RAW));
            String delta = finalWire(V10DungeonBodyCodec.encode(
                    groups, V10DungeonBodyCodec.CoordinatePolicy.FORCE_DELTA));
            String packed = finalWire(V10DungeonBodyCodec.encode(
                    groups, V10DungeonBodyCodec.CoordinatePolicy.PACK_WHEN_ELIGIBLE));
            assertEquals(scenario.expectedV10Chars(), local.length(), scenario.name());
            assertEquals(scenario.expectedDeltaChars(), delta.length(), scenario.name());
            assertEquals(v10, local, scenario.name());
            assertEquals(local, packed, scenario.name());
        }
    }

    private static String finalWire(byte[] semantic) throws Exception {
        List<String> candidates = new ArrayList<>();
        candidates.add(transport(0, seal(0, semantic)));
        candidates.add(transport(1,
                deflateAndSeal(semantic, Deflater.DEFAULT_STRATEGY)));
        candidates.add(transport(1,
                deflateAndSeal(semantic, Deflater.FILTERED)));
        return candidates.stream().min((left, right) -> {
            int compared = Integer.compare(left.length(), right.length());
            if (compared != 0) return compared;
            compared = Character.compare(left.charAt(WaypointCodec.MAGIC.length()),
                    right.charAt(WaypointCodec.MAGIC.length()));
            return compared != 0 ? compared : left.compareTo(right);
        }).orElseThrow();
    }

    private static String transport(int mode, byte[] payload) {
        return WaypointCodec.MAGIC + (mode == 0 ? 'A' : 'B')
                + escapeContextual(AsciiStreamCodec.encode(payload));
    }

    private static byte[] seal(int mode, byte[] semantic) {
        CRC32 crc = new CRC32();
        crc.update(mode);
        crc.update(semantic);
        long value = crc.getValue();
        byte[] sealed = Arrays.copyOf(semantic, semantic.length + 4);
        sealed[semantic.length] = (byte) (value >>> 24);
        sealed[semantic.length + 1] = (byte) (value >>> 16);
        sealed[semantic.length + 2] = (byte) (value >>> 8);
        sealed[semantic.length + 3] = (byte) value;
        return sealed;
    }

    private static byte[] deflate(byte[] input, int strategy) throws Exception {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setStrategy(strategy);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(output, deflater)) {
            stream.write(input);
        } finally {
            deflater.end();
        }
        return output.toByteArray();
    }

    private static byte[] deflateAndSeal(byte[] semantic, int strategy) throws Exception {
        byte[] sealed = seal(1, semantic);
        byte[] compressed = deflate(semantic, strategy);
        byte[] payload = Arrays.copyOf(compressed, compressed.length + Integer.BYTES);
        System.arraycopy(sealed, semantic.length, payload, compressed.length, Integer.BYTES);
        return payload;
    }

    private static String escapeContextual(String body) {
        StringBuilder output = new StringBuilder(body.length());
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            output.append(current);
            char following = index + 1 < body.length() ? body.charAt(index + 1) : '\0';
            if ((current == '<' && (following == '3' || following == '~'))
                    || (current == 'o' && (following == '/' || following == '~'))) {
                output.append('~');
            }
        }
        return output.toString();
    }

    private static List<WaypointGroup> groups(Scenario scenario) {
        String[] rooms = {"blood_room", "crypt", "three_weirdos", "water_board",
                "ice_fill", "bomb_defuse", "teleport_maze", "wither_key",
                "trap", "miniboss", "red_green", "tictactoe"};
        String[] names = {"Chest", "Lever", "Etherwarp", "Secret", "Wither Door", "Bat"};
        int[] colors = {0x55CCEE, 0x33AA55, 0xFFAA00, 0xAA55FF, 0xE64646, 0xF5F7FA};
        Random random = new Random(scenario.seed());
        List<WaypointGroup> groups = new ArrayList<>();
        for (int routeIndex = 0; routeIndex < scenario.routeCount(); routeIndex++) {
            String room = rooms[routeIndex % rooms.length].replace('_', '-') + "-" + routeIndex;
            WaypointGroup group = WaypointGroup.create("F7 " + rooms[routeIndex % rooms.length]
                    + " route " + (routeIndex + 1), room);
            group.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
            group.setLoadMode(routeIndex % 5 == 0
                    ? WaypointGroup.LoadMode.STATIC : WaypointGroup.LoadMode.SEQUENCE);
            group.setDefaultRadius(3.0 + (routeIndex % 4) * 0.25);
            group.setSkipAheadEnabled(routeIndex % 3 != 0);
            int x = (routeIndex % 8) * 32 - 96;
            int y = 64 + routeIndex % 7;
            int z = (routeIndex / 8) * 36 - 72;
            for (int waypointIndex = 0; waypointIndex < scenario.pointsPerRoute(); waypointIndex++) {
                x += random.nextInt(9) - 4;
                y += random.nextInt(3) - 1;
                z += random.nextInt(9) - 4;
                int flags = switch (waypointIndex % 6) {
                    case 0 -> Waypoint.FLAG_DUNGEON_SECRET | Waypoint.FLAG_SKIP_ON_INTERACT;
                    case 1 -> Waypoint.FLAG_DUNGEON_ETHERWARP | Waypoint.FLAG_SKIP_ON_STAND;
                    case 2 -> Waypoint.FLAG_DUNGEON_SUPERBOOM | Waypoint.FLAG_SKIP_ON_MINE;
                    case 3 -> Waypoint.FLAG_DUNGEON_ITEM;
                    case 4 -> Waypoint.FLAG_DUNGEON_BAT;
                    default -> Waypoint.FLAG_THROUGH_WALL;
                };
                double radius = waypointIndex % 7 == 0
                        ? 2.5 + (waypointIndex % 3) * 0.25 : 0.0;
                Waypoint waypoint = new Waypoint(x, y, z,
                        names[waypointIndex % names.length] + " " + (waypointIndex + 1),
                        colors[(routeIndex + waypointIndex) % colors.length], flags, radius);
                if (waypointIndex % 3 == 0) {
                    waypoint = waypoint.withPreciseSixteenths(
                            x * 16 + (waypointIndex * 5 + 3) % 16,
                            y * 16 + (waypointIndex * 7 + 12) % 16,
                            z * 16 + (waypointIndex * 11 + 5) % 16);
                }
                group.add(waypoint);
            }
            groups.add(group);
        }
        return groups;
    }

    private record Scenario(String name, int routeCount, int pointsPerRoute, long seed,
                            int expectedWpdChars, int expectedV10Chars,
                            int expectedDeltaChars) {}
}
