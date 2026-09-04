# Waypointer Codec, Wire Version 10

This document specifies the Waypointer V10 share-string format. The codec source and conformance tests in this repository are authoritative.

This document separates three conformance profiles:

1. **V10 wire codec.** This profile defines the text transport, frame, and typed bodies.
2. **Universal importer.** This profile probes V10 first and then supports frozen V1-V9 inputs.
3. **Reference exporter.** This profile defines Waypointer candidate selection and product policy.

A third-party decoder can support the V10 wire codec without copying the reference exporter's DEFLATE strategy. A third-party encoder MUST obey every canonical rule that applies to the body form it emits. It does not need to copy the reference exporter's candidate portfolio or DEFLATE bytes.

## 1. Words with special meanings

- **MUST**: the rule is mandatory. A violation makes an implementation incompatible.
- **MUST NOT**: the action is forbidden.
- **MAY**: the action is optional.

Terms used in this document:

- **Transport text**: the ASCII string after `WP:`.
- **Selector**: the first transport character, `A` or `B`.
- **Payload**: the bytes decoded from the base-91 part of the transport text.
- **Semantic body**: the decompressed, checksum-free content bytes. It starts with the semantic header byte.
- **uvarint**: an unsigned integer in little-endian 7-bit groups. The high bit of each byte means "more bytes follow". Encoders MUST write the shortest form. Decoders MUST reject a longer form.
- **svarint**: a signed integer, zigzag-mapped, then written as a uvarint.
- **zigzag**: `v ≥ 0 → 2v`; `v < 0 → −2v−1`.
- **Bit stream**: bits packed into bytes least-significant-bit first, in write order.
- **Canonical**: the one permitted byte spelling for a value or local grammar. A decoder checks this by applying the stated local rule or by re-encoding that local body.
- **Raw DEFLATE**: RFC 1951 DEFLATE data without a zlib or gzip wrapper.
- **Strict UTF-8**: UTF-8 that rejects malformed input, unmappable input, and unpaired surrogate code units.
- **Projection**: an export that intentionally removes fields which the caller disabled.
- **Subwaypoint**: a waypoint whose user flags include `FLAG_SUBWAYPOINT`. This flag is bit 4, value `0x00000010`. Its parent is the nearest earlier non-subwaypoint.
- **Packed-local**: the kind-4 ten-bit delta form defined in section 9.2.
- **Unsigned byte order**: compare bytes from left to right as values from 0 to 255. The first different byte decides the order.

## 2. Frame overview

Every V10 object is one line of text:

```text
WP:<selector><contextual base-91 of payload>
```

- Selector `A` (mode 0, direct): `payload = semantic || CRC32_BE(0x00 || semantic)`.
- Selector `B` (mode 1, DEFLATE): `payload = rawDeflate(semantic) || CRC32_BE(0x01 || semantic)`.

CRC-32 uses the ISO-HDLC parameters of Java `CRC32`: reflected polynomial `0xEDB88320`, initial value `0xFFFFFFFF`, final XOR `0xFFFFFFFF`. The check value for ASCII `123456789` is `0xCBF43926`. The 4 CRC bytes are big-endian. The CRC input always starts with the binary mode byte (`0x00` or `0x01`), never the letter `A` or `B`. The selector is therefore bound to the CRC. A selector-only mutation is rejected by the conformance vectors.

The CRC covers the semantic body, not the compressed bytes. For mode B, a decoder MUST accept any standards-valid Raw DEFLATE stream that inflates exactly to the semantic body. A decoder MUST NOT require re-compression equality for mode B. V10 Raw DEFLATE MUST NOT use a preset dictionary. A decoder MUST reject a stream that requests a dictionary.

Limits:

- Complete payload: at most 2,097,152 bytes, including the four CRC bytes.
- Semantic body: at most 2,097,148 bytes.
- Compressed part of a B payload: at most 2,097,148 bytes.
- Transport text after `WP:`: at most 3,145,728 characters, including `A` or `B`.
- Inflate output: at most 2,097,152 bytes. The semantic-plus-CRC check then applies the smaller semantic limit.

The decoder rejects truncation, extra compressed input, a second DEFLATE member, a dictionary request, and a no-progress stream.

## 3. Text layer

### 3.1 Base-91 alphabet

The alphabet is the 91 printable ASCII characters except space, comma, period, and backtick, in ASCII order:

```text
!"#$%&'()*+-/0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\]^_abcdefghijklmnopqrstuvwxyz{|}~
```

The encoder accumulates payload bits little-endian.

1. Continue while more than 13 bits are buffered.
2. Read the low 13 bits as `e`.
3. If `e > 88`, consume 13 bits. Otherwise, read and consume 14 bits.
4. Emit `alphabet[e mod 91]`, then `alphabet[e div 91]`.
5. For the final buffer, emit `alphabet[bitBuffer mod 91]`.
6. Emit a second final character when `bitCount > 7 || bitBuffer >= 91`.

The decoder reads alphabet digits in pairs. For a pair `(first, second)`, set `e = first + second*91`. Consume 13 bits when `(e & 8191) > 88`; otherwise consume 14 bits. Emit complete low bytes in little-endian bit order. If one digit `d` remains, set `bitBuffer |= d << bitCount`. Then emit `bitBuffer & 0xFF`. There is no pad character. The canonical text check in section 3.3 rejects alternate tails.

### 3.2 Contextual escape

Hypixel rewrites `<3` and `o/` in chat. The escape inserts one `~` marker in exactly these cases, scanning left to right over the raw base-91 text:

- After `<` when the next raw character is `3` or `~`.
- After `o` when the next raw character is `/` or `~`.

Unescape removes one `~` after `<` or `o` only when the character after that `~` is the matching partner (`3` or `/`) or another `~`. Every other `~` is a normal base-91 digit. Escaped output never contains `<3` or `o/`.

### 3.3 Canonical text

A decoder MUST re-encode the decoded payload (base-91 plus escape, with the same selector) and require byte equality with the received transport text. This rejects non-canonical escapes and alternate base-91 tails. This rule applies to the text layer only; it does not re-run DEFLATE.

## 4. Semantic header and commitment

The first semantic byte is:

```text
bit 7      label flag (kind 0 only; 0 for all other kinds)
bits 6..4  content kind
bits 3..0  wire version = 10
```

Implemented kinds are 0 (general route), 2 (bare route), 3 (configuration), 4 (dungeon collection), 5 (sparse route), 6 subtype 0 (bare route pack), and 6 subtype 1 (route library). Kinds 1 and 7 are reserved. Other kind-4 and kind-6 subtypes are reserved.

Import order for a `WP:` string:

1. If the transport starts with `A` or `B`, attempt the V10 probe. Check canonical text, mode-B inflation, the mode-bound CRC, and version nibble 10.
2. If the probe succeeds, the decoder has committed to V10. Dispatch by kind. Any later body error is a V10 error. The decoder MUST NOT fall back to a legacy decoder after commitment.
3. If the probe fails, decode through the immutable V1-V9 path. A non-10 version nibble does not commit the A/B probe. Some legacy base-91 bodies start with `A` or `B`; the probe rejects them and they fall back correctly.

