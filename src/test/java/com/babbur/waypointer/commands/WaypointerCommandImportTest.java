package com.babbur.waypointer.commands;

import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WaypointerCommandImportTest {

    @TempDir
    Path tempDir;

    @Test
    void boundedWorkerReadDecodesValidRoute() throws Exception {
        WaypointGroup group = WaypointGroup.create("Test", "hub");
        group.add(new Waypoint(1, 2, 3, "Point", 0x58C878, 0, 0.0));
        String payload = WaypointCodec.encode(List.of(group));
        Path file = tempDir.resolve("route.txt");
        Files.writeString(file, payload);

        WaypointCommandImport.Result result =
                WaypointCommandImport.readAndDecode(file, payload.length() + 1);

        assertNull(result.error());
        assertInstanceOf(UniversalShareCodec.Waypoints.class, result.decoded());
    }

    @Test
    void boundedWorkerReadRejectsOversizedRoute() throws Exception {
        Path file = tempDir.resolve("oversized.txt");
        Files.writeString(file, "x".repeat(33));

        WaypointCommandImport.Result result =
                WaypointCommandImport.readAndDecode(file, 32);

        assertNull(result.decoded());
        assertNotNull(result.error());
    }
}
