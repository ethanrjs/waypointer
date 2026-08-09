package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;

import java.util.List;

public record DecodeDebug(
        String rawInput,
        int inputChars,
        String magic,
        int payloadChars,
        String textEncoding,
        int compressedBytes,
        int rawBodyBytes,
        double charsPerRawByte,
        int headerByte,
        int version,
        boolean includesNames,
        boolean hasLabel,
        boolean reservedBit6,
        boolean reservedBit7,
        String label,
        List<String> stringPool,
        List<GroupDebug> groups,
        List<WaypointGroup> decodedGroups,
        long decodeNanos
) {

    /**
     * Group byte counts refer to the body after inflation. Compact v9 records use
     * {@code -1} for fields that do not have separate coordinate sections.
     */
    public record GroupDebug(
            int index,
            String name,
            String zoneId,
            int groupFlagsByte,
            boolean enabled,
            boolean gradientAuto,
            boolean loadSequence,
            boolean customRadius,
            String coordMode,
            int coordModeOrdinal,
            double defaultRadius,
            int currentIndex,
            int pointCount,
            int coordBlockBytes,
            int bodyBlockBytes,
            List<WaypointDebug> waypoints
    ) {}

    public record WaypointDebug(
            int index,
            int x,
            int y,
            int z,
            int wpFlagsByte,
            boolean hasName,
            boolean hasColor,
            boolean hasRadius,
            boolean extended,
            String name,
            int color,
            double customRadius,
            int extendedFlags
    ) {}
}
