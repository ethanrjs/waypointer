package dev.ethan.waypointer.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.ethan.waypointer.Waypointer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Custom line pipeline for Waypointer.
 *
 * Vanilla {@code RenderTypes.lines()} uses {@code LEQUAL_DEPTH_TEST}, so lines
 * occlude behind terrain. That's right for block outlines but wrong for a waypoint
 * beacon -- if you can't see the marker through a cave wall the beacon isn't
 * doing its job.
 *
 * We reuse {@link RenderPipelines#LINES_SNIPPET} (exposed via access widener) to
 * inherit the line shader + vertex format, then flip depth testing off.
 *
 * Built lazily: {@link RenderPipelines} static init has to finish before we derive
 * new pipelines from its snippets.
 */
public final class WaypointerRenderPipelines {

    private static RenderType linesThroughWalls;

    private WaypointerRenderPipelines() {}

    /** Opaque lines that ignore the depth buffer -- visible through terrain. */
    public static RenderType linesThroughWalls() {
        if (linesThroughWalls == null) linesThroughWalls = buildLinesType();
        return linesThroughWalls;
    }

    private static RenderType buildLinesType() {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "lines_through_walls"))
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build();
        return RenderType.create("waypointer_lines_through_walls",
                RenderSetup.builder(pipeline).createRenderSetup());
    }
}
