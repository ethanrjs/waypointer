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
        assertFalse(V10CompactRouteCodec.canEncode(route,
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
    void commonProjectionCannotCarryFieldsThatTheRequestWouldDiscard() {
        List<ProjectionChange> changes = List.of(
                new ProjectionChange("auto gradient", route -> route.setGradientMode(WaypointGroup.GradientMode.AUTO)),
                new ProjectionChange("static gradient", route -> route.setGradientMode(WaypointGroup.GradientMode.STATIC)),
                new ProjectionChange("static load", route -> route.setLoadMode(WaypointGroup.LoadMode.STATIC)),
                new ProjectionChange("group radius", route -> route.setDefaultRadius(2.5)),
                new ProjectionChange("skip ahead", route -> route.setSkipAheadEnabled(false)),
                new ProjectionChange("static color", route -> route.setStaticColor(0x112233)),
                new ProjectionChange("gradient start", route -> route.setGradientStartColor(0x112233)),
                new ProjectionChange("gradient end", route -> route.setGradientEndColor(0x112233)),
                new ProjectionChange("point radius", route -> route.set(0, route.get(0).withRadius(2.5))),
                new ProjectionChange("visual flag", route -> route.set(0, route.get(0).withFlags(Waypoint.FLAG_HIDE_BEACON))),
                new ProjectionChange("ordinary precision", route -> {
                    Waypoint point = route.get(0);
                    route.set(0, point.withPreciseSixteenths(point.preciseX() + 1, point.preciseY(), point.preciseZ()));
                }),
                new ProjectionChange("dungeon", route -> route.setRouteKind(WaypointGroup.RouteKind.DUNGEON)),
                new ProjectionChange("non-RGB color", route -> route.set(0, route.get(0).withColor(0xAA112233))),
                new ProjectionChange("temporary point", route -> route.set(0, route.get(0).withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0))));
        for (ProjectionChange change : changes) {
            WaypointGroup route = commonRoute();
            change.apply().accept(route);
            assertFalse(V10CompactRouteCodec.canEncode(route, COMMON), change.name());
        }
        WaypointGroup route = commonRoute();
        for (WaypointCodec.Options options : List.of(
                COMMON.toBuilder().label("label").build(),
                COMMON.toBuilder().includeNames(false).build(),
                COMMON.toBuilder().includeColors(false).build(),
                COMMON.toBuilder().includeZone(false).build(),
                COMMON.toBuilder().includeRadii(true).build(),
                COMMON.toBuilder().includeWaypointFlags(true).build(),
                COMMON.toBuilder().includeGroupMeta(true).build())) {
            assertFalse(V10CompactRouteCodec.canEncode(route, options));
        }
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
