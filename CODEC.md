# Waypointer Codec

Waypointer shares routes as single pasteable strings that look like this:

```
WP:4BdPN0BU%k[nFq#[FH-++?AX6bO}NHVtY(cx5KE...
```

Paste it in chat, get back a group of waypoints. This document explains how that string is built.

**Current wire version: 2.**

Reference implementation in `src/main/java/dev/ethan/waypointer/codec/`:

- `WaypointCodec.java` body format, coord modes, encode/decode
- `AsciiPackCodec.java` text alphabet (base-85, ASCII)
- `CodecDictionary.java` preset DEFLATE dictionary

---

## 1. The Big Picture

Five steps. The top half runs on the sender, the bottom half runs on the receiver, in mirror order.

```
  sender                                           receiver
  ------                                           --------
  Waypoint list                                    Waypoint list
       │                                                ▲
       ▼                                                │
  [1] Binary body       ──── tight bit packing ────    [1] Binary body
       │                                                ▲
       ▼                                                │
  [2] DEFLATE + dict    ──── entropy compression ──    [2] Inflate + dict
       │                                                ▲
       ▼                                                │
  [3] base-85 text      ──── chat-safe alphabet ───    [3] base-85 decode
       │                                                ▲
       ▼                                                │
  [4] "WP:" prefix      ──── scanner anchor ───────    [4] Strip "WP:"
       │                                                ▲
       ▼                                                │
  "WP:4BdPN0BU..."   ────────►   /pc <paste>  ─────►  "WP:4BdPN0BU..."
```

Each stage has one job:

| Stage | Job |
| --- | --- |
| Binary body | Squeeze varints and bit-packed fields. Route-level smarts live here. |
| DEFLATE + dict | Byte-level compression with a preset dictionary. |
| base-85 | Turn bytes into chat-safe ASCII at 1 byte per character. |
| `WP:` prefix | Lets the chat scanner find the string without parsing it. |

---

## 2. Why the Format Looks Like This

The Minecraft client silently refuses to send any chat command whose wire
packet runs past **256 UTF-8 bytes**. That's the real ceiling. The 256-character
chat textbox is a separate, weaker limit on typed input.

The format is built around that number:

```
  Total budget: 256 wire bytes per /command
  ┌──────────────────────────────────────────────────────────────┐
  │  /pc  │  WP:  │  ............  base-85 body  ............   │
  └───────┴───────┴──────────────────────────────────────────────┘
    4 B     3 B                    up to ~249 B
```

Everything in the codec is in service of cramming the most route info into those ~249 body bytes.

Other constraints shaping the design:

- Chat validation strips control characters, collapses whitespace, rejects `§` (`U+00A7`).
- Hypixel's advertising filter disconnects senders whose message looks URL-shaped, in particular, anything with a `.` in it. That's why the alphabet swaps `.` for `;`.
- Copy-paste must round-trip byte-identically.
- Hover tooltips need a cheap partial decode (the optional label).
- Exports describe a route to share, not a session — no progress state, no personal toggles.

---

## 3. Grammar

```
payload    := "WP:" body
body       := 1*alphabet-char + trailer   ; each char is 1 UTF-8 byte
```

The body decodes to bytes, which are raw DEFLATE, which inflates to the binary body in §6.

---

## 4. Text Alphabet (base-85)

### 4.1 Characters

85 printable ASCII characters. Z85's alphabet with `.` swapped for `;`:

```
  0 1 2 3 4 5 6 7 8 9
  a b c d e f g h i j k l m n o p q r s t u v w x y z
  A B C D E F G H I J K L M N O P Q R S T U V W X Y Z
  ; - : + = ^ ! / * ? & < > ( ) [ ] { } @ % $ #
```

Every character:

- is a single UTF-8 byte, so byte budgets equal character budgets
- is not `.`, so sequences can't look like `host.tld` to Hypixel's ad filter
- is not whitespace, so paste can't collapse runs
- is not `§`, so chat validation never treats a body character as a color code
- is printable ASCII, verified live on Hypixel by typing every character in `/pc` and `/msg`

### 4.2 Packing

Four input bytes become five output characters:

```
  4 input bytes  (32 bits)
  ┌──────┬──────┬──────┬──────┐
  │  B0  │  B1  │  B2  │  B3  │
  └──────┴──────┴──────┴──────┘
             │
             ▼  interpret as one big-endian uint32
             │
             ▼  split into 5 base-85 digits (MSB first)
             │
  ┌────┬────┬────┬────┬────┐
  │ d0 │ d1 │ d2 │ d3 │ d4 │   each digit 0..84 → one alphabet char
  └────┴────┴────┴────┴────┘
```

If the input length isn't a multiple of 4, pad with zero bytes and remember how many. One trailer character (digit value `0..3`) records the pad count:

```
  ... 5 chars per group ...  [trailer]
                                  └── digit 0..3 = how many trailing
                                      bytes to discard on decode
```

Output length is `ceil(n / 4) * 5 + 1` characters for `n` input bytes.

### 4.3 Why base-85 and not base-84 / CJK / base64?

The packing math forces our hand. Four input bytes need to fit in five output digits, which requires `base⁵ ≥ 2³²`:

| Alphabet | base⁵ | ≥ 2³²? | Bits/char | UTF-8 bytes/char | **Bits per wire byte** |
| --- | ---: | :---: | ---: | ---: | ---: |
| base64 | 1,073,741,824 | no (uses 4-char groups instead) | 6.00 | 1 | 6.00 |
| base-84 | 4,182,119,424 | **no** | 6.39 | 1 | — |
| **base-85 (ours)** | 4,437,053,125 | yes | 6.41 | 1 | **6.41** |
| CJK base-16384 | — | — | 14.00 | 3 | 4.67 |

Base-84 *almost* works but `84⁵` falls short of `2³²` by ~113M, so some 4-byte inputs cannot be represented in 5 base-84 digits. We need at least 85 symbols.

Straight Z85 hits 85 symbols by including `.`, which is also the single most URL-shaped character on the keyboard. Hypixel's ad filter disconnected senders whenever a random digit sequence happened to land as `H.vD` or similar. Swapping `.` for `;` keeps the 85-symbol alphabet (so the packing math works) while making any URL shape impossible — semicolons don't appear in URLs.

CJK looks like it wins on raw density (14 bits per character), but each character costs 3 UTF-8 bytes on the wire, so it's actually the worst per-byte. v1 used it because we were optimising for the chat textbox, not the server byte cap. v2 fixed that.

### 4.4 Decode safety

`decode()` checks:

- body length (excluding trailer) is a multiple of 5
- every character is in the alphabet
- the trailer digit is in `[0, 3]`
- each 5-digit group's integer value fits in 32 bits (since `85⁵ > 2³²`, a digit sequence like `#####` would otherwise silently wrap)

A single bad character fails loudly instead of producing silent errors.

---

## 5. Compression

### 5.1 Framing

Raw DEFLATE (`Deflater(..., nowrap=true)`). No zlib header or Adler-32 trailer, those would cost 6 wire bytes per share for redundancy we don't need. The binary body parses strictly enough that corruption fails at the body layer.

### 5.2 Preset dictionary

Both encoder and decoder set `CodecDictionary.BYTES` as DEFLATE's preset dictionary. This acts as virtual history inside the LZ77 window, so the very first real byte can already back-reference common vocabulary:

```
  virtual history (~600 bytes, never transmitted)
  ┌──────────────────────────────────────────────────────────┐
  │ dungeon_f7 hub crystal_hollows ... Terminal Lever ...    │
  └──────────────────────────────────────────────────────────┘
                                                        ╲
                                                         ╲ first real byte
                                                          ▼
  actual stream                           ┌─────────────────────────┐
                                          │   binary body bytes...  │
                                          └─────────────────────────┘
                                          back-references into the
                                          dictionary cost 2-3 bytes
                                          each instead of shipping
                                          the full word
```

The dictionary concatenates:

- All canonical Hypixel SkyBlock zone IDs, ordered longest-and-most-common first
- Common waypoint name fragments: `Terminal`, `Lever`, `Puzzle`, `Device`, `Boss`, `Spawn`, `Start`, `End`, `Checkpoint`, `T1..T8`

Total size: ~600 bytes. Keeping it short matters, every byte is scanned on every encode and decode. Typical saving on named routes: 10–40% of the compressed output.

