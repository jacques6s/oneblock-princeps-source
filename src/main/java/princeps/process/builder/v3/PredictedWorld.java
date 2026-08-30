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

package princeps.process.builder.v3;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import princeps.pathing.movement.MovementHelper;
import princeps.pathing.precompute.Ternary;

import java.util.Objects;

/**
 * The world as it WILL be — a frozen snapshot of the real one plus the placements the plan has committed so far.
 *
 * <p>Layer 0 of the design, and the reason the rest of it can claim to have proven anything. A planner that reasons
 * against the world as it is today decides that cell 4 231 is unplaceable because the neighbour it will click has not
 * been built yet, which is true and useless. The forward simulation walks the build in execution order and applies
 * each accepted placement, so dependencies — click face, standing room, occlusion, support — are not graph edges
 * anybody computes, they simply hold or do not hold at the moment the planner asks.
 *
 * <p>Three storage layers, in read order: the {@code deltas} map the planner writes as it steps forward, the
 * {@code snapshot} array taken once from the real world over the schematic's bounding box plus
 * {@link #DEFAULT_MARGIN} blocks, and out-of-bounds. The solid bitset is derived from both and maintained
 * incrementally on every {@link #apply}/{@link #revert}, because it is what {@link GridRay} walks and rebuilding it
 * per ray would cost more than the rays.
 *
 * <h2>Loaded-ness is stored, not inferred</h2>
 * <p>{@code BlockStateInterface.get0} never returns null and never throws: an unloaded chunk, an out-of-world Y and a
 * missing cached region all come back as {@code minecraft:air}. Code that reads that air as "definitely empty" plans
 * confidently into terrain it has never seen. Since the entire value of this design is "proven before the run", the
 * snapshot records loaded-ness per column separately and {@link #isLoaded} is the difference between a cell the plan
 * can call PROVEN and one it must call PROVISIONAL. Without it the proof is worth nothing.
 *
 * <p>Not thread-safe, and deliberately so: {@link #capture} must be called on the main thread (that is where the
 * world can be read at all), after which one planner thread owns the instance for the length of the dry run.
 */
public final class PredictedWorld implements BlockGetter {

    /** How far past the schematic's bounding box the snapshot reaches. Stances sit up to three blocks out and up to
     *  three down, their rays run out to the planning reach, and the enclosure flood fill needs to find the exterior —
     *  eight covers all three with room to spare, and the snapshot is taken once. */
    public static final int DEFAULT_MARGIN = 8;

    /** Where the real world's states come from at capture time. Wired to {@code bsi::get0} by the process; a fake in
     *  tests. A functional seam rather than a {@code BlockStateInterface} parameter so this class — and therefore the
     *  whole planner above it — never imports client code. */
    @FunctionalInterface
    public interface StateSource {

        BlockState at(int x, int y, int z);
    }

    /** Whether a column was genuinely readable at capture time. Wired to {@code bsi::worldContainsLoadedChunk} — real
     *  render distance, not the Princeps chunk cache, because a cached chunk is a memory of the world and the proof is
     *  about the world. */
    @FunctionalInterface
    public interface LoadedTest {

        boolean isLoaded(int blockX, int blockZ);
    }

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    /** Real states at capture time, indexed {@code (y * sizeZ + z) * sizeX + x} in local coordinates. */
    private final BlockState[] snapshot;

    /** What the plan has placed or broken so far, keyed by {@link BlockPos#asLong}. Read before {@link #snapshot}. */
    private final Long2ObjectOpenHashMap<BlockState> deltas;

    /** One bit per cell of the box: is it a full solid cube right now. Maintained incrementally; walked by
     *  {@link GridRay}. */
    private final long[] solid;

    /** One bit per column of the box: was that column in a loaded chunk at capture time. */
    private final long[] loaded;

    /** Frozen pathfinder policy; never a live/global settings lookup. */
    private final MovementHelper.WalkSettings walkSettings;

