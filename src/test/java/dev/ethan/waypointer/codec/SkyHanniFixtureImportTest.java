package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkyHanniFixtureImportTest {

    @Test
    void importsCommittedSkyHanniFlatRouteFixture() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/fixtures/skyhanni-flat-route.json"));

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);

        assertEquals(WaypointImporter.Source.SKYHANNI, result.source());
        assertEquals(1, result.groups().size());
        WaypointGroup group = result.groups().get(0);
        assertEquals(4, group.size());
        assertEquals("1", group.get(0).name());
        assertEquals("4", group.get(3).name());
        assertEquals(100, group.get(0).x());
        assertEquals(230, group.get(3).z());
        assertEquals(dev.ethan.waypointer.core.Zone.UNKNOWN.id(), group.zoneId());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
    }
}
