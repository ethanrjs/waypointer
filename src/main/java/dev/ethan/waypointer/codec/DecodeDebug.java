package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.WaypointGroup;

import java.util.List;

/**
 * Structured snapshot of everything {@link WaypointCodec#debugDecode(String)} could
 * observe while taking a codec payload apart. Purpose-built for debugging UIs:
 * every wire-level field that drives the decoder's behavior is exposed as a
 * concrete numeric or string value, alongside the fully-materialized
 * {@link WaypointGroup} list that {@code decode()} would return.
 *
 * Nothing in this tree is part of the hot path. Field names mirror wire
 * terminology so the debug screen can label them without translation.
 *
 * @param rawInput         Original input string, untrimmed.
 * @param inputChars       {@code rawInput.length()}.
 * @param magic            Magic prefix that matched (always {@link WaypointCodec#MAGIC}).
 * @param payloadChars     Chars after the magic prefix.
 * @param textEncoding     Text layer used before DEFLATE.
 * @param compressedBytes  Byte count after text decoding, before inflate.
 * @param rawBodyBytes     Byte count after inflate -- size of the binary body.
 * @param charsPerRawByte  {@code inputChars / rawBodyBytes}; overall density ratio.
 * @param headerByte       First byte of the raw body: version in low nibble; v9 content kind and
 *                         label presence in the high nibble (legacy versions expose old flags).
 * @param version          Wire format version extracted from the low nibble of {@code headerByte}.
 * @param includesNames    Whether names were present in the decoded route. In v1-v8 this comes
 *                         from header bit 4; v9 derives it from the selected layout/body.
 * @param hasLabel         Whether a sender-supplied label string is present (v9 bit 7;
 *                         legacy bit 5).
 * @param reservedBit6     Compatibility field: true for a v9 coordinate-only content kind or
 *                         when the legacy anonymous-group bit 6 is set.
 * @param reservedBit7     Raw header bit 7 (the v9 label bit; reserved in v1-v8).
 * @param label            Sender-supplied human-readable export title; empty if none.
 *                         Already sanitized (no formatting codes / control chars).
 * @param stringPool       UTF-8 string pool, in wire order. Index 0 is always {@code ""}.
 * @param groups           One {@link GroupDebug} per group, preserving wire order.
 * @param decodedGroups    The same groups materialized as {@link WaypointGroup} instances
 *                         -- callers that want both analytics and the payload object tree
 *                         should read from here.
 * @param decodeNanos      Wall clock nanoseconds spent inside {@code debugDecode} (pipeline + parse).
 */
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
     * One group's worth of debug info. Byte counts in {@code coordBlockBytes}
     * and {@code bodyBlockBytes} reference the raw (post-inflate) body, not the
     * compressed or text-encoded forms. Compact v9 kinds do not expose separate
     * coordinate/body sections: for those records {@code groupFlagsByte},
     * {@code coordModeOrdinal}, and {@code coordBlockBytes} are {@code -1},
     * {@code coordMode} is {@code COMPACT_V9_RANGE}, and
     * {@code bodyBlockBytes} is the complete compact payload.
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

    /**
     * One waypoint's worth of debug info, capturing both the raw per-point
     * flags byte and the decoded values it controls.
     */
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
