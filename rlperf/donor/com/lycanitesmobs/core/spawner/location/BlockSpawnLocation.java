package com.lycanitesmobs.core.spawner.location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * Donor for Lycanites Mobs' BlockSpawnLocation. Only getSpawnPositions and rlperf$matches (plus
 * the rlperf$ fields) are copied into the real class; the other fields are declared so this
 * compiles and must match the real ones.
 *
 * The original walks every block in range and, for each one, looks the block up twice, asks
 * whether it can see the sky, builds its registry name as a new string and searches the id list
 * for it. This version finds the same positions in the same order: one lookup per block through
 * the chunk it is in, the id test remembered per block type, and the cheap tests first.
 */
public class BlockSpawnLocation extends SpawnLocation {
    public List<String> blockIds;
    public String listType;
    public boolean surface;
    public boolean underground;
    public int blockCost;
    public int requiredBlockTypes;
    public Vec3i offset;

    private IdentityHashMap<Block, Boolean> rlperf$memo;
    private List<String> rlperf$memoIds;
    private String rlperf$memoType;

    /** The white/blacklist test from isValidBlock, remembered per block type. */
    private boolean rlperf$matches(Block block) {
        if (this.blockIds.isEmpty()) return true;
        if (this.rlperf$memo == null || this.rlperf$memoIds != this.blockIds || this.rlperf$memoType != this.listType) {
            this.rlperf$memo = new IdentityHashMap<Block, Boolean>();
            this.rlperf$memoIds = this.blockIds;
            this.rlperf$memoType = this.listType;
        }
        Boolean known = this.rlperf$memo.get(block);
        if (known == null) {
            boolean listed = this.blockIds.contains(block.getRegistryName().toString());
            known = "blacklist".equalsIgnoreCase(this.listType) ? !listed : listed;
            this.rlperf$memo.put(block, known);
        }
        return known;
    }

    @Override
    public List<BlockPos> getSpawnPositions(World world, EntityPlayer player, BlockPos triggerPos) {
        ArrayList<BlockPos> spawnPositions = new ArrayList<BlockPos>();
        HashMap<Block, Integer> validBlocksFound = new HashMap<Block, Integer>();
        boolean anyLight = this.surface && this.underground;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        Chunk chunk = null;
        int chunkX = 0, chunkZ = 0;
        for (int y = triggerPos.func_177956_o() - this.rangeMax.func_177956_o(); y <= triggerPos.func_177956_o() + this.rangeMax.func_177956_o(); ++y) {
            int yMin = 0;
            if (this.yMin >= 0) {
                yMin = this.yMin;
            }
            if (y < yMin) {
                y = yMin;
            }
            int yMax = world.func_72940_L();
            if (this.yMax >= 0) {
                yMax = Math.min(this.yMax, yMax);
            }
            if (y >= yMax) break;
            for (int x = triggerPos.func_177958_n() - this.rangeMax.func_177958_n(); x <= triggerPos.func_177958_n() + this.rangeMax.func_177958_n(); ++x) {
                for (int z = triggerPos.func_177952_p() - this.rangeMax.func_177952_p(); z <= triggerPos.func_177952_p() + this.rangeMax.func_177952_p(); ++z) {
                    IBlockState blockState;
                    if (y < 256) {
                        if (chunk == null || chunkX != x >> 4 || chunkZ != z >> 4) {
                            chunkX = x >> 4;
                            chunkZ = z >> 4;
                            chunk = world.func_72964_e(chunkX, chunkZ);
                        }
                        blockState = chunk.func_186032_a(x, y, z);
                    } else {
                        blockState = world.func_180495_p(probe.func_181079_c(x, y, z));
                    }
                    Block block = blockState.func_177230_c();
                    if (!this.rlperf$matches(block)) continue;
                    if (block instanceof BlockLiquid && block.func_176201_c(blockState) != 0) continue;
                    if (block instanceof IFluidBlock) {
                        float filled = ((IFluidBlock) block).getFilledPercentage(world, new BlockPos(x, y, z));
                        if (filled != 1.0f && filled != -1.0f) continue;
                    }
                    if (!anyLight && (world.func_175678_i(probe.func_181079_c(x, y, z)) ? !this.surface : !this.underground)) continue;
                    BlockPos spawnPos = new BlockPos(x, y, z);
                    spawnPositions.add(spawnPos.func_177971_a(this.offset));
                    if (this.requiredBlockTypes <= 0) continue;
                    if (!validBlocksFound.containsKey(block)) {
                        validBlocksFound.put(block, 1);
                        continue;
                    }
                    validBlocksFound.put(block, (Integer) validBlocksFound.get(block) + 1);
                }
            }
        }
        if (this.blockCost > 0 && spawnPositions.size() < this.blockCost) {
            return new ArrayList<BlockPos>();
        }
        if (this.requiredBlockTypes > 0) {
            if (validBlocksFound.size() < this.blockIds.size()) {
                return new ArrayList<BlockPos>();
            }
            Iterator<Integer> iterator = validBlocksFound.values().iterator();
            while (iterator.hasNext()) {
                int blocksFoundOfType = iterator.next();
                if (blocksFoundOfType >= this.requiredBlockTypes) continue;
                return new ArrayList<BlockPos>();
            }
        }
        return this.sortSpawnPositions(spawnPositions, world, triggerPos);
    }
}
