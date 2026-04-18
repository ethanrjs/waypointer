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
 * @param payloadChars     Chars after the magic prefix (the CJK-encoded body).
 * @param compressedBytes  Byte count after CJK decoding, before inflate.
 * @param rawBodyBytes     Byte count after inflate -- size of the binary body.
 * @param charsPerRawByte  {@code inputChars / rawBodyBytes}; overall density ratio.
 * @param headerByte       First byte of the raw body: version in low nibble, flags in high nibble.
 * @param version          Wire format version extracted from the low nibble of {@code headerByte}.
 * @param includesNames    Header bit 4 -- whether the encoder wrote per-waypoint names.
 * @param hasLabel         Header bit 5 -- whether a sender-supplied label string is present.
 * @param reservedBit6     Header bit 6. Reserved; current encoder writes 0. Non-zero indicates
 *                         a hand-crafted or future-version payload -- decoder ignores either way.
 * @param reservedBit7     Header bit 7. Reserved; same semantics as {@code reservedBit6}.
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
     * and {@code bodyBlockBytes} reference the raw (post-inflate)
     * body, not the compressed or CJK-encoded forms.
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
