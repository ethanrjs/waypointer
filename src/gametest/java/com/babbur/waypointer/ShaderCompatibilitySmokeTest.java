package com.babbur.waypointer;

import com.babbur.waypointer.api.RouteLoadMode;
import com.babbur.waypointer.api.RouteSpec;
import com.babbur.waypointer.api.WaypointFlags;
import com.babbur.waypointer.api.WaypointSpec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.render.gpu.IrisBridge;
import com.babbur.waypointer.render.gpu.MeshBucket;
import com.babbur.waypointer.render.gpu.MeshCaptureSink;
import com.babbur.waypointer.render.gpu.OverlayCompositing;
import com.babbur.waypointer.render.gpu.OverlayPass;
import com.babbur.waypointer.render.gpu.OverlayPipelines;
import com.babbur.waypointer.render.gpu.OverlayRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings("UnstableApiUsage")
final class ShaderCompatibilitySmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("waypointer.test.shaders");
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";
    private static AssertionError layoutFailure;
    private static int layoutFrames;
    private static int checkedLayoutFrames;

    private ShaderCompatibilitySmokeTest() {}

    static void prepare(ClientGameTestContext context) {
        if (!ENABLED) return;
        context.runOnClient(client -> {
            if (!IrisBridge.get().isIrisLoaded()) throw new AssertionError("Shader test requires Iris");
            LevelRenderEvents.END_MAIN.register(renderContext -> checkLiveLayouts());
        });
        waitForShaderState(context, true);
        String routeId = context.computeOnClient(client -> {
            var config = WaypointerClient.config();
            config.setBoxStyle(WaypointerConfig.BoxStyle.FILLED_OUTLINED);
            config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
            config.setUseBeaconBeamTextures(true);
            config.setShowRouteLines(true);
            config.setShowTracer(true);
            config.setHideTracerOnStaticRoutes(false);
            config.setHideWaypointsNearPlayer(false);
            var position = client.player.blockPosition().offset(0, 1, 12);
            var route = RouteSpec.builder().name("Shader compatibility")
                    .zoneId("private_world").loadMode(RouteLoadMode.STATIC);
            for (int i = -1; i <= 1; i++) {
                route.waypoint(WaypointSpec.at(position.getX() + i * 5, position.getY(), position.getZ() + i * 2)
                        .name("Shader waypoint " + (i + 2)).color(i == 0 ? 0x00FFFF : 0xFFAA00)
                        .flags(i == 0 ? WaypointFlags.DEPTH_CHECKED : WaypointFlags.THROUGH_WALL));
            }
            return WaypointerClient.api().createRoute(route.build());
        });
        context.waitFor(client -> WaypointerClient.api().activeGroups().stream()
                .anyMatch(route -> route.id().equals(routeId) && route.waypoints().size() == 3), 100);
    }

    static void check(ClientGameTestContext context, String screenshot) {
        if (!ENABLED) return;
        waitForShaderState(context, true);
        context.waitTicks(20);
        context.runOnClient(client -> assertRetainedGeometry(true));
        context.takeScreenshot("shader-" + screenshot);
    }

    static void checkToggle(ClientGameTestContext context) {
        if (!ENABLED) return;
        context.runOnClient(client -> setShadersEnabled(false));
        try {
            waitForShaderState(context, false);
            context.waitTicks(20);
            context.runOnClient(client -> assertRetainedGeometry(false));
            captureCompletedFrame(context);
        } finally {
            context.runOnClient(client -> setShadersEnabled(true));
        }
        check(context, "reenabled");
    }

    private static void captureCompletedFrame(ClientGameTestContext context) {
        var saved = new CompletableFuture<Void>();
        context.runOnClient(client -> {
            RenderTarget target;
            try {
                var targetMethod = Class.forName("com.babbur.waypointer.render.gpu.OverlayPassCompat")
                        .getDeclaredMethod("mainTarget");
                targetMethod.setAccessible(true);
                target = (RenderTarget) targetMethod.invoke(null);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Cannot capture the completed shader-disabled frame", failure);
            }
            var path = client.gameDirectory.toPath().resolve("screenshots/shader-disabled.png");
            Screenshot.takeScreenshot(target, image -> {
                try (image) {
                    Files.createDirectories(path.getParent());
                    image.writeToFile(path);
                    saved.complete(null);
                } catch (Exception failure) {
                    saved.completeExceptionally(failure);
                }
            });
        });
        context.waitFor(client -> saved.isDone(), 100);
        saved.join();
    }

    private static void waitForShaderState(ClientGameTestContext context, boolean enabled) {
        context.waitFor(client -> shaderPackInUse() == enabled, 600);
        context.waitFor(client -> IrisBridge.get().isShaderPackInUse() == enabled, 100);
        context.runOnClient(client -> {
            if (IrisBridge.get().apiFailed()) throw new AssertionError("Waypointer could not bind the Iris API");
        });
    }

    private static boolean shaderPackInUse() {
        try {
            Class<?> api = Class.forName(IRIS_API);
            return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse")
                    .invoke(api.getMethod("getInstance").invoke(null)));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not query the active shader pack", failure);
        }
    }

    private static void setShadersEnabled(boolean enabled) {
        try {
            Class<?> api = Class.forName(IRIS_API);
            Object config = api.getMethod("getConfig").invoke(api.getMethod("getInstance").invoke(null));
            Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig")
                    .getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, enabled);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not apply the shader toggle", failure);
        }
    }

    private static void assertRetainedGeometry(boolean shadersEnabled) {
        if (layoutFailure != null) throw layoutFailure;
        if (layoutFrames <= checkedLayoutFrames) throw new AssertionError("No new world frames validated the mesh layouts");
        checkedLayoutFrames = layoutFrames;
        OverlayRenderer renderer = OverlayRenderer.activeOrNull();
        if (renderer == null || renderer.effectiveCompositing() != OverlayCompositing.IN_WORLD
                || !renderer.options().assignIrisPrograms()) {
            throw new AssertionError("Shader test fell back from the Iris-integrated retained renderer");
        }
        Map<?, ?> meshes = (Map<?, ?>) field(renderer, "meshes");
        EnumSet<MeshBucket.Kind> populated = EnumSet.noneOf(MeshBucket.Kind.class);
        EnumSet<MeshBucket.Kind> depthPopulated = EnumSet.noneOf(MeshBucket.Kind.class);
        EnumSet<MeshBucket.Kind> throughPopulated = EnumSet.noneOf(MeshBucket.Kind.class);
        for (var entry : meshes.entrySet()) {
            var mesh = (OverlayPass.MeshSet) entry.getValue();
            if (mesh.statics.indexCount() > 0 || mesh.dynamics.indexCount() > 0) {
                var bucket = (MeshBucket) entry.getKey();
                populated.add(bucket.kind());
                (bucket.depthTested() ? depthPopulated : throughPopulated).add(bucket.kind());
            }
        }
        if (!populated.containsAll(EnumSet.of(MeshBucket.Kind.LINES, MeshBucket.Kind.QUADS, MeshBucket.Kind.BEAM))) {
            throw new AssertionError("Shader fixture did not produce outline/fill/beam GPU meshes: " + populated);
        }
        if (!depthPopulated.containsAll(populated) || !throughPopulated.containsAll(populated)) {
            throw new AssertionError("Shader fixture did not cover both depth-tested and through-wall pipelines: depth="
                    + depthPopulated + ", through=" + throughPopulated);
        }
        if (shadersEnabled) {
            Set<?> assigned = (Set<?>) field(IrisBridge.get(), "assigned");
            Map<?, ?> slots = (Map<?, ?>) field(field(renderer, "pipelines"), "slots");
            for (Object value : slots.values()) {
                if (!assigned.contains(((OverlayPipelines.Slot) value).inWorld())) {
                    throw new AssertionError("Active waypoint pipeline was not assigned to Iris");
                }
            }
        }
    }

    private static void checkLiveLayouts() {
        if (layoutFailure != null || IrisBridge.get().isRenderingShadowPass()) return;
        var renderer = OverlayRenderer.activeOrNull();
        if (renderer == null) return;
        try {
            var capture = (MeshCaptureSink) field(renderer, "captureSink");
            if (capture == null || capture.buckets().isEmpty()) return;
            var pipelines = (OverlayPipelines) field(renderer, "pipelines");
            var describe = Class.forName("com.babbur.waypointer.render.gpu.VertexLayoutCompat")
                    .getDeclaredMethod("describe", RenderPipeline.class);
            describe.setAccessible(true);
            for (var entry : capture.buckets().entrySet()) {
                var slot = pipelines.slot(entry.getKey());
                var expected = describe.invoke(null, slot.inWorld());
                if (!expected.equals(slot.layout()) || !expected.equals(entry.getValue().statics.layout())
                        || !expected.equals(entry.getValue().dynamics.layout())) {
                    throw new AssertionError("Stale captured vertex layout for " + entry.getKey());
                }
            }
            layoutFrames++;
        } catch (ReflectiveOperationException | RuntimeException | AssertionError failure) {
            layoutFailure = new AssertionError("Shader mesh layout validation failed", failure);
        }
    }

    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect retained renderer " + name, failure);
        }
    }
}
