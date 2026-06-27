/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princeps is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princeps.  If not, see <https://www.gnu.org/licenses/>.
 */

package princeps.process.elytra;

import princeps.Princeps;
import princeps.api.event.events.BlockChangeEvent;
import princeps.api.utils.BetterBlockPos;
import princeps.utils.accessor.IPalettedContainer;
import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;
import dev.babbaj.pathfinder.PathSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.SoftReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author Brady
 */
public final class NetherPathfinderContext {

    private static final BlockState AIR_BLOCK_STATE = Blocks.AIR.defaultBlockState();
    // This lock must be held while there are active pointers to chunks in java,
    // but we just hold it for the entire tick so we don't have to think much about it.
    public final Object cullingLock = new Object();

    // Visible for access in BlockStateOctreeInterface
    final long context;
    private final long seed;
    // Dimension model for the native pathfinder. The octree is 0-based over [0, height); world Y maps
    // via octreeY = worldY - minY. Nether/End have minY==0 (the offset is a no-op, so their behaviour
    // is identical to the original Nether-only code); only the Overworld carries a real offset (+64).
    final int dimension;   // NetherPathfinder.DIMENSION_*
    final int minY;        // world min build Y for this dimension (== octree 0)
    final int height;      // flyable height fed to the native context (Nether 128, End 256, Overworld 384)
    private final ExecutorService executor;

    public NetherPathfinderContext(long seed, int dimension, int minY, int height) {
        this.context = NetherPathfinder.newContext(seed, null, dimension, height, false);
        this.seed = seed;
        this.dimension = dimension;
        this.minY = minY;
        this.height = height;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Builds a context configured for the given dimension's flyable space:
     * Nether {@code [0,128)} (roof kept — identical to the original behaviour), End {@code [0,256)},
     * anything else (Overworld / modded) the world's real {@code [minY, minY+height)}.
     */
    public static NetherPathfinderContext forLevel(final long seed, final Level level) {
        final int dim;
        final int minY;
        final int height;
        if (level.dimension() == Level.NETHER) {
            dim = NetherPathfinder.DIMENSION_NETHER;
            minY = 0;
            height = 128;
        } else if (level.dimension() == Level.END) {
            dim = NetherPathfinder.DIMENSION_END;
            minY = 0;
            height = 256;
        } else {
            dim = NetherPathfinder.DIMENSION_OVERWORLD;
            minY = level.getMinY();
            height = level.getHeight();
        }
        return new NetherPathfinderContext(seed, dim, minY, height);
    }

    public boolean hasChunk(ChunkPos pos) {
        return NetherPathfinder.hasChunkFromJava(this.context, pos.x(), pos.z());
    }

    public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks, BlockStateOctreeInterface boi) {
        this.executor.execute(() -> {
            synchronized (this.cullingLock) {
                boi.chunkPtr = 0L;
                NetherPathfinder.cullFarChunks(this.context, chunkX, chunkZ, maxDistanceBlocks);
            }
        });
    }

    public void queueForPacking(final LevelChunk chunkIn) {
        final SoftReference<LevelChunk> ref = new SoftReference<>(chunkIn);
        this.executor.execute(() -> {
            // TODO: Prioritize packing recent chunks and/or ones that the path goes through,
            //       and prune the oldest chunks per chunkPackerQueueMaxSize
            final LevelChunk chunk = ref.get();
            if (chunk != null) {
                long ptr = NetherPathfinder.allocateAndInsertChunk(this.context, chunk.getPos().x(), chunk.getPos().z());
                writeChunkData(chunk, ptr, this.height);
                // mark the chunk as supplied from java so the pathfinder uses it instead of generating
                NetherPathfinder.setChunkState(this.context, chunk.getPos().x(), chunk.getPos().z(), true);
            }
        });
    }

    public void queueBlockUpdate(BlockChangeEvent event) {
        this.executor.execute(() -> {
            ChunkPos chunkPos = event.getChunkPos();
            long ptr = NetherPathfinder.getChunk(this.context, chunkPos.x(), chunkPos.z());
            if (ptr == 0) return; // this shouldn't ever happen
            event.getBlocks().forEach(pair -> {
                BlockPos pos = pair.first();
                final int ry = pos.getY() - this.minY;          // octree-relative Y
                if (ry < 0 || ry >= this.height) return;
                boolean isSolid = pair.second() != AIR_BLOCK_STATE;
                Octree.setBlock(ptr, pos.getX() & 15, ry, pos.getZ() & 15, isSolid);
            });
        });
    }

