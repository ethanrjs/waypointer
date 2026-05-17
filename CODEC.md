# Waypointer Codec

Waypointer shares routes as single pasteable strings that look like this:

```
WP:4BdPN0BU%k[nFq#[FH-++?AX6bO}NHVtY(cx5KE...
```

Paste it in chat, get back a group of waypoints. This document explains how that string is built.

**Current wire version: 5.**

Reference implementation in `src/main/java/dev/ethan/waypointer/codec/`:

- `WaypointCodec.java` body format, coord modes, chat escaping, encode/decode
- `AsciiStreamCodec.java` text alphabet (base-91 streaming, ASCII)
- `AsciiPackCodec.java` retired v2 base-85 packer, kept for tests/history
- `CodecDictionary.java` preset DEFLATE dictionary
- `CodecZoneDictionary.java` compact known-zone refs, seeded from Skyblocker

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
  [3] base-91 + escape ──── chat-safe alphabet ───    [3] unescape + base-91
       │                                                ▲
       ▼                                                │
  [4] "WP:" prefix      ──── scanner anchor ───────    [4] Strip "WP:"
       │                                                ▲
       ▼                                                │
  "WP:4BdPN0BU..."   ────────►   /pc <paste>  ─────►  "WP:4BdPN0BU..."
```

Each stage has one job:


| Stage          | Job                                                                    |
| -------------- | ---------------------------------------------------------------------- |
| Binary body    | Squeeze varints and bit-packed fields. Route-level smarts live here.   |
| DEFLATE + dict | Byte-level compression with a preset dictionary.                       |
| base-91        | Turn bytes into chat-safe ASCII at 1 byte per character.               |
| chat escape    | Split Hypixel's `<3`/`o/` MVP++ emote triggers without changing bytes. |
| `WP:` prefix   | Lets the chat scanner find the string without parsing it.              |


---

## 2. Why the Format Looks Like This

The Minecraft client silently refuses to send any chat command whose wire
packet runs past **256 UTF-8 bytes**. That's the real ceiling. The 256-character
chat textbox is a separate, weaker limit on typed input.

The format is built around that number:

```
  Total budget: 256 wire bytes per /command
  ┌──────────────────────────────────────────────────────────────┐
  │  /pc  │  WP:  │  ............  base-91 body  ............   │
  └───────┴───────┴──────────────────────────────────────────────┘
    3 B     3 B                    up to ~250 B
```

Everything in the codec is in service of cramming the most route info into those ~250 body bytes.

Other constraints shaping the design:

- Chat validation strips control characters, collapses whitespace, rejects `§` (`U+00A7`).
- Hypixel's advertising filter disconnects senders whose message looks URL-shaped, in particular, anything with a `.` in it. That's why the alphabet excludes `.`.
- Hypixel rewrites `<3` and `o/` to MVP++ emotes before recipients see chat. The encoder escapes those pairs after text packing so route bytes do not get malformed in transit.
- Commas are common surrounding punctuation in chat, so v5+ excludes them from fresh exports.
- Backticks make payloads awkward in Markdown-heavy surfaces like Discord, so v4+ excludes them from the output alphabet.
- Copy-paste must round-trip byte-identically.
- Hover tooltips need a cheap partial decode (the optional label).
- Exports describe a route to share, not a session — no progress state, no personal toggles.

---

## 3. Grammar

```
payload    := "WP:" body
body       := *alphabet-char   ; each char is 1 UTF-8 byte
```

The body decodes to bytes, which are raw DEFLATE, which inflates to the binary body in §6.

---

## 4. Text Alphabet (base-91)

### 4.1 Characters

91 printable ASCII characters. Every printable ASCII character except space, comma, `.`, and ```:

```
  ! " # $ % & ' ( ) * + - /
  0 1 2 3 4 5 6 7 8 9
  : ; < = > ? @
  A B C D E F G H I J K L M N O P Q R S T U V W X Y Z
  [ \ ] ^ _
  a b c d e f g h i j k l m n o p q r s t u v w x y z
  { | } ~
```

