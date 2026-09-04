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
    void newLauncherProfilesReuseIdentityAndNameAfterOriginalProfileIsDeleted() throws Exception {
        Path shared = temporaryDirectory.resolve("computer/identity.json");
        Path first = temporaryDirectory.resolve("profile-a/identity.json");
        PublisherIdentityStore original = PublisherIdentityStore.forProfile(first, shared);
        PublisherIdentity named = original.savePublisherName(original.loadOrCreate(), "RouteAuthor");
        Files.delete(first);

        PublisherIdentityStore next = PublisherIdentityStore.forProfile(
                temporaryDirectory.resolve("profile-b/identity.json"), shared);
        PublisherIdentity restored = next.loadOrCreate();

        assertEquals(named.publisherId(), restored.publisherId());
        assertEquals("RouteAuthor", restored.publisherName());
        byte[] message = "same author".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(named.verifies(message, restored.sign(message)));
        assertTrue(Files.isRegularFile(next.file()));
    }

    @Test
    void existingProfileSeedsSharedIdentityWithoutChangingItsFile() throws Exception {
        Path local = temporaryDirectory.resolve("legacy/identity.json");
        PublisherIdentityStore legacy = new PublisherIdentityStore(local);
        PublisherIdentity expected = legacy.savePublisherName(legacy.loadOrCreate(), "ExistingAuthor");
        byte[] before = Files.readAllBytes(local);
        Path shared = temporaryDirectory.resolve("computer/identity.json");

        PublisherIdentityStore.forProfile(local, shared).load();

        assertEquals(expected.publisherId(), new PublisherIdentityStore(shared).load().publisherId());
        assertEquals("ExistingAuthor", new PublisherIdentityStore(shared).load().publisherName());
        assertArrayEquals(before, Files.readAllBytes(local));
    }

    @Test
    void differentExistingProfilesKeepTheirOwnAccounts() throws Exception {
        Path shared = temporaryDirectory.resolve("computer/identity.json");
        PublisherIdentity global = new PublisherIdentityStore(shared).loadOrCreate();
        byte[] sharedBefore = Files.readAllBytes(shared);
        Path local = temporaryDirectory.resolve("other/identity.json");
        PublisherIdentity existing = new PublisherIdentityStore(local).loadOrCreate();
        PublisherIdentityStore profile = PublisherIdentityStore.forProfile(local, shared);

        assertEquals(existing.publisherId(), profile.loadOrCreate().publisherId());
        profile.savePublisherName(existing, "OtherAuthor");

        assertEquals(global.publisherId(), new PublisherIdentityStore(shared).load().publisherId());
        assertArrayEquals(sharedBefore, Files.readAllBytes(shared));
    }

    @Test
    void corruptSharedIdentityIsNeverReplacedByANewAccount() throws Exception {
        Path shared = temporaryDirectory.resolve("computer/identity.json");
        Files.createDirectories(shared.getParent());
        Files.writeString(shared, "corrupt");
        Path local = temporaryDirectory.resolve("new-profile/identity.json");

        assertThrows(CatalogStorageException.class,
                () -> PublisherIdentityStore.forProfile(local, shared).loadOrCreate());
        assertEquals("corrupt", Files.readString(shared));
        assertTrue(Files.notExists(local));
    }

    @Test
    void damagedSharedCopyDoesNotLockOutAValidExistingProfile() throws Exception {
        Path local = temporaryDirectory.resolve("existing/identity.json");
        PublisherIdentity expected = new PublisherIdentityStore(local).loadOrCreate();
        Path shared = temporaryDirectory.resolve("computer/identity.json");
        Files.createDirectories(shared.getParent());
        Files.writeString(shared, "corrupt");
        PublisherIdentityStore store = PublisherIdentityStore.forProfile(local, shared);

        assertEquals(expected.publisherId(), store.loadOrCreate().publisherId());
        assertEquals("ExistingAuthor", store.savePublisherName(expected, "ExistingAuthor").publisherName());
        assertEquals("corrupt", Files.readString(shared));
    }

    @Test
    void simultaneousNameClaimsCannotDivergeAcrossProfiles() throws Exception {
        Path shared = temporaryDirectory.resolve("computer/identity.json");
        PublisherIdentityStore first = PublisherIdentityStore.forProfile(
                temporaryDirectory.resolve("a/identity.json"), shared);
        PublisherIdentityStore second = PublisherIdentityStore.forProfile(
                temporaryDirectory.resolve("b/identity.json"), shared);
        PublisherIdentity a = first.loadOrCreate();
        PublisherIdentity b = second.loadOrCreate();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                try { first.savePublisherName(a, "AuthorOne"); return true; }
                catch (IllegalStateException conflict) { return false; }
            });
            var two = executor.submit(() -> {
                try { second.savePublisherName(b, "AuthorTwo"); return true; }
                catch (IllegalStateException conflict) { return false; }
            });
            assertTrue(one.get() ^ two.get());
        }
        String name = new PublisherIdentityStore(shared).load().publisherName();
        assertEquals(name, first.load().publisherName());
        assertEquals(name, second.load().publisherName());
    }

    @Test
    void sameLocalAndSharedPathBehavesAsAnOrdinaryStore() {
        Path path = temporaryDirectory.resolve("identity.json");
        PublisherIdentityStore store = PublisherIdentityStore.forProfile(path, path);
        assertEquals(store.loadOrCreate().publisherId(), store.load().publisherId());
    }

    @Test
    void sharedNameIsPersistedInAnExistingUnnamedProfile() throws Exception {
        Path shared = temporaryDirectory.resolve("computer/identity.json");
        Path local = temporaryDirectory.resolve("profile/identity.json");
        PublisherIdentityStore profile = PublisherIdentityStore.forProfile(local, shared);
        PublisherIdentity unnamed = profile.loadOrCreate();
        PublisherIdentityStore computer = new PublisherIdentityStore(shared);
        computer.savePublisherName(unnamed, "SharedAuthor");

        assertEquals("SharedAuthor", profile.load().publisherName());
        assertEquals("SharedAuthor", new PublisherIdentityStore(local).load().publisherName());
        Files.delete(shared);
        assertEquals("SharedAuthor", profile.load().publisherName());
    }

    @Test
    void conflictingNamesForOneKeyAreReportedWithoutOverwritingEitherFile() throws Exception {
        Path shared = temporaryDirectory.resolve("computer/identity.json");
        Path local = temporaryDirectory.resolve("profile/identity.json");
        PublisherIdentityStore profile = PublisherIdentityStore.forProfile(local, shared);
        PublisherIdentity unnamed = profile.loadOrCreate();
        new PublisherIdentityStore(local).savePublisherName(unnamed, "LocalAuthor");
        new PublisherIdentityStore(shared).savePublisherName(unnamed, "SharedAuthor");
        byte[] localBefore = Files.readAllBytes(local);
        byte[] sharedBefore = Files.readAllBytes(shared);

        assertThrows(CatalogStorageException.class, profile::load);
        assertArrayEquals(localBefore, Files.readAllBytes(local));
        assertArrayEquals(sharedBefore, Files.readAllBytes(shared));
    }

    @Test
    void simultaneousNewProfilesShareOneIdentity() throws Exception {
        Path shared = temporaryDirectory.resolve("computer/identity.json");
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<PublisherIdentity>> identities = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Path profile = temporaryDirectory.resolve("profile-" + i + "/identity.json");
                identities.add(executor.submit(
                        () -> PublisherIdentityStore.forProfile(profile, shared).loadOrCreate()));
            }
            String expected = identities.getFirst().get().publisherId();
            for (var identity : identities) assertEquals(expected, identity.get().publisherId());
        }
    }

    @Test
    void sharedLocationUsesUserStorageAndIgnoresRelativeEnvironmentPaths() {
        Path home = temporaryDirectory.resolve("home");
        assertEquals(home.resolve("Library/Application Support/waypointer/publisher/identity.json"),
                PublisherIdentityStore.sharedIdentityFile("Mac OS X", home, null, null));
        assertEquals(home.resolve("AppData/Roaming/waypointer/publisher/identity.json"),
                PublisherIdentityStore.sharedIdentityFile("Windows 11", home, null, null));
        assertEquals(home.resolve(".config/waypointer/publisher/identity.json"),
                PublisherIdentityStore.sharedIdentityFile("Linux", home, null, "relative"));
        Path custom = temporaryDirectory.resolve("xdg");
        assertEquals(custom.resolve("waypointer/publisher/identity.json"),
                PublisherIdentityStore.sharedIdentityFile("Linux", home, null, custom.toString()));
    }

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
