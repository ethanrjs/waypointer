package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V10CompactRouteCodecTest {

    private static final WaypointCodec.Options COMMON = WaypointCodec.Options.builder()
            .includeNames(true).includeColors(true).includeZone(true)
            .includeRadii(false).includeWaypointFlags(false).includeGroupMeta(false).build();

    @Test
    void fullRoundTripPreservesCompactPersistentMetadata() throws Exception {
        WaypointGroup route = WaypointGroup.create("Secret Route — Pirate", "pirate");
        route.setGradientMode(WaypointGroup.GradientMode.STATIC);
        route.setStaticColor(0x112233);
        route.setGradientStartColor(0x445566);
        route.setGradientEndColor(0x778899);
        route.setLoadMode(WaypointGroup.LoadMode.STATIC);
        route.setDefaultRadius(2.5);
        route.setSkipAheadEnabled(false);
        route.add(new Waypoint(1, 70, -3, "One", 0xAABBCC,
                Waypoint.FLAG_DISABLED, 0.0)
                .withPreciseSixteenths(17, 1_127, -39));
        route.add(new Waypoint(2, 71, -1, "Two", 0x445566, 0, 0.0));

        assertTrue(V10CompactRouteCodec.canEncode(
                route, WaypointCodec.Options.FULL_FIDELITY));
        V10Transport.CheckedFrame frame = V10Transport.probe(
                V10CompactRouteCodec.encode(route, WaypointCodec.Options.FULL_FIDELITY));
        WaypointGroup decoded = V10CompactRouteCodec.decode(frame);

        assertEquals(V10CompactRouteCodec.CONTENT_KIND, frame.contentKind());
        assertEquals(V10CompactRouteCodec.SUBTYPE_FULL, frame.semantic()[1] & 1);
        assertGroupMetadata(route, decoded);
        for (int index = 0; index < route.size(); index++) {
            Waypoint expected = route.get(index);
            Waypoint actual = decoded.get(index);
            assertEquals(expected.x(), actual.x());
            assertEquals(expected.y(), actual.y());
            assertEquals(expected.z(), actual.z());
            assertEquals(expected.name(), actual.name());
            assertEquals(expected.color(), actual.color());
            assertEquals(expected.flags(), actual.flags());
            assertEquals(expected.preciseX(), actual.preciseX());
            assertEquals(expected.preciseY(), actual.preciseY());
            assertEquals(expected.preciseZ(), actual.preciseZ());
        }
    }

    @Test
    void noNamesRoundTripUsesPackedZoneAndProjectedPreciseFlags() throws Exception {
        WaypointGroup route = WaypointGroup.create("discarded", "custom_zone");
        route.setGradientMode(WaypointGroup.GradientMode.AUTO);
        route.setLoadMode(WaypointGroup.LoadMode.STATIC);
        route.setDefaultRadius(4.5);
        route.setSkipAheadEnabled(false);
        route.add(Waypoint.at(-12, 70, 5));
        Waypoint point = Waypoint.at(-10, 70, 5)
                .withFlags(Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT)
                .withPreciseSixteenths(-159, 1_129, 89);
        route.add(point);

        assertTrue(V10CompactRouteCodec.canEncode(route, WaypointCodec.Options.NO_NAMES));
        V10Transport.CheckedFrame frame = V10Transport.probe(
                V10CompactRouteCodec.encode(route, WaypointCodec.Options.NO_NAMES));
        WaypointGroup decoded = V10CompactRouteCodec.decode(frame);

        assertEquals(V10CompactRouteCodec.SUBTYPE_NO_NAMES, frame.semantic()[1] & 1);
        assertEquals(0, frame.semantic()[2] & 0xFF);
        assertEquals("", decoded.name());
        assertEquals(route.zoneId(), decoded.zoneId());
        assertEquals(WaypointGroup.GradientMode.MANUAL, decoded.gradientMode());
        assertEquals(route.loadMode(), decoded.loadMode());
        assertEquals(route.defaultRadius(), decoded.defaultRadius());
        assertEquals(route.skipAheadEnabled(), decoded.skipAheadEnabled());
        assertEquals("", decoded.get(0).name());
        assertEquals(Waypoint.DEFAULT_COLOR, decoded.get(0).color());
        assertEquals(WaypointCodec.exportedWaypointFlags(
                route.get(1), WaypointCodec.Options.NO_NAMES), decoded.get(1).flags());
        assertEquals(point.preciseX(), decoded.get(1).preciseX());
        assertEquals(point.preciseY(), decoded.get(1).preciseY());
        assertEquals(point.preciseZ(), decoded.get(1).preciseZ());
    }

    @Test
    void routeSelectorUsesKindOneOnlyWhenItWinsFinalTextScore() throws Exception {
        WaypointGroup route = WaypointGroup.create("Secret Route — Pirate", "pirate");
        route.add(Waypoint.at(1, 70, 2));
        route.add(Waypoint.at(3, 71, 4));

        String selected = V10RouteCodec.encode(
                List.of(route), WaypointCodec.Options.FULL_FIDELITY);

        assertEquals(V10CompactRouteCodec.CONTENT_KIND,
                V10Transport.probe(selected).contentKind());
    }

    @Test
    void labelsAndPartialProjectionsAreIneligible() {
        WaypointGroup route = fullRoute();

        assertFalse(V10CompactRouteCodec.canEncode(route,
                WaypointCodec.Options.FULL_FIDELITY.toBuilder().label("title").build()));
        assertTrue(V10CompactRouteCodec.canEncode(route,
                WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                        .includeRadii(false)
                        .build()));
    }

    @Test
    void commonProjectionUsesShorterExistingFullBodyWithoutChangingDecodedFields() throws Exception {
        WaypointGroup route = commonRoute();
        WaypointGroup snapshot = route.exportSnapshot();
        String general = V10GeneralRouteCodec.encodeCandidate(List.of(route), COMMON).transport();
        String selected = V10RouteCodec.encode(List.of(route), COMMON);
        V10Transport.CheckedFrame frame = V10Transport.probe(selected);

        assertTrue(selected.length() < general.length());
        assertEquals(V10CompactRouteCodec.CONTENT_KIND, frame.contentKind());
        assertEquals(V10CompactRouteCodec.SUBTYPE_FULL, frame.semantic()[1] & 1);
        WaypointGroup expected = V10GeneralRouteCodec.decode(V10Transport.probe(general)).groups().getFirst();
        WaypointGroup decoded = V10CompactRouteCodec.decode(frame);
        assertGroupMetadata(expected, decoded);
        assertEquals(expected.waypoints(), decoded.waypoints());
        assertGroupMetadata(snapshot, route);
        assertEquals(snapshot.waypoints(), route.waypoints());
    }

    @Test
    void commonProjectionKeepsRequiredFlagsAndSubwaypointPrecision() throws Exception {
        WaypointGroup route = commonRoute();
        Waypoint subway = route.get(3).withFlags(Waypoint.FLAG_SUBWAYPOINT
                | Waypoint.FLAG_SMALL_SUBWAYPOINT | Waypoint.FLAG_LOCKED_COLOR);
        route.set(3, subway.withPreciseSixteenths(
                subway.preciseX() + 1, subway.preciseY() + 2, subway.preciseZ() + 3));
        route.set(5, route.get(5).withFlags(Waypoint.FLAG_DISABLED));

        assertTrue(V10CompactRouteCodec.canEncode(route, COMMON));
        String general = V10GeneralRouteCodec.encodeCandidate(List.of(route), COMMON).transport();
        WaypointGroup expected = V10GeneralRouteCodec.decode(V10Transport.probe(general)).groups().getFirst();
        WaypointGroup decoded = V10CompactRouteCodec.decode(V10Transport.probe(
                V10CompactRouteCodec.encode(route, COMMON)));
        assertGroupMetadata(expected, decoded);
        assertEquals(expected.waypoints(), decoded.waypoints());
        assertTrue(V10RouteCodec.encode(List.of(route), COMMON).length() <= general.length());
    }

    @Test
    void commonProjectionDropsOmittedFieldsBeforeCompactEncoding() throws Exception {
        List<ProjectionChange> changes = List.of(
                new ProjectionChange("auto gradient", route -> route.setGradientMode(WaypointGroup.GradientMode.AUTO)),
                new ProjectionChange("static gradient", route -> route.setGradientMode(WaypointGroup.GradientMode.STATIC)),
                new ProjectionChange("static load", route -> route.setLoadMode(WaypointGroup.LoadMode.STATIC)),
                new ProjectionChange("group radius", route -> route.setDefaultRadius(2.5)),
                new ProjectionChange("skip ahead", route -> route.setSkipAheadEnabled(false)),
                new ProjectionChange("static color", route -> route.setStaticColor(0x112233)),
                new ProjectionChange("gradient start", route -> route.setGradientStartColor(0x112233)),
                new ProjectionChange("gradient end", route -> route.setGradientEndColor(0x112233)),
                new ProjectionChange("visual flag", route -> route.set(0, route.get(0).withFlags(Waypoint.FLAG_HIDE_BEACON))),
                new ProjectionChange("ordinary precision", route -> {
                    Waypoint point = route.get(0);
                    route.set(0, point.withPreciseSixteenths(point.preciseX() + 1, point.preciseY(), point.preciseZ()));
                }),
                new ProjectionChange("dungeon", route -> route.setRouteKind(WaypointGroup.RouteKind.DUNGEON)));
        for (ProjectionChange change : changes) {
            WaypointGroup route = commonRoute();
            change.apply().accept(route);
            if (route.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
                assertFalse(V10CompactRouteCodec.canEncode(route, COMMON), change.name());
            } else {
                assertCompactProjection(route, COMMON);
            }
        }
        WaypointGroup route = commonRoute();
        assertFalse(V10CompactRouteCodec.canEncode(route,
                COMMON.toBuilder().label("label").build()));
        for (WaypointCodec.Options options : List.of(
                COMMON.toBuilder().includeNames(false).build(),
                COMMON.toBuilder().includeColors(false).build(),
                COMMON.toBuilder().includeZone(false).build())) {
            assertCompactProjection(route, options);
        }

        WaypointGroup customRadius = commonRoute();
        customRadius.set(0, customRadius.get(0).withRadius(2.5));
        assertTrue(V10CompactRouteCodec.canEncode(customRadius, COMMON));
        WaypointCodec.Options withRadii = COMMON.toBuilder().includeRadii(true).build();
        assertFalse(V10CompactRouteCodec.canEncode(customRadius, withRadii));
        WaypointGroup decoded = WaypointCodec.decode(
                WaypointCodec.encode(List.of(customRadius), withRadii)).getFirst();
        assertEquals(2.5, decoded.get(0).customRadius());
    }

    @Test
    void fullProjectionDropsAlphaAndTemporaryWaypointState() throws Exception {
        WaypointGroup route = fullRoute();
        route.set(0, route.get(0).withColor(0xAA112233)
                .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 42L));

        String general = V10GeneralRouteCodec.encodeCandidate(
                List.of(route), WaypointCodec.Options.FULL_FIDELITY).transport();
        WaypointGroup expected = V10GeneralRouteCodec.decode(
                V10Transport.probe(general)).groups().getFirst();
        V10Transport.CheckedFrame frame = V10Transport.probe(
                V10CompactRouteCodec.encode(route, WaypointCodec.Options.FULL_FIDELITY));
        WaypointGroup actual = V10CompactRouteCodec.decode(frame);

        assertEquals(V10CompactRouteCodec.SUBTYPE_FULL, frame.semantic()[1] & 1);
        assertGroupMetadata(expected, actual);
        assertEquals(expected.waypoints(), actual.waypoints());
    }

    @Test
    void partialFieldCombinationsMatchGeneralProjection() throws Exception {
        for (int mask = 0; mask < 7; mask++) {
            WaypointCodec.Options options = partialOptions(mask);
            WaypointGroup route = partialRoute(mask);
            assertTrue(V10CompactRouteCodec.canEncode(route, options), "mask " + mask);

            String general = V10GeneralRouteCodec.encodeCandidate(List.of(route), options).transport();
            WaypointGroup expected = V10GeneralRouteCodec.decode(
                    V10Transport.probe(general)).groups().getFirst();
            V10Transport.CheckedFrame frame = V10Transport.probe(
                    V10CompactRouteCodec.encode(route, options));
            assertEquals(V10CompactRouteCodec.CONTENT_KIND, frame.contentKind());
            assertEquals(V10CompactRouteCodec.SUBTYPE_FULL, frame.semantic()[1] & 1);
            WaypointGroup actual = V10CompactRouteCodec.decode(frame);
            assertGroupMetadata(expected, actual);
            assertEquals(expected.waypoints(), actual.waypoints(), "mask " + mask);
        }
    }

    @Test
    void omittedNondefaultFieldsUseCompactProjectionForEveryPartialCombination() throws Exception {
        for (int mask = 0; mask < 7; mask++) {
            assertCompactProjection(omittedNondefaultRoute(mask), partialOptions(mask));
        }
    }

    @Test
    void disablingColorsOnImportedCrystalHollowsRouteDoesNotDoubleShareLength() throws Exception {
        String shared = "WP:=!Ds1Y_&4P!!6{)PG&\\0C\"XXZe#]^+A(qo($'TQn7m;$V6A4O5H/fAgh2sZ<vXp8F$G=Crkd~=}75E]@Hbb3n*N?h"
                + "Uae&v;eM=5Bh3vlI-RrcSdjMB9T+j+cTdGr}@:M{tz}5'?V5CP@A7PA:Vs7tHlJUW@eM5\"?i+$?S#;(F1;!S+clI;cAm"
                + "un8J*x/\"Q<S[5a?xY[T{:ePvEKZSkGG&xJ+\\A}luGfDdQ?op?7c|2!(^9b2@7C*]g:vFj4ISw_2k#FV\\>J4wr67w(i\">"
                + "/\\huO:g{)OAxlFm=8aYA5'o4TTvGwLB2k$lQE)_C2-/lc?%8UMxZG[M%(;WSI=_\\'&x;y%_/]L9G2I=g}XLOa#n-AvjD"
                + "aK2+7:NXe)?4se)~u2:!J/)Q/j==WWU=^3X\\1<}_Ksq%w}@)9[1E@^IGK5rmWev?h!;\"|;\"duazn<8v[GnvEBOx~/Og&"
                + "kOr&t^B-t638U-6sb$7W-XZe%rL~#$3O\"s1BuA_+'/\\LANy%8j%(nd@epVvn)mcd<];96-[0a+W'Nr%}oxlt\";Gt$ZPm"
                + "\"zu~:YZUF#DaV*I|o1ICL3@6GJ63W3bZ)HG3i#$>;AtV2Xn5~=IMIq\\'y!jHx\"[&]8ZPR<\\fT70*v_fO[{&$%zQj3\"=V"
                + "LE3ySF]9@h&EaLc0$vHlR7vI8l4*G8K(GQ0q9Tlm&ur^scge1A+n=D_;\\Fyy3!{[b#q#(fz:-z(<4G|9<6BZrkH:>RxJ"
                + "uP}^sL-Tv;oqBSW7mW/:S2TxW\\VCFQJn3!:Xo%\\~dOsOd$-p}DrO#Do\\<Z%'R2TAKwlk[>?Ii>g:Ub}xb%Rd5mLl%{5<"
                + "\\4+<V;iw06(R3'8oE(\"Ar4{/$xmPror5@|jIm-ZUY(~|5xz%N;/]&)7XzDVO@T@DuC^Au_uL9NnhcH08WRi:CAaUP\"#_"
                + "%pO!!Y|!P=~Dy(";
        WaypointGroup route = WaypointCodec.decode(shared).getFirst();
        assertEquals(410, route.size());
        assertEquals(934, shared.length());
        WaypointCodec.Options options = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                .includeColors(false).build();
        String selected = WaypointCodec.encode(List.of(route), options);
        assertTrue(selected.length() <= shared.length());
        assertEquals(V10CompactRouteCodec.CONTENT_KIND,
                V10Transport.probe(selected.substring(WaypointCodec.MAGIC.length())).contentKind());
        assertCompactProjection(route, options);
    }

    @Test
    void colorsAndNamesTogglesKeepTheCompactCoordinateAndNameEncoding() throws Exception {
        WaypointGroup route = WaypointGroup.create("Mining route", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int index = 0; index < 200; index++) {
            route.add(Waypoint.at(index * 3, 70 + index % 3, index * -2)
                    .withName(Integer.toString(index + 1)).withColor(0x123456 + index));
        }
        route.setLoadMode(WaypointGroup.LoadMode.STATIC);
        route.setDefaultRadius(4.5);
        route.setSkipAheadEnabled(false);
        route.setStaticColor(0x556677);
        route.setGradientStartColor(0x112233);
        route.setGradientEndColor(0x778899);
        route.set(1, route.get(1).withFlags(Waypoint.FLAG_LOCKED_COLOR | Waypoint.FLAG_HIDE_BEACON));
        Waypoint precise = route.get(2);
        route.set(2, precise.withPreciseSixteenths(precise.preciseX() + 1,
                precise.preciseY() + 2, precise.preciseZ() + 3));
        WaypointGroup snapshot = route.exportSnapshot();
        String full = V10RouteCodec.encode(List.of(route), WaypointCodec.Options.FULL_FIDELITY);
        WaypointCodec.Options withoutColors = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                .includeColors(false).build();
        String colorless = V10RouteCodec.encode(List.of(route), withoutColors);
        assertTrue(colorless.length() <= full.length());
        assertEquals(V10CompactRouteCodec.CONTENT_KIND, V10Transport.probe(colorless).contentKind());
        for (WaypointGroup.GradientMode gradient : WaypointGroup.GradientMode.values()) {
            route.setGradientMode(gradient);
            for (int mask = 0; mask < 64; mask++) {
                WaypointCodec.Options options = WaypointCodec.Options.builder()
                        .includeNames((mask & 1) != 0).includeColors((mask & 2) != 0)
                        .includeRadii((mask & 4) != 0).includeWaypointFlags((mask & 8) != 0)
                        .includeGroupMeta((mask & 16) != 0).includeZone((mask & 32) != 0).build();
                // The older no-names subtype intentionally omits the route title as well.
                if (mask == 48) continue;
                assertCompactProjection(route, options);
            }
        }
        route.setGradientMode(snapshot.gradientMode());
        assertGroupMetadata(snapshot, route);
        assertEquals(snapshot.waypoints(), route.waypoints());
    }

    private static void assertCompactProjection(WaypointGroup route, WaypointCodec.Options options)
            throws Exception {
        assertTrue(V10CompactRouteCodec.canEncode(route, options));
        String general = V10GeneralRouteCodec.encodeCandidate(List.of(route), options).transport();
        WaypointGroup expected = V10GeneralRouteCodec.decode(
                V10Transport.probe(general)).groups().getFirst();
        WaypointGroup actual = V10CompactRouteCodec.decode(V10Transport.probe(
                V10CompactRouteCodec.encode(route, options)));
        assertGroupMetadata(expected, actual);
        assertEquals(expected.waypoints(), actual.waypoints());
    }

    @Test
    void decoderRejectsNonCanonicalGroupNameSpelling() throws Exception {
        V10Transport.CheckedFrame frame = V10Transport.probe(
                V10CompactRouteCodec.encode(fullRoute(), WaypointCodec.Options.FULL_FIDELITY));
        byte[] semantic = frame.semantic();
        assertEquals(2, semantic[3] & 0xFF);
        byte[] name = "Secret Route".getBytes(StandardCharsets.UTF_8);
        byte[] nonCanonical = new byte[semantic.length + name.length + 1];
        System.arraycopy(semantic, 0, nonCanonical, 0, 3);
        nonCanonical[3] = 0;
        nonCanonical[4] = (byte) name.length;
        System.arraycopy(name, 0, nonCanonical, 5, name.length);
        System.arraycopy(semantic, 4, nonCanonical, 5 + name.length, semantic.length - 4);

        assertThrows(java.io.IOException.class,
                () -> V10CompactRouteCodec.decode(direct(nonCanonical)));
    }

    @Test
    void decoderRejectsReservedGradientAndRedundantTrailingPrecision() throws Exception {
        V10Transport.CheckedFrame frame = V10Transport.probe(
                V10CompactRouteCodec.encode(fullRoute(), WaypointCodec.Options.FULL_FIDELITY));
        byte[] reserved = frame.semantic().clone();
        reserved[1] |= 3 << 5;
        assertThrows(java.io.IOException.class,
                () -> V10CompactRouteCodec.decode(direct(reserved)));

        byte[] trailingZero = Arrays.copyOf(frame.semantic(), frame.semantic().length + 1);
        assertThrows(java.io.IOException.class,
                () -> V10CompactRouteCodec.decode(direct(trailingZero)));
    }

    @Test
    void decoderRejectsTruncatedPackedZoneAndOversizedCoordinateBody() {
        assertThrows(java.io.IOException.class, () -> V10CompactRouteCodec.decode(direct(
                new byte[]{(byte) V10CompactRouteCodec.SEMANTIC_HEADER, 0x41, 0, 10})));
        assertThrows(java.io.IOException.class, () -> V10CompactRouteCodec.decode(direct(
                new byte[]{(byte) V10CompactRouteCodec.SEMANTIC_HEADER, 0x41,
                        1, (byte) 0xFF, (byte) 0xFF, 0x7F})));
    }

    private static V10Transport.CheckedFrame direct(byte[] semantic) {
        return new V10Transport.CheckedFrame(V10Transport.MODE_DIRECT, semantic);
    }

    private static WaypointGroup fullRoute() {
        WaypointGroup route = WaypointGroup.create("Secret Route", "dungeon_f7");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.add(Waypoint.at(1, 70, 2));
        return route;
    }

    private static WaypointGroup commonRoute() {
        WaypointGroup route = WaypointGroup.create("Route 1", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int index = 0; index < 16; index++) {
            route.add(new Waypoint(index * 3, 70 + index % 3, -index * 2,
                    Integer.toString(index + 1), 0x336699, 0, 0.0));
        }
        return route;
    }

    private static WaypointCodec.Options partialOptions(int mask) {
        return WaypointCodec.Options.builder()
                .includeNames(true).includeColors(true).includeZone(true)
                .includeRadii((mask & 1) != 0)
                .includeWaypointFlags((mask & 2) != 0)
                .includeGroupMeta((mask & 4) != 0)
                .build();
    }

    private static WaypointGroup partialRoute(int mask) {
        WaypointGroup route = WaypointGroup.create("Route 1", "mining_3");
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        for (int index = 0; index < 24; index++) {
            route.add(new Waypoint(index * 3, 70 + index % 3, -index * 2,
                    Integer.toString(index + 1), 0x336699, 0, 0.0));
        }
        route.set(0, route.get(0).withColor(0xAA336699)
                .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 42L));
        if ((mask & 1) == 0) route.set(0, route.get(0).withRadius(2.5));
        if ((mask & 2) != 0) {
            Waypoint point = route.get(0).withFlags(Waypoint.FLAG_DISABLED);
            route.set(0, point.withPreciseSixteenths(point.preciseX() + 1,
                    point.preciseY() + 2, point.preciseZ() + 3));
        }
        if ((mask & 4) != 0) {
            route.setLoadMode(WaypointGroup.LoadMode.STATIC);
            route.setDefaultRadius(4.5);
            route.setSkipAheadEnabled(false);
            route.setGradientMode(WaypointGroup.GradientMode.AUTO);
        }
        return route;
    }

    private static WaypointGroup omittedNondefaultRoute(int mask) {
        WaypointGroup route = commonRoute();
        if ((mask & 1) == 0) route.set(0, route.get(0).withRadius(2.5));
        if ((mask & 2) == 0) route.set(0, route.get(0).withFlags(Waypoint.FLAG_HIDE_BEACON));
        if ((mask & 4) == 0) route.setLoadMode(WaypointGroup.LoadMode.STATIC);
        return route;
    }

    private record ProjectionChange(String name, Consumer<WaypointGroup> apply) {}

    private static void assertGroupMetadata(WaypointGroup expected, WaypointGroup actual) {
        assertEquals(expected.name(), actual.name());
        assertEquals(expected.zoneId(), actual.zoneId());
        assertEquals(expected.routeKind(), actual.routeKind());
        assertEquals(expected.gradientMode(), actual.gradientMode());
        assertEquals(expected.staticColor(), actual.staticColor());
        assertEquals(expected.gradientStartColor(), actual.gradientStartColor());
        assertEquals(expected.gradientEndColor(), actual.gradientEndColor());
        assertEquals(expected.loadMode(), actual.loadMode());
        assertEquals(expected.defaultRadius(), actual.defaultRadius());
        assertEquals(expected.skipAheadEnabled(), actual.skipAheadEnabled());
        assertEquals(expected.size(), actual.size());
    }
}
