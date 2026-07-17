package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkyHanniFixtureImportTest {

    @Test
    void importsCommittedCurrentSkyHanniRouteFixture() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/fixtures/skyhanni-current-route.json"));

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);

        assertEquals(WaypointImporter.Source.SKYHANNI, result.source());
        assertEquals(1, result.groups().size());
        WaypointGroup group = result.groups().get(0);
        assertEquals(4, group.size());
        assertEquals("1", group.get(0).name());
        assertEquals("4", group.get(3).name());
        assertEquals(100, group.get(0).x());
        assertEquals(230, group.get(3).z());
        assertEquals(com.babbur.waypointer.core.Zone.UNKNOWN.id(), group.zoneId());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
    }
}
