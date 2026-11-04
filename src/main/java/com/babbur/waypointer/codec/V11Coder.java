package com.babbur.waypointer.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class V11Coder {

    private static final long MASK = 0xFFFFFFFFL;
    private static final long TOP = 1L << 24;
    private static final long BOTTOM = 1L << 16;
    private static final int ONE = 1 << 16;
    private static final int MIN_P = 48;
    private static final int MAX_P = ONE - MIN_P;
    private static final int MAX_RATE_LIMIT = 120;
    private static final int RATE_LIMIT = 60;
    private static final int[] RATE = new int[MAX_RATE_LIMIT + 1];

    static final int MAX_INPUTS = 8;
    private static final int MIX_RATE = 1;
    private static final int INITIAL_WEIGHT = 9000;
    private static final int[] STRETCH = new int[4096];
    private static final int[] SQUASH = new int[4095];

    static {
        for (int n = 0; n <= MAX_RATE_LIMIT; n++) RATE[n] = (int) Math.round(ONE / (n + 1.5));
        for (int p = 0; p < 4096; p++) {
            double q = Math.clamp(p, 1, 4095) / 4096.0;
            STRETCH[p] = (int) Math.round(256 * StrictMath.log(q / (1 - q)));
        }
        for (int x = -2047; x <= 2047; x++) {
            SQUASH[x + 2047] = Math.clamp((int) Math.round(4096 / (1 + StrictMath.exp(-x / 256.0))), 1, 4095);
        }
    }

    private static final long MAX_DECODE_BITS = 64L * 1024 * 1024;

    private final boolean encoding;
    private final int[] probability;
    private final byte[] seen;
    private final int[] weights;
    private final int[] stretched = new int[MAX_INPUTS];
    private final ByteArrayOutputStream out;
    private final byte[] in;
    private int position;
    private long low;
    private long range = MASK;
    private long code;
    private long bits;

    static java.util.Map<String, Double> costs;
    String part = "other";

    private V11Coder(boolean encoding, int contexts, int mixers, byte[] input) {
        this.encoding = encoding;
        this.probability = new int[contexts];
        this.seen = new byte[contexts];
        this.weights = new int[mixers * MAX_INPUTS];
        java.util.Arrays.fill(probability, ONE / 2);
        java.util.Arrays.fill(weights, INITIAL_WEIGHT);
        this.out = encoding ? new ByteArrayOutputStream() : null;
        this.in = input;
        if (!encoding) {
            for (int i = 0; i < 4; i++) code = ((code << 8) | nextByte()) & MASK;
        }
    }

    static V11Coder encoder(int contexts, int mixers) {
        return new V11Coder(true, contexts, mixers, null);
    }

    static V11Coder decoder(int contexts, int mixers, byte[] input) {
        return new V11Coder(false, contexts, mixers, input);
    }

    boolean encoding() {
        return encoding;
    }

    int[] probabilities() {
        return probability;
    }

    byte[] seen() {
        return seen;
    }

    int[] weights() {
        return weights;
    }

    void start(int[] probabilities, byte[] seenCounts, int[] mixerWeights, int confidence) {
        for (int i = 0; i < probability.length && i < probabilities.length; i++) {
            if (seenCounts[i] == 0) continue;
            probability[i] = probabilities[i];
            seen[i] = (byte) Math.min(seenCounts[i], confidence);
        }
        System.arraycopy(mixerWeights, 0, weights, 0, Math.min(weights.length, mixerWeights.length));
    }

    void prior(int context, double probabilityOfOne, int weight) {
        int p = (int) Math.round((1.0 - probabilityOfOne) * ONE);
        probability[context] = Math.clamp(p, MIN_P, MAX_P);
        seen[context] = (byte) Math.min(weight, RATE_LIMIT);
    }

    int bit(int context, int value) throws IOException {
        int bit = code(probability[context], value);
        update(context, bit);
        return bit;
    }

    boolean flag(int context, boolean value) throws IOException {
        return bit(context, value ? 1 : 0) == 1;
    }

    int raw(int value) throws IOException {
        return code(ONE / 2, value);
    }

    int mix(int[] contextIds, int count, int mixer, int value) throws IOException {
        int base = mixer * MAX_INPUTS;
        long dot = 0;
        for (int i = 0; i < count; i++) {
            stretched[i] = STRETCH[probability[contextIds[i]] >>> 4];
            dot += (long) weights[base + i] * stretched[i];
        }
        int p12 = squash((int) (dot >> 16));
        int bit = code(Math.clamp(p12 << 4, MIN_P, MAX_P), value);
        int error = ((bit == 0 ? 4095 : 0) - p12) * MIX_RATE;
        for (int i = 0; i < count; i++) {
            weights[base + i] += (stretched[i] * error) >> 10;
            update(contextIds[i], bit);
        }
        return bit;
    }

    private int code(int p0, int value) throws IOException {
        if (costs != null && encoding) {
            double p = p0 / (double) ONE;
            costs.merge(part, -Math.log((value & 1) == 0 ? p : 1 - p) / Math.log(2), Double::sum);
        }
        long bound = (range >>> 16) * p0;
        int bit;
        if (encoding) {
            bit = value & 1;
            if (bit == 0) {
                range = bound;
            } else {
                low = (low + bound) & MASK;
                range = (range - bound) & MASK;
            }
        } else {
            if (++bits > MAX_DECODE_BITS) throw new IOException("v11 share is too complex to decode");
            if (((code - low) & MASK) < bound) {
                bit = 0;
                range = bound;
            } else {
                bit = 1;
                low = (low + bound) & MASK;
                range = (range - bound) & MASK;
            }
        }
        renormalize();
        return bit;
    }

    private void update(int context, int bit) {
        int p = probability[context];
        int n = seen[context];
        int target = bit == 0 ? ONE : 0;
        p += (int) (((long) (target - p) * RATE[n]) >> 16);
        probability[context] = Math.clamp(p, MIN_P, MAX_P);
        if (n < RATE_LIMIT) seen[context] = (byte) (n + 1);
    }

    private static int squash(int x) {
        if (x > 2047) return 4095;
        if (x < -2047) return 1;
        return SQUASH[x + 2047];
    }

    long rawBits(int count, long value) throws IOException {
        long result = 0;
        for (int i = count - 1; i >= 0; i--) {
            result = result << 1 | raw((int) (value >>> i) & 1);
        }
        return result;
    }

    private void renormalize() {
        while (true) {
            if ((low ^ (low + range)) < TOP) {

            } else if (range < BOTTOM) {
                range = (-low) & (BOTTOM - 1L);
            } else {
                break;
            }
            if (encoding) {
                out.write((int) ((low >>> 24) & 0xFF));
            } else {
                code = ((code << 8) | nextByte()) & MASK;
            }
            low = (low << 8) & MASK;
            range = (range << 8) & MASK;
        }
    }

    private int nextByte() {
        int value = position < in.length ? in[position] & 0xFF : 0;
        position++;
        return value;
    }

    byte[] finish() {
        for (int count = 0; count <= 4; count++) {
            int shift = 8 * (4 - count);
            long candidate = shift == 0 ? low : ((low + ((1L << shift) - 1L)) >>> shift) << shift;
            if (((candidate - low) & MASK) < range && candidate <= MASK) {
                for (int i = 0; i < count; i++) out.write((int) ((candidate >>> (24 - 8 * i)) & 0xFF));
                return trimTrailingZeros(out.toByteArray());
            }
        }
        for (int i = 0; i < 4; i++) out.write((int) ((low >>> (24 - 8 * i)) & 0xFF));
        return trimTrailingZeros(out.toByteArray());
    }

    private static byte[] trimTrailingZeros(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) end--;
        return java.util.Arrays.copyOf(bytes, end);
    }
}
