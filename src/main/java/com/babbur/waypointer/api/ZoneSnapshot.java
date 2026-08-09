package com.babbur.waypointer.api;

import com.babbur.waypointer.core.Zone;

public record ZoneSnapshot(String id, String displayName) {

    static ZoneSnapshot from(Zone zone) {
        return zone == null ? null : new ZoneSnapshot(zone.id(), zone.displayName());
    }
}
