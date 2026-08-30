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

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import princeps.api.utils.Rotation;
import princeps.api.utils.RotationUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code BREAK} action type of 5.9 — everything the planner knows about taking a block back out.
 *
 * <p>Precondition V6 has always read "cell empty or foreign block removable", but V2 had no action for the second
 * half, so every build site that was not surgically clean lost cells to a case the engine could see and not answer:
 * terrain inside the box, leftovers from an earlier run, an orientation that landed wrong, water in the way. A break
 * is EQUAL in rank to a placement here and runs through the same machinery — stance, approach, aim curve, a gate that
 * checks the live ray, a held click, a server acknowledgement — which is why it carries the same twelve fields rather
 * than being a special case the executor improvises.
 *
 * <h2>Scope is a rule, not an accident</h2>
 *
 * <p>The engine does NOT empty every cell the schematic marks as air. It breaks only what stands in the way of an
 * action it is about to take. Clearing a volume stays a separate switch ({@code clearArea}) because the two are
 * different promises: "make room for this click" is bounded by the plan, and "make this region match the schematic's
 * air" is bounded by the region — and a builder that silently did the second on a survival server has destroyed
 * somebody's terrain rather than built a farm.
 *
 * <h2>Two executor constraints that must survive planning</h2>
 *
 * <ul>
 *   <li><b>{@code BlockBreakHelper} has no target.</b> It breaks whatever the crosshair rests on, and
 *       {@code InputOverrideHandler} forces {@code CLICK_LEFT} without ever being told which block was meant. So the
 *       action carries everything needed to VERIFY the aim before the click is armed — {@code cell}, {@code face},
 *       {@code aimPoint}, {@code rotation} and {@code expected} — and the executor's gate is a result comparison
 *       against the live ray, never an angle comparison ({@code Rotation.isReallyCloseTo}'s 0.01° never fires under
 *       humanized look).</li>
 *   <li><b>Never in the same tick as a placement.</b> {@code InputOverrideHandler.java:91-93} clears
 *       {@code CLICK_RIGHT} whenever {@code CLICK_LEFT} is forced, and it runs BEFORE the helpers tick — so a tick
 *       that holds both gives {@code BlockPlaceHelper} a {@code tick(false)}, which reads-and-nulls the pending
 *       {@code ExpectedPlacement} with no diagnostics at all. The frozen order is a sequence of actions and the
 *       executor advances one at a time, so the constraint holds by construction; it is written down here because the
 *       first "optimisation" anyone reaches for is to overlap the two.</li>
 * </ul>
 *
 * <p>A third one is checkable at plan time and therefore is checked: {@link #repeatedBreaks}. {@code BlockBreakHelper}
 * blacklists a {@code BlockPos} PERMANENTLY when it is broken twice inside {@code REGROW_WINDOW_MS = 4000}
 * ({@code BlockBreakHelper.java:88-101}), and {@code clearBlacklist()} is called from nowhere in {@code src/} — so a
 * plan that breaks one position twice can deadlock forever on the second one, and the deadlock looks exactly like a
 * slow build.
 *
 * <h2>Pure by construction</h2>
 *
 * <p>Nothing here reads {@code Princeps.settings()} and nothing here logs — {@code Helper.logMechanic} initialises an
 * interface whose field initialisers call {@code Minecraft.getInstance()}, which is the same untestability
 * {@link V3Settings} exists to keep out of this package. The world mutations the cascade implies are handed to an
 * {@link Edits} sink rather than written directly, so {@link OrderPlanner.PlannerState} stays the single owner of the
 * predicted world, the dependency index and the enclosure marking (decision E-C, exactly one owner per state).
 */
public final class BreakPlanner {

    /**
     * The faces of a block the stance search tries, in order.
     *
     * <p>UP first because the overwhelmingly common breaking stance is beside and above the target, then the four
     * sides, then DOWN, which needs a stance below and is the rarest. The order is fixed for determinism; the
     * preference embedded in it is a cost heuristic and nothing depends on it being optimal.
     */
    private static final Direction[] BREAK_FACES = {
            Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    /** Neighbours a break's consequences are chased through, in a fixed order so the cascade is reproducible. */
    private static final Direction[] CASCADE_NEIGHBOURS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** Horizontal half-width of the stance window, matching {@code PlacementOracle}'s placement window so that a cell
     *  reachable for a placement is reachable for a break. */
    public static final int STANCE_RADIUS = 3;

    /** Lowest break stance relative to the target: two down covers breaking a block at head height from below. */
    public static final int STANCE_LOWEST = -2;

    /** Highest break stance relative to the target: one up covers breaking a floor block while standing on the next
     *  one along. */
    public static final int STANCE_HIGHEST = 1;

    /**
     * The yaw every stored rotation is wrapped relative to — the same fixed reference {@code PlacementOracle} uses,
     * and it has to be the same one.
     *
     * <p>A plan is proven hours before the bot stands there, so there is no "current yaw" to keep the turn short
     * against. A fixed reference makes the stored angle canonical: two runs of the same build record the same number
     * rather than two representatives of the same angle, and {@code plan.txt} diffs to nothing. The executor wraps it
     * against the live rotation when it hands the aim to {@code ILookBehavior}.
     */
    private static final Rotation PLANNING_REFERENCE = new Rotation(0.0F, 0.0F);

    /**
     * Blocks whose break does not return the item that places them again, by registry path.
     *
     * <p>Membership only, never iterated, so the hash order it is stored in cannot reach a plan or a report. The list
     * is deliberately short and deliberately about the cases a SCHEMATIC contains: the families below are matched by
     * class, the suffix rules cover the ores and the panes, and this set holds the rest — the blocks that hand back a
     * different item, which for the material ledger is the same loss as handing back nothing.
     *
     * <p>It is a curated list rather than a loot-table query because a loot table needs a {@code ServerLevel}, which
     * the planner does not have and a unit test cannot build. That is a named limitation ({@link #LOSS_LIMITATION}),
     * not a hidden assumption: an unlisted lossy block is booked as dropping, so the ledger under-counts rather than
     * refusing to plan, and the report says which way it errs.
     */
    private static final Set<String> LOSSY_BLOCKS = Set.of(
            // Ice that is not an IceBlock subclass. Both melt to nothing without silk touch.
            "packed_ice", "blue_ice",
            // Stone and the dirt family: cobblestone, dirt. A schematic full of stone that has to break one cell of
            // its own stone gets cobblestone back, and cobblestone does not place stone.
            "stone", "deepslate", "grass_block", "podzol", "mycelium", "dirt_path", "farmland",
            // Hand back a different item entirely.
            "glowstone", "sea_lantern", "clay", "bookshelf", "melon", "snow_block", "snow", "gravel",
            "budding_amethyst", "turtle_egg", "sniffer_egg", "dragon_egg"
    );

    /** The report line that says which way {@link #drops} errs. Named up front, per the report's own rule that every
     *  limitation is a promise kept in advance rather than a surprise. */
    public static final String LOSS_LIMITATION =
            "break loss is decided from a curated block list, not from loot tables (a loot table needs a ServerLevel "
                    + "the planner does not have) — an unlisted lossy block is booked as dropping, so the material "
                    + "ledger under-counts rather than refusing the build";

    private BreakPlanner() {
    }

    /**
     * Why a break is being asked for. All four are the triggers of 5.9, and they are carried rather than inferred
     * because the report sentence differs: "the cell holds the wrong block" is a schematic problem the user can fix in
     * the schematic, and "the stance is buried in terrain" is a build-site problem they can fix with a shovel.
     */
    public enum Reason {

        /** A schematic cell holds something other than {@code desired}. */
        SCHEMATIC_CELL,

        /** A cell an action needs to stand in is blocked by terrain. */
        STANCE,

        /** The cell above a stance — the bot's head room — is blocked. */
        HEAD_ROOM,

        /** A cell on the ray between the crouched eye and the click point is blocked. */
        RAY
    }

    /** Why no break was planned. {@link #NONE} means one was. */
    public enum Refusal {

        /** A break was planned. */
        NONE,

        /** The cell already satisfies the schematic under the acceptance rules — nothing to do. */
        CELL_ALREADY_CORRECT,

        /** The cell is air, or holds something the click itself replaces (tall grass, snow layers, water). Breaking it
         *  would be an action that buys nothing and pays the full break cooldown. */
        NOTHING_TO_REMOVE,

        /** A gravity block stands directly above: it would land in the very cell the break was clearing, so the break
         *  would have to be repeated — and repeating a break on one position is what blacklists it forever. */
        FALLING_BLOCK_ABOVE,

        /** No stance in the window yields a look whose crosshair provably rests on the target. */
        NO_PROVABLE_AIM
    }

    /**
     * One proven left-click or right-click on an EXISTING block: where to stand, where inside that cell, which face,
     * and the exact look. The break-and-interact counterpart of {@link PlacementSolution}, which cannot answer this
     * because it solves for a cell that is still empty.
     */
    public record Aim(BlockPos stance, Vec3 approach, Direction face, Vec3 point, Rotation rotation) {
    }

    /**
     * The outcome of asking for a break: the action, or the named reason there is none.
     *
     * <p>A refusal is a first-class answer rather than an empty {@link Optional} because every one of them is a
     * sentence the report wants to print. "This cell was not broken" is not information; "this cell was not broken
     * because a gravity block stands over it" is the line that tells a human to go and move the sand.
     */
    public record Attempt(BuildAction.Break action, Refusal refusal, String note) {

        /** A planned break. */
        public static Attempt of(BuildAction.Break action) {
            return new Attempt(action, Refusal.NONE, null);
        }

        /** A refusal that needs no report line — the cell is simply already correct or already empty. */
        public static Attempt refused(Refusal refusal) {
            return new Attempt(null, refusal, null);
        }

        /** A refusal the report must print. */
        public static Attempt refused(Refusal refusal, String note) {
            return new Attempt(null, refusal, note);
        }

        public boolean planned() {
            return this.action != null;
        }

        /** The action as an {@link Optional}, for the call sites that only want the happy path. */
        public Optional<BuildAction.Break> optional() {
            return Optional.ofNullable(this.action);
        }
    }

    /**
     * The world mutations a cascade implies, handed to whoever owns the predicted world.
     *
     * <p>The cascade cannot be a pure function — a block that pops off changes whether the NEXT one still has support
     * — but it must not be the second writer to the world either, because the enclosure marking, the dependency index
     * and the invalidation list all have to move with it. So it decides and this sink applies. A test passes a sink
     * that only touches the {@link PredictedWorld}; the planner passes one that keeps its bookkeeping in step.
     */
    public interface Edits {

        /** Empty a cell. */
        void vacate(BlockPos pos);

        /** Put a state into a cell — a gravity block that resettled one layer down. */
        void fill(BlockPos pos, BlockState state);
    }

    /**
     * What a break did to the world beyond the cell it emptied.
     *
     * @param emptied cells that ended up air, the broken one first, in the order the cascade reached them
     * @param lost    SCHEMATIC cells of the current or an earlier layer that lost their block — every one of them owes
     *                an explicit re-placement in the plan, at this point in the order, or the layer gate finds a hole
     *                nobody can explain
     * @param notes   report lines for the parts the simulation refuses to model
     */
    public record Cascade(List<BlockPos> emptied, List<BlockPos> lost, List<String> notes) {

        public Cascade {
            emptied = List.copyOf(emptied);
            lost = List.copyOf(lost);
            notes = List.copyOf(notes);
        }
    }

    // ------------------------------------------------------------------- planning one break

    /**
     * Plan the break of one cell, or name why there is none.
     *
     * <p>The {@code todo} filter and the {@code BREAK} trigger are one question asked twice, so they are one method:
     * a cell that {@link SchematicView#satisfied} accepts needs neither.
     *
     * <p>Refuses under a falling block, and that refusal is load-bearing rather than cautious. Sand, gravel, concrete
     * powder and an anvil all land in the very cell the break was trying to empty, so the cell would be occupied again
     * the moment it was cleared; a plan that broke it anyway would either loop or, worse, break the same position
     * twice inside four seconds and hit {@code BlockBreakHelper}'s permanent blacklist. E3 forbids tearing down to
     * correct, so the cell is REPORTED instead — which is the answer the dry run exists to give.
     *
     * @param preferred a stance to try before the search — normally where the bot already stands, so a break that
     *                  follows its own placement pays no walk at all
     * @param reason    which of 5.9's four triggers is asking. Only {@link Reason#SCHEMATIC_CELL} consults
     *                  {@link SchematicView#satisfied} first, because only that trigger is about a cell the schematic
     *                  has an opinion on — a stance, a head room or a ray cell is terrain in the way, and asking the
     *                  schematic about it would answer "satisfied" for every position outside the box
     * @param layer     the layer the action belongs to; the caller knows whether the cell is a schematic cell of its
     *                  own layer or terrain being cleared for the current one
     */
    public static Attempt plan(PredictedWorld world, SchematicView view, V3Settings settings, PlayerPose pose,
                               SolveBudget budget, BlockPos cell, BlockPos preferred, Reason reason, int layer) {
        if (reason == Reason.SCHEMATIC_CELL && view.satisfied(world, settings, cell)) {
            return Attempt.refused(Refusal.CELL_ALREADY_CORRECT);
        }
        BlockState current = world.get(cell);
        if (current.isAir() || current.canBeReplaced()) {
            // Tall grass, snow layers, water: vanilla replaces these on the click itself, and the oracle's precheck
            // agrees -- it refuses a cell only when canBeReplaced is false. Breaking them would be an action that
            // buys nothing and pays TICKS_PER_BREAK.
            return Attempt.refused(Refusal.NOTHING_TO_REMOVE);
        }
        if (world.get(cell.above()).getBlock() instanceof FallingBlock) {
            return Attempt.refused(Refusal.FALLING_BLOCK_ABOVE,
                    "cell " + BuildAction.describePos(cell) + " holds " + BuildAction.describeState(current)
                            + " under a falling block — not broken, because the block above would land in the cell "
                            + "the break was clearing");
        }
        Optional<Aim> aim = aim(world, pose, budget, cell, preferred, null, true);
        if (aim.isEmpty()) {
            return Attempt.refused(Refusal.NO_PROVABLE_AIM,
                    "cell " + BuildAction.describePos(cell) + " holds " + BuildAction.describeState(current)
                            + " and no stance in the window puts the crosshair on it — the block stays and the cell "
                            + "cannot be built");
        }
        Aim at = aim.get();
        return Attempt.of(new BuildAction.Break(cell, at.stance(), at.approach(), at.point(), at.rotation(),
                at.face(), current, drops(current),
                // The geometry above was proven from the CROUCHED eye, so the click has to go out crouched or the ray
                // it was proven with is not the ray that is cast.
                true, HotbarSchedule.PICKAXE_SLOT, layer));
    }

    /**
     * Find a stance and a look that put the crosshair on an existing block.
     *
     * <p>Deliberately narrower than the placement search: the cell centre rather than the optimised approach grid, and
     * the first workable face rather than a ranking. There is nothing for a margin to protect here — a break has no
     * orientation to get wrong and an interaction's outcome does not depend on where on the face the click lands — so
     * the extra rays would buy a number nobody reads.
     *
     * @param placing the state that will be in the cell when the click goes out, or {@code null} to read the world's
     *                current one — an interaction is aimed at a block the plan has not placed yet
     */
    public static Optional<Aim> aim(PredictedWorld world, PlayerPose pose, SolveBudget budget, BlockPos cell,
                                    BlockPos preferred, BlockState placing) {
        return aim(world, pose, budget, cell, preferred, placing, false);
    }

    /**
     * Shared aim search, with the physical result named explicitly. A removal has one extra invariant: after the block
     * becomes air, the whole arrival disk must still be supported.
     */
    private static Optional<Aim> aim(PredictedWorld world, PlayerPose pose, SolveBudget budget, BlockPos cell,
                                     BlockPos preferred, BlockState placing, boolean removing) {
        BlockState targetState = placing == null ? world.get(cell) : placing;
        List<AABB> outline = OutlineGeometry.localParts(world, cell, targetState);
        if (outline.isEmpty()) {
            return Optional.empty();
        }
        List<AABB> collision = collisionParts(world, cell, targetState);
        if (collision == null) {
            return Optional.empty();
        }
        for (BlockPos stance : stances(cell, preferred)) {
            if (!world.isLoaded(stance.getX(), stance.getY(), stance.getZ())
                    || !world.isStandable(stance.getX(), stance.getY(), stance.getZ())) {
                continue;
            }
            Vec3 approach = new Vec3(stance.getX() + 0.5D, PlacementOracle.footY(world, stance),
                    stance.getZ() + 0.5D);
            if (!PlacementOracle.approachEnvelopeSupported(
                    world, pose, stance, approach, FineApproach.TOLERANCE)) {
                continue;
            }
            AABB body = pose.bodyAt(approach);
            if (collision.stream().anyMatch(body::intersects)) {
                continue;   // the same check A1 makes for a placement: the bot cannot be inside what it is clicking
            }
            if (removing && !supportedAfterRemoval(world, pose, cell, approach)) {
                continue;
            }
            Vec3 eye = pose.eyeAt(approach);
            for (Direction face : BREAK_FACES) {
                for (AABB part : outline) {
                    Vec3 point = OutlineGeometry.faceCentre(cell, part, face);
                    if (eye.distanceTo(point) > budget.maxReach()) {
                        continue;
                    }
                    Rotation rotation = RotationUtils.calcRotationFromVec3d(eye, point, PLANNING_REFERENCE);
                    if (!OutlineGeometry.hits(world, cell, targetState, eye, rotation, budget.maxReach(), face)) {
                        continue;
                    }
                    return Optional.of(new Aim(stance, approach, face, point, rotation));
                }
            }
        }
        return Optional.empty();
    }

    /** Stage one removal and prove that it does not take the current floor with it or reshape it away. */
    private static boolean supportedAfterRemoval(PredictedWorld world, PlayerPose pose, BlockPos cell, Vec3 approach) {
        ScaffoldPlanner.Scratch scratch = new ScaffoldPlanner.Scratch(world);
        try {
            scratch.apply(cell, Blocks.AIR.defaultBlockState());
            return PlacementOracle.arrivalDiskSupported(world, pose, approach, FineApproach.TOLERANCE);
        } finally {
            scratch.restore();
        }
    }

    /** Candidate feet cells for a click on an existing block, nearest first, with an optional preferred stance at the
     *  head. */
    public static List<BlockPos> stances(BlockPos cell, BlockPos preferred) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -STANCE_RADIUS; dx <= STANCE_RADIUS; dx++) {
            for (int dz = -STANCE_RADIUS; dz <= STANCE_RADIUS; dz++) {
                for (int dy = STANCE_LOWEST; dy <= STANCE_HIGHEST; dy++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) {
                        continue;   // feet or head inside the block being clicked
                    }
                    candidates.add(cell.offset(dx, dy, dz));
                }
            }
        }
        // Nearest to the target, then by coordinate. Ranked on the CELL rather than on the bot's current position so
        // that the same break plans the same way wherever the planner happens to be in the order -- the ranking is a
        // property of the geometry, and a stance chosen by the walk history would make the plan depend on it.
        candidates.sort(Comparator.comparingLong((BlockPos stance) -> distanceSquared(cell, stance))
                .thenComparingLong(PlacementGeometry::positionKey));
        if (preferred != null && !preferred.equals(cell)) {
            candidates.remove(preferred);
            candidates.add(0, preferred);
        }
        return candidates;
    }

    // ------------------------------------------------------------------- the cascade

    /**
     * Run a break's consequences through the predicted world: gravity blocks resettle, neighbours that lose their
     * support pop off, and both of those can empty a cell that starts the whole thing again one block further out.
     *
     * <p>Two mechanisms, and they are genuinely different. {@code canSurvive} decides whether a torch, a repeater, a
     * carpet or a rail may EXIST where it is, and a block that fails it pops off the instant its support goes.
     * Gravity says nothing about survival — sand survives perfectly well and then falls — so a column of it above a
     * fresh hole shifts DOWN one cell and its top cell becomes the next hole. Modelling only the first would leave the
     * plan believing a schematic block is at a position the world has just moved it out of, and every proof after that
     * would be against a fiction.
     *
     * <p>Every schematic cell of the current or an earlier layer that loses its block is returned in {@link
     * Cascade#lost}. The caller owes each of them an explicit re-placement AT THIS POINT in the order: a cascade the
     * plan does not model is a divergence at execution time, and the executor's only honest response to a divergence
     * is to stop.
     *
     * @param currentLayer cells of a LATER layer are left alone — their own layer has not started, so nothing is owed
     * @param edits        the sink that owns the world; see {@link Edits}
     */
    public static Cascade cascade(PredictedWorld world, SchematicView view, V3Settings settings, BlockPos broken,
                                  int currentLayer, Edits edits) {
        List<BlockPos> emptied = new ArrayList<>();
        List<BlockPos> lost = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        ArrayDeque<BlockPos> holes = new ArrayDeque<>();
        // Membership only, never iterated: it exists to stop the walk revisiting a cell, and a hash order in it could
        // not reach the output even if it were walked, because the output lists are appended in visit order.
        LongOpenHashSet seen = new LongOpenHashSet();
        holes.add(broken);
        seen.add(PlacementGeometry.positionKey(broken));
        emptied.add(broken);

        while (!holes.isEmpty()) {
            BlockPos hole = holes.poll();
            settle(world, view, settings, hole, currentLayer, edits, holes, seen, emptied, lost, notes);
            for (Direction side : CASCADE_NEIGHBOURS) {
                BlockPos victim = hole.relative(side);
                BlockState standing = world.get(victim);
                if (standing.isAir()) {
                    continue;
                }
                Direction support = PlacementGeometry.requiredSupportDirection(standing);
                if (support == null || !victim.relative(support).equals(hole)) {
                    continue;   // it hangs on something else, or on nothing named at all
                }
                if (!seen.add(PlacementGeometry.positionKey(victim))) {
                    continue;
                }
                edits.vacate(victim);
                emptied.add(victim);
                holes.add(victim);
                if (owedBack(view, settings, world, victim, currentLayer)) {
                    lost.add(victim);
                }
            }
        }
        return new Cascade(emptied, lost, notes);
    }

    /**
     * The gravity half of the cascade: the contiguous column of falling blocks directly above a fresh hole drops by
     * exactly one cell, and its top cell becomes a hole of its own.
     *
     * <p>One cell and not more, because the hole is one cell: {@code FallingBlock.tick} spawns an entity only while
     * the space below is free, and after the shift the bottom of the column occupies the hole. The column is walked
     * contiguously — the first non-falling block above it is a lid, and everything over the lid stays where it is.
     */
    private static void settle(PredictedWorld world, SchematicView view, V3Settings settings, BlockPos hole,
                               int currentLayer, Edits edits, ArrayDeque<BlockPos> holes, LongOpenHashSet seen,
                               List<BlockPos> emptied, List<BlockPos> lost, List<String> notes) {
        List<BlockState> column = new ArrayList<>();
        BlockPos scan = hole.above();
        while (world.get(scan).getBlock() instanceof FallingBlock) {
            column.add(world.get(scan));
            scan = scan.above();
        }
        if (column.isEmpty()) {
            return;
        }
        for (int height = 0; height < column.size(); height++) {
            edits.fill(hole.above(height), column.get(height));
        }
        BlockPos top = hole.above(column.size());
        edits.vacate(top);
        notes.add("a column of " + column.size() + " falling block(s) resettled into "
                + BuildAction.describePos(hole) + " — the plan places the column one cell lower and treats "
                + BuildAction.describePos(top) + " as the new hole");

        // Every cell the column passed through changed state, top included. A schematic cell among them has lost what
        // it held even though nothing was broken there, and it owes a re-placement exactly like a pop-off does.
        for (int height = 0; height <= column.size(); height++) {
            BlockPos moved = hole.above(height);
            if (owedBack(view, settings, world, moved, currentLayer)) {
                lost.add(moved);
            }
        }
        if (seen.add(PlacementGeometry.positionKey(top))) {
            emptied.add(top);
            holes.add(top);
        }
    }

    /** Does the schematic want a block at this position, in a layer that has already started, and is what stands
     *  there now not it? The three-part question that decides whether a cell the cascade touched owes a re-placement. */
    private static boolean owedBack(SchematicView view, V3Settings settings, PredictedWorld world, BlockPos pos,
                                    int currentLayer) {
        return view.wantsBlock(pos) && view.layerOf(pos) <= currentLayer && !view.satisfied(world, settings, pos);
    }

    // ------------------------------------------------------------------- loss, and the blacklist trap

    /**
     * Does breaking this block hand back the item that places it again?
     *
     * <p>Glass is the case the plan names, and it is not the only one: leaves, ice, ores, stone and the dirt family
     * all hand back something else or nothing at all, which for a material ledger is the same loss. The ledger books a
     * replacement for every one of them, because the alternative is a build that stops two thousand cells later on a
     * missing material with nothing in the log connecting it to a break that happened at cell four.
     *
     * <p>Answered from a curated list rather than from the loot table on purpose — {@code Block.getDrops} needs a
     * {@code ServerLevel} the planner does not have and a unit test cannot build. The default is TRUE, so an unlisted
     * block is assumed to drop itself and the ledger under-counts rather than refusing to plan; {@link
     * #LOSS_LIMITATION} is the report line that says so.
     */
    public static boolean drops(BlockState state) {
        if (state == null || state.isAir()) {
            return true;   // nothing there, nothing to lose
        }
        Block block = state.getBlock();
        // Families, by class. TransparentBlock is 26.1.2's name for what earlier versions called
// AbstractGlassBlock -- glass, stained glass, tinted glass, and nothing else; the sibling
// HalfTransparentBlock is deliberately NOT used, because slime and honey blocks are that and both
// drop themselves. Then every leaf, ice that melts, infested stone, and spawners.
        if (block instanceof TransparentBlock || block instanceof LeavesBlock || block instanceof IceBlock
                || block instanceof InfestedBlock || block instanceof SpawnerBlock) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        // Two suffix rules rather than forty entries: every *_glass_pane and the plain glass_pane drop nothing, and
        // every *_ore hands back a raw material or a gem, never the ore block.
        if (path.endsWith("glass_pane") || path.endsWith("_ore")) {
            return false;
        }
        return !LOSSY_BLOCKS.contains(path);
    }

    /**
     * Positions the frozen order takes a block out of more than once — the plan-time detection of trap 1.32.
     *
     * <p>{@code BlockBreakHelper} counts break/rebuild cycles per {@code BlockPos} and, at {@code REGROW_LIMIT = 1}
     * inside a four-second window, blacklists the position PERMANENTLY; {@code InputOverrideHandler.onTick:97-99} then
     * refuses to force {@code CLICK_LEFT} there for the rest of the session, and nothing in {@code src/} ever clears
     * the blacklist. The failure is a bot standing in front of a block, correctly aimed, clicking nothing, forever.
     *
     * <p>{@link BuildAction.RemoveScaffold} counts too and that is the whole point: it goes out through the same
     * helper with the same held click, so a helper block placed and removed at a position the plan later breaks is two
     * breaks at one position even though only one of them is called {@code BREAK}.
     *
     * <p>Reported rather than refused. Two breaks four hours apart are perfectly safe, and the plan cannot know the
     * real interval — but a plan with none of these cannot hit the trap at all, which makes the empty list the
     * useful answer.
     *
     * @return the offending positions, each once, ordered by {@link PlacementGeometry#positionKey}
     */
    public static List<BlockPos> repeatedBreaks(List<BuildAction> order) {
        List<BlockPos> broken = new ArrayList<>();
        for (BuildAction action : order) {
            if (action.kind() == BuildAction.Kind.BREAK || action.kind() == BuildAction.Kind.REMOVE_SCAFFOLD) {
                broken.add(action.cell());
            }
        }
        // Sorted and scanned rather than counted in a map: the answer is a LIST that goes into a report, and a map
        // would have to be forbidden from being iterated to produce it in a stable order anyway.
        broken.sort(Comparator.comparingLong(PlacementGeometry::positionKey));
        List<BlockPos> repeated = new ArrayList<>();
        for (int index = 1; index < broken.size(); index++) {
            BlockPos previous = broken.get(index - 1);
            if (previous.equals(broken.get(index))
                    && (repeated.isEmpty() || !repeated.get(repeated.size() - 1).equals(previous))) {
                repeated.add(previous);
            }
        }
        return List.copyOf(repeated);
    }

    // ------------------------------------------------------------------- geometry helpers

    /**
     * Vanilla collision components of the target in world coordinates, for the body-overlap predicate only.
     *
     * <p>An empty collision shape is a real answer (torch, repeater, wire), not permission to fabricate a full cube.
     * A shape-evaluation failure returns {@code null}; callers conservatively refuse the target because they cannot
     * prove the body fits. Click geometry never comes through this method -- that is always {@link OutlineGeometry}.
     */
    private static List<AABB> collisionParts(PredictedWorld world, BlockPos cell, BlockState state) {
        try {
            List<AABB> parts = state.getCollisionShape(OutlineGeometry.withState(world, cell, state), cell,
                    CollisionContext.empty()).toAabbs();
            if (parts.isEmpty()) {
                return List.of();
            }
            List<AABB> moved = new ArrayList<>(parts.size());
            for (AABB part : parts) {
                if (part.maxX > part.minX && part.maxY > part.minY && part.maxZ > part.minZ) {
                    moved.add(part.move(cell.getX(), cell.getY(), cell.getZ()));
                }
            }
            return List.copyOf(moved);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Squared cell distance as a long, not {@code BlockPos.distSqr}'s double: the comparison decides the order of the
     *  stance list and integer arithmetic cannot round two different distances into one. */
    private static long distanceSquared(BlockPos from, BlockPos to) {
        long dx = from.getX() - to.getX();
        long dy = from.getY() - to.getY();
        long dz = from.getZ() - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
