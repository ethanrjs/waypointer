# Waypointer Codec

Waypointer exports routes as one pasteable chat string:

```text
WP:4BdPN0BU%k[nFq#[FH-++?AX6bO}NHVtY(cx5KE...
```

A recipient pastes the string in chat and imports the route as waypoint groups.

Current wire version: **9**.

Reference implementation: `src/main/java/com/babbur/waypointer/codec/` — see the file map in section 13.

v9 keeps the `WP:`/base-91 transport and CRC integrity frame, then adds:

- a three-bit content kind in the header, leaving zero-overhead reserved kinds for future unified config and dungeon artifacts;
- a compact full-route body for default single-group routes with modeled waypoint names, RGB palettes, and train-initialized range-coded coordinates;
- a separately identified compact coordinate-only body for the explicit minimal export preset;
- a version-specific 32 KiB train-only DEFLATE dictionary. Rich routes, including subwaypoints, use the general kind-0 group/waypoint body, which also preserves exact radii, the full 32-bit waypoint flag field, and persistent group presentation metadata.

This document describes the production wire format. The executable source of truth is `WaypointCodec.java`, `V9CompactCodec.java`, and the version-pinned dictionary resource listed in section 13; no ignored design draft is required to decode v9.

v8 keeps v7's body and text layouts, then adds an integrity frame around the binary body:

- a four-byte CRC-32 of the uncompressed binary body detects mutations that raw DEFLATE cannot;
- decoders reject compressed bytes or binary-body bytes left after a complete payload;
- inflation stops at 16 MiB, preventing small DEFLATE inputs from expanding without bound.

v7 added default-preserved subwaypoint detail:

- minimal exports now keep subwaypoint small/filled style bits along with the structural subwaypoint bit;
- subwaypoints with custom one-sixteenth placement write a packed 12-bit in-block offset so shared tiny markers import at the same precise center.

v6 kept v5's chat-safe base-91 text layer. Its improvements came from three binary-side changes:

- coordinate-only single-group exports can skip the normal string pool, group count, group name index, and waypoint body bytes;
- long ordered coordinate routes can use `RANGE_DELTA`, a new bit-level adaptive delta mode that gets closer to the entropy limit than varints or fixed packed deltas;
- final compression picks the shorter escaped text output from two DEFLATE strategies instead of assuming the default strategy is always best.

---

## 1. Format Pipeline

Sender and receiver run the same stages in opposite order.

```text
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

| Stage          | Job                                                                    |
| -------------- | ---------------------------------------------------------------------- |
| Binary body    | Squeeze varints and bit-packed fields. Route-level smarts live here.   |
| DEFLATE + dict | Byte-level compression with a preset dictionary.                       |
| base-91        | Turn bytes into chat-safe ASCII at 1 byte per character.               |
| chat escape    | Split Hypixel's `<3`/`o/` MVP++ emote triggers without changing bytes. |
| `WP:` prefix   | Lets the chat scanner find the string without parsing it.              |

---

## 2. Design Constraints

Minecraft refuses to send chat commands whose packet exceeds **256 UTF-8 bytes**. The 256-character textbox limit is separate and less important; the server-side byte cap is the problematic limit.

```text
  Total budget: 256 wire bytes per /command
  ┌──────────────────────────────────────────────────────────────┐
  │  /pc  │  WP:  │  ............  base-91 body  ............   │
  └───────┴───────┴──────────────────────────────────────────────┘
    3 B     3 B                    up to ~250 B
```

The codec optimizes for the ~250 bytes left after `/pc` and `WP:`.

Additional constraints:

- Chat validation strips control characters and collapses whitespace.
- Hypixel's advertising filter can disconnect senders when a message looks like a URL, especially when it contains `.` or `,`. The alphabet excludes periods and commas.
- Hypixel rewrites `<3` and `o/` to MVP++ emotes before recipients see chat. The encoder escapes those pairs after text packing.
- Codes exclude backticks so route strings do not break Markdown messages in Discord specifically.
- Copy-paste must round-trip byte-identically.
- Hover tooltips need a cheap partial decode of the optional label.
- Exports describe shareable routes, not player sessions. They do not include progress state or personal toggles.

---

## 3. Grammar

```text
payload    := "WP:" body
body       := *alphabet-char   ; each char is 1 UTF-8 byte
```

The body decodes to compressed bytes. Raw DEFLATE inflates those bytes into the binary body described in section 6.

---

## 4. Text Alphabet

### 4.1 Characters

v5 and newer use 91 printable ASCII characters. The alphabet includes every printable ASCII character except space, comma, `.`, and backtick.

```text
  ! " # $ % & ' ( ) * + - /
  0 1 2 3 4 5 6 7 8 9
  : ; < = > ? @
  A B C D E F G H I J K L M N O P Q R S T U V W X Y Z
  [ \ ] ^ _
  a b c d e f g h i j k l m n o p q r s t u v w x y z
  { | } ~
```

Each body character must be:

- one UTF-8 byte
- not whitespace
- not `§`
- not backtick
- printable ASCII.

The encoder escapes `~` (the escape character itself) and Hypixel's MVP++ emote triggers after text packing:

```text
~   -> ~~
<3  -> <~3
o/  -> o~/
```

`~` is part of the alphabet, so the scanner still sees one contiguous `WP:` body. The escape only adds bytes when a risky pair appears. Legacy v3 bodies skip this escape and decode through the compatibility path.

### 4.2 Packing

v5 and newer use the basE91 streaming scheme with a 91-symbol alphabet. The packer accumulates source bits and emits two output characters carrying either 13 or 14 bits, depending on whether the current 14-bit value fits inside `91²`.

```text
91² = 8281
2¹³ = 8192