After commitment, a decoder MUST reject reserved kinds, reserved subtypes, and malformed bodies. It MUST NOT retry them as V1-V9.

V1-V9 decoding is frozen. Every V10 exporter (route, route library, configuration, dungeon) writes `WP:`. The legacy `WPC:`, `WPD:`, and `WPL:1:` prefixes are frozen input forms; the universal importer still accepts them, and the compatibility codecs that write them remain callable. The reference exporter writes `WPL:1:` only when a route library exceeds the V10 frame profile (section 6.1).

V10 DEFLATE uses no preset dictionary. V10 also has no predefined waypoint-name vocabulary. Kind 0 still uses the frozen built-in zone-ID table as part of its semantic body.

## 5. Kind 2: bare route

Semantic body:

```text
0x2A                      header
count : uvarint           0..20000, at most 3 bytes
first : 3 × svarint       present when count > 0; each in [−2^27, 2^27−1]
entropy body              present when count > 1
```

Deltas are per-axis differences between consecutive points, zigzag-mapped. Each zigzag delta MUST be ≤ 536,870,910. Reconstructed coordinates MUST stay inside `[−2^27, 2^27−1]`.

### 5.1 Mode A entropy body

The body is a bit stream. It starts with the K descriptor. `kx`, `ky`, and `kz` are the Rice parameters for the three axes.

- 2-bit token. Values 0–2 select a frozen common tuple `(kx, ky, kz)`: `(4,0,4)`, `(4,3,4)`, `(3,2,3)`.
- Token 3: three per-axis fields follow. Each field starts with 3 bits.

| Three-bit value | Meaning |
|---:|---|
| 0-6 | Rice parameter |
| 7 | Read a five-bit extension |

In the extension, 7-30 is the Rice parameter. Value 31 means a constant zero axis. Values 0-6 are invalid in the extension.

**Reserved Golomb marker.** The 10-bit pattern token=3, field=7, extended=0 is reserved. An encoder MUST NOT emit it. A decoder MUST reject it.

**Quotient descriptor.** The pattern token=3, field=7, extended=1 selects the quotient body. The decoder reads this 10-bit prefix before dispatch. The quotient decoder validates the marker again.

**Rice body** (plain descriptor): process each nonconstant axis in x, y, z order. For value `v` and parameter `k`, write:

1. `floor(v/2^k)` zero bits.
2. One bit with value 1.
3. The low `k` bits of `v`.

Unused bits in the final byte MUST be zero and fewer than eight. The decoder permits at most `90×(count−1)` unary zero bits across all axes. Each decoded value MUST stay within the coordinate model.

The encoder picks each axis `k` by exact minimal bit cost; the smallest `k` wins ties. A decoder MUST re-encode the decoded route and require semantic byte equality (canonicality).

### 5.2 Quotient body

The quotient body applies only when `1 < count ≤ 1,024`. The decoder first detects the marker. It MUST then check the point limit. This check occurs before parameters, cardinality, rank, or other combinatorial data. Three axis parameter fields follow the marker. They use the format in section 5.1 and parameters 0-28.

```text
majority   : 1 bit    1 when more than half of the deltas have quotient > 0
cardinality: Elias-gamma of (minority + 1)
rank       : fixed-width colex rank of the minority position set,
             width = bitlength(C(n, minority) − 1), most-significant bit first
remainders : low k bits of every delta, in order (omitted when k = 0)
tails      : for each delta with quotient q > 0, in order: (q−1) zero bits, one 1 bit
```

| Term | Meaning |
|---|---|
| `n` | `count-1`, the number of deltas on one axis |
| `present` | Number of deltas whose quotient is nonzero |
| `minority` | `min(present,n-present)` |
| Minority set | Positions whose presence differs from the majority value |
| Colex rank | For positions `p_1<...<p_m`, `sum(C(p_i,i))` |

`minority` MUST be at most `n/2`. When `n` is even and `minority=n/2`, majority MUST be 0. Rank MUST be less than `C(n,minority)`.

The encoder tests each `k` from 0 through 28. For each value, `quotient = value >>> k`. Let `present` be the count of nonzero quotients and `minority = min(present, n-present)`. The exact cost is:

```text
1
+ gammaLength(minority + 1)
+ bitlength(C(n, minority) - 1)
+ sum(quotient)
+ k*n
```

The encoder selects the smallest cost. The smallest `k` wins a tie. `gammaLength(v) = 2*floor(log2(v))+1`.

Implementations MUST compute binomials with an incremental multiply-divide recurrence. A decoder permits at most 200,000 combinatorial operations. It also permits at most 256,000,000 big-integer bit-work units. Charge work as follows:

| Operation | Operation charge | Bit-work charge |
|---|---:|---:|
| Read a rank of width `w` | `w` | `w*(w+1)/2` |
| Re-encode a rank of width `w` | `w` | `w` |
| Multiply `value` by integer `n` | 1 | `bitlength(value)+bitlength(n)` |
| Divide the product by integer `d` | 1 | `bitlength(product)+bitlength(d)` |
| Add, subtract, or compare `a,b` | 1 | `max(bitlength(a),bitlength(b))+1` |

The multiply-divide recurrence MUST divide exactly. The quotient-tail unary budget is `90×(count−1)+128` zero bits. A decoder MUST reject when any budget is exceeded. The body ends with the zero-padding and canonical re-encode rules from section 5.1.

### 5.3 Mode B body

The mode-B semantic starts with `0x2A`, count, and the first absolute x, y, z values. For each later point, write interleaved `dx,dy,dz` svarints. Exact end-of-body is required.

### 5.4 Selection

The Waypointer reference exporter builds the Rice body and an eligible quotient body. It also builds mode-B candidates with Java's default and filtered strategies. It scores complete final transport texts.

Selection order:

1. Shortest final transport text.
2. Direct mode A before mode B.
3. Shortest payload.
4. Lowest payload in unsigned byte order.

A compatible decoder MUST accept any body that is canonical under the kind-2 rules. It MUST NOT require the received body or DEFLATE stream to be the local exporter's winner.

## 6. Kind 6 subtype 0: bare route pack

Semantic body:

```text
0x6A                      header
subtype : uvarint         MUST be 0
count   : uvarint         2..256
children: count × ( length : uvarint ≥ 1 ; body bytes )
```

Each child is the canonical kind-2 semantic body for the enclosing transport mode with its leading `0x2A` byte removed. Direct children use the shorter of the Rice and quotient bodies; Rice wins ties. Mode B children use the delta body. Children have no nested `WP:` prefix, selector, base-91 layer, checksum, schema byte, or terminator. Declared lengths and exact end-of-body provide every boundary. There is no `BODY_SCHEMA` byte and no `END` marker.

A decoder MUST validate every child boundary, the aggregate byte total, and the aggregate waypoint total (≤ 50,000) before it allocates waypoint storage. Subtype 0 children are ordered bare regular routes only. Subtype 0 MUST NOT carry folders, paints, or other library metadata; that is subtype 1.

The reference exporter also selects the outer transport. It compares direct A with default-strategy B and filtered-strategy B by the ordering in section 5.4.

### 6.1 Kind 6 subtype 1: route library

Subtype 1 carries one general route body plus the route-library metadata that the legacy `WPL:1:` JSON wrapper used to carry: manual color snapshots, folders, and waypoint paints.

