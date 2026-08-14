package com.babbur.waypointer.update;

import java.time.Instant;

record AvailableUpdate(
        String currentVersion,
        Instant currentPublishedAt,
        String latestVersion,
        Instant latestPublishedAt,
        int versionsBehind) {
}