Most pairs carry 13 bits.
89 low-value pairs carry 14 bits because they fit below 8281.
```

Legacy v4 uses the same scheme with a 92-symbol alphabet that included comma. Legacy v3 uses a 93-symbol alphabet that also included backtick. The scanner accepts all three alphabets so old chat exports still match.

There is no pad trailer. The final partial bit buffer emits as one or two characters. Decode reconstructs the original byte length.

Typical output length is data-dependent and close to:

```text
ceil(n * 1.22)
```

where `n` is the compressed byte count.

### 4.3 Why base-91

The budget is UTF-8 bytes, not visible glyphs.

| Alphabet | Bits/char | UTF-8 bytes/char | Bits per wire byte | Notes |
| --- | ---: | ---: | ---: | --- |
| base64 | 6.00 | 1 | 6.00 | Safe, lower density. |
| v2 base-85 | 6.41 | 1 | 6.41 | Fixed 4-byte/5-char groups plus one trailer. |
| v5-v9 base-91 stream | ~6.51 | 1 | ~6.51 | No text-packing trailer; variable 13/14-bit pairs. |
| v4 base-92 stream | ~6.52 | 1 | ~6.52 | Legacy; includes comma. |
| v3 base-93 stream | ~6.53 | 1 | ~6.53 | Legacy; includes backtick. |
| CJK base-16384 | 14.00 | 3 | 4.67 | Short visually, expensive on the wire. |

CJK carries more bits per glyph, but each glyph costs three UTF-8 bytes. That makes it worse under the server byte cap. v1 optimized for textbox length. v2 switched to base-85 for byte efficiency. v3 removed the base-85 pad trailer. v4 spent one symbol to remove backticks. v5 spent one more symbol to remove commas and added extended coordinate modes. v6 kept the same base-91 text layer, then improved the binary body with a coordinate-only shortcut and a new range-delta coordinate mode. v7 added exact tiny-subwaypoint sharing. v8 added CRC-32 integrity. v9 changes the body/model and dictionary without changing the text alphabet.

### 4.4 Decode Safety

`decode()` validates:

- every character exists in the active alphabet;
- every two-character value fits the streaming base math.

A malformed character fails instead of producing silent corruption.

For v9, alphabet validity is only the first layer: the decoder also requires
canonical Hypixel escaping and canonical base-91 output, as detailed in section
7.3. Legacy probes retain their historical acceptance behavior.

---

## 5. Compression

### 5.1 Framing

The codec uses raw DEFLATE:

```java
Deflater(..., nowrap=true)
```

It omits the zlib header and Adler-32 trailer. That saves six wrapper bytes per share and removes the zlib `DICTID` field. The decoder binds the preset dictionary manually by calling `Inflater.setDictionary(...)` before inflating.

v8 and v9 pass this frame to raw DEFLATE:

```text
binary body || crc32(binary body, 4 bytes, big-endian)
```

The receiver inflates at most 16 MiB, requires the DEFLATE stream to consume every compressed byte, verifies the CRC-32, removes it, parses the binary body, and requires that parser to consume every remaining byte. v1-v7 payloads remain decode-compatible but have no checksum and are therefore legacy unchecked payloads; they still receive the inflate bound and exact-consumption checks.

### 5.2 Preset Dictionary

v1-v8 set the immutable 363-byte `CodecDictionary.BYTES` as DEFLATE's preset dictionary. v9 sets the versioned 32,768-byte `V9CodecDictionary.BYTES` resource instead. The dictionary acts as virtual LZ77 history, so early stream bytes can back-reference common route structure and vocabulary.

```text
  legacy virtual history (~360 bytes, never transmitted)
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

Dictionary contents:

- canonical Hypixel SkyBlock zone IDs, ordered longest-and-most-common first;
- common waypoint name fragments: `Terminal`, `Lever`, `Puzzle`, `Device`, `Boss`, `Spawn`, `Start`, `End`, `Checkpoint`, `T1..T8`.

That vocabulary list describes the 363-byte legacy dictionary. v9 instead ships
the deterministic 32 KiB train-only body dictionary at
`src/main/resources/assets/waypointer/codec/v9-deflate-dictionary.bin`.
`V9CodecDictionary` verifies
both its length and pinned SHA-256 before use. It is never used while probing
legacy payloads.

### 5.3 DEFLATE Strategy Selection

Since v6, the encoder scores the final escaped text length after compression. When writing the actual payload, the encoder tries the normal DEFLATE strategy and the filtered strategy, then keeps whichever produces the shorter escaped base-91 string.

This matters because the best raw compressed byte count is not always the best chat string. The base-91 layer and the Hypixel emote escape can make two equal-byte compressed streams differ by a character, and a one-character win is real under the `/pc` byte cap.

### 5.4 Dictionary Versioning

Dictionary bytes are part of the wire contract. Raw DEFLATE does not advertise a dictionary ID, so v1-v7 payloads are not protected against a dictionary mismatch. v8/v9 CRC-32 detects wrong uncompressed output. `CodecDictionary.BYTES` must remain byte-identical for v1-v8. `V9CodecDictionary.BYTES` is independently versioned and hash-checked at load time.

Do not edit either active dictionary without assigning a new wire version and retaining the old dictionary for its existing decode path.

---

## 6. Binary Body

Most scalar counts, indexes, and general-body coordinates use varints or zigzag varints. The exceptions are deliberate:

- v9 general kind-0 and anonymous kind-5 radii are exact, big-endian IEEE-754 binary64 values;
- fixed-width coordinate bitstreams in compact coordinate modes;
- two-byte packed width preambles in `FIT_COMPACT`, `DELTA_FIT_AXIS_SEPARATED`, and `RANGE_DELTA`;
- the varint-prefixed range-coded payload in `RANGE_DELTA`;
- three raw RGB bytes in waypoint color records;
- raw UTF-8 byte strings after a varint length.

### 6.1 Top-Level Layout

v9 interprets the high header nibble as a content envelope. Kinds 0, 1, 2,
and 5 are implemented route bodies. Kind 3 is reserved for config and kind 4
for dungeon artifacts, but the current route decoder rejects both until those
layouts exist. Kinds 6 and 7 are unassigned and rejected.

General kind-0 bodies keep the v5-v8 regular wrapper:

```text
  ┌──────┬──────────┬─────────────┬───────────────┬─────────────┐
  │ hdr  │ [label]  │ string pool │ groupCount    │ groups...   │
  │ 1 B  │ optional │ varint+utf8 │ varint        │ one per gid │
  └──────┴──────────┴─────────────┴───────────────┴─────────────┘
```

Compact kind 1 is a single full route with an implicit default group
configuration:

```text
header [label] compact-zone group-name waypointCount
name-stream color-stream compact-coordinate-stream
```

It is eligible only in `AUTO` mode for one group when names, colors, and group
metadata are requested; the group is MANUAL, SEQUENCE, skip-ahead enabled, has
the default radius and default static/gradient colors; and every waypoint has no
flags, custom radius, precise offset, temporary mode, or expiry. Name streams
use one of six modes: empty, constant, numeric arithmetic, numeric delta,
prefix arithmetic, or palette. Color streams use constant, palette, or raw RGB.

Compact kind 2 is the corresponding explicit coordinate-only route:

```text
header [label] compact-zone waypointCount compact-coordinate-stream
```

It is eligible only when names, colors, radii, and waypoint flags are all
excluded. If group metadata is requested, the source group must already have
the compact defaults listed above. Every source waypoint must also have zero
flags and custom radius, with no precise position, temporary mode, or expiry.
Both compact kinds encode a known zone as a one-byte token; token `0` is
followed by an inline compact UTF-8 string. Tokens `1..7` mean `unknown`,
`mining_3`, `foraging_2`, `crystal_hollows`, `combat_3`, `mineshaft`, and
`farming_1`, respectively.

