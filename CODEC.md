# Waypointer Codec

Specification for the native `WP:` waypoint exchange format. Encodes one or more ordered waypoint groups as a single pasteable chat-safe string.

Current wire version: 1.

Reference implementation lives in `src/main/java/dev/ethan/waypointer/codec/`:

- `WaypointCodec.java`: body format, coord modes, string pool, options
- `CjkBase16384.java`: text alphabet
- `CodecDictionary.java`: preset DEFLATE dictionary

## 1. Design Goals

The codec is built around Minecraft chat constraints:

- 256 visible characters per chat message. A 40-waypoint route with names must fit.
- Chat validation strips control chars, collapses whitespace, and rejects `§` (`U+00A7`).
- Every character must render under the bundled Unifont fallback.
- Copy/paste round-trips must be byte-identical.
- Chat-hover previews need cheap partial decodes without running a full parse.
- Exports describe a route to share, not a session. Progress state and personal toggles must not leak.

The format is:

```
WP:<CJK-base-16384 text of DEFLATE(binary body)>
```

Each layer has a specific job:


| Layer             | Purpose                                                          |
| ----------------- | ---------------------------------------------------------------- |
| `WP:` prefix      | Anchor for the chat scanner. Not part of the versioned format.   |
| CJK base-16384    | 14 bits per character, about 2.18x the density of base64.        |
| DEFLATE           | Generic byte-level compression.                                  |
| Preset dictionary | Free compression for known zone ids and waypoint-name fragments. |
| Binary body       | Varint and bit-packed fields, tuned per-group for minimum bytes. |


## 2. Grammar

```
payload  := "WP:" cjk-body
cjk-body := 1*cjk-char         ; each char encodes 14 bits, see §3
```

The CJK body decodes to a byte sequence, which is raw DEFLATE (§4) compressed from the binary body (§5).

## 3. Text Layer

### 3.1 Alphabet

The 16384 code points `U+4E00` through `U+8DFF` (the first 2^14 CJK Unified Ideographs). Every character:

- renders under Minecraft's Unifont fallback (no tofu)
- has no combining marks, ZWJ glyphs, or variation selectors
- is outside every ASCII whitespace class, so paste cannot collapse it
- is not `U+00A7`, so Minecraft's chat validator accepts it

### 3.2 Packing

Seven input bytes (56 bits) pack into four 14-bit digits MSB-first. Each digit `d` emits `(char)(0x4E00 + d)`.

For `n` input bytes:

1. Compute `pad = (7 - (n mod 7)) mod 7` zero bytes to append so the length is a multiple of 7.
2. Encode in 7-byte groups, four chars per group.
3. Append one trailer character whose digit value (0..6) equals `pad`.

Output length is exactly `ceil(n / 7) * 4 + 1` characters.

### 3.3 Why not base64 / base85 / Z85?


| Alphabet  | Bits/char | Notes                                         |
| --------- | --------- | --------------------------------------------- |
| base64    | 6         | Lowest density. Padding `=` is ugly.          |
| Z85       | ~6.4      | Includes punctuation some chat filters scrub. |
| base85    | ~6.4      | Contains `"` and backtick.                    |
| CJK-16384 | 14        | About 2.18x more payload per chat character.  |


### 3.4 Decode safety

`decode()` validates:

- body length (excluding trailer) is a multiple of 4
- every character is inside `[U+4E00, U+8E00)`
- the trailer digit is in `[0, 6]`

A single out-of-range character fails loudly instead of producing silent garbage.

## 4. Compression Layer

### 4.1 Framing

Raw DEFLATE (`Deflater(..., nowrap=true)`). No zlib header, no Adler-32 trailer. Those would cost 6 bytes per share for redundancy we do not need. The binary body parses strictly enough that corruption fails at the body layer.

### 4.2 Preset dictionary

The encoder and decoder both set `CodecDictionary.BYTES` as a preset dictionary. It acts as virtual history inside the LZ77 window, so the first real byte can already back-reference it. Typical gain: 10-40% smaller compressed output on short routes.

The dictionary concatenates:

- All canonical Hypixel Skyblock zone ids from `Zone`, ordered longest and most common first.
- Common waypoint name fragments: `Terminal`, `Lever`, `Puzzle`, `Device`, `Boss`, `Spawn`, `Start`, `End`, `Checkpoint`, `T1`..`T8`.

Total size is about 600 bytes. Keeping it short matters: every byte is scanned on every encode and decode.

### 4.3 Dictionary is part of the wire version

Java's `Inflater` embeds the dictionary's Adler-32 in the deflate stream and throws on mismatch. Any byte-level edit to `CodecDictionary.RAW` invalidates every previously-shared string.

Rule: do not edit the dictionary without bumping `WaypointCodec.WIRE_VERSION`.

