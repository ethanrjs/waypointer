package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.HappySnowmanSession;
import com.babbur.waypointer.render.WaypointPaintTextureCache;
import com.babbur.waypointer.render.WaypointRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.CommandEncoder;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns retained world geometry for one client session. */
public final class OverlayRenderer implements AutoCloseable {

    private static volatile OverlayRenderer active;

    private final WaypointRenderer worldRenderer;
    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final OverlayRendererOptions options;
    private final OverlayPipelines pipelines;
    private final SceneKeyFactory keyFactory;
    private final OverlayPass pass = new OverlayPass();
    private final DepthSnapshot depthSnapshot = new DepthSnapshot();
    private final Map<MeshBucket, OverlayPass.MeshSet> meshes = new LinkedHashMap<>();

    private MeshCaptureSink captureSink;
    private PoseStack capturePose;
    private SceneKey lastKey = SceneKey.NONE;
    private OverlayCompositing effectiveCompositing;
    private OverlayPass.FrameView pendingView;
    private SceneKey pendingKey;
    private boolean pendingPostDraw;
    private boolean postHookObserved;
    private int postHookMissedFrames;

    private OverlayRenderer(WaypointRenderer worldRenderer, ActiveGroupManager manager,
                            WaypointerConfig config,
                            OverlayRendererOptions options) {
        this.worldRenderer = Objects.requireNonNull(worldRenderer, "worldRenderer");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.config = Objects.requireNonNull(config, "config");
        this.options = Objects.requireNonNull(options, "options");
        this.pipelines = OverlayPipelines.create();
        this.keyFactory = new SceneKeyFactory(options);
        this.effectiveCompositing = options.compositing();
    }

    public static OverlayRenderer install(WaypointRenderer worldRenderer, ActiveGroupManager manager,
                                          WaypointerConfig config) {
        OverlayRendererOptions options = OverlayRendererOptions.fromSystemProperties();
        OverlayRenderer renderer = new OverlayRenderer(worldRenderer, manager, config, options);
        if (options.compositing().usesGpuRenderer()) {
            OverlayPassCompat.registerEndMain(renderer::onEndMain);
        }
        active = renderer;
        Waypointer.LOGGER.info("Waypointer overlay renderer: {} (irisAssign={})",
                options.compositing(), options.assignIrisPrograms());
        return renderer;
    }

    public static boolean ownsWorldGeometry() {
        OverlayRenderer renderer = active;
        return renderer != null && renderer.effectiveCompositing.usesGpuRenderer();
    }

    public static OverlayRenderer activeOrNull() {
        return active;
    }

    public OverlayRendererOptions options() {
        return options;
    }

    public OverlayCompositing effectiveCompositing() {
        return effectiveCompositing;
    }

    public static void onPaintTextureCreated(WaypointPaintTextureCache.Entry entry) {
        OverlayRenderer renderer = active;
        if (renderer != null && entry != null) {
            renderer.pipelines.registerPainted(entry.id(), entry.throughWalls(), entry.depthTested());
        }
    }

    public static void onPaintTextureEvicted(WaypointPaintTextureCache.Entry entry) {
        OverlayRenderer renderer = active;
        if (renderer != null && entry != null) {
            for (MeshBucket bucket : renderer.pipelines.unregisterPainted(
                    entry.throughWalls(), entry.depthTested())) {
                OverlayPass.MeshSet set = renderer.meshes.remove(bucket);
                if (set != null) {
                    closeStep("static paint mesh", set.statics::close);
                    closeStep("dynamic paint mesh", set.dynamics::close);
                }
                if (renderer.captureSink != null) {
                    closeStep("paint capture buffers",
                            () -> renderer.captureSink.removeBucket(bucket));
                }
            }
        }
    }

    public void onEndMain(LevelRenderContext ctx) {
        if (!effectiveCompositing.usesGpuRenderer()) return;
        try {
            renderFrame(ctx);
        } catch (RuntimeException | LinkageError failure) {
            tripCircuitBreaker("in-world pass", failure);
        }
    }

    private void tripCircuitBreaker(String stage, Throwable failure) {
        Waypointer.LOGGER.error("Waypointer GPU overlay failed during {}; reverting to the "
                + "immediate-mode renderer for this session. Report this with the trace below.",
                stage, failure);
        effectiveCompositing = OverlayCompositing.LEGACY_SUBMIT;
        pendingPostDraw = false;
        try {
            close();
        } catch (RuntimeException | LinkageError ignored) {
        }
        active = this; // Preserve diagnostics after failure.
    }

    private void renderFrame(LevelRenderContext ctx) {
        depthSnapshot.invalidate();
        pendingPostDraw = false;
        reconcilePostHook();

        IrisBridge iris = IrisBridge.get();
        boolean inWorld = effectiveCompositing == OverlayCompositing.IN_WORLD;
        boolean shaderPack = inWorld && iris.isShaderPackInUse();
        if (inWorld && iris.apiFailed()) {
            throw new IllegalStateException("The installed Iris API could not be queried");
        }
        boolean shadowPass = inWorld && iris.isRenderingShadowPass();
        if (inWorld && iris.apiFailed()) {
            throw new IllegalStateException("The installed Iris shadow-pass state could not be queried");
        }
        if (shadowPass) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            resetScene();
            return;
        }
        Camera camera = MinecraftCompat.mainCamera(mc.gameRenderer);
        if (camera == null || !camera.isInitialized()) return;
        List<WaypointGroup> groups = manager.activeGroups();
        int paintOverflow = worldRenderer.reserveActivePaintsFor(groups);
        if (paintOverflow > 0 && options.debugLogging()) {
            Waypointer.LOGGER.warn("Retained renderer used RGB fallback for {} paint(s)",
                    paintOverflow);
        }
        if (groups.isEmpty()) {
            resetScene();
            return;
        }