For a nonempty route, the compact coordinate stream writes the first point as
three signed varlongs, a big-endian 16-bit field containing three 5-bit delta
widths, then a canonical unsigned-varint range-payload length and the
trained-prior range payload. Bit 15 of the width field is reserved and must be
zero. Each per-axis zigzag delta
must fit 31 bits.

Compact-layout limits are:

- exactly one group and at most 20,000 waypoints;
- at most 256 UTF-8 bytes for each compact zone, group name, waypoint name, or palette name;
- block coordinates in `[-134217728, 134217727]`, the range representable by the waypoint model's one-sixteenth-block integer coordinates;
- at most 2 MiB for the compact coordinate payload, additionally bounded by the decoded count and width-derived bit budget.

The encoder first produces a baseline body: normally kind 0, or kind 5 when the
anonymous coordinate-only shape is eligible. In `AUTO` mode with one group it
then evaluates eligible kind 2 and kind 1 candidates. Every candidate is CRC
framed, compressed with the v9 dictionary and both DEFLATE strategies, base-91
encoded, and Hypixel-escaped before its text length is compared. Replacement
uses strict `<`, so ties keep the earlier candidate: baseline before kind 2,
and kind 2 before kind 1.

v6-v8 can also use an anonymous single-group coordinate-only body. v9 carries
that same metadata-capable fallback as content kind 5. It still begins with the
same header and optional label, but skips the string pool and group count
because there is exactly one unnamed group:

```text
  hdr [label] anonymous-group
```

The anonymous shape is only for exports that contain exactly one group of coordinates in order, with no requested waypoint body data to preserve. If a requested field or always-preserved structural detail such as subwaypoint style/precision would be lost, the encoder falls back to the regular body.

### 6.2 Header Byte

v9 uses the high nibble as a typed envelope:

```text
  bit   7   6   5   4   3   2   1   0
      │ L │ content kind│  version=9  │
```

Bit 7 means an optional label immediately follows. Bits 4..6 select kind 0
general route, 1 compact full route, 2 compact coordinates, 3 reserved config,
4 reserved dungeon, or 5 coordinate-with-group-metadata. The current route
decoder accepts only 0, 1, 2, and 5 and rejects every other kind before parsing
kind-specific data.

v1-v8 retain the legacy high-nibble interpretation below:

```text
  bit   7   6   5   4   3   2   1   0
      ┌───┬───┬───┬───┬───────────────┐
      │ r │ A │ L │ N │    version    │
      └───┴───┴───┴───┴───────────────┘
        │   │   │   │        │
        │   │   │   │        └── 4 bits; must be non-zero; legacy version
        │   │   │   └─────────── HEADER_FLAG_NAMES, informational
        │   │   └─────────────── HEADER_FLAG_LABEL, label byte-string follows
        │   └─────────────────── HEADER_FLAG_ANONYMOUS_SINGLE_GROUP, v6+ coordinate-only body
        └─────────────────────── reserved; encoder writes 0, decoder ignores
```

Version 0 is invalid, so a corrupted leading byte cannot masquerade as an old schema. The diagram's legacy flags are not reinterpreted while decoding v1-v8.

Legacy `HEADER_FLAG_NAMES` is informational. Each waypoint still carries its own `WP_FLAG_HAS_NAME`. The duplicate flag lets debug tools show sender intent without scanning every waypoint.

Bit 6 is `HEADER_FLAG_ANONYMOUS_SINGLE_GROUP` in v6-v8. When set, the body uses the anonymous single-group coordinate-only layout in section 6.7 instead of the normal string-pool/group-count wrapper. v9 selects that fallback with content kind 5 instead.

Bit 7 is reserved only in v1-v8. In v9 it is the label-presence bit.

### 6.3 Optional Label

The label is present when legacy `HEADER_FLAG_LABEL` or v9
`V9_HEADER_FLAG_LABEL` is set. It appears immediately after the header so
`peekLabel()` can stop before the content-specific body.

```text
label := varint labelLen
         byte[labelLen]   ; strict UTF-8, sanitized as tooltip text
```

Constraints:

- maximum wire length: 256 bytes (`MAX_LABEL_BYTES`);
- maximum sanitized length: 64 UTF-16 code units (`Options.MAX_LABEL_CHARS`), without splitting a surrogate pair;
- sanitization removes malformed surrogate halves, `§`, C0 controls (`< 0x20`), and `0x7F`, then strips leading and trailing whitespace;
- the encoder sanitizes before strict UTF-8 encoding; full decode and bounded label preview read strict UTF-8 before sanitizing again.

The character sanitizer may truncate at its safe 64-code-unit boundary, but
the wire writer never slices UTF-8 bytes: if its already-sanitized value somehow
exceeds 256 encoded bytes, it throws instead of emitting a partial character.

Sender labels are deliberately different from route display names. v9 validates
every source group name (even if a coordinate-only kind later omits it) and
every waypoint name selected for export. A name written to the wire is not
truncated or rewritten: it must already be valid Unicode, encode to at most 256
UTF-8 bytes, and contain no `§`, C0 control, or `0x7F` character. Encode and
decode reject an unsafe on-wire display name so a valid one round-trips exactly.
Legacy group and waypoint names continue through the older sanitizing
compatibility path.

### 6.4 String Pool

The string pool is a flat strict-UTF-8 table. Group names, custom zones, and repeated waypoint names reference it by index. It exists only in regular kind-0 bodies; compact kinds 1/2 and anonymous kind 5 omit it.

```text
string-pool := varint count
               ( varint byteLen; byte[byteLen] ){count}
```

Index `0` is always the empty string. Records use `nameIdx = 0` for unnamed values, with no null sentinel.

Wire safety caps:

- maximum pool entries: 65,536;
- maximum string size: 1 MiB.

The 1 MiB limit applies to general wire strings such as custom zone IDs. v9
group and waypoint display names have the separate 256-byte safety limit above;
compact strings also have a 256-byte layout limit.

The v9 encoder and every route-body decoder share the importer allocation
limits: at most 256 groups, at most 20,000 waypoints in one group, and at most
50,000 waypoints across the payload. Kind 1, kind 2, and kind 5 contain exactly
one group, so their total is necessarily also bounded by 20,000.

### 6.5 Group Record

```text
group := varint nameIdx         ; pool index, 0 = unnamed
         varint zoneRef         ; known-zone ref or pool index
         u8     groupFlags      ; see below
         [ persistent-meta ]    ; iff v9 kind 0 and GROUP_FLAG_V9_PERSISTENT_META
         [ f64be defaultRadius ]; iff GROUP_FLAG_CUSTOM_RADIUS in v9
         varint waypointCount
         coord-block            ; section 6.8
         waypoint-body{waypointCount} ; omitted iff GROUP_FLAG_BODYLESS_WAYPOINTS
```

