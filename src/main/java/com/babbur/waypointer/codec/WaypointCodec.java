package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.data.V10DungeonBodyCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Encodes and decodes native {@code WP:} route shares.
 *
 * <p>Automatic exports use the V10 typed envelope and fall back to V9 only
 * when an otherwise supported route exceeds the V10 profile. Versions 1-9
 * remain importable. See {@code CODEC.md} for the wire contract.
 */
public final class WaypointCodec {

    /** Prefix every encoded string starts with. Used by the chat scanner to find embedded exports. */
    public static final String MAGIC = "WP:";

    /** Frozen legacy writer used by explicit V9 exports and V10 profile fallback. */
    static final int WIRE_VERSION = 9;
    /** Current unified envelope version. */
    static final int V10_WIRE_VERSION = 10;
    static final int LEGACY_V8_WIRE_VERSION = 8;
    static final int LEGACY_V7_WIRE_VERSION = 7;
    static final int LEGACY_V6_WIRE_VERSION = 6;
    static final int LEGACY_V5_WIRE_VERSION = 5;
    private static final int LEGACY_V4_WIRE_VERSION = 4;
    private static final int LEGACY_V3_WIRE_VERSION = 3;
    private static final int LEGACY_V2_WIRE_VERSION = 2;
    private static final int LEGACY_V1_WIRE_VERSION = 1;
    private static final int HEADER_VERSION_MASK = 0x0F;
    /** v1-v8 export flags. v9 reinterprets the high nibble as content kind + label presence. */
    private static final int HEADER_FLAG_NAMES = 1 << 4;
    /** Legacy V1-V8 bit 5: a sender-supplied label follows the header. */
    private static final int HEADER_FLAG_LABEL = 1 << 5;
    /** Bit 6 in v6+: a single anonymous coordinate-only group follows without the normal pool/group wrapper. */
    private static final int HEADER_FLAG_ANONYMOUS_SINGLE_GROUP = 1 << 6;
    static final int V9_CONTENT_KIND_GENERAL_ROUTE = 0;
    static final int V9_CONTENT_KIND_COMPACT_ROUTE = 1;
    static final int V9_CONTENT_KIND_COORDINATE_ROUTE = 2;
    static final int V9_CONTENT_KIND_CONFIG = 3;
    static final int V9_CONTENT_KIND_DUNGEON_ROUTE = 4;
    /** Coordinate-only fallback retaining the v6-v8 anonymous group metadata layout. */
    static final int V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META = 5;
    private static final int V9_CONTENT_KIND_SHIFT = 4;
    private static final int V9_CONTENT_KIND_MASK = 0b111 << V9_CONTENT_KIND_SHIFT;
    private static final int V9_HEADER_FLAG_LABEL = 1 << 7;
    private static final int ANONYMOUS_SINGLE_GROUP_MIN_VERSION = 6;
    private static final int RANGE_DELTA_MIN_VERSION = 6;
    private static final int PRECISE_WAYPOINT_MIN_VERSION = 7;

    /**
     * Hard cap on label byte length on the wire. Keeps a single export within a
     * predictable upper bound (~256B before compression) and stops malformed
     * payloads from forcing huge allocations during {@link #peekLabel(String)}.
     * UTF-8 max 4 bytes/char -> roughly 64 visible characters, which matches
     * {@link Options#MAX_LABEL_CHARS}.
     */
    private static final int MAX_LABEL_BYTES = 256;

    /**
     * Bit 0: no per-waypoint body bytes follow the coord block. Used when every
     * waypoint would otherwise write a zero flag byte.
     */
    private static final int GROUP_FLAG_BODYLESS_WAYPOINTS = 1;
    private static final int GROUP_FLAG_GRAD_AUTO     = 1 << 1;
    private static final int GROUP_FLAG_LOAD_SEQUENCE = 1 << 2;
    private static final int GROUP_FLAG_CUSTOM_RADIUS = 1 << 3;
    /** 2-bit field at bits 4..5 holding the coord-mode ordinal (0..3). */
    private static final int GROUP_FLAG_COORD_MODE_SHIFT = 4;
    private static final int GROUP_FLAG_COORD_MODE_MASK  = 0b11 << GROUP_FLAG_COORD_MODE_SHIFT;
    /** V5+ uses bit 6 as the high bit for coord-mode ids 4..7. */
    private static final int GROUP_FLAG_COORD_MODE_EXTENDED = 1 << 6;
    private static final int GROUP_FLAG_V9_PERSISTENT_META = 1 << 7;
    private static final int V9_GROUP_META_SKIP_AHEAD = 1 << 2;
    private static final int V9_GROUP_META_RESERVED_MASK = 0xF8;
    private static final int DEFAULT_STATIC_COLOR = Waypoint.DEFAULT_COLOR;
    private static final int DEFAULT_GRADIENT_START_COLOR = 0x00BFFF;
    private static final int DEFAULT_GRADIENT_END_COLOR = 0xFF3040;

    /** Bit widths for the FIXED_COMPACT packing. */
    private static final int FIXED_X_BITS = 12;
    private static final int FIXED_Y_BITS = 9;
    private static final int FIXED_Z_BITS = 12;
    /** Y coordinate offset so (y + FIXED_Y_OFFSET) stays non-negative. Covers y in [-64, +447]. */
    private static final int FIXED_Y_OFFSET = 64;

    /**
     * FIT_COMPACT bit-width field. 5 bits per axis lets us store any width in
     * [0, 31]; a width of 0 means "every coord equals the origin" and consumes
     * zero bits per waypoint on that axis (useful for flat groups, e.g. a
     * horizontal row at a fixed y). A width of 31 is enough for any realistic
     * Minecraft world range (2^31 is wider than the world border on any known
     * server).
     */
    private static final int FIT_BITS_PER_AXIS = 5;
    private static final int FIT_MAX_WIDTH = (1 << FIT_BITS_PER_AXIS) - 1;
    private static final int RANGE_PROB_BITS = 12;
    private static final int RANGE_PROB_SCALE = 1 << RANGE_PROB_BITS;
    private static final short RANGE_PROB_INITIAL = RANGE_PROB_SCALE >>> 1;
    private static final int RANGE_PROB_MOVE = 4;
    private static final int RANGE_DELTA_CONTEXTS = 3 * FIT_MAX_WIDTH;
    private static final int RANGE_DELTA_MAX_PAYLOAD_BYTES = 1 << 20;
    private static final long RANGE_TOP = 1L << 24;
    private static final long RANGE_BOTTOM = 1L << 16;
    private static final long RANGE_MASK = 0xFFFF_FFFFL;
    private static final int MAX_WIRE_WAYPOINTS_PER_GROUP = WaypointImporter.MAX_WAYPOINTS_PER_GROUP;
    private static final int MAX_WIRE_WAYPOINTS_TOTAL = WaypointImporter.MAX_TOTAL_WAYPOINTS_PER_IMPORT;
    private static final int MIN_WAYPOINT_BLOCK_COORDINATE =
            Math.floorDiv(Integer.MIN_VALUE, Waypoint.PRECISE_SCALE);
    private static final int MAX_WAYPOINT_BLOCK_COORDINATE =
            Math.floorDiv(Integer.MAX_VALUE, Waypoint.PRECISE_SCALE);
    private static final int MAX_WIRE_STRING_BYTES = 1 << 20;
    static final int MAX_ROUTE_DISPLAY_NAME_BYTES = 256;
    private static final int MAX_WIRE_GROUPS = WaypointImporter.MAX_GROUPS_PER_IMPORT;
    private static final int MAX_ARRAYLIST_PRESIZE = 1024;
    static final int MAX_INFLATED_BYTES = 16 << 20;
    private static final int CHECKSUM_BYTES = Integer.BYTES;
    private static final int MAX_PEEK_PAYLOAD_CHARS = 1024;

    private static final int WP_FLAG_HAS_NAME   = 1;
    private static final int WP_FLAG_HAS_COLOR  = 1 << 1;
    private static final int WP_FLAG_HAS_RADIUS = 1 << 2;
    private static final int WP_FLAG_EXTENDED   = 1 << 3;
    /** Set together with HAS_NAME when the UTF-8 name follows inline instead of a pool index. */
    private static final int WP_FLAG_NAME_INLINE = 1 << 4;
    /** v7+: a packed 12-bit x/y/z sixteenth-block offset follows the older optional body fields. */
    private static final int WP_FLAG_HAS_PRECISE = 1 << 5;
    private static final int PRECISE_OFFSET_BITS = 4;
    private static final int PRECISE_OFFSET_MASK = (1 << PRECISE_OFFSET_BITS) - 1;
    private static final int PRECISE_OFFSET_PACKED_MASK = (1 << (PRECISE_OFFSET_BITS * 3)) - 1;
    private static final String TEXT_ENCODING_V9 = "ASCII base-91 stream + v9 dictionary + CRC-32";
    private static final String TEXT_ENCODING_V8 = "ASCII base-91 stream + CRC-32";
    private static final String TEXT_ENCODING_V7 = "ASCII base-91 stream + subwaypoint precision";
    private static final String TEXT_ENCODING_V6 = "ASCII base-91 stream + range-delta coord mode";
    private static final String TEXT_ENCODING_V5 = "ASCII base-91 stream + extended coord modes";
    private static final String TEXT_ENCODING_V4 = "ASCII base-92 stream + Hypixel emote escape";
    private static final String TEXT_ENCODING_V3 = "ASCII base-93 stream";
    private static final String TEXT_ENCODING_V2 = "ASCII base-85";
    private static final String TEXT_ENCODING_V1 = "CJK base-16384";
    private static final char HYPIXEL_EMOTE_ESCAPE = '~';

    private WaypointCodec() {}

    /**
     * Per-group coordinate packing strategy. The numeric ordinal is wire-facing
     * (stored in the 2-bit coord-mode field of {@code groupFlags}); do not
     * reorder without bumping {@link #WIRE_VERSION}.
     */
    enum CoordMode {
        VECTOR(0),
        ABSOLUTE_VARINT(1),
        FIXED_COMPACT(2),
        FIT_COMPACT(3),
        VECTOR_AXIS_SEPARATED(4),
        DELTA_FIT_AXIS_SEPARATED(5),
        RANGE_DELTA(6);

        final int wireValue;

        CoordMode(int wireValue) { this.wireValue = wireValue; }

        static CoordMode fromWire(int v) {
            for (CoordMode m : values()) if (m.wireValue == v) return m;
            throw new IllegalArgumentException("unknown coord mode wire value: " + v);
        }
    }

    /**
     * Coordinate packing mode driver. {@link #AUTO} tries every eligible mode per
     * group and keeps the smallest; the forced modes exist mainly so tests can
     * assert that AUTO actually picks the best option. Production code should
     * stick with AUTO.
     */
    enum PackingMode {
        AUTO,
        FORCE_VECTOR,
        FORCE_ABSOLUTE,
        FORCE_FIXED,
        FORCE_FIT,
        FORCE_VECTOR_AXIS_SEPARATED,
        FORCE_DELTA_FIT_AXIS_SEPARATED,
        FORCE_RANGE_DELTA
    }

    /**
     * Export options. Six independent toggles control which payload fields are
     * emitted, plus an optional {@code label} the sender can use to title the
     * export. Progress and enabled state are never written -- shared routes
     * always import fresh on the recipient's client (see the class doc).
     *
     * The {@link #WITH_NAMES} / {@link #NO_NAMES} constants stay around as
     * shorthand for chat-typed shortcuts ({@code /wp export names}); GUI flows
     * build options through the {@link Builder} for finer control.
     *
     * The no-options encode API uses {@link #FULL_FIDELITY}. The older
     * {@link #WITH_NAMES} and {@link #NO_NAMES} presets remain explicit lossy
     * chat-size choices.
     */
    public static final class Options {
        /** Hard cap on label visible characters; the byte cap is tracked separately. */
        public static final int MAX_LABEL_CHARS = 64;

        /** Explicit compact preset: names included, colors/radii/flags stripped. */
        public static final Options WITH_NAMES = builder()
                .includeNames(true)
                .includeColors(false)
                .includeRadii(false)
                .includeWaypointFlags(false)
                .includeGroupMeta(true)
                .build();
        /** Names and colors stripped -- minimal-payload preset for chat sharing. */
        public static final Options NO_NAMES = builder()
                .includeNames(false)
                .includeColors(false)
                .includeRadii(false)
                .includeWaypointFlags(false)
                .includeGroupMeta(true)
                .build();
        /**
         * Ordered coordinates with every name, color, setting, label, and zone
         * projected away. V10 supports one route or a bare-route pack.
         */
        public static final Options BARE_COORDINATES = builder()
                .includeNames(false)
                .includeColors(false)
                .includeRadii(false)
                .includeWaypointFlags(false)
                .includeGroupMeta(false)
                .includeZone(false)
                .bareCoordinatesOnly(true)
                .build();
        /** Every persistent route field supported by the waypoint share format. */
        public static final Options FULL_FIDELITY = builder().build();
        /** Alias retained for callers that describe the full-fidelity preset as lossless. */
        public static final Options LOSSLESS = FULL_FIDELITY;

        public final boolean includeNames;
        public final boolean includeColors;
        public final boolean includeRadii;
        public final boolean includeWaypointFlags;
        public final boolean includeGroupMeta;
        private final boolean bareCoordinatesOnly;
        /**
         * Whether each route keeps the island it was recorded on. Stripping it
         * encodes {@link Zone#UNKNOWN} instead, which every import path already
         * retargets to the island the recipient is standing on -- so a shared
         * route becomes portable rather than pinned to the sender's island.
         */
        public final boolean includeZone;
        /** Sanitized label; an empty string leaves the wire label flag unset. */
        public final String  label;

        private Options(boolean includeNames, boolean includeColors, boolean includeRadii,
                        boolean includeWaypointFlags, boolean includeGroupMeta,
                        boolean includeZone, boolean bareCoordinatesOnly, String label) {
            this.includeNames         = includeNames;
            this.includeColors        = includeColors;
            this.includeRadii         = includeRadii;
            this.includeWaypointFlags = includeWaypointFlags;
            this.includeGroupMeta     = includeGroupMeta;
            this.includeZone          = includeZone;
            this.bareCoordinatesOnly  = bareCoordinatesOnly;
            this.label                = label == null ? "" : label;
        }

        public static Builder builder() { return new Builder(); }

        public Builder toBuilder() {
            return new Builder()
                    .includeNames(includeNames)
                    .includeColors(includeColors)
                    .includeRadii(includeRadii)
                    .includeWaypointFlags(includeWaypointFlags)
                    .includeGroupMeta(includeGroupMeta)
                    .includeZone(includeZone)
                    .bareCoordinatesOnly(bareCoordinatesOnly)
                    .label(label);
        }

        /** Convenience selector for callers that only know a names-included boolean. */
        public static Options forNamesIncluded(boolean includeNames) {
            return includeNames ? WITH_NAMES : NO_NAMES;
        }

        /** True when the export intentionally carries only ordered block coordinates. */
        public boolean isBareCoordinateProjection() {
            return bareCoordinatesOnly && hasAllExportFieldsOff();
        }

        /** True when all visible export fields and the label are off. */
        boolean hasAllExportFieldsOff() {
            return !includeNames && !includeColors && !includeRadii
                    && !includeWaypointFlags && !includeGroupMeta && !includeZone
                    && label.isEmpty();
        }

