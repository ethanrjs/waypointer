package com.babbur.waypointer.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Direct-A entropy descriptors for V10 kind-2 coordinate bodies. */
final class V10BareEntropyCodec {

    static final int MAX_QUOTIENT_WAYPOINTS = 1_024;
    static final long MAX_COMBINATION_OPERATIONS = 200_000;
    static final long MAX_BIG_INTEGER_BIT_WORK = 256_000_000;
    static final int MAX_QUOTIENT_K = 28;

    private static final int CONSTANT_AXIS = -1;
    private V10BareEntropyCodec() {}

    enum DirectDescriptor {
        RICE,
        RESERVED_GOLOMB,
        QUOTIENT
    }

    /** Inspect the reserved ten-bit descriptor without trial-decoding bodies. */
    static DirectDescriptor descriptor(byte[] semantic) throws IOException {
        V10BareRouteCodec.ByteReader reader = new V10BareRouteCodec.ByteReader(semantic);
        V10BareRouteCodec.Prefix prefix = V10BareRouteCodec.decodePrefix(reader);
        if (prefix.count() <= 1) return DirectDescriptor.RICE;
        V10BareRouteCodec.RiceBitReader bits = new V10BareRouteCodec.RiceBitReader(
                reader.remainingBytes(), 0);
        if (bits.readBits(2) != 3) return DirectDescriptor.RICE;
        if (bits.readBits(3) != 7) return DirectDescriptor.RICE;
        return switch (bits.readBits(5)) {
            case 0 -> DirectDescriptor.RESERVED_GOLOMB;
            case 1 -> DirectDescriptor.QUOTIENT;
            default -> DirectDescriptor.RICE;
        };
    }

