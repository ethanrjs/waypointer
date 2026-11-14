package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.HappySnowmanSession;
import com.babbur.waypointer.render.RenderCost;
import com.babbur.waypointer.render.WaypointPaintTextureCache;
import com.babbur.waypointer.render.WaypointRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.CommandEncoder;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private SceneKey lastGlobal = SceneKey.NONE;
    private Map<Object, Long> lastScopeKeys = new IdentityHashMap<>();
    private boolean forceFullRebuild;
    private long routeRebuilds;
    private OverlayCompositing effectiveCompositing;
    private OverlayPass.FrameView pendingView;
    private SceneKey pendingKey;
    private boolean pendingPostDraw;
    private boolean postHookObserved;
    private int postHookMissedFrames;
    private long fullRebuilds;

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

    public int lastDrawCalls() {
        return pass.lastDrawCalls();
    }

    /** Frames that rebuilt every route's static geometry. */
    public long fullRebuilds() {
        return fullRebuilds;
    }

    /** Frames that rebuilt only the routes whose own key changed. */
    public long routeRebuilds() {
        return routeRebuilds;
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
                renderer.forceFullRebuild = true;
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
        long started = RenderCost.start();
        try {
            renderFrame(ctx);
        } catch (RuntimeException | LinkageError failure) {
            tripCircuitBreaker("in-world pass", failure);
        } finally {
            RenderCost.stop(started);
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
        active = this;
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
        if (pipelines.refreshVertexLayouts()) discardCapturedGeometry();
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
        SceneKey global = keyFactory.buildGlobal(config, cameraPos, level,
                level.getMinY(), level.getMaxY(), worldRenderer.staticGatesFingerprintFor(groups),
                HappySnowmanSession.facePaint());
        // Each route's static geometry depends on its own key; only changed routes re-emit.
        long[] groupKeys = new long[groups.size()];
        Map<WaypointerConfig, Long> styleHashes = new IdentityHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            List<WaypointGroup> one = List.of(groups.get(i));
            long occlusion = raycastOcclusion ? worldRenderer.depthVisibilityFingerprintFor(one, cameraPos) : 0L;
            groupKeys[i] = keyFactory.groupKey(groups.get(i), config, styleHashes, occlusion,
                    worldRenderer.worldVisibilityFingerprintFor(one, cameraPos, playerPos),
                    worldRenderer.blockShapeFingerprintFor(one));
        }
        SceneKey key = SceneKeyFactory.combine(global, groupKeys);
        boolean rebuildAll = forceFullRebuild || !global.equals(lastGlobal);
        forceFullRebuild = false;
        // Static scopes: each route, and each route's far connector lines (which also follow the
        // camera's cell, see WaypointRenderer.routeLineCellOf).
        int cellX = WaypointRenderer.routeLineCellOf(cameraPos.x);
        int cellY = WaypointRenderer.routeLineCellOf(cameraPos.y);
        int cellZ = WaypointRenderer.routeLineCellOf(cameraPos.z);
        List<Object> scopes = new ArrayList<>(groups.size() * 2);
        Set<Object> rebuild = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Object, Long> keysNow = new IdentityHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            WaypointGroup group = groups.get(i);
            Object lines = worldRenderer.routeLineScope(group);
            long linesKey = SceneKey.builder().mix(groupKeys[i]).mix(cellX).mix(cellY).mix(cellZ).finish().hash();
            scopes.add(group);
            scopes.add(lines);
            noteScope(group, groupKeys[i], keysNow, rebuild);
            noteScope(lines, linesKey, keysNow, rebuild);
        }
        if (rebuildAll) fullRebuilds++;
        else if (!rebuild.isEmpty()) routeRebuilds++;
        if ((rebuildAll || !rebuild.isEmpty()) && options.debugLogging()) {
            Waypointer.LOGGER.info("[overlay] rebuild: {}", lastKey == SceneKey.NONE ? "first frame"
                    : rebuildAll ? "whole scene" : rebuild.size() + " of " + scopes.size() + " static scopes");
        }

        capture(ctx, key, rebuildAll ? null : rebuild, scopes);
        upload(scopes);
        lastKey = key;
        lastGlobal = global;
        lastScopeKeys = keysNow;

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
        long started = RenderCost.start();
        try {
            pass.draw(OverlayCompositing.POST_WORLD, pendingView, pendingKey, meshes, pipelines,
                    depthSnapshot.isValid() ? depthSnapshot.view() : null);
        } catch (RuntimeException | LinkageError failure) {
            tripCircuitBreaker("post-world pass", failure);
        } finally {
            RenderCost.stop(started);
        }
    }

    private void noteScope(Object scope, long key, Map<Object, Long> keysNow, Set<Object> rebuild) {
        keysNow.put(scope, key);
        Long previous = lastScopeKeys.get(scope);
        if (previous == null || previous != key) rebuild.add(scope);
    }

    private void capture(LevelRenderContext ctx, SceneKey key, Set<Object> rebuild,
                         List<Object> live) {
        if (captureSink == null) {
            capturePose = new PoseStack();
            captureSink = new MeshCaptureSink(pipelines, capturePose);
        }
        captureSink.beginCapture(rebuild, live);
        Vec3 origin = new Vec3(key.originX(), key.originY(), key.originZ());
        worldRenderer.emitWorldGeometryFor(ctx, capturePose, captureSink, origin,
                !options.occlusion().usesRaycast());
    }

    private void upload(List<Object> order) {
        CommandEncoder encoder = GpuMeshCompat.createCommandEncoder();
        for (Map.Entry<MeshBucket, MeshCaptureSink.Pair> entry : captureSink.buckets().entrySet()) {
            OverlayPass.MeshSet set = meshes.computeIfAbsent(entry.getKey(), OverlayPass.MeshSet::create);
            MeshCaptureSink.Pair pair = entry.getValue();
            if (captureSink.assembleStatics(pair, order)) {
                set.statics.upload(pair.statics, encoder);
            }
            set.dynamics.upload(pair.dynamics, encoder);
        }
    }

    private void discardCapturedGeometry() {
        for (OverlayPass.MeshSet set : meshes.values()) {
            closeStep("static overlay mesh", set.statics::close);
            closeStep("dynamic overlay mesh", set.dynamics::close);
        }
        meshes.clear();
        if (captureSink != null) closeStep("capture buffers", captureSink::close);
        captureSink = null;
        capturePose = null;
        lastKey = SceneKey.NONE;
        lastGlobal = SceneKey.NONE;
        lastScopeKeys = new IdentityHashMap<>();
    }

    private void ensureIrisAssignment(boolean shaderPack) {
        if (!shaderPack || effectiveCompositing != OverlayCompositing.IN_WORLD) return;
        IrisBridge iris = IrisBridge.get();
        if (!options.assignIrisPrograms() || !iris.isIrisLoaded()) {
            throw new IllegalStateException(
                    "Iris shader pack is active but pipeline assignment is disabled");
        }
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
        discardCapturedGeometry();
        closeStep("overlay pass", pass::close);
        closeStep("depth snapshot", depthSnapshot::close);
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
