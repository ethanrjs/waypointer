package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.render.WaypointSkipFade;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

/** Builds the static-geometry key from renderer inputs. */
public final class SceneKeyFactory {

    /** Maximum camera offset from the retained origin before geometry is rebased. */
    static final double REBASE_DISTANCE_BLOCKS = 128.0;

    private final OverlayRendererOptions options;
    private Object originLevelIdentity;
    private int originX;
    private int originY;
    private int originZ;
    private boolean hasOrigin;

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
        updateOrigin(camera, levelIdentity);
        SceneKey.Builder key = SceneKey.builder()
                .origin(originX, originY, originZ)
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
            WaypointSkipFade fade = WaypointSkipFade.observe(group, config);
            key.mix(fade != null && fade.active());
            mixGroup(key, group);
        }
        return key.finish();
    }

    /** Forgets the retained origin after a world/session reset. */
    public void reset() {
        hasOrigin = false;
        originLevelIdentity = null;
        originX = 0;
        originY = 0;
        originZ = 0;
    }

    private void updateOrigin(Vec3 camera, Object levelIdentity) {
        if (!hasOrigin
                || levelIdentity != originLevelIdentity
                || outsideRebaseDistance(camera.x, originX)
                || outsideRebaseDistance(camera.y, originY)
                || outsideRebaseDistance(camera.z, originZ)) {
            originX = SceneKey.originFor(camera.x);
            originY = SceneKey.originFor(camera.y);
            originZ = SceneKey.originFor(camera.z);
            originLevelIdentity = levelIdentity;
            hasOrigin = true;
        }
    }

    private static boolean outsideRebaseDistance(double cameraCoordinate, int originCoordinate) {
        return Math.abs(cameraCoordinate - originCoordinate) >= REBASE_DISTANCE_BLOCKS;
    }

    static void mixConfig(SceneKey.Builder key, WaypointerConfig config) {
        key.mix(config.enableFeatureBloat()).mixEnum(config.effectiveBoxStyle());
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
        key.mix(config.waypointSkipFadeMs());
        key.mix(config.sequenceVisibility().previous())
                .mix(config.sequenceVisibility().current())
                .mix(config.sequenceVisibility().next())
                .mix(config.colorSequenceWaypointsByRole())
                .mix(config.sequencePreviousWaypointColor())
                .mix(config.sequenceCurrentWaypointColor())
                .mix(config.sequenceNextWaypointColor())
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