Semantic body:

```text
0x6A                      header
subtype      : uvarint    MUST be 1
routeLength  : uvarint    ≥ 1
route        : routeLength bytes; a complete kind-0 semantic body (section 10)
manualCount  : uvarint    0..256
manual[manualCount]
folderCount  : uvarint    0..256
folder[folderCount]
paintCount   : uvarint    0..256
paint[paintCount]

manual :=
    ordinal    : uvarint  route ordinal inside the kind-0 body
    colorCount : uvarint  MUST equal that route's point count
    colors     : u24be × colorCount

folder :=
    name        : uvarint byte length + strict UTF-8 (1..256 bytes)
    color       : u24be
    flags       : u8      bit 0 = collapsed; bits 1-7 MUST be zero
    memberCount : uvarint 1..256
    members     : uvarint × memberCount (route ordinals)

paint :=
    ordinal : uvarint
    enabled : u8          0 or 1
    palette : u24be × 16
    pixels  : 768 bytes   two 4-bit palette slots per byte, low nibble first,
                          in the paint's atlas pixel order (6 faces × 16 × 16)
```

The route body is decoded by the kind-0 reader unchanged; its header byte is `0x0A` or `0x8A`, so the share label lives at the same offset a kind-0 reader expects after the library prefix. The body ends after the final paint. A decoder MUST reject trailing bytes.

Canonical rules:

- At least one manual-color, folder, or paint entry MUST be present. A library without metadata is a kind-0 route and MUST be written as one.
- Manual-color ordinals and paint ordinals MUST be strictly ascending. Each ordinal MUST be less than the route count.
- Folder names MUST be nonblank and equal their whitespace-trimmed value. Folder member ordinals MUST be unique inside a folder, and a route MUST belong to at most one folder. Folder members MUST be regular routes that share one zone.
- Colors are 24-bit RGB. Paint pixel nibbles are palette slots 0..15.

The reference exporter applies the section 10.7 projection first: disabling colors drops manual colors and paints and resets folder colors to the default; disabling group metadata drops folders. When that projection leaves no metadata, the exporter writes the plain route kinds instead. Outer transport selection follows section 5.4 with the same three candidates as kind 0. When the library exceeds the frame profile, the reference exporter falls back to the legacy `WPL:1:` wrapper.

## 7. Kind 5: sparse route

Kind 5 is a kind-2 coordinate body plus sparse waypoint flags and sixteenth-block precision. It reconstructs one anonymous regular group in the unknown zone. The group uses sequence load, manual color mode, and default group settings.

The route is eligible when all these rules are true:

- The group is regular, anonymous, and has at most 20,000 points.
- The label, names, colors, radii, group metadata, and zone are absent.
- The request is not the explicit bare-coordinate projection.
- The first projected point is not a subwaypoint.
- At least one projected flag or precision value is nondefault.

Semantic prefix:

```text
0x5A                      header
selector : uvarint
```

The coordinate body is a kind-2 body without its `0x2A` header. A mode-A body is direct entropy data. A mode-B body is interleaved delta data. The reference kind-5 writer uses Rice for mode A. It does not test quotient. The decoder accepts a canonical Rice or quotient direct body.

### 7.1 Index encodings

The metadata grammars use one of these index encodings:

- **Ordinal list:** `itemCount:uvarint`, then one gap uvarint per item. Start `previous=-1`. Each index is `previous+gap+1`.
- **Presence bitmap:** exactly `ceil(pointCount/8)` bytes. Bit `i` is bit `i mod 8` of byte `floor(i/8)`. Unused high bits in the final byte MUST be zero.

Indices MUST increase and stay below the point count. A selected side stream MUST contain at least one item.

### 7.2 Split grammar

Selector values 2 or larger select the split grammar. The selector is the coordinate-body byte length.

```text
0x5A
coordinateLength : uvarint
coordinateBody[coordinateLength]
sideHeader : u8
subwaypointStream
precisionStream
otherFlagsStream
```

The side header has three two-bit modes:

| Bits | Stream |
|---|---|
| 0-1 | Subwaypoints |
| 2-3 | Precision |
| 4-5 | Other flags |
| 6-7 | Reserved; MUST be zero |

Each mode is 0 (absent), 1 (presence bitmap), or 2 (ordinal list). Mode 3 is reserved.

The streams have this format:

- **Subwaypoints:** indices, then one packed three-bit style value per index. The style is flag bits 5-7 shifted right by 5. The decoder adds `FLAG_SUBWAYPOINT`.
- **Precision:** indices, then one packed 12-bit value per index. The value is `(xOffset<<8)|(yOffset<<4)|zOffset`. Each offset is 0-15. The centered value `(8,8,8)` is not canonical.
- **Other flags:** indices, then one nonzero unsigned-32 uvarint per index. These values MUST NOT contain `FLAG_SUBWAYPOINT`. They also MUST NOT contain subwaypoint style bits when that point is in the subwaypoint stream.

Packed values are little-endian bit streams. Unused final bits MUST be zero.

### 7.3 Unified grammar, selector 0

The body starts with an ordinal list of all exception indices. It then has a packed record for each index:

```text
isSubwaypoint : 1 bit
[style       : 3 bits]       when isSubwaypoint = 1
hasOther     : 1 bit
precisionMask: 3 bits
[residual    : 4 bits]       for each set mask bit, x then y then z
```

A residual is a signed four-bit two's-complement value from -8 through 7. Zero is not canonical when its mask bit is set. Add the residual to center offset 8. The result MUST stay in 0-15. After the packed records, write one nonzero unsigned-32 uvarint for each record whose `hasOther` bit is 1.

### 7.4 Controlled unified grammar, selector 1

The body starts with the same ordinal list. It then has these control bits:

```text
anySubway : 1 bit
[allSubway: 1 bit]       when anySubway = 1
anyOther  : 1 bit
[allOther : 1 bit]       when anyOther = 1
anyPrecision : 1 bit
```

For each exception record:

1. Write `isSubwaypoint` only when `anySubway=1` and `allSubway=0`.
2. Write the three style bits when the point is a subwaypoint.
3. Write `hasOther` only when `anyOther=1` and `allOther=0`.
4. Write the precision mask and residuals when `anyPrecision=1`.

Write the nonzero other-flag uvarints after the packed records. A mixed control MUST describe both present and absent records. An enabled precision control MUST describe at least one noncentered precision value.

For selectors 0 and 1, append `coordinateBody` after all metadata and other-flag uvarints. The coordinate body consumes all remaining semantic bytes.

### 7.5 Canonical checks and selection

All three grammars require at least one real exception. A no-op record is invalid. The first point cannot be a subwaypoint. Specialized flag overlap is invalid. Packed padding MUST be zero. Exact end-of-body is required.

The reference exporter tests all eligible split index modes plus selectors 0 and 1. It tests both transport modes and both B strategies. It uses section 5.4 ordering. A decoder checks local canonical rules only. It does not require the received grammar to be the shortest grammar.

## 8. Kind 3: configuration

Semantic body:

```text
0x3A                      header
fields                    zero or more, strictly ascending by tag
```

