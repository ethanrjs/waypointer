package com.babbur.waypointer.render.gpu;

/** Counters from the most recent overlay frame. */
public final class OverlayFrameStats {

    private volatile long frame;
    private volatile boolean staticRebuilt;
    private volatile String rebuildReason = "";
    private volatile int bucketsDrawn;
    private volatile int drawCalls;
    private volatile int staticVertices;
    private volatile int dynamicVertices;
    private volatile int uploadedBytes;
    private volatile int unknownRenderTypes;
    private volatile long captureNanos;
    private volatile long uploadNanos;
    private volatile long drawNanos;
    private volatile String compositing = "";
    private volatile boolean shaderPackActive;
    private volatile boolean skippedShadowPass;

    public record Snapshot(long frame, boolean staticRebuilt, String rebuildReason,
                           int bucketsDrawn, int drawCalls, int staticVertices,
                           int dynamicVertices, int uploadedBytes, int unknownRenderTypes,
                           long captureNanos, long uploadNanos, long drawNanos,
                           String compositing, boolean shaderPackActive,
                           boolean skippedShadowPass) {}

    public Snapshot snapshot() {
        return new Snapshot(frame, staticRebuilt, rebuildReason, bucketsDrawn, drawCalls,
                staticVertices, dynamicVertices, uploadedBytes, unknownRenderTypes,
                captureNanos, uploadNanos, drawNanos, compositing, shaderPackActive,
                skippedShadowPass);
    }

    void beginFrame(long frameIndex, OverlayCompositing mode, boolean shaderPack) {
        frame = frameIndex;
        staticRebuilt = false;
        rebuildReason = "";
        bucketsDrawn = 0;
        drawCalls = 0;
        staticVertices = 0;
        dynamicVertices = 0;
        uploadedBytes = 0;
        unknownRenderTypes = 0;
        captureNanos = 0L;
        uploadNanos = 0L;
        drawNanos = 0L;
        compositing = mode == null ? "" : mode.name();
        shaderPackActive = shaderPack;
        skippedShadowPass = false;
    }

    void recordRebuild(String reason) {
        staticRebuilt = true;
        rebuildReason = reason == null ? "" : reason;
    }

    void recordCapture(long nanos, int unknown) {
        captureNanos = nanos;
        unknownRenderTypes = unknown;
    }

    void recordUpload(long nanos, int bytes, int staticVerts, int dynamicVerts) {
        uploadNanos += nanos;
        uploadedBytes += bytes;
        staticVertices += staticVerts;
        dynamicVertices += dynamicVerts;
    }

    void recordDraw(long nanos, int buckets, int calls) {
        drawNanos = nanos;
        bucketsDrawn = buckets;
        drawCalls = calls;
    }

    void recordShadowPassSkip() {
        skippedShadowPass = true;
    }
}