    public CompletableFuture<PathSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        return CompletableFuture.supplyAsync(() -> {
            // Terrain prediction uses the Nether generator; outside the Nether force "assume air for
            // ungenerated chunks" so we only path on the real (packed) world.
            final boolean assumeAir = this.dimension != NetherPathfinder.DIMENSION_NETHER
                    || !Princeps.settings().elytraPredictTerrain.value;
            final PathSegment segment = NetherPathfinder.pathFind(
                    this.context,
                    src.getX(), src.getY() - this.minY, src.getZ(),
                    dst.getX(), dst.getY() - this.minY, dst.getZ(),
                    true,
                    false,
                    10000,
                    assumeAir,
                    Princeps.settings().elytraFakeChunkCost.value   // v1.6 fakeChunkCost
            );
            if (segment == null) {
                throw new PathCalculationException("Path calculation failed");
            }
            return unoffsetSegment(segment);
        }, this.executor);
    }

    /** Map the native pathfinder's octree-relative Y back to world Y (+minY). No-op when minY == 0. */
    private PathSegment unoffsetSegment(final PathSegment segment) {
        if (this.minY == 0) {
            return segment;
        }
        final long[] packed = segment.packed;
        for (int i = 0; i < packed.length; i++) {
            final BetterBlockPos p = BetterBlockPos.deserializeFromLong(packed[i]);
            packed[i] = BetterBlockPos.serializeToLong(p.x, p.y + this.minY, p.z);
        }
        return segment;
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param startX The start X coordinate
     * @param startY The start Y coordinate
     * @param startZ The start Z coordinate
     * @param endX   The end X coordinate
     * @param endY   The end Y coordinate
     * @param endZ   The end Z coordinate
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final double startX, final double startY, final double startZ,
                            final double endX, final double endY, final double endZ) {
        return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID,
                startX, startY - this.minY, startZ, endX, endY - this.minY, endZ);
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param start The starting point
     * @param end   The ending point
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final Vec3 start, final Vec3 end) {
        return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID,
                start.x, start.y - this.minY, start.z, end.x, end.y - this.minY, end.z);
    }

    public boolean raytrace(final int count, final double[] src, final double[] dst, final int visibility) {
        final double[] s = offsetY(src, count);
        final double[] d = offsetY(dst, count);
        switch (visibility) {
            case Visibility.ALL:
                return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, s, d, false) == -1;
            case Visibility.NONE:
                return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, s, d, true) == -1;
            case Visibility.ANY:
                return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, s, d, true) != -1;
            default:
                throw new IllegalArgumentException("lol");
        }
    }

    public void raytrace(final int count, final double[] src, final double[] dst, final boolean[] hitsOut, final double[] hitPosOut) {
        NetherPathfinder.raytrace(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, offsetY(src, count), offsetY(dst, count), hitsOut, hitPosOut);
        if (this.minY != 0) {
            for (int i = 0; i < count; i++) hitPosOut[i * 3 + 1] += this.minY;
        }
    }

    /** Copy of an interleaved (x,y,z) coord array with every Y shifted to octree space. No-op when minY==0. */
    private double[] offsetY(final double[] coords, final int count) {
        if (this.minY == 0) {
            return coords;
        }
        final double[] out = coords.clone();
        for (int i = 0; i < count; i++) {
            out[i * 3 + 1] -= this.minY;
        }
        return out;
    }

    public void cancel() {
        NetherPathfinder.cancel(this.context);
    }

    public void destroy() {
        this.cancel();
        // Ignore anything that was queued up, just shutdown the executor
        this.executor.shutdownNow();

        try {
            while (!this.executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        NetherPathfinder.freeContext(this.context);
    }

    public long getSeed() {
        return this.seed;
    }

    private static void writeChunkData(LevelChunk chunk, long ptr, int height) {
        try {
            LevelChunkSection[] chunkInternalStorageArray = chunk.getSections();
            // section 0 == the world's min build Y == octree 0, so yReal (= y0<<4) is octree-relative in
            // every dimension. Only pack sections within the flyable height (Nether -> first 8 == Y<128).
            final int sectionCount = Math.min(chunkInternalStorageArray.length, height >> 4);
            for (int y0 = 0; y0 < sectionCount; y0++) {
                final LevelChunkSection extendedblockstorage = chunkInternalStorageArray[y0];
                if (extendedblockstorage == null) {
                    continue;
                }
                final PalettedContainer<BlockState> bsc = extendedblockstorage.getStates();
                IPalettedContainer<BlockState> iPalettedContainer = (IPalettedContainer<BlockState>) bsc;
                int airId = -1;
                if (iPalettedContainer.getPalette().maybeHas(state -> state.equals(AIR_BLOCK_STATE))) {
                    airId = iPalettedContainer.getPalette().idFor(AIR_BLOCK_STATE, PaletteResize.noResizeExpected());
                }
                // pasted from FasterWorldScanner
                final BitStorage array = iPalettedContainer.getStorage();
                if (array == null) continue;
                final long[] longArray = array.getRaw();
                final int arraySize = array.getSize();
                int bitsPerEntry = array.getBits();
                long maxEntryValue = (1L << bitsPerEntry) - 1L;

                final int yReal = y0 << 4;
                for (int i = 0, idx = 0; i < longArray.length && idx < arraySize; ++i) {
                    long l = longArray[i];
                    for (int offset = 0; offset <= (64 - bitsPerEntry) && idx < arraySize; offset += bitsPerEntry, ++idx) {
                        int value = (int) ((l >> offset) & maxEntryValue);
                        int x = (idx & 15);
                        int y = yReal + (idx >> 8);
                        int z = ((idx >> 4) & 15);
                        Octree.setBlock(ptr, x, y, z, value != airId);
                    }
                }
            }
            // "from java" state is set by the caller via NetherPathfinder.setChunkState (v1.6)
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static final class Visibility {

        public static final int ALL = 0;
        public static final int NONE = 1;
        public static final int ANY = 2;

        private Visibility() {}
    }

    public static boolean isSupported() {
        return NetherPathfinder.isThisSystemSupported();
    }
}
