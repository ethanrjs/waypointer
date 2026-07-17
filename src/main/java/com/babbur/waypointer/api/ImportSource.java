package com.babbur.waypointer.api;

import com.babbur.waypointer.codec.WaypointImporter;

/** Identifies the input format recognized by a successful route import. */
public enum ImportSource {
    WAYPOINTER,
    SKYBLOCKER,
    SKYTILS,
    SKYHANNI,
    SOOPY,
    FIRMAMENT,
    COLEWEIGHT,
    ODIN,
    JSON;

    static ImportSource from(WaypointImporter.Source source) {
        return valueOf(source.name());
    }
}
