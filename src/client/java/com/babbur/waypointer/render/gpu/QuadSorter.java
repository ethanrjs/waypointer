package com.babbur.waypointer.render.gpu;

import java.util.Arrays;

final class QuadSorter {

    private QuadSorter() {}

    private static final int INSERTION_MOVES_PER_QUAD = 8;

    static boolean sortBackToFront(float[] centers, int[] order, float[] distances,
                                   long[] keys, int count, boolean reuseOrder,
                                   double cameraX, double cameraY, double cameraZ) {
        for (int i = 0; i < count; i++) {
            distances[i] = (float) squaredDistance(centers, i * 3, cameraX, cameraY, cameraZ);
        }
        if (reuseOrder) {
            if (isOrdered(order, distances, count)) return false;
            for (int i = 0; i < count; i++) keys[i] = sortKey(distances[order[i]], order[i]);
            if (!insertionSort(keys, count, (long) count * INSERTION_MOVES_PER_QUAD)) {
                Arrays.sort(keys, 0, count);
            }
        } else {
            for (int i = 0; i < count; i++) keys[i] = sortKey(distances[i], i);
            Arrays.sort(keys, 0, count);
        }
        for (int i = 0; i < count; i++) order[i] = (int) keys[i];
        return true;
    }

    private static boolean insertionSort(long[] keys, int count, long budget) {
        for (int i = 1; i < count; i++) {
            long key = keys[i];
            int j = i - 1;
            while (j >= 0 && keys[j] > key) {
                keys[j + 1] = keys[j];
                j--;
                budget--;
            }
            keys[j + 1] = key;
            if (budget < 0) return false;
        }
        return true;
    }

    private static long sortKey(float distance, int index) {
        return ((long) ~Float.floatToRawIntBits(distance) << 32) | index;
    }

    private static boolean isOrdered(int[] order, float[] distances, int count) {
        for (int i = 1; i < count; i++) {
            if (sortKey(distances[order[i - 1]], order[i - 1]) > sortKey(distances[order[i]], order[i])) {
                return false;
            }
        }
        return true;
    }

    private static double squaredDistance(float[] centers, int offset,
                                          double cameraX, double cameraY, double cameraZ) {
        double x = centers[offset] - cameraX;
        double y = centers[offset + 1] - cameraY;
        double z = centers[offset + 2] - cameraZ;
        return x * x + y * y + z * z;
    }
}
