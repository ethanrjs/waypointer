package dev.ethan.waypointer.codec;

import java.nio.charset.StandardCharsets;

/**
 * Preset DEFLATE dictionary used by {@link WaypointCodec} on both encode and decode.
 *
 * A preset dictionary is virtual history that the compressor can back-reference
 * but never transmits. The encoder and decoder MUST agree on the exact bytes --
 * Java's {@link java.util.zip.Inflater} embeds the dictionary's Adler-32 in the
 * deflate stream and throws on mismatch, so any accidental edit to this array
 * breaks decoding loudly, never silently.
 *
 * Content:
 *
 * Concatenated strings that appear verbatim in typical Waypointer routes:
 *
 *   - All canonical Hypixel Skyblock zone ids from
 *     {@link dev.ethan.waypointer.core.Zone}, ordered so the longest, most common
 *     tokens appear first (deflate's LZ77 window favors earlier matches).
 *   - Common waypoint name fragments used across dungeon routes
 *     ({@code Terminal}, {@code Lever}, {@code Puzzle}, {@code Device},
 *     {@code Boss}, {@code Spawn}, {@code Start}, {@code End}, T1..T8).
 *
 * Keep this string short -- deflate history only benefits matches that land
 * inside the 32 KiB LZ77 window, and a long dictionary costs CPU on every
 * encode/decode. The current payload is about 600 bytes, well under any
 * meaningful cost threshold.
 *
 * Versioning:
 *
 * The dictionary contents are effectively part of the codec wire format: changing
 * a byte changes its Adler-32, which invalidates every codec string produced by a
 * previous version. Do not edit {@link #RAW} without also bumping
 * {@link WaypointCodec#WIRE_VERSION}.
 */
public final class CodecDictionary {

    /**
     * Raw dictionary bytes. Intentionally packed as a single no-delimiter string:
     * DEFLATE's LZ77 doesn't care about token boundaries, and delimiter bytes
     * would waste dictionary space.
     */
    private static final String RAW =
            // Catacombs (most-common dungeon prefix; long shared prefix "dungeon_")
            "dungeon_f7dungeon_f6dungeon_f5dungeon_f4dungeon_f3dungeon_f2dungeon_f1"
          + "dungeon_m7dungeon_m6dungeon_m5dungeon_m4dungeon_m3dungeon_m2dungeon_m1"
          + "dungeon_hub"
            // Other curated zone ids (see Zone.KNOWN).
          + "crystal_hollowsdwarven_minesthe_farming_islesthe_parkthe_end"
          + "private_islandspiders_dennether_hubkuudrariftmineshaftdeep_caverns"
          + "gold_minegardenhub"
            // Waypoint name fragments common across shared routes.
          + "TerminalLeverPuzzleDeviceBossSpawnStartEndCheckpoint"
          + "T1T2T3T4T5T6T7T8";

    /** Immutable dictionary bytes. Shared between encoder and decoder. */
    public static final byte[] BYTES = RAW.getBytes(StandardCharsets.UTF_8);

    private CodecDictionary() {}
}
