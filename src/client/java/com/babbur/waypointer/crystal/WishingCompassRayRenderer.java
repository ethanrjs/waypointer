package com.babbur.waypointer.crystal;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.crystal.compass.CompassRay;
import com.babbur.waypointer.crystal.compass.Vec3d;
import com.babbur.waypointer.render.RenderHelpers;
import com.babbur.waypointer.render.RenderSubmission;
import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.babbur.waypointer.render.WorldOverlayCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Renders captured Wishing Compass rays through walls for the current solve session. */
public final class WishingCompassRayRenderer {

    private static final double UNSOLVED_RAY_LENGTH = 200.0;
    private static final long SOLVED_FADE_MILLIS = 60_000L;
    private static final int RAY_COLOR = 0xF5F5DC;
    private static final float RAY_WIDTH = 3.0f;

    private final WaypointerConfig config;
    private List<CompassRay> rays = List.of();
    private Vec3d solution;
    private long solvedAtMillis;

    public WishingCompassRayRenderer(WaypointerConfig config) {
        this.config = config;
    }

    public void install() {
        WorldOverlayCompat.register(this::render);
    }

    public void update(List<CompassRay> nextRays, Vec3d nextSolution, long solvedAt) {
        rays = nextRays == null ? List.of() : List.copyOf(nextRays);
        solution = nextSolution;
        solvedAtMillis = solvedAt;
    }

    public void clear() {
        rays = List.of();
        solution = null;
        solvedAtMillis = 0L;
    }

    private void render(LevelRenderContext context) {
        if (!config.crystalHollowsShowCompassRays() || rays.isEmpty()) return;
        long now = System.currentTimeMillis();
        float alpha = 0.8f;
        if (solution != null && solvedAtMillis > 0L) {
            long age = now - solvedAtMillis;
            if (age >= SOLVED_FADE_MILLIS) {
                clear();
                return;
            }
            alpha *= 1.0f - age / (float) SOLVED_FADE_MILLIS;
        }
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) return;
        Minecraft client = Minecraft.getInstance();
        Camera camera = MinecraftCompat.mainCamera(client.gameRenderer);
        Vec3 cameraPosition = camera.position();
        poseStack.pushPose();
        try {
            poseStack.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
            float renderAlpha = alpha;
            RenderSubmission.submit(context, poseStack,
                    WaypointerRenderPipelines.linesThroughWalls(), (vertices, submittedPose) -> {
                        for (CompassRay ray : rays) {
                            Vec3d end = solution == null
                                    ? ray.origin().add(ray.direction().scale(UNSOLVED_RAY_LENGTH))
                                    : solution;
                            RenderHelpers.emitLine(vertices, submittedPose,
                                    (float) ray.origin().x(), (float) ray.origin().y(),
                                    (float) ray.origin().z(), (float) end.x(), (float) end.y(),
                                    (float) end.z(), RAY_COLOR, renderAlpha, RAY_WIDTH);
                        }
                    });
        } finally {
            poseStack.popPose();
        }
    }
}
