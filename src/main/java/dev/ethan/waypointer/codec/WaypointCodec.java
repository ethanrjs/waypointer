package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Compact binary codec for sharing Waypointer routes as a single pasteable string.
 *
 * Wire format:
 *
 *     WP:<CJK-base-16384 body of raw DEFLATE(bin)>
 *
 * The {@code WP:} prefix is just a scanner anchor; the schema version lives
 * in the low nibble of the first body byte (see below). Keeping the version
 * out of the magic means a future bump never has to break the chat-import
 * detector or the regex-style callers that look for the prefix.
 *
 * The body is raw DEFLATE (no gzip header/trailer) compressed with a preset
 * dictionary of Hypixel zone ids and waypoint-name fragments, then encoded into
 * the CJK base-16384 alphabet for density. Each output character is a printable
 * Han glyph that survives Minecraft chat validation, paste, and font fallbacks.
 *
 * Binary body:
 *
 *     u8   header
 *           bits 0..3 = version (MUST be non-zero; 0 is reserved as "invalid"
 *                       so a corrupted leading byte can't masquerade as v0)
 *           bit 4     = includesNames
 *           bit 5     = hasLabel (sender-supplied human-readable title follows
 *                       immediately after the header, before the string pool)
 *           bits 6..7 = reserved; encoder writes 0, decoder ignores
 *     if hasLabel:
 *       varint labelLen
 *       utf8 bytes x labelLen      (already sanitized at encode time)
 *     varint stringPoolSize
 *     (varint len; utf8 bytes) x stringPoolSize
 *     varint groupCount
 *     per group:
 *       varint    nameIdx          (always written; empty if omitted)
 *       varint    zoneIdIdx
 *       u8        groupFlags
 *                   bit 0 = reserved (enabled-state); encoder writes 0, decoder ignores
 *                   bit 1 = gradientAuto       (else MANUAL)
 *                   bit 2 = loadSequence       (else STATIC)
 *                   bit 3 = customDefaultRadius (else 3.0)
 *                   bits 4..5 = coordMode (0=VECTOR delta, 1=ABSOLUTE_VARINT, 2=FIXED_COMPACT)
 *       if customDefaultRadius: varint radius_x10
 *       varint    waypointCount
 *       coord stream (see below)
 *       per waypoint: waypoint body (flags + optional name/color/radius)
 *
 * The label is intentionally placed before the string pool so a partial
 * decode (e.g. {@link #peekLabel(String)} for chat hover tooltips) can read
 * it without first walking the pool or any groups.
 *
 * Progress (currentIndex) and enabled/disabled state are intentionally never
 * written to the wire. Exports are for sharing routes, not personal sessions --
 * an imported route should start fresh on the recipient's client regardless of
 * the sender's playthrough state. The reserved high bits in the header byte
 * and bit 0 of {@code groupFlags} are left in place so we don't need a version
 * bump for this policy change; any payload that happens to set them decodes
 * identically because we discard the bits.
 *
 * Coordinate encoding:
 *
 * Each group picks its own coordinate scheme at encode time; the encoder tries
 * every mode the group qualifies for and keeps the smallest byte count:
 *
 *   - VECTOR (delta, default): first waypoint absolute zigzag-varint, rest stored
 *     as zigzag-varint deltas from previous. Wins when the route walks
 *     consecutively (1-2 bytes per coordinate).
 *   - ABSOLUTE_VARINT: every waypoint stored as absolute zigzag-varint. Wins when
 *     the route yo-yos between low-magnitude coordinates with large per-step
 *     travel (e.g. revisiting origin).
 *   - FIXED_COMPACT: every coord packed into a fixed bit-width stream -- 12 bits
 *     zigzag-x, 9 bits (y+64), 12 bits zigzag-z = 33 bits per waypoint. Only
 *     available when every coord in the group fits x,z in [-2048, +2047] and
 *     y in [-64, +447]. Wins on groups with moderate absolute magnitudes and no
 *     delta locality.
 *
 * Worst case AUTO picks wrong by 0 bytes (the losing modes are discarded);
 * best case it saves real characters on pathologically-shaped routes.
 */
public final class WaypointCodec {

    /** Prefix every encoded string starts with. Used by the chat scanner to find embedded exports. */
    public static final String MAGIC = "WP:";

    /**
     * Current wire format version. Lives in the low nibble of the header byte
     * so future breaking bumps can happen without touching MAGIC or the chat
     * scanner. Version 0 is reserved as "invalid" so a corrupted header byte
     * can't accidentally decode as an older schema.
     */
    static final int WIRE_VERSION = 1;
    private static final int HEADER_VERSION_MASK = 0x0F;
    /** Export flags occupy the high nibble so the version field can grow toward it if we ever need more than 4 bits. */
    private static final int HEADER_FLAG_NAMES = 1 << 4;
    /** Bit 5: a sender-supplied label string follows the header byte. */
    private static final int HEADER_FLAG_LABEL = 1 << 5;

    /**
     * Hard cap on label byte length on the wire. Keeps a single export within a
     * predictable upper bound (~256B before compression) and stops malformed
     * payloads from forcing huge allocations during {@link #peekLabel(String)}.
     * UTF-8 max 4 bytes/char -> roughly 64 visible characters, which matches
     * {@link Options#MAX_LABEL_CHARS}.
     */
    private static final int MAX_LABEL_BYTES = 256;

    // Bit 0 (previously "enabled-state") is reserved: the encoder zeroes it,
    // the decoder masks it off. Keeping the slot lets us evolve the group-flags
    // byte later without bumping the version.
    private static final int GROUP_FLAG_GRAD_AUTO     = 1 << 1;
    private static final int GROUP_FLAG_LOAD_SEQUENCE = 1 << 2;
    private static final int GROUP_FLAG_CUSTOM_RADIUS = 1 << 3;
    /** 2-bit field at bits 4..5 holding the coord-mode ordinal (0..2). */
    private static final int GROUP_FLAG_COORD_MODE_SHIFT = 4;
    private static final int GROUP_FLAG_COORD_MODE_MASK  = 0b11 << GROUP_FLAG_COORD_MODE_SHIFT;

    /** Bit widths for the FIXED_COMPACT packing. */
    private static final int FIXED_X_BITS = 12;
    private static final int FIXED_Y_BITS = 9;
    private static final int FIXED_Z_BITS = 12;
    /** Y coordinate offset so (y + FIXED_Y_OFFSET) stays non-negative. Covers y in [-64, +447]. */
    private static final int FIXED_Y_OFFSET = 64;

    private static final int WP_FLAG_HAS_NAME   = 1;
    private static final int WP_FLAG_HAS_COLOR  = 1 << 1;
    private static final int WP_FLAG_HAS_RADIUS = 1 << 2;
    private static final int WP_FLAG_EXTENDED   = 1 << 3;

    private WaypointCodec() {}

    /**
     * Per-group coordinate packing strategy. The numeric ordinal is wire-facing
     * (stored in the 2-bit coord-mode field of {@code groupFlags}); do not
     * reorder without bumping {@link #WIRE_VERSION}.
     */
    enum CoordMode {
        VECTOR(0),
        ABSOLUTE_VARINT(1),
        FIXED_COMPACT(2);

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
    enum PackingMode { AUTO, FORCE_VECTOR, FORCE_ABSOLUTE, FORCE_FIXED }

    /**
     * Export options. Five independent toggles control which payload fields are
     * emitted, plus an optional {@code label} the sender can use to title the
     * export. Progress and enabled state are never written -- shared routes
     * always import fresh on the recipient's client (see the class doc).
     *
     * The {@link #WITH_NAMES} / {@link #NO_NAMES} constants stay around as
     * shorthand for chat-typed shortcuts ({@code /wp export names}); GUI flows
     * build options through the {@link Builder} for finer control.
     *
     * Defaults are everything-on with no label: a fresh export captures exactly
     * what the sender sees so the recipient can import a faithful copy without
     * surprise data loss. Toggles let the sender opt out (e.g. drop personal
     * beacon-hide flags) on a per-export basis.
     */
    public static final class Options {
        /** Hard cap on label visible characters; the byte cap is tracked separately. */
        public static final int MAX_LABEL_CHARS = 64;

        /** Everything-on, no label. The recommended default for "share my route as-is". */
        public static final Options WITH_NAMES = builder().build();
        /** Everything-on but names stripped -- minimal-payload preset for chat sharing. */
        public static final Options NO_NAMES   = builder().includeNames(false).build();

        public final boolean includeNames;
        public final boolean includeColors;
        public final boolean includeRadii;
        public final boolean includeWaypointFlags;
        public final boolean includeGroupMeta;
        /** Sanitized label; empty string means "no label" (header bit 5 stays 0). */
        public final String  label;

        private Options(boolean includeNames, boolean includeColors, boolean includeRadii,
                        boolean includeWaypointFlags, boolean includeGroupMeta, String label) {
            this.includeNames         = includeNames;
            this.includeColors        = includeColors;
            this.includeRadii         = includeRadii;
            this.includeWaypointFlags = includeWaypointFlags;
            this.includeGroupMeta     = includeGroupMeta;
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
                    .label(label);
        }

        /** Convenience selector for callers that only know a names-included boolean. */
        public static Options forNamesIncluded(boolean includeNames) {
            return includeNames ? WITH_NAMES : NO_NAMES;
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
            private String  label                = "";

            public Builder includeNames(boolean v)         { this.includeNames = v; return this; }
            public Builder includeColors(boolean v)        { this.includeColors = v; return this; }
            public Builder includeRadii(boolean v)         { this.includeRadii = v; return this; }
            public Builder includeWaypointFlags(boolean v) { this.includeWaypointFlags = v; return this; }
            public Builder includeGroupMeta(boolean v)     { this.includeGroupMeta = v; return this; }
            public Builder label(String v)                 { this.label = sanitizeLabel(v); return this; }

            // Read accessors so UIs can seed their toggle state from the
            // builder's current values without storing a parallel snapshot.
            public boolean includeNames()         { return includeNames; }
            public boolean includeColors()        { return includeColors; }
            public boolean includeRadii()         { return includeRadii; }
            public boolean includeWaypointFlags() { return includeWaypointFlags; }
            public boolean includeGroupMeta()     { return includeGroupMeta; }
            public String  label()                { return label; }

            public Options build() {
                return new Options(includeNames, includeColors, includeRadii,
                        includeWaypointFlags, includeGroupMeta, label);
            }
        }
    }

    // --- public API ---------------------------------------------------------------------------

    /** Encode with names included. */
    public static String encode(List<WaypointGroup> groups) {
        return encode(groups, Options.WITH_NAMES);
    }

    /** Encode with explicit export options and automatic per-group coord packing. */
    public static String encode(List<WaypointGroup> groups, Options opts) {
        return encode(groups, opts, PackingMode.AUTO);
    }

    /**
     * Package-private: encode with an explicit packing mode. Only tests should pass
     * anything other than {@link PackingMode#AUTO}; forcing a mode defeats the
     * multi-pass selection and typically yields larger output.
     */
    static String encode(List<WaypointGroup> groups, Options opts, PackingMode mode) {
        try {
            byte[] raw = writeBody(groups, opts, mode);
            byte[] compressed = deflate(raw);
            return MAGIC + CjkBase16384.encode(compressed);
        } catch (IOException e) {
            throw new IllegalStateException("codec encode failed", e);
        }
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
        try {
            byte[] compressed = CjkBase16384.decode(payload);
            byte[] raw = inflate(compressed);
            DecodedHeader headerOut = new DecodedHeader();
            List<WaypointGroup> groups = readBody(raw, null, headerOut);
            return new Decoded(groups, headerOut.label);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("codec decode failed: " + e.getMessage(), e);
        }
    }

    /**
     * Cheap-enough integrity probe used by the chat import detector to decide
     * whether a candidate codec deserves an interactive pill or the stripped
     * {@code [Invalid Waypointer Code]} fallback.
     *
     * <p>Implemented as "try to fully decode and discard" because every
     * corruption surface the wire format cares about lives in the decoder
     * path: the CJK alphabet check, the DEFLATE bit-stream self-check (raw
     * DEFLATE doesn't carry a CRC, but any corrupted token sequence surfaces
     * as a {@code DataFormatException} on inflate), the header-version guard,
     * and the per-field length sanity scattered through {@link #readBody}.
     * Any bit-flip survives at most one of these -- two layers of self-check
     * in practice -- so a full decode is strictly stronger than a quick
     * prefix/length probe, and on the microsecond scale a chat-receive
     * handler can afford per detected match.
     *
     * @return {@code true} iff the payload decodes cleanly into at least one
     *         group. Empty decodes count as invalid because a zero-group
     *         export has no reason to exist and is almost certainly a truncation.
     */
    public static boolean isValidCodec(String text) {
        if (text == null) return false;
        try {
            Decoded decoded = decodeFull(text);
            return !decoded.groups().isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Best-effort partial decode that only returns the sender's label, or
     * {@link Optional#empty()} if the payload has none / fails to decode.
     *
     * Used by chat-hover tooltips where we want to surface the label without
     * paying for a full group parse on every received chat line. Failures
     * swallow silently because a malformed codec shouldn't crash a chat
     * receive handler -- the click-to-import path will surface the real error
     * when the user actually tries to import.
     */
    public static Optional<String> peekLabel(String text) {
        if (text == null) return Optional.empty();
        String trimmed = text.trim();
        if (!trimmed.startsWith(MAGIC)) return Optional.empty();
        String payload = trimmed.substring(MAGIC.length());
        try {
            byte[] compressed = CjkBase16384.decode(payload);
            byte[] raw = inflate(compressed);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            int header = in.readUnsignedByte();
            if ((header & HEADER_VERSION_MASK) != WIRE_VERSION) return Optional.empty();
            if ((header & HEADER_FLAG_LABEL) == 0) return Optional.empty();
            String label = readLabel(in);
            return label.isEmpty() ? Optional.empty() : Optional.of(label);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** Result of {@link #decodeFull(String)}: the groups plus whatever label the sender stamped on. */
    public record Decoded(List<WaypointGroup> groups, String label) {}

    /** Internal scratch type so {@link #readBody} can hand the label up to {@link #decodeFull} without a wider signature. */
    private static final class DecodedHeader {
        String label = "";
    }

    /**
     * Decode with full wire-level introspection. Returns the same group list that
     * {@link #decode(String)} would, plus every header byte, per-group flag, coord
     * mode, string-pool entry, and waypoint flag byte observed during parse.
     * Intended for the {@code /wp debug} inspector -- not for the hot path.
     */
    public static DecodeDebug debugDecode(String text) {
        if (text == null) throw new IllegalArgumentException("null payload");
        long t0 = System.nanoTime();
        String trimmed = text.trim();
        if (!trimmed.startsWith(MAGIC)) {
            throw new IllegalArgumentException("not a Waypointer export (expected " + MAGIC + " prefix)");
        }
        String payload = trimmed.substring(MAGIC.length());
        try {
            byte[] compressed = CjkBase16384.decode(payload);
            byte[] raw = inflate(compressed);
            DebugCapture cap = new DebugCapture();
            List<WaypointGroup> groups = readBody(raw, cap, null);
            long elapsed = System.nanoTime() - t0;
            return cap.build(text, payload, compressed, raw, groups, elapsed);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("codec debug decode failed: " + e.getMessage(), e);
        }
    }

    /** True iff {@code s} looks like a Waypointer export (prefix check only, does not validate payload). */
    public static boolean isCodecString(String s) {
        return s != null && s.trim().startsWith(MAGIC);
    }

    // --- writer -------------------------------------------------------------------------------

    private static byte[] writeBody(List<WaypointGroup> groups, Options opts, PackingMode mode) throws IOException {
        StringPool pool = buildStringPool(groups, opts);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.writeByte(buildHeaderByte(opts));
        if (!opts.label.isEmpty()) writeLabel(out, opts.label);
        pool.writeTo(out);
        writeVarint(out, groups.size());

        for (WaypointGroup g : groups) {
            writeGroup(out, g, pool, opts, mode);
        }
        out.flush();
        return buf.toByteArray();
    }

    /**
     * Write the optional sender label as varint length + UTF-8 bytes. Truncates
     * to {@link #MAX_LABEL_BYTES} so a sanitization regression upstream can't
     * blow past the wire-level cap (we'd rather drop trailing bytes than emit
     * an unparseable payload). The label is already sanitized at this point;
     * truncation is purely a defense-in-depth byte-budget check.
     */
    private static void writeLabel(DataOutputStream out, String label) throws IOException {
        byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LABEL_BYTES) {
            byte[] trimmed = new byte[MAX_LABEL_BYTES];
            System.arraycopy(bytes, 0, trimmed, 0, MAX_LABEL_BYTES);
            bytes = trimmed;
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
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static StringPool buildStringPool(List<WaypointGroup> groups, Options opts) {
        StringPool pool = new StringPool();
        // Reserve index 0 for "" so group/waypoint records can omit names without a null check.
        pool.intern("");
        for (WaypointGroup g : groups) {
            pool.intern(g.name());
            pool.intern(g.zoneId());
            if (opts.includeNames) {
                for (Waypoint w : g.waypoints()) if (w.hasName()) pool.intern(w.name());
            }
        }
        return pool;
    }

    /**
     * Pack the first header byte: version in the low nibble, export flags in
     * the high nibble. Keeping version at a fixed, well-known position means
     * the decoder can validate the schema before touching anything else.
     */
    private static int buildHeaderByte(Options opts) {
        int header = WIRE_VERSION & HEADER_VERSION_MASK;
        if (opts.includeNames) header |= HEADER_FLAG_NAMES;
        if (!opts.label.isEmpty()) header |= HEADER_FLAG_LABEL;
        return header;
    }

    private static void writeGroup(DataOutputStream out, WaypointGroup g, StringPool pool,
                                   Options opts, PackingMode mode) throws IOException {
        writeVarint(out, pool.index(g.name()));
        writeVarint(out, pool.index(g.zoneId()));

        CoordPicked picked = pickCoordMode(g, pool, opts, mode);

        // Enabled-state bit (bit 0) is intentionally never set: exports describe a
        // route to share, not the sender's toggle state. Same reason currentIndex
        // is no longer written below.
        //
        // When the sender opts out of group metadata, we want the recipient to
        // see the same defaults a freshly-created WaypointGroup would have:
        // AUTO gradient, STATIC load mode, default 3.0 radius. The wire bit
        // for "auto gradient" is 1, so we set it explicitly here -- writing
        // groupFlags=0 would otherwise decode as MANUAL, which is *worse*
        // than the user's source if they had AUTO selected. The coord-mode
        // nibble is unaffected: it's a wire-level packing detail, not
        // user-visible state.
        int groupFlags = 0;
        boolean customRadius = false;
        if (opts.includeGroupMeta) {
            if (g.gradientMode() == WaypointGroup.GradientMode.AUTO) groupFlags |= GROUP_FLAG_GRAD_AUTO;
            if (g.loadMode()     == WaypointGroup.LoadMode.SEQUENCE) groupFlags |= GROUP_FLAG_LOAD_SEQUENCE;
            customRadius = Math.abs(g.defaultRadius() - 3.0) > 0.001;
            if (customRadius) groupFlags |= GROUP_FLAG_CUSTOM_RADIUS;
        } else {
            groupFlags |= GROUP_FLAG_GRAD_AUTO;
        }
        groupFlags |= (picked.mode.wireValue << GROUP_FLAG_COORD_MODE_SHIFT) & GROUP_FLAG_COORD_MODE_MASK;
        out.writeByte(groupFlags);

        if (customRadius) writeVarint(out, (int) Math.round(g.defaultRadius() * 10.0));

        writeVarint(out, g.size());
        out.write(picked.bytes);
    }

    /** Result of coordinate-mode selection: the chosen mode and the encoded bytes. */
    private record CoordPicked(CoordMode mode, byte[] bytes) {}

    /**
     * Runs the packing-mode contest for one group. AUTO encodes every eligible
     * mode and keeps the smallest; forced modes skip the comparison entirely.
     *
     * FIXED_COMPACT is only eligible when every coord in the group fits the
     * tight bit-packing bounds -- trying to encode it when a coord overflows
     * would truncate silently.
     */
    private static CoordPicked pickCoordMode(WaypointGroup g, StringPool pool, Options opts,
                                             PackingMode mode) throws IOException {
        boolean fixedEligible = canUseFixedCompact(g.waypoints());

        switch (mode) {
            case FORCE_VECTOR -> {
                return new CoordPicked(CoordMode.VECTOR, encodeVectorOrAbsolute(g.waypoints(), pool, opts, false));
            }
            case FORCE_ABSOLUTE -> {
                return new CoordPicked(CoordMode.ABSOLUTE_VARINT, encodeVectorOrAbsolute(g.waypoints(), pool, opts, true));
            }
            case FORCE_FIXED -> {
                if (!fixedEligible) {
                    throw new IllegalArgumentException(
                            "FORCE_FIXED requested but group contains coords outside FIXED_COMPACT bounds");
                }
                return new CoordPicked(CoordMode.FIXED_COMPACT, encodeFixedCompact(g.waypoints(), pool, opts));
            }
            default -> {
                byte[] v = encodeVectorOrAbsolute(g.waypoints(), pool, opts, false);
                byte[] a = encodeVectorOrAbsolute(g.waypoints(), pool, opts, true);
                byte[] f = fixedEligible ? encodeFixedCompact(g.waypoints(), pool, opts) : null;

                CoordPicked best = new CoordPicked(CoordMode.VECTOR, v);
                if (a.length < best.bytes.length) best = new CoordPicked(CoordMode.ABSOLUTE_VARINT, a);
                if (f != null && f.length < best.bytes.length) best = new CoordPicked(CoordMode.FIXED_COMPACT, f);
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
                                                 boolean absolute) throws IOException {
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
        for (Waypoint w : pts) writeWaypointBody(out, w, pool, opts);
        out.flush();
        return scratch.toByteArray();
    }

    /**
     * Encode a group using the fixed-width bit-packed scheme. The coord
     * bitstream runs to completion (byte-aligned via the bit writer's flush)
     * BEFORE any waypoint bodies; this matches the all-coords-then-all-bodies
     * shape of the other modes and keeps the bit writer simple (at most 7 bits
     * of padding regardless of waypoint count).
     */
    private static byte[] encodeFixedCompact(List<Waypoint> pts, StringPool pool, Options opts) throws IOException {
        ByteArrayOutputStream scratch = new ByteArrayOutputStream();
        BitWriter bits = new BitWriter(scratch);
        for (Waypoint w : pts) {
            bits.write(zigzag(w.x()), FIXED_X_BITS);
            bits.write(w.y() + FIXED_Y_OFFSET, FIXED_Y_BITS);
            bits.write(zigzag(w.z()), FIXED_Z_BITS);
        }
        bits.flush();

        DataOutputStream out = new DataOutputStream(scratch);
        for (Waypoint w : pts) writeWaypointBody(out, w, pool, opts);
        out.flush();
        return scratch.toByteArray();
    }

    private static void writeWaypointBody(DataOutputStream out, Waypoint w, StringPool pool, Options opts)
            throws IOException {
        // Each "include" toggle gates an entire field family: when the sender
        // opts out, we don't emit the bit OR the value, so the decoder reads
        // back the recipient-side default (DEFAULT_COLOR, group radius, no flags).
        // This keeps the decode path completely unaware of opt-outs -- it just
        // sees a payload where some flags happen to be unset.
        boolean hasName   = opts.includeNames && w.hasName();
        boolean hasColor  = opts.includeColors
                && (w.color() & 0xFFFFFF) != (Waypoint.DEFAULT_COLOR & 0xFFFFFF);
        boolean hasRadius = opts.includeRadii         && w.customRadius() > 0;
        boolean extended  = opts.includeWaypointFlags && w.flags() != 0;

        int wpFlags = 0;
        if (hasName)   wpFlags |= WP_FLAG_HAS_NAME;
        if (hasColor)  wpFlags |= WP_FLAG_HAS_COLOR;
        if (hasRadius) wpFlags |= WP_FLAG_HAS_RADIUS;
        if (extended)  wpFlags |= WP_FLAG_EXTENDED;
        out.writeByte(wpFlags);

        if (hasName)   writeVarint(out, pool.index(w.name()));
        if (hasColor) {
            int c = w.color() & 0xFFFFFF;
            out.writeByte((c >> 16) & 0xFF);
            out.writeByte((c >>  8) & 0xFF);
            out.writeByte( c        & 0xFF);
        }
        if (hasRadius) writeVarint(out, (int) Math.round(w.customRadius() * 10.0));
        if (extended)  writeVarint(out, w.flags() & 0xFF);
    }

    // --- reader -------------------------------------------------------------------------------

    private static List<WaypointGroup> readBody(byte[] bytes, DebugCapture cap, DecodedHeader headerOut)
            throws IOException {
        TrackedByteStream bais = new TrackedByteStream(bytes);
        DataInputStream in = new DataInputStream(bais);
        // Header byte: version in the low nibble, flags in the high nibble.
        // Reject unknown versions up front so a schema bump surfaces a clean
        // error message instead of a garbled field read downstream.
        int header = in.readUnsignedByte();
        int version = header & HEADER_VERSION_MASK;
        if (version != WIRE_VERSION) {
            throw new IOException("unsupported wire version " + version
                    + " (this build speaks v" + WIRE_VERSION + ")");
        }
        // includesNames is informational only -- each waypoint carries its own
        // WP_FLAG_HAS_NAME bit. Other flag bits are reserved and ignored.
        if (cap != null) cap.headerByte = header;

        // Optional sender label sits between the header and the string pool so
        // peekLabel can stop early. Sanitize on read too: a hand-crafted payload
        // could embed §-codes or control chars even though the encoder won't.
        String label = "";
        if ((header & HEADER_FLAG_LABEL) != 0) {
            label = Options.sanitizeLabel(readLabel(in));
        }
        if (cap != null) cap.label = label;
        if (headerOut != null) headerOut.label = label;

        List<String> pool = StringPool.readFrom(in);
        if (cap != null) cap.stringPool = pool;
        int groupCount = readVarint(in);
        List<WaypointGroup> groups = new ArrayList<>(groupCount);

        for (int gi = 0; gi < groupCount; gi++) {
            groups.add(readGroup(in, bais, pool, cap, gi));
        }
        return groups;
    }

    private static WaypointGroup readGroup(DataInputStream in, TrackedByteStream bais, List<String> pool,
                                           DebugCapture cap, int groupIndex)
            throws IOException {
        String name = poolGet(pool, readVarint(in));
        String zone = poolGet(pool, readVarint(in));
        int groupFlags = in.readUnsignedByte();
        boolean autoGrad     = (groupFlags & GROUP_FLAG_GRAD_AUTO)     != 0;
        boolean sequence     = (groupFlags & GROUP_FLAG_LOAD_SEQUENCE) != 0;
        boolean customRadius = (groupFlags & GROUP_FLAG_CUSTOM_RADIUS) != 0;
        CoordMode coordMode  = CoordMode.fromWire(
                (groupFlags & GROUP_FLAG_COORD_MODE_MASK) >>> GROUP_FLAG_COORD_MODE_SHIFT);

        double radius = customRadius ? readVarint(in) / 10.0 : 3.0;

        WaypointGroup g = WaypointGroup.create(name, zone);
        g.setDefaultRadius(radius);
        // Imported groups always land enabled and at progress index 0. Exports
        // don't carry session state; the recipient gets a fresh route.
        g.setEnabled(true);
        g.setLoadMode(sequence ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
        // Stamp gradient mode BEFORE adding waypoints: AUTO applied after would
        // overwrite explicit colors read from the payload.
        g.setGradientMode(autoGrad ? WaypointGroup.GradientMode.AUTO : WaypointGroup.GradientMode.MANUAL);

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
        int[][] coords = readCoords(in, pointCount, coordMode);
        int coordEnd = bais.position();
        if (gCap != null) gCap.coordBlockBytes = coordEnd - coordStart;

        for (int i = 0; i < pointCount; i++) {
            int x = coords[i][0], y = coords[i][1], z = coords[i][2];
            int wpFlags = in.readUnsignedByte();
            String wname = (wpFlags & WP_FLAG_HAS_NAME) != 0 ? poolGet(pool, readVarint(in)) : "";
            int color    = (wpFlags & WP_FLAG_HAS_COLOR) != 0
                    ? (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte()
                    : Waypoint.DEFAULT_COLOR;
            double radiusW = (wpFlags & WP_FLAG_HAS_RADIUS) != 0 ? readVarint(in) / 10.0 : 0.0;
            int wFlags     = (wpFlags & WP_FLAG_EXTENDED)   != 0 ? readVarint(in) & 0xFF : 0;

            if (gCap != null) {
                gCap.waypoints.add(new DecodeDebug.WaypointDebug(
                        i, x, y, z, wpFlags,
                        (wpFlags & WP_FLAG_HAS_NAME)   != 0,
                        (wpFlags & WP_FLAG_HAS_COLOR)  != 0,
                        (wpFlags & WP_FLAG_HAS_RADIUS) != 0,
                        (wpFlags & WP_FLAG_EXTENDED)   != 0,
                        wname, color, radiusW, wFlags));
            }

            g.add(new Waypoint(x, y, z, wname, color, wFlags, radiusW));
        }
        if (gCap != null) gCap.bodyBlockBytes = bais.position() - coordEnd;

        // currentIndex is never written on the wire (see writeGroup). Imported
        // groups always start at index 0, which is WaypointGroup's default.
        if (autoGrad) g.setGradientMode(WaypointGroup.GradientMode.AUTO);
        return g;
    }

    /** Read every waypoint's (x,y,z) in order according to the group's coord mode. */
    private static int[][] readCoords(DataInputStream in, int count, CoordMode mode) throws IOException {
        int[][] out = new int[count][3];
        switch (mode) {
            case VECTOR -> {
                int lx = 0, ly = 0, lz = 0;
                for (int i = 0; i < count; i++) {
                    int dx = readZigzag(in);
                    int dy = readZigzag(in);
                    int dz = readZigzag(in);
                    int x = i == 0 ? dx : lx + dx;
                    int y = i == 0 ? dy : ly + dy;
                    int z = i == 0 ? dz : lz + dz;
                    out[i][0] = x; out[i][1] = y; out[i][2] = z;
                    lx = x; ly = y; lz = z;
                }
            }
            case ABSOLUTE_VARINT -> {
                for (int i = 0; i < count; i++) {
                    out[i][0] = readZigzag(in);
                    out[i][1] = readZigzag(in);
                    out[i][2] = readZigzag(in);
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
                bits.alignToByteBoundary();
            }
        }
        return out;
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
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
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
     * {@link #alignToByteBoundary()} before resuming byte reads -- the next
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

        /** Discard any buffered partial-byte bits so the underlying stream resumes at the next full byte. */
        void alignToByteBoundary() {
            buf = 0;
            bufBits = 0;
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

        DecodeDebug build(String rawInput, String payload, byte[] compressed, byte[] raw,
                          List<WaypointGroup> decoded, long nanos) {
            double ratio = raw.length == 0 ? 0.0 : (double) rawInput.length() / raw.length;
            List<DecodeDebug.GroupDebug> gs = new ArrayList<>(groups.size());
            for (GroupBuilder b : groups) gs.add(b.build());
            return new DecodeDebug(
                    rawInput,
                    rawInput.length(),
                    MAGIC,
                    payload.length(),
                    compressed.length,
                    raw.length,
                    ratio,
                    headerByte,
                    headerByte & HEADER_VERSION_MASK,
                    (headerByte & HEADER_FLAG_NAMES) != 0,
                    (headerByte & HEADER_FLAG_LABEL) != 0,
                    // Bits 6 and 7 of the header are reserved; encoder writes 0.
                    // Report the raw bits anyway so /wp debug shows whatever the
                    // payload actually contained.
                    (headerByte & (1 << 6)) != 0,
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
                        coordMode.name(), coordMode.wireValue,
                        defaultRadius, currentIndex, pointCount,
                        coordBlockBytes, bodyBlockBytes,
                        List.copyOf(waypoints));
            }
        }
    }

    // --- deflate ------------------------------------------------------------------------------

    /**
     * Raw DEFLATE with a preset dictionary. Raw (nowrap=true) skips the 2-byte
     * zlib header and 4-byte adler trailer that we'd otherwise waste on every
     * share -- the dictionary mismatch detection we'd normally get from that
     * trailer is provided instead by the Adler-32 inside the DEFLATE stream's
     * dictionary id record.
     */
    private static byte[] deflate(byte[] raw) throws IOException {
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION, true);
        def.setDictionary(CodecDictionary.BYTES);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(out, def)) {
            dos.write(raw);
        } finally {
            def.end();
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(compressed);
            // With nowrap=true the dictionary is never advertised in the stream,
            // so setDictionary before the first inflate() call is the sole
            // binding of encoder dictionary to decoder dictionary.
            inf.setDictionary(CodecDictionary.BYTES);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 2);
            byte[] buf = new byte[256];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) {
                        throw new IOException("truncated deflate stream");
                    }
                }
                out.write(buf, 0, n);
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

        void writeTo(DataOutputStream out) throws IOException {
            writeVarint(out, list.size());
            for (String s : list) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                writeVarint(out, bytes.length);
                out.write(bytes);
            }
        }

        static List<String> readFrom(DataInputStream in) throws IOException {
            int count = readVarint(in);
            if (count < 0 || count > 1 << 16) throw new IOException("string pool too large: " + count);
            List<String> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int len = readVarint(in);
                if (len < 0 || len > 1 << 20) throw new IOException("string too long: " + len);
                byte[] bytes = in.readNBytes(len);
                if (bytes.length != len) throw new IOException("truncated string");
                out.add(new String(bytes, StandardCharsets.UTF_8));
            }
            return out;
        }
    }
}