## 5. Binary Body

All multi-byte numbers use varints or zigzag varints (§6). There is no raw little- or big-endian field.

### 5.1 Top-level layout

```
body := header           ; u8, §5.2
        [ label ]        ; varint-prefixed UTF-8, §5.3 (iff HEADER_FLAG_LABEL)
        string-pool      ; §5.4
        varint groupCount
        group{groupCount}
```

### 5.2 Header byte

```
bit 7 6 5 4 3 2 1 0
    | | | | \_____/  version            (4 bits, MUST be non-zero; current: 1)
    | | | \_________ HEADER_FLAG_NAMES  (1 bit)
    | | \___________ HEADER_FLAG_LABEL  (1 bit)
    | \_____________ reserved (encoder writes 0, decoder ignores)
    \_______________ reserved (encoder writes 0, decoder ignores)
```

- Version 0 is reserved as "invalid" so a corrupted leading byte cannot masquerade as an older schema.
- `HEADER_FLAG_NAMES` is informational. Each waypoint still carries its own `WP_FLAG_HAS_NAME` bit. The duplication lets debug tools surface sender intent without scanning every waypoint.
- Bits 6 and 7 are reserved headroom. The version nibble can grow toward them without a structural change.

### 5.3 Optional label

Present iff `HEADER_FLAG_LABEL` is set. Placed before the string pool so `peekLabel(String)` can stop reading after the label.

```
label := varint labelLen
         byte[labelLen]   ; UTF-8, already sanitized
```

Constraints:

- Max 256 bytes on the wire (`MAX_LABEL_BYTES`).
- Max 64 visible chars (`Options.MAX_LABEL_CHARS`).
- Sanitization strips `§` (`U+00A7`), C0 controls (`< 0x20`), `0x7F`, then trims whitespace.
- Sanitization runs on both encode and decode. A hand-crafted payload cannot inject color codes or line breaks into hover tooltips.

### 5.4 String pool

A flat UTF-8 string table that group and waypoint records reference by index:

```
string-pool := varint count
               ( varint byteLen; byte[byteLen] ){count}
```

Index 0 is always the empty string. This lets records reference "no name" as `nameIdx = 0` with no null check or sentinel varint.

Safety caps, decode-only, to prevent memory amplification from malformed inputs:

- Pool size <= 65536 entries.
- Each string <= 1 MiB.

### 5.5 Group record

```
group := varint nameIdx         ; index into string pool (0 = unnamed)
         varint zoneIdIdx       ; index into string pool
         u8     groupFlags      ; see table below
         [ varint radius_x10 ]  ; iff GROUP_FLAG_CUSTOM_RADIUS
         varint waypointCount
         coord-block            ; §5.7
         waypoint-body{waypointCount}
```

`groupFlags`:


| Bits | Field                       | Notes                                                             |
| ---- | --------------------------- | ----------------------------------------------------------------- |
| 0    | reserved (formerly enabled) | Encoder writes 0, decoder masks off. See §5.6.                    |
| 1    | `GROUP_FLAG_GRAD_AUTO`      | 1 = AUTO gradient, 0 = MANUAL (preserve per-point colors).        |
| 2    | `GROUP_FLAG_LOAD_SEQUENCE`  | 1 = SEQUENCE load mode, 0 = STATIC.                               |
| 3    | `GROUP_FLAG_CUSTOM_RADIUS`  | 1 = explicit default radius follows; 0 = recipient default (3.0). |
| 4..5 | coord-mode ordinal (§5.7)   | 0 = VECTOR, 1 = ABSOLUTE_VARINT, 2 = FIXED_COMPACT.               |
| 6..7 | unused                      | Encoder writes 0, decoder ignores.                                |


When `GROUP_FLAG_CUSTOM_RADIUS` is set, `radius_x10` is the radius in tenths (3.5 m is `35`). User-facing radii step in 0.1 units, so a float would waste 3 bytes per group for no added precision.

### 5.6 What is never written

Exports describe a route to share, not a sender's session. These fields are deliberately absent from the wire:

- Group `enabled` state. Imported groups always land enabled.
- Group `currentIndex` (progress). Imported groups always start at index 0.

The old bit slot for `enabled` is reserved (bit 0 of `groupFlags`). The encoder zeroes it, the decoder masks it off. Future reuse does not need a version bump.

### 5.7 Coordinate block

Each group picks one of three schemes at encode time. The chosen mode is stored in `groupFlags[4..5]`.

#### VECTOR (mode 0, default)

- Waypoint 0: zigzag-varint `(x, y, z)` absolute.
- Waypoints 1..n-1: zigzag-varint `(dx, dy, dz)` from the previous point.

Wins on routes that walk consecutively. Typical dungeon path: 1-2 bytes per coordinate.