Each field starts with `token:uvarint`, then its value. Compute `token = (tagDelta << 2) | lengthClass`. `tagDelta` MUST be at least 1.

| Length class | Value length |
|---:|---:|
| 0 | 1 byte |
| 1 | 3 bytes |
| 2 | 8 bytes |
| 3 | Read an explicit length uvarint |

Class 3 MUST NOT encode lengths 1, 3, or 8. The body is at most 32,768 bytes. It has at most 256 fields. A field value is at most 4,096 bytes. A tag is at most 65,535. A string list has at most 256 entries. Each entry has at most 64 strict UTF-8 bytes.

A decoder MUST skip structurally valid unknown tags. It does not retain them. Known fields decode into a new default configuration. The decoder then normalizes and re-encodes all known fields. The resulting known-field list MUST equal the input's known-field subsequence. This rejects explicit defaults and values that need normalization.

The reference writer compares direct A with DEFAULT-strategy and FILTERED-strategy B candidates. It applies the ordering in section 5.4.

Configuration import MUST report the object type. It MUST require user confirmation before it applies settings. Legacy `WPC` schemas remain separate compatibility decoders.

### 8.1 Value types

| Key | Wire value |
|---|---|
| B | One byte, exactly `00` or `01` |
| C | Three bytes in R, G, B order |
| D | Eight-byte big-endian IEEE-754 binary64 |
| U | One shortest uvarint, at most 2,147,483,647 |
| E | One defined enum ordinal byte |
| L | String list from section 8.4 |

### 8.2 Active tag registry

The writer omits a field when it equals its normalized default.

| Tag | Setting | Type | Default |
|---:|---|:---:|---|
| 1 | `defaultReachRadius` | D | `3.0` |
| 2 | `resetProgressOnWorldJoin` | B | `true` |
| 3 | `restartRouteWhenComplete` | B | `true` |
| 4 | `defaultWaypointColor` | C | `4FE05A` |
| 5 | `tracerColor` | C | `4FE05A` |
| 6 | `matchTracerToWaypointColor` | B | `true` |
| 7 | `tracerOpacity` | D | `0.95` |
| 8 | `tracerThickness` | D | `3.0` |
| 9 | `waypointOutlineThickness` | D | `5.0` |
| 10 | `beaconOpacity` | D | `0.33` |
| 11 | `showWaypointNames` | B | `true` |
| 12 | `showWaypointDistances` | B | `true` |
| 13 | `showRouteProgress` | B | `false` |
| 14 | `labelScale` | D | `1.0` |
| 15 | `scaleWaypointTextWithDistance` | B | `false` |
| 16 | `matchWaypointTextToWaypointColor` | B | `true` |
| 18 | `showTracer` | B | `true` |
| 19 | `dimSequenceContextWaypoints` | B | `true` |
| 20 | `hideTracerOnStaticRoutes` | B | `true` |
| 21 | `hideWaypointsNearPlayer` | B | `false` |
| 22 | `hideWaypointsNearRadius` | D | `5.0` |
| 23 | `hideWaypointLabelsNearPlayer` | B | `false` |
| 24 | `hideWaypointLabelsNearRadius` | D | `5.0` |
| 25 | `hideReachedStaticWaypointsUntilCycleComplete` | B | `false` |
| 26 | `skipAheadOnlyVisibleWaypoints` | B | `true` |
| 27 | `showRouteLines` | B | `false` |
| 28 | `routeLineColor` | C | `00FF00` |
| 29 | `showLabelBackdrop` | B | `true` |
| 30 | `maxWaypointLabels` | U | `32` |
| 31 | `maxStaticWaypointRenderDistance` | D | `0.0` |
| 32 | `labelHeightOffset` | D | `0.0` |
| 33 | `boxStyle` | E | `FILLED_OUTLINED` (2) |
| 34 | `beaconBeamMode` | E | `OFF` (0) |
| 35 | `beaconBeamExtendsBelowWaypoint` | B | `false` |
| 36 | `chatCoordDetection` | B | `true` |
| 37 | `chatCoordSenderBlacklist` | L | empty |
| 38 | `autoAddChatTempWaypoints` | B | `false` |
| 39 | `placeNewWaypointsBelowPlayer` | B | `true` |
| 40 | `focusTempWaypoints` | B | `false` |
| 41 | `chatCodecDetection` | B | `true` |
| 42 | `importedRouteColorMode` | E | `STATIC` (0) |
| 43 | `importedRouteDefaultColor` | C | `00FF00` |
| 44 | `exportIncludeNames` | B | `false` |
| 45 | `exportIncludeColors` | B | `false` |
| 46 | `exportIncludeRadii` | B | `false` |
| 47 | `exportIncludeWaypointFlags` | B | `false` |
| 48 | `exportIncludeGroupMeta` | B | `false` |
| 49 | `dungeonWaypointsFeatureEnabled` | B | `false` |
| 50 | `skipAheadMechanicEnabled` | B | `true` |
| 52 | `irisShaderHudFallback` | B | `true` |
| 53 | `tempDefaultMode` | U | `TEMP_TIME` (1) |
| 56 | `editSounds` | B | `true` |
| 57 | `showEditModeSubtitle` | B | `true` |
| 58 | `useBeaconBeamTextures` | B | `true` |
| 59 | `tempDefaultDurationSec` | U | `60` |
| 60 | `showDungeonEntryPathToFirstWaypoint` | B | `false` |
| 61 | `dungeonEntryPathColor` | C | `00FF00` |
| 62 | `showDungeonEntryPathToFollowingWaypoints` | B | `false` |
| 63 | `showContributorBadges` | B | `true` |
| 64 | `showLabelTextShadow` | B | `true` |
| 65 | `showWaypointChatShareButtons` | B | `true` |
| 67 | `showRouteIndicesInGui` | B | `false` |
| 68 | `keepSubwaypointsVisibleUntilNextWaypoint` | B | `true` |
| 69 | `exportIncludeZone` | B | `false` |
| 70 | `useEtherwarpHeight` | B | `false` |
| 71 | `showExportRoutePreview` | B | `false` |
| 72 | `waypointMarkerScale` | D | `1.0` |
| 73 | `waypointOutlineOpacity` | D | `1.0` |
| 74 | `matchWaypointOutlineToWaypointColor` | B | `true` |
| 75 | `waypointOutlineColor` | C | `4FE05A` |
| 76 | `sequencePreviousWaypointCount` | U | `1` |
| 77 | `showCurrentSequenceWaypoint` | B | `true` |
| 78 | `sequenceNextWaypointCount` | U | `1` |
| 79 | `etherwarpAlignmentSound` (legacy, decode-only) | B | `false` |
| 80 | `etherwarpAlignmentSound` | E | `OFF` (0) |

Inactive legacy holes are tags 17, 51, 54, 55, and 66. Tag 79 is a decode-only boolean alias (`false=OFF`, `true=EXPERIENCE`); tag 80 is active. Tags 81-65,535 are unassigned. The decoder treats unassigned values as bounded unknown fields. Tag 0 is invalid.

### 8.3 Enum and normalized value domains

