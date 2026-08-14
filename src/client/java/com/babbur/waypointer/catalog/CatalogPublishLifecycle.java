package com.babbur.waypointer.catalog;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public final class CatalogPublishLifecycle {
    private CatalogPublishLifecycle() {
    }

    public static CompletableFuture<Completion> publishAndPersist(
            RouteCatalogClient client,
            CatalogPublishRequest request,
            PublisherIdentity identity,
            PublisherIdentityStore identityStore,
            CatalogPublicationRegistry publicationRegistry) {
        if (client == null || request == null || identity == null
                || identityStore == null || publicationRegistry == null) {
            throw new IllegalArgumentException("Publish lifecycle dependencies are required");
        }
        return client.publishRoute(request, identity).thenApply(published -> persist(
                published, request, identity, identityStore, publicationRegistry,
                client.apiRoot(), Instant.now()));
    }

    static Completion persist(
            CatalogPublishResult published,
            CatalogPublishRequest request,
            PublisherIdentity identity,
            PublisherIdentityStore identityStore,
            CatalogPublicationRegistry publicationRegistry,
            String apiRoot,
            Instant recordedAt) {
        PublisherIdentity durableIdentity = identity;
        boolean nameSaveFailed = false;
        if (identity.publisherName() == null) {
            try {
                durableIdentity = identityStore.savePublisherName(
                        identity, PublisherNamePolicy.requireValid(request.publisherName()));
            } catch (RuntimeException failure) {
                nameSaveFailed = true;
            }
        }

        boolean publicationSaveFailed = false;
        CatalogPublication publication = null;
        try {
            publication = publicationRegistry.recordSuccessfulPublish(
                    published, request, identity, apiRoot, recordedAt);
        } catch (RuntimeException failure) {
            publicationSaveFailed = true;
        }
        return new Completion(
                published, durableIdentity, publication,
                nameSaveFailed, publicationSaveFailed);
    }

    public record Completion(
            CatalogPublishResult result,
            PublisherIdentity identity,
            CatalogPublication publication,
            boolean nameSaveFailed,
            boolean publicationSaveFailed) {
    }
}
