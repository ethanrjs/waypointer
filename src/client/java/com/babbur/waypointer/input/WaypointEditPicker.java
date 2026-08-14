package com.babbur.waypointer.input;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.WaypointRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WaypointEditPicker {

    private static final double PICK_RANGE = 512.0;
    private static final double PICK_PADDING = 0.18;
    private static final double DIRECTION_EPSILON = 1.0E-7;

    private WaypointGroup lastGroup;
    private int lastIndex = -1;

    Selection find(Minecraft mc, ActiveGroupManager manager, WaypointerConfig config) {
        if (mc == null || mc.player == null || mc.level == null) return null;
        if (manager == null || config == null) return null;

        ClientLevel level = mc.level;
        Vec3 origin = MinecraftCompat.mainCamera(mc.gameRenderer).position();
        Vec3 direction = normalized(mc.player.getViewVector(1.0F));
        if (direction == null) return null;

        List<PickCandidate> candidates = new ArrayList<>();
        for (WaypointGroup group : manager.activeGroups()) {
            group.forEachVisibleIndex(config.sequenceVisibility(),
                    config.keepSubwaypointsVisibleUntilNextWaypoint(), index -> {
                if (index < 0 || index >= group.size()) return;

                AABB bounds = WaypointRenderer.waypointBoxBounds(level, group.get(index));
                if (bounds == null) return;

                double distance = rayBoxDistance(
                        origin,
                        direction,
                        bounds.inflate(PICK_PADDING),
                        PICK_RANGE);
                if (distance >= 0.0) {
                    candidates.add(new PickCandidate(new Selection(group, index), distance));
                }
            });
        }
        candidates.sort(Comparator.comparingDouble(PickCandidate::distance));
        return choose(candidates);
    }

    void clear() {
        lastGroup = null;
        lastIndex = -1;
    }

    private Selection choose(List<PickCandidate> candidates) {
        if (candidates.isEmpty()) {
            clear();
            return null;
        }

        int previousPosition = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Selection selection = candidates.get(i).selection();
            if (selection.group() == lastGroup && selection.waypointIndex() == lastIndex) {
                previousPosition = i;
                break;
            }
        }

        int chosenIndex = previousPosition >= 0
                ? (previousPosition + 1) % candidates.size()
                : 0;
        Selection chosen = candidates.get(chosenIndex).selection();
        lastGroup = chosen.group();
        lastIndex = chosen.waypointIndex();
        return chosen;
    }

    private static Vec3 normalized(Vec3 vector) {
        if (vector == null
                || !Double.isFinite(vector.x)
                || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) {
            return null;
        }
        double lengthSquared = vector.lengthSqr();
        if (lengthSquared <= DIRECTION_EPSILON * DIRECTION_EPSILON) return null;
        return vector.scale(1.0 / Math.sqrt(lengthSquared));
    }

    static double rayBoxDistance(Vec3 origin, Vec3 direction, AABB box, double maxDistance) {
        if (origin == null || direction == null || box == null) return -1.0;
        if (!finite(origin) || !finite(direction)
                || !Double.isFinite(maxDistance) || maxDistance < 0.0) {
            return -1.0;
        }
        if (box.contains(origin)) return 0.0;

        Vec3 end = origin.add(direction.scale(maxDistance));
        return box.clip(origin, end)
                .map(origin::distanceTo)
                .orElse(-1.0);
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    record Selection(WaypointGroup group, int waypointIndex) {}

    private record PickCandidate(Selection selection, double distance) {}
}
