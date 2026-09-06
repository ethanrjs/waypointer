package com.babbur.waypointer.render.gpu;

/** Sorts quad centers from farthest to nearest without per-frame allocation. */
final class QuadSorter {

    private QuadSorter() {}

    /** Returns false when the previous order is still exact and needs no index upload. */
    static boolean sortBackToFront(float[] centers, int[] order, double[] distances,
                                int[] scratch, int count, boolean reuseOrder,
                                double cameraX, double cameraY, double cameraZ) {
        for (int i = 0; i < count; i++) {
            if (!reuseOrder) order[i] = i;
            distances[i] = squaredDistance(centers, i * 3, cameraX, cameraY, cameraZ);
        }
        if (reuseOrder) {
            boolean ordered = true;
            for (int i = 1; i < count; i++) {
                if (compare(order[i - 1], order[i], distances) > 0) {
                    ordered = false;
                    break;
                }
            }
            if (ordered) return false;
        }
        if (count < 2) return true;

        int[] source = order;
        int[] target = scratch;
        for (int width = 1; width < count; width = nextWidth(width, count)) {
            for (int start = 0; start < count; start += width * 2) {
                int middle = Math.min(start + width, count);
                int end = Math.min(start + width * 2, count);
                int left = start;
                int right = middle;
                for (int output = start; output < end; output++) {
                    if (right >= end || left < middle
                            && compare(source[left], source[right], distances) <= 0) {
                        target[output] = source[left++];
                    } else {
                        target[output] = source[right++];
                    }
                }
            }
            int[] swap = source;
            source = target;
            target = swap;
        }
        if (source != order) System.arraycopy(source, 0, order, 0, count);
        return true;
    }

    private static int nextWidth(int width, int count) {
        return width > count / 2 ? count : width * 2;
    }

    private static int compare(int left, int right, double[] distances) {
        int distanceOrder = Double.compare(distances[right], distances[left]);
        return distanceOrder != 0 ? distanceOrder : Integer.compare(left, right);
    }

    private static double squaredDistance(float[] centers, int offset,
                                          double cameraX, double cameraY, double cameraZ) {
        double x = centers[offset] - cameraX;
        double y = centers[offset + 1] - cameraY;
        double z = centers[offset + 2] - cameraZ;
        return x * x + y * y + z * z;
    }
}
