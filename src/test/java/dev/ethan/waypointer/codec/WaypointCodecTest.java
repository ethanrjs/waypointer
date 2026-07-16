package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip safety net for the import/export format. A regression here silently
 * corrupts every shared waypoint chain, so we lean on fuzzing over hand-crafted cases.
 */
class WaypointCodecTest {

    private static final WaypointCodec.Options FULL_FIDELITY =
            WaypointCodec.Options.builder()
                    .includeNames(true)
                    .includeColors(true)
                    .includeRadii(true)
                    .includeWaypointFlags(true)
                    .includeGroupMeta(true)
                    .build();

    private static final String LEGACY_V2_SAMPLE = WaypointCodec.MAGIC
                + "9NEC)tXa-R&pB;-XZcYv:>OTCcUT%MWvWyNh(fK:V)48Ri7YNG^6nQBfSw-seuxx]y/9F^Ag[GiDd?zQ3QD8{XnSR33(3o6&>*5x"
                + "Ss+fZXP@uK?$}(JOQotG@(9POy-AwI?Ua}B>4s8MNZpP17$v}#?tb+!<*HK:K$0nhGeD!&Woi;Un$Pl[Tg!BeZRr!6*u-V)kNmS4"
                + "P==fW1elaHF[NI[ar%CokroUX{#MO}?Ga+>qBb6s24OyM1>!T%kT!GJn<T%P4B]#zlBAGup-X:9t0L;]1*aEq=}SZtBq}/zmR1nh"
                + "q[J2B(#;{O($s}N9%q]gy5QGfMPvfM{$>&lh$]yLQ&f1SvTU+g{vX#P?5boUf}oR3b=k83^Lntz7/}81Zo2oFAu[1o}v?(+/n7*i"
                + "k%fh{AR6/${wWuPY1g$R]7/#(z%!Pe[xw35-fE1hC1#F3N)}jkI)9IyA0;P{I3az4:Pi<yl/kFU<fUwGnQcVm5PR!kVCGM5:p(zV"
                + "i&<4H{0Ss^Jmdh/;$kHIO+D8=7^g*0C6#MrTx0Ich[@O}NoNq7ZI%7&+cfQ@BB:no+5u6Z{RMyOZJOMDO{kxy[z:2hjLs8#>JQKU"
                + "Yf9B$!lOUKDJ2N?tIx7C>A/Hp&nJXI2yHjPGl$nn}0*OYXoN4}M+29^DaA?JZzdQRd%EQ2^[W5V8(#f{vcahp}!aeU?p%MFTdBsH"
                + "gQt0$Io%SNxfn2i>EAHfJdNVU>cB42F)DVB(]t*:XK}fu3H)]Iftc;#r!X@ANCH]83x@4rOQXR6C*4<EUD}SoxD^$9+Pnm5+VVXx"
                + "O3qk^V=(z5WvJotkF!)04So1gc/#gM6aS$l9]N+sa%r4pN-D4yb1HZJKqGeGm3e(fwN;495>v-&&WvUGmU@>V7&=7A6xv1qvUf%)"
                + "RIF}}Hl%nZ688$IaQyT/lk6*5nWFZ12lj-73";

    private static final String LEGACY_V3_RELEASE_SAMPLE = WaypointCodec.MAGIC
                + "ETR'C/Me=pcE$YZ7GWFbB_}bQV~Zi+)W=MVG0Fk+{>z/=\\kNR,94i]D\\?A`|jb;2I9phNe@cBZiOd-db\\b30:t)0mW6\"/"
                + "VVy:MH&0R!^4tc~iFSII3LJwoI`=yE)bHV5v~+'@R(faf3dfoa,A&c!t|3,O{i&)~[u5CR&d|\"Mwqkd]k>|#o?C-jDxFEzA7DXu"
                + "_6i_@n'5KK:e2&U5%F5c3>:6KwMqXo_*clyIcAxF'qs)d'tiMW>Z_;J31*Tg=o_g&';HFj&X};'--JZ^v8<gA3ubT>(M+1Ig]_C6"
                + "vO&NCQ71?[+=W!bSmDs-MLteFwXwoqbf~[fkKP&g;`0C\"l!1uCJ*cqZ];2qFp`Y>/T__>|g`/NBB\\6J,e=~3\"J?~1[4<o;[O1"
                + ";?>RlM5\\Z6g@T`e`eY-4;*{{|>kSZsCoj36/@r$oj16&zLQ/]78,\\m,b;=?SZ9Q`0)?zSp-b\\]?`eEUh\\N@I'D?r\\oCb;E?"
                + "<]9Qa0ng]}TJM2/MKc?0&&$A+/?22LBX>=T_@Y*Wc9#?]%i6:PUWerR\\wIr%g[AC9*M-Iq<Py~8EboDuttvDOw9DDdev;d!y(op"
                + "K7o9nUHl[=]2A)eu($zkH(`t4]q\"8[\"e*b&v[$W%b0P1[4<o;[OjZXv]7zLG5/nJG11F/\\:p4,GyU=SS>MU%OC**GR@F*_QtA"
                + "&V@Fi_VEsbjf5ABJj(JG\\R:\\n,o<I\"u-C*>&0E/pQe4Di@d4a4/EbQ!3J1*-GW5J0E]:*RHq&R[_/>~Cww;WIG`b\"3sttM4g"
                + "C=p'dUJuyPL7^ED+SxV-H7)]aKtMq7!";

    private static final String LEGACY_V8_SAMPLE =
            "WP:[\"^)'Y>[w=3]Tk!hei-cq<[%?tw$1Blk^:nXj=PTeB;cXrPn07)!";

    private static final String LEGACY_V7_SUBWAYPOINT_SAMPLE =
            "WP:v6!*VBQK)(T5\\tX_LD2P28aWe3iMK_+[l@^4|Q7*M'E&yn5i+#{bF6~~X{'VVSwJYTT\"u@ia[$5}g{4\"!";

    @Test
    void magic_prefix_is_emitted() {
        String s = WaypointCodec.encode(List.of(sampleGroup("A", "hub")));
        assertTrue(s.startsWith(WaypointCodec.MAGIC), "missing " + WaypointCodec.MAGIC + " prefix: " + s);
    }

    @Test
    void round_trip_single_group() {
        WaypointGroup before = sampleGroup("Gold Run", "dungeon_f7");
        List<WaypointGroup> decoded = WaypointCodec.decode(
                WaypointCodec.encode(List.of(before), FULL_FIDELITY));
        assertEquals(1, decoded.size());
        assertGroupsEqual(before, decoded.get(0));
    }

    @Test
    void subwaypoint_structure_roundTripsEvenWhenVisualFlagsAreExcluded() {
        WaypointGroup before = WaypointGroup.create("Subway", "dungeon_f7");
        before.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        before.add(Waypoint.at(0, 70, 0));
        before.add(Waypoint.at(5, 70, 0).withFlags(Waypoint.FLAG_HIDE_BEACON));
        before.toggleSubwaypoint(1);

        WaypointGroup decoded = WaypointCodec.decode(
                WaypointCodec.encode(List.of(before), WaypointCodec.Options.NO_NAMES)).get(0);

        assertFalse(decoded.isSubwaypoint(0));
        assertTrue(decoded.isSubwaypoint(1),
                "subwaypoint is route structure, so it survives minimal exports");
        assertFalse(decoded.get(1).hasFlag(Waypoint.FLAG_HIDE_BEACON),
                "visual flags should still be stripped by minimal exports");
    }

    @Test
    void optionalWaypointFlagsRoundTripOnlyWhenWaypointFlagsIncluded() {
        WaypointGroup before = WaypointGroup.create("Depth", "dungeon_f7");
        before.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        int optionalFlags = Waypoint.FLAG_DEPTH_CHECKED
                | Waypoint.FLAG_SKIP_ON_STAND
                | Waypoint.FLAG_SKIP_ON_INTERACT;
        before.add(Waypoint.at(0, 70, 0).withFlags(optionalFlags));

        WaypointGroup full = WaypointCodec.decode(
                WaypointCodec.encode(List.of(before), FULL_FIDELITY)).get(0);
        assertTrue(full.get(0).hasFlag(Waypoint.FLAG_DEPTH_CHECKED),
                "full-fidelity exports should preserve depth-check flags");
        assertTrue(full.get(0).hasFlag(Waypoint.FLAG_SKIP_ON_STAND),
                "full-fidelity exports should preserve stand-skip flags");
        assertTrue(full.get(0).hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT),
                "full-fidelity exports should preserve interact-skip flags");

