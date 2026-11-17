package com.babbur.waypointer.crystal;

import com.babbur.waypointer.core.Waypoint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairyGrottoLayoutTest {

    private static final CrystalHollowsPosition RECORDED = new CrystalHollowsPosition(300, 100, 300);

    @Test
    void placementTurnsBlockCentresOntoBlockCentres() {
        Waypoint local = new Waypoint(3, -2, 5, "glass", 0xFFFFFF, 0, 0.0);
        for (int turns = 0; turns < 4; turns++) {
            StructurePlacement placement = new StructurePlacement(new CrystalHollowsPosition(500, 80, 600), turns);
            Waypoint world = placement.toWorld(local);
            CrystalHollowsPosition block = placement.blockToWorld(3, -2, 5);
            assertEquals(block.x(), Math.floorDiv(world.preciseX(), Waypoint.PRECISE_SCALE));
            assertEquals(block.y(), Math.floorDiv(world.preciseY(), Waypoint.PRECISE_SCALE));
            assertEquals(block.z(), Math.floorDiv(world.preciseZ(), Waypoint.PRECISE_SCALE));
            assertEquals(local.preciseX(), placement.toLocal(world).preciseX());
            assertEquals(local.preciseZ(), placement.toLocal(world).preciseZ());
        }
    }

    @Test
    void quarterTurnIsClockwiseSeenFromAbove() {
        StructurePlacement turned = new StructurePlacement(new CrystalHollowsPosition(0, 0, 0), 1);
        assertEquals(new CrystalHollowsPosition(0, 0, 4), turned.blockToWorld(4, 0, 0),
                "east turns to south");
    }

    @Test
    void findsTheGrottoInAnotherLobbyAtAnyTurn() {
        Set<Long> recorded = grotto(RECORDED, 0, 0);
        FairyGrottoLayout layout = FairyGrottoLayout.capture(RECORDED, recorded);
        assertNotNull(layout);

        for (int turns = 0; turns < 4; turns++) {
            StructurePlacement truth = new StructurePlacement(new CrystalHollowsPosition(612, 54, 241), turns);
            Set<Long> world = place(layout, truth);
            world.addAll(noise(new CrystalHollowsPosition(612, 54, 241), 40, 7));
            var match = layout.match(world, truth.pivot());
            assertTrue(match.isPresent(), "turns " + turns);
            assertEquals(truth, match.orElseThrow().placement());
        }
    }

    @Test
    void stillMatchesAfterMostOfTheGlassIsMined() {
        FairyGrottoLayout layout = FairyGrottoLayout.capture(RECORDED, grotto(RECORDED, 0, 0));
        StructurePlacement truth = new StructurePlacement(new CrystalHollowsPosition(400, 90, 700), 2);
        List<Long> world = new ArrayList<>(place(layout, truth));
        world.subList(0, world.size() * 6 / 10).clear();
        assertEquals(truth, layout.match(new HashSet<>(world), truth.pivot()).orElseThrow().placement());
    }

    @Test
    void ignoresAnotherGrottoRoom() {
        FairyGrottoLayout layout = FairyGrottoLayout.capture(RECORDED, grotto(RECORDED, 0, 0));
        Set<Long> otherRoom = grotto(new CrystalHollowsPosition(500, 100, 500), 0, 99);
        assertTrue(layout.match(otherRoom, null).isEmpty());
    }

    @Test
    void picksTheNearestOfTwoMatchingGrottos() {
        FairyGrottoLayout layout = FairyGrottoLayout.capture(RECORDED, grotto(RECORDED, 0, 0));
        StructurePlacement far = new StructurePlacement(new CrystalHollowsPosition(200, 100, 200), 0);
        StructurePlacement near = new StructurePlacement(new CrystalHollowsPosition(260, 100, 200), 3);
        Set<Long> world = place(layout, far);
        world.addAll(place(layout, near));
        assertEquals(near, layout.match(world, new CrystalHollowsPosition(258, 100, 199))
                .orElseThrow().placement());
    }

    @Test
    void tooLittleGlassRecordsNoLayout() {
        Set<Long> few = new HashSet<>();
        for (int i = 0; i < FairyGrottoLayout.MIN_CELLS - 1; i++) few.add(FairyGrottoLayout.pack(i, 100, 0));
        assertNull(FairyGrottoLayout.capture(RECORDED, few));
    }

    @Test
    void storeKeepsLastPlacements() throws Exception {
        Path file = Files.createTempDirectory("grotto").resolve("fairy-grottos.json");
        FairyGrottoStore store = new FairyGrottoStore(file);
        StructurePlacement placement = new StructurePlacement(new CrystalHollowsPosition(1, 2, 3), 1);
        store.putLastPlacement("route-1", "mini42A", placement);

        FairyGrottoStore reloaded = new FairyGrottoStore(file);
        reloaded.load();
        assertEquals(new FairyGrottoStore.LastPlacement("mini42A", placement), reloaded.lastPlacement("route-1"));
        assertNull(reloaded.lastPlacement("unknown"));
    }

    @Test
    void layoutTextRoundTripsAndStaysShort() {
        FairyGrottoLayout layout = FairyGrottoLayout.capture(RECORDED, grotto(RECORDED, 0, 0));
        String text = layout.toText();
        FairyGrottoLayout parsed = FairyGrottoLayout.fromText(text);
        assertNotNull(parsed);
        assertEquals(text, parsed.toText());
        assertTrue(java.util.Arrays.equals(layout.offsets(), parsed.offsets()));
        assertTrue(text.length() < 260, text.length() + " chars");
        assertNull(FairyGrottoLayout.fromText(text + "!"));
        assertNull(FairyGrottoLayout.fromText("1.gAA"), "a padded varint is not the canonical spelling");
    }

    @Test
    void bigGrottosKeepAnEvenSpreadOfCells() {
        Set<Long> glass = new HashSet<>();
        for (int x = -30; x <= 30; x++) for (int z = -30; z <= 30; z++) glass.add(FairyGrottoLayout.pack(300 + x, 100, 300 + z));
        FairyGrottoLayout layout = FairyGrottoLayout.capture(RECORDED, glass);
        assertEquals(FairyGrottoLayout.MAX_CELLS, layout.size());
        int[] offsets = layout.offsets();
        int farthest = 0;
        for (int i = 0; i < offsets.length; i += 3) farthest = Math.max(farthest, Math.abs(offsets[i]));
        assertTrue(farthest > 20, "cells reach the edge of the grotto, not just the middle");
    }

    @Test
    void packRoundTripsNegativeOffsets() {
        long packed = FairyGrottoLayout.pack(-37, -5, 1023);
        assertEquals(-37, FairyGrottoLayout.unpackX(packed));
        assertEquals(-5, FairyGrottoLayout.unpackY(packed));
        assertEquals(1023, FairyGrottoLayout.unpackZ(packed));
    }

    /** A made-up room: an irregular blob of glass around {@code origin}, seeded per room. */
    private static Set<Long> grotto(CrystalHollowsPosition origin, int turns, long seed) {
        Random random = new Random(seed);
        StructurePlacement placement = new StructurePlacement(origin, turns);
        Set<Long> glass = new HashSet<>();
        while (glass.size() < 90) {
            CrystalHollowsPosition block = placement.blockToWorld(
                    random.nextInt(25) - 12, random.nextInt(9) - 4, random.nextInt(25) - 12);
            glass.add(FairyGrottoLayout.pack(block.x(), block.y(), block.z()));
        }
        return glass;
    }

    private static Set<Long> place(FairyGrottoLayout layout, StructurePlacement placement) {
        Set<Long> world = new HashSet<>();
        int[] offsets = layout.offsets();
        for (int i = 0; i < offsets.length; i += 3) {
            CrystalHollowsPosition block = placement.blockToWorld(offsets[i], offsets[i + 1], offsets[i + 2]);
            world.add(FairyGrottoLayout.pack(block.x(), block.y(), block.z()));
        }
        return world;
    }

    private static Set<Long> noise(CrystalHollowsPosition around, int count, long seed) {
        Random random = new Random(seed);
        Set<Long> glass = new HashSet<>();
        while (glass.size() < count) {
            glass.add(FairyGrottoLayout.pack(around.x() + random.nextInt(81) - 40,
                    around.y() + random.nextInt(41) - 20, around.z() + random.nextInt(81) - 40));
        }
        return glass;
    }
}
