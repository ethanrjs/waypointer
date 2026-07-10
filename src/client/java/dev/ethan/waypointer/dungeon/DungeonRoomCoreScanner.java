package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
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
    private static final int EARLY_AIR_BREAK_Y = 69;

    private final ClientLevel level;
    private final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

    DungeonRoomCoreScanner(ClientLevel level) {
        this.level = level;
    }

    @Override
    public List<Integer> coreHashesFor(DungeonRoom room) {
        if (room == null || room.segments().isEmpty()) return List.of();

        List<DungeonCoreSignature> signatures = new ArrayList<>(room.segments().size());
        for (long packedSegment : room.segments()) {
            signatures.add(coreSignatureForSegment(packedSegment));
        }
        return coreHashesFromSignatures(signatures);
    }

    static List<Integer> coreHashesFromSignatures(List<DungeonCoreSignature> signatures) {
        if (signatures == null || signatures.isEmpty()) return List.of();
        List<Integer> hashes = new ArrayList<>(signatures.size());
        for (DungeonCoreSignature signature : signatures) {
            if (signature == null || signature.topY() == 0 || signature.sampleCount() == 0) {
                return List.of();
            }
            hashes.add(signature.hash());
        }
        return List.copyOf(hashes);
    }

    int coreHashForSegment(long packedSegment) {
        return coreSignatureForSegment(packedSegment).hash();
    }

    static DungeonCoreSignature coreSignatureForChunk(LevelChunk chunk) {
        if (chunk == null) return DungeonCoreSignature.UNKNOWN;
        int centerX = chunk.getPos().x() * 16 + 7;
        int centerZ = chunk.getPos().z() * 16 + 7;
        return new DungeonRoomCoreScanner(null).coreSignatureAt(centerX, centerZ, chunk);
    }

    DungeonCoreSignature coreSignatureForSegment(long packedSegment) {
        if (level == null) return DungeonCoreSignature.UNKNOWN;
        int centerX = DungeonRoom.segmentX(packedSegment) + ROOM_CENTER_OFFSET;
        int centerZ = DungeonRoom.segmentZ(packedSegment) + ROOM_CENTER_OFFSET;
        int chunkX = centerX >> 4;
        int chunkZ = centerZ >> 4;
        if (!level.hasChunk(chunkX, chunkZ)) return DungeonCoreSignature.UNKNOWN;
        return coreSignatureAt(centerX, centerZ, level.getChunk(chunkX, chunkZ));
    }

    private DungeonCoreSignature coreSignatureAt(int centerX, int centerZ, LevelChunk chunk) {
        StringBuilder builder = new StringBuilder(150);
        boolean foundHighest = false;
        int roofY = 0;
        int consecutiveBedrock = 0;
        int sampleCount = 0;
        for (int y = MAX_SCAN_Y; y >= LOWEST_BLOCK_Y; y--) {
            mutableBlockPos.set(centerX, y, centerZ);
            BlockState state = chunk.getBlockState(mutableBlockPos);
            Block block = state.getBlock();

            if (!foundHighest) {
                if (state.isAir() || block == Blocks.GOLD_BLOCK) {
                    builder.append('0');
                    sampleCount++;
                    continue;
                }
                foundHighest = true;
                roofY = y;
            }

            if (state.isAir() && consecutiveBedrock >= 2 && y < EARLY_AIR_BREAK_Y) {
                int trailingZeros = Math.max(0, y - MIN_SCAN_Y);
                appendZeros(builder, trailingZeros);
                sampleCount += trailingZeros;
                break;
            }

            if (block == Blocks.BEDROCK) {
                consecutiveBedrock++;
            } else {
                consecutiveBedrock = 0;
                if (ignoredCoreBlock(block)) continue;
            }

            builder.append(block);
            sampleCount++;
        }

        return new DungeonCoreSignature(builder.toString().hashCode(), roofY, sampleCount);
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
