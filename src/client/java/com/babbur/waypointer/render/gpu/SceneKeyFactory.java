package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

/** Builds the static-geometry key from renderer inputs. */
public final class SceneKeyFactory {

    private final OverlayRendererOptions options;

    public SceneKeyFactory(OverlayRendererOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public SceneKey build(List<WaypointGroup> groups, WaypointerConfig config,
                          Vec3 camera, Object levelIdentity, int levelMinY, int levelMaxY,
                          long occlusionFingerprint, long worldVisibilityFingerprint,
                          long blockShapeFingerprint, WaypointPaint runtimePaintOverride) {
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(camera, "camera");
        SceneKey.Builder key = SceneKey.builder()
                .origin(SceneKey.originFor(camera.x), SceneKey.originFor(camera.y),
                        SceneKey.originFor(camera.z))
                .levelIdentity(levelIdentity)
                .mix(levelMinY)
                .mix(levelMaxY)
                .mix(occlusionFingerprint)
                .mix(worldVisibilityFingerprint)
                .mix(blockShapeFingerprint);
        mixPaint(key, runtimePaintOverride);
        key.mixEnum(options.occlusion());
        mixConfig(key, config);

        key.mix(groups.size());
        for (WaypointGroup group : groups) {
            mixGroup(key, group);
        }
        return key.finish();
    }

    static void mixConfig(SceneKey.Builder key, WaypointerConfig config) {
        key.mixEnum(config.boxStyle());
        mixPaint(key, config.waypointPainterDefaultPaint());
        key.mixEnum(config.beaconBeamMode())
                .mix(config.useBeaconBeamTextures())
                .mix(config.beaconBeamExtendsBelowWaypoint())
                .mix(config.beaconOpacity())
                .mix(config.waypointOutlineOpacity())
                .mix(config.waypointOutlineThickness())
                .mix(config.waypointOutlineColor())
                .mix(config.matchWaypointOutlineToWaypointColor())
                .mix(config.waypointMarkerScale());
        key.mix(config.sequenceVisibility().previous())
                .mix(config.sequenceVisibility().current())
                .mix(config.sequenceVisibility().next())
                .mix(config.dimSequenceContextWaypoints())
                .mix(config.keepSubwaypointsVisibleUntilNextWaypoint())
                .mix(config.hideWaypointsNearPlayer())
                .mix(config.hideWaypointsNearRadius())
                .mix(config.hideReachedStaticWaypointsUntilCycleComplete())
                .mix(config.maxStaticWaypointRenderDistance());
    }

    static void mixGroup(SceneKey.Builder key, WaypointGroup group) {
        key.mix(group.currentIndex())
                .mix(group.activeSubwaypointParentIndex())
                .mix(group.paintEnabled())
                .mix(group.visibleMainSteps())
                .mix(group.size());
        key.mixEnum(group.loadMode());
        mixPaint(key, group.paint());
        int size = group.size();
        for (int i = 0; i < size; i++) {
            Waypoint waypoint = group.get(i);
            mixWaypoint(key, waypoint);
            key.mix(group.isWaypointDisabled(i))
                    .mix(group.isStaticWaypointReached(i));
        }
    }

    static void mixWaypoint(SceneKey.Builder key, Waypoint waypoint) {
        if (waypoint == null) {
            key.mix(-1L);
            return;
        }
        key.mix(waypoint.preciseX())
                .mix(waypoint.preciseY())
                .mix(waypoint.preciseZ())
                .mix(waypoint.color())
                .mix(waypoint.flags());
    }

    static void mixPaint(SceneKey.Builder key, WaypointPaint paint) {
        key.mix(paint == null ? 0L : paint.contentFingerprint());
    }
}
