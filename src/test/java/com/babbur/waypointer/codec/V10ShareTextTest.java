package com.babbur.waypointer.codec;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V10ShareTextTest {

    @Test
    void shareTextNeverCarriesAngleBracketsOrChatRewrites() {
        Random random = new Random(187);
        for (int round = 0; round < 2_000; round++) {
            String transport = V10Transport.direct(semantic(random)).transport();
            assertFalse(transport.contains("<"), transport);
            assertFalse(transport.contains(">"), transport);
            assertFalse(transport.contains("<3"), transport);
            assertFalse(transport.contains("o/"), transport);
            assertFalse(transport.contains("~~"), transport);
        }
    }

    @Test
    void escapesRoundTripEveryMarkedCharacter() throws Exception {
        String raw = "a<b>c~d~~eo/fo~g<3h";
        String escaped = V10Transport.escapeShareText(raw);
        assertEquals("a~(b~)c~-d~-~-eo~/fo~-g~(3h", escaped);
        assertEquals(raw, V10Transport.unescapeShareText(escaped));
    }

    @Test
    void legacySpelledCodesStillDecodeToTheSamePayload() throws Exception {
        Random random = new Random(91);
        int ambiguous = 0;
        for (int round = 0; round < 2_000; round++) {
            byte[] semantic = semantic(random);
            byte[] sealed = V10Transport.seal(V10Transport.MODE_DIRECT, semantic);
            String legacy = V10Transport.encodeLegacyText(sealed);
            String shareText = V10Transport.encode(sealed);
            if (!legacy.equals(shareText) && legacy.matches(".*~[-()/].*")) ambiguous++;

            assertArrayEquals(semantic, V10Transport.probe(legacy).semantic(), legacy);
            assertArrayEquals(semantic, V10Transport.probe(shareText).semantic(), shareText);
            assertArrayEquals(sealed, V10Transport.decode(legacy).payload(), legacy);
            assertEquals(legacy, V10Transport.legacyText(shareText));
        }
        assertTrue(ambiguous > 0, "fixture should include legacy codes that also parse as share text");
    }

    private static byte[] semantic(Random random) {
        byte[] semantic = new byte[1 + random.nextInt(64)];
        random.nextBytes(semantic);
        semantic[0] = (byte) WaypointCodec.V10_WIRE_VERSION;
        return semantic;
    }
}
