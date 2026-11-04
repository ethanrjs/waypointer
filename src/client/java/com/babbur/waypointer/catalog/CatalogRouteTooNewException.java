package com.babbur.waypointer.catalog;

public final class CatalogRouteTooNewException extends IllegalArgumentException {
    private final String requiredModVersion;

    CatalogRouteTooNewException(CatalogRouteSummary route) {
        super("Catalog route needs an unsupported codec version");
        this.requiredModVersion = route.requiredModVersion();
    }

    public String requiredModVersion() {
        return requiredModVersion;
    }
}
