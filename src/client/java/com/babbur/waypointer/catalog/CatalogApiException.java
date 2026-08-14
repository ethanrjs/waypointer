package com.babbur.waypointer.catalog;

public final class CatalogApiException extends RuntimeException {
    private final int status;
    private final String code;

    CatalogApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