### 5.3 Dictionary changes are wire-version changes

Java's `Inflater` embeds the dictionary's Adler-32 in the stream and throws on mismatch. Any byte-level edit to `CodecDictionary.RAW` invalidates every previously-shared string.

**Rule**: don't edit the dictionary without bumping `WaypointCodec.WIRE_VERSION`.

---

## 6. Binary Body

All multi-byte numbers use varints or zigzag varints (§7). No raw little- or big-endian fields.

### 6.1 Top-level layout

```
  ┌──────┬──────────┬─────────────┬───────────────┬─────────────┐
  │ hdr  │ [label]  │ string pool │ groupCount    │ groups...   │
  │ 1 B  │ optional │ varint+utf8 │ varint        │ one per gid │
  └──────┴──────────┴─────────────┴───────────────┴─────────────┘
```

### 6.2 Header byte

```
  bit   7   6   5   4   3   2   1   0
      ┌───┬───┬───┬───┬───────────────┐
      │ r │ r │ L │ N │    version    │
      └───┴───┴───┴───┴───────────────┘
        │   │   │   │        │
        │   │   │   │        └── 4 bits; MUST be non-zero; current: 2
        │   │   │   └─────────── HEADER_FLAG_NAMES (informational)
        │   │   └─────────────── HEADER_FLAG_LABEL (a label byte-string follows)
        │   └─────────────────── reserved (encoder writes 0, decoder ignores)
        └─────────────────────── reserved (encoder writes 0, decoder ignores)
```

- Version 0 is reserved as "invalid" so a corrupted leading byte can't masquerade as an older schema.
- `HEADER_FLAG_NAMES` is informational. Each waypoint still carries its own `WP_FLAG_HAS_NAME`. The duplication lets debug tools surface sender intent without scanning every waypoint.
- Bits 6–7 (lol) are headroom. The version nibble can grow into them without a structural change.

### 6.3 Optional label

Present iff `HEADER_FLAG_LABEL` is set. Placed before the string pool so `peekLabel()` can stop reading early:

```
label := varint labelLen
         byte[labelLen]   ; UTF-8, already sanitized
```

Constraints:

- Max 256 bytes on the wire (`MAX_LABEL_BYTES`).
- Max 64 visible chars (`Options.MAX_LABEL_CHARS`).
- Sanitization strips `§`, C0 controls (`< 0x20`), `0x7F`, then trims whitespace.
- Sanitization runs on both encode and decode, a hand-crafted payload can't inject color codes or line breaks into tooltips.

### 6.4 String pool

Flat UTF-8 table that groups and waypoints reference by index:

```
string-pool := varint count
               ( varint byteLen; byte[byteLen] ){count}
```

Index 0 is always the empty string. Records reference "no name" as `nameIdx = 0` with no null check or sentinel.

Decode-only safety caps against memory amplification:

- Pool ≤ 65,536 entries
- Each string ≤ 1 MiB

### 6.5 Group record

```
group := varint nameIdx         ; pool index (0 = unnamed)
         varint zoneIdIdx       ; pool index
         u8     groupFlags      ; see below
         [ varint radius_x10 ]  ; iff GROUP_FLAG_CUSTOM_RADIUS
         varint waypointCount
         coord-block            ; §6.7
         waypoint-body{waypointCount}
```

`groupFlags`:

```
  bit   7   6   5   4   3   2   1   0
      ┌───┬───┬───────┬───┬───┬───┬───┐
      │ r │ r │ coord │ R │ S │ G │ r │
      └───┴───┴───────┴───┴───┴───┴───┘
        │   │     │     │   │   │   │
        │   │     │     │   │   │   └── reserved (formerly "enabled"; see §6.6)
        │   │     │     │   │   └────── GROUP_FLAG_GRAD_AUTO  (1=AUTO, 0=MANUAL)
        │   │     │     │   └────────── GROUP_FLAG_LOAD_SEQUENCE (1=SEQUENCE, 0=STATIC)
        │   │     │     └────────────── GROUP_FLAG_CUSTOM_RADIUS (1=radius_x10 follows)
        │   │     └──────────────────── coord-mode ordinal 0..3 (see §6.7)
        │   └────────────────────────── reserved
        └────────────────────────────── reserved
```