| Tag | Ordinal mapping |
|---:|---|
| 33 | `0=OUTLINED`, `1=FILLED`, `2=FILLED_OUTLINED`, `3=PAINT` |
| 34 | `0=OFF`, `1=CURRENT`, `2=ALL_VISIBLE` |
| 42 | `0=STATIC`, `1=AUTO`, `2=MANUAL` |
| 53 | `1=TEMP_TIME`, `2=TEMP_UNTIL_REACHED`, `3=TEMP_UNTIL_LEAVE` |
| 80 | `0=OFF`, `1=EXPERIENCE`, `2=PLING`, `3=BELL` |

Tag 53 uses type U, not type E.

| Tags | Required normalized value |
|---|---|
| 1 | Finite and in `0.5..100.0` |
| 7, 10 | Finite and in `0.0..1.0` |
| 8, 9 | Finite and in `1.0..12.0` |
| 14 | Finite and in `0.25..4.0` |
| 22, 24 | Finite and in `0.5..100.0` |
| 30 | `0..2,147,483,647` |
| 31 | Finite and at least `0.0` |
| 32 | Any finite binary64 value |
| 53 | `1..3` |
| 59 | `1..86,400` |
| 72 | Finite and in `0.25..3.0` |
| 73 | Finite and in `0.0..1.0` |
| 76 | `0..33`; 33 means all previous points |
| 78 | `0..32` |

For nonnegative floating-point fields, `-0.0` normalizes to `+0.0` and is not canonical. Tag 32 preserves `-0.0`.

### 8.4 Tag 37 string list

```text
count : uvarint
repeat count times:
    byteLength : uvarint
    utf8Bytes[byteLength]
```

The count is 0-256. A string is at most 64 strict UTF-8 bytes. The complete field is at most 4,096 bytes. Each decoded string MUST already be stable under Java `String.trim()`. It MUST be nonempty and at most 16 UTF-16 code units. Entries MUST be unique under case-insensitive comparison. Order is preserved. An explicit empty list is not canonical because it equals the default.

## 9. Kind 4: dungeon collection

Semantic body:

```text
0x4A                      header
subtype : uvarint         MUST be 0 (flattened WPD schema-2)
count   : uvarint         1..512
routes  : count × ( length : uvarint ≥ 1 ; route body )
```

Each route body MUST end at its declared length. The collection MUST end after the final declared route body. There is no schema byte and no `END` marker.

Route body order:

```text
roomId : string
routeName : string
groupFlags : u8
[defaultRadius : f64be]
waypointCount : uvarint
coordinateMode : u8
coordinates
waypointRecords[waypointCount]
```

Group flag bit 0 selects sequence load. Bit 1 enables skip-ahead. Bit 2 announces the radius. Other bits MUST be zero.

### 9.1 Strings, group fields, and limits

A string is `length:uvarint` plus strict UTF-8 bytes. Each string is at most 256 bytes. Aggregate string data is at most 1,048,576 bytes. The room ID MUST be nonempty. The route name MAY be empty.

Normalize the room ID as follows:

1. Apply Java `trim`.
2. Convert to lower case with `Locale.ROOT`.
3. Replace each run outside `[a-z0-9]` with `-`.
4. Remove leading and trailing `-` characters.

The decoded room ID MUST already equal this normalized value.

The optional group radius is an IEEE-754 binary64 value in big-endian order. It MUST be finite and in `[0.5,100]`. It MUST equal `Waypoint.normalizeDefaultRadius(value)`. An explicit value of `3.0` is not canonical.

Limits are 512 routes, 512 points per route, and 50,000 points in total. The collection MUST contain at least one point. One route MAY contain zero points. A route body is at most 1,048,576 bytes. The complete semantic body is at most 2,097,148 bytes.

The decoder work limit is 2,929,916 units:

```text
2,097,148 + 16*50,000 + 64*512
```

The decoder charges one unit per parsed byte, 16 per point, and 64 per route.

### 9.2 Coordinate modes

Mode 0 is delta-varint:

- Write the first point as three absolute svarints.
- Write each later point as interleaved `dx,dy,dz` svarints.

Mode 1 is packed-local:

- Write the first point as three absolute svarints.
- For each later point, write one ten-bit tuple, little-endian in the bit stream.
- Tuple bits 0-3 are four-bit zigzag `dx`.
- Tuple bits 4-5 are two-bit zigzag `dy`.
- Tuple bits 6-9 are four-bit zigzag `dz`.
- Packed eligibility is `dx,dz` in `[-7,7]` and `dy` in `[-1,1]`.
- Unused bits in the last byte MUST be zero.

The encoder compares the raw byte counts of mode 0 and mode 1. Mode 1 is canonical only when it is eligible and strictly shorter. Mode 0 wins a tie. The decoder recomputes this choice.

### 9.3 Waypoint records

The waypoint flag byte has this layout:

| Bit | Meaning |
|---:|---|
| 0 | Name |
| 1 | Ordinary RGB color |
| 2 | User flags |
| 3 | Custom radius |
| 4 | Precise residuals |
| 5 | Extended color |
| 6-7 | Reserved; MUST be zero |

RGB and extended color are mutually exclusive. Payload fields use this exact order, which is not flag-bit order:

1. Name.
2. Ordinary RGB.
3. Extended color.
4. User flags.
5. Custom radius.
6. Precise residuals.

Field formats:

- **Name:** nonempty length-prefixed strict UTF-8.
- **RGB:** three bytes in R, G, B order. Explicit default color `0x4FE05A` is not canonical.
- **Extended color:** unsigned-32 uvarint. It is canonical only outside `0x000000..0xFFFFFF`.
- **User flags:** nonzero unsigned-32 uvarint.
- **Custom radius:** big-endian IEEE-754 binary64. It MUST be finite, greater than zero, at most 100, and equal its normalized model value.
- **Precise residuals:** three one-byte svarints, x then y then z. Each is in `[-8,7]` from block center `block*16+8`. The all-zero tuple is not canonical.

### 9.4 Outer transport selection

The reference exporter compares direct A, default-strategy B, and filtered-strategy B. It uses section 5.4 ordering. A decoder accepts any valid outer transport and applies the local body canonical rules.

## 10. Kind 0: general route

Kind 0 carries general rich-route data. It reuses the V9 general-body grammar. V10 changes the version nibble and the outer transport. V10 does not use the V9 DEFLATE dictionary.

The header is `0x0A` without a label and `0x8A` with a label.

### 10.1 Top-level grammar

```text
kind0 :=
    header : u8
    [label]
    stringPool
    groupCount : uvarint
    group[groupCount]

label :=
    byteLength : uvarint
    strictUtf8[byteLength]

stringPool :=
    stringCount : uvarint
    string[stringCount]

string :=
    byteLength : uvarint
    strictUtf8[byteLength]
```

The body ends after the final group. A decoder MUST reject trailing bytes.

The label has a maximum wire length of 256 UTF-8 bytes. Before encode, the reference writer:

1. Removes `§`, C0 controls, and `0x7F`.
2. Removes malformed surrogate halves.
3. Keeps at most 64 UTF-16 code units without splitting a surrogate pair.
4. Removes leading and trailing Unicode whitespace.

Decode applies the same normalization after strict UTF-8 validation.

Group and waypoint display names are different. The writer does not normalize them. The encoder and decoder reject invalid Unicode, `§`, C0 controls, `0x7F`, and names longer than 256 UTF-8 bytes.

