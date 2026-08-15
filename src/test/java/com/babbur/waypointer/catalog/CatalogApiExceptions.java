package com.babbur.waypointer.catalog;

/** Test-only access to the package-private {@link CatalogApiException} constructor. */
public final class CatalogApiExceptions {
    private CatalogApiExceptions() {
    }

    public static CatalogApiException withCode(int status, String code, String message) {
        return new CatalogApiException(status, code, message);
    }
}
