package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublicationRegistry;
import com.babbur.waypointer.catalog.CatalogPublishLifecycle;
import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.catalog.CatalogPublishResult;
import com.babbur.waypointer.catalog.PublisherIdentity;
import com.babbur.waypointer.catalog.PublisherIdentityStore;
import com.babbur.waypointer.catalog.RouteCatalogClient;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.core.WaypointGroup;

import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;

// Keeps one publish flow alive across screen navigation.
final class CatalogPublishSession {
    private final Supplier<PublisherIdentity> identityLoader;
    private final Supplier<PublisherIdentity> knownIdentityLoader;
    private final PublishOperation publisher;
    private final Executor worker;
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners =
            new CopyOnWriteArrayList<>();
    private final CatalogPublishFormModel form;

    private Phase phase = Phase.IDLE;
    private PublisherIdentity identity;
    private CatalogPublishResult result;
    private String publishedPayload;
    private Throwable failure;
    private boolean nameSaveFailed;
    private boolean publicationSaveFailed;
    private boolean copied;
    private long attempt;

    CatalogPublishSession(
            WaypointGroup group,
            RouteCatalogClient client,
            PublisherIdentityStore identityStore,
            CatalogPublicationRegistry publicationRegistry) {
        this(group, client, identityStore, publicationRegistry, ForkJoinPool.commonPool());
    }

    CatalogPublishSession(
            WaypointGroup group,
            RouteCatalogClient client,
            PublisherIdentityStore identityStore,
            CatalogPublicationRegistry publicationRegistry,
            Executor worker) {
        this(group, identityStore::loadOrCreate,
                () -> Files.isRegularFile(identityStore.file()) ? identityStore.load() : null,
                (request, identity) -> CatalogPublishLifecycle.publishAndPersist(
                        client, request, identity, identityStore, publicationRegistry),
                worker);
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(identityStore, "identityStore");
        Objects.requireNonNull(publicationRegistry, "publicationRegistry");
    }

    CatalogPublishSession(
            WaypointGroup group,
            Supplier<PublisherIdentity> identityLoader,
            PublishOperation publisher,
            Executor worker) {
        this(group, identityLoader, () -> null, publisher, worker);
    }

    private CatalogPublishSession(
            WaypointGroup group,
            Supplier<PublisherIdentity> identityLoader,
            Supplier<PublisherIdentity> knownIdentityLoader,
            PublishOperation publisher,
            Executor worker) {
        this.identityLoader = Objects.requireNonNull(identityLoader, "identityLoader");
        this.knownIdentityLoader = Objects.requireNonNull(
                knownIdentityLoader, "knownIdentityLoader");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.form = new CatalogPublishFormModel(group, this::formEdited);
        loadKnownIdentity();
    }

    CatalogPublishFormModel form() {
        return form;
    }

    synchronized Snapshot snapshot() {
        return snapshotLocked();
    }