### 10.2 String pool and zone references

The reference writer reserves pool index 0 for the empty string. It then processes groups in order. It interns these values:

1. Each group name.
2. Each custom zone that is not blank, `unknown`, or in the zone dictionary.
3. Each selected waypoint name that occurs more than once in the complete export.

A waypoint name that occurs once is inline. The decoder permits duplicate pool strings. It does not require index 0 to be empty. Pool construction is a reference-writer rule.

A pool has at most 65,536 strings. One general string has at most 1,048,576 UTF-8 bytes.

A zone reference is a tagged uvarint:

```text
known zone := (dictionaryIndex << 1) | 1
pooled zone := poolIndex << 1
```

The reference writer uses reference 0 for no recorded zone because it reserves pool index 0 as empty. A decoder treats 0 as pool index 0. An unknown odd index becomes the unknown zone. An even reference outside the pool is invalid. A blank pooled value or literal `unknown` also becomes the unknown zone.

After resolution, trim the zone and convert it to lowercase with `Locale.ROOT`. A blank value becomes `unknown`. Map `great_glacite_lake`, `glacite_tunnels`, and `dwarven_base_camp` to `dwarven_mines`. Map any `mineshaft_*_crystal` value to `mineshaft_crystal`. Map `foraging_3` to `torrhus_canyon`.

Dictionary indices are zero-based positions in this immutable list:

```text
hub, private_island, dungeon_hub, the_park, the_farming_isles,
spiders_den, the_end, crimson_isle, kuudra, gold_mine,
deep_caverns, dwarven_mines, crystal_hollows, garden, rift,
galatea, backwater_bayou, winter, dark_auction, dungeon,
dungeon_f1, dungeon_f2, dungeon_f3, dungeon_f4, dungeon_f5,
dungeon_f6, dungeon_f7, dungeon_m1, dungeon_m2, dungeon_m3,
dungeon_m4, dungeon_m5, dungeon_m6, dungeon_m7, dynamic,
farming_1, foraging_1, foraging_2, combat_1, combat_2,
combat_3, mining_1, mining_2, mining_3, fishing_1, mineshaft,
great_glacite_lake, glacite_tunnels, dwarven_base_camp,
mineshaft_unknown, mineshaft_topaz_1, mineshaft_topaz_2,
mineshaft_sapphire_1, mineshaft_sapphire_2,
mineshaft_amethyst_1, mineshaft_amethyst_2,
mineshaft_amber_1, mineshaft_amber_2, mineshaft_jade_1,
mineshaft_jade_2, mineshaft_ruby_1, mineshaft_ruby_2,
mineshaft_ruby_crystal, mineshaft_onyx_1, mineshaft_onyx_2,
mineshaft_onyx_crystal, mineshaft_aquamarine_1,
mineshaft_aquamarine_2, mineshaft_aquamarine_crystal,
mineshaft_citrine_1, mineshaft_citrine_2,
mineshaft_citrine_crystal, mineshaft_peridot_1,
mineshaft_peridot_2, mineshaft_peridot_crystal,
mineshaft_jasper, mineshaft_jasper_crystal, mineshaft_opal,
mineshaft_opal_crystal, mineshaft_titanium, mineshaft_umber,
mineshaft_tungsten, mineshaft_vanguard,
mineshaft_littlefoots_den, mineshaft_crystal
```

### 10.3 Group record

```text
group :=
    nameIndex : uvarint
    zoneReference : uvarint
    groupFlags : u8
    [persistentMetadata]
    [defaultRadius : f64be]
    waypointCount : uvarint
    coordinateBlock
    [waypointBody[waypointCount]]
```

Waypoint bodies are absent when group flag bit 0 is set.

| Group flag | Meaning |
|---:|---|
| Bit 0 | No waypoint-body bytes follow |
| Bit 1 | Gradient mode is AUTO |
| Bit 2 | Load mode is SEQUENCE; clear means STATIC |
| Bit 3 | Exact group radius follows |
| Bits 4-5 | Low two bits of coordinate-mode ID |
| Bit 6 | High bit of coordinate-mode ID |
| Bit 7 | Persistent metadata follows |

Persistent metadata is:

```text
metadataFlags : u8
staticRgb : u24be
gradientStartRgb : u24be
gradientEndRgb : u24be
```

Metadata flag bits 0-1 select gradient mode: 0 MANUAL, 1 AUTO, 2 STATIC. Value 3 is invalid. Bit 2 enables skip-ahead. Bits 3-7 MUST be zero. AUTO MUST agree with group flag bit 1.

Without the extension, skip-ahead is enabled. Default colors are `0x4FE05A`, `0x00BFFF`, and `0xFF3040`.

The reference writer emits persistent metadata when skip-ahead is disabled. It also emits metadata when selected colors use STATIC mode or a nondefault palette color.

The default group radius is 3.0. A transmitted radius is big-endian IEEE-754 binary64. It MUST be finite, in `[0.5,100]`, and equal its model-normalized value. The reference writer omits an explicit value of 3.0.

### 10.4 Coordinate block

All block coordinates MUST be in `[-134217728,134217727]`.

```text
mode = ((groupFlags >> 4) & 3) |
       ((groupFlags & 0x40) != 0 ? 4 : 0)
```

The packed coordinate streams for modes 2, 3, and 5 are most-significant-bit first. Their final partial byte uses zero padding.

| ID | Name | Grammar |
|---:|---|---|
| 0 | VECTOR | First point is three absolute svarints. Later points are three svarint deltas. |
| 1 | ABSOLUTE_VARINT | Every point is three absolute svarints. |
| 2 | FIXED_COMPACT | Each point is 12-bit zigzag X, 9-bit `Y+64`, and 12-bit zigzag Z. |
| 3 | FIT_COMPACT | Three svarint origins, one width word, then unsigned offsets. |
| 4 | VECTOR_AXIS_SEPARATED | First point, then all X deltas, all Y deltas, and all Z deltas. |
| 5 | DELTA_FIT_AXIS_SEPARATED | First point, a width word, then fixed-width zigzag X, Y, and Z delta streams. |
| 6 | RANGE_DELTA | First point, a width word, payload length, then adaptive range-coded delta bits. |

Mode 2 requires X and Z in `[-2048,2047]` and Y in `[-64,447]`.

A width word is big-endian:

```text
reserved:1 | xWidth:5 | yWidth:5 | zWidth:5
```

The reserved bit MUST be zero. In mode 3, width 0 means that all coordinates on that axis equal the origin. Mode 3 writes point-major X, Y, Z offsets. The reference writer uses axis minima as origins and minimum sufficient widths.

In modes 5 and 6, width 0 means that all deltas on that axis are zero. These modes write values axis-major. The reference writer uses minimum sufficient widths for the largest zigzag deltas. A decoder does not require reference-writer origins or widths.

Mode 6 writes:

```text
rangePayloadLength : uvarint
rangePayload[rangePayloadLength]
```

The length limit is:

```text
min(1048576, max(16,
    (waypointCount-1)*(xWidth+yWidth+zWidth)+16))
```

Mode 6 processes X, then Y, then Z. It processes each value from its most-significant bit to its least-significant bit. The range coder uses unsigned 32-bit state:

```text
MASK = 0xFFFFFFFF
TOP = 1 << 24
BOTTOM = 1 << 16
SCALE = 1 << 12
MOVE = 4

encoder: low = 0; range = MASK
decoder: low = 0; range = MASK; code = first four payload bytes
```

It has 93 probability contexts. Context `(axis,bit)` is `axis*31+bit`. Each probability starts at 2048.

For one bit:

```text
bound = (range >>> 12) * probability

if bit == 0:
    range = bound
    probability += (4096-probability) >>> 4
else:
    low = (low+bound) & MASK
    range = (range-bound) & MASK
    probability -= probability >>> 4
```

After each bit, renormalize:

```text
while ((low ^ (low+range)) < TOP) or (range < BOTTOM):
    if (low ^ (low+range)) >= TOP:
        range = (-low) & (BOTTOM-1)
    emit (low >>> 24) & 0xFF
    low = (low << 8) & MASK
    range = (range << 8) & MASK
```

Finish the range payload as follows:

1. Test output length `n` from zero through four.
2. Set `shift=8*(4-n)`.
3. For `shift>0`, round `low` up to a multiple of `2^shift`.
4. Accept the first candidate at most `MASK` where `((candidate-low)&MASK)<range`.
5. Emit the first `n` big-endian candidate bytes.
6. If no candidate works, emit four big-endian bytes of `low`.

The decoder mirrors these operations. It initializes `code` from four bytes and uses zero beyond the declared payload. It decodes zero when `((code-low)&MASK)<bound`.

The current decoder does not require minimal origins, widths, or unique range-coder output. It does require valid coordinates, reserved-bit rules, packed zero padding, declared limits, and exact semantic EOF.

### 10.5 Coordinate-mode selection

The reference writer tests every eligible mode in numeric order. Mode 2 requires its coordinate bounds. Modes 5 and 6 require widths of at most 31 bits. For each mode, the writer scores the semantic prefix through the current group. The prefix includes earlier groups but not later groups. It scores the shortest complete V10 A/B text form of that partial body.

The writer replaces a mode only when the new text is strictly shorter. Numeric order is therefore the tie order. A decoder MUST accept any valid mode. It MUST NOT require the sender's mode to match its local writer.

### 10.6 Waypoint record

After all coordinates, a nonbodyless group has one record for each point:

```text
waypointFlags : u8
[name]
[rgb : u24be]
[customRadius : f64be]
[semanticFlags : flags-uvarint]
[preciseOffsets : uvarint]
```

| Waypoint flag | Meaning |
|---:|---|
| Bit 0 | Name follows |
| Bit 1 | RGB follows |
| Bit 2 | Custom radius follows |
| Bit 3 | Semantic flags follow |
| Bit 4 | Name is inline; clear means pool index |
| Bit 5 | Packed precise offsets follow |
| Bits 6-7 | Reference writer writes zero; current decoder ignores them |

Payload order is name, RGB, radius, semantic flags, and precise offsets.

The reference writer omits an RGB field when its value is the default `0x4FE05A`.

An inline name is `byteLength:uvarint` plus strict UTF-8. A pooled name is `nameIndex:uvarint`. The default color is `0x4FE05A`.

A custom radius is big-endian IEEE-754 binary64. It MUST be finite, greater than zero, at most 100, and equal its model-normalized value.

The semantic flags field preserves the complete unsigned 32-bit pattern. Packed precision is `(xOffset<<8)|(yOffset<<4)|zOffset`. Each offset is 0-15. Bits above bit 11 MUST be zero. An absent precision field means block-center offset 8 on all axes.

### 10.7 Selected-field projection

| Disabled option | Result |
|---|---|
| Names | Remove waypoint names; keep group names |
| Colors | Remove waypoint colors; project group gradient and palette to defaults |
| Radii | Remove waypoint radius overrides |
| Group metadata | Use sequence load, enabled skip-ahead, radius 3.0, MANUAL mode, and default palette |
| Zone | Store no recorded zone |
| Waypoint flags | Remove optional flags except the cases below |

When waypoint flags are disabled, the writer keeps subwaypoint structure, dungeon metadata, and disabled-waypoint state. It keeps subwaypoint styles when the subwaypoint bit is present. It keeps locked-color state when colors are included.

The always-preserved behavior mask is `0x001FF010`. The subwaypoint-style mask is `0x000000E0`. The locked-color bit is `0x00000008`.

A custom precise position remains for a subwaypoint. For another point, it remains only when waypoint flags are included.

The body does not store group enabled state, current progress, temporary mode, expiry time, or other player-session state. It also does not store route kind, paint state, catalog provenance, or a stable group ID. Imported groups are new REGULAR groups. They start enabled and at progress index zero.

### 10.8 Limits and outbound behavior

| Item | Limit |
|---|---:|
| Groups | 256 |
| Points in one group | 20,000 |
| Total points | 50,000 |
| String-pool entries | 65,536 |
| General UTF-8 string | 1,048,576 bytes |
| Group or point display name | 256 UTF-8 bytes |
| Label | 64 UTF-16 units and 256 UTF-8 bytes |
| Range payload | 1,048,576 bytes, with the tighter count-derived limit |

The reference writer builds direct A and two B candidates. Both B candidates use Java `Deflater.BEST_COMPRESSION`. They use the DEFAULT and FILTERED strategies. The writer applies section 5.4 ordering. An oversized optional B candidate is ignored when A fits.

The dungeon writer uses the same rule. It ignores an oversized optional B candidate when its direct A candidate fits.

For one eligible route, kind 5 replaces kind 0 only when kind 5 is strictly shorter. Kind 0 wins a tie. The normal writer falls back to V9 only after `V10ProfileLimitException`. It does not downgrade after another encode error. The catalog writer deliberately stays full-fidelity V9.

## 11. Encoder product policy

The six visible route fields are names, colors, radii, waypoint flags, group metadata, and zone.

| Input and request | Reference result |
|---|---|
| One eligible regular route, all six fields off, empty label | Kind 2 |
| Two or more eligible regular routes, all six fields off, empty label | Kind 6 subtype 0 |
| Explicit `BARE_COORDINATES` with a nonempty ineligible input | Reject; do not fall through to kind 0 |
| Ordinary all-off request with a nonregular route | Kind 0 |
| One eligible anonymous route with sparse exceptions | Compare kind 0 and kind 5 |
| Dungeon collection through the typed dungeon exporter | Kind 4 |
| Configuration through the typed configuration exporter | Kind 3 |
| Library with folders, paints, or manual-color metadata after projection | Kind 6 subtype 1 |
| Library that exceeds the V10 frame profile | Legacy `WPL:1:` wrapper |

The bare selection applies to generic and public API exports. It is a projection: discarded names and metadata do not block it. If a supported general route exceeds the bounded V10 frame profile, `WaypointCodec.encode` can emit a canonical V9 `WP:` share as its compatibility fallback.

## 12. Identity, integrity, and security

