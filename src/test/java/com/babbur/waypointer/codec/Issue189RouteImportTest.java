package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The 28-point route from issue #189, pasted the ways players share it. */
class Issue189RouteImportTest {

    private static String route() throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/issue-189-coleweight-route.json"));
    }

    @Test
    void importsThePrettyPrintedList() throws Exception {
        assertRoute(WaypointImporter.importAny(route()));
    }

    @Test
    void importsTheListInsideADiscordCodeFence() throws Exception {
        assertRoute(WaypointImporter.importAny("```json\n" + route() + "\n```"));
    }

    @Test
    void importsTheListWrappedInTheSkyblockerHeader() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(route().getBytes(StandardCharsets.UTF_8));
        }
        String packed = WaypointImporter.SKYBLOCKER_V1_PREFIX
                + Base64.getEncoder().encodeToString(out.toByteArray());
        assertRoute(WaypointImporter.importAny(packed));
    }

    private static void assertRoute(WaypointImporter.ImportResult result) {
        assertEquals(1, result.groups().size());
        WaypointGroup group = result.groups().getFirst();
        assertEquals(28, group.size());
        assertEquals("1", group.get(0).name());
        assertEquals(552, group.get(0).x());
        assertEquals(47, group.get(0).y());
        assertEquals(555, group.get(0).z());
        assertEquals("28", group.get(27).name());
        assertEquals(573, group.get(27).x());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
    }
}
