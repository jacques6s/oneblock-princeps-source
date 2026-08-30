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

import princeps.api.schematic.ISchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The planner's read of a schematic: absolute coordinates, one desired state per cell, and the cells grouped by
 * layer in the order the layer loop wants them.
 *
 * <p>It exists because the only layer-scoped schematic accessor in V2 is {@code BuilderCalculationContext}, and that
 * class is a private NON-STATIC inner class of {@code BuilderProcess} — reaching it from a planner means holding a
 * {@code BuilderProcess} instance, which drags in {@code ctx}, the live world, the live inventory and
 * {@code Princeps.settings()}, and with them the {@code ExceptionInInitializerError} that makes anything touching
 * settings permanently untestable. This view depends on {@link ISchematic}, a {@link Vec3i} origin and a
 * {@link PredictedWorld} snapshot, so the layer loop can be unit-tested against a hand-built schematic.
 *
 * <h2>Three traps this type exists to close, all of them silent</h2>
 *
 * <ul>
 *   <li><b>Relative versus absolute.</b> {@link ISchematic#desiredState} takes coordinates RELATIVE to the origin;
 *       {@code BuilderProcess.placeAt} and the whole planner speak ABSOLUTE. Mixing them does not fail — it reads a
 *       different cell, and for an origin near 0,0,0 it can even read a plausible one. Every method here takes
 *       absolute coordinates and does the subtraction in exactly one place.</li>
 *   <li><b>AIR is two different answers.</b> {@code placeAt} collapses air to {@code null} ("nothing to do here");
 *       {@code getSchematic} returns the air state ("this cell must END UP empty"). The distinction decides whether a
 *       terrain block inside the box is a {@code BREAK} or is ignored, so this type keeps both:
 *       {@link #covers} says the cell is in scope, {@link #desired} returns the state including air, and
 *       {@link #wantsBlock} is the "there is a block to place here" question the layer loop asks.</li>
 *   <li><b>{@code CompositeSchematic.desiredState} THROWS on an uncovered position</b> rather than returning air, and
 *       {@code inSchematic} is what guards it. Because the capture below walks the box once and asks
 *       {@code inSchematic} first, no later caller can trip that — the planner solves cells thousands of times and a
 *       guard that has to be remembered at every call site is a guard that will be forgotten.</li>
 * </ul>
 *
 * <h2>Why the states are captured once instead of queried lazily</h2>
 *
 * <p>{@link ISchematic#desiredState} takes the CURRENT world state and the approx-placeable list as arguments, and
 * the map-art and substitute wrappers genuinely use them. A lazy view would therefore answer differently before and
 * after the planner's own simulated placements — the schematic would change shape underneath the forward simulation,
 * which is precisely the property the frozen plan is supposed to have removed. Capturing against
 * {@link PredictedWorld#original} freezes it at the same instant the world snapshot is taken, so the plan is proven
 * against one schematic rather than a moving one.
 *
 * <h2>Storage: a flat array, never a map</h2>
 *
 * <p>The cells are held in a dense {@code BlockState[]} over the schematic's own box, indexed exactly like
 * {@link PredictedWorld}'s snapshot, with {@code null} for "the schematic has no opinion here". The alternative — a
 * hash map keyed on {@link PlacementGeometry#positionKey} — would answer lookups just as fast and would then have to
 * be forbidden from ever being iterated, because a hash order in the layer lists is a different plan per JVM run. A
 * dense array cannot be iterated in the wrong order, so the ordering guarantee is structural rather than a rule
 * somebody has to keep.
 */
public final class SchematicView {

    private final String name;
    private final Vec3i origin;
    private final BlockPos min;
    private final BlockPos max;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    /** Desired state per cell of the box, {@code null} where the schematic has no opinion. Indexed as
     *  {@code (localY * sizeZ + localZ) * sizeX + localX}, the same layout {@link PredictedWorld} uses. */
    private final BlockState[] states;

    /**
     * Block-entity NBT per cell, keyed with {@link PlacementGeometry#positionKey}. A map and not a second dense array
     * because a schematic of fifteen thousand cells typically carries a few dozen of these, and because it is never
     * iterated — every read is a lookup by position, so no hash order can reach the plan.
     */
    private final Map<Long, CompoundTag> blockEntities;

    /** Block cells per layer, index {@code layerY - min.getY()}, each already ordered by (x, z). Layers with nothing
     *  to do hold an empty list rather than being absent, so no lookup can miss. */
    private final List<List<BlockPos>> cellsByLayer;

    /** {@link #cellsByLayer} minus the halves vanilla fills in for free — the list the layer loop iterates. */
    private final List<List<BlockPos>> primaryCellsByLayer;

    private final List<Integer> layers;
    private final List<BlockPos> cells;
    private final List<BlockState> distinctStates;

    /** Cells captured DRY because waterlogging was out of scope. Declared in the plan report: a build
     *  that deliberately finishes a cell in a state the schematic did not ask for has to say so. */
    private final int driedCells;

    private SchematicView(String name, Vec3i origin, BlockPos min, BlockPos max, int sizeX, int sizeY, int sizeZ,
                          BlockState[] states, Map<Long, CompoundTag> blockEntities,
                          List<List<BlockPos>> cellsByLayer,
                          List<List<BlockPos>> primaryCellsByLayer, List<Integer> layers, List<BlockPos> cells,
                          List<BlockState> distinctStates, int driedCells) {
        this.name = name;
        this.origin = origin;
        this.min = min;
        this.max = max;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.states = states;
        this.blockEntities = blockEntities;
        this.cellsByLayer = cellsByLayer;
        this.primaryCellsByLayer = primaryCellsByLayer;
        this.layers = layers;
        this.cells = cells;
        this.distinctStates = distinctStates;
        this.driedCells = driedCells;
    }

    /**
     * Walk the schematic box once, resolve every cell against the untouched snapshot, and freeze the result.
     *
     * <p>{@code approxPlaceable} is passed straight through to {@link ISchematic#desiredState}; V3 hands it the
     * material ledger's item set rather than a per-tick guess at what is on the hotbar, because a schematic that
     * chooses a different state depending on what the bot happened to be holding cannot be planned against.
     *
     * @param name            the user-facing build name, carried so the plan and the report agree on it
     * @param schematic       already through the user transforms (substitutes, mirror, rotation, skip mask)
     * @param origin          where the schematic's local (0,0,0) sits in the world
     * @param world           the snapshot; read via {@link PredictedWorld#original}, never {@code get}, so that a
     *                        capture taken mid-plan would still describe the same schematic
     * @param approxPlaceable states the builder is willing to accept, for schematics that adapt to them
     */
    /** As {@link #capture(String, ISchematic, Vec3i, PredictedWorld, List, boolean)} with waterlogging in scope. */
    public static SchematicView capture(String name, ISchematic schematic, Vec3i origin, PredictedWorld world,
                                        List<BlockState> approxPlaceable) {
        return capture(name, schematic, origin, world, approxPlaceable, true);
    }

    /**
     * @param waterloggingInScope when false, every desired state is captured DRY: {@code waterlogged} is forced to
     *                            false here, at the one place the schematic is read, so that the oracle, the material
     *                            ledger, the acceptance comparison and the report all see the same block. Forcing it
     *                            in only some of those is how a cell becomes placeable and permanently unsatisfied at
     *                            the same time — the layer gate then never opens and nothing says why. The count of
     *                            cells altered is kept and declared in the report; this is a stated deviation from
     *                            the schematic, never a silent one.
     */
    public static SchematicView capture(String name, ISchematic schematic, Vec3i origin, PredictedWorld world,
                                        List<BlockState> approxPlaceable, boolean waterloggingInScope) {
        int sizeX = Math.max(0, schematic.widthX());
        int sizeY = Math.max(0, schematic.heightY());
        int sizeZ = Math.max(0, schematic.lengthZ());
        BlockPos min = new BlockPos(origin.getX(), origin.getY(), origin.getZ());
        BlockPos max = min.offset(Math.max(0, sizeX - 1), Math.max(0, sizeY - 1), Math.max(0, sizeZ - 1));
        // Copied rather than referenced: desiredState receives this list on every one of the box's cells, and the
        // caller's list is the live approxPlaceable, which BuilderProcess rewrites every tick.
        List<BlockState> placeable = approxPlaceable == null ? List.of() : List.copyOf(approxPlaceable);

        BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
        Map<Long, CompoundTag> blockEntities = new HashMap<>();
        List<List<BlockPos>> cellsByLayer = new ArrayList<>(sizeY);
        List<List<BlockPos>> primaryByLayer = new ArrayList<>(sizeY);
        List<Integer> layers = new ArrayList<>();
        List<BlockPos> cells = new ArrayList<>();
        List<BlockState> distinct = new ArrayList<>();
        // Membership only, never iterated: the report order is imposed by the sort below, so a hash container here
        // cannot leak into the plan. Stated because the rule in this package is otherwise absolute.
        Set<BlockState> distinctSeen = new HashSet<>();
        int driedCells = 0;

        for (int localY = 0; localY < sizeY; localY++) {
            List<BlockPos> inLayer = new ArrayList<>();
            List<BlockPos> primaries = new ArrayList<>();
            // x outer, z inner: this IS the plan's last tiebreak (5.3), and producing it here rather than sorting
            // afterwards means no caller can reintroduce another order by accident.
            for (int localX = 0; localX < sizeX; localX++) {
                for (int localZ = 0; localZ < sizeZ; localZ++) {
                    int x = min.getX() + localX;
                    int y = min.getY() + localY;
                    int z = min.getZ() + localZ;
                    BlockState current = world.original(x, y, z);
                    // inSchematic FIRST, always. CompositeSchematic.desiredState throws IllegalStateException on an
                    // uncovered position and LitematicaSchematic delegates to it, so a multi-region litematic with a
                    // gap kills the whole capture at the first hole in it.
                    if (!schematic.inSchematic(localX, localY, localZ, current)) {
                        continue;
                    }
                    BlockState desired = schematic.desiredState(localX, localY, localZ, current, placeable);
                    if (desired == null) {
                        continue;
                    }
                    if (!waterloggingInScope && FluidPlan.wantsWaterlogging(desired)) {
                        // Dried HERE and nowhere else. Every consumer downstream reads this array, so one edit keeps
                        // the oracle's item search, the bucket ledger, the acceptance test and the report agreeing
                        // about what this cell is. Counted, because the report has to say how many cells the build
                        // will deliberately finish in a state the schematic did not ask for.
                        desired = desired.setValue(BlockStateProperties.WATERLOGGED, false);
                        driedCells++;
                    }
                    states[(localY * sizeZ + localZ) * sizeX + localX] = desired;
                    if (desired.isAir()) {
                        // Kept as a covered cell with an air state, not dropped: "this cell must end up empty" is
                        // what makes a terrain leftover a BREAK candidate, and it is the answer placeAt destroys.
                        continue;
                    }
                    BlockPos cell = new BlockPos(x, y, z);
                    // Captured with the state and at the same instant, for the same reason: a lazy read would answer
                    // differently once the planner's own simulated placements have moved the world underneath it.
                    // Absent for all but a handful of cells, which is why it is stored only when present.
                    CompoundTag nbt = schematic.blockEntity(localX, localY, localZ);
                    if (nbt != null) {
                        blockEntities.put(PlacementGeometry.positionKey(cell), nbt.copy());
                    }
                    inLayer.add(cell);
                    if (!PlacementGeometry.isSecondaryHalf(desired)) {
                        primaries.add(cell);
                    }
                    cells.add(cell);
                    if (distinctSeen.add(desired)) {
                        distinct.add(desired);
                    }
                }
            }
            cellsByLayer.add(List.copyOf(inLayer));
            primaryByLayer.add(List.copyOf(primaries));
            if (!inLayer.isEmpty()) {
                layers.add(min.getY() + localY);
            }
        }

        // Sorted by name then by the full state text, both of which are fixed properties of the state rather than of
        // this run: the report prints materials in this order and two runs of the same build must render the same
        // file. Identity or hash order would look correct in every test and differ between JVMs.
        distinct.sort(Comparator.comparing(PlacementGeometry::blockName).thenComparing(BuildAction::describeState));

        return new SchematicView(name, origin, min, max, sizeX, sizeY, sizeZ, states, Map.copyOf(blockEntities),
                List.copyOf(cellsByLayer), List.copyOf(primaryByLayer), List.copyOf(layers), List.copyOf(cells),
                List.copyOf(distinct), driedCells);
    }

    /** How many cells the capture dried because waterlogging was out of scope. */
    public int driedCells() {
        return this.driedCells;
    }

    /** The user-facing build name. */
    public String name() {
        return this.name;
    }

    /** Where the schematic's local (0,0,0) sits in the world. */
    public Vec3i origin() {
        return this.origin;
    }

    /** Inclusive lower corner of the box, absolute. */
    public BlockPos min() {
        return this.min;
    }

    /** Inclusive upper corner of the box, absolute. */
    public BlockPos max() {
        return this.max;
    }

    // ------------------------------------------------------------------- layers

    /**
     * Layer Y values in build order, bottom to top, absolute — and ONLY the layers that hold at least one cell the
     * plan has something to do in.
     *
     * <p>Bottom to top is not configurable here even though {@code Settings.layerOrder} exists, and that is a
     * consequence of the hard layer bound rather than an oversight: the upward-look family stands one layer BELOW
     * its target and clicks the underside of a helper block one layer above it, so a top-down build would have to
     * place its scaffold into a finished layer. Top-down is a different design, not a flag.
     */
    public List<Integer> layers() {
        return this.layers;
    }

    /** Lowest layer Y that holds a cell, absolute. */
    public int minLayer() {
        return this.layers.isEmpty() ? this.min.getY() : this.layers.get(0);
    }

    /** Highest layer Y that holds a cell, absolute. */
    public int maxLayer() {
        return this.layers.isEmpty() ? this.min.getY() : this.layers.get(this.layers.size() - 1);
    }

    /**
     * Every cell in one layer that the schematic wants a BLOCK in, ordered lexicographically by (x, z).
     *
     * <p>The order is the planner's last tiebreak (5.3) and therefore the thing that makes two runs of the same build
     * produce the same trace. It is fixed here rather than at the point of use so that no caller can reintroduce a
     * hash iteration by accident: everything downstream consumes this list.
     */
    public List<BlockPos> cellsInLayer(int layerY) {
        int index = layerY - this.min.getY();
        return index < 0 || index >= this.cellsByLayer.size() ? List.of() : this.cellsByLayer.get(index);
    }

    /**
     * As {@link #cellsInLayer}, minus the secondary halves — see {@link #isSecondaryHalf}. This is the list the layer
     * loop iterates; the omitted cells are filled by their own primary's action list, not by a separate visit.
     */
    public List<BlockPos> primaryCellsInLayer(int layerY) {
        int index = layerY - this.min.getY();
        return index < 0 || index >= this.primaryCellsByLayer.size() ? List.of() : this.primaryCellsByLayer.get(index);
    }

    /** Every block cell in the whole schematic, layer-major in build order then (x, z). */
    public List<BlockPos> cells() {
        return this.cells;
    }

    /** How many cells the schematic wants a block in — the denominator of every completion figure the bench prints. */
    public int cellCount() {
        return this.cells.size();
    }

    /** Which layer a cell belongs to. Absolute Y today, and a method rather than a field read so that a
     *  {@code layerHeight} greater than one can be added without touching a single call site. */
    public int layerOf(BlockPos pos) {
        return pos.getY();
    }

    // ------------------------------------------------------------------- cells

    /**
     * The state the schematic wants at an absolute position, air included, or {@code null} when the position is
     * outside the box or outside the schematic's own mask.
     *
     * <p>Air is a real answer here, unlike {@code BuilderProcess.placeAt}: "this cell must end up empty" is what
     * makes a terrain block inside the box a candidate for {@code BREAK}, and collapsing it to null is what let V2
     * build around leftovers from an earlier run.
     */
    public BlockState desired(BlockPos pos) {
        return pos == null ? null : desired(pos.getX(), pos.getY(), pos.getZ());
    }

    /** As {@link #desired(BlockPos)}, absolute coordinates. */
    public BlockState desired(int x, int y, int z) {
        int localX = x - this.min.getX();
        int localY = y - this.min.getY();
        int localZ = z - this.min.getZ();
        if (localX < 0 || localX >= this.sizeX || localY < 0 || localY >= this.sizeY
                || localZ < 0 || localZ >= this.sizeZ) {
            return null;
        }
        return this.states[(localY * this.sizeZ + localZ) * this.sizeX + localX];
    }

    /** Is this position inside the box and inside the schematic's mask — i.e. does the schematic have an opinion
     *  about it at all, air or not? */
    public boolean covers(BlockPos pos) {
        return desired(pos) != null;
    }

    /** Does the schematic want a placeable block here? {@code covers(pos) && !desired(pos).isAir()}. The scaffold
     *  eligibility rules and the upward family's foot-column test both ask exactly this question. */
    public boolean wantsBlock(BlockPos pos) {
        BlockState state = desired(pos);
        return state != null && !state.isAir();
    }

    /**
     * Is the cell already correct in {@code world} under the acceptance rules — the {@code todo} filter of 5.1.
     *
     * <p>Delegates to {@link V3Settings#valid} rather than to {@code equals}, because "already correct" is a user
     * setting: {@code buildIgnoreExisting}, {@code buildValidSubstitutes} and {@code okIfWater} all widen it, and a
     * planner that used equality would replan cells the executor would then refuse to touch.
     *
     * <p>{@code itemVerify} is false here on purpose. That is the "is this cell done" question, and V2 asks it with
     * false from {@code placementSatisfiedOrHandedOff} while asking the different "would this click be accepted"
     * question with true from {@code placementResultAccepted}. Passing true here would make the planner re-plan cells
     * the executor then declares finished, forever.
     */
    public boolean satisfied(PredictedWorld world, V3Settings settings, BlockPos pos) {
        return settings.valid(world.get(pos), desired(pos), false);
    }

    /**
     * The state to SOLVE FOR at this cell right now — {@link #desired} everywhere except the first half of a double
     * chest, which vanilla can only land as {@code type=single}.
     *
     * <p>{@code ChestBlock.getStateForPlacement} reaches LEFT or RIGHT only when a chest is already standing beside
     * the cell ({@code candidatePartnerFacing} requires a neighbour that is a chest with {@code TYPE == SINGLE}). With
     * an empty partner cell every stance, face, aim and sneak state produces SINGLE, so asking the oracle for
     * {@code left} is asking for a click that does not exist — which is exactly the 88 {@code WRONG_STATE_WOULD_LAND}
     * chests. The pair is a two-step process and SINGLE is its mandatory intermediate state.
     *
     * <p>So the planner asks for what vanilla can produce AT THAT MOMENT, and the second half's placement converts the
     * first for free through {@code ChestBlock.updateShape}. Nothing about the acceptance rules moves: the gate still
     * compares the landed state against this target exactly, and {@link PlacementFamilies} still predicts SINGLE, so
     * the two simply agree instead of disagreeing.
     *
     * <p>{@link #desired} keeps its meaning as the FROZEN schematic, which is what makes this safe: {@link #satisfied},
     * {@code CellExecutor.schematicLayerViolation} and {@code PlannedBuilderProcess.observeServerBlockChange} all still
     * enforce the true {@code left}/{@code right}. A pair whose second half never lands therefore still stops the next
     * layer with a named cell — loudly, not silently.
     *
     * <p>The downgrade is withheld when the schematic's own two halves are not
     * {@link PlacementFamilies#chestPairConsistent} — vanilla cannot pair those, so they stay blocked with a reason.
     */
    public BlockState placementTarget(PredictedWorld world, BlockPos pos) {
        BlockState desired = desired(pos);
        BlockPos partner = PlacementFamilies.chestPairPartner(desired, pos);
        if (partner == null || !chestPairPending(world, desired, partner)) {
            return desired;
        }
        return desired.setValue(ChestBlock.TYPE, ChestType.SINGLE);
    }

    /** Is the partner half of this double chest still missing from {@code world}? Once it is standing, the real
     *  {@code left}/{@code right} is what {@code getStateForPlacement} produces and the target is the schematic's. */
    private boolean chestPairPending(PredictedWorld world, BlockState desired, BlockPos partner) {
        BlockState partnerDesired = desired(partner);
        if (!PlacementFamilies.chestPairConsistent(desired, partnerDesired)) {
            return false;
        }
        BlockState standing = world.get(partner);
        return standing == null || standing.getBlock() != partnerDesired.getBlock();
    }

    // ------------------------------------------------------------------- block entities

    /**
     * The block-entity NBT the schematic carries for a cell, or {@code null}.
     *
     * <p>{@code null} is by far the common answer and means two different things that this type deliberately does not
     * distinguish: the cell has no block entity, or the file dropped it. The difference is a property of the FORMAT,
     * not of the cell, and it is reported once per build through {@link #carriesBlockEntities} rather than once per
     * cell — fifteen thousand identical "no NBT" lines teach nothing, and one line saying the format carries none
     * teaches the whole thing.
     */
    public CompoundTag blockEntity(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        CompoundTag nbt = this.blockEntities.get(PlacementGeometry.positionKey(pos));
        return nbt == null ? null : nbt.copy();
    }

    /** Does this schematic carry block-entity data at all? False for every format whose parser drops it, which is
     *  the honest thing to say up front in the report rather than after a build full of blank signs. */
    public boolean carriesBlockEntities() {
        return !this.blockEntities.isEmpty();
    }

    /** How many cells carry block-entity data. The report prints it so "4 signs, 12 chests" is visible before the
     *  run rather than inferred from it. */
    public int blockEntityCount() {
        return this.blockEntities.size();
    }

    /**
     * The lines to type into a cell's sign, or an empty list when there is nothing to type.
     *
     * <p>Empty covers every reason at once and that is intended here, because they all lead to the same action —
     * none. The cell is not a sign, the file carried no NBT for it, the side is blank. The one case that must NOT be
     * silently empty is a sign whose text is present but unreadable, and {@link SignNbt#unreadable} is how the caller
     * separates it out before deciding to place a blank sign over a schematic that asked for words.
     *
     * @param frontSide 26.1.2 signs have two writable sides
     */
    public List<String> signLines(BlockPos pos, boolean frontSide) {
        CompoundTag nbt = blockEntity(pos);
        return SignNbt.isSign(nbt) ? SignNbt.lines(nbt, frontSide) : List.of();
    }

    // ------------------------------------------------------------------- secondary halves

    /**
     * Is this cell the half that vanilla places for free — a door's UPPER, a bed's HEAD, a tall flower's UPPER?
     *
     * <p>Filtering these out of the layer loop is not an optimisation. Both halves are separate schematic cells and
     * both are non-air, so a loop that visits them independently asks the oracle for a placement of a door's upper
     * half — for which there is no item, no click and no legal state — and reports it as an unsolvable blocker. The
     * lower half places both; the upper half is a consequence, and its layer is a layer the plan never has to enter
     * on its account.
     *
     * <p>Predicate lives in {@link PlacementGeometry#isSecondaryHalf} with the rest of the vanilla knowledge; this
     * is the view's application of it, so the layer loop never has to remember the rule.
     */
    public boolean isSecondaryHalf(BlockPos pos) {
        return PlacementGeometry.isSecondaryHalf(desired(pos));
    }

    /** The cell whose placement also fills {@code pos}, for a secondary half — the door's lower cell, the bed's foot.
     *  {@code null} when {@code pos} is not a secondary half. Used by the audit, which must count the free half as
     *  built rather than as missing. */
    public BlockPos primaryOf(BlockPos pos) {
        BlockState state = desired(pos);
        if (!PlacementGeometry.isSecondaryHalf(state)) {
            return null;
        }
        // A bed carries the same horizontal facing on both halves and it points foot -> head, so the foot is one step
        // BACK along it. A door's upper half is simply the cell above the lower one. Read through
        // BlockStateProperties rather than through BedBlock.FACING so a state that somehow lacks the property is a
        // null answer rather than an IllegalArgumentException in the middle of a dry run.
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (state.hasProperty(BlockStateProperties.BED_PART)) {
                return pos.relative(facing.getOpposite());
            }
        }
        return pos.below();
    }

    // ------------------------------------------------------------------- materials

    /**
     * Every distinct block state the schematic asks for, ordered by {@link PlacementGeometry#blockName} so that two
     * runs report the same materials in the same order. The input to the hotbar capacity check (5.8) and to the
     * scaffold planner's "a helper block type that appears in NO schematic cell" rule (5.5).
     */
    public List<BlockState> distinctStates() {
        return this.distinctStates;
    }

    /** How many cells ask for this exact state, under {@link V3Settings#sameBlockstate}. The material ledger's
     *  per-item demand before lossy breaks are added to it. */
    public int demandFor(V3Settings settings, BlockState state) {
        if (state == null) {
            return 0;
        }
        int demand = 0;
        for (BlockPos cell : this.cells) {
            // The cell's own state is the FIRST argument: sameBlockstate iterates the first state's properties and is
            // deliberately asymmetric (trap 1.63), so swapping these counts a different set of cells.
            if (settings.sameBlockstate(desired(cell), state)) {
                demand++;
            }
        }
        return demand;
    }
}
