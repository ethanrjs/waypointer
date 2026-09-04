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
        return client.publishRoute(request, identity).thenApply(receipt -> persist(
                receipt, identityStore, publicationRegistry,
                client.apiRoot(), Instant.now()));
    }

    public static CompletableFuture<Completion> publishPreparedAndPersist(
            RouteCatalogClient client,
            CatalogPublishRequest request,
            PublisherIdentity identity,
            CatalogProtocol.PreparedCatalogPayload prepared,
            PublisherIdentityStore identityStore,
            CatalogPublicationRegistry publicationRegistry) {
        if (client == null || request == null || identity == null || prepared == null
                || identityStore == null || publicationRegistry == null) {
            throw new IllegalArgumentException("Publish lifecycle dependencies are required");
        }
        return client.publishPreparedRoute(request, identity, prepared)
                .thenApply(receipt -> persist(
                        receipt, identityStore, publicationRegistry,
                        client.apiRoot(), Instant.now()));
    }

    static Completion persist(
            CatalogPublishReceipt receipt,
            PublisherIdentityStore identityStore,
            CatalogPublicationRegistry publicationRegistry,
            String apiRoot,
            Instant recordedAt) {
        CatalogPublishResult published = receipt.result();
        CatalogPublishRequest request = receipt.request();
        PublisherIdentity identity = receipt.identity();
        PublisherIdentity durableIdentity = identity;
        boolean nameSaveFailed = false;
        if (identity.publisherName() == null) {
            String publisherName = PublisherNamePolicy.requireValid(request.publisherName());
            durableIdentity = identity.withPublisherName(publisherName);
            try {
                durableIdentity = identityStore.savePublisherName(
                        identity, publisherName);
            } catch (CatalogStorageException failure) {
                nameSaveFailed = true;
            }
        }

        boolean publicationSaveFailed = false;
        CatalogPublication publication = null;
        try {
            publication = publicationRegistry.recordSuccessfulPublish(
                    receipt, apiRoot, recordedAt);
        } catch (CatalogStorageException failure) {
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
