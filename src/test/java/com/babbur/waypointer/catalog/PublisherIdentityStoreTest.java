package com.babbur.waypointer.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublisherIdentityStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndReloadsTheSameWorkingIdentity() throws Exception {
        Path file = temporaryDirectory.resolve("publisher/identity.json");
        PublisherIdentityStore store = new PublisherIdentityStore(file);

        PublisherIdentity created = store.loadOrCreate();
        PublisherIdentity loaded = store.loadOrCreate();
        byte[] message = "route body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = created.sign(message);

        assertEquals(created.publisherId(), loaded.publisherId());
        assertArrayEquals(created.publicKeyEncoded(), loaded.publicKeyEncoded());
        assertTrue(loaded.verifies(message, signature));
        assertTrue(Files.readString(file).contains("\"algorithm\":\"Ed25519\""));
    }

    @Test
    void preservesAndRejectsCorruptIdentityData() throws Exception {
        Path file = temporaryDirectory.resolve("publisher/identity.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not-json");
        PublisherIdentityStore store = new PublisherIdentityStore(file);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, store::loadOrCreate);

        assertTrue(failure.getMessage().contains("Restore identity.json"));
        assertEquals("not-json", Files.readString(file));
    }

    @Test
    void concurrentCreationReturnsOneDurableIdentity() throws Exception {
        Path file = temporaryDirectory.resolve("publisher/identity.json");
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<PublisherIdentity>> futures = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                futures.add(executor.submit(
                        () -> new PublisherIdentityStore(file).loadOrCreate()));
            }

            String expected = futures.getFirst().get().publisherId();
            for (var future : futures) assertEquals(expected, future.get().publisherId());
            assertEquals(expected, new PublisherIdentityStore(file).load().publisherId());
        }
    }

    @Test
    void savesOnePermanentNameWithoutChangingTheIdentity() {
        Path file = temporaryDirectory.resolve("publisher/identity.json");
        PublisherIdentityStore store = new PublisherIdentityStore(file);
        PublisherIdentity original = store.loadOrCreate();

        PublisherIdentity named = store.savePublisherName(original, "Ethan_26");
        PublisherIdentity reloaded = store.load();

        assertEquals(original.publisherId(), named.publisherId());
        assertArrayEquals(original.publicKeyEncoded(), named.publicKeyEncoded());
        assertEquals("Ethan_26", reloaded.publisherName());
        assertEquals("Ethan_26",
                store.savePublisherName(reloaded, "Ethan_26").publisherName());
        assertThrows(IllegalStateException.class,
                () -> store.savePublisherName(reloaded, "AnotherName"));
    }

    @Test
    void loadsLegacySchemaWithoutInventingAName() throws Exception {
        Path file = temporaryDirectory.resolve("publisher/identity.json");
        PublisherIdentityStore store = new PublisherIdentityStore(file);
        store.loadOrCreate();
        String legacy = Files.readString(file).replace("\"schema\":2", "\"schema\":1");
        Files.writeString(file, legacy);

        assertNull(store.load().publisherName());
    }

    @Test
    void legacyIdentityCanSaveItsFirstName() throws Exception {
        Path file = temporaryDirectory.resolve("publisher/identity.json");
        PublisherIdentityStore store = new PublisherIdentityStore(file);
        PublisherIdentity created = store.loadOrCreate();
        Files.writeString(file, Files.readString(file)
                .replace("\"schema\":2", "\"schema\":1"));

        PublisherIdentity legacy = store.load();
        PublisherIdentity named = store.savePublisherName(legacy, "LegacyName");

        assertEquals(created.publisherId(), named.publisherId());
        assertEquals("LegacyName", store.load().publisherName());
    }

    @Test
    void writeFullyHandlesRepeatedShortWrites() throws Exception {
        byte[] expected = "publisher identity bytes".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        ShortWriteChannel channel = new ShortWriteChannel(3);

        CatalogAtomicFile.writeFully(channel, ByteBuffer.wrap(expected));

        assertArrayEquals(expected, channel.bytes());
        assertTrue(channel.writeCalls > 1);
    }

    @Test
    void ownerOnlyPermissionsRemoveInheritedWindowsEntries() throws Exception {
        Path file = temporaryDirectory.resolve("identity.json");
        Files.writeString(file, "temporary");
        AclFileAttributeView view = Files.getFileAttributeView(
                file, AclFileAttributeView.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        org.junit.jupiter.api.Assumptions.assumeTrue(view != null);

        PublisherIdentityStore.setOwnerOnlyPermissions(file);

        assertEquals(1, view.getAcl().size());
        assertEquals(Files.getOwner(file), view.getAcl().getFirst().principal());
        assertEquals(java.nio.file.attribute.AclEntryType.ALLOW,
                view.getAcl().getFirst().type());
    }

    private static final class ShortWriteChannel implements WritableByteChannel {
        private final java.io.ByteArrayOutputStream output =
                new java.io.ByteArrayOutputStream();
        private final int maximumPerWrite;
        private boolean open = true;
        private int writeCalls;

        private ShortWriteChannel(int maximumPerWrite) {
            this.maximumPerWrite = maximumPerWrite;
        }

        @Override
        public int write(ByteBuffer source) {
            int count = Math.min(maximumPerWrite, source.remaining());
            byte[] next = new byte[count];
            source.get(next);
            output.writeBytes(next);
            writeCalls++;
            return count;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }
}
