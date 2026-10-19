package com.babbur.waypointer.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedSourcesTest {
    @TempDir Path dir;

    @Test
    void aDungeonBundleKeepsEveryLinkedGroup() {
        Path file = dir.resolve("published-sources.json");
        List<String> rooms = ids(200);
        new PublishedSources(file).remember("route", rooms);

        assertEquals(rooms, new PublishedSources(file).groups("route"));
    }

    @Test
    void aBundleOverThePublishLimitIsNotLinkedToAPartOfItself() {
        Path file = dir.resolve("published-sources.json");
        PublishedSources sources = new PublishedSources(file);
        sources.remember("route", ids(3));
        sources.remember("route", ids(CatalogProtocol.MAX_DUNGEON_GROUPS + 1));

        assertTrue(sources.groups("route").isEmpty());
    }

    @Test
    void linksThatTheOldSixtyFourGroupCapMayHaveCutAreDropped() throws Exception {
        Path file = dir.resolve("published-sources.json");
        Files.writeString(file, "{\"schema\":1,\"routes\":{\"cut\":" + json(ids(64))
                + ",\"whole\":" + json(ids(63)) + "}}");

        PublishedSources sources = new PublishedSources(file);
        assertTrue(sources.groups("cut").isEmpty());
        assertEquals(ids(63), sources.groups("whole"));
    }

    private static List<String> ids(int count) {
        return IntStream.range(0, count).mapToObj(i -> "group-" + i).toList();
    }

    private static String json(List<String> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
    }
}
