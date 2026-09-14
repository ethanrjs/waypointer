package com.babbur.waypointer.input;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointPlacementRayTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void emptyWorldUsesReachEndpointIncludingNegativeCoordinates() {
        BlockGetter level = new SingleBlock(Blocks.AIR.defaultBlockState());
        Vec3 eye = new Vec3(-0.25, 72.375, -0.5625);
        for (double reach : new double[] { 4.5, 5.0, 8.0 }) {
            BlockHitResult hit = WaypointRepositionMode.placementHit(level, eye,
                    new Vec3(0, 0, -1), reach, CollisionContext.empty());
            Vec3 end = new Vec3(eye.x, eye.y, eye.z - reach);
            assertEquals(HitResult.Type.MISS, hit.getType());
            assertEquals(end, hit.getLocation());
            assertEquals(BlockPos.containing(end), WaypointRepositionMode.placementBlock(hit));
            assertEquals(new WaypointRepositionMode.PreciseTarget(-4, 1158,
                            (int) Math.round(end.z * 16)),
                    WaypointRepositionMode.placementPrecise(hit));
        }
    }

    @Test
    void configuredReachExtendsMidAirPlacementButNeverShortensVanillaReach() {
        var config = new com.babbur.waypointer.config.WaypointerConfig();
        assertEquals(5.0, WaypointRepositionMode.placementReach(config, 4.5));
        assertEquals(5.0, WaypointRepositionMode.placementReach(null, 4.5));
        assertEquals(6.0, WaypointRepositionMode.placementReach(config, 6.0));
        config.setEditModePlacementReach(32.0);
        assertEquals(32.0, WaypointRepositionMode.placementReach(config, 4.5));
        BlockGetter level = new SingleBlock(Blocks.AIR.defaultBlockState());
        Vec3 eye = new Vec3(0.5, 80.0, 0.5);
        BlockHitResult hit = WaypointRepositionMode.placementHit(level, eye, new Vec3(0, 0, 1),
                WaypointRepositionMode.placementReach(config, 4.5), CollisionContext.empty());
        assertEquals(HitResult.Type.MISS, hit.getType());
        assertEquals(new BlockPos(0, 80, 32), WaypointRepositionMode.placementBlock(hit));
    }

    @Test
    void wheelOffsetPullsTheTargetAlongTheViewRayWithinSafeBounds() {
        Vec3 eye = new Vec3(0.5, 80.0, 0.5);
        Vec3 direction = new Vec3(0, 0, 1);
        BlockHitResult block = new BlockHitResult(new Vec3(0.5, 80.0, 5.0),
                net.minecraft.core.Direction.NORTH, new BlockPos(0, 80, 5), false);
        assertEquals(block, WaypointRepositionMode.applyPlacementOffset(block, eye, direction, 0, 64.0));
        BlockHitResult closer = WaypointRepositionMode.applyPlacementOffset(block, eye, direction, 1, 64.0);
        assertEquals(HitResult.Type.MISS, closer.getType());
        assertEquals(new Vec3(0.5, 80.0, 4.0), closer.getLocation());
        assertEquals(new BlockPos(0, 80, 4), WaypointRepositionMode.placementBlock(closer));
        // Scrolling up against a block never passes through it.
        assertEquals(new Vec3(0.5, 80.0, 5.0),
                WaypointRepositionMode.applyPlacementOffset(block, eye, direction, -3, 64.0).getLocation());
        // Scrolling down never reaches the player's head.
        assertEquals(new Vec3(0.5, 80.0, 1.5),
                WaypointRepositionMode.applyPlacementOffset(block, eye, direction, 40, 64.0).getLocation());

        int steps = 0;
        for (int i = 0; i < 10; i++) steps = WaypointRepositionMode.nextPlacementOffset(steps, -1, true, 4.5, 64.0);
        assertEquals(3, steps, "4.5 blocks away leaves three whole notches before the minimum");
        assertEquals(0, WaypointRepositionMode.nextPlacementOffset(0, 1, true, 4.5, 64.0));
        assertEquals(1, WaypointRepositionMode.nextPlacementOffset(2, 1, true, 4.5, 64.0));

        BlockHitResult air = BlockHitResult.miss(new Vec3(0.5, 80.0, 5.5),
                net.minecraft.core.Direction.UP, new BlockPos(0, 80, 5));
        int out = WaypointRepositionMode.nextPlacementOffset(0, 1, false, 5.0, 64.0);
        assertEquals(-1, out);
        assertEquals(new Vec3(0.5, 80.0, 6.5),
                WaypointRepositionMode.applyPlacementOffset(air, eye, direction, out, 64.0).getLocation());
        int far = 0;
        for (int i = 0; i < 100; i++) far = WaypointRepositionMode.nextPlacementOffset(far, 1, false, 5.0, 64.0);
        assertEquals(-59, far, "open air stops at the maximum placement reach");
        assertEquals(64.0, WaypointRepositionMode.placementDistance(air, eye, far, 64.0));
    }

    @Test
    void selectableShapesIncludeThinNonCollidingAndPartialBlocks() {
        for (BlockState state : new BlockState[] {
                Blocks.STONE.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(),
                Blocks.GLASS_PANE.defaultBlockState(),
                Blocks.OAK_TRAPDOOR.defaultBlockState(),
                Blocks.TORCH.defaultBlockState(),
                Blocks.LADDER.defaultBlockState(),
                Blocks.OAK_BUTTON.defaultBlockState(),
                Blocks.LEVER.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(),
                Blocks.MOSS_CARPET.defaultBlockState()
        }) {
            BlockGetter level = new SingleBlock(state);
            AABB shape = state.getShape(level, BlockPos.ZERO).bounds();
            double x = (shape.minX + shape.maxX) / 2;
            double z = (shape.minZ + shape.maxZ) / 2;
            BlockHitResult hit = WaypointRepositionMode.placementHit(level,
                    new Vec3(x, 3, z), new Vec3(0, -1, 0), 4, CollisionContext.empty());
            assertEquals(HitResult.Type.BLOCK, hit.getType(), state.toString());
            assertEquals(BlockPos.ZERO, WaypointRepositionMode.placementBlock(hit), state.toString());
            assertTrue(hit.getLocation().y >= shape.minY && hit.getLocation().y <= shape.maxY,
                    state.toString());
        }
    }

    @Test
    void rayPassesThroughEmptyPartOfSlabInsteadOfSelectingFullBlockCube() {
        BlockGetter level = new SingleBlock(Blocks.OAK_SLAB.defaultBlockState());
        BlockHitResult gap = WaypointRepositionMode.placementHit(level,
                new Vec3(-1, 0.75, 0.5), new Vec3(1, 0, 0), 3, CollisionContext.empty());
        assertEquals(HitResult.Type.MISS, gap.getType());
        assertEquals(new BlockPos(2, 0, 0), WaypointRepositionMode.placementBlock(gap));

        BlockHitResult solid = WaypointRepositionMode.placementHit(level,
                new Vec3(-1, 0.25, 0.5), new Vec3(1, 0, 0), 3, CollisionContext.empty());
        assertEquals(HitResult.Type.BLOCK, solid.getType());
        assertEquals(BlockPos.ZERO, WaypointRepositionMode.placementBlock(solid));
    }

    @Test
    void blockBeyondInteractionReachDoesNotReplaceAirEndpoint() {
        BlockHitResult hit = WaypointRepositionMode.placementHit(
                new SingleBlock(Blocks.STONE.defaultBlockState()),
                new Vec3(-2, 0.5, 0.5), new Vec3(1, 0, 0), 1, CollisionContext.empty());
        assertEquals(HitResult.Type.MISS, hit.getType());
        assertEquals(new BlockPos(-1, 0, 0), WaypointRepositionMode.placementBlock(hit));
    }

    @Test
    void sourceAndFlowingFluidsRemainSelectable() {
        for (BlockState state : new BlockState[] {
                Blocks.WATER.defaultBlockState(),
                Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 4),
                Blocks.LAVA.defaultBlockState()
        }) {
            BlockHitResult hit = WaypointRepositionMode.placementHit(new SingleBlock(state),
                    new Vec3(0.5, 2, 0.5), new Vec3(0, -1, 0), 3, CollisionContext.empty());
            assertEquals(HitResult.Type.BLOCK, hit.getType(), state.toString());
            assertEquals(BlockPos.ZERO, WaypointRepositionMode.placementBlock(hit), state.toString());
        }
    }

    private record SingleBlock(BlockState state) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(BlockPos.ZERO) ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

        @Override
        public int getHeight() { return 384; }

        @Override
        public int getMinY() { return -64; }
    }
}