Every character:

- is a single UTF-8 byte, so byte budgets equal character budgets
- is not `.`, so sequences can't look like `host.tld` to Hypixel's ad filter
- is not `,`, so sentence punctuation cannot become part of fresh exports
- is not whitespace, so paste can't collapse runs
- is not `§`, so chat validation never treats a body character as a color code
- is not ```, so route strings can be pasted into Markdown without opening code spans
- is printable ASCII

v4+ applies one reversible chat escape after base-91/base-92 packing: `~` is doubled,
`<3` becomes `<~3`, and `o/` becomes `o~/`. The escape character is part of the
alphabet, so the scanner still sees one contiguous `WP:` body and the
escape costs only when a risky pair actually appears. Legacy v3 bodies skip this
step and still decode through the compatibility path.

### 4.2 Packing

The v5+ text layer is the basE91 streaming scheme generalized to a 91-symbol
alphabet. It accumulates source bits and emits 13 or 14 bits per two output
characters depending on whether the current 14-bit value fits in `91²`.

```
  91² = 8281
  2¹³ = 8192

  Most pairs carry 13 bits.
  89 low-value pairs carry 14 bits because they still fit below 8281.
```

Legacy v4 uses the same streaming scheme with the older 92-symbol alphabet,
which included comma. Legacy v3 used the 93-symbol alphabet, which also included
backtick. The scanner accepts all three alphabets so old chat exports still get
detected.

There is no pad trailer. The final partial bit buffer is emitted as one or two
characters, and decode reconstructs the exact original byte length.

Output length is data-dependent, typically about `ceil(n * 1.22)` characters
for `n` compressed bytes.

### 4.3 Why base-91 and not CJK / base64 / base-85?

The real budget is UTF-8 bytes, not visible glyph count:


| Alphabet              | Bits/char | UTF-8 bytes/char | **Bits per wire byte** | Notes                                  |
| --------------------- | --------- | ---------------- | ---------------------- | -------------------------------------- |
| base64                | 6.00      | 1                | 6.00                   | safe but wastes capacity               |
| v2 base-85            | 6.41      | 1                | 6.41                   | fixed 4-byte/5-char groups + 1 trailer |
| **v5+ base-91 stream** | ~6.51     | 1                | ~6.51                  | no trailer, variable 13/14-bit pairs   |
| v4 base-92 stream     | ~6.52     | 1                | ~6.52                  | legacy; includes comma                 |
| v3 base-93 stream     | ~6.53     | 1                | ~6.53                  | legacy; includes backtick              |
| CJK base-16384        | 14.00     | 3                | 4.67                   | visually short, byte-expensive         |


CJK looks like it wins on raw density (14 bits per character), but each character costs 3 UTF-8 bytes on the wire, so it's actually the worst per-byte. v1 used it because we were optimising for the chat textbox, not the server byte cap. v2 fixed that with base-85; v3 removed the base-85 pad trailer, v4 spends one symbol of capacity to avoid Markdown backticks, and v5 spends one more to keep commas out of fresh shares while adding extended coordinate modes.

### 4.4 Decode safety

`decode()` checks:

- every character is in the alphabet
- every two-character value fits the streaming base math

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
        │   │   │   │        └── 4 bits; MUST be non-zero; current: 4
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
         varint zoneRef         ; known-zone ref or pool index
         u8     groupFlags      ; see below
         [ varint radius_x10 ]  ; iff GROUP_FLAG_CUSTOM_RADIUS
         varint waypointCount
         coord-block            ; §6.7
         waypoint-body{waypointCount} ; omitted iff GROUP_FLAG_BODYLESS_WAYPOINTS
```

`groupFlags`:

```
  bit   7   6   5   4   3   2   1   0
      ┌───┬───┬───────┬───┬───┬───┬───┐
      │ r │ r │ coord │ R │ S │ G │ B │
      └───┴───┴───────┴───┴───┴───┴───┘
        │   │     │     │   │   │   │
        │   │     │     │   │   │   └── GROUP_FLAG_BODYLESS_WAYPOINTS
        │   │     │     │   │   └────── GROUP_FLAG_GRAD_AUTO  (1=AUTO, 0=MANUAL)
        │   │     │     │   └────────── GROUP_FLAG_LOAD_SEQUENCE (1=SEQUENCE, 0=STATIC)
        │   │     │     └────────────── GROUP_FLAG_CUSTOM_RADIUS (1=radius_x10 follows)
        │   │     └──────────────────── coord-mode ordinal 0..3 (see §6.7)
        │   └────────────────────────── reserved
        └────────────────────────────── reserved
```

`zoneRef` is a tagged varint:

```
known zone: (zoneDictionaryIndex << 1) | 1
custom zone: poolIndex << 1
```

The built-in zone dictionary is seeded from Skyblocker's `Location` enum
(Hypixel location IDs + friendly names), then extended with Waypointer's
canonical aliases and dungeon/mineshaft refinements. Unknown or user-created
zone IDs still round-trip through the string pool.

When `CUSTOM_RADIUS` is set, `radius_x10` is the radius in tenths (`3.5m = 35`). Radii step in 0.1 units, so a float would waste 3 bytes per group for no added precision.

### 6.6 What is never written

Exports describe a route to share, not a sender's session. Deliberately absent from the wire:

- Group `enabled` state, imported groups always land enabled
- Group `currentIndex` (progress), imported groups always start at index 0

The old bit slot for `enabled` is now reused as `GROUP_FLAG_BODYLESS_WAYPOINTS`.
When set, the coord block is followed by no per-waypoint body bytes; every
waypoint uses the default body (`wpFlags = 0`). This saves one raw zero byte per
waypoint before DEFLATE on clean geometry-only exports.

### 6.7 Coordinate block

Each group picks a coordinate scheme at encode time. In v5+, the chosen mode
uses `groupFlags[4..5]` for the low two bits and `groupFlags[6]` for bit 2.
Legacy v4 and older only used `groupFlags[4..5]` and therefore only support
modes 0..3.

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

#### Mode 4 - VECTOR_AXIS_SEPARATED

Same values as VECTOR, but transposed by axis:

```
  first absolute x, y, z
  all dx values
  all dy values
  all dz values
```

Raw size is essentially VECTOR's raw size, but the axis streams can give
DEFLATE cleaner repeated patterns on long mining/foraging routes.

#### Mode 5 - DELTA_FIT_AXIS_SEPARATED

Stores the first waypoint as absolute zigzag varints, then packs per-axis delta
streams with fitted bit widths:

```
  first absolute x, y, z
  u16: [dxBits:5 | dyBits:5 | dzBits:5 | 1 pad]
  bitpacked all dx, then all dy, then all dz
```

An axis width of 0 means every delta on that axis is zero. This mode is only an
AUTO candidate when every packed zigzag delta fits in 31 bits.

#### Picking a mode

The encoder tries every eligible mode, runs each candidate through DEFLATE plus
the base-91 text layer, and picks the one whose final text length is smallest.
Comparing raw bytes isn't enough — a repetitive VECTOR delta stream can look
large but compress to almost nothing, while an already-dense FIT_COMPACT
bitstream compresses poorly.

*Worst case*: AUTO matches the best forced mode. *Best case*: it saves real characters. The decoder pays nothing, it just reads whichever mode the group header names.

All coord modes lay out as `[ coord-stream | waypoint-bodies ]`, so the decoder reads `waypointCount` coords in whichever mode, then `waypointCount` waypoint bodies unless the group is bodyless.

### 6.8 Waypoint body

