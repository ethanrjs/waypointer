package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoopyFirmamentCompatibilityTest {

    @Test
    void importsCurrentSoopyV1ShareFixture() throws IOException {
        WaypointImporter.ImportResult result = WaypointImporter.importAny(fixture("soopy-current-share.txt"));

        assertEquals(WaypointImporter.Source.SOOPY, result.source());
        assertEquals(2, result.groups().size());
        WaypointGroup crystalHollows = result.groups().get(0);
        assertEquals("crystal_hollows", crystalHollows.zoneId());
        assertEquals(WaypointGroup.LoadMode.STATIC, crystalHollows.loadMode());
        assertEquals(WaypointGroup.GradientMode.MANUAL, crystalHollows.gradientMode());
        assertEquals(10, crystalHollows.get(0).x());
        assertEquals(64, crystalHollows.get(0).y());
        assertEquals(-20, crystalHollows.get(0).z());
        assertEquals(0xFE7E00, crystalHollows.get(0).color());

        WaypointGroup hub = result.groups().get(1);
        assertEquals("hub", hub.zoneId());
        assertEquals(0x007EFE, hub.get(0).color());
    }

    @Test
    void rejectsTrailingBytesInSoopyShare() throws IOException {
        byte[] original = Base64.getDecoder().decode(fixture("soopy-current-share.txt"));
        byte[] withTrailingByte = java.util.Arrays.copyOf(original, original.length + 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointImporter.importAny(Base64.getEncoder().encodeToString(withTrailingByte)));

        assertTrue(error.getMessage().contains("trailing"));
    }

    @Test
    void importsCurrentFirmamentAbsoluteShareFixture() throws IOException {
        WaypointImporter.ImportResult result = WaypointImporter.importAny(fixture("firmament-current-share.txt"));

        assertEquals(WaypointImporter.Source.FIRMAMENT, result.source());
        assertEquals(1, result.groups().size());
        WaypointGroup group = result.groups().get(0);
        assertEquals("F7 Path", group.name());
        assertEquals("unknown", group.zoneId());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
        assertEquals(2, group.size());
        assertEquals("1", group.get(0).name());
        assertEquals(1, group.get(0).x());
        assertEquals(64, group.get(0).y());
        assertEquals(2, group.get(0).z());
        assertEquals("2", group.get(1).name());
    }

    @Test
    void rejectsFirmamentRelativeShareInsteadOfSilentlyMisplacingIt() throws IOException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointImporter.importAny(fixture("firmament-current-relative-share.txt")));

        assertTrue(error.getMessage().contains("relative Firmament"));
        assertTrue(error.getMessage().contains("origin"));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream input = SoopyFirmamentCompatibilityTest.class
                .getResourceAsStream("/fixtures/" + name)) {
            if (input == null) throw new IOException("missing fixture " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