`groupFlags`:

```text
  bit   7   6   5   4   3   2   1   0
      ┌───┬───┬───────┬───┬───┬───┬───┐
      │ M │ C │ coord │ R │ S │ G │ B │
      └───┴───┴───────┴───┴───┴───┴───┘
        │   │     │     │   │   │   │
        │   │     │     │   │   │   └── GROUP_FLAG_BODYLESS_WAYPOINTS
        │   │     │     │   │   └────── GROUP_FLAG_GRAD_AUTO
        │   │     │     │   └────────── GROUP_FLAG_LOAD_SEQUENCE
        │   │     │     └────────────── GROUP_FLAG_CUSTOM_RADIUS
        │   │     └──────────────────── coord-mode low bits, bits 4..5
        │   └────────────────────────── coord-mode high bit, bit 6, v5+
        └────────────────────────────── v9 kind-0 persistent metadata extension
```

`GROUP_FLAG_GRAD_AUTO`, `GROUP_FLAG_LOAD_SEQUENCE`, and `GROUP_FLAG_CUSTOM_RADIUS` are only faithful to the source group when group metadata is included. See section 6.6.

Bit 7 has a version-and-kind-specific meaning. In v9 general kind 0 it means
the persistent metadata extension follows. It is reserved in kind 5, and the
kind-5 decoder rejects a payload that sets it. Legacy versions do not read this
extension.

The v9 kind-0 extension is:

```text
persistent-meta := u8 metadataFlags
                   byte[3] staticRgb
                   byte[3] gradientStartRgb
                   byte[3] gradientEndRgb

metadataFlags bits 0..1 := 0 MANUAL, 1 AUTO, 2 STATIC; 3 invalid
metadataFlags bit 2     := skip-ahead enabled
metadataFlags bits 3..7 := reserved, must be zero
```

The legacy `GROUP_FLAG_GRAD_AUTO` bit must agree with the extension's AUTO
mode. A disagreement, invalid gradient mode, or nonzero reserved metadata bit
is rejected.

`zoneRef` is a tagged varint:

```text
known zone:  (zoneDictionaryIndex << 1) | 1
custom zone: poolIndex << 1
```

The built-in zone dictionary starts from Skyblocker's `Location` enum, which provides Hypixel location IDs and friendly names. Waypointer adds canonical aliases plus dungeon and mineshaft refinements. Unknown and user-created zone IDs round-trip through the string pool.

Dictionary indexes are immutable across the supported decode versions. Retired IDs remain in their old slots so later entries cannot shift. A retired ID can decode to a newer canonical route zone; for example, `great_glacite_lake`, `glacite_tunnels`, and `dwarven_base_camp` now canonicalize to `dwarven_mines`.

Anonymous bodies in v6-v8 do not have a string pool, so their zone reference keeps odd dictionary refs for known zones and uses `0` followed by an inline UTF-8 zone ID for custom zones. V9 content kind 5 uses the same rule. Other nonzero even zone refs are invalid in the anonymous layout.

For v9 kind 0, `GROUP_FLAG_CUSTOM_RADIUS` is followed by the exact Java
`double` bit pattern as eight big-endian bytes. The same encoding is used for
kind-5 default radii and kind-0 per-waypoint custom radii. The decoder rejects
non-finite or model-out-of-range values, and a waypoint custom radius may not
be zero. v1-v8 keep the legacy rounded `radius_x10` varint.

### 6.6 Group Metadata and Omitted Session State

In regular kind-0 bodies, group name and zone are always part of the group record. `includeNames` only controls waypoint names; it does not remove group names. In anonymous coordinate-only bodies, the single group name is intentionally blank and the zone is still written.

`Options.includeGroupMeta` controls the group metadata bits:

- when true, the encoder preserves load mode and an exact nondefault group radius; it preserves AUTO gradient when colors are included;
- in v9 kind 0, it also emits the bit-7 extension when needed to preserve disabled skip-ahead, STATIC mode, or nondefault static/gradient colors;
- when false, the encoder writes the default radius, forces SEQUENCE, enables skip-ahead, uses the default group palette, and projects the group to MANUAL even when explicit waypoint colors are included.

AUTO gradient state is tied to color export. When colors are stripped, the encoder does not preserve AUTO gradient. Otherwise a colorless import could regenerate colors and appear to have carried color data.

Kind 5 has no bit-7 extension. Its eligibility check prevents use when disabled
skip-ahead would be lost, and its coordinate-only record can preserve only the
projected load mode and exact default radius. Kinds 1 and 2 instead imply their
documented default group configuration.

Exports describe shared route data, not sender session state. The wire format does not store:

- group ID;
- group `enabled` state; imported groups land enabled;
- group `currentIndex`; imported groups start at index 0;
- temp-group state;
- static-route reached bits;
- proximity suppression/focused index state;
- active subwaypoint parent state;
- waypoint `tempMode` or `expiresAtMillis`.

In v9 kind 0, skip-ahead, gradient mode, static color, and both gradient colors
are persistent route metadata, not session state, and round-trip when the
corresponding export options include them.

The old `enabled` bit slot now means `GROUP_FLAG_BODYLESS_WAYPOINTS`. When set, the coordinate block has no per-waypoint body bytes after it. Every waypoint uses the default body, `wpFlags = 0`. This saves one raw zero byte per waypoint before DEFLATE on geometry-only exports.

### 6.7 Anonymous Single-Group Body

When header bit 6 is set on a v6-v8 payload—or v9 content kind 5 is selected—the body is:

```text
anonymous-body := header
                  [ label ]
                  anonymous-group

anonymous-group := anonymous-zone-ref
                   u8 groupFlags
                   [ f64be defaultRadius ]; iff GROUP_FLAG_CUSTOM_RADIUS in v9
                   varint waypointCount
                   coord-block
```

There is no string pool, no group count, no group name index, and no waypoint body section. The group decodes with an empty name. The decoder requires `GROUP_FLAG_BODYLESS_WAYPOINTS` because every waypoint is reconstructed from coordinates alone.

For v9 kind 5, `groupFlags` bit 7 is reserved and must be zero. Setting the
kind-0 persistent-metadata extension bit is a hard decode error rather than an
instruction to consume extension bytes.

Eligibility is intentionally strict:

- exactly one group;
- waypoint names disabled;
- waypoint colors disabled;
- waypoint radii disabled;
- waypoint flags disabled;
- no surviving structural waypoint flags, such as subwaypoint state.

Labels and group metadata can still be kept. The optional label lives next to the header, and the anonymous group record can still carry zone, load mode, and custom default radius.

The purpose is to avoid paying wrapper bytes for the most common compressed export target: one ordered list of coordinates. If the route needs any richer waypoint body data, the encoder writes the regular body instead.