        /**
         * Strip Minecraft chat formatting escapes ({@code §}), control chars, and
         * trailing whitespace, then truncate to {@link #MAX_LABEL_CHARS}.
         *
         * Sanitization runs at the encoder boundary so a raw user string (typed
         * in the export GUI or sent in chat) can never inject color codes or
         * click events into the recipient's hover tooltip. Returning a string
         * that's safe to feed straight into {@code Component.literal} is the
         * whole contract.
         */
        public static String sanitizeLabel(String raw) {
            if (raw == null || raw.isEmpty()) return "";
            StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_LABEL_CHARS));
            int kept = 0;
            for (int i = 0; i < raw.length() && kept < MAX_LABEL_CHARS; i++) {
                char c = raw.charAt(i);
                // §/\u00A7 is Minecraft's formatting escape -- if it survived, the
                // client would interpret the next char as a color/style code.
                // C0 control chars (newlines, tabs, etc.) would line-break the
                // hover tooltip or break wrapping, which is also out of scope
                // for a one-line title.
                if (c == '\u00A7' || c < 0x20 || c == 0x7F) continue;
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= raw.length() || !Character.isLowSurrogate(raw.charAt(i + 1))
                            || kept + 2 > MAX_LABEL_CHARS) {
                        continue;
                    }
                    sb.append(c).append(raw.charAt(++i));
                    kept += 2;
                    continue;
                }
                if (Character.isLowSurrogate(c)) continue;
                sb.append(c);
                kept++;
            }
            // Trim now (rather than first) so internal sanitization can't expose
            // newly-leading whitespace: e.g. "  §c hi" -> after § removal the
            // leading spaces would otherwise survive.
            return sb.toString().strip();
        }

        public static final class Builder {
            private boolean includeNames         = true;
            private boolean includeColors        = true;
            private boolean includeRadii         = true;
            private boolean includeWaypointFlags = true;
            private boolean includeGroupMeta     = true;
            private boolean includeZone          = true;
            private boolean bareCoordinatesOnly;
            private String  label                = "";

            public Builder includeNames(boolean v)         { this.includeNames = v; return this; }
            public Builder includeColors(boolean v)        { this.includeColors = v; return this; }
            public Builder includeRadii(boolean v)         { this.includeRadii = v; return this; }
            public Builder includeWaypointFlags(boolean v) { this.includeWaypointFlags = v; return this; }
            public Builder includeGroupMeta(boolean v)     { this.includeGroupMeta = v; return this; }
            public Builder includeZone(boolean v)          { this.includeZone = v; return this; }
            Builder bareCoordinatesOnly(boolean v)         { this.bareCoordinatesOnly = v; return this; }
            public Builder label(String v)                 { this.label = sanitizeLabel(v); return this; }

            // Read accessors so UIs can seed their toggle state from the
            // builder's current values without storing a parallel snapshot.
            public boolean includeNames()         { return includeNames; }
            public boolean includeColors()        { return includeColors; }
            public boolean includeRadii()         { return includeRadii; }
            public boolean includeWaypointFlags() { return includeWaypointFlags; }
            public boolean includeGroupMeta()     { return includeGroupMeta; }
            public boolean includeZone()          { return includeZone; }
            public String  label()                { return label; }

            public Options build() {
                return new Options(includeNames, includeColors, includeRadii,
                        includeWaypointFlags, includeGroupMeta, includeZone,
                        bareCoordinatesOnly, label);
            }
        }
    }

    // --- public API ---------------------------------------------------------------------------

    /** Encode with every persistent route field supported by the share format. */
    public static String encode(List<WaypointGroup> groups) {
        return encode(groups, Options.FULL_FIDELITY);
    }

    /** Encode with explicit export options and automatic per-group coord packing. */
    public static String encode(List<WaypointGroup> groups, Options opts) {
        return encode(groups, opts, PackingMode.AUTO);
    }

    /** Encode a full-fidelity v9 payload for the public route catalog. */
    public static String encodeCatalog(List<WaypointGroup> groups) {
        validateEncodeInput(groups, Options.FULL_FIDELITY, PackingMode.AUTO);
        try {
            return encodeV9(groups, Options.FULL_FIDELITY, PackingMode.AUTO);
        } catch (IOException failure) {
            throw new IllegalStateException("catalog v9 encode failed", failure);
        }
    }

    public static int currentWireVersion() {
        return V10_WIRE_VERSION;
    }

    /**
     * Package-private: encode with an explicit packing mode. Only tests should pass
     * anything other than {@link PackingMode#AUTO}; forcing a mode defeats the
     * multi-pass selection and typically yields larger output.
     */
    static String encode(List<WaypointGroup> groups, Options opts, PackingMode mode) {
        Options effectiveOptions = mode == PackingMode.AUTO
                && V10RouteCodec.canEncodeBareSelection(groups, opts)
                ? Options.BARE_COORDINATES : opts;
        validateEncodeInput(groups, effectiveOptions, mode);
        try {
            if (mode == PackingMode.AUTO) {
                try {
                    return MAGIC + V10RouteCodec.encode(groups, effectiveOptions);
                } catch (V10ProfileLimitException profileLimit) {
                    // The only outbound V9 escape: an otherwise supported route
                    // exceeds the documented bounded V10 frame profile.
                    return encodeV9(groups, effectiveOptions, mode);
                }
            }
            return encodeV9(groups, effectiveOptions, mode);
        } catch (IOException e) {
            throw new IllegalStateException("codec encode failed", e);
        }
    }

    private static String encodeV9(List<WaypointGroup> groups, Options opts, PackingMode mode)
            throws IOException {
        byte[] generalRaw = writeBody(groups, opts, mode, WIRE_VERSION, true, true);
        validateV9BodySize(generalRaw);
        byte[] compressed = deflateV9(appendChecksum(generalRaw));

        if (mode == PackingMode.AUTO && groups.size() == 1) {
            WaypointGroup group = groups.get(0);
            if (V9CompactCodec.canEncodeCoordinates(group, opts)) {
                byte[] coordinateRaw = writeCompactCoordinateV9Body(group, opts);
                validateV9BodySize(coordinateRaw);
                byte[] coordinateCompressed = deflateV9(appendChecksum(coordinateRaw));
                if (escapedTextLength(coordinateCompressed) < escapedTextLength(compressed)) {
                    compressed = coordinateCompressed;
                }
            }
            if (V9CompactCodec.canEncode(group, opts)) {
                byte[] compactRaw = writeCompactV9Body(group, opts);
                validateV9BodySize(compactRaw);
                byte[] compactCompressed = deflateV9(appendChecksum(compactRaw));
                if (escapedTextLength(compactCompressed) < escapedTextLength(compressed)) {
                    compressed = compactCompressed;
                }
            }
        }
        return MAGIC + escapeHypixelEmotes(AsciiStreamCodec.encode(compressed));
    }

    static String encodeV9ForTest(List<WaypointGroup> groups, Options opts) {
        validateEncodeInput(groups, opts, PackingMode.AUTO);
        try {
            return encodeV9(groups, opts, PackingMode.AUTO);
        } catch (IOException failure) {
            throw new IllegalStateException("v9 test encode failed", failure);
        }
    }

    static String encodeV9ForTest(List<WaypointGroup> groups) {
        return encodeV9ForTest(groups, Options.FULL_FIDELITY);
    }

    public static List<WaypointGroup> decode(String text) {
        return decodeFull(text).groups();
    }

    /**
     * Decode a payload to its groups plus the sender-supplied label.
     *
     * The label is part of the wire format but separate from any group's data;
     * callers that need to surface it (chat hover tooltips, the import
     * confirmation toast) should call this instead of {@link #decode(String)}
     * to avoid re-decoding the payload twice.
     */
    public static Decoded decodeFull(String text) {
        if (text == null) throw new IllegalArgumentException("null payload");
        String trimmed = text.trim();
        if (!trimmed.startsWith(MAGIC)) {
            throw new IllegalArgumentException("not a Waypointer export (expected " + MAGIC + " prefix)");
        }
        String payload = trimmed.substring(MAGIC.length());
        Exception v10Failure = null;
        boolean committedV10 = false;
        V10Transport.CheckedFrame v10Frame = null;
        if (V10Transport.looksLikeV10(payload)) {
            try {
                v10Frame = V10Transport.probe(payload);
            } catch (IOException | IllegalArgumentException failure) {
                v10Failure = failure;
            }
        }
        if (v10Frame != null) {
            try {
                return decodePayloadV10(v10Frame);
            } catch (IOException | IllegalArgumentException bodyFailure) {
                // A verified envelope with a bad body is almost certainly a
                // damaged V10 share. The legacy readers still get one silent
                // attempt so no pre-V10 code can ever be locked out by a
                // checksum coincidence; if they also fail, the V10 error wins.
                committedV10 = true;
                v10Failure = bodyFailure;
            }
        }
        try {
            return decodePayloadCurrent(payload);
        } catch (IOException | IllegalArgumentException e) {
            if (e instanceof InflatedPayloadLimitException) {
                throw new IllegalArgumentException("codec decode failed: " + e.getMessage(), e);
            }
            StringBuilder failures = new StringBuilder();
            if (v10Failure != null) {
                failures.append("v10=").append(v10Failure.getMessage()).append("; ");
            }
            failures.append("v9=").append(e.getMessage());
            Exception lastFailure = e;
            for (int version = LEGACY_V8_WIRE_VERSION; version >= LEGACY_V1_WIRE_VERSION; version--) {
                try {
                    return decodeLegacyPayload(payload, version);
                } catch (IOException | IllegalArgumentException legacyFailure) {
                    if (legacyFailure instanceof InflatedPayloadLimitException) {
                        throw new IllegalArgumentException("codec decode failed: "
                                + legacyFailure.getMessage(), legacyFailure);
                    }
                    failures.append("; v").append(version).append('=').append(legacyFailure.getMessage());
                    lastFailure = legacyFailure;
                }
            }
            if (committedV10) {
                throw new IllegalArgumentException(
                        "codec decode failed: v10=" + v10Failure.getMessage(), v10Failure);
            }
            throw new IllegalArgumentException("codec decode failed: " + failures, lastFailure);
        }
    }

    /**
     * Decode one exact, canonical wire-v9 payload without trying legacy codecs.
     *
     * <p>This is the trust boundary for catalog payloads. General clipboard and
     * chat imports must continue to use {@link #decodeFull(String)} so old route
     * codes remain compatible. Catalog data is server-canonical and therefore
     * must not contain surrounding whitespace or a different lossless encoding.
     */
    public static Decoded decodeCanonicalV9(String text) {
        if (text == null) throw new IllegalArgumentException("null payload");
        if (!text.equals(text.trim())) {
            throw new IllegalArgumentException("catalog route is not an exact canonical payload");
        }
        if (!text.startsWith(MAGIC)) {
            throw new IllegalArgumentException(
                    "not a Waypointer export (expected " + MAGIC + " prefix)");
        }

        try {
            Decoded decoded = decodePayloadCurrent(text.substring(MAGIC.length()));
            Options options = Options.FULL_FIDELITY.toBuilder()
                    .label(decoded.label())
                    .build();
            String canonical = encodeV9(decoded.groups(), options, PackingMode.AUTO);
            if (!canonical.equals(text)) {
                throw new IllegalArgumentException("catalog route is not canonical wire-v9");
            }
            return decoded;
        } catch (IOException e) {
            throw new IllegalArgumentException("catalog v9 decode failed: " + e.getMessage(), e);
        }
    }

    public static boolean isValidCodec(String text) {
        if (text == null) return false;
        try {
            Decoded decoded = decodeFull(text);
            return !decoded.groups().isEmpty();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Best-effort partial decode that only returns the sender's label, or
     * {@link Optional#empty()} if the payload has none / fails to decode.
     *
     * Used by chat-hover tooltips where we want to surface the label without
     * paying for a full group parse on every received chat line. Only a bounded
     * payload prefix and bounded inflated prefix are inspected. This preview is
     * intentionally unchecked; the click-to-import path performs full current
     * integrity validation and surfaces malformed input.
     */
    public static Optional<String> peekLabel(String text) {
        if (text == null) return Optional.empty();
        String trimmed = text.trim();
        if (!trimmed.startsWith(MAGIC)) return Optional.empty();
        String payload = trimmed.substring(MAGIC.length(),
                Math.min(trimmed.length(), MAGIC.length() + MAX_PEEK_PAYLOAD_CHARS));
        Optional<String> v10 = peekLabelV10(payload);
        if (v10.isPresent()) return v10;
        Optional<String> current = peekLabelCurrent(payload);
        if (current.isPresent()) return current;

        Optional<String> v8 = peekLabelLegacyV8(payload);
        if (v8.isPresent()) return v8;

        Optional<String> v7 = peekLabelLegacyV7(payload);
        if (v7.isPresent()) return v7;

        Optional<String> v6 = peekLabelLegacyV6(payload);
        if (v6.isPresent()) return v6;

        Optional<String> v5 = peekLabelLegacyV5(payload);
        if (v5.isPresent()) return v5;

        Optional<String> v4 = peekLabelLegacyV4(payload);
        if (v4.isPresent()) return v4;

        Optional<String> v3 = peekLabel(payload, LEGACY_V3_WIRE_VERSION, false);
        if (v3.isPresent()) return v3;

        Optional<String> v2 = peekLabel(payload, LEGACY_V2_WIRE_VERSION, true);
        return v2.isPresent() ? v2 : peekLabel(payload, LEGACY_V1_WIRE_VERSION, true);
    }

    /**
     * Result of {@link #decodeFull(String)}: the groups, whatever label the sender
     * stamped on, and any route-library metadata (folders, manual colors, paints)
     * carried by a V10 route-library share. Plain route shares report empty metadata.
     */
    public record Decoded(List<WaypointGroup> groups, String label, RouteLibraryMetadata metadata) {
        public Decoded {
            metadata = metadata == null ? RouteLibraryMetadata.empty() : metadata;
        }

        public Decoded(List<WaypointGroup> groups, String label) {
            this(groups, label, RouteLibraryMetadata.empty());
        }
    }

    /** Internal scratch type so {@link #readBody} can hand the label up to {@link #decodeFull} without a wider signature. */
    private static final class DecodedHeader {
        String label = "";
    }

    private static final class InflatedPayloadLimitException extends IOException {
        InflatedPayloadLimitException(int maxOutputBytes) {
            super("inflated payload exceeds " + maxOutputBytes + " bytes");
        }
    }

    public static DecodeDebug debugDecode(String text) {
        if (text == null) throw new IllegalArgumentException("null payload");
        long t0 = System.nanoTime();
        String trimmed = text.trim();
        if (!trimmed.startsWith(MAGIC)) {
            throw new IllegalArgumentException("not a Waypointer export (expected " + MAGIC + " prefix)");
        }
        String payload = trimmed.substring(MAGIC.length());
        Exception v10Failure = null;
        boolean committedV10 = false;
        V10Transport.CheckedFrame v10Frame = null;
        if (V10Transport.looksLikeV10(payload)) {
            try {
                v10Frame = V10Transport.probe(payload);
            } catch (IOException | IllegalArgumentException failure) {
                v10Failure = failure;
            }
        }
        if (v10Frame != null) {
            try {
                return debugDecodePayloadV10(text, payload, t0, v10Frame);
            } catch (IOException | IllegalArgumentException bodyFailure) {
                committedV10 = true;
                v10Failure = bodyFailure;
            }
        }
        try {
            return debugDecodePayloadCurrent(text, payload, t0);
        } catch (IOException | IllegalArgumentException e) {
            if (e instanceof InflatedPayloadLimitException) {
                throw new IllegalArgumentException("codec debug decode failed: " + e.getMessage(), e);
            }
            StringBuilder failures = new StringBuilder();
            if (v10Failure != null) {
                failures.append("v10=").append(v10Failure.getMessage()).append("; ");
            }
            failures.append("v9=").append(e.getMessage());
            Exception lastFailure = e;
            for (int version = LEGACY_V8_WIRE_VERSION; version >= LEGACY_V1_WIRE_VERSION; version--) {
                try {
                    return debugDecodeLegacyPayload(text, payload, t0, version);
                } catch (IOException | IllegalArgumentException legacyFailure) {
                    if (legacyFailure instanceof InflatedPayloadLimitException) {
                        throw new IllegalArgumentException("codec debug decode failed: "
                                + legacyFailure.getMessage(), legacyFailure);
                    }
                    failures.append("; v").append(version).append('=').append(legacyFailure.getMessage());
                    lastFailure = legacyFailure;
                }
            }
            if (committedV10) {
                throw new IllegalArgumentException(
                        "codec debug decode failed: v10=" + v10Failure.getMessage(), v10Failure);
            }
            throw new IllegalArgumentException("codec debug decode failed: " + failures, lastFailure);
        }
    }

    /** True iff {@code s} looks like a Waypointer export (prefix check only, does not validate payload). */
    public static boolean isCodecString(String s) {
        return s != null && s.trim().startsWith(MAGIC);
    }

    private static Decoded decodePayloadCurrent(String payload) throws IOException {
        byte[] compressed = decodeCanonicalV9Text(payload);
        byte[] raw = verifyAndStripChecksum(inflateV9(compressed));
        return decodeBody(raw, WIRE_VERSION, false);
    }

    private static Decoded decodePayloadV10(V10Transport.CheckedFrame frame) throws IOException {
        return V10RouteCodec.decode(frame);
    }

    private static Decoded decodeLegacyPayload(String payload, int version) throws IOException {
        return switch (version) {
            case LEGACY_V8_WIRE_VERSION -> decodePayloadLegacyV8(payload);
            case LEGACY_V7_WIRE_VERSION -> decodePayloadLegacyV7(payload);
            case LEGACY_V6_WIRE_VERSION -> decodePayloadLegacyV6(payload);
            case LEGACY_V5_WIRE_VERSION -> decodePayloadLegacyV5(payload);
            case LEGACY_V4_WIRE_VERSION -> decodePayloadLegacyV4(payload);
            case LEGACY_V3_WIRE_VERSION -> decodePayloadV3(payload);
            case LEGACY_V2_WIRE_VERSION -> decodePayloadV2(payload);
            case LEGACY_V1_WIRE_VERSION -> decodePayloadV1(payload);
            default -> throw new IllegalArgumentException("unsupported legacy wire version " + version);
        };
    }

    private static Decoded decodePayloadLegacyV8(String payload) throws IOException {
        byte[] compressed = AsciiStreamCodec.decode(unescapeHypixelEmotes(payload));
        byte[] raw = verifyAndStripChecksum(inflate(compressed));
        return decodeBody(raw, LEGACY_V8_WIRE_VERSION, false);
    }

    private static Decoded decodePayloadLegacyV7(String payload) throws IOException {
        return decodePayloadV3Shape(unescapeHypixelEmotes(payload), LEGACY_V7_WIRE_VERSION);
    }

    private static Decoded decodePayloadLegacyV6(String payload) throws IOException {
        return decodePayloadV3Shape(unescapeHypixelEmotes(payload), LEGACY_V6_WIRE_VERSION);
    }

    private static Decoded decodePayloadLegacyV5(String payload) throws IOException {
        return decodePayloadV3Shape(unescapeHypixelEmotes(payload), LEGACY_V5_WIRE_VERSION);
    }

    private static Decoded decodePayloadLegacyV4(String payload) throws IOException {
        return decodePayloadV3Shape(unescapeHypixelEmotes(payload), LEGACY_V4_WIRE_VERSION);
    }

    private static Decoded decodePayloadV3(String payload) throws IOException {
        return decodePayloadV3Shape(payload, LEGACY_V3_WIRE_VERSION);
    }

    private static Decoded decodePayloadV3Shape(String payload, int expectedVersion) throws IOException {
        byte[] compressed = switch (expectedVersion) {
            case LEGACY_V3_WIRE_VERSION -> AsciiStreamCodec.decodeLegacyV3(payload);
            case LEGACY_V4_WIRE_VERSION -> AsciiStreamCodec.decodeLegacyV4(payload);
            default -> AsciiStreamCodec.decode(payload);
        };
        byte[] raw = inflate(compressed);
        return decodeBody(raw, expectedVersion, false);
    }

    private static Decoded decodeBody(byte[] raw, int expectedVersion, boolean legacyV2) throws IOException {
        DecodedHeader headerOut = new DecodedHeader();
        List<WaypointGroup> groups = readBody(raw, null, headerOut, expectedVersion, legacyV2);
        return new Decoded(groups, headerOut.label);
    }

    /**
     * Split Hypixel's MVP++ emote triggers while staying inside the codec
     * alphabet, so chat scanning still sees one contiguous body. The escape
     * character itself is escaped too, making the transform reversible.
     */
    static String escapeHypixelEmotes(String body) {
        if (body == null || body.isEmpty()) return body;

        StringBuilder out = null;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            boolean needsEscape = c == HYPIXEL_EMOTE_ESCAPE || startsHypixelEmote(body, i);
            if (!needsEscape) {
                if (out != null) out.append(c);
                continue;
            }

            if (out == null) {
                out = new StringBuilder(body.length() + 4);
                out.append(body, 0, i);
            }
            out.append(c).append(HYPIXEL_EMOTE_ESCAPE);
        }
        return out == null ? body : out.toString();
    }

    static String unescapeHypixelEmotes(String body) {
        if (body == null || body.indexOf(HYPIXEL_EMOTE_ESCAPE) < 0) return body;

        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            out.append(c);
            if (i + 1 < body.length()
                    && body.charAt(i + 1) == HYPIXEL_EMOTE_ESCAPE
                    && isEscapedHypixelChar(body, i)) {
                i++;
            }
        }
        return out.toString();
    }

    private static byte[] decodeCanonicalV9Text(String payload) throws IOException {
        String unescaped = unescapeHypixelEmotes(payload);
        if (!escapeHypixelEmotes(unescaped).equals(payload)) {
            throw new IOException("non-canonical Hypixel escape sequence");
        }
        byte[] compressed = AsciiStreamCodec.decode(unescaped);
        if (!AsciiStreamCodec.encode(compressed).equals(unescaped)) {
            throw new IOException("non-canonical base-91 payload");
        }
        return compressed;
    }

    private static boolean startsHypixelEmote(String body, int i) {
        if (i + 1 >= body.length()) return false;

        char c = body.charAt(i);
        char next = body.charAt(i + 1);
        return (c == '<' && next == '3') || (c == 'o' && next == '/');
    }

    private static boolean isEscapedHypixelChar(String body, int i) {
        char c = body.charAt(i);
        char next = i + 2 < body.length() ? body.charAt(i + 2) : 0;
        return c == HYPIXEL_EMOTE_ESCAPE
                || (c == '<' && next == '3')
                || (c == 'o' && next == '/');
    }

    private static Decoded decodePayloadV2(String payload) throws IOException {
        byte[] compressed = AsciiPackCodec.decode(payload);
        byte[] raw = inflate(compressed);
        DecodedHeader headerOut = new DecodedHeader();
        List<WaypointGroup> groups = readBody(raw, null, headerOut, LEGACY_V2_WIRE_VERSION, true);
        return new Decoded(groups, headerOut.label);
    }

    private static Decoded decodePayloadV1(String payload) throws IOException {
        byte[] compressed = CjkBase16384.decode(payload);
        byte[] raw = inflate(compressed);
        DecodedHeader headerOut = new DecodedHeader();
        List<WaypointGroup> groups = readBody(raw, null, headerOut, LEGACY_V1_WIRE_VERSION, true);
        return new Decoded(groups, headerOut.label);
    }

    private static Optional<String> peekLabelCurrent(String payload) {
        try {
            byte[] compressed = decodeCanonicalV9Text(payload);
            byte[] raw = inflateV9Prefix(compressed, 1 + 5 + MAX_LABEL_BYTES);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            int header = in.readUnsignedByte();
            if ((header & HEADER_VERSION_MASK) != WIRE_VERSION) return Optional.empty();
            int contentKind = v9ContentKind(header);
            if (!isRouteContentKind(contentKind)) return Optional.empty();
            if ((header & V9_HEADER_FLAG_LABEL) == 0) return Optional.empty();
            String label = Options.sanitizeLabel(readLabel(in));
            return label.isEmpty() ? Optional.empty() : Optional.of(label);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> peekLabelV10(String payload) {
        try {
            if (!V10Transport.looksLikeV10(payload)) return Optional.empty();
            byte[] framed = AsciiStreamCodec.decode(V10Transport.unescapeContextual(payload));
            if (framed.length == 0) return Optional.empty();
            int header = framed[0] & 0xFF;
            int mode = V10Transport.modeOf((byte) header);
            header &= ~V10Transport.HEADER_MODE_BIT;
            // Enough for a kind-7 header plus label, or a kind-6 library prefix
            // (subtype, route length) followed by the inner kind-7 header.
            byte[] body = mode == V10Transport.MODE_DIRECT
                    ? Arrays.copyOfRange(framed, 1, framed.length)
                    : V10Transport.inflatePrefix(
                            Arrays.copyOfRange(framed, 1, framed.length),
                            5 + 5 + 1 + 5 + MAX_LABEL_BYTES);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            int kind = v9ContentKind(header);
            if (kind == V10RouteLibraryCodec.CONTENT_KIND) {
                if (readVarint(in) != V10RouteLibraryCodec.SUBTYPE_ROUTE_LIBRARY) {
                    return Optional.empty();
                }
                readVarint(in); // inner route length; the label sits right after its header
                header = in.readUnsignedByte();
                if ((header & HEADER_VERSION_MASK) != V10_WIRE_VERSION) return Optional.empty();
                kind = v9ContentKind(header);
            }
            if (kind != V10GeneralRouteCodec.LABELED_CONTENT_KIND) {
                return Optional.empty();
            }
            String label = Options.sanitizeLabel(readLabel(in));
            return label.isEmpty() ? Optional.empty() : Optional.of(label);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static String encodeLegacyForTest(List<WaypointGroup> groups, Options opts, PackingMode mode,
                                      int wireVersion) {
        if (wireVersion < LEGACY_V5_WIRE_VERSION || wireVersion > LEGACY_V8_WIRE_VERSION) {
            throw new IllegalArgumentException("test legacy writer supports only v5-v8");
        }
        validateEncodeInput(groups, opts, mode);
        try {
            byte[] body = writeBody(groups, opts, mode, wireVersion, true,
                    wireVersion >= RANGE_DELTA_MIN_VERSION);
            byte[] framed = wireVersion >= LEGACY_V8_WIRE_VERSION ? appendChecksum(body) : body;
            byte[] compressed = deflate(framed);
            return MAGIC + escapeHypixelEmotes(AsciiStreamCodec.encode(compressed));
        } catch (IOException exception) {
            throw new IllegalStateException("legacy test codec encode failed", exception);
        }
    }

    /** Input validation shared with the V10 route-library writer, which bypasses {@link #encode}. */
    static void validateEncodeInputForV10(List<WaypointGroup> groups, Options opts) {
        validateEncodeInput(groups, opts, PackingMode.AUTO);
    }

    private static void validateEncodeInput(List<WaypointGroup> groups, Options opts,
                                            PackingMode mode) {
        if (groups == null) throw new IllegalArgumentException("groups cannot be null");
        if (opts == null) throw new IllegalArgumentException("options cannot be null");
        if (mode == null) throw new IllegalArgumentException("packing mode cannot be null");
        if (groups.size() > MAX_WIRE_GROUPS) {
            throw new IllegalArgumentException("group count exceeds wire limit: " + groups.size());
        }
        boolean exactV10BarePackProjection = mode == PackingMode.AUTO
                && V10BareRoutePackCodec.canEncode(groups, opts);
        int totalWaypoints = 0;
        for (int index = 0; index < groups.size(); index++) {
            WaypointGroup group = groups.get(index);
            if (group == null) throw new IllegalArgumentException("group " + index + " is null");
            boolean exactV10BareProjection = mode == PackingMode.AUTO
                    && groups.size() == 1
                    && V10BareRouteCodec.canEncode(group, opts);
            if (!exactV10BareProjection && !exactV10BarePackProjection) {
                validateRouteDisplayName(group.name(), "group " + index + " name");
            }
            validateWireStringForEncode(exportedZoneId(group, opts), "group " + index + " zone");
            if (group.size() > MAX_WIRE_WAYPOINTS_PER_GROUP) {
                throw new IllegalArgumentException("group " + index + " exceeds waypoint limit: "
                        + group.size());
            }
            totalWaypoints += group.size();
            if (totalWaypoints > MAX_WIRE_WAYPOINTS_TOTAL) {
                throw new IllegalArgumentException("total waypoint count exceeds wire limit: "
                        + totalWaypoints);
            }
            if (opts.includeNames) {
                for (int waypointIndex = 0; waypointIndex < group.size(); waypointIndex++) {
                    validateRouteDisplayName(group.get(waypointIndex).name(),
                            "group " + index + " waypoint " + waypointIndex + " name");
                }
            }
        }
    }

    private static void validateV9BodySize(byte[] body) {
        if ((long) body.length + CHECKSUM_BYTES > MAX_INFLATED_BYTES) {
            throw new IllegalArgumentException("encoded v9 body exceeds inflated wire limit: "
                    + body.length + " bytes before checksum");
        }
    }

    /** Reject a persisted display name that cannot safely round-trip through the native codec. */
    public static void validateRouteDisplayName(String value, String field) {
        if (!isValidRouteDisplayName(value)) {
            throw new IllegalArgumentException(field + " is unsafe or exceeds "
                    + MAX_ROUTE_DISPLAY_NAME_BYTES + " UTF-8 bytes");
        }
    }

    private static void validateWireStringForEncode(String value, String field) {
        int bytes = strictUtf8Length(value);
        if (bytes < 0) throw new IllegalArgumentException(field + " is not valid Unicode");
        if (bytes > MAX_WIRE_STRING_BYTES) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_WIRE_STRING_BYTES
                    + " UTF-8 bytes");
        }
    }

    public static boolean isValidRouteDisplayName(String value) {
        String actual = value == null ? "" : value;
        int encodedLength = strictUtf8Length(actual);
        if (encodedLength < 0 || encodedLength > MAX_ROUTE_DISPLAY_NAME_BYTES) return false;
        for (int index = 0; index < actual.length(); index++) {
            char character = actual.charAt(index);
            if (character == '\u00A7' || character < 0x20 || character == 0x7F) return false;
        }
        return true;
    }

    static int strictUtf8Length(String value) {
        try {
            return encodeUtf8Strict(value).length;
        } catch (IOException ignored) {
            return -1;
        }
    }

    static byte[] encodeUtf8Strict(String value) throws IOException {
        String actual = value == null ? "" : value;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(actual));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IOException("string is not valid Unicode", exception);
        }
    }

    static String decodeUtf8Strict(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("wire string is not valid UTF-8", exception);
        }
    }

    private static Optional<String> peekLabelLegacyV8(String payload) {
        return peekLabel(unescapeHypixelEmotes(payload), LEGACY_V8_WIRE_VERSION, false);
    }

    private static Optional<String> peekLabelLegacyV7(String payload) {
        return peekLabel(unescapeHypixelEmotes(payload), LEGACY_V7_WIRE_VERSION, false);
    }

    private static Optional<String> peekLabelLegacyV6(String payload) {
        return peekLabel(unescapeHypixelEmotes(payload), LEGACY_V6_WIRE_VERSION, false);
    }

    private static Optional<String> peekLabelLegacyV5(String payload) {
        return peekLabel(unescapeHypixelEmotes(payload), LEGACY_V5_WIRE_VERSION, false);
    }

    private static Optional<String> peekLabelLegacyV4(String payload) {
        return peekLabel(unescapeHypixelEmotes(payload), LEGACY_V4_WIRE_VERSION, false);
    }

    private static Optional<String> peekLabel(String payload, int expectedVersion, boolean legacyV2) {
        try {
            byte[] compressed = expectedVersion == LEGACY_V1_WIRE_VERSION
                    ? CjkBase16384.decode(payload)
                    : legacyV2
                    ? AsciiPackCodec.decode(payload)
                    : switch (expectedVersion) {
                        case LEGACY_V3_WIRE_VERSION -> AsciiStreamCodec.decodeLegacyV3(payload);
                        case LEGACY_V4_WIRE_VERSION -> AsciiStreamCodec.decodeLegacyV4(payload);
                        default -> AsciiStreamCodec.decode(payload);
                    };
            byte[] raw = inflatePrefix(compressed, 1 + 5 + MAX_LABEL_BYTES);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            int header = in.readUnsignedByte();
            if ((header & HEADER_VERSION_MASK) != expectedVersion) return Optional.empty();
            if ((header & HEADER_FLAG_LABEL) == 0) return Optional.empty();
            String label = Options.sanitizeLabel(readLabel(in));
            return label.isEmpty() ? Optional.empty() : Optional.of(label);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static DecodeDebug debugDecodePayloadCurrent(String input, String payload, long startNanos)
            throws IOException {
        byte[] compressed = decodeCanonicalV9Text(payload);
        byte[] raw = verifyAndStripChecksum(inflateV9(compressed));
        return buildDebugDecode(input, payload, startNanos, WIRE_VERSION, false, compressed, raw);
    }

    private static DecodeDebug debugDecodePayloadV10(String input, String payload, long startNanos,
                                                      V10Transport.CheckedFrame frame)
            throws IOException {
        V10Transport.Frame transport = V10Transport.decode(payload);
        if (V10GeneralRouteCodec.isGeneralKind(frame.contentKind())) {
            byte[] semantic = frame.semantic();
            byte[] v9Shape = semantic.clone();
            v9Shape[0] = (byte) v9ShapeHeaderForV10General(frame.header());
            DebugCapture capture = new DebugCapture();
            List<WaypointGroup> groups = readBody(v9Shape, capture, null, WIRE_VERSION, false);
            long elapsed = System.nanoTime() - startNanos;
            DecodeDebug shape = capture.build(
                    input, payload,
                    frame.mode() == V10Transport.MODE_DIRECT
                            ? "ASCII contextual base-91 + V10 general direct + CRC-16"
                            : "ASCII contextual base-91 + V10 general raw DEFLATE + CRC-16",
                    transport.payload(), semantic, groups, elapsed);
            return new DecodeDebug(
                    shape.rawInput(), shape.inputChars(), shape.magic(), shape.payloadChars(),
                    shape.textEncoding(), shape.compressedBytes(), shape.rawBodyBytes(),
                    shape.charsPerRawByte(), frame.header(), V10_WIRE_VERSION,
                    shape.includesNames(), shape.hasLabel(), false, false,
                    shape.label(), shape.stringPool(), shape.groups(), shape.decodedGroups(),
                    shape.decodeNanos());
        }
        Decoded decoded = frame.contentKind() == V10DungeonBodyCodec.CONTENT_KIND
                ? new Decoded(V10DungeonCodec.decode(frame).routes(), "")
                : V10RouteCodec.decode(frame);
        byte[] semantic = frame.semantic();
        String coordinateMode = switch (frame.contentKind()) {
            case V10GeneralRouteCodec.CONTENT_KIND, V10GeneralRouteCodec.LABELED_CONTENT_KIND ->
                    frame.mode() == V10Transport.MODE_DIRECT
                            ? "V10_GENERAL_DIRECT" : "V10_GENERAL_DEFLATE";
            case 2 -> frame.mode() == V10Transport.MODE_DIRECT
                    ? switch (V10BareEntropyCodec.descriptor(semantic)) {
                        case RICE -> "V10_RICE";
                        case QUOTIENT -> "V10_QUOTIENT";
                        case RESERVED_GOLOMB -> "V10_RESERVED_GOLOMB";
                    }
                    : "V10_DELTA_DEFLATE";
            case V10SparseRouteCodec.CONTENT_KIND -> frame.mode() == V10Transport.MODE_DIRECT
                    ? "V10_SPARSE_RICE" : "V10_SPARSE_DELTA_DEFLATE";
            case V10BareRoutePackCodec.CONTENT_KIND ->
                    V10RouteLibraryCodec.isLibrarySemantic(semantic)
                            ? (frame.mode() == V10Transport.MODE_DIRECT
                                    ? "V10_LIBRARY_DIRECT" : "V10_LIBRARY_DEFLATE")
                            : (frame.mode() == V10Transport.MODE_DIRECT
                                    ? "V10_BARE_PACK_DIRECT" : "V10_BARE_PACK_DELTA_DEFLATE");
            case V10DungeonBodyCodec.CONTENT_KIND -> frame.mode() == V10Transport.MODE_DIRECT
                    ? "V10_DUNGEON_DIRECT" : "V10_DUNGEON_DEFLATE";
            default -> "V10_KIND_" + frame.contentKind();
        };
        List<DecodeDebug.GroupDebug> groupDebug = new ArrayList<>(decoded.groups().size());
        boolean includesNames = false;
        for (int groupIndex = 0; groupIndex < decoded.groups().size(); groupIndex++) {
            WaypointGroup group = decoded.groups().get(groupIndex);
            List<DecodeDebug.WaypointDebug> waypoints = new ArrayList<>(group.size());
            for (int index = 0; index < group.size(); index++) {
                Waypoint waypoint = group.get(index);
                includesNames |= waypoint.hasName();
                waypoints.add(new DecodeDebug.WaypointDebug(
                        index,
                        waypoint.x(), waypoint.y(), waypoint.z(),
                        -1,
                        waypoint.hasName(),
                        (waypoint.color() & 0xFFFFFF) != (Waypoint.DEFAULT_COLOR & 0xFFFFFF),
                        waypoint.customRadius() > 0,
                        waypoint.flags() != 0,
                        waypoint.name(), waypoint.color(), waypoint.customRadius(), waypoint.flags()));
            }
            groupDebug.add(new DecodeDebug.GroupDebug(
                    groupIndex,
                    group.name(),
                    group.zoneId(),
                    -1,
                    group.enabled(),
                    group.gradientMode() == WaypointGroup.GradientMode.AUTO,
                    group.loadMode() == WaypointGroup.LoadMode.SEQUENCE,
                    Double.compare(group.defaultRadius(), Waypoint.DEFAULT_REACH_RADIUS) != 0,
                    coordinateMode,
                    frame.mode(),
                    group.defaultRadius(),
                    group.currentIndex(),
                    group.size(),
                    -1,
                    -1,
                    List.copyOf(waypoints)));
        }
        long elapsed = System.nanoTime() - startNanos;
        double ratio = semantic.length == 0 ? 0.0 : (double) input.length() / semantic.length;
        return new DecodeDebug(
                input,
                input.length(),
                MAGIC,
                payload.length(),
                frame.mode() == V10Transport.MODE_DIRECT
                        ? "ASCII contextual base-91 + " + coordinateMode + " + CRC-16"
                        : "ASCII contextual base-91 + V10 raw DEFLATE + CRC-16",
                transport.payload().length,
                semantic.length,
                ratio,
                frame.header(),
                V10_WIRE_VERSION,
                includesNames,
                frame.contentKind() == V10GeneralRouteCodec.LABELED_CONTENT_KIND,
                false,
                false,
                decoded.label(),
                List.of(),
                List.copyOf(groupDebug),
                decoded.groups(),
                elapsed);
    }

    private static DecodeDebug debugDecodeLegacyPayload(String input, String payload, long startNanos,
                                                        int version) throws IOException {
        return switch (version) {
            case LEGACY_V8_WIRE_VERSION -> debugDecodePayloadLegacyV8(input, payload, startNanos);
            case LEGACY_V7_WIRE_VERSION -> debugDecodePayloadLegacyV7(input, payload, startNanos);
            case LEGACY_V6_WIRE_VERSION -> debugDecodePayloadLegacyV6(input, payload, startNanos);
            case LEGACY_V5_WIRE_VERSION -> debugDecodePayloadLegacyV5(input, payload, startNanos);
            case LEGACY_V4_WIRE_VERSION -> debugDecodePayload(input, payload,
                    unescapeHypixelEmotes(payload), startNanos, version, false);
            case LEGACY_V3_WIRE_VERSION -> debugDecodePayload(input, payload, startNanos, version, false);
            case LEGACY_V2_WIRE_VERSION, LEGACY_V1_WIRE_VERSION ->
                    debugDecodePayload(input, payload, startNanos, version, true);
            default -> throw new IllegalArgumentException("unsupported legacy wire version " + version);
        };
    }

    private static DecodeDebug debugDecodePayloadLegacyV8(String input, String payload, long startNanos)
            throws IOException {
        String decodePayload = unescapeHypixelEmotes(payload);
        byte[] compressed = AsciiStreamCodec.decode(decodePayload);
        byte[] raw = verifyAndStripChecksum(inflate(compressed));
        return buildDebugDecode(input, payload, startNanos,
                LEGACY_V8_WIRE_VERSION, false, compressed, raw);
    }

    private static DecodeDebug debugDecodePayloadLegacyV7(String input, String payload, long startNanos)
            throws IOException {
        return debugDecodePayload(input, payload, unescapeHypixelEmotes(payload),
                startNanos, LEGACY_V7_WIRE_VERSION, false);
    }

    private static DecodeDebug debugDecodePayloadLegacyV6(String input, String payload, long startNanos)
            throws IOException {
        return debugDecodePayload(input, payload, unescapeHypixelEmotes(payload),
                startNanos, LEGACY_V6_WIRE_VERSION, false);
    }

    private static DecodeDebug debugDecodePayloadLegacyV5(String input, String payload, long startNanos)
            throws IOException {
        return debugDecodePayload(input, payload, unescapeHypixelEmotes(payload),
                startNanos, LEGACY_V5_WIRE_VERSION, false);
    }

    private static DecodeDebug debugDecodePayload(String input, String payload, long startNanos,
                                                  int expectedVersion, boolean legacyV2)
            throws IOException {
        return debugDecodePayload(input, payload, payload, startNanos, expectedVersion, legacyV2);
    }

    private static DecodeDebug debugDecodePayload(String input, String reportedPayload, String decodePayload,
                                                  long startNanos, int expectedVersion, boolean legacyV2)
            throws IOException {
        byte[] compressed = expectedVersion == LEGACY_V1_WIRE_VERSION
                ? CjkBase16384.decode(decodePayload)
                : legacyV2
                ? AsciiPackCodec.decode(decodePayload)
                : switch (expectedVersion) {
                    case LEGACY_V3_WIRE_VERSION -> AsciiStreamCodec.decodeLegacyV3(decodePayload);
                    case LEGACY_V4_WIRE_VERSION -> AsciiStreamCodec.decodeLegacyV4(decodePayload);
                    default -> AsciiStreamCodec.decode(decodePayload);
                };
        byte[] raw = inflate(compressed);
        return buildDebugDecode(input, reportedPayload, startNanos, expectedVersion, legacyV2, compressed, raw);
    }

    private static DecodeDebug buildDebugDecode(String input, String reportedPayload, long startNanos,
                                                int expectedVersion, boolean legacyV2,
                                                byte[] compressed, byte[] raw) throws IOException {
        DebugCapture cap = new DebugCapture();
        List<WaypointGroup> groups = readBody(raw, cap, null, expectedVersion, legacyV2);
        long elapsed = System.nanoTime() - startNanos;
        String encoding = switch (expectedVersion) {
            case WIRE_VERSION -> TEXT_ENCODING_V9;
            case LEGACY_V8_WIRE_VERSION -> TEXT_ENCODING_V8;
            case LEGACY_V7_WIRE_VERSION -> TEXT_ENCODING_V7;
            case LEGACY_V6_WIRE_VERSION -> TEXT_ENCODING_V6;
            case LEGACY_V5_WIRE_VERSION -> TEXT_ENCODING_V5;
            case LEGACY_V4_WIRE_VERSION -> TEXT_ENCODING_V4;
            case LEGACY_V3_WIRE_VERSION -> TEXT_ENCODING_V3;
            case LEGACY_V2_WIRE_VERSION -> TEXT_ENCODING_V2;
            case LEGACY_V1_WIRE_VERSION -> TEXT_ENCODING_V1;
            default -> "unknown";
        };
        return cap.build(input, reportedPayload, encoding, compressed, raw, groups, elapsed);
    }

    // --- writer -------------------------------------------------------------------------------

    private static byte[] writeCompactV9Body(WaypointGroup group, Options opts) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.writeByte(buildV9Header(opts, V9_CONTENT_KIND_COMPACT_ROUTE));
        if (!opts.label.isEmpty()) writeLabel(out, opts.label);
        out.write(V9CompactCodec.encodePayload(group));
        out.flush();
        return buf.toByteArray();
    }

    private static byte[] writeCompactCoordinateV9Body(WaypointGroup group, Options opts) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.writeByte(buildV9Header(opts, V9_CONTENT_KIND_COORDINATE_ROUTE));
        if (!opts.label.isEmpty()) writeLabel(out, opts.label);
        out.write(V9CompactCodec.encodeCoordinatePayload(group));
        out.flush();
        return buf.toByteArray();
    }

    private static byte[] writeBody(List<WaypointGroup> groups, Options opts, PackingMode mode,
                                    int wireVersion, boolean extendedCoordModes,
                                    boolean allowRangeDelta) throws IOException {
        if (wireVersion >= ANONYMOUS_SINGLE_GROUP_MIN_VERSION
                && shouldUseAnonymousSingleGroup(groups, opts)) {
            return writeAnonymousBody(groups.get(0), opts, mode, wireVersion, extendedCoordModes);
        }

        return writeGeneralBody(groups, opts, mode, wireVersion, extendedCoordModes,
                allowRangeDelta, buildHeaderByte(opts, wireVersion));
    }

    private static byte[] writeGeneralBody(
            List<WaypointGroup> groups, Options opts, PackingMode mode,
            int shapeVersion, boolean extendedCoordModes, boolean allowRangeDelta,
            int header) throws IOException {

        StringPool pool = buildStringPool(groups, opts);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.writeByte(header);
        if (!opts.label.isEmpty()) writeLabel(out, opts.label);
        pool.writeTo(out);
        writeVarint(out, groups.size());

        for (WaypointGroup g : groups) {
            writeGroup(buf, out, g, pool, opts, mode, shapeVersion,
                    extendedCoordModes, allowRangeDelta);
        }
        out.flush();
        return buf.toByteArray();
    }

    /** V10 kind 0 uses the V9 general body shape without its compression dictionary. */
    static byte[] encodeV10GeneralSemantic(
            List<WaypointGroup> groups, Options opts, PackingMode mode) throws IOException {
        // A labeled export is kind 7 (label follows the header); an unlabeled
        // one is kind 0. Header bit 7 belongs to the transport mode in V10.
        int header = opts.label.isEmpty()
                ? V10GeneralRouteCodec.SEMANTIC_HEADER
                : V10GeneralRouteCodec.LABELED_SEMANTIC_HEADER;
        return writeGeneralBody(groups, opts, mode, WIRE_VERSION, true, true, header);
    }

    /** True when a V10 semantic header names the general route body, with or without a label. */
    static boolean isV10GeneralHeader(int header) {
        return (header & HEADER_VERSION_MASK) == V10_WIRE_VERSION
                && V10GeneralRouteCodec.isGeneralKind(v9ContentKind(header));
    }

    /** Header byte of the V9-shaped body that mirrors a V10 kind 0/7 semantic. */
    static int v9ShapeHeaderForV10General(int header) {
        boolean labeled = v9ContentKind(header) == V10GeneralRouteCodec.LABELED_CONTENT_KIND;
        return WIRE_VERSION | (labeled ? V9_HEADER_FLAG_LABEL : 0);
    }

    static Decoded decodeV10GeneralSemantic(byte[] semantic) throws IOException {
        if (semantic == null || semantic.length == 0) {
            throw new IOException("empty v10 general semantic body");
        }
        int header = semantic[0] & 0xFF;
        if (!isV10GeneralHeader(header)) {
            throw new IOException("v10 general semantic header mismatch");
        }
        byte[] v9Shape = semantic.clone();
        v9Shape[0] = (byte) v9ShapeHeaderForV10General(header);
        return decodeBody(v9Shape, WIRE_VERSION, false);
    }

    private static boolean shouldUseAnonymousSingleGroup(List<WaypointGroup> groups, Options opts) {
        if (groups.size() != 1) return false;
        if (opts.includeNames || opts.includeColors || opts.includeRadii || opts.includeWaypointFlags) {
            return false;
        }
        WaypointGroup group = groups.get(0);
        if (opts.includeGroupMeta && !group.skipAheadEnabled()) return false;
        return hasAnonymousCoordinateOnlyWaypointBodies(group.waypoints(), opts);
    }

    private static boolean hasAnonymousCoordinateOnlyWaypointBodies(List<Waypoint> pts, Options opts) {
        for (Waypoint w : pts) {
            if (exportedWaypointFlags(w, opts) != 0) return false;
        }
        return true;
    }

    private static byte[] writeAnonymousBody(WaypointGroup group, Options opts, PackingMode mode,
                                             int wireVersion, boolean extendedCoordModes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        int header = wireVersion == WIRE_VERSION
                ? buildV9Header(opts, V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META)
                : buildHeaderByte(opts, wireVersion) | HEADER_FLAG_ANONYMOUS_SINGLE_GROUP;
        out.writeByte(header);
        if (!opts.label.isEmpty()) writeLabel(out, opts.label);
        out.flush();

        int baseGroupFlags = GROUP_FLAG_BODYLESS_WAYPOINTS;
        boolean customRadius = false;
        if (opts.includeGroupMeta) {
            if (group.loadMode() == WaypointGroup.LoadMode.SEQUENCE) baseGroupFlags |= GROUP_FLAG_LOAD_SEQUENCE;
            customRadius = wireVersion == WIRE_VERSION
                    ? Double.compare(group.defaultRadius(), Waypoint.DEFAULT_REACH_RADIUS) != 0
                    : Math.abs(group.defaultRadius() - Waypoint.DEFAULT_REACH_RADIUS) > 0.001;
            if (customRadius) baseGroupFlags |= GROUP_FLAG_CUSTOM_RADIUS;
        } else {
            baseGroupFlags |= GROUP_FLAG_LOAD_SEQUENCE;
        }

        CoordPicked picked = pickAnonymousCoordMode(group, opts, mode, baseGroupFlags,
                customRadius, buf.toByteArray(), wireVersion, extendedCoordModes);
        writeAnonymousGroupRecord(out, group, opts, picked, baseGroupFlags, customRadius,
                wireVersion, extendedCoordModes);
        out.flush();
        return buf.toByteArray();
    }

    private static void writeAnonymousGroupRecord(DataOutputStream out, WaypointGroup group, Options opts,
                                                  CoordPicked picked,
                                                  int baseGroupFlags, boolean customRadius,
                                                  int wireVersion, boolean extendedCoordModes) throws IOException {
        writeAnonymousZoneRef(out, exportedZoneId(group, opts));
        int groupFlags = baseGroupFlags | encodeCoordModeFlags(picked.mode, extendedCoordModes);
        out.writeByte(groupFlags);
        if (customRadius) writeRadius(out, group.defaultRadius(), wireVersion);
        writeVarint(out, group.size());
        out.write(picked.bytes);
    }

    /**
     * Write the optional sender label as varint length + strict UTF-8 bytes.
     * The label is already sanitized and character-capped at this point; the
     * byte limit remains a defense-in-depth check and rejects instead of
     * truncating a multi-byte code point.
     */
    private static void writeLabel(DataOutputStream out, String label) throws IOException {
        byte[] bytes = encodeUtf8Strict(label);
        if (bytes.length > MAX_LABEL_BYTES) {
            throw new IOException("label exceeds UTF-8 byte limit: " + bytes.length);
        }
        writeVarint(out, bytes.length);
        out.write(bytes);
    }

    /** Read the optional sender label. Mirrors {@link #writeLabel}; caller must have already confirmed the header bit. */
    private static String readLabel(DataInputStream in) throws IOException {
        int len = readVarint(in);
        if (len < 0 || len > MAX_LABEL_BYTES) {
            throw new IOException("label length out of range: " + len);
        }
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) throw new IOException("truncated label payload");
        return decodeUtf8Strict(bytes);
    }

    private static void writeUtf8String(DataOutputStream out, String value) throws IOException {
        byte[] bytes = encodeUtf8Strict(value);
        if (bytes.length > MAX_WIRE_STRING_BYTES) {
            throw new IOException("string too long: " + bytes.length);
        }
        writeVarint(out, bytes.length);
        out.write(bytes);
    }

    private static String readUtf8String(DataInputStream in) throws IOException {
        int len = readVarint(in);
        if (len < 0 || len > MAX_WIRE_STRING_BYTES) throw new IOException("string too long: " + len);
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) throw new IOException("truncated string");
        return decodeUtf8Strict(bytes);
    }

    /**
     * Zone id actually written for {@code group}.
     *
     * With {@link Options#includeZone} off the sender's island is replaced by
     * {@link Zone#UNKNOWN}, which the import paths already treat as "no island
     * recorded" and snap to whatever island the recipient is standing on. Every
     * writer -- pool building, group records, the anonymous single-group body,
     * and the coord-mode scorer -- funnels through here so the string pool and
     * the emitted refs can never disagree about which zone was exported.
     */
    private static String exportedZoneId(WaypointGroup group, Options opts) {
        return opts.includeZone ? group.zoneId() : Zone.UNKNOWN.id();
    }

    /**
     * Zone refs are tagged varints: odd values point into the built-in
     * Skyblocker-derived zone dictionary, even values point into the string pool.
     * Unknown/custom zones therefore still round-trip exactly.
     */
    private static boolean isUnrecordedZone(String zoneId) {
        return zoneId == null || zoneId.isBlank() || Zone.UNKNOWN.id().equals(zoneId);
    }

    /**
     * "No island" is written as the pool's reserved empty slot rather than by
     * interning the literal {@code "unknown"}.
     *
     * <p>Index 0 always holds {@code ""}, so this is a single varint byte -- the
     * same cost as a dictionary hit, which means dropping the island can never
     * make a share code longer than keeping it. It also needs nothing new from
     * the reader: existing clients already run every decoded zone through
     * {@link Zone#canonicalId}, which turns a blank id into {@code unknown},
     * and their import paths already snap unknown-island routes to whatever
     * island the recipient is standing on.
     */
    private static void writeZoneRef(DataOutputStream out, String zoneId, StringPool pool)
            throws IOException {
        if (isUnrecordedZone(zoneId)) {
            writeVarint(out, pool.index("") << 1);
            return;
        }
        int dictIndex = CodecZoneDictionary.indexOf(zoneId);
        if (dictIndex >= 0) {
            writeVarint(out, (dictIndex << 1) | 1);
            return;
        }
        writeVarint(out, pool.index(zoneId) << 1);
    }

    /** Pool-less variant; the inline-string form carries the empty id instead. */
    private static void writeAnonymousZoneRef(DataOutputStream out, String zoneId) throws IOException {
        if (isUnrecordedZone(zoneId)) {
            writeVarint(out, 0);
            writeUtf8String(out, "");
            return;
        }
        int dictIndex = CodecZoneDictionary.indexOf(zoneId);
        if (dictIndex >= 0) {
            writeVarint(out, (dictIndex << 1) | 1);
            return;
        }
        writeVarint(out, 0);
        writeUtf8String(out, zoneId);
    }

    /**
     * Resolve a zone ref, treating anything it cannot place as "no island".
     *
     * <p>A zone is the one field where refusing the payload helps nobody: the
     * coordinates, names, and route are all still perfectly good, and the
     * import paths already know how to place an unknown-island route (they snap
     * it to whatever island the recipient is standing on). So an index outside
     * the dictionary -- a deliberately reserved one, a zone added by a newer
     * Waypointer, or a corrupt byte -- degrades to {@link Zone#UNKNOWN} instead
     * of failing the whole decode. Malformed *structure* is still rejected; this
     * leniency is scoped to the zone value alone.
     */
    private static String readZoneRef(DataInputStream in, List<String> pool) throws IOException {
        return resolveZoneRef(readVarint(in), pool);
    }

    static String resolveZoneRef(int ref, List<String> pool) throws IOException {
        if ((ref & 1) != 0) {
            int dictIndex = ref >>> 1;
            return CodecZoneDictionary.isKnownIndex(dictIndex)
                    ? CodecZoneDictionary.idAt(dictIndex)
                    : Zone.UNKNOWN.id();
        }
        return zoneOrUnknown(poolGet(pool, ref >>> 1));
    }

    /** Blank pooled zone strings mean the same thing as an unplaceable index. */
    private static String zoneOrUnknown(String zoneId) {
        return isUnrecordedZone(zoneId) ? Zone.UNKNOWN.id() : zoneId;
    }

    /** Same leniency as {@link #readZoneRef}, for the pool-less anonymous body. */
    private static String readAnonymousZoneRef(DataInputStream in) throws IOException {
        int ref = readVarint(in);
        if ((ref & 1) != 0) {
            int dictIndex = ref >>> 1;
            return CodecZoneDictionary.isKnownIndex(dictIndex)
                    ? CodecZoneDictionary.idAt(dictIndex)
                    : Zone.UNKNOWN.id();
        }
        if (ref == 0) {
            return zoneOrUnknown(readUtf8String(in));
        }
        throw new IOException("anonymous zone ref cannot use string pool index: " + ref);
    }

    private static StringPool buildStringPool(List<WaypointGroup> groups, Options opts) {
        StringPool pool = new StringPool();
        Map<String, Integer> waypointNameCounts = countWaypointNames(groups, opts);

        // Reserve index 0 for "" so group/waypoint records can omit names without a null check.
        pool.intern("");
        for (WaypointGroup g : groups) {
            pool.intern(g.name());
            // Mirrors writeZoneRef: only zones that will actually be written as
            // pooled strings belong here. An unrecorded island reuses the
            // reserved empty slot and must not intern "unknown" on top of it.
            String zoneId = exportedZoneId(g, opts);
            if (!isUnrecordedZone(zoneId) && CodecZoneDictionary.indexOf(zoneId) < 0) {
                pool.intern(zoneId);
            }
            if (opts.includeNames) {
                for (Waypoint w : g.waypoints()) {
                    if (w.hasName() && waypointNameCounts.getOrDefault(w.name(), 0) > 1) {
                        pool.internWaypointName(w.name());
                    }
                }
            }
        }
        return pool;
    }

    private static Map<String, Integer> countWaypointNames(List<WaypointGroup> groups, Options opts) {
        Map<String, Integer> counts = new HashMap<>();
        if (!opts.includeNames) return counts;

        for (WaypointGroup g : groups) {
            for (Waypoint w : g.waypoints()) {
                if (w.hasName()) counts.merge(w.name(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Pack the first header byte: version in the low nibble, export flags in
     * the high nibble. Keeping version at a fixed, well-known position means
     * the decoder can validate the schema before touching anything else.
     */
    private static int buildHeaderByte(Options opts, int wireVersion) {
        if (wireVersion == WIRE_VERSION) {
            return buildV9Header(opts, V9_CONTENT_KIND_GENERAL_ROUTE);
        }
        int header = wireVersion & HEADER_VERSION_MASK;
        if (opts.includeNames) header |= HEADER_FLAG_NAMES;
        if (!opts.label.isEmpty()) header |= HEADER_FLAG_LABEL;
        return header;
    }

    private static int buildV9Header(Options opts, int contentKind) {
        if (contentKind < 0 || contentKind > 0b111) {
            throw new IllegalArgumentException("v9 content kind out of range: " + contentKind);
        }
        return WIRE_VERSION
                | (contentKind << V9_CONTENT_KIND_SHIFT)
                | (!opts.label.isEmpty() ? V9_HEADER_FLAG_LABEL : 0);
    }

    static int v9ContentKind(int header) {
        return (header & V9_CONTENT_KIND_MASK) >>> V9_CONTENT_KIND_SHIFT;
    }

    private static boolean isRouteContentKind(int contentKind) {
        return contentKind == V9_CONTENT_KIND_GENERAL_ROUTE
                || contentKind == V9_CONTENT_KIND_COMPACT_ROUTE
                || contentKind == V9_CONTENT_KIND_COORDINATE_ROUTE
                || contentKind == V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META;
    }

    private static void writeGroup(ByteArrayOutputStream bodySoFar, DataOutputStream out, WaypointGroup g, StringPool pool,
                                   Options opts, PackingMode mode, int wireVersion, boolean extendedCoordModes,
                                   boolean allowRangeDelta) throws IOException {
        out.flush();
        byte[] bodyPrefix = bodySoFar.toByteArray();

        boolean bodyless = hasEmptyWaypointBodies(g.waypoints(), pool, opts);

        // When colors are stripped, don't preserve AUTO gradient. Otherwise the
        // decoder would recolor every default-colored imported waypoint and make
        // a colorless export look like it carried colors after all.
        int baseGroupFlags = 0;
        if (bodyless) baseGroupFlags |= GROUP_FLAG_BODYLESS_WAYPOINTS;
        boolean customRadius = false;
        if (opts.includeGroupMeta) {
            if (opts.includeColors && g.gradientMode() == WaypointGroup.GradientMode.AUTO) {
                baseGroupFlags |= GROUP_FLAG_GRAD_AUTO;
            }
            if (g.loadMode()     == WaypointGroup.LoadMode.SEQUENCE) baseGroupFlags |= GROUP_FLAG_LOAD_SEQUENCE;
            customRadius = wireVersion == WIRE_VERSION
                    ? Double.compare(g.defaultRadius(), Waypoint.DEFAULT_REACH_RADIUS) != 0
                    : Math.abs(g.defaultRadius() - Waypoint.DEFAULT_REACH_RADIUS) > 0.001;
            if (customRadius) baseGroupFlags |= GROUP_FLAG_CUSTOM_RADIUS;
        } else {
            // Omitting group metadata projects the route to MANUAL. Marking it
            // AUTO here would make WaypointGroup.addAll recolor explicit RGB
            // waypoint values during decode even when colors were requested.
            baseGroupFlags |= GROUP_FLAG_LOAD_SEQUENCE;
        }
        if (wireVersion == WIRE_VERSION && opts.includeGroupMeta
                && needsV9PersistentGroupMetadata(g, opts.includeColors)) {
            baseGroupFlags |= GROUP_FLAG_V9_PERSISTENT_META;
        }

        CoordPicked picked = pickCoordMode(g, pool, opts, mode, bodyless, baseGroupFlags,
                customRadius, bodyPrefix, wireVersion, extendedCoordModes, allowRangeDelta);
        int groupFlags = baseGroupFlags;
        groupFlags |= encodeCoordModeFlags(picked.mode, extendedCoordModes);

        writeVarint(out, pool.index(g.name()));
        writeZoneRef(out, exportedZoneId(g, opts), pool);
        out.writeByte(groupFlags);
        if ((groupFlags & GROUP_FLAG_V9_PERSISTENT_META) != 0) {
            writeV9PersistentGroupMetadata(out, g, opts.includeColors);
        }

        if (customRadius) writeRadius(out, g.defaultRadius(), wireVersion);

        writeVarint(out, g.size());
        out.write(picked.bytes);
    }

    /** Result of coordinate-mode selection: the chosen mode and the encoded bytes. */
    private record CoordPicked(CoordMode mode, byte[] bytes) {}

    private static boolean needsV9PersistentGroupMetadata(WaypointGroup group, boolean includeColors) {
        return !group.skipAheadEnabled()
                || includeColors && (group.gradientMode() == WaypointGroup.GradientMode.STATIC
                || group.staticColor() != DEFAULT_STATIC_COLOR
                || group.gradientStartColor() != DEFAULT_GRADIENT_START_COLOR
                || group.gradientEndColor() != DEFAULT_GRADIENT_END_COLOR);
    }

    private static void writeV9PersistentGroupMetadata(DataOutputStream out, WaypointGroup group,
                                                       boolean includeColors) throws IOException {
        WaypointGroup.GradientMode projectedMode = includeColors
                ? group.gradientMode()
                : WaypointGroup.GradientMode.MANUAL;
        int gradientMode = switch (projectedMode) {
            case MANUAL -> 0;
            case AUTO -> 1;
            case STATIC -> 2;
        };
        int metadataFlags = gradientMode
                | (group.skipAheadEnabled() ? V9_GROUP_META_SKIP_AHEAD : 0);
        out.writeByte(metadataFlags);
        writeRgb(out, includeColors ? group.staticColor() : DEFAULT_STATIC_COLOR);
        writeRgb(out, includeColors ? group.gradientStartColor() : DEFAULT_GRADIENT_START_COLOR);
        writeRgb(out, includeColors ? group.gradientEndColor() : DEFAULT_GRADIENT_END_COLOR);
    }

    private static void writeRgb(DataOutputStream out, int color) throws IOException {
        out.writeByte(color >>> 16);
        out.writeByte(color >>> 8);
        out.writeByte(color);
    }

    private static int readRgb(DataInputStream in) throws IOException {
        return (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 8)
                | in.readUnsignedByte();
    }

    private static int encodeCoordModeFlags(CoordMode mode, boolean extendedCoordModes) {
        int wire = mode.wireValue;
        if (!extendedCoordModes && (wire & 0b100) != 0) {
            throw new IllegalArgumentException("coord mode " + mode + " is not available before v5");
        }
        return ((wire & 0b11) << GROUP_FLAG_COORD_MODE_SHIFT)
                | ((wire & 0b100) != 0 ? GROUP_FLAG_COORD_MODE_EXTENDED : 0);
    }

    private static CoordMode decodeCoordMode(int groupFlags, int expectedVersion) {
        int wire = (groupFlags & GROUP_FLAG_COORD_MODE_MASK) >>> GROUP_FLAG_COORD_MODE_SHIFT;
        if (expectedVersion >= LEGACY_V5_WIRE_VERSION && (groupFlags & GROUP_FLAG_COORD_MODE_EXTENDED) != 0) {
            wire |= 0b100;
        }
        if (expectedVersion <= LEGACY_V1_WIRE_VERSION && wire > CoordMode.FIXED_COMPACT.wireValue) {
            throw new IllegalArgumentException("coord mode " + wire + " is not available in v1");
        }
        if (expectedVersion < RANGE_DELTA_MIN_VERSION && wire > CoordMode.DELTA_FIT_AXIS_SEPARATED.wireValue) {
            throw new IllegalArgumentException("coord mode " + wire + " is not available before v6");
        }
        return CoordMode.fromWire(wire);
    }

    /**
     * Runs the packing-mode contest for one group. AUTO encodes every eligible
     * mode and keeps the smallest; forced modes skip the comparison entirely.
     *
     * FIXED_COMPACT is only eligible when every coord fits the global bounds
     * (x/z in [-2048, +2047], y in [-64, +447]). FIT_COMPACT is always
     * eligible but has a small preamble cost, so it only wins on groups
     * tight enough that the saved coord bits outweigh the preamble.
     */
    private static CoordPicked pickCoordMode(WaypointGroup g, StringPool pool, Options opts,
                                             PackingMode mode, boolean bodyless, int baseGroupFlags,
                                             boolean customRadius, byte[] bodyPrefix,
                                             int wireVersion, boolean extendedCoordModes,
                                             boolean allowRangeDelta) throws IOException {
        boolean fixedEligible = canUseFixedCompact(g.waypoints());

        switch (mode) {
            case FORCE_VECTOR -> {
                return new CoordPicked(CoordMode.VECTOR,
                        encodeVectorOrAbsolute(g.waypoints(), pool, opts, false, bodyless, wireVersion));
            }
            case FORCE_ABSOLUTE -> {
                return new CoordPicked(CoordMode.ABSOLUTE_VARINT,
                        encodeVectorOrAbsolute(g.waypoints(), pool, opts, true, bodyless, wireVersion));
            }
            case FORCE_FIXED -> {
                if (!fixedEligible) {
                    throw new IllegalArgumentException(
                            "FORCE_FIXED requested but group contains coords outside FIXED_COMPACT bounds");
                }
                return new CoordPicked(CoordMode.FIXED_COMPACT,
                        encodeFixedCompact(g.waypoints(), pool, opts, bodyless, wireVersion));
            }
            case FORCE_FIT -> {
                return new CoordPicked(CoordMode.FIT_COMPACT,
                        encodeFitCompact(g.waypoints(), pool, opts, bodyless, wireVersion));
            }
            case FORCE_VECTOR_AXIS_SEPARATED -> {
                if (!extendedCoordModes) {
                    throw new IllegalArgumentException("FORCE_VECTOR_AXIS_SEPARATED is not available before v5");
                }
                return new CoordPicked(CoordMode.VECTOR_AXIS_SEPARATED,
                        encodeVectorAxisSeparated(g.waypoints(), pool, opts, bodyless, wireVersion));
            }
            case FORCE_DELTA_FIT_AXIS_SEPARATED -> {
                if (!extendedCoordModes) {
                    throw new IllegalArgumentException("FORCE_DELTA_FIT_AXIS_SEPARATED is not available before v5");
                }
                if (!canUseDeltaFitAxisSeparated(g.waypoints())) {
                    throw new IllegalArgumentException(
                            "FORCE_DELTA_FIT_AXIS_SEPARATED requested but a delta needs more than 31 bits");
                }
                return new CoordPicked(CoordMode.DELTA_FIT_AXIS_SEPARATED,
                        encodeDeltaFitAxisSeparated(g.waypoints(), pool, opts, bodyless, wireVersion));
            }
            case FORCE_RANGE_DELTA -> {
                if (!allowRangeDelta) {
                    throw new IllegalArgumentException("FORCE_RANGE_DELTA is not available before v6");
                }
                if (!canUseRangeDelta(g.waypoints())) {
                    throw new IllegalArgumentException(
                            "FORCE_RANGE_DELTA requested but a delta needs more than 31 bits");
                }
                return new CoordPicked(CoordMode.RANGE_DELTA,
                        encodeRangeDelta(g.waypoints(), pool, opts, bodyless, wireVersion));
            }
            default -> {
                byte[] v = encodeVectorOrAbsolute(g.waypoints(), pool, opts, false, bodyless, wireVersion);
                byte[] a = encodeVectorOrAbsolute(g.waypoints(), pool, opts, true, bodyless, wireVersion);
                byte[] f = fixedEligible
                        ? encodeFixedCompact(g.waypoints(), pool, opts, bodyless, wireVersion)
                        : null;
                byte[] t = encodeFitCompact(g.waypoints(), pool, opts, bodyless, wireVersion);
                byte[] vx = extendedCoordModes
                        ? encodeVectorAxisSeparated(g.waypoints(), pool, opts, bodyless, wireVersion)
                        : null;
                byte[] dt = extendedCoordModes && canUseDeltaFitAxisSeparated(g.waypoints())
                        ? encodeDeltaFitAxisSeparated(g.waypoints(), pool, opts, bodyless, wireVersion)
                        : null;
                byte[] rd = allowRangeDelta && canUseRangeDelta(g.waypoints())
                        ? encodeRangeDelta(g.waypoints(), pool, opts, bodyless, wireVersion)
                        : null;

                // Rank by final text size, not raw bytes. Raw-byte size mis-ranks
                // candidates whose contents compress differently, and the v3
                // stream text layer can make equal compressed byte counts differ
                // by a character. This remains a per-group approximation (later
                // groups can still affect cross-group compression context), but
                // it is close to the actual share-string length users care about.
                int vScore = encodedGroupScore(bodyPrefix, g, pool, CoordMode.VECTOR, v,
                        baseGroupFlags, customRadius, wireVersion, opts, extendedCoordModes);
                int aScore = encodedGroupScore(bodyPrefix, g, pool, CoordMode.ABSOLUTE_VARINT, a,
                        baseGroupFlags, customRadius, wireVersion, opts, extendedCoordModes);
                int fScore = f != null
                        ? encodedGroupScore(bodyPrefix, g, pool, CoordMode.FIXED_COMPACT, f,
                                baseGroupFlags, customRadius, wireVersion, opts,
                                extendedCoordModes)
                        : Integer.MAX_VALUE;
                int tScore = encodedGroupScore(bodyPrefix, g, pool, CoordMode.FIT_COMPACT, t,
                        baseGroupFlags, customRadius, wireVersion, opts, extendedCoordModes);
                int vxScore = vx != null
                        ? encodedGroupScore(bodyPrefix, g, pool, CoordMode.VECTOR_AXIS_SEPARATED, vx,
                                baseGroupFlags, customRadius, wireVersion, opts,
                                extendedCoordModes)
                        : Integer.MAX_VALUE;
                int dtScore = dt != null
                        ? encodedGroupScore(bodyPrefix, g, pool, CoordMode.DELTA_FIT_AXIS_SEPARATED, dt,
                                baseGroupFlags, customRadius, wireVersion, opts,
                                extendedCoordModes)
                        : Integer.MAX_VALUE;
                int rdScore = rd != null
                        ? encodedGroupScore(bodyPrefix, g, pool, CoordMode.RANGE_DELTA, rd,
                                baseGroupFlags, customRadius, wireVersion, opts,
                                extendedCoordModes)
                        : Integer.MAX_VALUE;

                CoordPicked best = new CoordPicked(CoordMode.VECTOR, v);
                int bestScore = vScore;
                if (aScore < bestScore) {
                    best = new CoordPicked(CoordMode.ABSOLUTE_VARINT, a);
                    bestScore = aScore;
                }
                if (fScore < bestScore) {
                    best = new CoordPicked(CoordMode.FIXED_COMPACT, f);
                    bestScore = fScore;
                }
                if (tScore < bestScore) {
                    best = new CoordPicked(CoordMode.FIT_COMPACT, t);
                    bestScore = tScore;
                }
                if (vxScore < bestScore) {
                    best = new CoordPicked(CoordMode.VECTOR_AXIS_SEPARATED, vx);
                    bestScore = vxScore;
                }
                if (dtScore < bestScore) {
                    best = new CoordPicked(CoordMode.DELTA_FIT_AXIS_SEPARATED, dt);
                    bestScore = dtScore;
                }
                if (rdScore < bestScore) {
                    best = new CoordPicked(CoordMode.RANGE_DELTA, rd);
                }
                return best;
            }
        }
    }

    private static CoordPicked pickAnonymousCoordMode(WaypointGroup group, Options opts, PackingMode mode,
                                                      int baseGroupFlags, boolean customRadius,
                                                      byte[] bodyPrefix, int wireVersion,
                                                      boolean extendedCoordModes)
            throws IOException {
        StringPool emptyPool = new StringPool();
        boolean fixedEligible = canUseFixedCompact(group.waypoints());

        switch (mode) {
            case FORCE_VECTOR -> {
                return new CoordPicked(CoordMode.VECTOR,
                        encodeVectorOrAbsolute(group.waypoints(), emptyPool, opts, false, true, wireVersion));
            }
            case FORCE_ABSOLUTE -> {
                return new CoordPicked(CoordMode.ABSOLUTE_VARINT,
                        encodeVectorOrAbsolute(group.waypoints(), emptyPool, opts, true, true, wireVersion));
            }
            case FORCE_FIXED -> {
                if (!fixedEligible) {
                    throw new IllegalArgumentException(
                            "FORCE_FIXED requested but group contains coords outside FIXED_COMPACT bounds");
                }
                return new CoordPicked(CoordMode.FIXED_COMPACT,
                        encodeFixedCompact(group.waypoints(), emptyPool, opts, true, wireVersion));
            }
            case FORCE_FIT -> {
                return new CoordPicked(CoordMode.FIT_COMPACT,
                        encodeFitCompact(group.waypoints(), emptyPool, opts, true, wireVersion));
            }
            case FORCE_VECTOR_AXIS_SEPARATED -> {
                if (!extendedCoordModes) {
                    throw new IllegalArgumentException("FORCE_VECTOR_AXIS_SEPARATED is not available before v5");
                }
                return new CoordPicked(CoordMode.VECTOR_AXIS_SEPARATED,
                        encodeVectorAxisSeparated(group.waypoints(), emptyPool, opts, true, wireVersion));
            }
            case FORCE_DELTA_FIT_AXIS_SEPARATED -> {
                if (!extendedCoordModes) {
                    throw new IllegalArgumentException("FORCE_DELTA_FIT_AXIS_SEPARATED is not available before v5");
                }
                if (!canUseDeltaFitAxisSeparated(group.waypoints())) {
                    throw new IllegalArgumentException(
                            "FORCE_DELTA_FIT_AXIS_SEPARATED requested but a delta needs more than 31 bits");
                }
                return new CoordPicked(CoordMode.DELTA_FIT_AXIS_SEPARATED,
                        encodeDeltaFitAxisSeparated(group.waypoints(), emptyPool, opts, true, wireVersion));
            }
            case FORCE_RANGE_DELTA -> {
                if (!extendedCoordModes) {
                    throw new IllegalArgumentException("FORCE_RANGE_DELTA is not available before v6");
                }
                if (!canUseRangeDelta(group.waypoints())) {
                    throw new IllegalArgumentException(
                            "FORCE_RANGE_DELTA requested but a delta needs more than 31 bits");
                }
                return new CoordPicked(CoordMode.RANGE_DELTA,
                        encodeRangeDelta(group.waypoints(), emptyPool, opts, true, wireVersion));
            }
            default -> {
                byte[] v = encodeVectorOrAbsolute(
                        group.waypoints(), emptyPool, opts, false, true, wireVersion);
                byte[] a = encodeVectorOrAbsolute(
                        group.waypoints(), emptyPool, opts, true, true, wireVersion);
                byte[] f = fixedEligible
                        ? encodeFixedCompact(group.waypoints(), emptyPool, opts, true, wireVersion)
                        : null;
                byte[] t = encodeFitCompact(group.waypoints(), emptyPool, opts, true, wireVersion);
                byte[] vx = extendedCoordModes
                        ? encodeVectorAxisSeparated(group.waypoints(), emptyPool, opts, true, wireVersion)
                        : null;
                byte[] dt = extendedCoordModes && canUseDeltaFitAxisSeparated(group.waypoints())
                        ? encodeDeltaFitAxisSeparated(group.waypoints(), emptyPool, opts, true, wireVersion)
                        : null;
                byte[] rd = extendedCoordModes && canUseRangeDelta(group.waypoints())
                        ? encodeRangeDelta(group.waypoints(), emptyPool, opts, true, wireVersion)
                        : null;

                int vScore = anonymousEncodedScore(bodyPrefix, group, opts, CoordMode.VECTOR, v,
                        baseGroupFlags, customRadius, wireVersion, extendedCoordModes);
                int aScore = anonymousEncodedScore(bodyPrefix, group, opts, CoordMode.ABSOLUTE_VARINT, a,
                        baseGroupFlags, customRadius, wireVersion, extendedCoordModes);
                int fScore = f != null
                        ? anonymousEncodedScore(bodyPrefix, group, opts, CoordMode.FIXED_COMPACT, f,
                                baseGroupFlags, customRadius, wireVersion, extendedCoordModes)
                        : Integer.MAX_VALUE;
                int tScore = anonymousEncodedScore(bodyPrefix, group, opts, CoordMode.FIT_COMPACT, t,
                        baseGroupFlags, customRadius, wireVersion, extendedCoordModes);
                int vxScore = vx != null
                        ? anonymousEncodedScore(bodyPrefix, group, opts, CoordMode.VECTOR_AXIS_SEPARATED, vx,
                                baseGroupFlags, customRadius, wireVersion, extendedCoordModes)
                        : Integer.MAX_VALUE;
                int dtScore = dt != null
                        ? anonymousEncodedScore(bodyPrefix, group, opts, CoordMode.DELTA_FIT_AXIS_SEPARATED, dt,
                                baseGroupFlags, customRadius, wireVersion, extendedCoordModes)
                        : Integer.MAX_VALUE;
                int rdScore = rd != null
                        ? anonymousEncodedScore(bodyPrefix, group, opts, CoordMode.RANGE_DELTA, rd,
                                baseGroupFlags, customRadius, wireVersion, extendedCoordModes)
                        : Integer.MAX_VALUE;

                CoordPicked best = new CoordPicked(CoordMode.VECTOR, v);
                int bestScore = vScore;
                if (aScore < bestScore) {
                    best = new CoordPicked(CoordMode.ABSOLUTE_VARINT, a);
                    bestScore = aScore;
                }
                if (fScore < bestScore) {
                    best = new CoordPicked(CoordMode.FIXED_COMPACT, f);
                    bestScore = fScore;
                }
                if (tScore < bestScore) {
                    best = new CoordPicked(CoordMode.FIT_COMPACT, t);
                    bestScore = tScore;
                }
                if (vxScore < bestScore) {
                    best = new CoordPicked(CoordMode.VECTOR_AXIS_SEPARATED, vx);
                    bestScore = vxScore;
                }
                if (dtScore < bestScore) {
                    best = new CoordPicked(CoordMode.DELTA_FIT_AXIS_SEPARATED, dt);
                    bestScore = dtScore;
                }
                if (rdScore < bestScore) {
                    best = new CoordPicked(CoordMode.RANGE_DELTA, rd);
                }
                return best;
            }
        }
    }

    private static boolean canUseFixedCompact(List<Waypoint> pts) {
        int xMax = 1 << (FIXED_X_BITS - 1);              // 2048: covers zigzag values 0..4095 -> x in [-2048, +2047]
        int zMax = 1 << (FIXED_Z_BITS - 1);
        int yMax = (1 << FIXED_Y_BITS) - FIXED_Y_OFFSET; // y <= 447 when offset=64
        for (Waypoint w : pts) {
            if (w.x() < -xMax || w.x() >= xMax) return false;
            if (w.z() < -zMax || w.z() >= zMax) return false;
            if (w.y() < -FIXED_Y_OFFSET || w.y() >= yMax) return false;
        }
        return true;
    }

    /**
     * Encode a group's waypoints as either VECTOR (delta, {@code absolute=false})
     * or ABSOLUTE_VARINT (every coord independent, {@code absolute=true}).
     *
     * Layout is all-coords-then-all-bodies so every coord mode (including
     * FIXED_COMPACT) produces the same two-section shape. A single
     * {@link #readCoords} call on the decode side works for every mode.
     */
    private static byte[] encodeVectorOrAbsolute(List<Waypoint> pts, StringPool pool, Options opts,
                                                 boolean absolute, boolean bodyless,
                                                 int wireVersion) throws IOException {
        ByteArrayOutputStream scratch = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(scratch);

        int lx = 0, ly = 0, lz = 0;
        for (int i = 0; i < pts.size(); i++) {
            Waypoint w = pts.get(i);
            int dx, dy, dz;
            if (absolute || i == 0) {
                dx = w.x(); dy = w.y(); dz = w.z();
            } else {
                dx = w.x() - lx; dy = w.y() - ly; dz = w.z() - lz;
            }
            writeZigzag(out, dx);
            writeZigzag(out, dy);
            writeZigzag(out, dz);
            lx = w.x(); ly = w.y(); lz = w.z();
        }
        if (!bodyless) {
            for (Waypoint w : pts) writeWaypointBody(out, w, pool, opts, wireVersion);
        }
        out.flush();
        return scratch.toByteArray();
    }

    /**
     * Same logical model as VECTOR, but transposed into three axis streams:
     * first absolute point, then every dx, then every dy, then every dz.
     * Raw size is nearly identical to VECTOR, but real mining routes often
     * compress better when DEFLATE sees each axis as its own repetitive stream.
     */
    private static byte[] encodeVectorAxisSeparated(List<Waypoint> pts, StringPool pool, Options opts,
                                                    boolean bodyless, int wireVersion) throws IOException {
        ByteArrayOutputStream scratch = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(scratch);

        if (!pts.isEmpty()) {
            Waypoint first = pts.get(0);
            writeZigzag(out, first.x());
            writeZigzag(out, first.y());
            writeZigzag(out, first.z());

            for (int axis = 0; axis < 3; axis++) {
                for (int i = 1; i < pts.size(); i++) {
                    Waypoint prev = pts.get(i - 1);
                    Waypoint cur = pts.get(i);
                    writeZigzag(out, coord(cur, axis) - coord(prev, axis));
                }
            }
        }

        if (!bodyless) {
            for (Waypoint w : pts) writeWaypointBody(out, w, pool, opts, wireVersion);
        }
        out.flush();
        return scratch.toByteArray();
    }

    /**
     * Delta-local sibling of FIT_COMPACT, transposed by axis. Preamble:
     * first absolute point, then 5-bit widths for zigzag(dx/dy/dz), followed
     * by all packed dx values, all packed dy values, all packed dz values.
     */
    private static byte[] encodeDeltaFitAxisSeparated(List<Waypoint> pts, StringPool pool, Options opts,
                                                      boolean bodyless, int wireVersion) throws IOException {
        int[] widths = deltaFitWidths(pts);

        ByteArrayOutputStream scratch = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(scratch);
        if (!pts.isEmpty()) {
            Waypoint first = pts.get(0);
            writeZigzag(out, first.x());
            writeZigzag(out, first.y());
            writeZigzag(out, first.z());
        }
        int packedWidths = (widths[0] << 10) | (widths[1] << 5) | widths[2];
        out.writeByte((packedWidths >>> 8) & 0xFF);
        out.writeByte(packedWidths & 0xFF);
        out.flush();

        BitWriter bits = new BitWriter(scratch);
        for (int axis = 0; axis < 3; axis++) {
            int width = widths[axis];
            if (width == 0) continue;
            for (int i = 1; i < pts.size(); i++) {
                int delta = coord(pts.get(i), axis) - coord(pts.get(i - 1), axis);
                bits.write(zigzag(delta), width);
            }
        }
        bits.flush();

        if (!bodyless) {
            for (Waypoint w : pts) writeWaypointBody(out, w, pool, opts, wireVersion);
        }
        out.flush();
        return scratch.toByteArray();
    }

    private static byte[] encodeRangeDelta(List<Waypoint> pts, StringPool pool, Options opts,
                                           boolean bodyless, int wireVersion) throws IOException {
        int[] widths = deltaFitWidths(pts);

        ByteArrayOutputStream scratch = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(scratch);
        if (!pts.isEmpty()) {
            Waypoint first = pts.get(0);
            writeZigzag(out, first.x());
            writeZigzag(out, first.y());
            writeZigzag(out, first.z());
        }
        int packedWidths = (widths[0] << 10) | (widths[1] << 5) | widths[2];
        out.writeByte((packedWidths >>> 8) & 0xFF);
        out.writeByte(packedWidths & 0xFF);

        byte[] payload = encodeRangeDeltaPayload(pts, widths);
        writeVarint(out, payload.length);
        out.write(payload);

        if (!bodyless) {
            for (Waypoint w : pts) writeWaypointBody(out, w, pool, opts, wireVersion);
        }
        out.flush();
        return scratch.toByteArray();
    }

    private static byte[] encodeRangeDeltaPayload(List<Waypoint> pts, int[] widths) {
        RangeBitEncoder encoder = new RangeBitEncoder();
        short[] probabilities = newRangeDeltaProbabilities();
        for (int axis = 0; axis < 3; axis++) {
            int width = widths[axis];
            if (width == 0) continue;
            for (int i = 1; i < pts.size(); i++) {
                int delta = coord(pts.get(i), axis) - coord(pts.get(i - 1), axis);
                int encoded = zigzag(delta);
                for (int bit = width - 1; bit >= 0; bit--) {
                    encoder.writeBit(probabilities, rangeDeltaContext(axis, bit), (encoded >>> bit) & 1);
                }
            }
        }
        return encoder.finish();
    }

    private static short[] newRangeDeltaProbabilities() {
        short[] probabilities = new short[RANGE_DELTA_CONTEXTS];
        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] = RANGE_PROB_INITIAL;
        }
        return probabilities;
    }

    private static int rangeDeltaContext(int axis, int bit) {
        return axis * FIT_MAX_WIDTH + bit;
    }

    private static boolean canUseRangeDelta(List<Waypoint> pts) {
        int[] widths = deltaFitWidths(pts);
        return widths[0] <= FIT_MAX_WIDTH
                && widths[1] <= FIT_MAX_WIDTH
                && widths[2] <= FIT_MAX_WIDTH;
    }

    private static boolean canUseDeltaFitAxisSeparated(List<Waypoint> pts) {
        int[] widths = deltaFitWidths(pts);
        return widths[0] <= FIT_MAX_WIDTH
                && widths[1] <= FIT_MAX_WIDTH
                && widths[2] <= FIT_MAX_WIDTH;
    }

    private static int[] deltaFitWidths(List<Waypoint> pts) {
        int[] widths = new int[3];
        for (int i = 1; i < pts.size(); i++) {
            Waypoint prev = pts.get(i - 1);
            Waypoint cur = pts.get(i);
            for (int axis = 0; axis < 3; axis++) {
                int delta = coord(cur, axis) - coord(prev, axis);
                widths[axis] = Math.max(widths[axis], bitsToRepresentUnsignedInt(zigzag(delta)));
            }
        }
        return widths;
    }

    private static int bitsToRepresentUnsignedInt(int value) {
        return value == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(value);
    }

    private static int coord(Waypoint waypoint, int axis) {
        return switch (axis) {
            case 0 -> waypoint.x();
            case 1 -> waypoint.y();
            case 2 -> waypoint.z();
            default -> throw new IllegalArgumentException("bad axis: " + axis);
        };
    }

    /**
     * Encode a group using the fixed-width bit-packed scheme. The coord
     * bitstream runs to completion (byte-aligned via the bit writer's flush)
     * BEFORE any waypoint bodies; this matches the all-coords-then-all-bodies
     * shape of the other modes and keeps the bit writer simple (at most 7 bits
     * of padding regardless of waypoint count).
     */
    private static byte[] encodeFixedCompact(List<Waypoint> pts, StringPool pool, Options opts,
                                             boolean bodyless, int wireVersion) throws IOException {
        ByteArrayOutputStream scratch = new ByteArrayOutputStream();
        BitWriter bits = new BitWriter(scratch);
        for (Waypoint w : pts) {
            bits.write(zigzag(w.x()), FIXED_X_BITS);
            bits.write(w.y() + FIXED_Y_OFFSET, FIXED_Y_BITS);
            bits.write(zigzag(w.z()), FIXED_Z_BITS);
        }
        bits.flush();

        DataOutputStream out = new DataOutputStream(scratch);
        if (!bodyless) {
            for (Waypoint w : pts) writeWaypointBody(out, w, pool, opts, wireVersion);
        }
        out.flush();
        return scratch.toByteArray();
    }

    /**
     * Encode a group with per-axis auto-fitted bit widths. Preamble layout:
     *
     *   zigzag-varint xOrigin
     *   zigzag-varint yOrigin
     *   zigzag-varint zOrigin
     *   packed-u15    xBits (5) | yBits (5) | zBits (5)   [2 bytes byte-aligned]
     *
     * Each waypoint then packs (x-xOrigin, y-yOrigin, z-zOrigin) in the chosen
     * widths -- all non-negative by construction because origin = min per axis.
     * A width of 0 on any axis means every coord equals the origin and
     * contributes zero bits per waypoint.
     *
     * On a group with <= 1 point this collapses to "3 zigzag-varints + 2 bytes
     * of bit-width preamble + 0 bits per point", which is strictly worse than
     * ABSOLUTE_VARINT; the AUTO contest will discard it in that case. We still
     * encode it cleanly so the contest's byte-count comparison doesn't need a
     * special case.
     */
    private static byte[] encodeFitCompact(List<Waypoint> pts, StringPool pool, Options opts,
                                           boolean bodyless, int wireVersion) throws IOException {
        int xMin = Integer.MAX_VALUE, yMin = Integer.MAX_VALUE, zMin = Integer.MAX_VALUE;
        int xMax = Integer.MIN_VALUE, yMax = Integer.MIN_VALUE, zMax = Integer.MIN_VALUE;
        for (Waypoint w : pts) {
            if (w.x() < xMin) xMin = w.x(); if (w.x() > xMax) xMax = w.x();
            if (w.y() < yMin) yMin = w.y(); if (w.y() > yMax) yMax = w.y();
            if (w.z() < zMin) zMin = w.z(); if (w.z() > zMax) zMax = w.z();
        }
        // Empty group: use zero origins so the decoder's readCoords loop runs
        // zero iterations but the preamble is still well-formed.
        int xOrigin = pts.isEmpty() ? 0 : xMin;
        int yOrigin = pts.isEmpty() ? 0 : yMin;
        int zOrigin = pts.isEmpty() ? 0 : zMin;
        int xBits = pts.isEmpty() ? 0 : bitsToRepresent((long) xMax - xMin);
        int yBits = pts.isEmpty() ? 0 : bitsToRepresent((long) yMax - yMin);
        int zBits = pts.isEmpty() ? 0 : bitsToRepresent((long) zMax - zMin);
        // Clamp: if any axis span exceeds 31 bits we fall back to the widest
        // valid width. In practice the AUTO contest discards FIT_COMPACT long
        // before this matters because the coord preamble overhead dwarfs any
        // savings at those ranges.
        xBits = Math.min(xBits, FIT_MAX_WIDTH);
        yBits = Math.min(yBits, FIT_MAX_WIDTH);
        zBits = Math.min(zBits, FIT_MAX_WIDTH);

        ByteArrayOutputStream scratch = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(scratch);
        writeZigzag(out, xOrigin);
        writeZigzag(out, yOrigin);
        writeZigzag(out, zOrigin);
        // 5+5+5 = 15 bits fits in a u16 but we only need 15; write as u16 big-endian
        // so the reader can unpack it in one readUnsignedShort call.
        int packedWidths = (xBits << 10) | (yBits << 5) | zBits;
        out.writeByte((packedWidths >>> 8) & 0xFF);
        out.writeByte(packedWidths & 0xFF);
        out.flush();

        BitWriter bits = new BitWriter(scratch);
        for (Waypoint w : pts) {
            if (xBits > 0) bits.write(w.x() - xOrigin, xBits);
            if (yBits > 0) bits.write(w.y() - yOrigin, yBits);
            if (zBits > 0) bits.write(w.z() - zOrigin, zBits);
        }
        bits.flush();

        if (!bodyless) {
            for (Waypoint w : pts) writeWaypointBody(out, w, pool, opts, wireVersion);
        }
        out.flush();
        return scratch.toByteArray();
    }

    /**
     * Number of bits needed to represent a non-negative {@code span} as an
     * unsigned integer. {@code bitsToRepresent(0) == 0} (no bits needed when
     * there's only one possible value), {@code bitsToRepresent(1) == 1}, etc.
     */
    private static int bitsToRepresent(long span) {
        if (span <= 0) return 0;
        return 64 - Long.numberOfLeadingZeros(span);
    }

    private static boolean hasEmptyWaypointBodies(List<Waypoint> pts, StringPool pool, Options opts) {
        for (Waypoint w : pts) {
            if (waypointFlags(w, pool, opts) != 0) return false;
        }
        return true;
    }

    private static void writeWaypointBody(DataOutputStream out, Waypoint w, StringPool pool, Options opts,
                                          int wireVersion)
            throws IOException {
        int wpFlags = waypointFlags(w, pool, opts);
        out.writeByte(wpFlags);

        if ((wpFlags & WP_FLAG_HAS_NAME) != 0) {
            if ((wpFlags & WP_FLAG_NAME_INLINE) != 0) {
                writeUtf8String(out, w.name());
            } else {
                writeVarint(out, pool.index(w.name()));
            }
        }
        if ((wpFlags & WP_FLAG_HAS_COLOR) != 0) {
            int c = w.color() & 0xFFFFFF;
            out.writeByte((c >> 16) & 0xFF);
            out.writeByte((c >>  8) & 0xFF);
            out.writeByte( c        & 0xFF);
        }
        if ((wpFlags & WP_FLAG_HAS_RADIUS) != 0) writeRadius(out, w.customRadius(), wireVersion);
        if ((wpFlags & WP_FLAG_EXTENDED)   != 0) writeVarint(out, exportedWaypointFlags(w, opts));
        if ((wpFlags & WP_FLAG_HAS_PRECISE) != 0) writeVarint(out, packedPreciseOffsets(w));
    }

    private static void writeRadius(DataOutputStream out, double radius, int wireVersion) throws IOException {
        if (wireVersion == WIRE_VERSION) {
            out.writeDouble(radius);
        } else {
            writeVarint(out, (int) Math.round(radius * 10.0));
        }
    }

    private static double readDefaultRadius(DataInputStream in, int wireVersion) throws IOException {
        if (wireVersion != WIRE_VERSION) {
            return Waypoint.normalizeDefaultRadius(readVarint(in) / 10.0);
        }
        double radius = in.readDouble();
        double normalized = Waypoint.normalizeDefaultRadius(radius);
        if (Double.compare(radius, normalized) != 0) {
            throw new IOException("v9 default radius is non-finite or outside the supported range");
        }
        return radius;
    }

    private static double readCustomRadius(DataInputStream in, int wireVersion) throws IOException {
        if (wireVersion != WIRE_VERSION) {
            return Waypoint.normalizeCustomRadius(readVarint(in) / 10.0);
        }
        double radius = in.readDouble();
        double normalized = Waypoint.normalizeCustomRadius(radius);
        if (Double.compare(radius, normalized) != 0 || radius == 0.0) {
            throw new IOException("v9 custom radius is non-finite or outside the supported range");
        }
        return radius;
    }

    private static int waypointFlags(Waypoint w, StringPool pool, Options opts) {
        // Each "include" toggle gates an entire field family: when the sender
        // opts out, we don't emit the bit OR the value, so the decoder reads
        // back the recipient-side default (DEFAULT_COLOR, group radius, no flags).
        boolean hasName   = opts.includeNames && w.hasName();
        boolean hasColor  = opts.includeColors
                && (w.color() & 0xFFFFFF) != (Waypoint.DEFAULT_COLOR & 0xFFFFFF);
        boolean hasRadius = opts.includeRadii         && w.customRadius() > 0;
        boolean extended  = exportedWaypointFlags(w, opts) != 0;
        boolean hasPrecise = shouldExportPrecisePosition(w, opts);

        int wpFlags = 0;
        if (hasName) {
            wpFlags |= WP_FLAG_HAS_NAME;
            if (!pool.shouldPoolWaypointName(w.name())) wpFlags |= WP_FLAG_NAME_INLINE;
        }
        if (hasColor)  wpFlags |= WP_FLAG_HAS_COLOR;
        if (hasRadius) wpFlags |= WP_FLAG_HAS_RADIUS;
        if (extended)  wpFlags |= WP_FLAG_EXTENDED;
        if (hasPrecise) wpFlags |= WP_FLAG_HAS_PRECISE;
        return wpFlags;
    }

    static int exportedWaypointFlags(Waypoint w, Options opts) {
        int flags = w.flags();
        if (!opts.includeWaypointFlags) {
            int requiredFlags = Waypoint.PERSISTENT_BEHAVIOR_FLAGS;
            if (opts.includeColors) requiredFlags |= Waypoint.FLAG_LOCKED_COLOR;
            flags &= requiredFlags;
            if ((flags & Waypoint.FLAG_SUBWAYPOINT) != 0) {
                flags |= w.flags() & Waypoint.SUBWAYPOINT_STYLE_FLAGS;
            }
        }
        return flags;
    }

    static boolean shouldExportPrecisePosition(Waypoint w, Options opts) {
        if (!w.hasCustomPrecisePosition()) return false;
        if (w.isSubwaypoint()) return true;
        return opts.includeWaypointFlags;
    }

    private static int packedPreciseOffsets(Waypoint w) {
        int ox = preciseOffset(w.preciseX());
        int oy = preciseOffset(w.preciseY());
        int oz = preciseOffset(w.preciseZ());
        return (ox << (PRECISE_OFFSET_BITS * 2)) | (oy << PRECISE_OFFSET_BITS) | oz;
    }

    private static int preciseOffset(int preciseCoordinate) {
        return Math.floorMod(preciseCoordinate, Waypoint.PRECISE_SCALE);
    }

    // --- reader -------------------------------------------------------------------------------

    private static List<WaypointGroup> readBody(byte[] bytes, DebugCapture cap, DecodedHeader headerOut,
                                                int expectedVersion, boolean legacyV2)
            throws IOException {
        TrackedByteStream bais = new TrackedByteStream(bytes);
        DataInputStream in = new DataInputStream(bais);
        // Header byte: version in the low nibble, flags in the high nibble.
        // Reject unknown versions up front so a schema bump surfaces a clean
        // error message instead of a garbled field read downstream.
        int header = in.readUnsignedByte();
        int version = header & HEADER_VERSION_MASK;
        if (version != expectedVersion) {
            throw new IOException("unsupported wire version " + version
                    + " (expected v" + expectedVersion + ")");
        }
        if (cap != null) cap.headerByte = header;

        int v9Kind = -1;
        if (expectedVersion == WIRE_VERSION) {
            v9Kind = v9ContentKind(header);
            if (!isRouteContentKind(v9Kind)) {
                throw new IOException("unsupported v9 content kind " + v9Kind);
            }
        }

        // Optional sender label sits between the header and the string pool so
        // peekLabel can stop early. Sanitize on read too: a hand-crafted payload
        // could embed §-codes or control chars even though the encoder won't.
        String label = "";
        boolean hasLabel = expectedVersion == WIRE_VERSION
                ? (header & V9_HEADER_FLAG_LABEL) != 0
                : (header & HEADER_FLAG_LABEL) != 0;
        if (hasLabel) {
            label = Options.sanitizeLabel(readLabel(in));
        }
        if (cap != null) cap.label = label;
        if (headerOut != null) headerOut.label = label;

        if (expectedVersion == WIRE_VERSION && v9Kind == V9_CONTENT_KIND_COMPACT_ROUTE) {
            if (cap != null) cap.stringPool = List.of();
            int compactStart = bais.position();
            WaypointGroup group = V9CompactCodec.decodePayload(in);
            requireFullyConsumed(bais);
            if (cap != null) captureCompactGroup(cap, group, bais.position() - compactStart, true);
            return List.of(group);
        }
        if (expectedVersion == WIRE_VERSION && v9Kind == V9_CONTENT_KIND_COORDINATE_ROUTE) {
            if (cap != null) cap.stringPool = List.of();
            int compactStart = bais.position();
            WaypointGroup group = V9CompactCodec.decodeCoordinatePayload(in);
            requireFullyConsumed(bais);
            if (cap != null) captureCompactGroup(cap, group, bais.position() - compactStart, false);
            return List.of(group);
        }

        boolean anonymousSingleGroup = !legacyV2 && (expectedVersion == WIRE_VERSION
                ? v9Kind == V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META
                : expectedVersion >= ANONYMOUS_SINGLE_GROUP_MIN_VERSION
                && (header & HEADER_FLAG_ANONYMOUS_SINGLE_GROUP) != 0);
        if (anonymousSingleGroup) {
            if (cap != null) cap.stringPool = List.of();
            List<WaypointGroup> groups = new ArrayList<>(1);
            groups.add(readAnonymousGroupRecord(in, bais, cap, 0, expectedVersion));
            requireFullyConsumed(bais);
            return groups;
        }

        List<String> pool = StringPool.readFrom(in);
        if (cap != null) cap.stringPool = pool;
        int groupCount = readVarint(in);
        if (groupCount < 0 || groupCount > MAX_WIRE_GROUPS) {
            throw new IOException("group count out of range: " + groupCount);
        }
        List<WaypointGroup> groups = new ArrayList<>(Math.min(groupCount, MAX_ARRAYLIST_PRESIZE));

        int totalWaypoints = 0;
        for (int gi = 0; gi < groupCount; gi++) {
            WaypointGroup group = legacyV2
                    ? readLegacyV2Group(in, bais, pool, cap, gi, expectedVersion)
                    : readGroup(in, bais, pool, cap, gi, expectedVersion);
            totalWaypoints = Math.addExact(totalWaypoints, group.size());
            if (totalWaypoints > MAX_WIRE_WAYPOINTS_TOTAL) {
                throw new IOException("total waypoint count out of range: " + totalWaypoints);
            }
            groups.add(group);
        }
        requireFullyConsumed(bais);
        return groups;
    }

    private static void captureCompactGroup(DebugCapture cap, WaypointGroup group, int payloadBytes,
                                            boolean fullMetadata) {
        DebugCapture.GroupBuilder gCap = cap.startGroup(0);
        gCap.name = group.name();
        gCap.zoneId = group.zoneId();
        gCap.groupFlagsByte = -1;
        gCap.enabled = true;
        gCap.gradientAuto = false;
        gCap.loadSequence = true;
        gCap.customRadius = false;
        gCap.coordMode = null;
        gCap.defaultRadius = group.defaultRadius();
        gCap.currentIndex = 0;
        gCap.pointCount = group.size();
        gCap.coordBlockBytes = -1;
        gCap.bodyBlockBytes = payloadBytes;
        for (int index = 0; index < group.size(); index++) {
            Waypoint waypoint = group.get(index);
            gCap.waypoints.add(new DecodeDebug.WaypointDebug(
                    index, waypoint.x(), waypoint.y(), waypoint.z(), 0,
                    fullMetadata && !waypoint.name().isEmpty(), fullMetadata, false, false,
                    waypoint.name(), waypoint.color(), 0.0, 0));
        }
    }

    private static void requireFullyConsumed(TrackedByteStream input) throws IOException {
        if (input.available() != 0) {
            throw new IOException("trailing binary body bytes: " + input.available());
        }
    }

    private static WaypointGroup readGroup(DataInputStream in, TrackedByteStream bais, List<String> pool,
                                           DebugCapture cap, int groupIndex, int expectedVersion)
            throws IOException {
        return readGroupRecord(in, bais, pool, cap, groupIndex, false, expectedVersion);
    }

    private static WaypointGroup readLegacyV2Group(DataInputStream in, TrackedByteStream bais, List<String> pool,
                                                  DebugCapture cap, int groupIndex, int expectedVersion)
            throws IOException {
        return readGroupRecord(in, bais, pool, cap, groupIndex, true, expectedVersion);
    }

    private static WaypointGroup readAnonymousGroupRecord(DataInputStream in, TrackedByteStream bais,
                                                         DebugCapture cap, int groupIndex,
                                                         int expectedVersion) throws IOException {
        String name = "";
        String zone = readAnonymousZoneRef(in);
        int groupFlags = in.readUnsignedByte();
        if (expectedVersion == WIRE_VERSION
                && (groupFlags & GROUP_FLAG_V9_PERSISTENT_META) != 0) {
            throw new IOException("v9 kind 5 group metadata extension bit is reserved");
        }
        boolean bodyless = (groupFlags & GROUP_FLAG_BODYLESS_WAYPOINTS) != 0;
        if (!bodyless) {
            throw new IOException("anonymous coordinate group is missing bodyless flag");
        }
        boolean autoGrad = (groupFlags & GROUP_FLAG_GRAD_AUTO) != 0;
        boolean sequence = (groupFlags & GROUP_FLAG_LOAD_SEQUENCE) != 0;
        boolean customRadius = (groupFlags & GROUP_FLAG_CUSTOM_RADIUS) != 0;
        CoordMode coordMode = decodeCoordMode(groupFlags, expectedVersion);

        double radius = customRadius
                ? readDefaultRadius(in, expectedVersion)
                : Waypoint.DEFAULT_REACH_RADIUS;

        WaypointGroup group = WaypointGroup.create(name, zone);
        group.setDefaultRadius(radius);
        group.setEnabled(true);
        group.setLoadMode(sequence ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
        group.setGradientMode(autoGrad ? WaypointGroup.GradientMode.AUTO : WaypointGroup.GradientMode.MANUAL);

        int pointCount = readVarint(in);

        DebugCapture.GroupBuilder gCap = null;
        if (cap != null) {
            gCap = cap.startGroup(groupIndex);
            gCap.name = name;
            gCap.zoneId = zone;
            gCap.groupFlagsByte = groupFlags;
            gCap.enabled = true;
            gCap.gradientAuto = autoGrad;
            gCap.loadSequence = sequence;
            gCap.customRadius = customRadius;
            gCap.coordMode = coordMode;
            gCap.defaultRadius = radius;
            gCap.currentIndex = 0;
            gCap.pointCount = pointCount;
        }

        int coordStart = bais.position();
        int[][] coords = readCoords(in, pointCount, coordMode, expectedVersion);
        int coordEnd = bais.position();
        if (gCap != null) gCap.coordBlockBytes = coordEnd - coordStart;

        List<Waypoint> waypoints = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) {
            int x = coords[i][0], y = coords[i][1], z = coords[i][2];
            if (gCap != null) {
                gCap.waypoints.add(new DecodeDebug.WaypointDebug(
                        i, x, y, z, 0,
                        false, false, false, false,
                        "", Waypoint.DEFAULT_COLOR, 0.0, 0));
            }
            waypoints.add(Waypoint.at(x, y, z));
        }
        group.addAll(waypoints);
        if (gCap != null) gCap.bodyBlockBytes = 0;

        if (autoGrad) group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        return group;
    }

    private static WaypointGroup readGroupRecord(DataInputStream in, TrackedByteStream bais, List<String> pool,
                                                 DebugCapture cap, int groupIndex, boolean legacyV2,
                                                 int expectedVersion)
            throws IOException {
        String encodedName = poolGet(pool, readVarint(in));
        String name = expectedVersion == WIRE_VERSION
                ? encodedName
                : Options.sanitizeLabel(encodedName);
        if (expectedVersion == WIRE_VERSION && !isValidRouteDisplayName(name)) {
            throw new IOException("v9 group name is unsafe or too long");
        }
        String zone = legacyV2 ? poolGet(pool, readVarint(in)) : readZoneRef(in, pool);
        int groupFlags = in.readUnsignedByte();
        boolean bodyless     = !legacyV2 && (groupFlags & GROUP_FLAG_BODYLESS_WAYPOINTS) != 0;
        boolean autoGrad     = (groupFlags & GROUP_FLAG_GRAD_AUTO)     != 0;
        boolean sequence     = (groupFlags & GROUP_FLAG_LOAD_SEQUENCE) != 0;
        boolean customRadius = (groupFlags & GROUP_FLAG_CUSTOM_RADIUS) != 0;
        boolean persistentMeta = expectedVersion == WIRE_VERSION
                && (groupFlags & GROUP_FLAG_V9_PERSISTENT_META) != 0;
        CoordMode coordMode  = decodeCoordMode(groupFlags, expectedVersion);

        WaypointGroup.GradientMode gradientMode = autoGrad
                ? WaypointGroup.GradientMode.AUTO
                : WaypointGroup.GradientMode.MANUAL;
        boolean skipAheadEnabled = true;
        int staticColor = DEFAULT_STATIC_COLOR;
        int gradientStartColor = DEFAULT_GRADIENT_START_COLOR;
        int gradientEndColor = DEFAULT_GRADIENT_END_COLOR;
        if (persistentMeta) {
            int metadataFlags = in.readUnsignedByte();
            if ((metadataFlags & V9_GROUP_META_RESERVED_MASK) != 0) {
                throw new IOException("v9 group metadata reserved bits are set");
            }
            gradientMode = switch (metadataFlags & 0b11) {
                case 0 -> WaypointGroup.GradientMode.MANUAL;
                case 1 -> WaypointGroup.GradientMode.AUTO;
                case 2 -> WaypointGroup.GradientMode.STATIC;
                default -> throw new IOException("v9 group metadata gradient mode is invalid");
            };
            if (autoGrad != (gradientMode == WaypointGroup.GradientMode.AUTO)) {
                throw new IOException("v9 group metadata disagrees with gradient flag");
            }
            skipAheadEnabled = (metadataFlags & V9_GROUP_META_SKIP_AHEAD) != 0;
            staticColor = readRgb(in);
            gradientStartColor = readRgb(in);
            gradientEndColor = readRgb(in);
        }

        double radius = customRadius
                ? readDefaultRadius(in, expectedVersion)
                : Waypoint.DEFAULT_REACH_RADIUS;

        WaypointGroup g = WaypointGroup.create(name, zone);
        g.setDefaultRadius(radius);
        // Imported groups always land enabled and at progress index 0. Exports
        // don't carry session state; the recipient gets a fresh route.
        g.setEnabled(true);
        g.setLoadMode(sequence ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
        g.setSkipAheadEnabled(skipAheadEnabled);
        g.setStaticColor(staticColor);
        g.setGradientStartColor(gradientStartColor);
        g.setGradientEndColor(gradientEndColor);
        // Stamp gradient mode before adding waypoints so AUTO/STATIC apply the
        // persisted palette to the decoded list exactly as the source group did.
        g.setGradientMode(gradientMode);

        int pointCount = readVarint(in);

        DebugCapture.GroupBuilder gCap = null;
        if (cap != null) {
            gCap = cap.startGroup(groupIndex);
            gCap.name = name;
            gCap.zoneId = zone;
            gCap.groupFlagsByte = groupFlags;
            gCap.enabled = true;
            gCap.gradientAuto = autoGrad;
            gCap.loadSequence = sequence;
            gCap.customRadius = customRadius;
            gCap.coordMode = coordMode;
            gCap.defaultRadius = radius;
            gCap.currentIndex = 0;
            gCap.pointCount = pointCount;
        }

        int coordStart = bais.position();
        int[][] coords = readCoords(in, pointCount, coordMode, expectedVersion);
        int coordEnd = bais.position();
        if (gCap != null) gCap.coordBlockBytes = coordEnd - coordStart;

        List<Waypoint> waypoints = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) {
            int x = coords[i][0], y = coords[i][1], z = coords[i][2];
            int wpFlags = bodyless ? 0 : in.readUnsignedByte();
            String wname = "";
            if ((wpFlags & WP_FLAG_HAS_NAME) != 0) {
                wname = !legacyV2 && (wpFlags & WP_FLAG_NAME_INLINE) != 0
                        ? readUtf8String(in)
                        : poolGet(pool, readVarint(in));
                if (expectedVersion == WIRE_VERSION) {
                    if (!isValidRouteDisplayName(wname)) {
                        throw new IOException("v9 waypoint name is unsafe or too long");
                    }
                } else {
                    wname = Options.sanitizeLabel(wname);
                }
            }
            int color    = (wpFlags & WP_FLAG_HAS_COLOR) != 0
                    ? (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte()
                    : Waypoint.DEFAULT_COLOR;
            double radiusW = (wpFlags & WP_FLAG_HAS_RADIUS) != 0
                    ? readCustomRadius(in, expectedVersion)
                    : 0.0;
            int wFlags     = (wpFlags & WP_FLAG_EXTENDED)   != 0 ? readFlagsVarint(in) : 0;
            boolean hasPrecise = expectedVersion >= PRECISE_WAYPOINT_MIN_VERSION
                    && (wpFlags & WP_FLAG_HAS_PRECISE) != 0;
            int preciseOffsets = hasPrecise ? readVarint(in) : 0;
            Waypoint waypoint = new Waypoint(x, y, z, wname, color, wFlags, radiusW);
            if (hasPrecise) {
                waypoint = applyPreciseOffsets(waypoint, preciseOffsets);
            }

            if (gCap != null) {
                gCap.waypoints.add(new DecodeDebug.WaypointDebug(
                        i, x, y, z, wpFlags,
                        (wpFlags & WP_FLAG_HAS_NAME)   != 0,
                        (wpFlags & WP_FLAG_HAS_COLOR)  != 0,
                        (wpFlags & WP_FLAG_HAS_RADIUS) != 0,
                        (wpFlags & WP_FLAG_EXTENDED)   != 0,
                        wname, color, radiusW, wFlags));
            }

            waypoints.add(waypoint);
        }
        g.addAll(waypoints);
        if (gCap != null) gCap.bodyBlockBytes = bais.position() - coordEnd;

        // currentIndex is never written on the wire (see writeGroup). Imported
        // groups always start at index 0, which is WaypointGroup's default.
        if (gradientMode != WaypointGroup.GradientMode.MANUAL) g.setGradientMode(gradientMode);
        return g;
    }

    private static Waypoint applyPreciseOffsets(Waypoint base, int packedOffsets) throws IOException {
        if ((packedOffsets & ~PRECISE_OFFSET_PACKED_MASK) != 0) {
            throw new IOException("precise waypoint offsets out of range: " + packedOffsets);
        }
        int ox = (packedOffsets >> (PRECISE_OFFSET_BITS * 2)) & PRECISE_OFFSET_MASK;
        int oy = (packedOffsets >> PRECISE_OFFSET_BITS) & PRECISE_OFFSET_MASK;
        int oz = packedOffsets & PRECISE_OFFSET_MASK;
        int preciseX = base.x() * Waypoint.PRECISE_SCALE + ox;
        int preciseY = base.y() * Waypoint.PRECISE_SCALE + oy;
        int preciseZ = base.z() * Waypoint.PRECISE_SCALE + oz;
        return base.withPreciseSixteenths(preciseX, preciseY, preciseZ);
    }

    /** Read every waypoint's (x,y,z) in order according to the group's coord mode. */
    private static int[][] readCoords(DataInputStream in, int count, CoordMode mode, int wireVersion)
            throws IOException {
        if (count < 0 || count > MAX_WIRE_WAYPOINTS_PER_GROUP) {
            throw new IOException("waypoint count out of range: " + count);
        }
        boolean strictV9Coordinates = wireVersion == WIRE_VERSION;
        int[][] out = new int[count][3];
        switch (mode) {
            case VECTOR -> {
                int lx = 0, ly = 0, lz = 0;
                for (int i = 0; i < count; i++) {
                    int dx = readZigzag(in);
                    int dy = readZigzag(in);
                    int dz = readZigzag(in);
                    int x = validateDecodedCoordinate(i == 0 ? dx : (long) lx + dx,
                            strictV9Coordinates);
                    int y = validateDecodedCoordinate(i == 0 ? dy : (long) ly + dy,
                            strictV9Coordinates);
                    int z = validateDecodedCoordinate(i == 0 ? dz : (long) lz + dz,
                            strictV9Coordinates);
                    out[i][0] = x; out[i][1] = y; out[i][2] = z;
                    lx = x; ly = y; lz = z;
                }
            }
            case ABSOLUTE_VARINT -> {
                for (int i = 0; i < count; i++) {
                    out[i][0] = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
                    out[i][1] = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
                    out[i][2] = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
                }
            }
            case FIXED_COMPACT -> {
                BitReader bits = new BitReader(in);
                for (int i = 0; i < count; i++) {
                    int x = unZigzag(bits.read(FIXED_X_BITS));
                    int y = bits.read(FIXED_Y_BITS) - FIXED_Y_OFFSET;
                    int z = unZigzag(bits.read(FIXED_Z_BITS));
                    out[i][0] = x; out[i][1] = y; out[i][2] = z;
                }
                bits.alignToByteBoundary(strictV9Coordinates);
            }
            case FIT_COMPACT -> {
                int xOrigin = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
                int yOrigin = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
                int zOrigin = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
                int packedWidths = (in.readUnsignedByte() << 8) | in.readUnsignedByte();
                validatePackedCoordinateWidths(packedWidths, strictV9Coordinates);
                int xBits = (packedWidths >>> 10) & FIT_MAX_WIDTH;
                int yBits = (packedWidths >>>  5) & FIT_MAX_WIDTH;
                int zBits =  packedWidths        & FIT_MAX_WIDTH;
                BitReader bits = new BitReader(in);
                for (int i = 0; i < count; i++) {
                    int x = validateDecodedCoordinate(
                            xBits > 0 ? (long) xOrigin + bits.read(xBits) : xOrigin,
                            strictV9Coordinates);
                    int y = validateDecodedCoordinate(
                            yBits > 0 ? (long) yOrigin + bits.read(yBits) : yOrigin,
                            strictV9Coordinates);
                    int z = validateDecodedCoordinate(
                            zBits > 0 ? (long) zOrigin + bits.read(zBits) : zOrigin,
                            strictV9Coordinates);
                    out[i][0] = x; out[i][1] = y; out[i][2] = z;
                }
                bits.alignToByteBoundary(strictV9Coordinates);
            }
            case VECTOR_AXIS_SEPARATED -> {
                readAxisSeparatedFirstPoint(in, out, count, strictV9Coordinates);
                for (int axis = 0; axis < 3; axis++) {
                    int last = count == 0 ? 0 : out[0][axis];
                    for (int i = 1; i < count; i++) {
                        last = validateDecodedCoordinate((long) last + readZigzag(in),
                                strictV9Coordinates);
                        out[i][axis] = last;
                    }
                }
            }
            case DELTA_FIT_AXIS_SEPARATED -> {
                readAxisSeparatedFirstPoint(in, out, count, strictV9Coordinates);
                int packedWidths = (in.readUnsignedByte() << 8) | in.readUnsignedByte();
                validatePackedCoordinateWidths(packedWidths, strictV9Coordinates);
                int xBits = (packedWidths >>> 10) & FIT_MAX_WIDTH;
                int yBits = (packedWidths >>>  5) & FIT_MAX_WIDTH;
                int zBits =  packedWidths        & FIT_MAX_WIDTH;
                int[] widths = { xBits, yBits, zBits };
                BitReader bits = new BitReader(in);
                for (int axis = 0; axis < 3; axis++) {
                    int last = count == 0 ? 0 : out[0][axis];
                    int width = widths[axis];
                    for (int i = 1; i < count; i++) {
                        int delta = width > 0 ? unZigzag(bits.read(width)) : 0;
                        last = validateDecodedCoordinate((long) last + delta, strictV9Coordinates);
                        out[i][axis] = last;
                    }
                }
                bits.alignToByteBoundary(strictV9Coordinates);
            }
            case RANGE_DELTA -> readRangeDeltaCoords(in, out, count, strictV9Coordinates);
        }
        return out;
    }

    private static void readRangeDeltaCoords(DataInputStream in, int[][] out, int count,
                                             boolean strictV9Coordinates) throws IOException {
        if (count > 0) {
            out[0][0] = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
            out[0][1] = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
            out[0][2] = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
        }
        int packedWidths = (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        validatePackedCoordinateWidths(packedWidths, strictV9Coordinates);
        int xBits = (packedWidths >>> 10) & FIT_MAX_WIDTH;
        int yBits = (packedWidths >>> 5) & FIT_MAX_WIDTH;
        int zBits = packedWidths & FIT_MAX_WIDTH;
        int[] widths = { xBits, yBits, zBits };

        int payloadLength = readVarint(in);
        validateRangeDeltaPayloadLength(count, widths, payloadLength);
        byte[] payload = in.readNBytes(payloadLength);
        if (payload.length != payloadLength) throw new IOException("truncated range-delta payload");

        RangeBitDecoder decoder = new RangeBitDecoder(payload);
        short[] probabilities = newRangeDeltaProbabilities();
        for (int axis = 0; axis < 3; axis++) {
            int width = widths[axis];
            int last = count == 0 ? 0 : out[0][axis];
            for (int i = 1; i < count; i++) {
                int encoded = 0;
                for (int bit = width - 1; bit >= 0; bit--) {
                    encoded |= decoder.readBit(probabilities, rangeDeltaContext(axis, bit)) << bit;
                }
                last = validateDecodedCoordinate((long) last + unZigzag(encoded), strictV9Coordinates);
                out[i][axis] = last;
            }
        }
    }

    private static void validateRangeDeltaPayloadLength(int count, int[] widths, int payloadLength)
            throws IOException {
        if (payloadLength < 0) throw new IOException("negative range-delta payload length: " + payloadLength);
        long deltaCount = Math.max(0L, (long) count - 1L);
        long widthSum = (long) widths[0] + widths[1] + widths[2];
        long bitBudget = deltaCount * widthSum;
        long computedCap = Math.max(16L, bitBudget + 16L);
        long cap = Math.min((long) RANGE_DELTA_MAX_PAYLOAD_BYTES, computedCap);
        if ((long) payloadLength > cap) {
            throw new IOException("range-delta payload too large: " + payloadLength);
        }
    }

    private static void validatePackedCoordinateWidths(int packedWidths, boolean strictV9Coordinates)
            throws IOException {
        if (strictV9Coordinates && (packedWidths & 0x8000) != 0) {
            throw new IOException("v9 coordinate width reserved bit is set");
        }
    }

    private static void readAxisSeparatedFirstPoint(DataInputStream in, int[][] out, int count,
                                                    boolean strictV9Coordinates) throws IOException {
        if (count == 0) return;
        out[0][0] = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
        out[0][1] = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
        out[0][2] = validateDecodedCoordinate(readZigzag(in), strictV9Coordinates);
    }

    private static int validateDecodedCoordinate(long value, boolean strictV9Coordinates) throws IOException {
        if (strictV9Coordinates
                && (value < MIN_WAYPOINT_BLOCK_COORDINATE || value > MAX_WAYPOINT_BLOCK_COORDINATE)) {
            throw new IOException("v9 coordinate outside representable block range: " + value);
        }
        return (int) value;
    }

    private static String poolGet(List<String> pool, int idx) throws IOException {
        if (idx < 0 || idx >= pool.size()) throw new IOException("string pool OOB: " + idx);
        return pool.get(idx);
    }

    // --- varint + zigzag + bit I/O ------------------------------------------------------------

    static void writeVarint(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v & 0x7F);
    }

    static int readVarint(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            if (shift == 28 && (b & 0xF8) != 0) {
                throw new IOException("varint overflow");
            }
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (shift > 0 && (b & 0x7F) == 0) throw new IOException("non-canonical varint");
                return result;
            }
            shift += 7;
            if (shift >= 35) throw new IOException("varint too long");
        }
    }

    static void writeZigzag(DataOutputStream out, int value) throws IOException {
        writeVarint(out, zigzag(value));
    }

    static int readZigzag(DataInputStream in) throws IOException {
        return unZigzag(readVarint(in));
    }

    private static int zigzag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    private static int unZigzag(int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    /**
     * Bit-level writer backed by a byte sink. Used by the FIXED_COMPACT coord
     * packer. Buffers up to 7 unaligned bits; {@link #flush()} zero-pads the
     * final partial byte so the subsequent stream is byte-aligned.
     */
    private static final class BitWriter {
        private final ByteArrayOutputStream sink;
        private long buf;
        private int bufBits;

        BitWriter(ByteArrayOutputStream sink) {
            this.sink = sink;
        }

        void write(int value, int bits) {
            if (bits < 0 || bits > 32) throw new IllegalArgumentException("bad bit width: " + bits);
            // Mask off high bits so a negative / out-of-range value can't spill into the next field.
            long mask = (1L << bits) - 1L;
            buf = (buf << bits) | (value & mask);
            bufBits += bits;
            while (bufBits >= 8) {
                int shift = bufBits - 8;
                sink.write((int) ((buf >>> shift) & 0xFF));
                bufBits -= 8;
                buf &= (1L << bufBits) - 1L;
            }
        }

        void flush() {
            if (bufBits == 0) return;
            sink.write((int) ((buf << (8 - bufBits)) & 0xFF));
            buf = 0;
            bufBits = 0;
        }
    }

    /**
     * Bit-level reader that pulls from a {@link DataInputStream} one byte at a
     * time. After reading the fixed coord stream, callers must call
     * {@link #alignToByteBoundary(boolean)} before resuming byte reads -- the next
     * section of the payload (waypoint bodies) is byte-aligned.
     */
    private static final class BitReader {
        private final DataInputStream in;
        private long buf;
        private int bufBits;

        BitReader(DataInputStream in) {
            this.in = in;
        }

        int read(int bits) throws IOException {
            if (bits < 0 || bits > 32) throw new IOException("bad bit width: " + bits);
            while (bufBits < bits) {
                buf = (buf << 8) | in.readUnsignedByte();
                bufBits += 8;
            }
            int shift = bufBits - bits;
            int value = (int) ((buf >>> shift) & ((1L << bits) - 1L));
            bufBits -= bits;
            buf &= (1L << bufBits) - 1L;
            return value;
        }

        /**
         * Discard buffered partial-byte bits so the underlying stream resumes at
         * the next full byte. V9 requires the writer's padding bits to be zero;
         * legacy versions retain their historical permissive behavior.
         */
        void alignToByteBoundary(boolean requireZeroPadding) throws IOException {
            if (requireZeroPadding && buf != 0) {
                throw new IOException("v9 coordinate padding bits must be zero");
            }
            buf = 0;
            bufBits = 0;
        }
    }

    /**
     * Carry-less binary range encoder for the RANGE_DELTA coordinate payload.
     */
    private static final class RangeBitEncoder {
        private long low = 0;
        private long range = RANGE_MASK;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        RangeBitEncoder() {
        }

        void writeBit(short[] probabilities, int context, int bit) {
            int probability = probabilities[context] & 0xFFFF;
            long bound = (range >>> RANGE_PROB_BITS) * probability;
            if (bit == 0) {
                range = bound;
                probability += (RANGE_PROB_SCALE - probability) >>> RANGE_PROB_MOVE;
            } else {
                low = (low + bound) & RANGE_MASK;
                range = (range - bound) & RANGE_MASK;
                probability -= probability >>> RANGE_PROB_MOVE;
            }
            probabilities[context] = (short) probability;
            renormalize();
        }

        private void renormalize() {
            while (true) {
                if ((low ^ (low + range)) < RANGE_TOP) {
                    // The top byte is stable and can be emitted.
                } else if (range < RANGE_BOTTOM) {
                    range = (-low) & (RANGE_BOTTOM - 1L);
                } else {
                    break;
                }
                out.write((int) ((low >>> 24) & 0xFF));
                low = (low << 8) & RANGE_MASK;
                range = (range << 8) & RANGE_MASK;
            }
        }

        byte[] finish() {
            for (int nbytes = 0; nbytes <= 4; nbytes++) {
                int shift = 8 * (4 - nbytes);
                long candidate = shift == 0
                        ? low
                        : ((low + ((1L << shift) - 1L)) >>> shift) << shift;
                if ((((candidate - low) & RANGE_MASK) < range) && candidate <= RANGE_MASK) {
                    for (int i = 0; i < nbytes; i++) {
                        out.write((int) ((candidate >>> (24 - 8 * i)) & 0xFF));
                    }
                    return out.toByteArray();
                }
            }
            for (int i = 0; i < 4; i++) {
                out.write((int) ((low >>> (24 - 8 * i)) & 0xFF));
            }
            return out.toByteArray();
        }
    }

    /**
     * Carry-less binary range decoder for the RANGE_DELTA coordinate payload.
     */
    private static final class RangeBitDecoder {
        private final byte[] data;
        private int position = 0;
        private long low = 0;
        private long range = RANGE_MASK;
        private long code = 0;

        RangeBitDecoder(byte[] data) {
            this.data = data;
            for (int i = 0; i < 4; i++) {
                code = ((code << 8) | nextByte()) & RANGE_MASK;
            }
        }

        private int nextByte() {
            int value = position < data.length ? data[position] & 0xFF : 0;
            position++;
            return value;
        }

        int readBit(short[] probabilities, int context) {
            int probability = probabilities[context] & 0xFFFF;
            long bound = (range >>> RANGE_PROB_BITS) * probability;
            int bit;
            if (((code - low) & RANGE_MASK) < bound) {
                bit = 0;
                range = bound;
                probability += (RANGE_PROB_SCALE - probability) >>> RANGE_PROB_MOVE;
            } else {
                bit = 1;
                low = (low + bound) & RANGE_MASK;
                range = (range - bound) & RANGE_MASK;
                probability -= probability >>> RANGE_PROB_MOVE;
            }
            probabilities[context] = (short) probability;
            renormalize();
            return bit;
        }

        private void renormalize() {
            while (true) {
                if ((low ^ (low + range)) < RANGE_TOP) {
                    // The top byte is stable and should be consumed.
                } else if (range < RANGE_BOTTOM) {
                    range = (-low) & (RANGE_BOTTOM - 1L);
                } else {
                    break;
                }
                code = ((code << 8) | nextByte()) & RANGE_MASK;
                low = (low << 8) & RANGE_MASK;
                range = (range << 8) & RANGE_MASK;
            }
        }
    }

    // --- debug capture ------------------------------------------------------------------------

    /**
     * {@link ByteArrayInputStream} that exposes its current read position. Used by
     * {@link #debugDecode(String)} to measure coord-block and body-block byte counts
     * per group without duplicating the parse loop.
     */
    private static final class TrackedByteStream extends ByteArrayInputStream {
        TrackedByteStream(byte[] buf) { super(buf); }
        int position() { return pos; }
    }

    /**
     * Scratchpad passed through the read path while {@link #debugDecode(String)} runs.
     * Mutable on purpose -- the capture accumulates during parsing and is turned into
     * an immutable {@link DecodeDebug} by {@link #build} at the end. Every field is
     * {@code null} / {@code 0} in normal decode because {@code readBody} is called
     * with a {@code null} capture.
     */
    private static final class DebugCapture {
        int headerByte;
        String label = "";
        List<String> stringPool;
        final List<GroupBuilder> groups = new ArrayList<>();

        GroupBuilder startGroup(int index) {
            GroupBuilder g = new GroupBuilder();
            g.index = index;
            groups.add(g);
            return g;
        }

        DecodeDebug build(String rawInput, String payload, String textEncoding, byte[] compressed,
                          byte[] raw, List<WaypointGroup> decoded, long nanos) {
            double ratio = raw.length == 0 ? 0.0 : (double) rawInput.length() / raw.length;
            List<DecodeDebug.GroupDebug> gs = new ArrayList<>(groups.size());
            for (GroupBuilder b : groups) gs.add(b.build());
            int version = headerByte & HEADER_VERSION_MASK;
            boolean v9 = version == WIRE_VERSION;
            int contentKind = v9 ? v9ContentKind(headerByte) : -1;
            boolean observedNames = contentKind == V9_CONTENT_KIND_COMPACT_ROUTE;
            if (v9 && !observedNames) {
                outer:
                for (GroupBuilder group : groups) {
                    for (DecodeDebug.WaypointDebug waypoint : group.waypoints) {
                        if (waypoint.hasName()) {
                            observedNames = true;
                            break outer;
                        }
                    }
                }
            }
            return new DecodeDebug(
                    rawInput,
                    rawInput.length(),
                    MAGIC,
                    payload.length(),
                    textEncoding,
                    compressed.length,
                    raw.length,
                    ratio,
                    headerByte,
                    version,
                    v9 ? observedNames : (headerByte & HEADER_FLAG_NAMES) != 0,
                    v9 ? (headerByte & V9_HEADER_FLAG_LABEL) != 0
                            : (headerByte & HEADER_FLAG_LABEL) != 0,
                    v9 ? contentKind == V9_CONTENT_KIND_COORDINATE_ROUTE
                            || contentKind == V9_CONTENT_KIND_COORDINATE_ROUTE_WITH_META
                            : (headerByte & (1 << 6)) != 0,
                    (headerByte & (1 << 7)) != 0,
                    label,
                    List.copyOf(stringPool == null ? List.of() : stringPool),
                    List.copyOf(gs),
                    decoded,
                    nanos);
        }

        /** Mutable builder for one group's debug record; finalized in {@link #build()}. */
        static final class GroupBuilder {
            int index;
            String name;
            String zoneId;
            int groupFlagsByte;
            boolean enabled;
            boolean gradientAuto;
            boolean loadSequence;
            boolean customRadius;
            CoordMode coordMode;
            double defaultRadius;
            int currentIndex;
            int pointCount;
            int coordBlockBytes;
            int bodyBlockBytes;
            final List<DecodeDebug.WaypointDebug> waypoints = new ArrayList<>();

            DecodeDebug.GroupDebug build() {
                return new DecodeDebug.GroupDebug(
                        index, name, zoneId, groupFlagsByte,
                        enabled, gradientAuto, loadSequence, customRadius,
                        coordMode == null ? "COMPACT_V9_RANGE" : coordMode.name(),
                        coordMode == null ? -1 : coordMode.wireValue,
                        defaultRadius, currentIndex, pointCount,
                        coordBlockBytes, bodyBlockBytes,
                        List.copyOf(waypoints));
            }
        }
    }

    // --- deflate ------------------------------------------------------------------------------

    /**
     * Raw DEFLATE with a preset dictionary. Raw (nowrap=true) skips the 2-byte
     * zlib header and 4-byte Adler-32 trailer that would otherwise cost six
     * bytes per share. v8+ integrity comes from the CRC-32 appended to the
     * uncompressed body before this method is called.
     */
    private static byte[] deflate(byte[] raw) throws IOException {
        byte[] defaultBytes = deflateWithStrategy(raw, Deflater.DEFAULT_STRATEGY);
        byte[] filteredBytes = deflateWithStrategy(raw, Deflater.FILTERED);
        return escapedTextLength(filteredBytes) < escapedTextLength(defaultBytes)
                ? filteredBytes
                : defaultBytes;
    }

    private static int readFlagsVarint(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = in.readUnsignedByte();
            if (shift == 28 && (next & 0xF0) != 0) {
                throw new IOException("waypoint flags exceed 32 bits");
            }
            result |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                if (shift > 0 && (next & 0x7F) == 0) {
                    throw new IOException("non-canonical waypoint flags varint");
                }
                return result;
            }
        }
        throw new IOException("waypoint flags varint is too long");
    }

    private static byte[] deflateV9(byte[] raw) throws IOException {
        byte[] defaultBytes = deflateWithStrategyAndDictionary(
                raw, Deflater.DEFAULT_STRATEGY, V9CodecDictionary.BYTES);
        byte[] filteredBytes = deflateWithStrategyAndDictionary(
                raw, Deflater.FILTERED, V9CodecDictionary.BYTES);
        return escapedTextLength(filteredBytes) < escapedTextLength(defaultBytes)
                ? filteredBytes
                : defaultBytes;
    }

    private static byte[] deflateWithStrategy(byte[] raw, int strategy) throws IOException {
        return deflateWithStrategyAndDictionary(raw, strategy, CodecDictionary.BYTES);
    }

    private static byte[] deflateWithStrategyAndDictionary(byte[] raw, int strategy, byte[] dictionary)
            throws IOException {
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION, true);
        def.setStrategy(strategy);
        def.setDictionary(dictionary);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(out, def)) {
            dos.write(raw);
        } finally {
            def.end();
        }
        return out.toByteArray();
    }

    private static int escapedTextLength(byte[] compressed) {
        return escapeHypixelEmotes(AsciiStreamCodec.encode(compressed)).length();
    }

    /**
     * Approximate final text size of a single coord-mode candidate, used to
     * rank AUTO candidates. Lower = better.
     *
     * Uses the legacy writer's dictionary and text codec so both
     * dictionary hits and variable text-packing tails get counted. Doesn't
     * include cross-group compression context, so the score is a heuristic
     * rather than a true final size -- but it's dramatically more accurate than
     * raw-byte comparisons for streams that differ in entropy characteristics.
     *
     * Returns {@link Integer#MAX_VALUE} on I/O failure so the caller simply
     * never picks the affected candidate; in practice {@link Deflater} on an
     * in-memory buffer cannot actually fail.
     */
    private static int encodedGroupScore(byte[] bodyPrefix, WaypointGroup g, StringPool pool, CoordMode mode,
                                         byte[] coordAndBody, int baseGroupFlags, boolean customRadius,
                                         int wireVersion, Options opts,
                                         boolean extendedCoordModes) {
        try {
            ByteArrayOutputStream scratch = new ByteArrayOutputStream();
            scratch.write(bodyPrefix);
            DataOutputStream out = new DataOutputStream(scratch);
            writeVarint(out, pool.index(g.name()));
            writeZoneRef(out, exportedZoneId(g, opts), pool);
            int groupFlags = baseGroupFlags
                    | encodeCoordModeFlags(mode, extendedCoordModes);
            out.writeByte(groupFlags);
            if ((groupFlags & GROUP_FLAG_V9_PERSISTENT_META) != 0) {
                writeV9PersistentGroupMetadata(out, g, opts.includeColors);
            }
            if (customRadius) writeRadius(out, g.defaultRadius(), wireVersion);
            writeVarint(out, g.size());
            out.write(coordAndBody);
            out.flush();
            return encodedScore(scratch.toByteArray());
        } catch (IOException ioe) {
            return Integer.MAX_VALUE;
        }
    }

    private static int anonymousEncodedScore(byte[] bodyPrefix, WaypointGroup group, Options opts,
                                             CoordMode mode,
                                             byte[] coordAndBody, int baseGroupFlags, boolean customRadius,
                                             int wireVersion, boolean extendedCoordModes) {
        try {
            ByteArrayOutputStream scratch = new ByteArrayOutputStream();
            scratch.write(bodyPrefix);
            DataOutputStream out = new DataOutputStream(scratch);
            writeAnonymousGroupRecord(out, group, opts, new CoordPicked(mode, coordAndBody),
                    baseGroupFlags, customRadius, wireVersion, extendedCoordModes);
            out.flush();
            return encodedScore(scratch.toByteArray());
        } catch (IOException ioe) {
            return Integer.MAX_VALUE;
        }
    }

    private static int encodedScore(byte[] raw) {
        try {
            if (raw.length > 0 && (raw[0] & HEADER_VERSION_MASK) == V10_WIRE_VERSION) {
                V10Transport.Outbound best = V10Transport.direct(raw);
                V10Transport.Outbound defaultDeflate = V10Transport.deflated(
                        raw, Deflater.DEFAULT_STRATEGY);
                if (defaultDeflate.compareTo(best) < 0) best = defaultDeflate;
                V10Transport.Outbound filteredDeflate = V10Transport.deflated(
                        raw, Deflater.FILTERED);
                if (filteredDeflate.compareTo(best) < 0) best = filteredDeflate;
                return best.transport().length();
            }
            byte[] compressed = raw.length > 0 && (raw[0] & HEADER_VERSION_MASK) == WIRE_VERSION
                    ? deflateV9(appendChecksum(raw))
                    : deflate(appendChecksum(raw));
            return escapeHypixelEmotes(AsciiStreamCodec.encode(compressed)).length();
        } catch (IOException ioe) {
            return Integer.MAX_VALUE;
        }
    }

    private static byte[] appendChecksum(byte[] raw) {
        CRC32 checksum = new CRC32();
        checksum.update(raw);
        long value = checksum.getValue();
        byte[] framed = Arrays.copyOf(raw, raw.length + CHECKSUM_BYTES);
        int offset = raw.length;
        framed[offset] = (byte) (value >>> 24);
        framed[offset + 1] = (byte) (value >>> 16);
        framed[offset + 2] = (byte) (value >>> 8);
        framed[offset + 3] = (byte) value;
        return framed;
    }

    private static byte[] verifyAndStripChecksum(byte[] framed) throws IOException {
        if (framed.length < CHECKSUM_BYTES) {
            throw new IOException("missing CRC-32 trailer");
        }
        int bodyLength = framed.length - CHECKSUM_BYTES;
        long expected = ((long) (framed[bodyLength] & 0xFF) << 24)
                | ((long) (framed[bodyLength + 1] & 0xFF) << 16)
                | ((long) (framed[bodyLength + 2] & 0xFF) << 8)
                | (long) (framed[bodyLength + 3] & 0xFF);
        CRC32 checksum = new CRC32();
        checksum.update(framed, 0, bodyLength);
        if (checksum.getValue() != expected) {
            throw new IOException("CRC-32 mismatch");
        }
        return Arrays.copyOf(framed, bodyLength);
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        return inflate(compressed, MAX_INFLATED_BYTES, true, CodecDictionary.BYTES);
    }

    private static byte[] inflateV9(byte[] compressed) throws IOException {
        return inflate(compressed, MAX_INFLATED_BYTES, true, V9CodecDictionary.BYTES);
    }

    private static byte[] inflatePrefix(byte[] compressed, int maxOutputBytes) throws IOException {
        return inflate(compressed, maxOutputBytes, false, CodecDictionary.BYTES);
    }

    private static byte[] inflateV9Prefix(byte[] compressed, int maxOutputBytes) throws IOException {
        return inflate(compressed, maxOutputBytes, false, V9CodecDictionary.BYTES);
    }

    private static byte[] inflate(byte[] compressed, int maxOutputBytes, boolean requireFinished,
                                  byte[] dictionary)
            throws IOException {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(compressed);
            // With nowrap=true the dictionary is never advertised in the stream,
            // so setDictionary before the first inflate() call is the sole
            // binding of encoder dictionary to decoder dictionary.
            inf.setDictionary(dictionary);
            int initialSize = (int) Math.min((long) maxOutputBytes,
                    Math.max(32L, (long) compressed.length * 2L));
            ByteArrayOutputStream out = new ByteArrayOutputStream(initialSize);
            byte[] buf = new byte[256];
            while (!inf.finished()) {
                int remainingCapacity = maxOutputBytes - out.size();
                if (remainingCapacity == 0) {
                    if (requireFinished) {
                        throw new InflatedPayloadLimitException(maxOutputBytes);
                    }
                    return out.toByteArray();
                }
                int n = inf.inflate(buf, 0, Math.min(buf.length, remainingCapacity));
                if (n == 0) {
                    if (inf.needsInput()) {
                        if (!requireFinished) return out.toByteArray();
                        throw new IOException("truncated deflate stream");
                    }
                    if (inf.needsDictionary()) throw new IOException("deflate dictionary mismatch");
                    throw new IOException("deflate stream made no progress");
                }
                out.write(buf, 0, n);
            }
            if (requireFinished && inf.getRemaining() != 0) {
                throw new IOException("trailing compressed bytes: " + inf.getRemaining());
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("malformed deflate stream: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
    }

    // --- string pool --------------------------------------------------------------------------

    /** UTF-8 string pool. Sits between the header and group records so decoders see it first. */
    static final class StringPool {
        private final Map<String, Integer> idx = new HashMap<>();
        private final List<String> list = new ArrayList<>();
        private final Set<String> pooledWaypointNames = new HashSet<>();

        int intern(String s) {
            String k = s == null ? "" : s;
            Integer existing = idx.get(k);
            if (existing != null) return existing;
            int i = list.size();
            idx.put(k, i);
            list.add(k);
            return i;
        }

        int index(String s) {
            Integer v = idx.get(s == null ? "" : s);
            return v == null ? 0 : v;
        }

        void internWaypointName(String s) {
            String k = s == null ? "" : s;
            if (k.isEmpty()) return;
            pooledWaypointNames.add(k);
            intern(k);
        }

        boolean shouldPoolWaypointName(String s) {
            return pooledWaypointNames.contains(s == null ? "" : s);
        }

        void writeTo(DataOutputStream out) throws IOException {
            writeVarint(out, list.size());
            for (String s : list) {
                writeUtf8String(out, s);
            }
        }

        static List<String> readFrom(DataInputStream in) throws IOException {
            int count = readVarint(in);
            if (count < 0 || count > 1 << 16) throw new IOException("string pool too large: " + count);
            List<String> out = new ArrayList<>(Math.min(count, MAX_ARRAYLIST_PRESIZE));
            for (int i = 0; i < count; i++) {
                out.add(readUtf8String(in));
            }
            return out;
        }
    }
}
