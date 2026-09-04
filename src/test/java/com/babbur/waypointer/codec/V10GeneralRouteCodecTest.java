package com.babbur.waypointer.codec;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V10GeneralRouteCodecTest {

    @Test
    void nearLimitDirectFrameSurvivesOversizedOptionalDeflateCandidates() throws Exception {
        byte[] semantic = new byte[V10Transport.MAX_FRAME_BYTES - V10Transport.CHECKSUM_BYTES];
        new Random(0x6E656172436170L).nextBytes(semantic);
        semantic[0] = (byte) V10GeneralRouteCodec.SEMANTIC_HEADER;

        assertThrows(V10ProfileLimitException.class, () ->
                V10Transport.deflated(semantic, Deflater.DEFAULT_STRATEGY));
        assertThrows(V10ProfileLimitException.class, () ->
                V10Transport.deflated(semantic, Deflater.FILTERED));

        V10Transport.Outbound selected = V10GeneralRouteCodec.selectCandidate(semantic);

        assertEquals(V10Transport.MODE_DIRECT, selected.mode());
        assertEquals(V10Transport.MAX_FRAME_BYTES, selected.payload().length);
        assertArrayEquals(semantic,
                V10Transport.unseal(V10Transport.MODE_DIRECT, selected.payload()));
    }

    @Test
    void externalCrcCanMakeAnOtherwiseBoundedDeflateCandidateIneligible() throws Exception {
        byte[] semantic = V10FrameBoundary.semanticWhoseDeflateOnlyFitsWithoutChecksum(
                (byte) V10GeneralRouteCodec.SEMANTIC_HEADER, 0x10C0FFEEL);

        assertThrows(V10ProfileLimitException.class, () ->
                V10Transport.deflateAndSeal(semantic, Deflater.DEFAULT_STRATEGY));

        V10Transport.Outbound selected = V10GeneralRouteCodec.selectCandidate(semantic);
        assertEquals(V10Transport.MODE_DIRECT, selected.mode());
        assertArrayEquals(semantic,
                V10Transport.unseal(V10Transport.MODE_DIRECT, selected.payload()));
    }
}
