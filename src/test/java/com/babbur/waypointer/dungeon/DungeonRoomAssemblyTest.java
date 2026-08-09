package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.dungeon.data.DungeonRoomCatalogEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonRoomAssemblyTest {

    private static final int STEP = DungeonMapMath.SEGMENT_BLOCKS;

    @Test
    void everySegmentLoadOrderProducesOneCompleteAssemblyForSupportedRoomShapes() {
        assertEveryLoadOrderAssembles(DungeonRoomShape.ONE_BY_TWO, List.of(
                segment(0, 0), segment(1, 0)));
        assertEveryLoadOrderAssembles(DungeonRoomShape.ONE_BY_THREE, List.of(
                segment(0, 0), segment(1, 0), segment(2, 0)));
        assertEveryLoadOrderAssembles(DungeonRoomShape.ONE_BY_FOUR, List.of(
                segment(0, 0), segment(1, 0), segment(2, 0), segment(3, 0)));
        assertEveryLoadOrderAssembles(DungeonRoomShape.TWO_BY_TWO, List.of(
                segment(0, 0), segment(1, 0), segment(0, 1), segment(1, 1)));
        assertEveryLoadOrderAssembles(DungeonRoomShape.L_SHAPE, List.of(
                segment(0, 0), segment(1, 0), segment(2, 0), segment(0, 1)));
    }

    @Test
    void bridgeDoesNotMergeAssembliesIntoAnIncompatibleRoomShape() {
        DungeonRoomResolver resolver = new DungeonRoomResolver();
        DungeonRoomCatalogEntry definition = definition(DungeonRoomShape.ONE_BY_THREE);

        attach(resolver, definition, segment(0, 0));
        attach(resolver, definition, segment(2, 0));
        attach(resolver, definition, segment(1, 1));
        attach(resolver, definition, segment(1, 0));

        assertEquals(3, resolver.assemblies(definition.id()).size());
    }

    private static void assertEveryLoadOrderAssembles(
            DungeonRoomShape shape,
            List<Long> expectedSegments) {
        for (List<Long> loadOrder : permutations(expectedSegments)) {
            DungeonRoomResolver resolver = new DungeonRoomResolver();
            DungeonRoomCatalogEntry definition = definition(shape);
            for (long segment : loadOrder) attach(resolver, definition, segment);

            List<DungeonRoomResolver.RoomAssembly> assemblies = resolver.assemblies(definition.id());
            assertEquals(1, assemblies.size(), shape + " failed for load order " + loadOrder);
            assertEquals(
                    new HashSet<>(expectedSegments),
                    assemblies.getFirst().segments(),
                    shape + " lost segments for load order " + loadOrder);
        }
    }

    private static DungeonRoomCatalogEntry definition(DungeonRoomShape shape) {
        return new DungeonRoomCatalogEntry(
                "test-" + shape.name().toLowerCase(),
                "Test",
                DungeonRoomType.ROOM,
                shape,
                List.of(),
                List.of(),
                -1, -1, -1);
    }

    private static long segment(int gridX, int gridZ) {
        return DungeonRoom.packSegment(gridX * STEP, gridZ * STEP);
    }

    private static void attach(
            DungeonRoomResolver resolver,
            DungeonRoomCatalogEntry definition,
            long segment) {
        resolver.attachScannedSegment(definition, segment, 70);
    }

    private static List<List<Long>> permutations(List<Long> values) {
        List<List<Long>> output = new ArrayList<>();
        permute(new ArrayList<>(values), 0, output);
        return output;
    }

    private static void permute(List<Long> values, int index, List<List<Long>> output) {
        if (index == values.size()) {
            output.add(List.copyOf(values));
            return;
        }
        for (int swapIndex = index; swapIndex < values.size(); swapIndex++) {
            long value = values.get(index);
            values.set(index, values.get(swapIndex));
            values.set(swapIndex, value);
            permute(values, index + 1, output);
            value = values.get(index);
            values.set(index, values.get(swapIndex));
            values.set(swapIndex, value);
        }
    }
}
