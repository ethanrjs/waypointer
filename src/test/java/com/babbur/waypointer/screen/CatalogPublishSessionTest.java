package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublishLifecycle;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.CatalogPublishResult;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import com.babbur.waypointer.catalog.PublisherIdentity;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublishSessionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void firstPublishPausesForANameThenKeepsItsResultWithoutAView() {
        PublisherIdentityStore identityStore = new PublisherIdentityStore(
                temporaryDirectory.resolve("anonymous/identity.json"));
        PublisherIdentity anonymous = identityStore.loadOrCreate();
        CompletableFuture<CatalogPublishLifecycle.Completion> network =
                new CompletableFuture<>();
        AtomicReference<CatalogPublishRequest> sent = new AtomicReference<>();
        CatalogPublishSession session = new CatalogPublishSession(
                route(), () -> anonymous, (request, ignoredIdentity) -> {
                    sent.set(request);
                    return network;
                }, Runnable::run);
        completeForm(session.form());

        session.beginPublish();
        assertEquals(CatalogPublishSession.Phase.NEEDS_PUBLISHER_NAME,
                session.snapshot().phase());
        assertNull(sent.get());

        session.confirmPublisherName("Tester_1");
        assertEquals(CatalogPublishSession.Phase.PUBLISHING,
                session.snapshot().phase());
        assertEquals("Tester_1", sent.get().publisherName());
        assertTrue(sent.get().payload().startsWith("WP:"));

        PublisherIdentity named = identityStore.savePublisherName(anonymous, "Tester_1");
        network.complete(completion(named));

        CatalogPublishSession.Snapshot completed = session.snapshot();
        assertEquals(CatalogPublishSession.Phase.SUCCEEDED, completed.phase());
        assertNotNull(completed.result());
        assertNotNull(completed.publishedPayload());
        assertEquals("Tester_1", completed.identity().publisherName());

        session.markCopied();
        assertTrue(session.snapshot().copied());
    }

    @Test
    void editingAfterSuccessStartsANewSessionWithoutChangingTheIdentity() {
        PublisherIdentity named = namedIdentity("existing", "Tester_1");
        CatalogPublishSession session = new CatalogPublishSession(
                route(), () -> named,
                (request, identity) -> CompletableFuture.completedFuture(
                        completion(identity)), Runnable::run);
        completeForm(session.form());

        session.beginPublish();
        assertEquals(CatalogPublishSession.Phase.SUCCEEDED, session.snapshot().phase());

        session.form().setTitle("Changed route");

        CatalogPublishSession.Snapshot edited = session.snapshot();
        assertEquals(CatalogPublishSession.Phase.IDLE, edited.phase());
        assertNull(edited.result());
        assertNull(edited.publishedPayload());
        assertEquals(named.publisherId(), edited.identity().publisherId());
    }

    @Test
    void loadFailureBecomesAStableTerminalState() {
        CatalogPublishSession session = new CatalogPublishSession(
                route(), () -> { throw new IllegalStateException("identity unavailable"); },
                (request, identity) -> {
                    throw new AssertionError("publish must not run");
                }, Runnable::run);
        completeForm(session.form());

        session.beginPublish();

        assertEquals(CatalogPublishSession.Phase.FAILED, session.snapshot().phase());
        assertNotNull(session.snapshot().failure());
    }

    private static void completeForm(CatalogPublishFormModel form) {
        form.setTitle("Published route");
        form.setDescription("A useful route description.");
        form.setVisibility(CatalogPublishRequest.Visibility.UNLISTED);
    }

    private static WaypointGroup route() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(new Waypoint(1, 64, 2, "Start", 0x44AA66, 0, 0.0));
        return group;
    }

    private static CatalogPublishLifecycle.Completion completion(
            PublisherIdentity identity) {
        String id = "Abcdefghijklmnopqrstuv";
        CatalogPublishResult result = new CatalogPublishResult(
                new CatalogRouteSummary(
                        id, "Published route", "A useful route description.",
                        identity.publisherName(), identity.publisherId(), true,
                        "unlisted", "hub", "Hub", 1, 1, 9, 1,
                        0, "", "", "/r/" + id), "");
        return new CatalogPublishLifecycle.Completion(
                result, identity, null, false, false);
    }

    private PublisherIdentity identity(String name) {
        return new PublisherIdentityStore(
                temporaryDirectory.resolve(name).resolve("identity.json"))
                .loadOrCreate();
    }

    private PublisherIdentity namedIdentity(String directory, String name) {
        PublisherIdentityStore store = new PublisherIdentityStore(
                temporaryDirectory.resolve(directory).resolve("identity.json"));
        return store.savePublisherName(store.loadOrCreate(), name);
    }
}