### 6.8 Coordinate Block

Each group chooses one coordinate mode during encode.

Every v9 route kind requires decoded block coordinates in
`[-134217728, 134217727]`, the range whose one-sixteenth-block representation
fits the waypoint model's signed 32-bit precise fields. Legacy decoders retain
their historical integer-coordinate behavior.

In v5+, the chosen mode uses `groupFlags[4..5]` for the low two bits and `groupFlags[6]` for bit 2. Legacy v2-v4 only used `groupFlags[4..5]`, so they only support modes `0..3`; the restored v1 compatibility path only accepts modes `0..2`. v6 adds mode `6`; older v5 payloads never contain it, but current decoders understand these modes in v5-v9 general/fallback bodies.

#### Mode 0: `VECTOR` delta

First waypoint absolute. Each following waypoint stores a delta from the previous waypoint.

```text
wp[0]:  (x,  y,  z)
wp[1]:  (+dx, +dy, +dz)   from wp[0]
wp[2]:  (+dx, +dy, +dz)   from wp[1]
...
```

Best for sequential routes, such as mining routes, including routes with high and low world-coordinate extremes.

Typical cost: one to two bytes per coordinate.

#### Mode 1: `ABSOLUTE_VARINT`

Every waypoint stores its own zigzag varint `(x, y, z)` tuple.

Best for routes that jump between low-magnitude points where deltas would be large.

#### Mode 2: `FIXED_COMPACT`

Every waypoint occupies exactly 33 bits.

```text
per waypoint:  [ x : 12 bits zigzag ][ y+64 : 9 bits ][ z : 12 bits zigzag ]
               ◄──────────────────── 33 bits ──────────────────────────►
```

After the last waypoint, the writer pads with up to seven zero bits to reach the next byte boundary. Waypoint bodies start on the next byte.

Eligibility:

```text
x, z ∈ [-2048, +2047]
y    ∈ [  -64,  +447]
```

This covers most SkyBlock interiors.

Best for moderate-magnitude groups without delta locality.

#### Mode 3: `FIT_COMPACT`

Fits per-axis bit widths to the group's actual coordinate range.

```text
preamble:   xOrigin (zigzag varint)
            yOrigin (zigzag varint)
            zOrigin (zigzag varint)
            u16:  [reserved=0:1 | xBits:5 | yBits:5 | zBits:5]

per waypoint:
    ◄─ xBits ─►◄─ yBits ─►◄─ zBits ─►
    │ x-xOrig │ y-yOrig │ z-zOrig │        all unsigned
```

Origins are the per-axis minima, so all deltas are non-negative and the bitstream does not need zigzag values. An axis with width `0` uses zero bits per waypoint because every coordinate equals the origin.

Best for tight clusters. Example: a dungeon group with `x∈[66..130]` (7 bits), `y∈[128..145]` (5 bits), and `z∈[135..190]` (6 bits) stores each waypoint in 18 bits instead of `FIXED_COMPACT`'s 33, with about five bytes of preamble.

#### Mode 4: `VECTOR_AXIS_SEPARATED`

Stores the same values as `VECTOR`, but transposes the deltas by axis.

```text
first absolute x, y, z
all dx values
all dy values
all dz values
```

Raw size is close to `VECTOR`, but the separated axis streams can give DEFLATE cleaner repeated patterns on long mining or foraging routes.

#### Mode 5: `DELTA_FIT_AXIS_SEPARATED`

Stores the first waypoint as absolute zigzag varints, then bit-packs fitted per-axis delta streams.

```text
first absolute x, y, z
u16: [reserved=0:1 | dxBits:5 | dyBits:5 | dzBits:5]
bitpacked all dx, then all dy, then all dz
```

An axis width of `0` means all deltas on that axis are zero. AUTO only considers this mode when every packed zigzag delta fits in 31 bits.

#### Mode 6: `RANGE_DELTA`

Introduced in v6 and still valid in v9 general/fallback bodies. Stores the first waypoint as absolute zigzag varints, then range-codes fixed-width zigzag deltas by axis.

```text
first absolute x, y, z
u16: [reserved=0:1 | dxBits:5 | dyBits:5 | dzBits:5]
varint rangePayloadLen
byte[rangePayloadLen] adaptive range-coded delta bits
```

The range payload is axis-major: all `dx` bits, then all `dy` bits, then all `dz` bits. Within each fixed-width delta, bits are written most-significant to least-significant. The adaptive model has a separate context for each axis and bit position, starting from a neutral probability table for each group.

This is best for long ordered coordinate routes where the next movement is predictable enough that a bit-level model beats both plain varints and DEFLATE over packed deltas. It has more setup overhead than the older modes, so AUTO only picks it when the final escaped text score is actually smaller.

#### AUTO Mode Selection

The encoder tries every eligible coordinate mode. For each candidate, it scores the body prefix plus the candidate group through the same DEFLATE, base-91, and Hypixel-escape path used by real exports, then picks the shortest scored text. Anonymous bodies in v6-v8, and v9 content kind 5, use a separate scorer because their wrapper bytes differ from regular groups.

The score is a per-group heuristic, not a full recompression of the final export with all later groups included. It still beats raw-byte comparison because DEFLATE and the text layer can rank candidates differently from their uncompressed size. A repetitive `VECTOR` delta stream can compress to almost nothing, while a dense `FIT_COMPACT` bitstream may compress poorly.

Worst case: AUTO ties the best forced mode for that group. Best case: it saves characters. The decoder pays no search cost; it reads the mode named in the group header.

Every coordinate mode has this layout:

```text
[ coord-stream | waypoint-bodies ]
```

The decoder reads `waypointCount` coordinates in the selected mode, then reads `waypointCount` waypoint bodies unless the group is bodyless.

### 6.9 Waypoint Body

```text
waypoint-body := u8 wpFlags
                 [ name-ref ]           ; iff WP_FLAG_HAS_NAME
                 [ byte[3] rgb ]        ; iff WP_FLAG_HAS_COLOR, MSB-first R,G,B
                 [ f64be customRadius ] ; iff WP_FLAG_HAS_RADIUS in v9
                 [ uint32-varint flags ]; iff WP_FLAG_EXTENDED
                 [ varint precise ]     ; iff WP_FLAG_HAS_PRECISE, v7+ packed x/y/z offsets

name-ref      := varint nameIdx         ; pooled name, iff WP_FLAG_NAME_INLINE unset
              | varint byteLen; bytes   ; inline UTF-8, iff WP_FLAG_NAME_INLINE set
```

`wpFlags`:

```text
  bit   7   6   5   4   3   2   1   0
      ┌───┬───┬───┬───┬───┬───┬───┬───┐
      │ r │ r │ P │ I │ X │ R │ C │ N │
      └───┴───┴───┴───┴───┴───┴───┴───┘
                │   │   │   │   │   │
                │   │   │   │   │   └── WP_FLAG_HAS_NAME
                │   │   │   │   └────── WP_FLAG_HAS_COLOR, else DEFAULT_COLOR
                │   │   │   └────────── WP_FLAG_HAS_RADIUS, else inherit group
                │   │   └────────────── WP_FLAG_EXTENDED, else no user flags
                │   └────────────────── WP_FLAG_NAME_INLINE; name-ref is inline UTF-8
                └────────────────────── WP_FLAG_HAS_PRECISE; v7+ packed sixteenth offsets
```

Unique waypoint names are inlined instead of added to the string pool. Pooling a one-use name costs the string bytes plus a separate index. Repeated names still pool, so labels such as `Terminal` or `Lever` store once and reference by index.

The v9 radius is an exact big-endian IEEE-754 binary64 value. v1-v8 use the
legacy rounded `radius_x10` varint instead. The v9 flags varint carries the
entire unsigned 32-bit bit pattern; decoding returns the same Java `int` bits,
including bit 31, rather than truncating to a byte.

#### Subwaypoints

Subwaypoint status is stored in the extended waypoint flags field. It is not a separate waypoint type and it does not store a parent index.

```text
Waypoint.FLAG_SUBWAYPOINT = 1 << 4   ; 0x10
Waypoint.FLAG_SMALL_SUBWAYPOINT = 1 << 5
Waypoint.FLAG_FILLED_SUBWAYPOINT = 1 << 6
```

A waypoint with only subwaypoint metadata writes this body:

```text
08 10
│  │
│  └─ varint flags = 0x10 = FLAG_SUBWAYPOINT
└──── wpFlags = WP_FLAG_EXTENDED
```

Subwaypoint structure and subwaypoint-specific style survive minimal exports. When `includeWaypointFlags` is false, the encoder keeps `FLAG_SUBWAYPOINT` and, when that structural bit is present, `FLAG_SMALL_SUBWAYPOINT` and `FLAG_FILLED_SUBWAYPOINT`. It strips other optional flags except `FLAG_LOCKED_COLOR`, which is retained when waypoint colors are exported.

v7 also stores precise small-waypoint placement by default for subwaypoints. The coordinate stream still stores whole-block `x/y/z`; the waypoint body can add one packed varint:

```text
precise = (xOffset << 8) | (yOffset << 4) | zOffset
offsets are 0..15, in sixteenths of a block inside the decoded block coordinate
```

A block-centered waypoint has offset `8, 8, 8` and usually omits this field. A custom tiny subwaypoint at `x + 3/16`, `y + 12/16`, `z + 15/16` writes `0x3CF`.

The parent relationship is positional. A subwaypoint is a one-level child of the nearest previous non-subwaypoint in the same group. The first waypoint cannot remain a subwaypoint after group normalization.

#### Opt-Out Semantics

Loss is opt-in for Waypointer exports. `encode(groups)`,
`WaypointCodec.Options.builder()`, `FULL_FIDELITY`/`LOSSLESS`, public
`ExportOptions.defaults()`/`builder()`, and a fresh `WaypointerConfig` all
enable the five supported persistent field families. The normal CLI and GUI
paths inherit those full-fidelity config defaults. A field is dropped only
after an explicit toggle or lossy preset selection.

`WITH_NAMES` and `NO_NAMES` are explicit chat-size presets, not defaults.
`WITH_NAMES` keeps waypoint names and group metadata but disables colors,
radii, and waypoint flags. `NO_NAMES` additionally disables waypoint names;
both still keep group metadata.

Sender `Options` can disable field families:

- `includeNames`: waypoint names only; group names are still written;
- `includeColors`: waypoint colors plus group gradient mode and static/gradient colors when group metadata is included;
- `includeRadii`: per-waypoint radius overrides;
- `includeWaypointFlags`: optional visual/user waypoint flags; structural subwaypoint bits still survive, and `FLAG_LOCKED_COLOR` survives when colors are included;
- `includeGroupMeta`: load mode, exact default radius, skip-ahead, gradient mode, and the persistent static/gradient colors supported by kind 0.

For waypoint field families, disabling an option writes neither the corresponding flag bit nor the value, except for subwaypoint structure/style flags, v7+ subwaypoint precise offsets, and `FLAG_LOCKED_COLOR` when colors are included. That color-lock exception prevents AUTO gradient decode from replacing an explicitly transmitted RGB value. The decoder sees other unset fields and substitutes defaults. Disabled group metadata is instead projected to SEQUENCE, enabled skip-ahead, the default radius/palette, and MANUAL gradient mode.

Colors are omitted when they equal `DEFAULT_COLOR`; the recipient substitutes
the same constant. With `includeWaypointFlags`, extended flags store all 32
bits of `Waypoint.flags`. Without it, the encoder retains only structural flags
and, for subwaypoints, their small/filled style bits; it also retains
`FLAG_LOCKED_COLOR` whenever waypoint colors are exported.

---

## 7. Varints and Zigzag

### 7.1 Varint

Varints use standard 7-bit little-endian chunks. Each byte's low seven bits carry data. The high bit means another byte follows. General-body integer varints are capped at five bytes and `0x7fffffff`; a continuation followed by a zero terminal group is rejected as non-canonical. Waypoint flags use the same chunk order but a dedicated decoder that permits the complete `0x00000000..0xffffffff` bit pattern in at most five bytes.

Compact layouts use canonical unsigned int varints for nonnegative counts and
lengths, plus canonical unsigned varlongs for zigzagged signed coordinate/name
values. A compact varlong is at most ten bytes, with only one payload bit in
the tenth byte. Every reader rejects overflow, excess continuation bytes, and
an overlong zero terminal group.

```text
value: 300          binary: 0000 0001 0010 1100

chunk 1: low 7 bits   -> 0101100
chunk 2: next 7 bits  -> 0000010

bytes on wire:
  ┌───────────┐┌───────────┐
  │ 1 0101100 ││ 0 0000010 │
  └───────────┘└───────────┘
   more=1       more=0, last
```

### 7.2 Zigzag

Signed integers are zigzagged before varint encoding so small negatives stay small.

```text
zigzag(v)    = (v << 1) ^ (v >> 31)      ; arithmetic shift
unZigzag(n)  = (n >>> 1) ^ -(n & 1)

 0 -> 0
-1 -> 1
 1 -> 2
-2 -> 3
 2 -> 4
...
```

### 7.3 Canonical v9 Encoding and Padding

