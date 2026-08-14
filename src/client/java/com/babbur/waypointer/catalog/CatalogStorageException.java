package com.babbur.waypointer.catalog;

public final class CatalogStorageException extends IllegalStateException {
    public CatalogStorageException(String message) {
        super(message);
    }

    public CatalogStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
