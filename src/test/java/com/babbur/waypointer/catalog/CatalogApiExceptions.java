package com.babbur.waypointer.catalog;

public final class CatalogApiExceptions {
    private CatalogApiExceptions() {
    }

    public static CatalogApiException withCode(int status, String code, String message) {
        return new CatalogApiException(status, code, message);
    }
}
