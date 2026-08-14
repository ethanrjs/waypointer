package com.babbur.waypointer.catalog;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class CatalogPublicationManager {
    private CatalogPublicationManager() {
    }

    public static CompletableFuture<Void> delete(
            RouteCatalogClient client,
            PublisherIdentity identity,
            CatalogPublicationRegistry registry,
            CatalogPublication publication) {
        if (client == null || identity == null || registry == null || publication == null) {
            throw new IllegalArgumentException("Publication deletion dependencies are required");
        }
        if (!identity.publisherId().equals(publication.publisherId())) {
            throw new IllegalArgumentException(
                    "The device identity does not own this publication record");
        }
        if (!client.apiRoot().equals(publication.apiRoot())) {
            throw new IllegalArgumentException(
                    "The publication belongs to a different catalog service");
        }
        return client.deleteRoute(publication.routeId(), identity)
                .handle((ignored, failure) -> {
                    if (failure != null && !routeAlreadyMissing(failure)) {
                        throw new CompletionException(unwrap(failure));
                    }
                    registry.remove(publication.routeId(), identity.publisherId());
                    return null;
                });
    }

    static boolean routeAlreadyMissing(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof CatalogApiException api
                && api.status() == 404
                && "route_not_found".equals(api.code());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