        WaypointGroup minimal = WaypointCodec.decode(
                WaypointCodec.encode(List.of(before), WaypointCodec.Options.NO_NAMES)).get(0);
        assertFalse(minimal.get(0).hasFlag(Waypoint.FLAG_DEPTH_CHECKED),
                "minimal exports should strip optional depth-check flags");
        assertFalse(minimal.get(0).hasFlag(Waypoint.FLAG_SKIP_ON_STAND),
                "minimal exports should strip optional stand-skip flags");
        assertFalse(minimal.get(0).hasFlag(Waypoint.FLAG_SKIP_ON_INTERACT),
                "minimal exports should strip optional interact-skip flags");
    }

    @Test
    void subwaypointStyleAndPrecisePlacementRoundTripByDefault() {
        WaypointGroup before = WaypointGroup.create("Tiny Subway", "dungeon_f7");
        before.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        before.add(Waypoint.at(0, 70, 0));
        Waypoint child = Waypoint.at(5, 70, -2)
                .withFlags(Waypoint.FLAG_SUBWAYPOINT
                        | Waypoint.FLAG_SMALL_SUBWAYPOINT
                        | Waypoint.FLAG_FILLED_SUBWAYPOINT
                        | Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED
                        | Waypoint.FLAG_HIDE_BEACON)
                .withPreciseSixteenths(5 * Waypoint.PRECISE_SCALE + 3,
                        70 * Waypoint.PRECISE_SCALE + 12,
                        -2 * Waypoint.PRECISE_SCALE + 15);
        before.add(child);

        WaypointGroup decoded = WaypointCodec.decode(
                WaypointCodec.encode(List.of(before), WaypointCodec.Options.NO_NAMES)).get(0);
        Waypoint decodedChild = decoded.get(1);

        assertTrue(decodedChild.hasFlag(Waypoint.FLAG_SUBWAYPOINT),
                "subwaypoint structure must survive default exports");
        assertTrue(decodedChild.hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT),
                "small subwaypoint style must survive default exports");
        assertTrue(decodedChild.hasFlag(Waypoint.FLAG_FILLED_SUBWAYPOINT),
                "filled subwaypoint style must survive default exports");
        assertTrue(decodedChild.hasFlag(Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED),
                "hide-after-parent subwaypoint setting must survive default exports");
        assertFalse(decodedChild.hasFlag(Waypoint.FLAG_HIDE_BEACON),
                "unrelated visual flags should still be stripped by default");
        assertEquals(child.preciseX(), decodedChild.preciseX(), "preciseX");
        assertEquals(child.preciseY(), decodedChild.preciseY(), "preciseY");
        assertEquals(child.preciseZ(), decodedChild.preciseZ(), "preciseZ");
    }

    @Test
    void minimalPlainRouteExportStaysBodylessWhenNoSubwaypointsExist() {
        WaypointGroup route = WaypointGroup.create("Plain", "dungeon_f7");
        route.add(Waypoint.at(0, 70, 0));
        route.add(Waypoint.at(5, 70, 0));
        route.add(Waypoint.at(10, 70, 0));

        String encoded = WaypointCodec.encode(List.of(route), WaypointCodec.Options.NO_NAMES);
        DecodeDebug.GroupDebug debug = WaypointCodec.debugDecode(encoded).groups().get(0);

        assertEquals(0, debug.bodyBlockBytes(),
                "plain minimal routes must keep the pre-subwaypoint bodyless encoding");
        assertTrue(debug.waypoints().stream().noneMatch(DecodeDebug.WaypointDebug::extended),
                "no subwaypoints means no per-waypoint extended flag records");
    }

    @Test
    void round_trip_multiple_groups_preserves_order() {
        WaypointGroup a = sampleGroup("A", "dwarven_mines");
        WaypointGroup b = sampleGroup("B", "crystal_hollows");
        b.setDefaultRadius(7.5);
        b.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        // currentIndex is intentionally NOT part of the export contract, so we
        // don't exercise it here. The decoded b will start at index 0 regardless.

        List<WaypointGroup> decoded = WaypointCodec.decode(
                WaypointCodec.encode(List.of(a, b), FULL_FIDELITY));
        assertEquals(2, decoded.size());
        assertGroupsEqual(a, decoded.get(0));
        assertGroupsEqual(b, decoded.get(1));
    }

    @Test
    void decodes_legacy_v1_cjk_exports() throws Exception {
        WaypointGroup group = sampleGroup("legacy-v1", "dungeon_f7");
        String legacy = legacyV1Export(List.of(group), FULL_FIDELITY, "Codec V1");

        String body = legacy.substring(WaypointCodec.MAGIC.length());
        assertTrue(CjkBase16384.isValidBody(body), "legacy fixture must use the v1 CJK body alphabet");
        assertEquals("Codec V1", WaypointCodec.peekLabel(legacy).orElseThrow());

        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(legacy);
        DecodeDebug debug = WaypointCodec.debugDecode(legacy);

        assertEquals("Codec V1", decoded.label());
        assertEquals(1, decoded.groups().size());
        assertGroupsEqual(group, decoded.groups().get(0));
        assertEquals(1, debug.version());
        assertEquals("CJK base-16384", debug.textEncoding());
    }

    @Test
    void legacy_cjk_decoder_rejects_bodyless_nonzero_padding() {
        String invalid = String.valueOf((char) (CjkBase16384.ALPHABET_BASE + 1));

        assertThrows(IllegalArgumentException.class, () -> CjkBase16384.decode(invalid));
    }

    @Test
    void decodes_legacy_v2_base85_exports() {
        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(LEGACY_V2_SAMPLE);
        assertEquals("Codec V2", decoded.label());
        assertEquals(4, decoded.groups().size());

        assertTrue(hasGroup(decoded.groups(), "hawcammang", "galatea", 14));
        assertTrue(hasGroup(decoded.groups(), "HideOnLeaf", "galatea", 50));

        WaypointGroup last = decoded.groups().get(3);
        assertEquals("HideOnLeaf", last.name());
        assertEquals("galatea", last.zoneId());
        assertEquals(50, last.size());

        DecodeDebug debug = WaypointCodec.debugDecode(LEGACY_V2_SAMPLE);
        assertEquals(2, debug.version());
        assertEquals("ASCII base-85", debug.textEncoding());
    }

    @Test
    void decodes_legacy_v3_base93_export_from_1_4_1_release() {
        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(LEGACY_V3_RELEASE_SAMPLE);
        assertEquals("Codec V3", decoded.label());
        assertEquals(4, decoded.groups().size());

        assertTrue(hasGroup(decoded.groups(), "hawcammang", "galatea", 14));
        assertTrue(hasGroup(decoded.groups(), "HideOnLeaf", "galatea", 50));

        DecodeDebug debug = WaypointCodec.debugDecode(LEGACY_V3_RELEASE_SAMPLE);
        assertEquals(3, debug.version());
        assertEquals("ASCII base-93 stream", debug.textEncoding());
    }

    @Test
    void decodes_fixed_legacy_v8_crc_payload_with_legacy_dictionary() {
        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(LEGACY_V8_SAMPLE);
        DecodeDebug debug = WaypointCodec.debugDecode(LEGACY_V8_SAMPLE);

        assertEquals(8, debug.version());
        assertEquals("ASCII base-91 stream + CRC-32", debug.textEncoding());
        assertEquals(1, decoded.groups().size());
        WaypointGroup group = decoded.groups().get(0);
        assertEquals("legacy-v8", group.name());
        assertEquals("mining_3", group.zoneId());
        assertEquals(2, group.size());
        assertEquals("1", group.get(0).name());
        assertEquals(0x123456, group.get(0).color());
        assertEquals(12, group.get(1).x());
        assertEquals(0xABCDEF, group.get(1).color());
    }

    @Test
    void decodes_fixed_legacy_v7_subwaypoint_payload() {
        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(LEGACY_V7_SUBWAYPOINT_SAMPLE);
        DecodeDebug debug = WaypointCodec.debugDecode(LEGACY_V7_SUBWAYPOINT_SAMPLE);

        assertEquals(WaypointCodec.LEGACY_V7_WIRE_VERSION, debug.version());
        assertEquals(1, decoded.groups().size());
        WaypointGroup group = decoded.groups().get(0);
        assertEquals("v7 subway", group.name());
        assertEquals("legacy_v7_zone", group.zoneId());
        assertEquals(4.0, group.defaultRadius());
        assertEquals(2, group.size());
        Waypoint main = group.get(0);
        assertEquals(10, main.x());
        assertEquals(70, main.y());
        assertEquals(-20, main.z());
        assertEquals("main", main.name());
        assertEquals(0x112233, main.color());

        Waypoint child = group.get(1);
        assertEquals("child", child.name());
        assertEquals(0x445566, child.color());
        assertEquals(Waypoint.FLAG_SUBWAYPOINT
                | Waypoint.FLAG_SMALL_SUBWAYPOINT
                | Waypoint.FLAG_FILLED_SUBWAYPOINT, child.flags());
        assertEquals(5.0, child.customRadius());
        assertEquals(177, child.preciseX());
        assertEquals(1122, child.preciseY());
        assertEquals(-317, child.preciseZ());
    }

    @Test
    void rejects_unbounded_group_count_payloads() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(WaypointCodec.LEGACY_V7_WIRE_VERSION);
        WaypointCodec.writeVarint(out, 0);
        WaypointCodec.writeVarint(out, WaypointImporter.MAX_GROUPS_PER_IMPORT + 1);
        out.flush();

        String encoded = WaypointCodec.MAGIC
                + WaypointCodec.escapeHypixelEmotes(AsciiStreamCodec.encode(deflateForTest(raw.toByteArray())));

        assertFalse(WaypointCodec.isValidCodec(encoded));
        try {
            WaypointCodec.decodeFull(encoded);
            fail("decodeFull should reject oversized group counts");
        } catch (IllegalArgumentException expected) {
            // Expected: malformed untrusted payloads surface as decode failures.
        }
    }

    @Test
    void boundsUntrustedWireRadiusBeforeItReachesTheModel() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(WaypointCodec.LEGACY_V7_WIRE_VERSION | 0x40);
        WaypointCodec.writeVarint(out, 0);
        WaypointCodec.writeVarint(out, 3);
        out.writeBytes("hub");
        out.writeByte(0x01 | 0x08);
        WaypointCodec.writeVarint(out, Integer.MAX_VALUE);
        WaypointCodec.writeVarint(out, 0);
        out.flush();

        String encoded = WaypointCodec.MAGIC
                + WaypointCodec.escapeHypixelEmotes(AsciiStreamCodec.encode(deflateForTest(raw.toByteArray())));
        WaypointCodec.Decoded decoded = WaypointCodec.decodeFull(encoded);

        assertEquals(Waypoint.MAX_REACH_RADIUS, decoded.groups().get(0).defaultRadius());
        assertEquals(Waypoint.MAX_REACH_RADIUS,
                WaypointCodec.debugDecode(encoded).groups().get(0).defaultRadius());
    }

    @Test
    void rejects_varints_that_overflow_signed_ints() {
        byte[] overflowing = {
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                0x0F
        };

        try {
            WaypointCodec.readVarint(new DataInputStream(new ByteArrayInputStream(overflowing)));
            fail("readVarint should reject overflowing five-byte values");
        } catch (IOException expected) {
            // Expected: values above Integer.MAX_VALUE are not valid wire ints.
        }
    }

    @Test
    void rejects_unsafe_group_and_waypoint_names_in_current_exports() {
        String groupName = "\u00A7cBad\nGroup";
        String waypointName = "\u00A7aBad\tPoint";
        WaypointGroup group = WaypointGroup.create(groupName, "hub");
        group.add(new Waypoint(1, 70, 2, waypointName, Waypoint.DEFAULT_COLOR, 0, 0));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.encode(List.of(group), FULL_FIDELITY));

        assertTrue(error.getMessage().contains("unsafe"));
    }

    @Test
    void current_exports_preserve_safe_names_longer_than_the_label_limit() {
        String groupName = "group-" + "g".repeat(80);
        String waypointName = "waypoint-" + "w".repeat(80) + "-\uD83D\uDE80";
        WaypointGroup group = WaypointGroup.create(groupName, "hub");
        group.add(new Waypoint(1, 70, 2, waypointName, Waypoint.DEFAULT_COLOR, 0, 0));

        WaypointGroup decoded = WaypointCodec.decode(
                WaypointCodec.encode(List.of(group), FULL_FIDELITY)).get(0);

        assertEquals(groupName, decoded.name());
        assertEquals(waypointName, decoded.get(0).name());
    }

    @Test
    void rejects_missing_magic() {
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode("not-a-waypointer-export"));
    }

    @Test
    void rejects_garbled_payload() {
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(WaypointCodec.MAGIC + "ZZZZZZZ"));
    }

    @Test
    void current_payload_rejects_binary_mutation_without_a_matching_checksum() throws Exception {
        String encoded = WaypointCodec.encode(List.of(sampleGroup("checksum", "hub")), FULL_FIDELITY);
        byte[] framed = currentFramedBody(encoded);
        framed[1] ^= 1;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeCompressedForTest(deflateV9ForTest(framed))));

        assertTrue(error.getMessage().contains("CRC-32 mismatch"));
    }

    @Test
    void current_payload_rejects_a_single_character_mutation() {
        String encoded = WaypointCodec.encode(List.of(sampleGroup("character-mutation", "hub")), FULL_FIDELITY);
        int index = encoded.length() / 2;
        char replacement = encoded.charAt(index) == '!' ? '"' : '!';
        String mutated = encoded.substring(0, index) + replacement + encoded.substring(index + 1);

        assertThrows(IllegalArgumentException.class, () -> WaypointCodec.decode(mutated));
        assertFalse(WaypointCodec.isValidCodec(mutated));
    }

    @Test
    void current_payload_rejects_trailing_compressed_bytes() throws Exception {
        String encoded = WaypointCodec.encode(List.of(sampleGroup("compressed-tail", "hub")), FULL_FIDELITY);
        byte[] compressed = currentCompressedBody(encoded);
        byte[] withTail = Arrays.copyOf(compressed, compressed.length + 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeCompressedForTest(withTail)));

        assertTrue(error.getMessage().contains("trailing compressed bytes"));
    }

    @Test
    void current_payload_rejects_trailing_binary_body_bytes_even_with_a_valid_checksum() throws Exception {
        String encoded = WaypointCodec.encode(List.of(sampleGroup("binary-tail", "hub")), FULL_FIDELITY);
        byte[] body = stripCurrentChecksum(currentFramedBody(encoded));
        byte[] withTail = Arrays.copyOf(body, body.length + 1);
        withTail[body.length] = 42;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encodeCompressedForTest(
                        deflateV9ForTest(appendChecksumForTest(withTail)))));

        assertTrue(error.getMessage().contains("trailing binary body bytes"));
    }

    @Test
    void rejects_deflate_payloads_over_the_inflated_size_limit() throws Exception {
        byte[] bomb = new byte[WaypointCodec.MAX_INFLATED_BYTES + 1];
        bomb[0] = (byte) WaypointCodec.WIRE_VERSION;
        String encoded = encodeCompressedForTest(deflateV9ForTest(bomb));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(encoded));

        assertTrue(error.getMessage().contains("inflated payload exceeds"));
    }

    @Test
    void peek_label_only_reads_a_bounded_payload_prefix() {
        String encoded = WaypointCodec.encode(List.of(sampleGroup("peek", "hub")),
                WaypointCodec.Options.builder().label("Trusted label").build());
        String hostileSuffix = "!".repeat(1_000_000);

        assertEquals("Trusted label", WaypointCodec.peekLabel(encoded + hostileSuffix).orElseThrow());
    }

    @Test
    void rejects_legacy_prefixes() {
        // Early prototypes tried WPTR1:, WP2:, and WP3: as the magic. None of them
        // shipped. The current format uses a version-in-header scheme so the magic
        // itself is just WP:; anything else must be rejected outright -- no silent
        // fallback, no misleading "unsupported version" error from deep inside
        // the decoder.
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode("WPTR1:AAAABBBBCCCCDDDD"));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode("WP2:AAAABBBBCCCCDDDD"));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode("WP3:AAAABBBBCCCCDDDD"));
    }

    @Test
    void empty_group_list_round_trips() {
        List<WaypointGroup> decoded = WaypointCodec.decode(WaypointCodec.encode(List.of()));
        assertTrue(decoded.isEmpty());
    }

    @Test
    void fuzz_random_routes_round_trip() {
        // Progress (currentIndex) and enabled state are deliberately NOT exported,
        // so we leave both at the WaypointGroup defaults on the source side.
        // Exercising them here would just prove the decoder overrides them back
        // to their defaults, which is already covered by the dedicated test.
        Random r = new Random(0xC0FFEE);
        for (int trial = 0; trial < 50; trial++) {
            List<WaypointGroup> groups = new ArrayList<>();
            int nGroups = 1 + r.nextInt(4);
            for (int gi = 0; gi < nGroups; gi++) {
                WaypointGroup g = WaypointGroup.create("group-" + trial + "-" + gi, "zone-" + r.nextInt(6));
                g.setDefaultRadius(1.0 + r.nextInt(20));
                g.setGradientMode(r.nextBoolean() ? WaypointGroup.GradientMode.AUTO
                        : WaypointGroup.GradientMode.MANUAL);

                int nPts = r.nextInt(30);
                for (int i = 0; i < nPts; i++) {
                    int x = r.nextInt(2000) - 1000;
                    int y = r.nextInt(320);
                    int z = r.nextInt(2000) - 1000;
                    String name = r.nextBoolean() ? "p" + i : "";
                    int color = r.nextInt(0xFFFFFF);
                    int flags = r.nextInt(0x10);
                    double radius = r.nextBoolean() ? 0 : (0.5 + r.nextInt(50));
                    g.add(new Waypoint(x, y, z, name, color, flags, radius));
                }
                groups.add(g);
            }

            String encoded = WaypointCodec.encode(groups, FULL_FIDELITY);
            List<WaypointGroup> decoded = WaypointCodec.decode(encoded);
            assertEquals(groups.size(), decoded.size(), "trial " + trial + " group count");
            for (int i = 0; i < groups.size(); i++) {
                assertGroupsEqual(groups.get(i), decoded.get(i));
            }
        }
    }

    @Test
    void packed_payload_is_small() {
        WaypointGroup g = WaypointGroup.create("Gold Run", "dungeon_f7");
        for (int i = 0; i < 40; i++) g.add(Waypoint.at(i * 3, 70, i * 2));
        String s = WaypointCodec.encode(List.of(g));
        // Density regression guard. JSON for the same 40-point route weighs
        // several kilobytes; the codec should stay well under a quarter of
        // that on structured data. Threshold is 400 chars (= 400 wire
        // bytes); real output on this fixture is well below that, so this
        // has ~20% slack for dictionary / DEFLATE fluctuations.
        assertTrue(s.length() < 400, "expected packed export < 400 chars, got " + s.length() + ": " + s);
    }

    @Test
    void twenty_named_waypoints_fit_in_command_packet() {
        // The native text codec uses one UTF-8 byte per char, so char
        // count equals its wire-byte count. The real failure mode is Hypixel's
        // 256-byte ServerboundChatCommandPacket cap: exceeding it disconnects
        // the sender. A 20-waypoint named route is the "reasonable share"
        // baseline we commit to supporting; with a short command prefix like
        // "/pc " (3 bytes on the wire) the body must stay under 253 bytes.
        //
        // Picking 20 points rather than the old 50 reflects the 256-byte
        // command-packet cap -- 50 named waypoints fundamentally can't fit
        // any chat command under any encoding, so the old test was
        // paper-over-a-bug.
        WaypointGroup g = WaypointGroup.create("Big Run", "dungeon_f7");
        for (int i = 0; i < 20; i++) {
            g.add(new Waypoint(100 + i * 3, 70 + (i % 5), 200 + i * 2,
                    "pt" + i, Waypoint.DEFAULT_COLOR, 0, 0));
        }
        String s = WaypointCodec.encode(List.of(g));
        int commandWireBytes = "pc ".length() + s.length();
        assertTrue(commandWireBytes <= 256,
                "20 named waypoints + /pc prefix must fit in 256 wire bytes; got "
                        + commandWireBytes + " (body=" + s.length() + "): " + s);
    }

    @Test
    void body_chars_are_all_in_alphabet() {
        // Every non-magic character must be a valid alphabet character so chat
        // paste, MC's chat validator, and the CodecScanner's word-boundary
        // extractor all accept it.
        WaypointGroup g = sampleGroup("range check", "dungeon_f7");
        String s = WaypointCodec.encode(List.of(g));
        String body = s.substring(WaypointCodec.MAGIC.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            assertTrue(dev.ethan.waypointer.codec.AsciiStreamCodec.isAlphabetChar(c),
                    "body char at " + i + " out of alphabet: '" + c
                            + "' (0x" + Integer.toHexString(c).toUpperCase() + ")");
        }
    }

    @Test
    void body_never_contains_period() {
        // Hypixel's advertising filter flags URL-shaped substrings and will
        // disconnect the sender. '.' is the single most URL-shaped character
        // we could ship (anything resembling "host.tld" trips the filter),
        // so the alphabet explicitly omits it. This test is a regression
        // guard: if someone reintroduces '.', shared routes start bouncing
        // off the ad filter and this fails loudly in CI.
        for (int i = 0; i < 20; i++) {
            WaypointGroup g = WaypointGroup.create("fuzz " + i, "dungeon_f7");
            for (int j = 0; j < 8 + i; j++) {
                g.add(new Waypoint(100 + j * (i + 1), 70 + j, 200 + j * 2,
                        "n" + j, 0x112233 + j, 0, 0));
            }
            String s = WaypointCodec.encode(List.of(g));
            assertFalse(s.contains("."),
                    "export " + i + " contains '.' which flags Hypixel's ad filter: " + s);
        }
    }

    @Test
    void current_wire_version_keeps_hypixel_emote_escape() {
        // v9 keeps the v4+ chat escape and v8 integrity checks.
        assertEquals(9, WaypointCodec.WIRE_VERSION);

        String raw = "abc<3defo/ghi~~jkl<~3mno~/pqr";
        String escaped = WaypointCodec.escapeHypixelEmotes(raw);

        assertFalse(escaped.contains("<3"), "escaped body must not trigger heart emotes: " + escaped);
        assertFalse(escaped.contains("o/"), "escaped body must not trigger wave emotes: " + escaped);
        assertEquals(raw, WaypointCodec.unescapeHypixelEmotes(escaped));
    }

    @Test
    void codec_doc_names_current_wire_version_and_writer() throws Exception {
        String docs = Files.readString(Path.of("CODEC.md"), StandardCharsets.UTF_8);
        String currentVersion = "v" + WaypointCodec.WIRE_VERSION;

        assertTrue(docs.contains("Current wire version: **" + WaypointCodec.WIRE_VERSION + "**."),
                "CODEC.md should name the live wire version");
        assertTrue(docs.contains("- " + currentVersion + ": current writer"),
                "CODEC.md decoder list should identify the live writer");
        assertTrue(docs.contains("The encoder only writes " + currentVersion + "."),
                "CODEC.md should not describe an older writer as current");
    }

    @Test
    void exports_never_contain_hypixel_mvp_emote_triggers() {
        for (int i = 0; i < 40; i++) {
            WaypointGroup g = WaypointGroup.create("emote fuzz " + i, "dungeon_f7");
            for (int j = 0; j < 5 + i; j++) {
                g.add(new Waypoint(50 + i * 7 + j, 70 + (j % 6), 120 + j * 3,
                        "p" + i + "-" + j, Waypoint.DEFAULT_COLOR, 0, 0));
            }

            String s = WaypointCodec.encode(List.of(g));
            assertFalse(s.contains("<3"),
                    "export " + i + " contains '<3' which Hypixel rewrites to an emote: " + s);
            assertFalse(s.contains("o/"),
                    "export " + i + " contains 'o/' which Hypixel rewrites to an emote: " + s);
            assertFalse(WaypointCodec.decode(s).isEmpty(), "escaped export must still decode");
        }
    }

    @Test
    void current_exports_never_contain_backticks() {
        for (int i = 0; i < 40; i++) {
            WaypointGroup g = WaypointGroup.create("backtick fuzz " + i, "dungeon_f7");
            for (int j = 0; j < 5 + i; j++) {
                g.add(new Waypoint(80 + i * 9 + j, 64 + (j % 8), 180 + j * 4,
                        "p" + i + "-" + j, Waypoint.DEFAULT_COLOR, 0, 0));
            }

            String s = WaypointCodec.encode(List.of(g));
            assertFalse(s.contains("`"), "export must not contain backticks: " + s);
            assertFalse(WaypointCodec.decode(s).isEmpty(), "backtick-free export must still decode");
        }
    }

    @Test
    void current_exports_never_contain_commas() {
        for (int i = 0; i < 40; i++) {
            WaypointGroup g = WaypointGroup.create("comma fuzz " + i, "dungeon_f7");
            for (int j = 0; j < 5 + i; j++) {
                g.add(new Waypoint(40 + i * 5 + j, 64 + (j % 6), 140 + j * 7,
                        "p" + i + "-" + j, Waypoint.DEFAULT_COLOR, 0, 0));
            }

            String s = WaypointCodec.encode(List.of(g));
            assertFalse(s.contains(","), "export must not contain commas: " + s);
            assertFalse(WaypointCodec.decode(s).isEmpty(), "comma-free export must still decode");
        }
    }

    @Test
    void decode_still_accepts_legacy_v4_payloads_with_commas() throws Exception {
        WaypointGroup g = WaypointGroup.create("legacy v4", "dungeon_f7");
        for (int i = 0; i < 12; i++) {
            g.add(new Waypoint(100 + i, 70, 200 + i * 2, "", Waypoint.DEFAULT_COLOR, 0, 0));
        }

        String legacyV4 = asLegacyV4WithComma(WaypointCodec.encode(List.of(g),
                WaypointCodec.Options.WITH_NAMES, WaypointCodec.PackingMode.FORCE_VECTOR));

        WaypointGroup decoded = WaypointCodec.decode(legacyV4).get(0);
        assertEquals(g.size(), decoded.size());
        for (int i = 0; i < g.size(); i++) {
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).y(), decoded.get(i).y(), "y@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
        }
    }

    @Test
    void decode_still_accepts_legacy_v3_payloads() throws Exception {
        WaypointGroup g = WaypointGroup.create("legacy v3", "dungeon_f7");
        for (int i = 0; i < 12; i++) {
            g.add(new Waypoint(100 + i, 70, 200 + i * 2, "", Waypoint.DEFAULT_COLOR, 0, 0));
        }

        String legacyV3 = asLegacyV3(WaypointCodec.encode(List.of(g),
                WaypointCodec.Options.WITH_NAMES, WaypointCodec.PackingMode.FORCE_VECTOR));

        WaypointGroup decoded = WaypointCodec.decode(legacyV3).get(0);
        assertEquals(g.size(), decoded.size());
        for (int i = 0; i < g.size(); i++) {
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).y(), decoded.get(i).y(), "y@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
        }
    }

    @Test
    void packed_export_is_chat_paste_safe() {
        // The whole point of the codec string is that it survives being retyped/pasted
        // into a Minecraft chat box, which collapses runs of spaces. A leaked space
        // character here would silently truncate shared routes.
        WaypointGroup g = sampleGroup("space test", "zone");
        String s = WaypointCodec.encode(List.of(g));
        assertFalse(s.contains(" "), "codec output must contain zero whitespace; got: " + s);
        assertFalse(s.contains("\t"), "codec output must contain no tabs");
        assertFalse(s.contains("\n"), "codec output must contain no newlines");
    }

    @Test
    void round_trip_preserves_load_mode() {
        WaypointGroup g = sampleGroup("seq", "zone");
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g))).get(0);
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, decoded.loadMode(),
                "sequence load-mode must survive export/import so shared routes keep their behavior");
    }

    @Test
    void round_trip_preserves_default_radius() {
        WaypointGroup g = sampleGroup("wide radius", "zone");
        g.setDefaultRadius(12.5);
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g))).get(0);
        assertEquals(12.5, decoded.defaultRadius(), 1e-6);
    }

    @Test
    void no_names_export_omits_names_and_still_decodes_geometry() {
        // When sharing a "clean" route you don't want teammate nicknames / dev labels
        // leaking. NO_NAMES strips names but must still round-trip coords.
        WaypointGroup g = WaypointGroup.create("Secret Route", "dungeon_f7");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(new Waypoint(10, 70, 20, "alpha-personal",    0xAABBCC, 0, 0));
        g.add(new Waypoint(11, 71, 22, "bravo-personal",    0xDDEEFF, 0, 0));
        g.add(new Waypoint(12, 72, 24, "charlie-personal",  0x001122, 0, 0));

        String stripped = WaypointCodec.encode(List.of(g), WaypointCodec.Options.NO_NAMES);
        // Name strings themselves shouldn't be in the payload. We can't see through
        // deflate+text encoding directly, but we can confirm the output is smaller
        // than a names export, and that the decoded waypoints come back nameless.
        String withNames = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES);
        assertTrue(stripped.length() < withNames.length(),
                "NO_NAMES export should be shorter than WITH_NAMES; stripped="
                        + stripped.length() + " named=" + withNames.length());

        WaypointGroup decoded = WaypointCodec.decode(stripped).get(0);
        assertEquals(3, decoded.size());
        for (int i = 0; i < decoded.size(); i++) {
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).y(), decoded.get(i).y(), "y@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
            assertEquals("", decoded.get(i).name(), "name must be stripped at index " + i);
        }
    }

    @Test
    void exports_always_reset_session_state_on_import() {
        // Exports are for sharing a route, not a session. The codec drops
        // per-group enabled state and currentIndex unconditionally so imports
        // always start fresh (enabled = true, index = 0), regardless of what
        // the sender's group looked like when they hit Export.
        WaypointGroup g = sampleGroup("Boss Route", "dungeon_f7");
        g.setCurrentIndex(2);
        g.setEnabled(false);

        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g))).get(0);
        assertEquals(0, decoded.currentIndex(),
                "exports must reset progress so recipients don't inherit the sender's position");
        assertTrue(decoded.enabled(),
                "exports default enabled=true so a shared route activates on import");
    }

    @Test
    void round_trip_preserves_every_shareable_metadata_field() {
        // Every field that defines the ROUTE (not the session) must survive a
        // round trip. Enabled-state and currentIndex are explicitly not in this
        // set -- see exports_always_reset_session_state_on_import.
        WaypointGroup g = WaypointGroup.create("F7 Terminals", "dungeon_f7");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setDefaultRadius(6.0);
        g.add(new Waypoint(100, 64, 200, "T1", 0x11AACC, Waypoint.FLAG_LOCKED_COLOR, 0));
        g.add(new Waypoint(110, 64, 210, "T2", 0x22BBCD,
                Waypoint.FLAG_LOCKED_COLOR | Waypoint.FLAG_HIDE_BEACON, 4.5));
        g.add(new Waypoint(120, 64, 220, "",   0x33CCDE, 0, 0));

        WaypointGroup d = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), FULL_FIDELITY)).get(0);
        assertEquals("F7 Terminals", d.name());
        assertEquals("dungeon_f7", d.zoneId());
        assertEquals(WaypointGroup.GradientMode.MANUAL, d.gradientMode());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, d.loadMode());
        assertEquals(6.0, d.defaultRadius(), 1e-6);
        assertTrue(d.enabled(), "import always activates the group");
        assertEquals(0, d.currentIndex(), "import always starts fresh");
        assertEquals(3, d.size());

        assertEquals("T1", d.get(0).name());
        assertEquals(0x11AACC, d.get(0).color());
        assertEquals(Waypoint.FLAG_LOCKED_COLOR, d.get(0).flags());

        assertEquals("T2", d.get(1).name());
        assertEquals(4.5, d.get(1).customRadius(), 1e-6);
        assertEquals(Waypoint.FLAG_LOCKED_COLOR | Waypoint.FLAG_HIDE_BEACON, d.get(1).flags());

        assertEquals("", d.get(2).name());
    }

    @Test
    void isCodecString_recognizes_current_magic() {
        assertTrue(WaypointCodec.isCodecString(WaypointCodec.MAGIC + "abcd0"));
        assertTrue(WaypointCodec.isCodecString("   " + WaypointCodec.MAGIC + "xyz0   "));
        assertFalse(WaypointCodec.isCodecString("random text WP no colon"));
        // Historical prefix shapes must not accidentally qualify -- none of them
        // ever shipped, but they all survived early prototyping and might crop up
        // in other people's notes or blog posts. Version handling now lives in the
        // header byte, not the magic, so there is exactly one valid prefix.
        assertFalse(WaypointCodec.isCodecString("WPTR1:abc"));
        assertFalse(WaypointCodec.isCodecString("WP2:abc"));
        assertFalse(WaypointCodec.isCodecString("WP3:abc"));
        assertFalse(WaypointCodec.isCodecString(null));
        assertFalse(WaypointCodec.isCodecString(""));
    }

    // --- granular Options toggles ----------------------------------------------------------

    @Test
    void include_colors_false_strips_per_waypoint_colors_to_default() {
        // Sender opts out of colors; recipient must see DEFAULT_COLOR everywhere
        // even though the source had distinct values per point. This is the
        // "share a route, recolor it on my end" workflow.
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(new Waypoint(0, 70, 0, "", 0xFF0000, 0, 0));
        g.add(new Waypoint(5, 70, 5, "", 0x00FF00, 0, 0));

        WaypointCodec.Options stripped = WaypointCodec.Options.builder()
                .includeColors(false).build();
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g), stripped)).get(0);
        for (int i = 0; i < decoded.size(); i++) {
            assertEquals(Waypoint.DEFAULT_COLOR, decoded.get(i).color(),
                    "color must reset to default at index " + i);
        }
    }

    @Test
    void include_colors_false_does_not_restore_auto_gradient_colors() {
        WaypointGroup g = WaypointGroup.create("auto gradient", "z");
        g.setGradientMode(WaypointGroup.GradientMode.AUTO);
        for (int i = 0; i < 5; i++) {
            g.add(Waypoint.at(i, 70, i));
        }

        WaypointCodec.Options stripped = WaypointCodec.Options.builder()
                .includeColors(false)
                .includeGroupMeta(true)
                .build();
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g), stripped)).get(0);

        assertEquals(WaypointGroup.GradientMode.MANUAL, decoded.gradientMode(),
                "stripping colors must also suppress AUTO gradient restoration");
        for (int i = 0; i < decoded.size(); i++) {
            assertEquals(Waypoint.DEFAULT_COLOR, decoded.get(i).color(),
                    "auto gradient must not recolor stripped export at index " + i);
        }
    }

    @Test
    void include_radii_false_strips_custom_per_waypoint_radius() {
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, 0, 8.5));
        g.add(new Waypoint(1, 70, 1, "", Waypoint.DEFAULT_COLOR, 0, 12.0));

        WaypointCodec.Options stripped = WaypointCodec.Options.builder()
                .includeRadii(false).build();
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g), stripped)).get(0);
        for (int i = 0; i < decoded.size(); i++) {
            assertEquals(0.0, decoded.get(i).customRadius(), 1e-6,
                    "custom radius must clear at index " + i);
        }
    }

    @Test
    void include_waypoint_flags_false_strips_per_waypoint_flag_bits() {
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR,
                Waypoint.FLAG_LOCKED_COLOR | Waypoint.FLAG_HIDE_BEACON, 0));

        WaypointCodec.Options stripped = WaypointCodec.Options.builder()
                .includeColors(false)
                .includeWaypointFlags(false)
                .build();
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g), stripped)).get(0);
        assertEquals(0, decoded.get(0).flags(),
                "flag bits must reset to 0 when includeWaypointFlags is off");
    }

    @Test
    void include_group_meta_false_strips_gradient_load_mode_and_default_radius() {
        // Group metadata stripping should make the recipient see plain defaults.
        // With colors stripped, that means no AUTO gradient restoration because
        // AUTO would recolor the default-colored import.
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setDefaultRadius(7.5);
        g.add(new Waypoint(0, 70, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));

        WaypointCodec.Options stripped = WaypointCodec.Options.builder()
                .includeGroupMeta(false).build();
        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(g), stripped)).get(0);
        assertEquals(WaypointGroup.GradientMode.MANUAL, decoded.gradientMode(),
                "colorless metadata stripping must not restore AUTO gradient");
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, decoded.loadMode(),
                "load mode must default to SEQUENCE when group meta is dropped");
        assertEquals(3.0, decoded.defaultRadius(), 1e-6,
                "default radius must reset to 3.0 when group meta is dropped");
    }

    @Test
    void granular_strip_produces_smaller_payload_than_full_export() {
        // The whole point of the toggles: stripping optional fields must yield
        // a byte-smaller payload. If a future encoder regression silently
        // included the data anyway, this would catch it.
        WaypointGroup g = WaypointGroup.create("R", "z");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setDefaultRadius(8.0);
        for (int i = 0; i < 10; i++) {
            g.add(new Waypoint(i, 70 + i, i * 2, "p" + i,
                    0xAA0000 | i, Waypoint.FLAG_LOCKED_COLOR, 4.5));
        }

        int full = WaypointCodec.encode(List.of(g),
                WaypointCodec.Options.builder()
                        .includeNames(true).includeColors(true).includeRadii(true)
                        .includeWaypointFlags(true).includeGroupMeta(true).build()).length();
        int stripped = WaypointCodec.encode(List.of(g),
                WaypointCodec.Options.builder()
                        .includeNames(false).includeColors(false).includeRadii(false)
                        .includeWaypointFlags(false).includeGroupMeta(false).build()).length();
        assertTrue(stripped < full,
                "all-toggles-off must beat all-toggles-on; stripped=" + stripped + " full=" + full);
    }

    @Test
    void no_optional_waypoint_fields_omit_the_body_block() {
        WaypointGroup g = WaypointGroup.create("plain", "dungeon_f7");
        for (int i = 0; i < 20; i++) {
            g.add(new Waypoint(100 + i, 70, 200 + i, "", Waypoint.DEFAULT_COLOR, 0, 0));
        }

        DecodeDebug.GroupDebug debug = WaypointCodec.debugDecode(
                WaypointCodec.encode(List.of(g), WaypointCodec.Options.NO_NAMES)).groups().get(0);

        assertEquals(0, debug.bodyBlockBytes(),
                "a route with no optional waypoint fields should not spend one zero flag byte per point");
        assertEquals(20, debug.waypoints().size(),
                "debug still materializes default waypoint bodies for inspection");
    }

    @Test
    void unique_waypoint_names_are_inlined_instead_of_pooled() {
        WaypointGroup g = WaypointGroup.create("route", "dungeon_f7");
        g.add(new Waypoint(0, 70, 0, "alpha", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(1, 70, 1, "bravo", Waypoint.DEFAULT_COLOR, 0, 0));

        DecodeDebug debug = WaypointCodec.debugDecode(
                WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES));

        assertFalse(debug.stringPool().contains("alpha"));
        assertFalse(debug.stringPool().contains("bravo"));
        assertEquals("alpha", debug.decodedGroups().get(0).get(0).name());
        assertEquals("bravo", debug.decodedGroups().get(0).get(1).name());
    }

    @Test
    void repeated_waypoint_names_still_use_the_string_pool() {
        WaypointGroup g = WaypointGroup.create("route", "dungeon_f7");
        g.add(new Waypoint(0, 70, 0, "Terminal", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(1, 70, 1, "Terminal", Waypoint.DEFAULT_COLOR, 0, 0));

        DecodeDebug debug = WaypointCodec.debugDecode(
                WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES));

        assertTrue(debug.stringPool().contains("Terminal"),
                "repeated names should still be pooled so they are stored once");
        assertEquals("Terminal", debug.decodedGroups().get(0).get(0).name());
        assertEquals("Terminal", debug.decodedGroups().get(0).get(1).name());
    }

    @Test
    void known_zone_ids_use_the_built_in_dictionary() {
        WaypointGroup g = WaypointGroup.create("route", "dungeon_f7");
        g.add(Waypoint.at(0, 70, 0));

        DecodeDebug debug = WaypointCodec.debugDecode(
                WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES));

        assertFalse(debug.stringPool().contains("dungeon_f7"),
                "known zones should be encoded as dictionary refs, not pool strings");
        assertEquals("dungeon_f7", debug.decodedGroups().get(0).zoneId());
    }

    @Test
    void unknown_zone_ids_still_round_trip_through_the_string_pool() {
        WaypointGroup g = WaypointGroup.create("route", "custom_event_zone");
        g.add(Waypoint.at(0, 70, 0));

        DecodeDebug debug = WaypointCodec.debugDecode(
                WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES));

        assertTrue(debug.stringPool().contains("custom_event_zone"),
                "unknown zones need the pool so custom/user-created zones survive");
        assertEquals("custom_event_zone", debug.decodedGroups().get(0).zoneId());
    }

    @Test
    void label_sanitization_strips_section_signs_and_caps_visible_chars() {
        // sanitizeLabel must drop section signs (formatting injection) and
        // truncate to MAX_LABEL_CHARS so an encoded payload never carries a
        // hostile or oversized title. Both rules are enforced at the Options
        // boundary so neither encoder nor decoder has to re-validate.
        String hostile = "\u00A7c" + "Pwned " + "\u00A74".repeat(5);
        String sanitized = WaypointCodec.Options.sanitizeLabel(hostile);
        assertFalse(sanitized.contains("\u00A7"),
                "section signs must be stripped, got: " + sanitized);

        String giant = "x".repeat(1000);
        String capped = WaypointCodec.Options.sanitizeLabel(giant);
        assertTrue(capped.length() <= WaypointCodec.Options.MAX_LABEL_CHARS,
                "label must be capped at MAX_LABEL_CHARS; got " + capped.length());

        String crossingPair = WaypointCodec.Options.sanitizeLabel("a".repeat(63) + "\uD83D\uDE80");
        assertEquals("a".repeat(63), crossingPair,
                "truncation must drop, not split, a surrogate pair at the boundary");
        String fittingPair = WaypointCodec.Options.sanitizeLabel("a".repeat(62) + "\uD83D\uDE80");
        assertTrue(fittingPair.endsWith("\uD83D\uDE80"));
    }

    // --- coord packing mode selection ---------------------------------------------------------

    @Test
    void auto_mode_beats_absolute_on_yoyo_route_inside_fixed_bounds() {
        // Yo-yo path entirely within the FIXED_COMPACT window ([-2048, +2047]
        // on x/z). Delta exacts big jumps; absolute-varint spends ~2 bytes per
        // coord; fixed-compact spends 33 bits; fit-compact spends less when
        // the span is tight. AUTO must pick whichever ends up smallest.
        WaypointGroup g = WaypointGroup.create("yoyo", "z");
        g.add(new Waypoint(0, 0, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(2000, 100, 2000, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(0, 0, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));

        int vector   = forcedLen(g, WaypointCodec.PackingMode.FORCE_VECTOR);
        int absolute = forcedLen(g, WaypointCodec.PackingMode.FORCE_ABSOLUTE);
        int fixed    = forcedLen(g, WaypointCodec.PackingMode.FORCE_FIXED);
        int fit      = forcedLen(g, WaypointCodec.PackingMode.FORCE_FIT);
        int vectorAxis = forcedLen(g, WaypointCodec.PackingMode.FORCE_VECTOR_AXIS_SEPARATED);
        int deltaFitAxis = forcedLen(g, WaypointCodec.PackingMode.FORCE_DELTA_FIT_AXIS_SEPARATED);
        int auto     = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES).length();

        assertTrue(auto <= vector && auto <= absolute && auto <= fixed && auto <= fit
                        && auto <= vectorAxis && auto <= deltaFitAxis,
                "AUTO must pick the smallest; auto=" + auto + " vector=" + vector
                        + " absolute=" + absolute + " fixed=" + fixed + " fit=" + fit
                        + " vectorAxis=" + vectorAxis + " deltaFitAxis=" + deltaFitAxis);
    }

    @Test
    void auto_mode_no_worse_than_either_when_coords_exceed_fixed_bounds() {
        // Yo-yo shape far enough out that FIXED_COMPACT is ineligible; AUTO must
        // still pick the smallest of the two remaining modes. Not asserting the
        // specific ordering of absolute vs vector because deflate+dictionary can
        // blur the raw-byte ranking when coord streams compress differently.
        WaypointGroup g = WaypointGroup.create("yoyo_far", "z");
        g.add(new Waypoint(0, 0, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(1_000_000, 100, 1_000_000, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(0, 0, 0, "", Waypoint.DEFAULT_COLOR, 0, 0));

        int vector   = forcedLen(g, WaypointCodec.PackingMode.FORCE_VECTOR);
        int absolute = forcedLen(g, WaypointCodec.PackingMode.FORCE_ABSOLUTE);
        int fit      = forcedLen(g, WaypointCodec.PackingMode.FORCE_FIT);
        int vectorAxis = forcedLen(g, WaypointCodec.PackingMode.FORCE_VECTOR_AXIS_SEPARATED);
        int deltaFitAxis = forcedLen(g, WaypointCodec.PackingMode.FORCE_DELTA_FIT_AXIS_SEPARATED);
        int auto     = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES).length();

        assertTrue(auto <= vector && auto <= absolute && auto <= fit
                        && auto <= vectorAxis && auto <= deltaFitAxis,
                "AUTO must not be worse than any eligible forced mode; auto=" + auto
                        + " vector=" + vector + " absolute=" + absolute + " fit=" + fit
                        + " vectorAxis=" + vectorAxis + " deltaFitAxis=" + deltaFitAxis);
    }

    @Test
    void auto_mode_picks_vector_when_waypoints_cluster_far_from_origin() {
        // Dense cluster at far coords: each step is tiny but the absolute coord is
        // 4 bytes of varint. Vector (delta) should beat absolute-varint here.
        // FIT_COMPACT could also win because the group span is small; we only
        // assert that AUTO is no worse than any eligible forced mode.
        WaypointGroup g = WaypointGroup.create("cluster", "z");
        int base = 2_000_000;
        for (int i = 0; i < 20; i++) {
            g.add(new Waypoint(base + i, 80, base + i * 2, "", Waypoint.DEFAULT_COLOR, 0, 0));
        }

        int vector   = forcedLen(g, WaypointCodec.PackingMode.FORCE_VECTOR);
        int absolute = forcedLen(g, WaypointCodec.PackingMode.FORCE_ABSOLUTE);
        int fit      = forcedLen(g, WaypointCodec.PackingMode.FORCE_FIT);
        int vectorAxis = forcedLen(g, WaypointCodec.PackingMode.FORCE_VECTOR_AXIS_SEPARATED);
        int deltaFitAxis = forcedLen(g, WaypointCodec.PackingMode.FORCE_DELTA_FIT_AXIS_SEPARATED);
        int auto     = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES).length();

        assertTrue(vector < absolute,
                "clustered route should be smaller under vector packing; vector=" + vector
                        + " absolute=" + absolute);
        assertTrue(auto <= vector && auto <= fit && auto <= vectorAxis && auto <= deltaFitAxis,
                "AUTO must not be worse than any eligible forced mode; auto=" + auto
                        + " vector=" + vector + " fit=" + fit
                        + " vectorAxis=" + vectorAxis + " deltaFitAxis=" + deltaFitAxis);
    }

    @Test
    void fit_compact_round_trips_tight_group_exactly() {
        // FIT_COMPACT picks per-axis bit widths from the group's span. A
        // dungeon-style group (x in [66..130], y in [128..145], z in [135..190])
        // needs 7/5/6 bits per axis = 18 bits per waypoint, vs the fixed 33.
        // Round-tripping must return byte-identical coords.
        WaypointGroup g = WaypointGroup.create("tight", "dungeon_f7");
        g.add(new Waypoint( 66, 128, 135, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint( 79, 128, 142, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint( 89, 132, 140, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint( 92, 138, 150, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(110, 140, 160, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(115, 140, 172, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(120, 140, 180, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(130, 145, 190, "", Waypoint.DEFAULT_COLOR, 0, 0));

        String encoded = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_FIT);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);
        assertEquals(g.size(), decoded.size());
        for (int i = 0; i < g.size(); i++) {
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).y(), decoded.get(i).y(), "y@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
        }
    }

    @Test
    void fit_compact_handles_single_value_axis() {
        // Flat path (every waypoint at y=70). FIT_COMPACT should choose yBits=0
        // for that axis, and the decoder must reconstruct y=70 for every point
        // without reading any bits off the wire for it.
        WaypointGroup g = WaypointGroup.create("flat", "z");
        g.add(new Waypoint(10, 70, 20, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(15, 70, 25, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(20, 70, 30, "", Waypoint.DEFAULT_COLOR, 0, 0));

        String encoded = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_FIT);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);
        for (int i = 0; i < g.size(); i++) {
            assertEquals(70, decoded.get(i).y(), "y@" + i + " must decode to constant 70");
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
        }
    }

    @Test
    void fixed_compact_round_trips_coords_exactly() {
        // FIXED_COMPACT packs coords with bit-level precision; any bit-width math
        // error would silently corrupt the decoded values. Explicit per-axis
        // checks across the full supported range.
        WaypointGroup g = WaypointGroup.create("edges", "z");
        g.add(new Waypoint(-2048, -64, -2048, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint( 2047, 447,  2047, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint(    0,   0,     0, "", Waypoint.DEFAULT_COLOR, 0, 0));
        g.add(new Waypoint( 1234,  75,  -456, "", Waypoint.DEFAULT_COLOR, 0, 0));

        String encoded = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                WaypointCodec.PackingMode.FORCE_FIXED);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);
        assertEquals(g.size(), decoded.size());
        for (int i = 0; i < g.size(); i++) {
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).y(), decoded.get(i).y(), "y@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
        }
    }

    @Test
    void fixed_compact_rejects_out_of_bounds_when_forced() {
        // If FIXED_COMPACT is forced on a group whose coords overflow the bit
        // widths, we must throw rather than silently truncate.
        WaypointGroup g = WaypointGroup.create("oob", "z");
        g.add(new Waypoint(100_000, 80, 100_000, "", Waypoint.DEFAULT_COLOR, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES,
                        WaypointCodec.PackingMode.FORCE_FIXED));
    }

    @Test
    void range_delta_forced_round_trips_full_fidelity_route() {
        WaypointGroup g = sampleGroup("range", "crystal_hollows");
        for (int i = 0; i < 40; i++) {
            g.add(new Waypoint(35 + i * 3, 68 + (i % 4), 9 + i * 2,
                    "p" + i, 0x55AAFF, i % 3 == 0 ? Waypoint.FLAG_HIDE_BEACON : 0,
                    i % 5 == 0 ? 4.5 : 0.0));
        }

        String encoded = WaypointCodec.encode(List.of(g), FULL_FIDELITY,
                WaypointCodec.PackingMode.FORCE_RANGE_DELTA);
        List<WaypointGroup> decoded = WaypointCodec.decode(encoded);

        assertEquals(1, decoded.size());
        assertGroupsEqual(g, decoded.get(0));
    }

    @Test
    void anonymous_coordinate_export_preserves_order_zone_and_group_meta() {
        WaypointGroup g = WaypointGroup.create("Secret Route", "dwarven_mines");
        g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        g.setDefaultRadius(6.5);
        for (int i = 0; i < 24; i++) {
            g.add(Waypoint.at(100 + i * 2, 64 + (i % 3), -50 - i));
        }

        String encoded = WaypointCodec.encode(List.of(g), WaypointCodec.Options.NO_NAMES);
        DecodeDebug debug = WaypointCodec.debugDecode(encoded);
        WaypointGroup decoded = WaypointCodec.decode(encoded).get(0);

        assertEquals(9, debug.version());
        assertTrue(debug.reservedBit6(), "v6+ bit 6 marks the anonymous coordinate-only body");
        assertTrue(debug.stringPool().isEmpty(), "anonymous coordinate export should not write a string pool");
        assertEquals(1, debug.groups().size());
        assertEquals("", decoded.name(), "anonymous coordinate export intentionally omits route name");
        assertEquals(g.zoneId(), decoded.zoneId());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, decoded.loadMode());
        assertEquals(6.5, decoded.defaultRadius(), 1e-6);
        assertEquals(g.size(), decoded.size());
        for (int i = 0; i < g.size(); i++) {
            assertEquals(g.get(i).x(), decoded.get(i).x(), "x@" + i);
            assertEquals(g.get(i).y(), decoded.get(i).y(), "y@" + i);
            assertEquals(g.get(i).z(), decoded.get(i).z(), "z@" + i);
        }
    }

    @Test
    void legacy_v7_payloads_still_decode_after_v8_bump() throws Exception {
        WaypointGroup g = sampleGroup("legacy-v7", "dungeon_f7");
        String legacyV7 = WaypointCodec.encodeLegacyForTest(List.of(g), FULL_FIDELITY,
                WaypointCodec.PackingMode.FORCE_VECTOR, WaypointCodec.LEGACY_V7_WIRE_VERSION);
        WaypointGroup decoded = WaypointCodec.decode(legacyV7).get(0);
        DecodeDebug debug = WaypointCodec.debugDecode(legacyV7);

        assertGroupsEqual(g, decoded);
        assertEquals(WaypointCodec.LEGACY_V7_WIRE_VERSION, debug.version());
        assertEquals("ASCII base-91 stream + subwaypoint precision", debug.textEncoding());
    }

    @Test
    void legacy_v6_payloads_still_decode_after_v8_bump() throws Exception {
        WaypointGroup g = sampleGroup("legacy-v6", "dungeon_f7");
        String legacyV6 = WaypointCodec.encodeLegacyForTest(List.of(g), FULL_FIDELITY,
                WaypointCodec.PackingMode.FORCE_VECTOR, WaypointCodec.LEGACY_V6_WIRE_VERSION);
        WaypointGroup decoded = WaypointCodec.decode(legacyV6).get(0);
        DecodeDebug debug = WaypointCodec.debugDecode(legacyV6);

        assertGroupsEqual(g, decoded);
        assertEquals(6, debug.version());
        assertEquals("ASCII base-91 stream + range-delta coord mode", debug.textEncoding());
    }

    @Test
    void legacy_v5_payloads_still_decode_after_v8_bump() throws Exception {
        WaypointGroup g = sampleGroup("legacy-v5", "dungeon_f7");
        String legacyV5 = WaypointCodec.encodeLegacyForTest(List.of(g), FULL_FIDELITY,
                WaypointCodec.PackingMode.FORCE_VECTOR, WaypointCodec.LEGACY_V5_WIRE_VERSION);
        WaypointGroup decoded = WaypointCodec.decode(legacyV5).get(0);
        DecodeDebug debug = WaypointCodec.debugDecode(legacyV5);

        assertGroupsEqual(g, decoded);
        assertEquals(5, debug.version());
        assertEquals("ASCII base-91 stream + extended coord modes", debug.textEncoding());
    }

    @Test
    void all_forced_modes_round_trip_identically() {
        // Packing mode is purely a byte-count optimization -- every mode must
        // decode to the same logical group.
        WaypointGroup g = sampleGroup("A", "z");
        WaypointGroup fromVector = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), FULL_FIDELITY,
                        WaypointCodec.PackingMode.FORCE_VECTOR)).get(0);
        WaypointGroup fromAbsolute = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), FULL_FIDELITY,
                        WaypointCodec.PackingMode.FORCE_ABSOLUTE)).get(0);
        WaypointGroup fromFixed = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), FULL_FIDELITY,
                        WaypointCodec.PackingMode.FORCE_FIXED)).get(0);
        WaypointGroup fromFit = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), FULL_FIDELITY,
                        WaypointCodec.PackingMode.FORCE_FIT)).get(0);
        WaypointGroup fromVectorAxis = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), FULL_FIDELITY,
                        WaypointCodec.PackingMode.FORCE_VECTOR_AXIS_SEPARATED)).get(0);
        WaypointGroup fromDeltaFitAxis = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), FULL_FIDELITY,
                        WaypointCodec.PackingMode.FORCE_DELTA_FIT_AXIS_SEPARATED)).get(0);
        WaypointGroup fromRangeDelta = WaypointCodec.decode(
                WaypointCodec.encode(List.of(g), FULL_FIDELITY,
                        WaypointCodec.PackingMode.FORCE_RANGE_DELTA)).get(0);
        assertGroupsEqual(g, fromVector);
        assertGroupsEqual(g, fromAbsolute);
        assertGroupsEqual(g, fromFixed);
        assertGroupsEqual(g, fromFit);
        assertGroupsEqual(g, fromVectorAxis);
        assertGroupsEqual(g, fromDeltaFitAxis);
        assertGroupsEqual(g, fromRangeDelta);
    }

    @Test
    void auto_picks_minimum_on_every_fuzz_case() {
        // Strongest safety guarantee: AUTO must never be larger than any eligible
        // forced mode on any input. If it ever is, the selection logic is broken.
        Random r = new Random(0xF00DF00DL);
        for (int trial = 0; trial < 20; trial++) {
            WaypointGroup g = WaypointGroup.create("t" + trial, "z");
            int n = 2 + r.nextInt(15);
            boolean fixedEligible = true;
            for (int i = 0; i < n; i++) {
                // Mix of origin-adjacent and far coords to stress both modes.
                int scale = r.nextBoolean() ? 1 : 1_000_000;
                int x = (r.nextInt(2000) - 1000) * scale;
                int z = (r.nextInt(2000) - 1000) * scale;
                int y = r.nextInt(320);
                if (x < -2048 || x > 2047 || z < -2048 || z > 2047) fixedEligible = false;
                g.add(new Waypoint(x, y, z, "", Waypoint.DEFAULT_COLOR, 0, 0));
            }

            int vector   = forcedLen(g, WaypointCodec.PackingMode.FORCE_VECTOR);
            int absolute = forcedLen(g, WaypointCodec.PackingMode.FORCE_ABSOLUTE);
            int fit      = forcedLen(g, WaypointCodec.PackingMode.FORCE_FIT);
            int vectorAxis = forcedLen(g, WaypointCodec.PackingMode.FORCE_VECTOR_AXIS_SEPARATED);
            int deltaFitAxis = forcedLenOrMax(g, WaypointCodec.PackingMode.FORCE_DELTA_FIT_AXIS_SEPARATED);
            int rangeDelta = forcedLenOrMax(g, WaypointCodec.PackingMode.FORCE_RANGE_DELTA);
            int auto     = WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES).length();
            assertTrue(auto <= vector && auto <= absolute && auto <= fit
                            && auto <= vectorAxis && auto <= deltaFitAxis && auto <= rangeDelta,
                    "trial " + trial + ": auto=" + auto + " vector=" + vector
                            + " absolute=" + absolute + " fit=" + fit
                            + " vectorAxis=" + vectorAxis + " deltaFitAxis=" + deltaFitAxis
                            + " rangeDelta=" + rangeDelta);
            if (fixedEligible) {
                int fixed = forcedLen(g, WaypointCodec.PackingMode.FORCE_FIXED);
                assertTrue(auto <= fixed,
                        "trial " + trial + ": auto=" + auto + " fixed=" + fixed);
            }
        }
    }

    private static int forcedLen(WaypointGroup g, WaypointCodec.PackingMode mode) {
        return WaypointCodec.encode(List.of(g), WaypointCodec.Options.WITH_NAMES, mode).length();
    }

    private static int forcedLenOrMax(WaypointGroup g, WaypointCodec.PackingMode mode) {
        try {
            return forcedLen(g, mode);
        } catch (IllegalArgumentException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private static boolean hasGroup(List<WaypointGroup> groups, String name, String zoneId, int size) {
        return groups.stream().anyMatch(g ->
                g.name().equals(name) && g.zoneId().equals(zoneId) && g.size() == size);
    }

    private static String asLegacyV3(String v4Export) throws Exception {
        String body = v4Export.substring(WaypointCodec.MAGIC.length());
        byte[] compressed = AsciiStreamCodec.decode(WaypointCodec.unescapeHypixelEmotes(body));
        byte[] raw = stripCurrentChecksum(inflateV9ForTest(compressed));
        raw[0] = (byte) ((raw[0] & 0xF0) | 3);
        return WaypointCodec.MAGIC + AsciiStreamCodec.encodeLegacyV3(deflateForTest(raw));
    }

    private static byte[] currentCompressedBody(String encoded) {
        String body = encoded.substring(WaypointCodec.MAGIC.length());
        return AsciiStreamCodec.decode(WaypointCodec.unescapeHypixelEmotes(body));
    }

    private static byte[] currentFramedBody(String encoded) throws Exception {
        return inflateV9ForTest(currentCompressedBody(encoded));
    }

    private static String encodeCompressedForTest(byte[] compressed) {
        return WaypointCodec.MAGIC
                + WaypointCodec.escapeHypixelEmotes(AsciiStreamCodec.encode(compressed));
    }

    private static byte[] appendChecksumForTest(byte[] body) {
        CRC32 checksum = new CRC32();
        checksum.update(body);
        long value = checksum.getValue();
        byte[] framed = Arrays.copyOf(body, body.length + Integer.BYTES);
        framed[body.length] = (byte) (value >>> 24);
        framed[body.length + 1] = (byte) (value >>> 16);
        framed[body.length + 2] = (byte) (value >>> 8);
        framed[body.length + 3] = (byte) value;
        return framed;
    }

    private static byte[] stripCurrentChecksum(byte[] framed) {
        return Arrays.copyOf(framed, framed.length - Integer.BYTES);
    }

    private static String asLegacyV4WithComma(String v5Export) throws Exception {
        String body = v5Export.substring(WaypointCodec.MAGIC.length());
        byte[] compressed = AsciiStreamCodec.decode(WaypointCodec.unescapeHypixelEmotes(body));
        byte[] raw = stripCurrentChecksum(inflateV9ForTest(compressed));
        raw[0] = (byte) ((raw[0] & 0xF0) | 4);

        return WaypointCodec.MAGIC
                + WaypointCodec.escapeHypixelEmotes(AsciiStreamCodec.encodeLegacyV4(deflateForTest(raw)));
    }

    private static String legacyV1Export(List<WaypointGroup> groups, WaypointCodec.Options opts, String label)
            throws Exception {
        String safeLabel = WaypointCodec.Options.sanitizeLabel(label);
        Map<String, Integer> pool = new LinkedHashMap<>();
        legacyV1Intern(pool, "");
        for (WaypointGroup group : groups) {
            legacyV1Intern(pool, group.name());
            legacyV1Intern(pool, group.zoneId());
            if (opts.includeNames) {
                for (Waypoint waypoint : group.waypoints()) {
                    if (waypoint.hasName()) legacyV1Intern(pool, waypoint.name());
                }
            }
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        int header = 1;
        if (opts.includeNames) header |= 0x10;
        if (!safeLabel.isEmpty()) header |= 0x20;
        out.writeByte(header);
        if (!safeLabel.isEmpty()) writeLegacyV1Utf8String(out, safeLabel);

        WaypointCodec.writeVarint(out, pool.size());
        for (String value : pool.keySet()) {
            writeLegacyV1Utf8String(out, value);
        }
        WaypointCodec.writeVarint(out, groups.size());

        for (WaypointGroup group : groups) {
            WaypointCodec.writeVarint(out, pool.get(group.name()));
            WaypointCodec.writeVarint(out, pool.get(group.zoneId()));

            boolean customRadius = opts.includeGroupMeta
                    && Math.abs(group.defaultRadius() - 3.0) > 0.001;
            int groupFlags = 0;
            if (opts.includeGroupMeta) {
                if (group.gradientMode() == WaypointGroup.GradientMode.AUTO) groupFlags |= 0x02;
                if (group.loadMode() == WaypointGroup.LoadMode.SEQUENCE) groupFlags |= 0x04;
                if (customRadius) groupFlags |= 0x08;
            } else {
                groupFlags |= 0x02;
            }
            out.writeByte(groupFlags);
            if (customRadius) {
                WaypointCodec.writeVarint(out, (int) Math.round(group.defaultRadius() * 10.0));
            }

            WaypointCodec.writeVarint(out, group.size());
            int lastX = 0;
            int lastY = 0;
            int lastZ = 0;
            for (int i = 0; i < group.size(); i++) {
                Waypoint waypoint = group.get(i);
                WaypointCodec.writeZigzag(out, i == 0 ? waypoint.x() : waypoint.x() - lastX);
                WaypointCodec.writeZigzag(out, i == 0 ? waypoint.y() : waypoint.y() - lastY);
                WaypointCodec.writeZigzag(out, i == 0 ? waypoint.z() : waypoint.z() - lastZ);
                lastX = waypoint.x();
                lastY = waypoint.y();
                lastZ = waypoint.z();
            }
            for (Waypoint waypoint : group.waypoints()) {
                writeLegacyV1WaypointBody(out, pool, waypoint, opts);
            }
        }
        out.flush();
        return WaypointCodec.MAGIC + CjkBase16384.encode(deflateForTest(body.toByteArray()));
    }

    private static int legacyV1Intern(Map<String, Integer> pool, String value) {
        String key = value == null ? "" : value;
        Integer existing = pool.get(key);
        if (existing != null) return existing;
        int index = pool.size();
        pool.put(key, index);
        return index;
    }

    private static void writeLegacyV1Utf8String(DataOutputStream out, String value) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        WaypointCodec.writeVarint(out, bytes.length);
        out.write(bytes);
    }

    private static void writeLegacyV1WaypointBody(DataOutputStream out, Map<String, Integer> pool,
                                                  Waypoint waypoint, WaypointCodec.Options opts)
            throws Exception {
        boolean hasName = opts.includeNames && waypoint.hasName();
        boolean hasColor = opts.includeColors
                && (waypoint.color() & 0xFFFFFF) != (Waypoint.DEFAULT_COLOR & 0xFFFFFF);
        boolean hasRadius = opts.includeRadii && waypoint.customRadius() > 0;
        int extendedFlags = opts.includeWaypointFlags ? waypoint.flags() & 0xFF : 0;

        int flags = 0;
        if (hasName) flags |= 0x01;
        if (hasColor) flags |= 0x02;
        if (hasRadius) flags |= 0x04;
        if (extendedFlags != 0) flags |= 0x08;
        out.writeByte(flags);

        if (hasName) WaypointCodec.writeVarint(out, pool.get(waypoint.name()));
        if (hasColor) {
            int color = waypoint.color() & 0xFFFFFF;
            out.writeByte((color >> 16) & 0xFF);
            out.writeByte((color >> 8) & 0xFF);
            out.writeByte(color & 0xFF);
        }
        if (hasRadius) WaypointCodec.writeVarint(out, (int) Math.round(waypoint.customRadius() * 10.0));
        if (extendedFlags != 0) WaypointCodec.writeVarint(out, extendedFlags);
    }

    private static byte[] deflateForTest(byte[] raw) throws Exception {
        return deflateForTest(raw, CodecDictionary.BYTES);
    }

    private static byte[] deflateV9ForTest(byte[] raw) throws Exception {
        return deflateForTest(raw, V9CodecDictionary.BYTES);
    }

    private static byte[] deflateForTest(byte[] raw, byte[] dictionary) throws Exception {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setDictionary(dictionary);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            stream.write(raw);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static byte[] inflateForTest(byte[] compressed) throws Exception {
        return inflateForTest(compressed, CodecDictionary.BYTES);
    }

    private static byte[] inflateV9ForTest(byte[] compressed) throws Exception {
        return inflateForTest(compressed, V9CodecDictionary.BYTES);
    }

    private static byte[] inflateForTest(byte[] compressed, byte[] dictionary) throws Exception {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            inflater.setDictionary(dictionary);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 2);
            byte[] buffer = new byte[256];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalArgumentException("truncated deflate stream");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static WaypointGroup sampleGroup(String name, String zone) {
        WaypointGroup g = WaypointGroup.create(name, zone);
        g.setDefaultRadius(4.0);
        g.add(new Waypoint(10, 70, -20, "start", 0x40E0D0, 0, 0.0));
        g.add(new Waypoint(15, 71, -18, "", 0xFF5577, Waypoint.FLAG_LOCKED_COLOR, 5.0));
        g.add(new Waypoint(30, 68, 5, "boss", 0xFFDD00, Waypoint.FLAG_HIDE_BEACON, 0.0));
        return g;
    }

    private static void assertGroupsEqual(WaypointGroup expected, WaypointGroup actual) {
        assertEquals(expected.name(), actual.name(), "name");
        assertEquals(expected.zoneId(), actual.zoneId(), "zoneId");
        // enabled and currentIndex are never written by the encoder (see
        // exports_always_reset_session_state_on_import). This helper only stays
        // valid when callers leave them at the WaypointGroup defaults; tests
        // that deliberately mutate either field must not route through here.
        assertTrue(actual.enabled(), "decoded groups always import enabled");
        assertEquals(0, actual.currentIndex(), "decoded groups always start at index 0");
        assertEquals(expected.defaultRadius(), actual.defaultRadius(), 1e-6, "defaultRadius");
        assertEquals(expected.gradientMode(), actual.gradientMode(), "gradientMode");
        assertEquals(expected.loadMode(), actual.loadMode(), "loadMode");
        assertEquals(expected.skipAheadEnabled(), actual.skipAheadEnabled(), "skipAheadEnabled");
        assertEquals(expected.staticColor(), actual.staticColor(), "staticColor");
        assertEquals(expected.gradientStartColor(), actual.gradientStartColor(), "gradientStartColor");
        assertEquals(expected.gradientEndColor(), actual.gradientEndColor(), "gradientEndColor");
        assertEquals(expected.size(), actual.size(), "size");
        // Waypoint comparison: compare position/flags/radius strictly; compare color
        // loosely when the source was AUTO because encode/decode re-applies the gradient.
        boolean looseColor = expected.gradientMode() == WaypointGroup.GradientMode.AUTO;
        for (int i = 0; i < expected.size(); i++) {
            Waypoint e = expected.get(i);
            Waypoint a = actual.get(i);
            assertEquals(e.x(), a.x(), "x@" + i);
            assertEquals(e.y(), a.y(), "y@" + i);
            assertEquals(e.z(), a.z(), "z@" + i);
            assertEquals(e.preciseX(), a.preciseX(), "preciseX@" + i);
            assertEquals(e.preciseY(), a.preciseY(), "preciseY@" + i);
            assertEquals(e.preciseZ(), a.preciseZ(), "preciseZ@" + i);
            assertEquals(e.name(), a.name(), "name@" + i);
            assertEquals(e.flags(), a.flags(), "flags@" + i);
            assertEquals(e.customRadius(), a.customRadius(), 1e-6, "customRadius@" + i);
            if (!looseColor || e.hasFlag(Waypoint.FLAG_LOCKED_COLOR)) {
                assertEquals(e.color(), a.color(), "color@" + i);
            }
        }
    }
}
