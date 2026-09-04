package com.babbur.waypointer.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecRouteCorpusTest {
    @TempDir
    Path directory;

    private static final String ROUTE = """
            {"name":"route","island":"unknown","type":"skyhanni","waypoints":[
              {"x":1,"y":2,"z":3,"r":0,"g":1,"b":0,"options":{"name":1}}
            ]}
            """;

    @Test
    void refusesFractionalCoordinatesUnknownFieldsAndDuplicateKeys() throws Exception {
        Path file = directory.resolve("routes.json");
        Files.writeString(file, "[" + ROUTE.replace("\"x\":1", "\"x\":1.25") + "]");
        assertThrows(ArithmeticException.class, () -> CodecRouteCorpus.load(file));
        Files.writeString(file, "[" + ROUTE.replace("\"x\":1", "\"unrecognized\":7,\"x\":1") + "]");
        assertThrows(IllegalArgumentException.class, () -> CodecRouteCorpus.load(file));
        Files.writeString(file, "[" + ROUTE.replace("\"x\":1", "\"x\":99,\"x\":1") + "]");
        assertThrows(IllegalArgumentException.class, () -> CodecRouteCorpus.load(file));
    }

    @Test
    void geometryDuplicatesStayTogetherAcrossMetadataAndInputOrder() throws Exception {
        Path file = directory.resolve("routes.json");
        String renamed = ROUTE.replace("\"route\"", "\"renamed\"").replace("\"unknown\"", "\"mining_3\"");
        Files.writeString(file, "[" + ROUTE + "," + renamed + "]");
        CodecRouteCorpus.Corpus corpus = CodecRouteCorpus.load(file);
        assertEquals(2, corpus.routes().size());
        assertEquals(1, corpus.integrity().get("uniqueOrderedCoordinates"));
        assertEquals(2, corpus.integrity().get("uniqueNormalizedRoutes"));
        var first = corpus.routes().getFirst();
        var second = corpus.routes().getLast();
        assertEquals(first.coordinateHash(), second.coordinateHash());
        assertEquals(first.split(), second.split());
        assertTrue(first.firstCoordinates());
        assertFalse(second.firstCoordinates());
        assertEquals("1", first.group().get(0).name());
        assertEquals("dwarven_mines", second.group().zoneId());
        Files.writeString(file, "[" + renamed + "," + ROUTE + "]");
        assertEquals(first.split(), CodecRouteCorpus.load(file).routes().getFirst().split());
    }
}
