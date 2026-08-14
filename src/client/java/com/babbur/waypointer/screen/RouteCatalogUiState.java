package com.babbur.waypointer.screen;

final class RouteCatalogUiState {
    private RouteCatalogUiState() {
    }

    record Controls(
            boolean installEnabled,
            boolean refreshEnabled,
            boolean loadMoreEnabled) {
    }

    static Controls controls(
            boolean hasSelection,
            boolean selectedRouteCanInstall,
            boolean detailLoading,
            boolean listLoading,
            boolean appending,
            boolean hasNextPage,
            int refreshCooldownSeconds) {
        return new Controls(
                hasSelection && selectedRouteCanInstall && !detailLoading,
                !listLoading && refreshCooldownSeconds == 0,
                !listLoading && !appending && hasNextPage);
    }
}