When `CUSTOM_RADIUS` is set, `radius_x10` is the radius in tenths (`3.5m = 35`). Radii step in 0.1 units, so a float would waste 3 bytes per group for no added precision.

### 6.6 What is never written

Exports describe a route to share, not a sender's session. Deliberately absent from the wire:

- Group `enabled` state, imported groups always land enabled
- Group `currentIndex` (progress), imported groups always start at index 0

The old bit slot for `enabled` is now reserved (bit 0 of `groupFlags`), encoder writes 0, decoder masks it off. Future reuse doesn't need a version bump.

### 6.7 Coordinate block

Each group picks one of four schemes at encode time. The chosen mode lives in `groupFlags[4..5]`.

#### Mode 0 — VECTOR (delta)

First waypoint absolute; every following waypoint is a delta from the previous:

```
  wp[0]:  (x,  y,  z)
  wp[1]:  (+dx, +dy, +dz)   ← from wp[0]
  wp[2]:  (+dx, +dy, +dz)   ← from wp[1]
   ...
```

*Best for*: routes you walk through sequentially, such as mining routes, with coordinates in the higher and lower extremes of the world.
Typical cost: 1–2 bytes per coordinate.

#### Mode 1 — ABSOLUTE_VARINT

Every waypoint as its own "zigzag" varint `(x, y, z)`. Zigzag varints are a signed integer encoding that is more efficient for small values.

*Best for*: routes that yo-yo between low-magnitude points where deltas would be large.

#### Mode 2 — FIXED_COMPACT

Every waypoint packed into exactly 33 bits:

```
  per waypoint:  [ x : 12 bits zigzag ][ y+64 : 9 bits ][ z : 12 bits zigzag ]
                 ◄──────────────────── 33 bits ──────────────────────────►
```

After the last waypoint, pad with up to 7 zero bits to realign to a byte boundary. Waypoint bodies start fresh on the next byte.

*Eligible only when*: `x, z ∈ [-2048, +2047]` and `y ∈ [-64, +447]`. Covers most SkyBlock interiors.

*Best for*: moderate-magnitude groups with no delta locality.

#### Mode 3 — FIT_COMPACT

Auto-fits per-axis bit widths to the group's actual range:

```
  preamble:   xOrigin (zigzag varint)
              yOrigin (zigzag varint)
              zOrigin (zigzag varint)
              u16:  [xBits:5 | yBits:5 | zBits:5 | 1 pad]

  per waypoint:
      ◄─ xBits ─►◄─ yBits ─►◄─ zBits ─►
      │ x-xOrig │ y-yOrig │ z-zOrig │        all unsigned
```

Origins are the per-axis `min` across the group, so every delta is ≥ 0 and no zigzag is needed inside the bitstream. An axis with width 0 means every coord equals the origin — zero bits per waypoint on that axis.

*Best for*: tightly-clustered groups. A dungeon group with `x∈[66..130]` (7 bits), `y∈[128..145]` (5 bits), `z∈[135..190]` (6 bits) packs each waypoint in **18 bits** vs FIXED_COMPACT's 33, at the cost of ~5 bytes of preamble.

#### Picking a mode

The encoder tries every eligible mode, runs each candidate through DEFLATE (with the preset dictionary), and picks the one whose *compressed* bytes are smallest. Comparing raw bytes isn't enough — a repetitive VECTOR delta stream can look large but compress to almost nothing, while an already-dense FIT_COMPACT bitstream compresses poorly.

*Worst case*: AUTO matches the best forced mode. *Best case*: it saves real characters. The decoder pays nothing, it just reads whichever mode the group header names.

All four modes lay out as `[ coord-stream | waypoint-bodies ]`, so the decoder reads `waypointCount` coords in whichever mode, then `waypointCount` waypoint bodies.

### 6.8 Waypoint body

```
waypoint-body := u8 wpFlags
                 [ varint nameIdx ]     ; iff WP_FLAG_HAS_NAME
                 [ byte[3] rgb ]        ; iff WP_FLAG_HAS_COLOR  (MSB-first R,G,B)
                 [ varint radius_x10 ]  ; iff WP_FLAG_HAS_RADIUS
                 [ varint flags ]       ; iff WP_FLAG_EXTENDED  (user flag byte, &0xFF)
```

