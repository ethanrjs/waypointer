package com.babbur.waypointer.screen;

final class RoutePublishUiState {
    private RoutePublishUiState() {
    }

    enum PrimaryAction {
        NONE,
        REQUEST_PUBLISHER_NAME,
        BEGIN_PUBLISH
    }

    record Controls(
            boolean editable,
            boolean publishEnabled,
            boolean backEnabled,
            boolean manageEnabled,
            boolean copyVisible,
            boolean copyEnabled) {
    }

    static Controls controls(
            CatalogPublishSession.Snapshot state, boolean formValid) {
        CatalogPublishSession.Phase phase = state == null ? null : state.phase();
        boolean busy = phase == null || phase.busy();
        boolean hasCode = state != null
                && state.result() != null
                && state.publishedPayload() != null;
        return new Controls(
                !busy,
                formValid && !busy,
                canNavigateBack(phase),
                true,
                hasCode,
                hasCode);
    }

    static PrimaryAction primaryAction(CatalogPublishSession.Phase phase) {
        if (phase == null || phase.busy()) return PrimaryAction.NONE;
        return phase == CatalogPublishSession.Phase.NEEDS_PUBLISHER_NAME
                ? PrimaryAction.REQUEST_PUBLISHER_NAME
                : PrimaryAction.BEGIN_PUBLISH;
    }

    static boolean canNavigateBack(CatalogPublishSession.Phase phase) {
        return phase != null && !phase.busy();
    }

    static boolean shouldPromptPublisherName(
            long lastPromptedAttempt, CatalogPublishSession.Snapshot state) {
        return state != null
                && state.phase() == CatalogPublishSession.Phase.NEEDS_PUBLISHER_NAME
                && state.attempt() != lastPromptedAttempt;
    }

    static boolean resultLayoutChanged(
            CatalogPublishSession.Snapshot previous,
            CatalogPublishSession.Snapshot next) {
        return previous != null && next != null
                && (previous.result() == null) != (next.result() == null);
    }
}
