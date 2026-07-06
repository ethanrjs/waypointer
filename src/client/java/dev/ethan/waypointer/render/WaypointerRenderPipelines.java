package dev.ethan.waypointer.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import dev.ethan.waypointer.Waypointer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Custom render pipelines for Waypointer.
 *
 * <p>Vanilla's stock pipelines (e.g. {@code RenderTypes.lines()}) use
 * {@code LEQUAL_DEPTH_TEST} so geometry occludes behind terrain. Waypoint
 * overlays intentionally pierce terrain so markers remain usable through caves
 * and walls.
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
    private static RenderType linesDepthTested;
    private static RenderType quadsDepthTested;
    private static RenderType beaconBeamThroughWalls;
    private static RenderType beaconBeamDepthTested;
    private static final DepthStencilState NO_DEPTH_TEST = new DepthStencilState(CompareOp.ALWAYS_PASS, false);
    private static final DepthStencilState LEQUAL_DEPTH_TEST = new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false);

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

    public static RenderType linesDepthTested() {
        if (linesDepthTested == null) linesDepthTested = buildDepthTestedLinesType();
        return linesDepthTested;
    }

    public static RenderType quadsDepthTested() {
        if (quadsDepthTested == null) quadsDepthTested = buildDepthTestedQuadsType();
        return quadsDepthTested;
    }

    public static RenderType beaconBeamThroughWalls() {
        if (beaconBeamThroughWalls == null) {
            beaconBeamThroughWalls = buildBeaconBeamType(
                    "waypointer_beacon_beam_through_walls",
                    "beacon_beam_through_walls",
                    NO_DEPTH_TEST);
        }
        return beaconBeamThroughWalls;
    }

    public static RenderType beaconBeamDepthTested() {
        if (beaconBeamDepthTested == null) {
            beaconBeamDepthTested = buildBeaconBeamType(
                    "waypointer_beacon_beam_depth_tested",
                    "beacon_beam_depth_tested",
                    LEQUAL_DEPTH_TEST);
        }
        return beaconBeamDepthTested;
    }

    private static RenderType buildLinesType() {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "lines_through_walls"))
                .withDepthStencilState(NO_DEPTH_TEST)
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
                .withDepthStencilState(NO_DEPTH_TEST)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .build();
        return RenderType.create("waypointer_quads_through_walls",
                RenderSetup.builder(pipeline).createRenderSetup());
    }

    private static RenderType buildDepthTestedLinesType() {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "lines_depth_tested"))
                .withDepthStencilState(LEQUAL_DEPTH_TEST)
                .build();
        return RenderType.create("waypointer_lines_depth_tested",
                RenderSetup.builder(pipeline).createRenderSetup());
    }

    private static RenderType buildBeaconBeamType(String renderTypeName,
                                                  String pipelinePath,
                                                  DepthStencilState depthState) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, pipelinePath))
                .withDepthStencilState(depthState)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", BeaconRenderer.BEAM_LOCATION)
                .sortOnUpload()
                .createRenderSetup();
        return RenderType.create(renderTypeName, setup);
    }

    private static RenderType buildDepthTestedQuadsType() {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "quads_depth_tested"))
                .withDepthStencilState(LEQUAL_DEPTH_TEST)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .build();
        return RenderType.create("waypointer_quads_depth_tested",
                RenderSetup.builder(pipeline).createRenderSetup());
    }

}
