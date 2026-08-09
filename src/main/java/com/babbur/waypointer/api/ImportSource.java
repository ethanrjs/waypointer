package com.babbur.waypointer.api;

import com.babbur.waypointer.codec.WaypointImporter;

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
