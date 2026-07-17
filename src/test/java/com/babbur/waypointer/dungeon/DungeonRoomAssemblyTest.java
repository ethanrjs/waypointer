package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonRoomAssemblyTest {

    private static final int STEP = DungeonMapMath.SEGMENT_BLOCKS;

    @Test
    void everySegmentLoadOrderProducesOneCompleteAssemblyForSupportedRoomShapes() throws Exception {
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
    void bridgeDoesNotMergeAssembliesIntoAnIncompatibleRoomShape() throws Exception {
        DungeonStateTracker tracker = new DungeonStateTracker(new ActiveGroupManager(), new DungeonConfig());
        DungeonRoomDefinition definition = definition(DungeonRoomShape.ONE_BY_THREE);

        attach(tracker, definition, segment(0, 0));
        attach(tracker, definition, segment(2, 0));
        attach(tracker, definition, segment(1, 1));
        attach(tracker, definition, segment(1, 0));

        assertEquals(3, assemblies(tracker, definition.id()).size());
    }

    private static void assertEveryLoadOrderAssembles(
            DungeonRoomShape shape,
            List<Long> expectedSegments) throws Exception {
        for (List<Long> loadOrder : permutations(expectedSegments)) {
            DungeonStateTracker tracker = new DungeonStateTracker(
                    new ActiveGroupManager(),
                    new DungeonConfig());
            DungeonRoomDefinition definition = definition(shape);
            for (long segment : loadOrder) attach(tracker, definition, segment);

            List<?> assemblies = assemblies(tracker, definition.id());
            assertEquals(1, assemblies.size(), shape + " failed for load order " + loadOrder);
            assertEquals(
                    new HashSet<>(expectedSegments),
                    assemblySegments(assemblies.getFirst()),
                    shape + " lost segments for load order " + loadOrder);
        }
    }

    private static DungeonRoomDefinition definition(DungeonRoomShape shape) {
        return new DungeonRoomDefinition(
                "test-" + shape.name().toLowerCase(),
                "Test",
                DungeonRoomType.ROOM,
                shape,
                List.of(),
                List.of(),
                List.of());
    }

    private static long segment(int gridX, int gridZ) {
        return DungeonRoom.packSegment(gridX * STEP, gridZ * STEP);
    }

    private static void attach(
            DungeonStateTracker tracker,
            DungeonRoomDefinition definition,
            long segment) throws Exception {
        Method method = DungeonStateTracker.class.getDeclaredMethod(
                "attachScannedSegment",
                DungeonRoomDefinition.class,
                long.class,
                int.class);
        method.setAccessible(true);
        method.invoke(tracker, definition, segment, 70);
    }

    @SuppressWarnings("unchecked")
    private static List<?> assemblies(DungeonStateTracker tracker, String definitionId) throws Exception {
        Field field = DungeonStateTracker.class.getDeclaredField("assembliesByDefinition");
        field.setAccessible(true);
        Map<String, List<?>> byDefinition = (Map<String, List<?>>) field.get(tracker);
        return byDefinition.getOrDefault(definitionId, List.of());
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> assemblySegments(Object assembly) throws Exception {
        Field field = assembly.getClass().getDeclaredField("segments");
        field.setAccessible(true);
        return Set.copyOf((Set<Long>) field.get(assembly));
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
