package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublishResult;
import com.babbur.waypointer.catalog.CatalogRouteSummary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePublishUiStateTest {
    @Test
    void busyPublishLocksDestructiveNavigationButKeepsManageReachable() {
        RoutePublishUiState.Controls loading = RoutePublishUiState.controls(
                snapshot(CatalogPublishSession.Phase.LOADING_IDENTITY, null, null, 1), true);
        RoutePublishUiState.Controls publishing = RoutePublishUiState.controls(
                snapshot(CatalogPublishSession.Phase.PUBLISHING, null, null, 1), true);

        for (RoutePublishUiState.Controls controls : new RoutePublishUiState.Controls[]{
                loading, publishing}) {
            assertFalse(controls.editable());
            assertFalse(controls.publishEnabled());
            assertFalse(controls.backEnabled());
            assertTrue(controls.manageEnabled());
            assertFalse(controls.copyVisible());
        }
    }

    @Test
    void terminalStateEnablesOnlyActionsBackedByRealResultData() {
        CatalogPublishResult result = result();
        RoutePublishUiState.Controls incomplete = RoutePublishUiState.controls(
                snapshot(CatalogPublishSession.Phase.SUCCEEDED, result, null, 2), true);
        RoutePublishUiState.Controls complete = RoutePublishUiState.controls(
                snapshot(CatalogPublishSession.Phase.SUCCEEDED, result, "encoded", 2), true);

        assertTrue(incomplete.editable());
        assertTrue(incomplete.publishEnabled());
        assertTrue(incomplete.backEnabled());
        assertFalse(incomplete.copyVisible());
        assertTrue(complete.copyVisible());
        assertTrue(complete.copyEnabled());

        RoutePublishUiState.Controls invalidForm = RoutePublishUiState.controls(
                snapshot(CatalogPublishSession.Phase.FAILED, null, null, 3), false);
        assertTrue(invalidForm.backEnabled());
        assertFalse(invalidForm.publishEnabled());
    }

    @Test
    void primaryActionAndPromptDeduplicationFollowTheSessionAttempt() {
        assertEquals(RoutePublishUiState.PrimaryAction.NONE,
                RoutePublishUiState.primaryAction(CatalogPublishSession.Phase.PUBLISHING));
        assertEquals(RoutePublishUiState.PrimaryAction.REQUEST_PUBLISHER_NAME,
                RoutePublishUiState.primaryAction(
                        CatalogPublishSession.Phase.NEEDS_PUBLISHER_NAME));
        assertEquals(RoutePublishUiState.PrimaryAction.BEGIN_PUBLISH,
                RoutePublishUiState.primaryAction(CatalogPublishSession.Phase.SUCCEEDED));

        CatalogPublishSession.Snapshot attemptFour = snapshot(
                CatalogPublishSession.Phase.NEEDS_PUBLISHER_NAME, null, null, 4);
        assertTrue(RoutePublishUiState.shouldPromptPublisherName(-1, attemptFour));
        assertFalse(RoutePublishUiState.shouldPromptPublisherName(4, attemptFour));
        assertTrue(RoutePublishUiState.shouldPromptPublisherName(4, snapshot(
                CatalogPublishSession.Phase.NEEDS_PUBLISHER_NAME, null, null, 5)));
        assertFalse(RoutePublishUiState.shouldPromptPublisherName(4, snapshot(
                CatalogPublishSession.Phase.PUBLISHING, null, null, 5)));
    }

    @Test
    void onlyResultPresenceChangesRequireAWidgetRebuild() {
        CatalogPublishSession.Snapshot idle = snapshot(
                CatalogPublishSession.Phase.IDLE, null, null, 0);
        CatalogPublishSession.Snapshot busy = snapshot(
                CatalogPublishSession.Phase.PUBLISHING, null, null, 1);
        CatalogPublishSession.Snapshot succeeded = snapshot(
                CatalogPublishSession.Phase.SUCCEEDED, result(), "encoded", 1);

        assertFalse(RoutePublishUiState.resultLayoutChanged(idle, busy));
        assertTrue(RoutePublishUiState.resultLayoutChanged(busy, succeeded));
        assertTrue(RoutePublishUiState.resultLayoutChanged(succeeded, idle));
    }

    private static CatalogPublishSession.Snapshot snapshot(
            CatalogPublishSession.Phase phase,
            CatalogPublishResult result,
            String payload,
            long attempt) {
        return new CatalogPublishSession.Snapshot(
                phase, null, result, payload, null,
                false, false, false, attempt);
    }

    private static CatalogPublishResult result() {
        return new CatalogPublishResult(new CatalogRouteSummary(
                "AAAAAAAAAAAAAAAAAAAAAA", "Route", "Description", "Tester", "", false,
                "unlisted", "hub", "Hub", 1, 1, 9, 1,
                0, "", "", "/r/AAAAAAAAAAAAAAAAAAAAAA"), "manage-token");
    }
}
