package com.babbur.waypointer.render;

import com.babbur.waypointer.core.Waypoint;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class WaypointVisibilityCache {

    private final Map<Waypoint, Boolean> values = new IdentityHashMap<>();
    private ClientLevel level;
    private long gameTime = Long.MIN_VALUE;
    private double cameraX = Double.NaN;
    private double cameraY = Double.NaN;
    private double cameraZ = Double.NaN;

    void beginFrame(ClientLevel nextLevel, long nextGameTime,
                    double nextCameraX, double nextCameraY, double nextCameraZ) {
        if (nextLevel == level
                && nextGameTime == gameTime
                && Double.compare(nextCameraX, cameraX) == 0
                && Double.compare(nextCameraY, cameraY) == 0
                && Double.compare(nextCameraZ, cameraZ) == 0) {
            return;
        }
        values.clear();
        level = nextLevel;
        gameTime = nextGameTime;
        cameraX = nextCameraX;
        cameraY = nextCameraY;
        cameraZ = nextCameraZ;
    }

    boolean getOrCompute(Waypoint waypoint, BooleanSupplier visibilityCheck) {
        Boolean cached = values.get(waypoint);
        if (cached != null) return cached;
        boolean visible = visibilityCheck.getAsBoolean();
        values.put(waypoint, visible);
        return visible;
    }
}
