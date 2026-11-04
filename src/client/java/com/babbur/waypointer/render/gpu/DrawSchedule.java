package com.babbur.waypointer.render.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

final class DrawSchedule {
    static final int EXACT_NEAREST_PRIMITIVES = 96;

    private static final double BAND_RATIO = 2.0;
    private static final double MIN_BAND_FLOOR = 0.25;

    interface Stream {
        int primitiveCount();

        double primitiveDistance(int index);

        int bandOrder();
    }

    @FunctionalInterface
    interface BatchConsumer<S extends Stream> {
        void draw(S stream, int start, int end);
    }

    private DrawSchedule() {}

    static <S extends Stream> void plan(List<S> streams, BatchConsumer<S> out) {
        double exactLimit = exactLimit(streams, EXACT_NEAREST_PRIMITIVES);
        PriorityQueue<Cursor<S>> queue = new PriorityQueue<>(Math.max(1, streams.size()),
                Comparator.comparingDouble((Cursor<S> cursor) -> cursor.distance()).reversed()
                        .thenComparingInt(cursor -> cursor.tieOrder));
        for (S stream : streams) {
            if (stream.primitiveCount() > 0) queue.add(new Cursor<>(stream, queue.size()));
        }
        List<Cursor<S>> band = new ArrayList<>(streams.size());
        Comparator<Cursor<S>> bandOrder = Comparator.comparingInt((Cursor<S> cursor) -> cursor.stream.bandOrder())
                .thenComparingInt(cursor -> cursor.tieOrder);
        while (!queue.isEmpty()) {
            double head = queue.peek().distance();
            if (head > exactLimit) {
                double floor = bandFloor(head, exactLimit);
                while (!queue.isEmpty() && queue.peek().distance() >= floor) band.add(queue.remove());
                band.sort(bandOrder);
                for (Cursor<S> cursor : band) {
                    int end = batchEnd(cursor.stream, cursor.index, floor);
                    out.draw(cursor.stream, cursor.index, end);
                    cursor.index = end;
                    if (end < cursor.stream.primitiveCount()) queue.add(cursor);
                }
                band.clear();
                continue;
            }
            Cursor<S> cursor = queue.remove();
            double bound = queue.isEmpty() ? Double.NEGATIVE_INFINITY : queue.peek().distance();
            int end = batchEnd(cursor.stream, cursor.index, bound);
            out.draw(cursor.stream, cursor.index, end);
            cursor.index = end;
            if (end < cursor.stream.primitiveCount()) queue.add(cursor);
        }
    }

    static double exactLimit(List<? extends Stream> streams, int nearest) {
        int total = 0;
        for (Stream stream : streams) total += Math.min(nearest, stream.primitiveCount());
        if (total <= nearest) return Double.POSITIVE_INFINITY;
        double[] distances = new double[total];
        int next = 0;
        for (Stream stream : streams) {
            int count = stream.primitiveCount();
            for (int index = Math.max(0, count - nearest); index < count; index++) {
                distances[next++] = stream.primitiveDistance(index);
            }
        }
        Arrays.sort(distances);
        return distances[nearest - 1];
    }

    static double bandFloor(double distance, double exactLimit) {
        double floor = Math.min(Math.max(exactLimit, MIN_BAND_FLOOR), distance);
        while (floor * BAND_RATIO <= distance) floor *= BAND_RATIO;
        return floor;
    }

    private static int batchEnd(Stream stream, int start, double bound) {
        int count = stream.primitiveCount();
        if (bound == Double.NEGATIVE_INFINITY) return count;
        int end = start;
        while (end < count && stream.primitiveDistance(end) >= bound) end++;
        return end;
    }

    private static final class Cursor<S extends Stream> {
        final S stream;
        final int tieOrder;
        int index;

        Cursor(S stream, int tieOrder) {
            this.stream = stream;
            this.tieOrder = tieOrder;
        }

        double distance() {
            return stream.primitiveDistance(index);
        }
    }
}