```
waypoint-body := u8 wpFlags
                 [ name-ref ]           ; iff WP_FLAG_HAS_NAME
                 [ byte[3] rgb ]        ; iff WP_FLAG_HAS_COLOR  (MSB-first R,G,B)
                 [ varint radius_x10 ]  ; iff WP_FLAG_HAS_RADIUS
                 [ varint flags ]       ; iff WP_FLAG_EXTENDED  (user flag byte, &0xFF)

name-ref      := varint nameIdx         ; pooled name, iff WP_FLAG_NAME_INLINE unset
              | varint byteLen; bytes   ; inline UTF-8, iff WP_FLAG_NAME_INLINE set
```

`wpFlags`:

```
  bit   7   6   5   4   3   2   1   0
      ┌───┬───┬───┬───┬───┬───┬───┬───┐
      │ r │ r │ r │ I │ X │ R │ C │ N │
      └───┴───┴───┴───┴───┴───┴───┴───┘
                            │   │   │   │
                            │   │   │   └── WP_FLAG_HAS_NAME   (else unnamed)
                            │   │   └────── WP_FLAG_HAS_COLOR  (else DEFAULT_COLOR)
                            │   └────────── WP_FLAG_HAS_RADIUS (else inherit group)
                            └────────────── WP_FLAG_EXTENDED   (else no user flags)
                        └────────────── WP_FLAG_NAME_INLINE (name-ref is inline UTF-8)
```

Unique waypoint names are inlined instead of inserted into the string pool,
because pooling a one-use name costs the string bytes plus a separate index.
Repeated waypoint names still pool, so common labels like `Terminal` or `Lever`
are stored once and referenced by index.

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
       ▼                ├─ coord mode through trial DEFLATE + base-91
  DEFLATE + preset dict ┘ and pick the shortest text
       │
       ▼
  base-91 encode
       │
       ▼
  escape <3 / o/ pairs
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
  remove v4+ chat escape
       │
       ▼
  base-91 decode
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
    read waypoint bodies (unless bodyless)
       │
       ▼
  return groups (+ label for decodeFull)
```

Unknown-version payloads fail fast with `unsupported wire version N` instead of limping through a misinterpreted body.

Current decoders accept v5, legacy v4, legacy v3, and legacy v2 exports:

- v5: `AsciiStreamCodec` base-91 text layer + Hypixel emote escape + extended coord modes.
- v4: `AsciiStreamCodec` base-92 text layer + Hypixel emote escape + v4 header.
- v3: `AsciiStreamCodec` base-93 text layer + v3 body (`zoneRef`, inline names,
bodyless groups).
- v2: `AsciiPackCodec` base-85 text layer + v2 body (zone IDs are string-pool
indexes, waypoint names are always pool refs, bit 0 of group flags is ignored).

The encoder only writes v5.

### 8.3 peekLabel

Chat-hover tooltip path:

```
  peekLabel(text):
    same prefix / v4+ unescape / base-91 / inflate path as decode, then legacy fallbacks
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

Four separate version surfaces:


| Surface           | Where it lives                | Cost of changing                                                                                                 |
| ----------------- | ----------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `MAGIC` (`"WP:"`) | Constant in `WaypointCodec`   | Breaks the chat scanner for all payloads.                                                                        |
| `WIRE_VERSION`    | Low nibble of the header byte | Breaks decode for older builds. Scanner still fires.                                                             |
| Dictionary bytes  | `CodecDictionary.RAW`         | Breaks decode at the stream level, `Inflater` throws on Adler-32 mismatch. Always bump `WIRE_VERSION` alongside. |
| Zone dictionary   | `CodecZoneDictionary.IDS`     | Breaks known-zone refs. Always bump `WIRE_VERSION` when entries change.                                          |


Rules:

1. Changing the header's high-nibble flag meanings → version bump.
2. Changing any field order in the body → version bump.
3. Editing `CodecDictionary.RAW` → version bump.
4. Editing `CodecZoneDictionary.IDS` → version bump.
5. Adding a new bit in a reserved slot is *not* a bump, as long as older decoders can ignore it safely. If older decoders would read a different byte stream, bump the wire version.
6. Legacy coord-mode storage was 2 bits. v5 claimed group flag bit 6 as the
   third coord-mode bit, so adding more coord modes now requires another version
   bump or another explicit extension bit.

