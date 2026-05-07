package dev.ethan.waypointer.api;

import dev.ethan.waypointer.core.Zone;

/**
 * Immutable view of Waypointer's currently detected Skyblock zone.
 */
public record ZoneSnapshot(String id, String displayName) {

    static ZoneSnapshot from(Zone zone) {
        return zone == null ? null : new ZoneSnapshot(zone.id(), zone.displayName());
    }
}
