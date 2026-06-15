package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

final class DungeonRoomCoreScanner implements DungeonRoomData.CoreHashLookup {

    private static final int ROOM_CENTER_OFFSET = 15;
    private static final int MIN_SCAN_Y = 11;
    private static final int LOWEST_BLOCK_Y = 12;
    private static final int MAX_SCAN_Y = 140;
    private static final int TOP_SEARCH_Y = 160;
    private static final int EARLY_AIR_BREAK_Y = 69;

    private final ClientLevel level;
    private final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

    DungeonRoomCoreScanner(ClientLevel level) {
        this.level = level;
    }

    @Override
    public List<Integer> coreHashesFor(DungeonRoom room) {
        if (room == null || room.segments().isEmpty()) return List.of();

        List<Integer> hashes = new ArrayList<>(room.segments().size());
        for (long packedSegment : room.segments()) {
            hashes.add(coreHashForSegment(packedSegment));
        }
        return hashes;
    }

    /*[[AI-FN-DOC
Function:
DungeonRoomCoreScanner.coreHashForSegment
Purpose:
Compute the stable Odin-style vertical room-core hash for one 32x32 dungeon segment.
Why this exists:
Room identity is determined from the center-column block skeleton of a segment, and callers need a reusable primitive for both current-room matching and adjacent-component expansion.
When to use:
Use when the caller has a packed DungeonRoom segment corner and needs to identify which authored room core occupies that segment; do not use for non-dungeon worlds or arbitrary block positions.
Inputs:
packedSegment is a DungeonRoom packed long whose X and Z values are the north-west corner of a 32x32 dungeon segment.
Outputs:
Returns the Java String.hashCode value for the normalized vertical block skeleton at that segment's center.
Side effects:
Reads chunk/block state from the client level through the scanner's mutable BlockPos; does not mutate world state.
Failure modes:
Unloaded or empty chunks can produce a hash that does not match the catalog, which callers should treat as an unknown segment rather than a fatal error.
Important invariants:
The center offset, top-layer search, ignored blocks, zero padding, bedrock-air early break, and hash algorithm must remain compatible with the bundled Odin-derived core hashes.
Internal logic:
Unpack the segment corner, offset to the center probe column, read the containing chunk, find the top room layer, then hash the normalized vertical column at that height.
Pseudocode:
centerX = segmentX + center offset
centerZ = segmentZ + center offset
chunk = level chunk containing center
roomHeight = topLayerAt(centerX, centerZ, chunk)
return coreHashAtHeight(centerX, centerZ, roomHeight, chunk)
Implementation notes:
This method is package-private so DungeonStateTracker can perform Odin-style direct core scans without constructing temporary DungeonRoom objects for every adjacent segment.
AI self-check:
Verify the method preserves the previous hashing behavior exactly and only broadens access for scanner users in the dungeon package.
]]*/
    int coreHashForSegment(long packedSegment) {
        int centerX = DungeonRoom.segmentX(packedSegment) + ROOM_CENTER_OFFSET;
        int centerZ = DungeonRoom.segmentZ(packedSegment) + ROOM_CENTER_OFFSET;
        LevelChunk chunk = level.getChunk(centerX >> 4, centerZ >> 4);
        int roomHeight = topLayerAt(centerX, centerZ, chunk);
        return coreHashAtHeight(centerX, centerZ, roomHeight, chunk);
    }

    private int topLayerAt(int centerX, int centerZ, LevelChunk chunk) {
        for (int y = TOP_SEARCH_Y; y >= LOWEST_BLOCK_Y; y--) {
            mutableBlockPos.set(centerX, y, centerZ);
            BlockState state = chunk.getBlockState(mutableBlockPos);
            if (!state.isAir()) {
                return state.is(Blocks.GOLD_BLOCK) ? y - 1 : y;
            }
        }
        return 0;
    }

    private int coreHashAtHeight(int centerX, int centerZ, int roomHeight, LevelChunk chunk) {
        StringBuilder builder = new StringBuilder(150);
        int clampedHeight = Math.max(MIN_SCAN_Y, Math.min(MAX_SCAN_Y, roomHeight));
        appendZeros(builder, MAX_SCAN_Y - clampedHeight);

        int consecutiveBedrock = 0;
        for (int y = clampedHeight; y >= LOWEST_BLOCK_Y; y--) {
            mutableBlockPos.set(centerX, y, centerZ);
            Block block = chunk.getBlockState(mutableBlockPos).getBlock();

            if (block == Blocks.AIR && consecutiveBedrock >= 2 && y < EARLY_AIR_BREAK_Y) {
                appendZeros(builder, y - MIN_SCAN_Y);
                break;
            }

            if (block == Blocks.BEDROCK) {
                consecutiveBedrock++;
            } else {
                consecutiveBedrock = 0;
                if (ignoredCoreBlock(block)) continue;
            }

            builder.append(block);
        }

        return builder.toString().hashCode();
    }

    private static void appendZeros(StringBuilder builder, int count) {
        for (int i = 0; i < count; i++) {
            builder.append('0');
        }
    }

    private static boolean ignoredCoreBlock(Block block) {
        return block == Blocks.OAK_PLANKS
                || block == Blocks.TRAPPED_CHEST
                || block == Blocks.CHEST;
    }
}
