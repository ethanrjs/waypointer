package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePreviewProjectionTest {

    @Test
    void depthEnvelopeKeepsEveryYawInsideTheNativePipDepthRange() {
        WaypointGroup group = WaypointGroup.create("Depth", "hub");
        group.add(Waypoint.at(-300, -40, 20));
        group.add(Waypoint.at(250, 75, 400));
        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);

        double envelope = RoutePreviewProjection.rotationSafeDepthEnvelope(scene);
        double scale = RoutePreviewProjection.depthSafeScale(envelope, 4);
        for (int degrees = 0; degrees < 360; degrees++) {
            double yaw = Math.toRadians(degrees);
            for (RoutePreviewScene.Marker marker : scene.markers()) {
                RoutePreviewScene.Box box = marker.box();
                for (int xi = 0; xi < 2; xi++) {
                    double x = xi == 0 ? box.minX() : box.maxX();
                    for (int yi = 0; yi < 2; yi++) {
                        double y = yi == 0 ? box.minY() : box.maxY();
                        for (int zi = 0; zi < 2; zi++) {
                            double z = zi == 0 ? box.minZ() : box.maxZ();
                            double physicalDepth = Math.abs(RoutePreviewProjection.project(
                                    x, y, z, yaw, scale, 0.0, 0.0).depth()
                                    * scale * 4.0);
                            assertTrue(physicalDepth
                                    <= RoutePreviewProjection.PIP_SAFE_HALF_DEPTH_PHYSICAL_PIXELS
                                    + 1.0e-6);
                        }
                    }
                }
            }
        }
    }

    @Test
    void rotationSafeFitContainsEveryCornerAtEveryWholeYawDegree() {
        WaypointGroup group = WaypointGroup.create("L", "hub");
        group.add(Waypoint.at(-8, -3, 2));
        group.add(Waypoint.at(14, 11, 2));
        group.add(Waypoint.at(14, 5, 19));
        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);
        int width = 320;
        int height = 190;
        double scale = RoutePreviewProjection.rotationSafeScale(scene, width, height);

        for (int yaw = 0; yaw < 360; yaw++) {
            for (RoutePreviewScene.Marker marker : scene.markers()) {
                RoutePreviewScene.Box box = marker.box();
                for (int xi = 0; xi < 2; xi++) {
                    for (int yi = 0; yi < 2; yi++) {
                        for (int zi = 0; zi < 2; zi++) {
                            var point = RoutePreviewProjection.project(
                                    xi == 0 ? box.minX() : box.maxX(),
                                    yi == 0 ? box.minY() : box.maxY(),
                                    zi == 0 ? box.minZ() : box.maxZ(),
                                    Math.toRadians(yaw), scale, width / 2.0, height / 2.0);
                            assertTrue(point.x() >= RoutePreviewProjection.PADDING - 1.0e-7);
                            assertTrue(point.x() <= width - RoutePreviewProjection.PADDING + 1.0e-7);
                            assertTrue(point.y() >= RoutePreviewProjection.PADDING - 1.0e-7);
                            assertTrue(point.y() <= height - RoutePreviewProjection.PADDING + 1.0e-7);
                        }
                    }
                }
            }
        }
    }

    @Test
    void singleNormalWaypointNeverExceedsFortyEightPixels() {
        WaypointGroup group = WaypointGroup.create("One", "hub");
        group.add(Waypoint.at(0, 0, 0));
        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);
        double scale = RoutePreviewProjection.rotationSafeScale(scene, 600, 600);

        for (int yaw = 0; yaw < 360; yaw++) {
            double[] envelope = RoutePreviewProjection.projectedEnvelope(
                    scene.markers().getFirst().box(), Math.toRadians(yaw));
            assertTrue((envelope[2] - envelope[0]) * scale <= 48.000001);
            assertTrue((envelope[3] - envelope[1]) * scale <= 48.000001);
        }
    }

    @Test
    void singleSmallWaypointUsesTheSameFortyEightPixelCap() {
        WaypointGroup group = WaypointGroup.create("One small", "hub");
        group.add(new Waypoint(0, 0, 0, "tiny", 0xFFFFFF,
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT,
                0.0, Waypoint.TEMP_NONE, 0L, 1, 1, 1));
        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);
        double scale = RoutePreviewProjection.rotationSafeScale(scene, 600, 600);

        for (int yaw = 0; yaw < 360; yaw++) {
            double[] envelope = RoutePreviewProjection.projectedEnvelope(
                    scene.markers().getFirst().box(), Math.toRadians(yaw));
            assertTrue((envelope[2] - envelope[0]) * scale <= 48.000001);
            assertTrue((envelope[3] - envelope[1]) * scale <= 48.000001);
        }
    }

    @Test
    void projectionUsesOneUniformAspectScale() {
        double yaw = Math.toRadians(45.0);
        double scale = 17.25;
        var origin = RoutePreviewProjection.project(0, 0, 0, yaw, scale, 0, 0);
        var right = RoutePreviewProjection.project(
                Math.cos(yaw), 0, Math.sin(yaw), yaw, scale, 0, 0);
        assertEquals(scale, right.x() - origin.x(), 1.0e-9);
    }

    @Test
    void rayPickingSelectsAVisibleWaypointAndUsesFrontMostDuplicate() {
        WaypointGroup group = WaypointGroup.create("Pick", "hub");
        group.add(Waypoint.at(2, 3, 4).withName("front"));
        group.add(Waypoint.at(2, 3, 4).withName("same"));
        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);
        double yaw = Math.toRadians(45.0);
        double scale = RoutePreviewProjection.rotationSafeScale(scene, 240, 160);
        RoutePreviewScene.Box box = scene.markers().getFirst().box();
        var center = RoutePreviewProjection.project(
                box.centerX(), box.centerY(), box.centerZ(), yaw, scale, 120, 80);

        assertEquals(0, RoutePreviewProjection.pick(
                scene, center.x(), center.y(), 0, 0, 240, 160, yaw, scale));
    }

    @Test
    void hoverPickingKeepsSubpixelWaypointUsableWithoutResizingIt() {
        WaypointGroup group = WaypointGroup.create("Tiny target", "hub");
        group.add(Waypoint.at(-1_000, 0, 0));
        group.add(new Waypoint(0, 0, 0, "tiny", 0xFFFFFF,
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT,
                0.0, Waypoint.TEMP_NONE, 0L, 1, 1, 1));
        group.add(Waypoint.at(1_000, 0, 0));
        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);
        double yaw = 0.0;
        double scale = RoutePreviewProjection.rotationSafeScale(scene, 240, 160);
        RoutePreviewScene.Box tiny = scene.markers().get(1).box();
        var center = RoutePreviewProjection.project(
                tiny.centerX(), tiny.centerY(), tiny.centerZ(), yaw, scale, 120, 80);

        assertEquals(1, RoutePreviewProjection.pick(scene,
                center.x() + 2.0, center.y(), 0, 0, 240, 160, yaw, scale));
    }

    @Test
    void verticalRouteFitRemainsFinite() {
        WaypointGroup group = WaypointGroup.create("Vertical", "hub");
        group.add(Waypoint.at(0, -200, 0));
        group.add(Waypoint.at(0, 200, 0));
        double scale = RoutePreviewProjection.rotationSafeScale(
                RoutePreviewScene.build(group, new WaypointerConfig(), null), 240, 160);
        assertTrue(Double.isFinite(scale));
        assertTrue(scale > 0.0);
    }

    @Test
    void localizedFloatAnchorsStayWithinHalfPhysicalPixelAtGuiScaleFour() {
        WaypointGroup group = WaypointGroup.create("Far cluster", "hub");
        group.add(Waypoint.at(100_000_000, 70, -100_000_000));
        group.add(new Waypoint(0, 0, 0, "sixteenth", 0xFFFFFF,
                Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SMALL_SUBWAYPOINT,
                0.0, Waypoint.TEMP_NONE, 0L,
                1_600_000_025, 1_129, -1_599_999_991));
        RoutePreviewScene scene = RoutePreviewScene.build(group, new WaypointerConfig(), null);
        double yaw = Math.toRadians(123.0);
        double scale = RoutePreviewProjection.rotationSafeScale(scene, 320, 180);

        for (RoutePreviewScene.Marker marker : scene.markers()) {
            RoutePreviewScene.Box box = marker.box();
            var reference = RoutePreviewProjection.project(
                    box.centerX(), box.centerY(), box.centerZ(), yaw, scale, 160, 90);
            var submitted = RoutePreviewProjection.project(
                    (float) box.centerX(), (float) box.centerY(), (float) box.centerZ(),
                    yaw, scale, 160, 90);
            assertTrue(Math.abs(reference.x() - submitted.x()) * 4.0 <= 0.5);
            assertTrue(Math.abs(reference.y() - submitted.y()) * 4.0 <= 0.5);
        }
    }
}