v9 is stricter than the legacy text probes. After removing the Hypixel escape,
the decoder re-applies that escape and requires byte-for-byte equality. It also
decodes and re-encodes the base-91 stream and requires equality, preventing an
alternate tail representation of the same compressed bytes. Inflation must
consume the complete raw-DEFLATE input; CRC-32 must match; and body parsing must
consume the complete uncompressed body.

All production v9 writers emit shortest-form varints. The decoders enforce
that minimal representation as described above.

Bit-packed sections end on byte boundaries. General-body coordinate writers
zero-fill up to seven unused bits; v9 rejects any nonzero coordinate padding,
while legacy decoders retain their historical discard-and-align behavior.
Compact palette indexes are LSB-first at the minimum width and likewise reject
any nonzero unused padding bit. Every v9 16-bit coordinate-width word rejects
reserved bit 15; legacy general-body readers remain permissive about that bit.
Compact kinds additionally require all three widths to equal the minimum widths
recomputed from the decoded deltas.
The compact range payload is also re-encoded after decode and must match
exactly, which makes its termination canonical.

---

## 8. Operations

### 8.1 Encode

```text
groups + options
     │
     ▼
buildStringPool()
     │
     ▼
write binary body   ──┐
     │                │ for each group, test every eligible coord mode
     ▼                ├─ through trial DEFLATE + base-91 and keep shortest text
DEFLATE + preset dict ┘
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

The first binary pass produces the baseline kind-0/kind-5 candidate and runs
the per-group coordinate-mode contest described in section 6.8. For a single
group in `AUTO`, eligible kind-2 and kind-1 candidates then compete against the
complete baseline as described in section 6.1. Only the selected body's
compressed bytes continue to the final text output.

### 8.2 Decode

```text
"WP:..."
     │
     ▼
verify "WP:" prefix
     │
     ▼
remove emote escape for versions that use it
     │
     ▼
text decode for probed version
     │
     ▼
inflate + preset dict
     │
     ▼
read header byte -> require the version currently being probed
     │
     ▼
if that version's label bit is set: read strict UTF-8; full decode sanitizes it
     │
     ▼
for v9, dispatch content kind 1, 2, 5, or general kind 0
(for v6-v8, header bit 6 selects the anonymous group)
     │
     ▼
for each general-body group:
  read group header
  read coord stream for coord mode
  read waypoint bodies unless bodyless
     │
     ▼
return groups; decodeFull also returns label
```

Unknown versions fail with:

```text
unsupported wire version N
```

Current decoders accept:

- v9: current writer, typed content envelope, compact trained route/coordinate layouts, v9 dictionary, and CRC-32;
- v8: legacy v7 body/text behavior plus a CRC-32 integrity trailer;
- v7: v6 text/anonymous/range-delta behavior plus default-preserved subwaypoint style flags and packed sixteenth-block precise offsets;
- v6: base-91 text layer, anonymous single-group coordinate-only bodies, `RANGE_DELTA`, and best-of-DEFLATE strategy selection;
- v5: `AsciiStreamCodec` base-91 text layer, Hypixel emote escape, extended coordinate modes;
- v4: `AsciiStreamCodec` base-92 text layer, Hypixel emote escape, v4 header;
- v3: `AsciiStreamCodec` base-93 text layer, v3 body with `zoneRef`, inline names, and bodyless groups;
- v2: `AsciiPackCodec` base-85 text layer, v2 body with zone IDs as string-pool indexes, waypoint names always as pool refs, and ignored bit 0 in group flags;
- v1: `CjkBase16384` text layer with the old pooled-zone body shape.

The encoder only writes v9. v8 is a checksummed decode-only compatibility path;
v7 and older are checksum-free decode-only paths. The separate v5
exporter/comparison UI was removed after v6 won the earlier export-size tests.

### 8.3 `peekLabel`

`peekLabel(text)` powers chat-hover tooltips.

```text
peekLabel(text):
  copy at most the first 1024 payload characters after WP:
  try v9 with the v9 dictionary, then v8 through v1 with legacy dictionaries
  inflate at most header + maximum label bytes; completion is not required
  read header byte
  if version mismatch or that version's label bit is unset -> Optional.empty()
  read strict UTF-8, sanitize the label, and return it
  malformed input -> Optional.empty()
```

The label appears before content-specific data, so this path never walks or allocates from the full payload. This is intentionally an unchecked preview: only `decodeFull()` verifies the v8/v9 CRC and exact stream consumption before import.

Both `decodeFull()` and the bounded `peekLabel()` preview sanitize labels after
strict UTF-8 decode. Preview remains unauthenticated because it does not verify
the checksum or require complete inflation; import always performs the full
version-specific integrity and structural decode.

### 8.4 Debug Decode

`debugDecode` returns a `DecodeDebug` record containing:

- header bits;
- label;
- string pool contents;
- per-group flag byte;
- coordinate mode;
- coordinate-block byte count;
- body-block byte count;
- per-waypoint flag bytes and decoded values.

Kinds 1 and 2 have neither a general-body group-flags byte nor a general
coordinate-mode ordinal, and they interleave modeled metadata and coordinates
in one compact payload. Their debug record therefore uses `-1` sentinels for
`groupFlagsByte`, `coordModeOrdinal`, and `coordBlockBytes`, names the modeled
coordinate path `COMPACT_V9_RANGE`, and reports the entire kind-specific
payload in `bodyBlockBytes`. The debug UI renders those nonexistent fields as
`N/A`. General kind 0 and anonymous kind 5 continue to report ordinary group
flags, coordinate modes, and nonnegative coordinate-block byte counts.

This supports `/wp debug`; it is not a hot path.

---

## 9. Versioning

| Surface | Location | Cost of changing |
| --- | --- | --- |
| `MAGIC` (`"WP:"`) | `WaypointCodec` constant | Breaks the chat scanner for all payloads. |
| `WIRE_VERSION` | Low nibble of the header byte | Breaks decode for older builds. Scanner still fires. |
| Legacy dictionary bytes | `CodecDictionary.BYTES` | Breaks v1-v8 inflation. Immutable; retain for legacy decode. |
| v9 dictionary bytes | `V9CodecDictionary.BYTES` resource | Breaks v9 inflation. Requires a new wire version and retained v9 asset. |
| Zone dictionary | `CodecZoneDictionary.IDS` | Breaks known-zone references. Always bump `WIRE_VERSION`. |

Version bump rules:

1. Changing high-nibble header flag meanings requires a version bump.
2. Changing body field order requires a version bump.
3. Dictionary edits require a version bump and the old dictionary must remain bound to its decode path.
4. Editing `CodecZoneDictionary.IDS` requires a version bump.
5. Adding a reserved bit does not require a bump if older decoders can ignore it safely. If older decoders would read a different byte stream, bump the wire version.
6. Legacy coordinate-mode storage used two bits. v5 claimed group flag bit 6 as the third coordinate-mode bit, and v6 uses that space for mode `6`. Adding more coordinate modes now requires another version bump or another explicit extension bit.

---

## 10. Legacy v8 Worked Example

Example route:

- group name: `Dungeon`
- zone: `dungeon_f7`
- waypoints: `(10, 70, 10)`, `(12, 70, 10)`, `(12, 70, 15)`
- gradient: AUTO
- load mode: STATIC
- radius: default
- label: none
- export options: names and colors included

This example shows the legacy wrapper and base record order from which v9 kind
0 evolved; v9 adds its different header and version-specific fields described
above. Binary body before the v8 CRC-32 is appended
and the frame is DEFLATE-compressed, with whitespace added:

```text
18               header: version=8, names flag, no label
02               string pool: 2 entries
  00                       ""          reserved at index 0
  07  44 75 6E 67 65 6F 6E              "Dungeon"