`wpFlags`:

```
  bit   7   6   5   4   3   2   1   0
      ┌───┬───┬───┬───┬───┬───┬───┬───┐
      │ r │ r │ r │ r │ X │ R │ C │ N │
      └───┴───┴───┴───┴───┴───┴───┴───┘
                        │   │   │   │
                        │   │   │   └── WP_FLAG_HAS_NAME   (else unnamed)
                        │   │   └────── WP_FLAG_HAS_COLOR  (else DEFAULT_COLOR)
                        │   └────────── WP_FLAG_HAS_RADIUS (else inherit group)
                        └────────────── WP_FLAG_EXTENDED   (else no user flags)
```

#### Opt-out semantics

The sender's `Options` can disable whole field families: `includeNames`, `includeColors`, `includeRadii`, `includeWaypointFlags`. When a family is off, the encoder emits neither the flag bit nor the value. The decoder doesn't know about opt-outs, it just sees unset flags and falls back to the defaults above.

Colors are also omitted when they equal `DEFAULT_COLOR`, since the recipient substitutes the same constant anyway.

---

## 7. Varints and Zigzag

### 7.1 Varint

Standard 7-bit little-endian varint. Each byte's low 7 bits are a chunk of the value; the high bit signals "more bytes follow." The decoder caps shift at 35 bits (5 bytes for an int) so malformed streams can't hang:

```
  value:  300          (binary: 0000 0001 0010 1100)

  chunk 1: low 7 bits    →  0101100
  chunk 2: next 7 bits   →  0000010

  bytes on wire:
    ┌───────────┐┌───────────┐
    │ 1 0101100 ││ 0 0000010 │
    └───────────┘└───────────┘
     more=1       more=0 (last)
```

### 7.2 Zigzag

Signed values are "zigzagged" first so small negatives stay one byte:

```
  zigzag(v)    = (v << 1) ^ (v >> 31)    ; arithmetic shift
  unZigzag(n)  = (n >>> 1) ^ -(n & 1)

   0 → 0
  -1 → 1
   1 → 2
  -2 → 3
   2 → 4
  ...
```

---

## 8. Operations

### 8.1 Encode

```
  groups + options
       │
       ▼
  buildStringPool()
       │
       ▼
  write binary body   ──┐
       │                │ for each group, run every eligible
       ▼                ├─ coord mode through a trial DEFLATE
  DEFLATE + preset dict ┘ and pick the smallest
       │
       ▼
  base-85 encode
       │
       ▼
  prepend "WP:"
       │
       ▼
  "WP:..."
```

### 8.2 Decode

```
  "WP:..."
       │
       ▼
  verify "WP:" prefix
       │
       ▼
  base-85 decode
       │
       ▼
  Inflate + preset dict
       │
       ▼
  read header byte → reject if version != WIRE_VERSION
       │
       ▼
  if HEADER_FLAG_LABEL: read + sanitize label
       │
       ▼
  read string pool
       │
       ▼
  for each group:
    read group header
    read coord stream per coord-mode
    read waypoint bodies
       │
       ▼
  return groups (+ label for decodeFull)
```

Unknown-version payloads fail fast with `unsupported wire version N` instead of limping through a misinterpreted body.

### 8.3 peekLabel

Chat-hover tooltip path:

```
  peekLabel(text):
    same prefix / base-85 / inflate path as decode
    read header byte
    if version mismatch or HEADER_FLAG_LABEL unset → Optional.empty()
    read label, sanitize, return
    (all exceptions swallowed → Optional.empty())
```

This is why the label lives before the string pool — partial decoders never walk it.

### 8.4 Debug decode

`debugDecode` returns a full `DecodeDebug` record: header bits, label, pool contents, per-group flag byte, coord mode, coord-block and body-block byte counts, and per-waypoint flag bytes and values. Intended for `/wp debug`. Not hot path.

---

## 9. Versioning

Three separate version surfaces:

| Surface | Where it lives | Cost of changing |
| --- | --- | --- |
| `MAGIC` (`"WP:"`) | Constant in `WaypointCodec` | Breaks the chat scanner for all payloads. |
| `WIRE_VERSION` | Low nibble of the header byte | Breaks decode only. Scanner still fires. |
| Dictionary bytes | `CodecDictionary.RAW` | Breaks decode at the stream level, `Inflater` throws on Adler-32 mismatch. Always bump `WIRE_VERSION` alongside. |