    Runnable addListener(Consumer<Snapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void beginPublish() {
        PublisherIdentity known;
        long token;
        synchronized (this) {
            if (!form.valid() || phase.busy()) return;
            if (phase == Phase.NEEDS_PUBLISHER_NAME && identity != null) {
                notifyListeners(snapshotLocked());
                return;
            }
            clearOutcomeLocked();
            phase = Phase.LOADING_IDENTITY;
            token = ++attempt;
            known = identity != null && identity.publisherName() != null ? identity : null;
        }
        notifyListeners(snapshot());
        if (known != null) {
            startPublishing(token, known, null);
            return;
        }
        CompletableFuture.supplyAsync(identityLoader, worker)
                .whenComplete((loaded, loadFailure) -> identityLoaded(
                        token, loaded, loadFailure));
    }

    void confirmPublisherName(String publisherName) {
        PublisherIdentity pending;
        long token;
        synchronized (this) {
            if (phase != Phase.NEEDS_PUBLISHER_NAME || identity == null) return;
            pending = identity;
            token = attempt;
        }
        startPublishing(token, pending, publisherName);
    }

    void markCopied() {
        synchronized (this) {
            if (result == null || publishedPayload == null) return;
            copied = true;
        }
        notifyListeners(snapshot());
    }

    private void identityLoaded(
            long token, PublisherIdentity loaded, Throwable loadFailure) {
        boolean publish;
        synchronized (this) {
            if (token != attempt || phase != Phase.LOADING_IDENTITY) return;
            if (loadFailure != null) {
                phase = Phase.FAILED;
                failure = loadFailure;
                notifyListeners(snapshotLocked());
                return;
            }
            identity = Objects.requireNonNull(loaded, "loaded identity");
            publish = identity.publisherName() != null;
            phase = publish ? Phase.PUBLISHING : Phase.NEEDS_PUBLISHER_NAME;
        }
        notifyListeners(snapshot());
        if (publish) startPublishing(token, loaded, null);
    }

    private void startPublishing(
            long token, PublisherIdentity signer, String requestedName) {
        WaypointGroup route;
        String title;
        String description;
        CatalogPublishRequest.Visibility visibility;
        synchronized (this) {
            if (token != attempt || !form.valid()) return;
            phase = Phase.PUBLISHING;
            route = form.group().exportSnapshot();
            title = form.normalizedTitle();
            description = form.normalizedDescription();
            visibility = form.visibility();
        }
        notifyListeners(snapshot());

        CompletableFuture.supplyAsync(() -> WaypointCodec.encodeCatalog(List.of(route)), worker)
                .thenCompose(payload -> {
                    CatalogPublishRequest request = new CatalogPublishRequest(
                            payload, title, description, visibility,
                            route.zoneId(), requestedName);
                    return publisher.publish(request, signer)
                            .thenApply(completion -> new Published(completion, payload));
                })
                .whenComplete((published, publishFailure) -> finishPublish(
                        token, published, publishFailure));
    }

    private void finishPublish(
            long token, Published published, Throwable publishFailure) {
        synchronized (this) {
            if (token != attempt || phase != Phase.PUBLISHING) return;
            if (publishFailure != null) {
                phase = Phase.FAILED;
                failure = publishFailure;
            } else {
                CatalogPublishLifecycle.Completion completion = published.completion();
                phase = Phase.SUCCEEDED;
                result = completion.result();
                publishedPayload = published.payload();
                identity = completion.identity();
                nameSaveFailed = completion.nameSaveFailed();
                publicationSaveFailed = completion.publicationSaveFailed();
            }
        }
        notifyListeners(snapshot());
    }

    private void formEdited() {
        synchronized (this) {
            if (phase.busy()) return;
            ++attempt;
            phase = Phase.IDLE;
            clearOutcomeLocked();
        }
        notifyListeners(snapshot());
    }

    private void loadKnownIdentity() {
        CompletableFuture.supplyAsync(knownIdentityLoader, worker)
                .whenComplete((loaded, ignoredFailure) -> {
                    if (ignoredFailure != null || loaded == null) return;
                    synchronized (this) {
                        if (identity == null) identity = loaded;
                    }
                    notifyListeners(snapshot());
                });
    }

    private void clearOutcomeLocked() {
        result = null;
        publishedPayload = null;
        failure = null;
        nameSaveFailed = false;
        publicationSaveFailed = false;
        copied = false;
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                phase, identity, result, publishedPayload, failure,
                nameSaveFailed, publicationSaveFailed, copied, attempt);
    }

    private void notifyListeners(Snapshot snapshot) {
        for (Consumer<Snapshot> listener : listeners) listener.accept(snapshot);
    }

    enum Phase {
        IDLE,
        LOADING_IDENTITY,
        NEEDS_PUBLISHER_NAME,
        PUBLISHING,
        SUCCEEDED,
        FAILED;

        boolean busy() {
            return this == LOADING_IDENTITY || this == PUBLISHING;
        }
    }

    record Snapshot(
            Phase phase,
            PublisherIdentity identity,
            CatalogPublishResult result,
            String publishedPayload,
            Throwable failure,
            boolean nameSaveFailed,
            boolean publicationSaveFailed,
            boolean copied,
            long attempt) {
    }

    private record Published(
            CatalogPublishLifecycle.Completion completion, String payload) {
    }

    @FunctionalInterface
    interface PublishOperation {
        CompletableFuture<CatalogPublishLifecycle.Completion> publish(
                CatalogPublishRequest request, PublisherIdentity identity);
    }
}