01               groupCount = 1
  01             nameIdx   = 1   "Dungeon"
  35             zoneRef   = (26 << 1) | 1   "dungeon_f7"
  03             groupFlags = 0b00000011  BODYLESS, GRAD_AUTO, VECTOR
  03             waypointCount = 3
  -- VECTOR coord stream --
  14  8C 01  14    (10, 70, 10) as zigzag varints
  04  00  00       delta (+2, 0, 0)
  00  00  0A       delta (0, 0, +5)
  -- waypoint bodies omitted: group is BODYLESS --
```

`dungeon_f7` exists in the known-zone dictionary, so the group stores it as one varint instead of writing the string. The body compresses to roughly half its raw size. The final payload is about 35–45 characters, well under one chat message.

---

## 11. Implementation Notes

- Bit I/O is byte-aligned at section boundaries. After a `FIXED_COMPACT`, `FIT_COMPACT`, or `DELTA_FIT_AXIS_SEPARATED` coordinate stream, `BitReader.alignToByteBoundary(...)` verifies zero v9 padding before dropping buffered partial-byte bits so waypoint-body reads resume cleanly; legacy calls skip the zero check. `BitWriter.flush()` mirrors this on encode. `RANGE_DELTA` is already byte-aligned because its range bytes are length-prefixed.
- All pool lookups go through `poolGet`, which bounds-checks against pool size and throws `IOException` on out-of-range indices. Malformed payloads report `string pool OOB: N` instead of `IndexOutOfBoundsException`.
- `decodeFull()` and `peekLabel()` sanitize labels even though encode already sanitizes them. `peekLabel()` remains an unchecked bounded preview and should not be treated as authenticated route metadata.
- v9 kind 0 preserves static and gradient colors through the persistent metadata extension when group metadata and colors are included. Compact kinds imply the default palette; legacy versions do not carry the v9 extension. Applying AUTO or STATIC after decode may recolor unlocked waypoints from the persisted or implied group palette.
- Skytils clipboard compatibility follows the current 1.x `Waypoints.kt` schema: V1 is `<Skytils-Waypoint-Data>(V1):` plus base64(gzip(`CategoryList` JSON)); V2 replaces gzip with Brotli. Waypointer imports both versions and exports V1, which current Skytils still accepts without its optional native Brotli encoder. Categories import as static groups because Skytils stores their waypoints in sets, and signed ARGB colors plus per-waypoint enabled state are preserved. The implementation is fixture-tested against [Skytils 1.x source at commit `276c07e`](https://github.com/Skytils/SkytilsMod/blob/276c07edf0f1e64956424016f438a5059c63a863/src/main/kotlin/gg/skytils/skytilsmod/features/impl/handlers/Waypoints.kt).

---

## 12. Non-Goals

- Random access. The format is sequential: no index, no length-prefixed group, no "seek to group 3."
- Human readability. Base-91 text is intentionally dense. Use `debugDecode` or hex-dump the raw body.
- Interchange with other mods. `WP:` is Waypointer-native. `WaypointImporter` handles Skyblocker, Skytils/Soopy, SkyHanni, Coleweight, Odin, and loose JSON-style payloads separately; those formats do not share bytes with this codec.
- Cross-version forward compatibility. Older builds refuse newer `WIRE_VERSION` values. Guessing at a newer layout risks silent misreads.

---

## 13. File Map

| Path | Responsibility |
| --- | --- |
| `codec/WaypointCodec.java` | Body format, coordinate modes, anonymous layout (v6+), options, encode/decode. |
| `codec/V9CompactCodec.java` | v9 kind-1/kind-2 name, color, zone, and trained range-coordinate bodies. |
| `codec/V9CodecDictionary.java`, `src/main/resources/assets/waypointer/codec/v9-deflate-dictionary.bin` | Loads and SHA-verifies the versioned v9 DEFLATE asset. |
| `codec/AsciiStreamCodec.java` | v5+ base-91 text alphabet, legacy v4/v3 stream alphabets, streaming pack/unpack, validation. |
| `codec/AsciiPackCodec.java` | Retired v2 base-85 packer, kept for regression tests/history. |
| `codec/CjkBase16384.java` | Retired v1 CJK base-16384 packer, restored for decode compatibility. |
| `codec/CodecDictionary.java` | Immutable v1-v8 preset DEFLATE dictionary. |
| `codec/CodecZoneDictionary.java` | Skyblocker-seeded known-zone dictionary. |
| `codec/DecodeDebug.java` | Immutable debug snapshot returned by `debugDecode`. |
| `codec/WaypointExportCodec.java` | Waypointer and third-party export target wrapper. |
| `api/DefaultWaypointerApi.java`, `api/ExportOptions.java`, `api/ExportTarget.java` | Public API layer for export calls and target/options mapping. |
| `codec/WaypointImporter.java` | Multi-format import: Waypointer, Skyblocker, Skytils/Soopy, SkyHanni, Coleweight, Odin, JSON. |
| `chat/CodecScanner.java`, `chat/ChatImportDetector.java` | Detect `WP:` substrings in chat lines. |

## 14. Why so complex?

Lots of research and time was spent trying to optimize codecs. This project initially came around because I, Babbur, hated sharing waypoints over Discord when I wanted to do it in-game.
It is mostly a passion project, and absolutely does not need this level of complexity. I do not recommend implementing this codec into your own project, at least by hand. Far more work than it's worth for most people.
Part of the philosophy of why I made this mod is because I wanted to overengineer the simple things in a simple mod, and make this mod damn good at the one thing it does best: waypoints.
There are so many moving parts and complex processes that all mesh together to create a beautifully efficient and stunningly compact encoder/decoder.
I understand that this is no easy task to implement into your own projects for support. I recommend you simply reference the API of this project.
In the future, I will create an online web API that will allow you to convert any set of waypoints between mods, i.e. SkyHanni -> Waypointer, Skytils -> Soopy, etc., but that's not yet available.
