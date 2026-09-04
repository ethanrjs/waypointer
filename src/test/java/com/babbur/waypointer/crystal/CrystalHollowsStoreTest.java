package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrystalHollowsStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsFullLobbyState() {
        AtomicLong now = new AtomicLong(1_000);
        Path file = temporaryDirectory.resolve("crystal_hollows.json");
        CrystalHollowsStore writer = new CrystalHollowsStore(file, now::get);
        CrystalHollowsLobbyState lobby = new CrystalHollowsLobbyState("m10DH", now.get(), 7);
        lobby.merge(new StructureSighting(CrystalHollowsStructure.MINES_OF_DIVAN,
                735, 98, 451, SightingConfidence.COMPASS, "compass", 1_001));
        lobby.setCrystal(Crystal.JADE, CrystalState.COLLECTED);
        lobby.setDivanCentre(new CrystalHollowsPosition(735, 98, 451));
        writer.put(lobby);
        writer.flush();

        CrystalHollowsStore reader = new CrystalHollowsStore(file, now::get);
        reader.load();
        CrystalHollowsLobbyState restored = reader.restore("m10DH", 7).orElseThrow();
        assertEquals(1, restored.sightings().size());
        assertEquals(CrystalState.COLLECTED, restored.crystals().get(Crystal.JADE));
        assertEquals(new CrystalHollowsPosition(735, 98, 451), restored.divanCentre());
        assertEquals(7, restored.lastKnownDay());
    }

    @Test
    void dropsExpiredLobbiesDuringLoad() {
        AtomicLong now = new AtomicLong(0);
        Path file = temporaryDirectory.resolve("crystal_hollows.json");
        CrystalHollowsStore writer = new CrystalHollowsStore(file, now::get);
        writer.put(new CrystalHollowsLobbyState("m1A", 0, 35));
        writer.flush();
        now.set(30L * 60L * 1_000L + 1);

        CrystalHollowsStore reader = new CrystalHollowsStore(file, now::get);
        reader.load();
        assertTrue(reader.lobbies().isEmpty());
    }

    @Test
    void discardsRecycledServerIdWhenDayMovesBack() {
        AtomicLong now = new AtomicLong(1_000);
        Path file = temporaryDirectory.resolve("crystal_hollows.json");
        CrystalHollowsStore store = new CrystalHollowsStore(file, now::get);
        store.put(new CrystalHollowsLobbyState("m2B", now.get(), 10));
        assertTrue(store.restore("m2B", 9).isPresent());
        assertFalse(store.restore("m2B", 5).isPresent());
        assertTrue(store.lobbies().isEmpty());
        store.discardPendingSave();
    }

    @Test
    void quarantinesCorruptFile() throws Exception {
        Path file = temporaryDirectory.resolve("crystal_hollows.json");
        Files.writeString(file, "{ definitely not json");
        CrystalHollowsStore store = new CrystalHollowsStore(file, () -> 0L);
        store.load();
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(temporaryDirectory.resolve("crystal_hollows.json.invalid")));
        assertTrue(store.lobbies().isEmpty());
    }

    @Test
    void preservesFutureSchemaFileAndBlocksEveryWrite() throws Exception {
        Path file = temporaryDirectory.resolve("crystal_hollows.json");
        String future = "{\n  \"schema\": 2,\n"
                + "  \"unknownFutureData\": {\"sentinel\": true}\n}\n";
        byte[] futureBytes = future.getBytes(StandardCharsets.UTF_8);
        Files.write(file, futureBytes);

        CrystalHollowsStore store = new CrystalHollowsStore(file, () -> 0L);
        store.load();
        assertTrue(store.lobbies().isEmpty());

        store.getOrCreate("m1A", 1);
        store.put(new CrystalHollowsLobbyState("m2B", 0L, 1));
        store.remove("m1A");
        store.flush();

        assertTrue(Files.exists(file));
        assertArrayEquals(futureBytes, Files.readAllBytes(file));
        assertFalse(Files.exists(temporaryDirectory.resolve("crystal_hollows.json.invalid")));
    }
}
