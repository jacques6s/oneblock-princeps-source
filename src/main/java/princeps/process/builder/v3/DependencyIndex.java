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
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code position -> the cells whose current best solution needs that position to stay free}.
 *
 * <p>This is the one piece of bookkeeping that makes invariant P affordable, and without it the invariant is not
 * merely slow but unimplementable at this scale. P says no placement may take another cell's LAST solution. The
 * literal reading — after placing C, re-solve every unplaced cell in the layer — is 2 189 solves per placement on
 * etz-basalt's y=-57 and 2 189 × 2 189 ≈ 4.8 million solves for that layer alone, at a measured cost the dry run
 * cannot pay. The index turns it into: re-solve only the cells that were actually USING the cell C just filled.
 * On the piston row that set has a median size of 3.
 *
 * <h2>What counts as "using" a position</h2>
 *
 * <p>All five of {@link Use}, and the fifth is the one that is easy to forget and expensive to omit. The failure P
 * exists to catch is the diagonal stance of a {@code facing=down} piston: it needs one solid side neighbour to click
 * AND one perpendicular side neighbour that stays EMPTY, because that is where the bot's head goes. A greedy
 * frontier that fills all four sides made every placement individually legal and the set illegal — 448 times in one
 * row. If head room were not indexed, the index would report "nothing depended on that cell" for exactly the case it
 * was built for.
 *
 * <h2>Why it costs nothing to fill</h2>
 *
 * <p>Every entry is a by-product of a solve that has already happened: the oracle knows the stance, the approach
 * point, the clicked face and the traversed ray cells at the moment it accepts a {@link PlacementSolution}, so
 * recording them is a walk over data already in hand. The index is written when a cell's best solution is chosen and
 * rewritten when it changes; it is never recomputed from scratch.
 *
 * <h2>Determinism</h2>
 *
 * <p>Every list this class hands out is ordered by {@link PlacementGeometry#positionKey}, never by insertion and
 * never by hash. The order decides which cell invariant P re-solves first, which decides which candidate is
 * discarded first, which decides the whole downstream plan — so a hash-ordered {@code dependents} would produce a
 * different byte-identical-plan answer per JVM run while every individual step still looked correct.
 *
 * <p>The two hash containers below are therefore never iterated for output. Reads sort their key arrays, and the
 * only insertion-ordered structure — the per-cell edge list — is an {@link ArrayList} written in
 * {@link #footprint}'s already-sorted order. {@link EnumSet} iterates in {@code ordinal()} order by construction,
 * which is what makes {@link #edgesAt} reproducible without a second sort.
 */
public final class DependencyIndex {

    /**
     * The reason a cell's solution needs a position, and therefore what breaks if the position is filled.
     *
     * <p>Kept as five distinct values rather than one boolean because the report says which — "112,-57,93 lost its
     * last solution: its head room at 113,-57,93 was filled" is actionable and "it lost its last solution" is not.
     */
    public enum Use {

        /** The feet cell the bot stands in. Filling it removes the stance outright. */
        STANCE,

        /** The cell the crouched head occupies — {@code stance.above()}. The piston row's actual failure mode. */
        HEAD_ROOM,

        /** The neighbour whose face is clicked. Filling it does not break the solution; REMOVING it does, which is
         *  why scaffold removal consults the index for this use and placement does not. */
        CLICK_FACE,

        /** A cell the eye-to-aim-point ray passes through. Filling it obstructs the line of sight. */
        RAY_CELL,

        /** The sub-block volume the body occupies at the approach point, when the body spills into a neighbouring
         *  cell. Distinct from {@link #STANCE} because the approach optimiser deliberately stands up to 0.3 blocks
         *  off centre and a 0.6-wide body at 0.3 from the wall touches the wall — so the cell that must stay free is
         *  not always the cell the feet are in. */
        APPROACH_SPACE
    }

    /** One edge of the index: {@code cell}'s current best solution needs {@code position} for {@code use}. */
    public record Dependency(BlockPos position, BlockPos cell, Use use) {
    }

    /** Half-open bound for the body-box cell walk: a box whose {@code max} lies exactly on a cell boundary occupies
     *  the cell BELOW that boundary and not the one above it, and {@code Mth.floor(max)} alone would claim both. */
    private static final double CELL_EPSILON = 1.0E-9D;

    /** {@code cell -> its edges}, in {@link #footprint} order. The reverse direction of the index, and what makes
     *  {@link #forget} a walk over one list instead of a scan of every position. */
    private final Long2ObjectOpenHashMap<List<Dependency>> byCell = new Long2ObjectOpenHashMap<>();

    /** {@code position -> cell -> uses}. Nested rather than flat so that {@link #dependents} answers with a key set
     *  and never has to de-duplicate: a cell that needs one position for three different reasons is ONE dependent,
     *  and a flat multiset would re-solve it three times. */
    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<EnumSet<Use>>> byPosition =
            new Long2ObjectOpenHashMap<>();

    private int edges;

    public DependencyIndex() {
    }

    // ------------------------------------------------------------------- writing

    /**
     * Record every position {@code solution} depends on, replacing whatever was recorded for {@code cell} before.
     *
     * <p>Replacing rather than adding is load-bearing: a cell's best solution changes whenever the world around it
     * changes, and an index that accumulated stale edges would veto placements on the strength of a stance the cell
     * no longer uses — invariant P failing closed, which under 5.1 means the layer escalates to scaffold for no
     * reason and the report blames geometry for a bookkeeping bug.
     *
     * @param world needed for {@link Use#RAY_CELL}: the traversed cells are a property of the ray through the
     *              predicted world, not of the solution's endpoints alone
     */
    public void put(BlockPos cell, PlacementSolution solution, PlayerPose pose, PredictedWorld world) {
        this.forget(cell);
        List<Dependency> owned = footprint(cell, solution, pose, world);
        if (owned.isEmpty()) {
            return;
        }
        this.byCell.put(PlacementGeometry.positionKey(cell), new ArrayList<>(owned));
        for (Dependency edge : owned) {
            this.link(edge);
        }
    }

    /** Add a single edge. The escape hatch for dependencies the oracle does not model — a scaffold's removal stance,
     *  an interaction's approach — so those are not invisible to invariant P. */
    public void putEdge(BlockPos position, BlockPos cell, Use use) {
        Dependency edge = new Dependency(position, cell, use);
        List<Dependency> owned = this.byCell.computeIfAbsent(PlacementGeometry.positionKey(cell),
                key -> new ArrayList<>());
        // The per-cell list and the nested map have to stay in step or forget() unlinks an edge twice and the counter
        // drifts below zero. Cheap to check: a cell's footprint is a handful of entries, never a scan.
        if (owned.contains(edge)) {
            return;
        }
        owned.add(edge);
        this.link(edge);
    }

    /** Drop every edge belonging to {@code cell}. Called the moment a cell is applied to the predicted world: a
     *  finished cell cannot lose a solution, and leaving it indexed makes it a permanent false positive for P. */
    public void forget(BlockPos cell) {
        List<Dependency> owned = this.byCell.remove(PlacementGeometry.positionKey(cell));
        if (owned == null) {
            return;
        }
        for (Dependency edge : owned) {
            this.unlink(edge);
        }
    }

    /** Empty the index. The layer gate does this: nothing from layer L can constrain layer L+1, because every cell
     *  in L is either finished or reported. */
    public void clear() {
        this.byCell.clear();
        this.byPosition.clear();
        this.edges = 0;
    }

    // ------------------------------------------------------------------- reading

    /**
     * The cells whose current best solution needs {@code position} — {@code index[C]} of 5.4, and the ONLY set
     * invariant P re-solves after applying C.
     *
     * <p>Ordered by {@link PlacementGeometry#positionKey}. Empty, never {@code null}: "nothing depends on this cell"
     * is the common answer and the caller should not have to spell it twice.
     */
    public List<BlockPos> dependents(BlockPos position) {
        Long2ObjectOpenHashMap<EnumSet<Use>> cells = this.byPosition.get(PlacementGeometry.positionKey(position));
        return cells == null ? List.of() : sortedPositions(cells.keySet().toLongArray());
    }

    /** As {@link #dependents(BlockPos)}, narrowed to one kind of use. Filling a cell breaks {@link Use#STANCE},
     *  {@link Use#HEAD_ROOM}, {@link Use#RAY_CELL} and {@link Use#APPROACH_SPACE}; emptying one breaks
     *  {@link Use#CLICK_FACE}. Those are different questions and the scaffold planner asks the second. */
    public List<BlockPos> dependents(BlockPos position, Use use) {
        Long2ObjectOpenHashMap<EnumSet<Use>> cells = this.byPosition.get(PlacementGeometry.positionKey(position));
        if (cells == null) {
            return List.of();
        }
        long[] keys = cells.keySet().toLongArray();
        Arrays.sort(keys);
        List<BlockPos> matching = new ArrayList<>();
        for (long key : keys) {
            if (cells.get(key).contains(use)) {
                matching.add(BlockPos.of(key));
            }
        }
        return List.copyOf(matching);
    }

    /** Every edge touching {@code position}, ordered by dependent cell then by {@link Use#ordinal()} — the input to
     *  the report line that names WHICH resource a blocked cell lost. */
    public List<Dependency> edgesAt(BlockPos position) {
        Long2ObjectOpenHashMap<EnumSet<Use>> cells = this.byPosition.get(PlacementGeometry.positionKey(position));
        if (cells == null) {
            return List.of();
        }
        long[] keys = cells.keySet().toLongArray();
        Arrays.sort(keys);
        List<Dependency> all = new ArrayList<>();
        for (long key : keys) {
            BlockPos cell = BlockPos.of(key);
            // EnumSet iterates in ordinal order by construction, so the second half of the documented ordering costs
            // nothing and cannot be lost to a refactor that swaps the container for a HashSet without noticing.
            for (Use use : cells.get(key)) {
                all.add(new Dependency(position, cell, use));
            }
        }
        return List.copyOf(all);
    }

    /** The positions {@code cell}'s current best solution depends on — the reverse direction, used to un-index a
     *  cell without walking the whole map and by the {@code PROVEN} test of 5.10, whose "dependency footprint" is
     *  exactly this set widened by the reach. */
    public List<BlockPos> footprintOf(BlockPos cell) {
        List<Dependency> owned = this.byCell.get(PlacementGeometry.positionKey(cell));
        if (owned == null) {
            return List.of();
        }
        List<BlockPos> positions = new ArrayList<>(owned.size());
        long previous = 0L;
        for (Dependency edge : owned) {
            // The list is already positionKey-ordered by footprint(), so de-duplicating a position that carries two
            // uses is a comparison against the previous entry rather than a set.
            long key = PlacementGeometry.positionKey(edge.position());
            if (positions.isEmpty() || key != previous) {
                positions.add(edge.position());
                previous = key;
            }
        }
        return List.copyOf(positions);
    }

    /** Does {@code cell}'s solution need {@code position} for this use? */
    public boolean depends(BlockPos cell, BlockPos position, Use use) {
        Long2ObjectOpenHashMap<EnumSet<Use>> cells = this.byPosition.get(PlacementGeometry.positionKey(position));
        if (cells == null) {
            return false;
        }
        EnumSet<Use> uses = cells.get(PlacementGeometry.positionKey(cell));
        return uses != null && uses.contains(use);
    }

    /** Number of edges held. Reported in the trace: an index that grows without bound across a layer means
     *  {@link #forget} is not being called, and that is silent until the dry run gets slow. */
    public int edgeCount() {
        return this.edges;
    }

    /** How many distinct cells are indexed. */
    public int cellCount() {
        return this.byCell.size();
    }

    // ------------------------------------------------------------------- the pure half

    /**
     * Every position one solution depends on, with its reason — pure, static, and the whole testable core of this
     * class. {@link #put} is this method plus a map write.
     *
     * <p>Ordered by {@link PlacementGeometry#positionKey} then by {@link Use#ordinal()}, so a test can assert the
     * exact footprint of a known stance rather than a set membership, and so the index's contents cannot depend on
     * the order the oracle happened to discover things in.
     *
     * <p>Two positions are deliberately absent. The solution's OWN cell is dropped — it is the thing being filled,
     * so an edge from it to itself would make every placement its own veto, and the ray to a click face on the far
     * side of the target passes straight through it. And anything outside the captured box is dropped, because
     * {@link PredictedWorld#apply} refuses to write there at all: the plan can never fill such a position, so an
     * edge naming it is an entry that can only ever be walked and never fire.
     */
    public static List<Dependency> footprint(BlockPos cell, PlacementSolution solution, PlayerPose pose,
                                             PredictedWorld world) {
        // LinkedHashMap for the accumulation and a sort at the end, rather than a sorted map throughout: the sort is
        // over a handful of entries and this way the de-duplication of "the eye cell is both HEAD_ROOM and the first
        // RAY_CELL" is a set union instead of a comparison chain.
        Map<Long, EnumSet<Use>> byPosition = new LinkedHashMap<>();
        record(byPosition, world, cell, solution.stance(), Use.STANCE);
        record(byPosition, world, cell, solution.stance().above(), Use.HEAD_ROOM);
        record(byPosition, world, cell, solution.against(), Use.CLICK_FACE);
        for (BlockPos traversed : rayCells(pose, solution)) {
            record(byPosition, world, cell, traversed, Use.RAY_CELL);
        }
        for (BlockPos occupied : approachCells(pose, solution)) {
            record(byPosition, world, cell, occupied, Use.APPROACH_SPACE);
        }
        long[] keys = new long[byPosition.size()];
        int at = 0;
        for (Long key : byPosition.keySet()) {
            keys[at++] = key;
        }
        Arrays.sort(keys);
        List<Dependency> edges = new ArrayList<>();
        for (long key : keys) {
            BlockPos position = BlockPos.of(key);
            for (Use use : byPosition.get(key)) {
                edges.add(new Dependency(position, cell, use));
            }
        }
        return List.copyOf(edges);
    }

    /**
     * The cells the eye-to-aim ray passes through, excluding the clicked block itself.
     *
     * <p>Half-open at the aim point, matching {@link GridRay}'s contract, for the reason the oracle documents: a
     * ray that included its own target would report the block being clicked as the thing obstructing the path to its
     * own face, and every solution would fail its own line-of-sight check.
     *
     * <p>Collected by casting through a {@link GridRay.SolidTest} that answers "not solid" to everything and records
     * what it was asked. That is not a trick for its own sake — it is the only way to guarantee the cells named here
     * are EXACTLY the cells the occlusion check walks. A second traversal written by hand would agree today and drift
     * the first time either one gained a tie-breaking rule, and the drift would show up as an invariant that vetoes
     * the wrong candidate rather than as a compile error.
     *
     * <p>In traversal order, eye first. The order is deterministic because the caster is, and it is the useful one:
     * the nearest obstruction is the one a reader wants named first.
     */
    public static List<BlockPos> rayCells(PlayerPose pose, PlacementSolution solution) {
        List<BlockPos> traversed = new ArrayList<>();
        GridRay.cast((x, y, z) -> {
            traversed.add(new BlockPos(x, y, z));
            return false;
        }, pose.eyeAt(solution.approach()), solution.aimPoint());
        traversed.removeIf(position -> position.equals(solution.against()));
        return List.copyOf(traversed);
    }

    // ------------------------------------------------------------------- internals

    /**
     * The cells the body box spills into at the approach point, other than the two the stance already names.
     *
     * <p>Empty for every approach the oracle's own grid produces — {@link SolveBudget#approachInset()} is exactly
     * half the body width, so the box is inside its own column by construction. It is computed anyway because
     * {@link PlacementOracle#bestApproach} is public and a caller may hand in a point that grid never offered, and
     * because a body cell that is not indexed is a placement invariant P will happily let another cell take.
     */
    private static List<BlockPos> approachCells(PlayerPose pose, PlacementSolution solution) {
        AABB body = pose.bodyAt(solution.approach());
        BlockPos stance = solution.stance();
        BlockPos head = stance.above();
        List<BlockPos> cells = new ArrayList<>();
        for (int x = Mth.floor(body.minX); x <= Mth.floor(body.maxX - CELL_EPSILON); x++) {
            for (int y = Mth.floor(body.minY); y <= Mth.floor(body.maxY - CELL_EPSILON); y++) {
                for (int z = Mth.floor(body.minZ); z <= Mth.floor(body.maxZ - CELL_EPSILON); z++) {
                    BlockPos cell = new BlockPos(x, y, z);
                    if (!cell.equals(stance) && !cell.equals(head)) {
                        cells.add(cell);
                    }
                }
            }
        }
        return cells;
    }

    /** One (position, use) pair, dropped when the position is the cell itself or outside the captured box. See
     *  {@link #footprint} for why both are dropped rather than carried and ignored later. */
    private static void record(Map<Long, EnumSet<Use>> byPosition, PredictedWorld world, BlockPos cell,
                               BlockPos position, Use use) {
        if (position.equals(cell)
                || !world.inBounds(position.getX(), position.getY(), position.getZ())) {
            return;
        }
        byPosition.computeIfAbsent(PlacementGeometry.positionKey(position), key -> EnumSet.noneOf(Use.class))
                .add(use);
    }

    private void link(Dependency edge) {
        Long2ObjectOpenHashMap<EnumSet<Use>> cells = this.byPosition.computeIfAbsent(
                PlacementGeometry.positionKey(edge.position()), key -> new Long2ObjectOpenHashMap<>());
        EnumSet<Use> uses = cells.computeIfAbsent(PlacementGeometry.positionKey(edge.cell()),
                key -> EnumSet.noneOf(Use.class));
        if (uses.add(edge.use())) {
            this.edges++;
        }
    }

    private void unlink(Dependency edge) {
        long positionKey = PlacementGeometry.positionKey(edge.position());
        Long2ObjectOpenHashMap<EnumSet<Use>> cells = this.byPosition.get(positionKey);
        if (cells == null) {
            return;
        }
        long cellKey = PlacementGeometry.positionKey(edge.cell());
        EnumSet<Use> uses = cells.get(cellKey);
        if (uses == null || !uses.remove(edge.use())) {
            return;
        }
        this.edges--;
        // Empty containers are removed rather than left behind. dependents() answers off the presence of the key, so
        // an empty EnumSet would report a dependent that has none, and P would re-solve a cell nothing depends on for
        // the rest of the layer.
        if (uses.isEmpty()) {
            cells.remove(cellKey);
        }
        if (cells.isEmpty()) {
            this.byPosition.remove(positionKey);
        }
    }

    private static List<BlockPos> sortedPositions(long[] keys) {
        // positionKey IS BlockPos.asLong, so sorting the raw keys sorts by positionKey without unpacking anything.
        Arrays.sort(keys);
        List<BlockPos> positions = new ArrayList<>(keys.length);
        for (long key : keys) {
            positions.add(BlockPos.of(key));
        }
        return List.copyOf(positions);
    }
}
