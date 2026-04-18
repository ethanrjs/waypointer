package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test against a real coleweight export. Disabled unless the user passes
 * {@code -Dcoleweight.file=<path>} on the Gradle command line so CI does not fail
 * when the fixture is missing; the path is an opaque local asset.
 */
class ColeweightSmokeTest {

    @Test
    @EnabledIfSystemProperty(named = "coleweight.file", matches = ".+")
    void imports_real_coleweight_export() throws Exception {
        Path p = Path.of(System.getProperty("coleweight.file"));
        String json = Files.readString(p);

        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        assertEquals(WaypointImporter.Source.COLEWEIGHT, result.source(),
                "should auto-detect the coleweight wire format");
        assertEquals(1, result.groups().size(), "coleweight exports are a single flat route");

        WaypointGroup g = result.groups().get(0);
        assertTrue(g.size() > 0, "group should contain at least one waypoint");
        assertEquals(WaypointGroup.GradientMode.AUTO, g.gradientMode());

        // Names should be stringified step numbers; monotonic if the exporter kept sorted order.
        Set<String> names = new HashSet<>();
        for (int i = 0; i < g.size(); i++) {
            Waypoint w = g.get(i);
            assertNotNull(w.name());
            names.add(w.name());
        }
        assertEquals(g.size(), names.size(), "every step should carry a distinct name");

        System.out.printf("coleweight import: %d waypoints, first=(%d,%d,%d)%n",
                g.size(), g.get(0).x(), g.get(0).y(), g.get(0).z());
    }
}