        Vec3 cameraPos = camera.position();
        Vec3 playerPos = mc.player == null ? null : mc.player.position();
        boolean raycastOcclusion = options.occlusion().usesRaycast();
        long occlusionFingerprint = raycastOcclusion
                ? worldRenderer.depthVisibilityFingerprintFor(groups, cameraPos)
                : 0L;
        long worldVisibilityFingerprint =
                worldRenderer.worldVisibilityFingerprintFor(groups, cameraPos, playerPos);
        long blockShapeFingerprint = worldRenderer.blockShapeFingerprintFor(groups);
        SceneKey key = keyFactory.build(groups, config, cameraPos, level,
                level.getMinY(), level.getMaxY(), occlusionFingerprint,
                worldVisibilityFingerprint, blockShapeFingerprint,
                HappySnowmanSession.facePaint());
        boolean fullRebuild = !key.equals(lastKey);
        if (fullRebuild && options.debugLogging()) {
            Waypointer.LOGGER.info("[overlay] rebuild: {}", lastKey == SceneKey.NONE ? "first frame"
                    : key.hash() != lastKey.hash() ? "scene or visibility changed" : "mesh origin changed");
        }

        capture(ctx, key, fullRebuild);
        upload(fullRebuild);
        lastKey = key;

        OverlayPass.FrameView view = new OverlayPass.FrameView(
                OverlayPassCompat.projectionBuffer(ctx), OverlayPassCompat.positionMatrix(ctx),
                cameraPos.x, cameraPos.y, cameraPos.z);
        ensureIrisAssignment(shaderPack);

        if (effectiveCompositing == OverlayCompositing.POST_WORLD) {
            depthSnapshot.capture();
            pendingView = view;
            pendingKey = key;
            pendingPostDraw = true;
            return;
        }
        pass.draw(effectiveCompositing, view, key, meshes, pipelines, null);
    }

    public void onAfterLevelRendered() {
        postHookObserved = true;
        if (!pendingPostDraw || pendingView == null || pendingKey == null) return;
        pendingPostDraw = false;
        try {
            pass.draw(OverlayCompositing.POST_WORLD, pendingView, pendingKey, meshes, pipelines,
                    depthSnapshot.isValid() ? depthSnapshot.view() : null);
        } catch (RuntimeException | LinkageError failure) {
            tripCircuitBreaker("post-world pass", failure);
        }
    }

    private void capture(LevelRenderContext ctx, SceneKey key, boolean fullRebuild) {
        if (captureSink == null) {
            capturePose = new PoseStack();
            captureSink = new MeshCaptureSink(pipelines, capturePose);
        }
        captureSink.beginCapture(fullRebuild);
        Vec3 origin = new Vec3(key.originX(), key.originY(), key.originZ());
        worldRenderer.emitWorldGeometryFor(ctx, capturePose, captureSink, origin,
                !options.occlusion().usesRaycast());
    }

    private void upload(boolean fullRebuild) {
        CommandEncoder encoder = GpuMeshCompat.createCommandEncoder();
        for (Map.Entry<MeshBucket, MeshCaptureSink.Pair> entry : captureSink.buckets().entrySet()) {
            OverlayPass.MeshSet set = meshes.computeIfAbsent(entry.getKey(), OverlayPass.MeshSet::create);
            MeshCaptureSink.Pair pair = entry.getValue();
            if (fullRebuild) {
                set.statics.upload(pair.statics, encoder);
            }
            set.dynamics.upload(pair.dynamics, encoder);
        }
    }

    private void ensureIrisAssignment(boolean shaderPack) {
        if (!shaderPack || effectiveCompositing != OverlayCompositing.IN_WORLD) return;
        IrisBridge iris = IrisBridge.get();
        if (!options.assignIrisPrograms() || !iris.isIrisLoaded()) {
            throw new IllegalStateException(
                    "Iris shader pack is active but pipeline assignment is disabled");
        }
        // Painted buckets can appear after the first Iris assignment.
        if (!pipelines.assignIrisPrograms(iris)) {
            throw new IllegalStateException("Iris did not accept every active overlay pipeline");
        }
    }

    private void reconcilePostHook() {
        if (effectiveCompositing != OverlayCompositing.POST_WORLD) return;
        if (postHookObserved) {
            postHookMissedFrames = 0;
            postHookObserved = false;
            return;
        }
        if (++postHookMissedFrames > 30) {
            Waypointer.LOGGER.warn("Overlay post-world hook never fired; using in-world compositing");
            effectiveCompositing = OverlayCompositing.IN_WORLD;
            depthSnapshot.close();
        }
    }

    @Override
    public void close() {
        if (active == this) active = null;
        resetScene();
    }

    public void resetScene() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::resetScene);
            return;
        }
        closeStep("paint reservation", WaypointPaintTextureCache::resetRetainedReservation);
        for (OverlayPass.MeshSet set : meshes.values()) {
            closeStep("static overlay mesh", set.statics::close);
            closeStep("dynamic overlay mesh", set.dynamics::close);
        }
        meshes.clear();
        if (captureSink != null) closeStep("capture buffers", captureSink::close);
        captureSink = null;
        capturePose = null;
        closeStep("overlay pass", pass::close);
        closeStep("depth snapshot", depthSnapshot::close);
        lastKey = SceneKey.NONE;
        keyFactory.reset();
        pendingView = null;
        pendingKey = null;
        pendingPostDraw = false;
        closeStep("paint textures", WaypointPaintTextureCache::clear);
    }

    private static void closeStep(String name, Runnable close) {
        try {
            close.run();
        } catch (RuntimeException | LinkageError failure) {
            Waypointer.LOGGER.warn("Could not close {}", name, failure);
        }
    }
}
