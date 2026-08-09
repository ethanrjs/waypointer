package com.babbur.waypointer.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.babbur.waypointer.Waypointer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

public final class WaypointerRenderPipelines {

    private static RenderType linesThroughWalls;
    private static RenderType quadsThroughWalls;
    private static RenderType linesDepthTested;
    private static RenderType quadsDepthTested;
    private static RenderType beaconBeamThroughWalls;
    private static RenderType beaconBeamDepthTested;
    private static RenderPipeline paintedQuadsThroughWallsPipeline;
    private static RenderPipeline paintedQuadsDepthTestedPipeline;
    private static final DepthStencilState NO_DEPTH_TEST =
            new DepthStencilState(CompareOp.ALWAYS_PASS, false);
    private static final DepthStencilState DEPTH_TESTED =
            new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false);

    private WaypointerRenderPipelines() {}

    public static RenderType linesThroughWalls() {
        if (linesThroughWalls == null) linesThroughWalls = buildLinesType();
        return linesThroughWalls;
    }

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
                    DEPTH_TESTED);
        }
        return beaconBeamDepthTested;
    }

    public static RenderType paintedQuads(Identifier texture, boolean depthTested) {
        RenderPipeline pipeline = depthTested
                ? paintedQuadsDepthTestedPipeline()
                : paintedQuadsThroughWallsPipeline();
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", texture)
                .sortOnUpload()
                .createRenderSetup();
        return RenderType.create("waypointer_painted_quads_"
                + (depthTested ? "depth_" : "through_")
                + Integer.toUnsignedString(texture.hashCode()), setup);
    }

    private static RenderType buildLinesType() {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "lines_through_walls"))
                .withDepthStencilState(NO_DEPTH_TEST)
                .build();
        return RenderType.create("waypointer_lines_through_walls",
                RenderSetup.builder(pipeline).createRenderSetup());
    }

    private static RenderPipeline paintedQuadsThroughWallsPipeline() {
        if (paintedQuadsThroughWallsPipeline == null) {
            paintedQuadsThroughWallsPipeline = buildPaintedQuadsPipeline(
                    "painted_quads_through_walls", NO_DEPTH_TEST);
        }
        return paintedQuadsThroughWallsPipeline;
    }

    private static RenderPipeline paintedQuadsDepthTestedPipeline() {
        if (paintedQuadsDepthTestedPipeline == null) {
            paintedQuadsDepthTestedPipeline = buildPaintedQuadsPipeline(
                    "painted_quads_depth_tested", DEPTH_TESTED);
        }
        return paintedQuadsDepthTestedPipeline;
    }

    private static RenderPipeline buildPaintedQuadsPipeline(String path,
                                                             DepthStencilState depthState) {
        return RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, path))
                .withDepthStencilState(depthState)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .build();
    }

    private static RenderType buildQuadsType() {
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
                .withDepthStencilState(DEPTH_TESTED)
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
                .withDepthStencilState(DEPTH_TESTED)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .build();
        return RenderType.create("waypointer_quads_depth_tested",
                RenderSetup.builder(pipeline).createRenderSetup());
    }

}
