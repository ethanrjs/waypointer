package com.babbur.waypointer.render.gpu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawScheduleTest {

    @Test
    void fewPrimitivesInterleaveExactlyFromFarToNear() {
        List<ArrayStream> streams = List.of(new ArrayStream(9, 4, 1), new ArrayStream(16, 5, 0.5));
        List<Double> drawn = drawnDistances(streams, new int[1]);

        assertEquals(List.of(16.0, 9.0, 5.0, 4.0, 1.0, 0.5), drawn);
    }

    @Test
    void manyInterleavedPrimitivesStayWithinABoundedNumberOfDraws() {
        Random random = new Random(42);
        List<ArrayStream> streams = new ArrayList<>();
        for (int stream = 0; stream < 8; stream++) streams.add(ArrayStream.random(random, 2_000, 250));
        int[] draws = {0};
        List<Double> drawn = drawnDistances(streams, draws);

        assertEquals(16_000, drawn.size(), "every primitive is drawn exactly once");
        assertTrue(draws[0] < 300, "draw calls: " + draws[0]);
    }

    @Test
    void nothingIsDrawnAfterAPrimitiveMuchNearerThanItself() {
        Random random = new Random(7);
        List<ArrayStream> streams = new ArrayList<>();
        for (int stream = 0; stream < 6; stream++) streams.add(ArrayStream.random(random, 700, 300));
        List<Double> drawn = drawnDistances(streams, new int[1]);

        double nearestSoFar = Double.POSITIVE_INFINITY;
        for (double squared : drawn) {
            assertTrue(squared <= nearestSoFar * 2, squared + " drawn after " + nearestSoFar);
            nearestSoFar = Math.min(nearestSoFar, squared);
        }
        double exactLimit = DrawSchedule.exactLimit(streams, DrawSchedule.EXACT_NEAREST_PRIMITIVES);
        List<Double> near = drawn.stream().filter(distance -> distance <= exactLimit).toList();
        List<Double> sorted = new ArrayList<>(near);
        sorted.sort((left, right) -> Double.compare(right, left));
        assertEquals(sorted, near, "the nearest primitives keep exact far-to-near order");
    }

    private static List<Double> drawnDistances(List<ArrayStream> streams, int[] draws) {
        List<Double> drawn = new ArrayList<>();
        DrawSchedule.plan(streams, (stream, start, end) -> {
            assertTrue(end > start);
            draws[0]++;
            for (int index = start; index < end; index++) drawn.add(stream.primitiveDistance(index));
        });
        return drawn;
    }

    private record ArrayStream(double... distances) implements DrawSchedule.Stream {

        @Override
        public int bandOrder() {
            return 0;
        }

        static ArrayStream random(Random random, int count, double maxDistance) {
            double[] distances = new double[count];
            for (int i = 0; i < count; i++) {
                double distance = random.nextDouble() * maxDistance;
                distances[i] = distance * distance;
            }
            Arrays.sort(distances);
            for (int i = 0; i < count / 2; i++) {
                double swap = distances[i];
                distances[i] = distances[count - 1 - i];
                distances[count - 1 - i] = swap;
            }
            return new ArrayStream(distances);
        }

        @Override
        public int primitiveCount() {
            return distances.length;
        }

        @Override
        public double primitiveDistance(int index) {
            return distances[index];
        }
    }
}
