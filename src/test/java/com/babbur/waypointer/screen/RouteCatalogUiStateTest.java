package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCatalogUiStateTest {
    @Test
    void listTicketsRejectStaleSearchAndPostNavigationCompletions() {
        CatalogScreenRequestTracker requests = new CatalogScreenRequestTracker();
        requests.activate();
        int first = requests.beginList(true);
        int append = requests.beginList(false);

        assertFalse(requests.acceptsList(first));
        assertTrue(requests.acceptsList(append));

        requests.invalidateForSearch();
        assertFalse(requests.acceptsList(append));
        int searched = requests.beginList(true);
        assertTrue(requests.acceptsList(searched));

        requests.deactivate();
        assertFalse(requests.acceptsList(searched));
        requests.activate();
        assertFalse(requests.acceptsList(searched));
    }

    @Test
    void installTicketRequiresLatestAttemptSelectionAndActiveScreen() {
        CatalogScreenRequestTracker requests = new CatalogScreenRequestTracker();
        requests.activate();
        requests.selectionChanged();
        CatalogScreenRequestTracker.InstallTicket first = requests.beginInstall();

        assertTrue(requests.latestInstallAttempt(first));
        assertTrue(requests.acceptsInstall(first, true));
        assertFalse(requests.acceptsInstall(first, false));

        requests.selectionChanged();
        assertFalse(requests.acceptsInstall(first, true));
        CatalogScreenRequestTracker.InstallTicket replacement = requests.beginInstall();
        assertFalse(requests.latestInstallAttempt(first));
        assertTrue(requests.acceptsInstall(replacement, true));

        requests.deactivate();
        assertFalse(requests.acceptsInstall(replacement, true));
    }

    @Test
    void primaryControlsMatchIndependentListAndDetailWork() {
        RouteCatalogUiState.Controls ready = RouteCatalogUiState.controls(
                true, true, false, false, false, true, 0);
        assertTrue(ready.installEnabled());
        assertTrue(ready.refreshEnabled());
        assertTrue(ready.loadMoreEnabled());

        RouteCatalogUiState.Controls appending = RouteCatalogUiState.controls(
                true, true, false, true, true, true, 0);
        assertTrue(appending.installEnabled());
        assertFalse(appending.refreshEnabled());
        assertFalse(appending.loadMoreEnabled());

        RouteCatalogUiState.Controls preparingInstall = RouteCatalogUiState.controls(
                true, true, true, false, false, true, 3);
        assertFalse(preparingInstall.installEnabled());
        assertFalse(preparingInstall.refreshEnabled());
        assertTrue(preparingInstall.loadMoreEnabled());

        RouteCatalogUiState.Controls installed = RouteCatalogUiState.controls(
                true, false, false, false, false, false, 0);
        assertFalse(installed.installEnabled());
        assertFalse(installed.loadMoreEnabled());
    }

    @Test
    void refreshCooldownUsesCeilingSecondsAndOpensAtTheExactBoundary() {
        long allowed = 10_000_000_000L;
        assertTrue(RouteCatalogScreen.refreshCooldownSeconds(
                true, 0L, allowed) == 10);
        assertTrue(RouteCatalogScreen.refreshCooldownSeconds(
                true, 9_000_000_001L, allowed) == 1);
        assertTrue(RouteCatalogScreen.refreshCooldownSeconds(
                true, allowed, allowed) == 0);
        assertTrue(RouteCatalogScreen.refreshCooldownSeconds(
                false, 0L, allowed) == 0);
    }
}