---

## 10. Worked Example

A single group named `Dungeon`, zone `dungeon_f7`, three waypoints at `(10, 70, 10)`, `(12, 70, 10)`, `(12, 70, 15)` (not real i just made these up). AUTO gradient, STATIC load, default radius, no label, names kept.

Binary body (pre-DEFLATE, whitespace for readability):

```
13               header: version=3, names flag, no label
02               string pool: 2 entries
  00                       ""          (reserved at index 0)
  07  44 75 6E 67 65 6F 6E              "Dungeon"
01               groupCount = 1
  01             nameIdx   = 1   ("Dungeon")
  35             zoneRef   = (26 << 1) | 1   ("dungeon_f7")
  03             groupFlags = 0b00000011  (BODYLESS, GRAD_AUTO, VECTOR)
  03             waypointCount = 3
  -- VECTOR coord stream --
  14  8C 01  14    (10, 70, 10)  as zigzag varints
  04  00  00       delta (+2, 0, 0)
  00  00  0A       delta (0, 0, +5)
  -- waypoint bodies omitted: group is BODYLESS --
```

`dungeon_f7` is already in the known-zone dictionary, so the group stores it as
one varint instead of writing the string. The whole body compresses to roughly
half its raw size. The final string lands around 35–45 characters (= 35–45 wire
bytes), well inside a single chat command.

---

## 11. Implementation Notes

- Bit I/O is byte-aligned at section boundaries. After a FIXED_COMPACT or FIT_COMPACT coord stream, `BitReader.alignToByteBoundary()` drops buffered partial-byte bits so waypoint-body reads resume cleanly. The writer mirrors this via `BitWriter.flush()`.
- All pool lookups go through `poolGet`, which bounds-checks against pool size and throws `IOException` on out-of-range indices. Malformed payloads report `string pool OOB: N` instead of `IndexOutOfBoundsException`.
- The sanitizer runs on decode as well as encode. A well-meaning encoder already sanitizes; the decoder repeats the pass so a hand-crafted payload can't inject `§` codes into the hover tooltip.
- Gradient mode is stamped on the group *before* adding waypoints. Setting AUTO afterwards would recolor and overwrite the explicit colors just read from the wire.

---

## 12. Non-Goals

- Random Access Format is sequential, no index, no length-prefixed group, no "seek to group 3."
- Human readability, base-91 text looks like line noise. Use `debugDecode` or hex-dump the raw body.
- Interchange with other mods, `WP:` is native. `WaypointImporter` handles Skyblocker, Skytils, Soopy, and Coleweight payloads separately; they don't share bytes with this codec.
- Cross-version forward compat, an older build that sees a newer `WIRE_VERSION` refuses to decode. Guessing at a newer layout risks silent misreads.

---

## 13. File Map


| Path                                                     | Responsibility                                                            |
| -------------------------------------------------------- | ------------------------------------------------------------------------- |
| `codec/WaypointCodec.java`                               | Body format, coord modes, options, encode/decode.                         |
| `codec/AsciiStreamCodec.java`                            | base-91 text alphabet, streaming pack/unpack, validation.                 |
| `codec/AsciiPackCodec.java`                              | Retired v2 base-85 packer, kept for regression tests/history.             |
| `codec/CodecDictionary.java`                             | Preset DEFLATE dictionary.                                                |
| `codec/CodecZoneDictionary.java`                         | Skyblocker-seeded known-zone dictionary.                                  |
| `codec/DecodeDebug.java`                                 | Immutable debug snapshot returned by `debugDecode`.                       |
| `codec/WaypointImporter.java`                            | Multi-format import (Waypointer, Skyblocker, Skytils, Soopy, Coleweight). |
| `chat/CodecScanner.java`, `chat/ChatImportDetector.java` | Detect `WP:` substrings in chat lines.                                    |
