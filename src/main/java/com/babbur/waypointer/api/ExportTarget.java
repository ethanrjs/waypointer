package com.babbur.waypointer.api;

import com.babbur.waypointer.codec.WaypointExportCodec;

public enum ExportTarget {
    WAYPOINTER(WaypointExportCodec.Target.WAYPOINTER),
    SKYBLOCKER(WaypointExportCodec.Target.SKYBLOCKER),
    SKYTILS(WaypointExportCodec.Target.SKYTILS),
    SKYHANNI(WaypointExportCodec.Target.SKYHANNI),
    SKYSOFT(WaypointExportCodec.Target.SKYSOFT),
    ODIN(WaypointExportCodec.Target.ODIN);

    private final WaypointExportCodec.Target codecTarget;

    ExportTarget(WaypointExportCodec.Target codecTarget) {
        this.codecTarget = codecTarget;
    }

    WaypointExportCodec.Target toCodecTarget() {
        return codecTarget;
    }
}