#### ABSOLUTE_VARINT (mode 1)

- Every waypoint: zigzag-varint `(x, y, z)` absolute.

Wins on routes that yo-yo between low-magnitude points. Deltas would be large and zigzag-expensive.

#### FIXED_COMPACT (mode 2)

- Every waypoint: `x` (12-bit zigzag), `y + 64` (9 bits unsigned), `z` (12-bit zigzag).
- Exactly 33 bits per waypoint, packed MSB-first into a bitstream.
- After the last coord, the bitstream is padded with up to 7 zero bits. Waypoint bodies resume at the next whole byte.

Eligible only when every waypoint satisfies `x, z in [-2048, +2047]` and `y in [-64, +447]`. Covers most Skyblock interiors.

Wins on groups with moderate magnitudes and no delta locality.

#### Picking a mode

The encoder runs every eligible mode and keeps the shortest. Worst case the AUTO pick ties the forced best by 0 bytes. Best case it saves real characters. The decoder pays nothing: it reads whichever mode the wire byte names.

All three modes emit `[ coord-stream | waypoint-bodies ]` in that order, so the decoder reads `waypointCount` coords using the group's mode, then `waypointCount` waypoint bodies.

### 5.8 Waypoint body

```
waypoint-body := u8 wpFlags
                 [ varint nameIdx ]     ; iff WP_FLAG_HAS_NAME
                 [ byte[3] rgb ]        ; iff WP_FLAG_HAS_COLOR (MSB-first R, G, B)
                 [ varint radius_x10 ]  ; iff WP_FLAG_HAS_RADIUS
                 [ varint flags ]       ; iff WP_FLAG_EXTENDED (user flag byte, & 0xFF)
```

`wpFlags`:


| Bit  | Name                 | Absent value                      |
| ---- | -------------------- | --------------------------------- |
| 0    | `WP_FLAG_HAS_NAME`   | `""` (unnamed)                    |
| 1    | `WP_FLAG_HAS_COLOR`  | `Waypoint.DEFAULT_COLOR`          |
| 2    | `WP_FLAG_HAS_RADIUS` | `0.0` (inherit group default)     |
| 3    | `WP_FLAG_EXTENDED`   | `0` (no flags)                    |
| 4..7 | unused               | encoder writes 0, decoder ignores |


#### Opt-out semantics

The sender's `Options` can disable whole field families (`includeNames`, `includeColors`, `includeRadii`, `includeWaypointFlags`). When a family is disabled the encoder emits neither the flag bit nor the value. The decoder does not know about opt-outs: it just sees a payload where some flags are unset and falls back to the defaults above.

Colors are also omitted when they equal `DEFAULT_COLOR`, since the recipient substitutes the same constant anyway.

## 6. Varints and Zigzag

### 6.1 Varint

Standard 7-bit-little-endian varint. Each byte's low 7 bits are a chunk of the value, the high bit signals "more bytes follow." The decoder caps shift at 35 bits (5 bytes for an int) to stop malformed streams from hanging.

### 6.2 Zigzag

Signed values are zigzagged first so small-magnitude negatives stay one byte:

```
zigzag(v)   = (v << 1) ^ (v >> 31)     ; arithmetic shift
unZigzag(n) = (n >>> 1) ^ -(n & 1)
```

`-1 -> 1`, `1 -> 2`, `-2 -> 3`, and so on.

## 7. Public Operations

### 7.1 Encode

```
encode(groups, opts):
  buildStringPool(groups, opts)
  write body per §5
  DEFLATE body with preset dictionary
  CJK-base-16384 encode
  prepend "WP:"
```

### 7.2 Decode

```
decode(text):
  verify "WP:" prefix
  CJK-base-16384 decode body
  inflate with preset dictionary
  read header byte; reject if version != WIRE_VERSION
  if HEADER_FLAG_LABEL: read label, sanitize again
  read string pool
  for each group:
    read group header
    read coord stream per coord-mode
    read waypoint bodies
  return groups (+ label, for decodeFull)
```

Unknown-version payloads fail fast with `unsupported wire version N` instead of limping through a misinterpreted body.

### 7.3 peekLabel

Used by the chat-hover tooltip:

```
peekLabel(text):
  same prefix / CJK / inflate path as decode
  read header byte
  if version mismatch or HEADER_FLAG_LABEL unset: return Optional.empty()
  read label, sanitize, return
  (all exceptions swallowed to Optional.empty())
```

This is why the label lives before the string pool. Partial decoders never have to walk it.

### 7.4 Debug decode

`debugDecode` returns the full `DecodeDebug` record: header bits, label, pool contents, per-group flag byte, coord mode, coord-block and body-block byte counts, and per-waypoint flag bytes and values. Intended for `/wp debug`. Not hot path.