    private PredictedWorld(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                           BlockState[] snapshot, long[] solid, long[] loaded,
                           MovementHelper.WalkSettings walkSettings) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.snapshot = snapshot;
        this.solid = solid;
        this.loaded = loaded;
        this.walkSettings = Objects.requireNonNull(walkSettings, "walkSettings");
        this.deltas = new Long2ObjectOpenHashMap<>();
    }

    /**
     * Take the snapshot. Main thread only — that is where {@code BlockStateInterface} may be constructed and read.
     *
     * @param min    inclusive lower corner of the schematic's bounding box, before margin
     * @param max    inclusive upper corner of the schematic's bounding box, before margin
     * @param margin blocks of context to include on every side; {@link #DEFAULT_MARGIN} unless a caller knows better
     */
    public static PredictedWorld capture(StateSource states, LoadedTest loaded, Vec3i min, Vec3i max, int margin) {
        return capture(states, loaded, min, max, margin, V3Settings.defaults());
    }

    /**
     * Take the snapshot with the immutable walkability policy the live pathfinder will use for this build.
     */
    public static PredictedWorld capture(StateSource states, LoadedTest loaded, Vec3i min, Vec3i max, int margin,
                                         MovementHelper.WalkSettings walkSettings) {
        // min/max are not trusted to be sorted: a schematic origin plus a negative size, or a clearArea corner pair
        // given in the order the user happened to click, both arrive here inverted, and an inverted box silently
        // captures nothing at all.
        int lowX = Math.min(min.getX(), max.getX()) - margin;
        int lowY = Math.min(min.getY(), max.getY()) - margin;
        int lowZ = Math.min(min.getZ(), max.getZ()) - margin;
        int sizeX = Math.max(min.getX(), max.getX()) + margin - lowX + 1;
        int sizeY = Math.max(min.getY(), max.getY()) + margin - lowY + 1;
        int sizeZ = Math.max(min.getZ(), max.getZ()) + margin - lowZ + 1;

        BlockState[] snapshot = new BlockState[sizeX * sizeY * sizeZ];
        long[] solid = new long[(snapshot.length + 63) >>> 6];
        long[] loadedBits = new long[(sizeX * sizeZ + 63) >>> 6];

        // Loaded-ness is per column, so it is asked per column. worldContainsLoadedChunk resolves a chunk lookup
        // every call; asking it once per cell would multiply that by sizeY for an answer that cannot vary with Y.
        for (int localZ = 0; localZ < sizeZ; localZ++) {
            for (int localX = 0; localX < sizeX; localX++) {
                if (loaded.isLoaded(lowX + localX, lowZ + localZ)) {
                    int column = localZ * sizeX + localX;
                    loadedBits[column >>> 6] |= 1L << (column & 63);
                }
            }
        }

        // Y outer, X inner, matching the index layout: the snapshot of a 63x63 schematic with margin is on the order
        // of a million cells and this is the one pass over all of them that the main thread pays for.
        for (int localY = 0; localY < sizeY; localY++) {
            for (int localZ = 0; localZ < sizeZ; localZ++) {
                int rowBase = (localY * sizeZ + localZ) * sizeX;
                for (int localX = 0; localX < sizeX; localX++) {
                    BlockState state = states.at(lowX + localX, lowY + localY, lowZ + localZ);
                    if (state == null) {
                        // get0 never returns null, but a StateSource is a seam and a test fake is under no such
                        // obligation. Air is what an unreadable cell means everywhere else in this class.
                        state = Blocks.AIR.defaultBlockState();
                    }
                    int index = rowBase + localX;
                    snapshot[index] = state;
                    if (solidFullCube(state)) {
                        solid[index >>> 6] |= 1L << (index & 63);
                    }
                }
            }
        }
        return new PredictedWorld(lowX, lowY, lowZ, sizeX, sizeY, sizeZ, snapshot, solid, loadedBits, walkSettings);
    }

    // ---------------------------------------------------------------------------------------------- reads

    /** The predicted state: delta if the plan has touched this cell, else the snapshot, else air. Never null. */
    public BlockState get(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState delta = this.deltas.get(BlockPos.asLong(x, y, z));
        return delta != null ? delta : this.snapshot[index(x, y, z)];
    }

    /** @see #get(int, int, int) */
    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * {@link BlockGetter}'s state read, backed by the same snapshot-plus-deltas view every planner predicate uses.
     *
     * <p>Implementing the real interface is what lets {@link BlockState#getShape} and Vanilla's OUTLINE ray ask their
     * ordinary neighbour questions against the predicted future without a fabricated {@code Level}.
     */
    @Override
    public BlockState getBlockState(BlockPos pos) {
        return get(pos);
    }

    /** Block entities do not affect any vanilla block outline. Metadata is captured separately by
     *  {@link SchematicView}; returning null is BlockGetter's ordinary "none at this position" answer. */
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    /** Fluid shape reads share the exact state overlay. */
    @Override
    public FluidState getFluidState(BlockPos pos) {
        return get(pos).getFluidState();
    }

    /** Captured vertical span, as required by {@link BlockGetter}. */
    @Override
    public int getHeight() {
        return this.sizeY;
    }

    /** Inclusive lower Y of the captured span. */
    @Override
    public int getMinY() {
        return this.minY;
    }

    /** The state before the plan touched anything — what the executor will compare the live world against to detect
     *  divergence at a cell the plan has not reached yet. */
    public BlockState original(int x, int y, int z) {
        return inBounds(x, y, z) ? this.snapshot[index(x, y, z)] : Blocks.AIR.defaultBlockState();
    }

    /**
     * Was this cell readable when the snapshot was taken? A cell whose entire dependency footprint answers true may be
     * called PROVEN; one that does not is PROVISIONAL and gets re-proven on approach.
     *
     * <p>Cells outside the captured box answer false. That is the honest answer, not a bounds error: the planner knows
     * nothing about them.
     */
    public boolean isLoaded(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return false;
        }
        int column = (z - this.minZ) * this.sizeX + (x - this.minX);
        return (this.loaded[column >>> 6] & (1L << (column & 63))) != 0L;
    }

    /** Is the cell inside the captured box at all? */
    public boolean inBounds(int x, int y, int z) {
        return x >= this.minX && x < this.minX + this.sizeX
                && y >= this.minY && y < this.minY + this.sizeY
                && z >= this.minZ && z < this.minZ + this.sizeZ;
    }

    /**
     * Full solid cube — the bitset predicate, and the only notion of solidity {@link GridRay} has. Partial shapes
     * (slabs, stairs, fences) are NOT solid here; their geometry enters the search through the face {@code AABB} the
     * aim points are derived from instead.
     */
    public boolean isSolidFullCube(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            // GridRay.SolidTest demands false out of bounds, and false is also the honest answer: the snapshot reaches
            // DEFAULT_MARGIN past the schematic in every direction, so a ray that leaves the box has already travelled
            // further than the planning reach. Cells out there are not PROVEN either, because isLoaded says no.
            return false;
        }
        int index = index(x, y, z);
        return (this.solid[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    /**
     * Can the bot stand with its feet in this cell — feet and head passable, floor walkable — against the PREDICTED
     * world?
     *
     * <p>Rewritten from {@code BuilderProcess.isStandable} (:4224), which reads {@code ctx.world()} directly. That
     * rewrite is not optional: a stance test against the world of today, inside a planner reasoning about the world of
     * three hours from now, makes every proof it produces meaningless.
     *
     * <p>Implement it from {@code MovementHelper}'s state-only forms — {@code canWalkOnBlockState} and
     * {@code canWalkThroughBlockState} both return a {@code Ternary} from the state alone and are pure. Do NOT bring
     * over {@code isStandableOrScaffoldable} (:4253): it quietly permits the pathfinder to place scaffolding the
     * planner knows nothing about, and in V3 scaffold is planned, never allowed.
     *
     * <p>The state-only pathing predicates receive {@link #walkSettings}, an immutable build-start snapshot. This keeps
     * the planner aligned with the pathfinder without reaching through {@code Princeps.settings()} (which is
     * client-only and cannot initialise headless) or maintaining a second implementation of walkability.
     */
    public boolean isStandable(int x, int y, int z) {
        BlockState feet = get(x, y, z);
        BlockState head = get(x, y + 1, z);
        BlockState floor = get(x, y - 1, z);
        // The floor's fluid state is checked too, which :4224 does not do. It gets away with that because the
        // pathfinder's water rules are settings-dependent both ways (assumeWalkOnWater flips the sense of "is there
        // water above"); a planner that claims PROVEN has no business standing on a surface whose walkability depends
        // on a setting the user may change between the plan and the run.
        return feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && floor.getFluidState().isEmpty()
                && canWalkThrough(x, y, z, feet)
                && canWalkThrough(x, y + 1, z, head)
                && canWalkOn(floor);
    }

    /** The collision box the ray caster and the body checks use for a cell, in world coordinates; empty when the cell
     *  has no collision. Read off the predicted state, so it follows {@link #apply}. */
    public AABB collisionBox(int x, int y, int z) {
        VoxelShape shape = collisionShape(get(x, y, z));
        return shape.isEmpty() ? new AABB(x, y, z, x, y, z) : shape.bounds().move(x, y, z);
    }

    /**
     * Line of sight over the predicted world. The planner's {@code level.clip}: first solid cell and the face entered,
     * or null for a clean miss.
     *
     * @see GridRay
     */
    public GridRay.Hit clip(Vec3 from, Vec3 to) {
        return GridRay.cast(this::isSolidFullCube, from, to);
    }

    // ---------------------------------------------------------------------------------------------- writes

    /** Step the simulation forward: the plan has decided this cell will hold this state.
     *
     *  <p>Throws rather than ignoring a write outside the captured box. The box is the schematic plus
     *  {@link #DEFAULT_MARGIN} on every side, so every cell the plan can legitimately touch is inside it by
     *  construction; a write that lands outside is a bug in the layer above, and a silently dropped write would show
     *  up as an unexplainable rejection thousands of cells later. */
    public void apply(int x, int y, int z, BlockState state) {
        if (!inBounds(x, y, z)) {
            throw new IndexOutOfBoundsException("apply outside the captured box: " + x + "," + y + "," + z
                    + " not in [" + this.minX + "," + this.minY + "," + this.minZ + "]..["
                    + maxX() + "," + maxY() + "," + maxZ() + "]");
        }
        this.deltas.put(BlockPos.asLong(x, y, z), state);
        setSolid(index(x, y, z), solidFullCube(state));
    }

    /** @see #apply(int, int, int, BlockState) */
    public void apply(BlockPos pos, BlockState state) {
        apply(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    /** Undo one {@link #apply}, returning the cell to its snapshot state. Used when an invariant rejects a candidate
     *  after it was speculatively applied. */
    public void revert(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            throw new IndexOutOfBoundsException("revert outside the captured box: " + x + "," + y + "," + z);
        }
        if (this.deltas.remove(BlockPos.asLong(x, y, z)) != null) {
            int index = index(x, y, z);
            setSolid(index, solidFullCube(this.snapshot[index]));
        }
    }

    /** @see #revert(int, int, int) */
    public void revert(BlockPos pos) {
        revert(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Drop every delta, returning to the captured world. A re-plan after divergence takes a fresh snapshot instead;
     *  this is for the planner's own restarts. */
    public void clearDeltas() {
        // Walk the deltas rather than recomputing the whole bitset: a restart typically carries a few thousand
        // deltas against a box of a million cells, and solidFullCube evaluates a VoxelShape per call.
        LongIterator keys = this.deltas.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            int index = index(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            setSolid(index, solidFullCube(this.snapshot[index]));
        }
        this.deltas.clear();
    }

    /** How many cells the plan has touched — the count the report prints beside the action total. */
    public int deltaCount() {
        return this.deltas.size();
    }

    // ---------------------------------------------------------------------------------------------- bounds

    public int minX() {
        return this.minX;
    }

    public int minY() {
        return this.minY;
    }

    public int minZ() {
        return this.minZ;
    }

    public int maxX() {
        return this.minX + this.sizeX - 1;
    }

    public int maxY() {
        return this.minY + this.sizeY - 1;
    }

    public int maxZ() {
        return this.minZ + this.sizeZ - 1;
    }

    /** The bitset predicate as a lambda, for handing straight to {@link GridRay}. */
    public GridRay.SolidTest solidTest() {
        return this::isSolidFullCube;
    }

    // ---------------------------------------------------------------------------------------------- internals

    /** World coordinates to the flat index the snapshot and the solid bitset share. Callers check bounds first. */
    private int index(int x, int y, int z) {
        return ((y - this.minY) * this.sizeZ + (z - this.minZ)) * this.sizeX + (x - this.minX);
    }

    private void setSolid(int index, boolean value) {
        if (value) {
            this.solid[index >>> 6] |= 1L << (index & 63);
        } else {
            this.solid[index >>> 6] &= ~(1L << (index & 63));
        }
    }

    /**
     * Does this state fill its whole cell for occlusion purposes — the one bit {@link GridRay} walks.
     *
     * <p>Two screens. Air short-circuits, because in a captured box that is most of the cells and every one of them
     * would otherwise pay a {@code VoxelShape} evaluation. Everything else is decided by the real collision shape.
     *
     * <p>The unevaluable case answers <b>true</b>, which is the opposite of what {@code MovementHelper
     * .isBlockNormalCube} does with the same exception (:1024, "assume it's bad"). Not an inconsistency: that method
     * asks "may I walk on this", where the cautious answer is no, and this one asks "does this block my line of
     * sight", where the cautious answer is yes. Both refuse to claim more than they know. Getting the sense backwards
     * here would produce a plan that is PROVEN against a ray it never actually verified.
     */
    private static boolean solidFullCube(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getCollisionShape(null, null));
        } catch (Exception ignored) {
            return true;
        }
    }

    /** The collision shape, with the same null-level guard {@code isBlockNormalCube} needs; empty when unevaluable, so
     *  a body check never rejects a stance over a shape nobody could compute. */
    private static VoxelShape collisionShape(BlockState state) {
        try {
            return state.getCollisionShape(null, null);
        } catch (Exception ignored) {
            return Shapes.empty();
        }
    }

    /** {@code MovementHelper.canWalkThrough} resolved against the overlay instead of a {@code BlockStateInterface} —
     *  the state-only {@code Ternary} verbatim, and its MAYBE cases (carpet, snow layers) resolved here so no
     *  {@code bsi} is needed. Fluids also return MAYBE, but {@link #isStandable} has already rejected them. */
    private boolean canWalkThrough(int x, int y, int z, BlockState state) {
        Ternary ternary = MovementHelper.canWalkThroughBlockState(state, this.walkSettings);
        if (ternary != Ternary.MAYBE) {
            return ternary == Ternary.YES;
        }
        if (state.getBlock() instanceof CarpetBlock) {
            return canWalkOn(get(x, y - 1, z));
        }
        return state.isPathfindable(PathComputationType.LAND);
    }

    /** {@code MovementHelper.canWalkOn}'s state-only form, with MAYBE — water and lava, nothing else — read as no.
     *  The positional form would resolve those against {@code assumeWalkOnWater}/{@code assumeWalkOnLava}; a stance
     *  the plan calls PROVEN may not rest on a surface that exists only because a setting is on. */
    private boolean canWalkOn(BlockState state) {
        return MovementHelper.canWalkOnBlockState(state, this.walkSettings) == Ternary.YES;
    }
}