- The exact `WP:` string identifies one transport spelling. Different valid mode-B DEFLATE streams can carry the same semantic body.
- A semantic body is not a general route-content identifier. Kind 2 can encode the same coordinates with different canonical local bodies.
- The current catalog remains V9. It hashes the exact canonical V9 payload text.
- Anonymous install tokens use `HMAC(deviceSecret, routeId)`. They do not hash V10 semantic bytes.
- The source does not define a V10 semantic content ID or a signed-share digest. Do not invent one from this document.
- CRC-32 detects accidental corruption. It is not identity or authentication. Anyone can forge it.
- A decoder MUST apply all limits. Some bounded buffers are allocated before their decoded-size check. The transport-character limit bounds that work.

## 13. Limits summary

| Item | Limit |
|---|---|
| Complete payload, including CRC | 2,097,152 bytes |
| Semantic body | 2,097,148 bytes |
| B compressed part | 2,097,148 bytes |
| Transport characters | 3,145,728 |
| Kind 2/5 waypoints | 20,000 |
| Quotient body waypoints | 1,024, checked after descriptor dispatch |
| Quotient combinatorial ops / bit work | 200,000 / 256,000,000 |
| Plain Rice unary budget | `90×(count−1)` zero bits |
| Quotient-tail unary budget | `90×(count−1)+128` zero bits |
| Pack routes / aggregate waypoints | 2–256 / 50,000 |
| Library manual-color, folder, paint entries | 256 each |
| Library folder name / members | 256 strict UTF-8 bytes / 1–256 |
| Kind-0 groups / per-group / aggregate points | 256 / 20,000 / 50,000 |
| Kind-0 pool entries / general string | 65,536 / 1,048,576 bytes |
| Route display name / share label | 256 / 256 strict UTF-8 bytes |
| Dungeon routes / per-route / aggregate points | 512 / 512 / 50,000 |
| Dungeon route body / strings / work | 1,048,576 B / 256 B each and 1,048,576 B total / 2,929,916 units |
| Config body / fields / field / tag | 32,768 B / 256 / 4,096 B / 65,535 |
| Coordinate range | [−134,217,728, 134,217,727] |
| Zigzag delta | ≤ 536,870,910 |

## 14. Example and golden fixtures

Exact mode-A empty-route vector:

| Item | Value |
|---|---|
| Semantic body | `2A00` |
| CRC | `902A153A` |
| Payload | `2A00902A153A` |
| Complete text | `WP:AM!p?K(]!` |

Repository fixtures:

- `src/test/resources/fixtures/waypointer-v10-next-no-golomb-goldens.json` has 21 quotient vectors and 6 DEFLATE vectors.
- `src/test/resources/fixtures/waypointer-v10-config-golden-vectors.json` has kind-3 vectors.
- `src/test/resources/fixtures/waypointer-native-golden-vectors.json` has V1-V9 compatibility vectors and dictionary hashes.
- `src/test/java/com/babbur/waypointer/codec/V10SparseRouteCodecTest.java` has the kind-5 reference strings.
- `src/test/java/com/babbur/waypointer/codec/V10DungeonCodecTest.java` has the kind-4 reference checks.

The 302-route corpus is external benchmark evidence. It is not a complete repository fixture. Supply its path with the `v10.corpus` system property to run `V10LockedCorpusTest`. The reference result is 76,699 final characters. Its SHA-256 digest is `19ee966ed93d36e96fce91adeb6c02bf19a41bb1bc2c23696f84fdf3ec47c935`.

The normalized corpus SHA-256 is `dc4fdd8368b3e25b982a4af509a1719b0e9c109692813ca56245c291fa967613`. The external reference candidate SHA-256 is `1b17d17a2f8e0871b98da8d974a317dbd186153ab83dd081eb1a86429bc5f058`.

The digest input is the corpus-order sequence of complete ASCII wire strings. Prefix each string with its four-byte big-endian byte length before hashing.

## 15. File map and interoperability checklist

| File | Responsibility |
|---|---|
| `src/main/java/com/babbur/waypointer/codec/UniversalShareCodec.java` | Universal route, config, and dungeon entry point |
| `src/main/java/com/babbur/waypointer/codec/V10Transport.java` | Frame, CRC, DEFLATE bounds, contextual text |
| `src/main/java/com/babbur/waypointer/codec/V10RouteCodec.java` | Route selection and kinds 0, 2, 5, and 6 dispatch |
| `src/main/java/com/babbur/waypointer/codec/V10GeneralRouteCodec.java` | Kind-0 A/B candidate selection |
| `src/main/java/com/babbur/waypointer/codec/V10BareRouteCodec.java` | Kind 2, Rice descriptor, coordinate primitive |
| `src/main/java/com/babbur/waypointer/codec/V10BareEntropyCodec.java` | Quotient descriptor and work budgets |
| `src/main/java/com/babbur/waypointer/codec/V10BareRoutePackCodec.java` | Kind 6 subtype 0 |
| `src/main/java/com/babbur/waypointer/codec/V10RouteLibraryCodec.java` | Kind 6 subtype 1 |
| `src/main/java/com/babbur/waypointer/codec/RouteLibraryCodec.java` | Library entry point; frozen `WPL:1:` reader and fallback writer |
| `src/main/java/com/babbur/waypointer/codec/V10SparseRouteCodec.java` | Kind 5 |
| `src/main/java/com/babbur/waypointer/codec/V10ConfigCodec.java` | Kind-3 transport selection |
| `src/main/java/com/babbur/waypointer/config/V10ConfigBodyCodec.java` | Kind-3 body |
| `src/main/java/com/babbur/waypointer/codec/V10DungeonCodec.java` | Kind-4 transport selection |
| `src/main/java/com/babbur/waypointer/dungeon/data/V10DungeonBodyCodec.java` | Kind-4 body |
| `src/main/java/com/babbur/waypointer/codec/WaypointCodec.java` | Route-only entry point, kind-0 interior, V1-V9 legacy |
| `src/main/java/com/babbur/waypointer/codec/AsciiStreamCodec.java` | Base-91 text codec |

Interoperability checks:

1. CRC check value is `0xCBF43926`.
2. Base-91 pair threshold is 88.
3. Contextual escape round-trips `<~3`, `o~/`, `<~~3`, and tilde runs.
4. Canonical text rejects an unescaped raw `<3` pair.
5. For selector-mutation conformance vectors, changing A to B or B to A is rejected by mode-specific decoding or the mode-bound CRC.
6. Quotient count and work-limit violations are rejected.
7. The reserved Golomb marker is rejected.
8. Alternate valid Raw DEFLATE streams are accepted.
9. Legacy V9 strings whose first body character is A or B reach V9 fallback.
10. Mode-A reference output matches the repository's recorded vectors byte for byte.
11. Mode-B input requires semantic equality, not DEFLATE byte equality.

## 16. History

V1 uses CJK Base16384. V2 uses base 85. V3 uses base 93. V4 uses base 92. V5-V9 use the current base-91 alphabet. V1-V8 use the frozen 363-byte legacy DEFLATE dictionary. V9 uses a separate frozen 32 KiB dictionary. V8 introduced an internal CRC.

V9 already had the three-bit content-kind field. V10 expands and unifies its use. V10 removes preset DEFLATE dictionaries and the waypoint-name vocabulary. It moves the CRC outside DEFLATE and uses contextual escaping. The built-in zone-ID table remains part of kind 0. This section is historical and is not normative V10 grammar.
