package dev.ethan.waypointer.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.ethan.waypointer.Waypointer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Custom render pipelines for Waypointer.
 *
 * <p>Vanilla's stock pipelines (e.g. {@code RenderTypes.lines()}) use
 * {@code LEQUAL_DEPTH_TEST} so geometry occludes behind terrain. That's right
 * for block outlines but wrong for a waypoint beacon -- if you can't see the
 * marker through a cave wall the beacon isn't doing its job.
 *
 * <p>We reuse the private {@code *_SNIPPET} base pipelines (exposed via
 * access widener) to inherit the correct shader + vertex format, then flip
 * depth testing off and -- for filled boxes -- force translucent blending.
 *
 * <p>Pipelines are built lazily because {@link RenderPipelines} static init
 * has to finish before we derive new pipelines from its snippets.
 */
public final class WaypointerRenderPipelines {

    private static RenderType linesThroughWalls;
    private static RenderType quadsThroughWalls;

    private WaypointerRenderPipelines() {}

    /** Opaque lines that ignore the depth buffer -- visible through terrain. */
    public static RenderType linesThroughWalls() {
        if (linesThroughWalls == null) linesThroughWalls = buildLinesType();
        return linesThroughWalls;
    }

    /**
     * Translucent coloured quads that ignore the depth buffer. Used by the
     * filled box style so the cube fill reads through terrain the same way
     * the line outline does.
     */
    public static RenderType quadsThroughWalls() {
        if (quadsThroughWalls == null) quadsThroughWalls = buildQuadsType();
        return quadsThroughWalls;
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

    private static RenderType buildQuadsType() {
        // DEBUG_FILLED_SNIPPET is the shared base vanilla uses for DEBUG_FILLED_BOX
        // / DEBUG_QUADS -- POSITION_COLOR vertex format, position_color shader.
        // We layer translucent blending on top (the snippet is opaque) and disable
        // the depth test/write so the fill is visible through walls like the lines.
        // Backface culling also stays off because emitFilledBox emits all six faces
        // in a single winding; culling would make back faces pop out when looking
        // around the cube.
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "quads_through_walls"))
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(false)
                .build();
        return RenderType.create("waypointer_quads_through_walls",
                RenderSetup.builder(pipeline).createRenderSetup());
    }
}