    static byte[] encodeQuotient(int[][] coordinates) throws IOException {
        if (coordinates.length > MAX_QUOTIENT_WAYPOINTS) {
            throw new IllegalArgumentException("v10 quotient route exceeds 1024-waypoint limit");
        }
        WorkBudget budget = new WorkBudget();
        ByteArrayOutputStream output = V10BareRouteCodec.semanticPrefix(coordinates);
        if (coordinates.length <= 1) return output.toByteArray();
        int[][] axes = V10BareRouteCodec.deltaAxes(coordinates);
        int[] parameters = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            parameters[axis] = V10BareRouteCodec.isAllZero(axes[axis])
                    ? CONSTANT_AXIS : chooseQuotientK(axes[axis], budget);
        }
        V10BareRouteCodec.RiceBitWriter bits = new V10BareRouteCodec.RiceBitWriter();
        bits.writeBits(3, 2);
        bits.writeBits(7, 3);
        bits.writeBits(1, 5);
        for (int parameter : parameters) writeRiceParameter(bits, parameter);
        for (int axis = 0; axis < 3; axis++) {
            int parameter = parameters[axis];
            if (parameter == CONSTANT_AXIS) continue;
            writeQuotientAxis(bits, axes[axis], parameter, budget);
        }
        output.writeBytes(bits.finish());
        return V10BareRouteCodec.requireSemanticLimit(output.toByteArray(), "quotient");
    }

    static int[][] decodeQuotient(byte[] semantic) throws IOException {
        WorkBudget budget = new WorkBudget();
        V10BareRouteCodec.ByteReader reader = new V10BareRouteCodec.ByteReader(semantic);
        V10BareRouteCodec.Prefix prefix = V10BareRouteCodec.decodePrefix(reader);
        int count = prefix.count();
        if (count <= 1) throw new IOException("v10 quotient descriptor is redundant");
        // Normative safety gate: before marker, cardinality, rank, or block work.
        if (count > MAX_QUOTIENT_WAYPOINTS) {
            throw new IOException("v10 quotient route exceeds 1024-waypoint limit");
        }
        int deltaCount = count - 1;
        V10BareRouteCodec.RiceBitReader bits = new V10BareRouteCodec.RiceBitReader(
                reader.remainingBytes(), 90L * deltaCount + 128);
        if (bits.readBits(2) != 3 || bits.readBits(3) != 7 || bits.readBits(5) != 1) {
            throw new IOException("v10 quotient descriptor mismatch");
        }
        int[] parameters = new int[3];
        for (int axis = 0; axis < 3; axis++) parameters[axis] = readRiceParameter(bits);
        int[][] axes = new int[3][deltaCount];
        UnaryBudget unary = new UnaryBudget(90L * deltaCount + 128);
        for (int axis = 0; axis < 3; axis++) {
            if (parameters[axis] != CONSTANT_AXIS) {
                axes[axis] = readQuotientAxis(bits, deltaCount, parameters[axis], budget, unary);
            }
        }
        bits.requireZeroPadding();
        int[][] coordinates = reconstruct(prefix.first(), axes);
        if (!Arrays.equals(semantic, encodeQuotientWithBudget(coordinates, budget))) {
            throw new IOException("non-canonical v10 quotient semantic body");
        }
        return coordinates;
    }

    private static byte[] encodeQuotientWithBudget(int[][] coordinates, WorkBudget budget)
            throws IOException {
        ByteArrayOutputStream output = V10BareRouteCodec.semanticPrefix(coordinates);
        int[][] axes = V10BareRouteCodec.deltaAxes(coordinates);
        int[] parameters = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            parameters[axis] = V10BareRouteCodec.isAllZero(axes[axis])
                    ? CONSTANT_AXIS : chooseQuotientK(axes[axis], budget);
        }
        V10BareRouteCodec.RiceBitWriter bits = new V10BareRouteCodec.RiceBitWriter();
        bits.writeBits(3, 2);
        bits.writeBits(7, 3);
        bits.writeBits(1, 5);
        for (int parameter : parameters) writeRiceParameter(bits, parameter);
        for (int axis = 0; axis < 3; axis++) {
            if (parameters[axis] != CONSTANT_AXIS) {
                writeQuotientAxis(bits, axes[axis], parameters[axis], budget);
            }
        }
        output.writeBytes(bits.finish());
        return V10BareRouteCodec.requireSemanticLimit(output.toByteArray(), "quotient");
    }

    private static void writeQuotientAxis(V10BareRouteCodec.RiceBitWriter bits,
                                          int[] values, int parameter,
                                          WorkBudget budget) throws IOException {
        int presentCount = 0;
        for (int value : values) if ((value >>> parameter) != 0) presentCount++;
        boolean majorityOnes = presentCount > values.length - presentCount;
        List<Integer> minority = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            if (((values[index] >>> parameter) != 0) != majorityOnes) minority.add(index);
        }
        bits.writeBit(majorityOnes ? 1 : 0);
        writeGamma(bits, minority.size() + 1);
        BigInteger rank = colexRank(minority, budget);
        int width = combinationWidth(values.length, minority.size(), budget);
        budget.charge(width, width);
        writeMsb(bits, rank, width);
        if (parameter != 0) {
            int mask = (1 << parameter) - 1;
            for (int value : values) bits.writeBits(value & mask, parameter);
        }
        for (int value : values) {
            int quotient = value >>> parameter;
            if (quotient == 0) continue;
            for (int index = 1; index < quotient; index++) bits.writeBit(0);
            bits.writeBit(1);
        }
    }

    private static int[] readQuotientAxis(V10BareRouteCodec.RiceBitReader bits,
                                          int count, int parameter,
                                          WorkBudget budget, UnaryBudget unary)
            throws IOException {
        boolean majorityOnes = bits.readBit() == 1;
        int minorityCount = readGamma(bits, count + 1) - 1;
        if (minorityCount > count / 2) {
            throw new IOException("non-canonical v10 quotient minority cardinality");
        }
        if ((count & 1) == 0 && minorityCount == count / 2 && majorityOnes) {
            throw new IOException("non-canonical v10 quotient majority tie");
        }
        int width = combinationWidth(count, minorityCount, budget);
        BigInteger rank = readMsb(bits, width, budget);
        int[] minorityPositions = colexUnrank(count, minorityCount, rank, budget);
        boolean[] minority = new boolean[count];
        for (int position : minorityPositions) minority[position] = true;
        int[] remainders = new int[count];
        for (int index = 0; index < count; index++) {
            remainders[index] = parameter == 0 ? 0 : bits.readBits(parameter);
        }
        int[] output = new int[count];
        for (int index = 0; index < count; index++) {
            boolean present = majorityOnes ? !minority[index] : minority[index];
            if (!present) {
                output[index] = remainders[index];
                continue;
            }
            int quotient = unary.read(bits, V10BareRouteCodec.MAX_ZIGZAG_DELTA >>> parameter,
                    "v10 quotient unary work exceeds count-derived limit") + 1;
            long value = ((long) quotient << parameter) | remainders[index];
            if (value > V10BareRouteCodec.MAX_ZIGZAG_DELTA) {
                throw new IOException("v10 quotient value exceeds coordinate model");
            }
            output[index] = (int) value;
        }
        return output;
    }

    private static int chooseQuotientK(int[] values, WorkBudget budget) throws IOException {
        int bestParameter = 0;
        long bestLength = Long.MAX_VALUE;
        for (int parameter = 0; parameter <= MAX_QUOTIENT_K; parameter++) {
            int present = 0;
            long quotients = 0;
            for (int value : values) {
                int quotient = value >>> parameter;
                quotients += quotient;
                if (quotient != 0) present++;
            }
            int minority = Math.min(present, values.length - present);
            int gammaLength = 2 * (Integer.SIZE - Integer.numberOfLeadingZeros(minority + 1) - 1) + 1;
            long length = 1L + gammaLength + combinationWidth(values.length, minority, budget)
                    + quotients + (long) parameter * values.length;
            if (length < bestLength) {
                bestLength = length;
                bestParameter = parameter;
            }
        }
        return bestParameter;
    }

    private static void writeRiceParameter(V10BareRouteCodec.RiceBitWriter bits, int parameter) {
        if (parameter == CONSTANT_AXIS) {
            bits.writeBits(7, 3);
            bits.writeBits(31, 5);
        } else if (parameter <= 6) {
            bits.writeBits(parameter, 3);
        } else {
            bits.writeBits(7, 3);
            bits.writeBits(parameter, 5);
        }
    }

    private static int readRiceParameter(V10BareRouteCodec.RiceBitReader bits)
            throws IOException {
        int small = bits.readBits(3);
        if (small < 7) return small;
        int extended = bits.readBits(5);
        if (extended == 31) return CONSTANT_AXIS;
        if (extended < 7 || extended > MAX_QUOTIENT_K) {
            throw new IOException("non-canonical v10 quotient Rice parameter");
        }
        return extended;
    }

    private static void writeGamma(V10BareRouteCodec.RiceBitWriter bits, int value) {
        int width = Integer.SIZE - Integer.numberOfLeadingZeros(value);
        for (int index = 1; index < width; index++) bits.writeBit(0);
        for (int shift = width - 1; shift >= 0; shift--) bits.writeBit((value >>> shift) & 1);
    }

    private static int readGamma(V10BareRouteCodec.RiceBitReader bits, int maximum)
            throws IOException {
        int zeros = 0;
        int maximumBits = Integer.SIZE - Integer.numberOfLeadingZeros(maximum);
        while (bits.readBit() == 0) {
            if (++zeros >= maximumBits) throw new IOException("v10 gamma value exceeds limit");
        }
        int value = 1;
        for (int index = 0; index < zeros; index++) value = (value << 1) | bits.readBit();
        if (value > maximum) throw new IOException("v10 gamma value exceeds limit");
        return value;
    }

    private static void writeMsb(V10BareRouteCodec.RiceBitWriter bits,
                                 BigInteger value, int width) {
        if (value.signum() < 0 || value.bitLength() > width) {
            throw new IllegalArgumentException("v10 combinatorial rank does not fit");
        }
        for (int shift = width - 1; shift >= 0; shift--) {
            bits.writeBit(value.testBit(shift) ? 1 : 0);
        }
    }

    private static BigInteger readMsb(V10BareRouteCodec.RiceBitReader bits,
                                      int width, WorkBudget budget) throws IOException {
        budget.charge(width, (long) width * (width + 1) / 2);
        BigInteger value = BigInteger.ZERO;
        for (int index = 0; index < width; index++) {
            value = value.shiftLeft(1);
            if (bits.readBit() != 0) value = value.or(BigInteger.ONE);
        }
        return value;
    }

    private static int combinationWidth(int size, int selected, WorkBudget budget)
            throws IOException {
        return budget.subtract(choose(size, selected, budget), BigInteger.ONE).bitLength();
    }

    private static BigInteger choose(int size, int selected, WorkBudget budget)
            throws IOException {
        if (selected < 0 || selected > size) throw new IOException("invalid v10 combination cardinality");
        int reduced = Math.min(selected, size - selected);
        BigInteger value = BigInteger.ONE;
        for (int ordinal = 1; ordinal <= reduced; ordinal++) {
            value = budget.multiplyDivide(value, size - reduced + ordinal, ordinal);
        }
        return value;
    }

    private static BigInteger colexRank(List<Integer> positions, WorkBudget budget)
            throws IOException {
        BigInteger rank = BigInteger.ZERO;
        BigInteger combination = BigInteger.ZERO;
        int current = 0;
        int ordinal = 1;
        int previous = -1;
        for (int position : positions) {
            if (position <= previous) throw new IOException("v10 combination positions are not ascending");
            while (current < position) {
                int next = current + 1;
                if (next < ordinal) combination = BigInteger.ZERO;
                else if (next == ordinal) combination = BigInteger.ONE;
                else combination = budget.multiplyDivide(combination, next, next - ordinal);
                current = next;
            }
            rank = budget.add(rank, combination);
            int oldOrdinal = ordinal++;
            if (ordinal > current) combination = BigInteger.ZERO;
            else combination = budget.multiplyDivide(combination, current - oldOrdinal, ordinal);
            previous = position;
        }
        return rank;
    }

    private static int[] colexUnrank(int size, int selected, BigInteger rank,
                                     WorkBudget budget) throws IOException {
        BigInteger total = choose(size, selected, budget);
        if (rank.signum() < 0 || budget.compare(rank, total) >= 0) {
            throw new IOException("v10 combination rank exceeds range");
        }
        int[] positions = new int[selected];
        if (selected == 0) return positions;
        int ceiling = size - 1;
        int ordinal = selected;
        BigInteger combination = choose(ceiling, ordinal, budget);
        BigInteger remainder = rank;
        while (ordinal > 0) {
            while (budget.compare(combination, remainder) > 0) {
                if (ceiling <= 0) throw new IOException("invalid v10 combination walk");
                combination = budget.multiplyDivide(combination, ceiling - ordinal, ceiling);
                ceiling--;
            }
            positions[ordinal - 1] = ceiling;
            remainder = budget.subtract(remainder, combination);
            if (ordinal > 1) {
                if (ceiling <= 0) throw new IOException("invalid v10 combination transition");
                combination = budget.multiplyDivide(combination, ordinal, ceiling);
                ceiling--;
            }
            ordinal--;
        }
        if (remainder.signum() != 0) throw new IOException("v10 combination rank remainder");
        return positions;
    }

    private static int[][] reconstruct(int[] first, int[][] axes) {
        int[][] coordinates = new int[axes[0].length + 1][3];
        System.arraycopy(first, 0, coordinates[0], 0, 3);
        for (int index = 1; index < coordinates.length; index++) {
            for (int axis = 0; axis < 3; axis++) {
                long value = (long) coordinates[index - 1][axis]
                        + V10BareRouteCodec.unzigzag(axes[axis][index - 1]);
                coordinates[index][axis] = V10BareRouteCodec.checkedCoordinate(
                        value, "entropy reconstruction");
            }
        }
        return coordinates;
    }

    private static final class UnaryBudget {
        private final long limit;
        private long zeros;

        UnaryBudget(long limit) {
            this.limit = limit;
        }

        int read(V10BareRouteCodec.RiceBitReader bits, int maximum, String message)
                throws IOException {
            int value = 0;
            while (bits.readBit() == 0) {
                if (++value > maximum) throw new IOException("v10 unary value exceeds coordinate model");
                if (++zeros > limit) throw new IOException(message);
            }
            return value;
        }
    }

    private static final class WorkBudget {
        private long operations;
        private long bitWork;

        void charge(long operationCount, long bits) throws IOException {
            if (operationCount < 0 || bits < 0
                    || operations > MAX_COMBINATION_OPERATIONS - operationCount
                    || bitWork > MAX_BIG_INTEGER_BIT_WORK - bits) {
                throw new IOException("v10 quotient combinatorial work exceeds limit");
            }
            operations += operationCount;
            bitWork += bits;
        }

        BigInteger multiplyDivide(BigInteger value, int numerator, int denominator)
                throws IOException {
            if (numerator < 0 || denominator <= 0) throw new IOException("invalid v10 binomial recurrence");
            charge(1, (long) value.bitLength() + bitLength(numerator));
            BigInteger product = value.multiply(BigInteger.valueOf(numerator));
            charge(1, (long) product.bitLength() + bitLength(denominator));
            BigInteger[] result = product.divideAndRemainder(BigInteger.valueOf(denominator));
            if (result[1].signum() != 0) throw new IOException("non-integral v10 binomial recurrence");
            return result[0];
        }

        BigInteger add(BigInteger left, BigInteger right) throws IOException {
            charge(1, Math.max(left.bitLength(), right.bitLength()) + 1L);
            return left.add(right);
        }

        BigInteger subtract(BigInteger left, BigInteger right) throws IOException {
            charge(1, Math.max(left.bitLength(), right.bitLength()) + 1L);
            return left.subtract(right);
        }

        int compare(BigInteger left, BigInteger right) throws IOException {
            charge(1, Math.max(left.bitLength(), right.bitLength()) + 1L);
            return left.compareTo(right);
        }

        private static int bitLength(int value) {
            return value == 0 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(value);
        }
    }
}