Rules:

1. Changing the header's high-nibble flag meanings → version bump.
2. Changing any field order in the body → version bump.
3. Editing `CodecDictionary.RAW` → version bump.
4. Adding a new bit in a reserved slot is *not* a bump, as long as older decoders can ignore it safely. Decoder masks reserved bits, encoder writes 0. That's how bit 0 of `groupFlags` went from `enabled` to reserved without bumping.
5. The coord-mode field is 2 bits. v2 uses all four values (0..3). A fifth coord mode requires a version bump.

---

## 10. Worked Example

A single group named `Dungeon`, zone `dungeon_f7`, three waypoints at `(10, 70, 10)`, `(12, 70, 10)`, `(12, 70, 15)` (not real i just made these up). AUTO gradient, STATIC load, default radius, no label, names kept.

Binary body (pre-DEFLATE, whitespace for readability):

```
02               header: version=2, no flags, no label
03               string pool: 3 entries
  00                       ""          (reserved at index 0)
  07  44 75 6E 67 65 6F 6E              "Dungeon"
  0A  64 75 6E 67 65 6F 6E 5F 66 37     "dungeon_f7"
01               groupCount = 1
  01             nameIdx   = 1   ("Dungeon")
  02             zoneIdIdx = 2   ("dungeon_f7")
  02             groupFlags = 0b00000010  (GRAD_AUTO, VECTOR)
  03             waypointCount = 3
  -- VECTOR coord stream --
  14  8C 01  14    (10, 70, 10)  as zigzag varints
  04  00  00       delta (+2, 0, 0)
  00  00  0A       delta (0, 0, +5)
  -- waypoint bodies --
  00 00 00         all three: no optional fields
```

`dungeon_f7` is already in the preset dictionary, so its 10 bytes compress to a ~3-byte back-reference. The whole body compresses to roughly half its raw size. The final string lands around 40–50 characters (= 40–50 wire bytes), well inside a single chat command.

---

## 11. Implementation Notes

- Bit I/O is byte-aligned at section boundaries. After a FIXED_COMPACT or FIT_COMPACT coord stream, `BitReader.alignToByteBoundary()` drops buffered partial-byte bits so waypoint-body reads resume cleanly. The writer mirrors this via `BitWriter.flush()`.
- All pool lookups go through `poolGet`, which bounds-checks against pool size and throws `IOException` on out-of-range indices. Malformed payloads report `string pool OOB: N` instead of `IndexOutOfBoundsException`.
- The sanitizer runs on decode as well as encode. A well-meaning encoder already sanitizes; the decoder repeats the pass so a hand-crafted payload can't inject `§` codes into the hover tooltip.
- Gradient mode is stamped on the group *before* adding waypoints. Setting AUTO afterwards would recolor and overwrite the explicit colors just read from the wire.

---

## 12. Non-Goals

- Random Access Format is sequential, no index, no length-prefixed group, no "seek to group 3."
- Human readability, base-85 text looks like line noise. Use `debugDecode` or hex-dump the raw body.
- Interchange with other mods, `WP:` is native. `WaypointImporter` handles Skyblocker, Skytils, Soopy, and Coleweight payloads separately; they don't share bytes with this codec.
- Cross-version forward compat, an older build that sees a newer `WIRE_VERSION` refuses to decode. Guessing at a newer layout risks silent misreads.

---

## 13. File Map

| Path | Responsibility |
| --- | --- |
| `codec/WaypointCodec.java` | Body format, coord modes, options, encode/decode. |
| `codec/AsciiPackCodec.java` | base-85 text alphabet, pack/unpack, validation. |
| `codec/CodecDictionary.java` | Preset DEFLATE dictionary. |
| `codec/DecodeDebug.java` | Immutable debug snapshot returned by `debugDecode`. |
| `codec/WaypointImporter.java` | Multi-format import (Waypointer, Skyblocker, Skytils, Soopy, Coleweight). |
| `chat/CodecScanner.java`, `chat/ChatImportDetector.java` | Detect `WP:` substrings in chat lines. |
