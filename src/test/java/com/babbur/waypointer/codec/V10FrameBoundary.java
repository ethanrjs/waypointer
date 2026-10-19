package com.babbur.waypointer.codec;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;

final class V10FrameBoundary {

    private V10FrameBoundary() {}

    static byte[] semanticWhoseDeflateOnlyFitsWithoutChecksum(byte header, long seed)
            throws IOException {
        int target = V10Transport.MAX_FRAME_BYTES - 1 - 1;
        int size = target - 5 * (target / 65_535 + 1);
        for (int attempt = 0; attempt < 12; attempt++) {
            byte[] semantic = new byte[size];
            new Random(seed).nextBytes(semantic);
            semantic[0] = header;
            byte[] body = Arrays.copyOfRange(semantic, 1, semantic.length);
            int compressed;
            try {
                compressed = V10Transport.deflate(body, Deflater.DEFAULT_STRATEGY).length;
            } catch (V10ProfileLimitException oversized) {
                compressed = V10Transport.MAX_FRAME_BYTES + 64;
            }
            boolean fitsWithoutChecksum = compressed + 1 <= V10Transport.MAX_FRAME_BYTES;
            boolean overflowsWithChecksum =
                    compressed + 1 + V10Transport.CHECKSUM_BYTES > V10Transport.MAX_FRAME_BYTES;
            if (fitsWithoutChecksum && overflowsWithChecksum) return semantic;
            size += target - compressed;
        }
        throw new AssertionError("could not place a body on the checksum boundary");
    }
}