## 8. Versioning and Compatibility

Three separate version surfaces:


| Surface           | Where it lives                | Break-on-change cost                                                                                             |
| ----------------- | ----------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `MAGIC` (`"WP:"`) | Constant in `WaypointCodec`   | Breaks the chat scanner for all payloads.                                                                        |
| `WIRE_VERSION`    | Low nibble of the header byte | Breaks decode only. `MAGIC` still matches, chat scanner still fires.                                             |
| Dictionary bytes  | `CodecDictionary.RAW`         | Breaks decode at the stream level; `Inflater` throws on Adler-32 mismatch. Always bump `WIRE_VERSION` alongside. |


Rules:

1. Changing the header's high nibble flag meanings is a version bump.
2. Changing any field order in the body is a version bump.
3. Editing `CodecDictionary.RAW` is a version bump.
4. Adding a new bit in a reserved slot is not a version bump, as long as older decoders can safely ignore it. The decoder masks reserved bits, the encoder writes 0. That is how bit 0 of `groupFlags` was repurposed from `enabled` to reserved without a bump.
5. New coord modes require a version bump. The coord-mode field is only 2 bits, and a fourth mode would collide with the "unknown wire value" check in `CoordMode.fromWire`.

## 9. Worked Example

A single group named `Dungeon`, zone `dungeon_f7`, three waypoints at `(10, 70, 10)`, `(12, 70, 10)`, `(12, 70, 15)`. AUTO gradient, STATIC load, default radius, no label, names stripped.

Binary body (pre-DEFLATE, whitespace for readability):

```
01               header: version=1, no flags, no label
03               string pool: 3 entries
  00                       ""          (reserved at index 0)
  07  44 75 6E 67 65 6F 6E              "Dungeon"
  0A  64 75 6E 67 65 6F 6E 5F 66 37     "dungeon_f7"
01               groupCount = 1
  01             nameIdx = 1 ("Dungeon")
  02             zoneIdIdx = 2 ("dungeon_f7")
  02             groupFlags = 0b00000010 (GRAD_AUTO, VECTOR)
  03             waypointCount = 3
  -- VECTOR coord stream --
  14  8C 01  14   (10, 70, 10) as zigzag varints
  04  00  00      delta (+2, 0, 0)
  00  00  0A      delta (0, 0, +5)
  -- waypoint bodies --
  00 00 00         all three waypoints: no flags
```

`dungeon_f7` is already in the preset dictionary, so its 10 bytes compress to a ~3-byte back-reference. The whole body compresses to roughly half its raw size. The final CJK string lands around 15-20 characters, well within a single chat line.

## 10. Implementation Notes

- Bit I/O is byte-aligned at section boundaries. After the FIXED_COMPACT coord stream, `BitReader.alignToByteBoundary()` drops buffered partial-byte bits so waypoint-body reads resume cleanly. The writer mirrors this via `BitWriter.flush()`.
- All pool lookups go through `poolGet`, which bounds-checks against pool size and throws `IOException` on out-of-range indices. Malformed payloads report `string pool OOB: N` instead of `IndexOutOfBoundsException`.
- The sanitizer runs on decode as well as encode. A well-meaning encoder already sanitizes; the decoder repeats the pass so a hand-crafted payload cannot inject `§` codes into the hover tooltip.
- Gradient mode is stamped on the group before adding waypoints. Setting AUTO afterwards would recolor and overwrite the explicit colors just read from the wire.

## 11. Non-Goals

- Random access. The format is sequential. There is no index, no length-prefixed group, no "seek to group 3".
- Human readability. CJK text is deliberately opaque. Use `debugDecode` or hex-dump the raw body.
- Interchange with other mods. `WP:` is native. `WaypointImporter` handles Skyblocker, Skytils, Soopy, and Coleweight payloads separately; they do not share bytes with this codec.
- Cross-version forward compat. An older build sees a newer `WIRE_VERSION` and refuses to decode. Guessing at a newer layout risks silent misreads.

## 12. File Map


| Path                                                     | Responsibility                                                            |
| -------------------------------------------------------- | ------------------------------------------------------------------------- |
| `codec/WaypointCodec.java`                               | Body format, coord modes, options, encode / decode.                       |
| `codec/CjkBase16384.java`                                | Text alphabet, pack / unpack, validation.                                 |
| `codec/CodecDictionary.java`                             | Preset DEFLATE dictionary.                                                |
| `codec/DecodeDebug.java`                                 | Immutable debug snapshot returned by `debugDecode`.                       |
| `codec/WaypointImporter.java`                            | Multi-format import (Waypointer, Skyblocker, Skytils, Soopy, Coleweight). |
| `chat/CodecScanner.java`, `chat/ChatImportDetector.java` | Detect `WP:` substrings in chat lines.                                    |


