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

package princeps.process;

import princeps.Princeps;
import princeps.api.behavior.look.AimIntent;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.goals.GoalComposite;
import princeps.api.pathing.goals.GoalGetToBlock;
// Imported, never written out in full: the fields named `princeps` in this file shadow the package root, so
// `princeps.api.pathing.movement.ActionCosts.X` does not resolve. Cost me two compile errors already.
import princeps.api.pathing.movement.ActionCosts;
import princeps.api.pathing.path.IPathExecutor;
// Same trap, fourth occurrence in one session: `princeps.flownav.FlowCam.suspendView()` resolves `princeps` against
// the field, not the package. Anything under the princeps root must be imported in this file, never qualified.
import princeps.flownav.FlowCam;
import princeps.api.pathing.PlacementLicence;
import princeps.api.pathing.WadeLicence;
import princeps.api.process.IBuilderProcess;
// Imported as a simple name: this class has a field named `princeps`, so the nested enum cannot be
// written out as princeps.api.process.IBuilderProcess.Ending anywhere in this file.
import princeps.api.process.IBuilderProcess.Ending;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.schematic.*;
import princeps.api.schematic.format.ISchematicFormat;
import princeps.api.utils.*;
import princeps.api.utils.Rotation;
import princeps.api.utils.input.Input;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.movement.Movement;
import princeps.pathing.movement.MovementHelper;
import princeps.pathing.movement.MovementOption;
import princeps.pathing.movement.movements.MovementDownward;
import princeps.pathing.movement.movements.MovementTraverse;
import princeps.pathing.path.PathExecutor;
import princeps.process.builder.BlockItemPlacementHelper;
import princeps.process.builder.ActionJournal;
import princeps.process.builder.BuildTrace;
import princeps.process.builder.CellWatch;
import princeps.process.builder.BuilderProgressWatch;
import princeps.process.builder.ConfirmedBuildActions;

import princeps.process.builder.PlacementTargetLock;
import net.minecraft.world.level.pathfinder.PathComputationType;
import princeps.pathing.calc.PathProbe;
import princeps.pathing.calc.SearchBudget;
import princeps.utils.PrincepsProcessHelper;
import princeps.utils.BlockPlaceHelper;
import princeps.utils.BlockStateInterface;
import princeps.utils.PathingCommandContext;
import princeps.utils.AreaTool;
import princeps.utils.ToolSet;
import princeps.utils.schematic.MapArtSchematic;
import princeps.utils.schematic.SchematicSystem;
import princeps.utils.schematic.SelectionSchematic;
import princeps.utils.schematic.litematica.LitematicaHelper;
import princeps.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Tuple;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static princeps.api.pathing.movement.ActionCosts.COST_INF;
import static princeps.api.utils.RotationUtils.DEG_TO_RAD_F;

public final class BuilderProcess extends PrincepsProcessHelper implements IBuilderProcess {

    /** Placement-CONTROLLABLE orientation: a survival player picks these by where they stand / which face+spot they
     *  click, so they must match the schematic exactly (only bypassed when the user sets buildIgnoreDirection). */
    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    TrapDoorBlock.HALF
            );

    /** Properties a placement can NEVER dictate — they are resolved by the environment (fence/wall/pane/bars/redstone
     *  connections derive from neighbours; leaf decay bookkeeping the game maintains) or need a follow-up interaction
     *  or redstone we don't drive (open, powered). A fence set between two posts legitimately stands unconnected until
     *  its neighbours exist, and every such state is CORRECT the instant it's placed. Matched by name so it holds
     *  across every connective block and mapping. NOTE: kept OFF this list on purpose — facing / half / hinge / axis
     *  (the things a human actually aims), and waterlogged (owned by the bucket pass).
     *
     *  <p>{@code shape} belongs here despite reading like geometry: a stair's SHAPE is not placeable. Vanilla derives
     *  it in getStateForPlacement and recomputes it in updateShape whenever a neighbour changes — no aim, stance or
     *  click can choose it. Enforcing it meant the builder rejected its OWN correct placement forever: the bench's
     *  oriented scenario stalled on stairs where the simulation produced exactly the wanted facing and half, and
     *  differed only in shape=outer_right vs straight, which the adjacent stairs had already decided. Rails carry a
     *  {@code shape} too and self-connect the same way, so the same reasoning covers them.</p> */
    private static final Set<String> AUTO_RESOLVED_PROP_NAMES =
            ImmutableSet.of(
                    "north", "east", "south", "west", "up", "down",
                    "powered", "power", "in_wall",
                    "distance", "persistent",
                    "shape",
                    // Written by the GAME, never by whoever places the block, and no right-click sets them either.
                    // Each one below was confirmed against 26.1.2's own bytecode (the overriding method is named), not
                    // assumed -- because getting this list wrong is silent and total: the builder compares the state
                    // it wanted against the state that landed, they differ in a property no click can influence, the
                    // cell is "incorrect" forever, and with buildInLayers + skipFailedLayers=false a single such cell
                    // stops the whole build at its layer. etz-basalt is 1081 repeaters, 544 note blocks, 2048 pistons
                    // and 140 hoppers, so this list is the difference between building it and never leaving layer 3.
                    "locked",       // RepeaterBlock.updateShape -- a powered repeater to the side locks this one
                    "instrument",   // NoteBlock.updateShape -- read off the block BELOW, recomputed when it changes
                    "enabled",      // HopperBlock.neighborChanged -- redstone decides it
                    "extended",     // PistonBaseBlock.onPlace -- redstone decides it
                    "bottom",       // ScaffoldingBlock.updateShape -- whether anything holds it from below
                    "triggered",    // dispenser/dropper, pulsed by redstone
                    "lit",          // redstone torch/lamp/furnace -- burning or powered, not placed
                    "short",        // piston head length, owned by the piston
                    "snowy",        // grass/podzol, read off the block ABOVE
                    "attached",     // tripwire, owned by its hook
                    "disarmed",     // tripwire
                    "signal_fire",  // campfire, read off the hay bale below
                    "occupied",     // bed, owned by whoever sleeps in it
                    // CONTENTS. A freshly placed bookshelf is empty, a freshly placed lectern has no book, a freshly
                    // placed cauldron holds nothing. Filling them is item logistics, not construction -- no click and
                    // no placement sets these, so comparing them makes a correctly built shelf permanently wrong.
                    // (lighthouse.litematic has 12 chiseled bookshelves and a lectern, all recorded full.)
                    "slot_0_occupied", "slot_1_occupied", "slot_2_occupied",
                    "slot_3_occupied", "slot_4_occupied", "slot_5_occupied",
                    "has_book", "has_record", "has_bottle_0", "has_bottle_1", "has_bottle_2", "level",
                    // GROWTH. Crops age, honey fills, eggs hatch, sculk cracks -- all on the game's schedule.
                    "age", "stage", "honey_level", "bites", "eggs", "hatch",
                    "moisture", "hydration", "dusted", "berries", "cracked"
            );

    /** Properties a placement CAN'T set but a follow-up right-click CAN: trapdoor/door/gate OPEN, repeater DELAY,
     *  comparator MODE, note-block NOTE. These are enforced for correctness (so the build isn't falsely "done")
     *  but fixed by the interaction pass (aim + right-click N times), never by break-and-replace. See
     *  {@link #interactionClicks}. "open" moved here from AUTO_RESOLVED so trapdoors/doors actually get opened. */
    private static final Set<String> INTERACTION_PROP_NAMES =
            ImmutableSet.of("open", "delay", "mode", "note",
                    "inverted");  // daylight detector: right-click flips it, exactly like comparator mode

    /**
     * Wie deutlich die Blickachse die anderen beiden schlagen muss, damit ein Zielpunkt angenommen wird.
     *
     * <p>1,15 heisst: fuenfzehn Prozent Vorsprung. Der gemessene Schadensfall hatte 0,8 Prozent -- 0,6136 gegen
     * 0,6086 -- und kippte. Ein Aufschlag ist hier kein Sicherheitspuffer im ueblichen Sinn, sondern die Grenze,
     * ab der die Frage ueberhaupt entscheidbar ist: unterhalb davon haengt das Ergebnis an Rundung, nicht an
     * Geometrie, und dann kann kein Verfahren der Welt es vorhersagen.
     *
     * <p>Der Preis ist, dass knappe Zielpunkte verworfen werden und die Standplatzsuche einen anderen finden
     * muss. Das ist der richtige Preis: ein verworfener Punkt kostet Suchzeit, ein gekippter Block kostet nach
     * P5 den ganzen Bau.
     */
    private static final double AIM_DOMINANCE_MARGIN = 1.15;
    private static final int AIM_RECOVERY_TICKS = 60;
    private static final int AIM_NO_CLOSER_TICKS = 10;
    /** A plain full cube has no placement state worth changing stance for. If a freshly-derived, already-aligned
     *  ray still misses for this many ticks in a row, briefly yield that cell so the normal build route keeps flowing.
     *  Sized to the crouch-eye settle window: after arriving the eye interpolates 1.62->1.27 over ~6 ticks, and the
     *  vanilla crosshair hit lags the applied aim by ~1 tick, so a value of 2 (the old default) yielded the cell
     *  before the pose had even settled — the visible sneak-stakkato. Hold the committed target (and its forced
     *  sneak) across the whole settle window instead, so the click lands on the first sneak-hold. */
    private static final int ORDINARY_GATE_YIELD_TICKS = 8;
    /** After this many yields of the SAME full cube (its GoalAdjacent stance keeps producing an aligned-but-invalid
     *  ray — occluded or edge geometry), stop yielding forever and reposition via the same walk-center-derive stance
     *  recovery oriented blocks use. Closes the ordinary-cube livelock: the hotfix that gave cubes a yield had no
     *  reposition path, so an occluded cube looped defer->retry with the bot standing still. Recovery is bounded
     *  (finite stances, then deferCell), so this terminates. */
    private static final int ORDINARY_MAX_YIELDS = 3;
    /** buildTick advances before selection; +2 excludes exactly the next scheduler tick, then expires. */
    private static final int ORDINARY_CELL_YIELD_TICKS = 2;
    private static final int PLACE_REPRESS_RECOVER_AT = 8;
    private static final int PLACE_REQUEST_NO_ACK_RECOVER_AT = 80;
    private static final int UNSTICK_AT_TICKS = 30;
    /**
     * While a helper block is being refused for want of a proven stance, commit a target after this many quiet ticks
     * instead of {@link #UNSTICK_AT_TICKS}.
     *
     * <p>Kept deliberately next to the 30 it undercuts, because the ORDER of the two is the whole safety argument.
     * The refusal at {@code MovementHelper.attemptToPlaceABlock} takes away the pathfinder's cheapest way to satisfy
     * an unproven goal; something has to give it a PROVEN one, promptly, or the bot simply stands there. That
     * something already exists and is already measured to work: the branch at the {@code UNSTICK_AT_TICKS} test ends
     * in {@code placementRecoveryCommand} → {@code new GoalBlock(plannedPlacementStance)}, i.e. exactly the
     * "precisely to the proven position" navigation the owner asked for. In the 96/96 run 50f40a49 it produced 6 of
     * the 8 stance commitments; the opportunistic scan produced none.
     *
     * <p>Why lowering it is safe, and this is the part the note at the trigger warns about: that branch also requires
     * {@code !placementTargetLock.isActive()}, and a successful acquisition sets the lock. So on the common path this
     * fires EARLIER, not MORE OFTEN — one commitment per lock episode either way, at tick 8 rather than 31.
     *
     * <p>But when the acquisition FAILS the lock stays inactive and the test is true again next tick, and each pass
     * consumes a candidate through {@code mayTryStance}. That is the documented regression (facings 96/96 → 57/96 and
     * 50/96, reproduced twice, when this fired every tick), so the early threshold is spent AT MOST ONCE per veto
     * episode — see {@code scaffoldVetoUnstickTried}. After that the ordinary 30 applies and the candidate pool is
     * consumed at exactly the old rate.
     *
     * <p>8 rather than a rounder number to match {@link #ORDINARY_GATE_YIELD_TICKS}, the other "yield briefly, then
     * reposition" budget in this file.
     */
    private static final int SCAFFOLD_VETO_UNSTICK_AT = 8;
    /**
     * After this many refusals in one episode the helper block is allowed after all.
     *
     * <p>Not a nicety — the alternative is a measured freeze. {@code MovementHelper.attemptToPlaceABlock} records that
     * refusing a movement does NOT make the router avoid the node: an earlier guard there froze the bot for 8800
     * ticks at {@code 104,-58,89} because the router kept handing back the same two-node path and the refusal fired
     * every tick. The lapse turns that failure mode from a dead build into a 20-tick pause.
     *
     * <p>Under every countdown that could undo it: {@code UNSTICK_AT_TICKS} 30, {@code UNSTICK_AFTER_QUIET_TICKS} 60,
     * {@code ROUTE_NO_CLOSER_TICKS} 80, and the bench's own {@code STALL_TICKS} 1920, of which 20 ticks is 1%.
     */
    private static final int SCAFFOLD_VETO_MAX_TICKS = 20;
    /**
     * How many quiet ticks may pass inside one refusal episode before it counts as over.
     *
     * <p>It was 1, and that number is why the lapse never armed in run f159bc1d. The refusal cadence is set by the
     * planner's own cycle -- refuse, cancel the path, recalculate, refuse again -- which lands a refusal on every
     * SECOND tick: 882 of the measured gaps were exactly 2. With a window of 1 the episode decayed between every pair,
     * {@code scaffoldVetoTicks} was reset to 0 forever, and a guard sized at 20 was reached zero times in 1920 ticks.
     *
     * <p>4 rather than 2, so a cycle that stretches by a tick or two under load still counts as the same episode. This
     * only bounds how long the veto may hold before letting a block through; it never causes one.
     */
    private static final int SCAFFOLD_VETO_EPISODE_GAP = 4;
    /**
     * EXPERIMENT, 05.08.2026, at the owner's request: no helper block for the pathfinder, ever.
     *
     * <p>"Ich denke, er könnte es auch ohne schaffen." The question is whether navigation needs to build at all, and
     * the previous run says it is worth asking: of 557 refused placements, {@code by=ascend} accounted for 552 and
     * {@code by=pillar} for 5 — every single one came from a MOVEMENT wanting a step up, none from the builder. 248 of
     * them were the same cell, {@code 75,-60,68}, retried over and over because refusing a movement does not make the
     * router avoid the node.
     *
     * <p>With this set, {@link #SCAFFOLD_VETO_UNSTICK_AT} and {@link #SCAFFOLD_VETO_MAX_TICKS} stop mattering for the
     * decision (the counters keep running, so the trace still shows how long each episode lasted) and the two measured
     * freeze guards are deliberately given up. The bench's own {@code STALL_TICKS} = 1920 is what ends the run if the
     * bot cannot proceed, which is the answer the experiment is looking for.
     *
     * <p>The builder's OWN scaffolding is untouched: a cell reserved through {@code temporarySupportGoal} is checked
     * before this, because that is deliberate, tracked and self-removing — the thing the owner asked for — and not a
     * throwaway step the pathfinder invented.
     *
     * <p>ANSWERED, 05.08.2026, and the answer is no -- not while the target selection still sends the bot into
     * positions from which nothing can be placed. Two runs, both with the three scenarios and a basalt window:
     *
     * <pre>
     *   veto at EXECUTION time   facings 96/96   basalt 1997/15004 @ t20060   bpm 119.5   paidOff 66%
     *   COST_INF at the PLANNER  facings 75/96   basalt 2300/15004 @ t22860   bpm 120.7   paidOff 45%
     *   (licence, for reference) facings 96/96   basalt 4124/15004 @ t49440   bpm 100.1   paidOff 94%
     * </pre>
     *
     * The 21 cells facings loses are the top stone row, watched live by the owner: the bot ends up with its HEAD in
     * the cell it wants to fill, which satisfies GoalAdjacent and makes placing impossible, and the helper block was
     * what let it climb out of that. So the block was not solving a routing problem -- it was papering over the
     * target selection. Both runs also produced the highest build rates ever measured (119.5 / 120.7 against 100.1),
     * which says the same thing from the other side: it moves faster without building, it just cannot reach
     * everything.
     *
     * <p>Left in place, set to false, because the question becomes live again once every cell gets a computed stance:
     * a stance search excludes the whole target column for the body, so the head-in-cell position cannot be returned,
     * and the reason the block was needed may then be gone.
     */
    private static final boolean NAVIGATION_MAY_NEVER_SCAFFOLD = false;
    private static final int ROUTE_NO_CLOSER_TICKS = 80;
    private static final int ROUTE_DEADLINE_TICKS = 240;
    /**
     * How long a cell parked by the distance trim may stay invisible before the work set is rebuilt.
     *
     * <p>200 ticks is ten game seconds: the builder places roughly one cell per fifteen ticks, so a parked cell comes
     * back within about a dozen placements instead of never. Cost is real and worth naming rather than guessing:
     * {@code fullRecalc} walks the whole schematic box (18x63x63), so ~196 extra passes over a 39000-tick window. That
     * is the same order as one of the per-tick scans, spread thin -- read {@code ticks_per_sec_achieved} before
     * shortening it, and if a bigger schematic makes it bite, restrict the refill to a small work set rather than
     * lengthening the interval, because a large set is exactly where parked cells hide.
     */
    private static final int WORK_SET_REFILL_INTERVAL = 200;

    /**
     * How long the builder may produce nothing before the radius trim stops shrinking the work set. See {@link #trim}
     * for the episode this exists for.
     *
     * <p>Chosen from the run, not from taste. Run 8d8e288f placed 3945 cells in 53100 ticks -- <b>one cell every 13.5
     * ticks</b> on average -- so 60 ticks of silence is already four and a half times the normal spacing and cannot
     * happen while the local window is productive. The measured distribution of the dead-goal episodes it targets has
     * its knee at the same place: of 352 episodes, the 209 that resolve within 20 ticks hold only 1313 ticks (14%),
     * while the 143 longer ones hold <b>7749 of 9062 ticks (86%)</b>.
     *
     * <p>Deliberately NOT shorter. A legitimate walk of 30 blocks takes roughly 140 ticks with no completion in it, and
     * during such a walk this suspension is harmless -- the window is capped at {@code incorrectSize} either way, so the
     * only cost is that the per-tick scan walks up to 100 cells instead of two. What was measured as expensive was
     * raising the CAP (incorrectSize 1000: -7%), not keeping the capped window intact.
     */
    private static final int TRIM_SUSPEND_AFTER_QUIET_TICKS = 60;

    /**
     * How long nothing may become correct before the builder COMMITS a cell and walks to a stance for it, whatever the
     * pathfinder thinks about its own progress. See the second trigger in {@code onTick}.
     *
     * <p>Same number as {@link #TRIM_SUSPEND_AFTER_QUIET_TICKS}, from the same measurement: run 3c3bffb3 completed a
     * cell every 13.5 ticks on average and the doors scenario every 30, so 60 cannot be reached while the local
     * situation is productive. Stated separately because the two bound different things -- that one bounds a WINDOW,
     * this one bounds a STALL -- and a later change may need to move one without the other.
     *
     * <p>Firing this EVERY tick instead was tried and reverted: the recovery path consumes a stance candidate on each
     * pass, so at 60x the frequency the pool empties 60x faster and the cell reports "all stances exhausted" purely
     * because it was asked too often. facings went 96/96 -> 57/96 and 50/96 on two runs, stanceless 64 -> 126. The
     * numbers and the reason are at the trigger itself.
     */
    private static final int UNSTICK_AFTER_QUIET_TICKS = 60;


    /**
     * WHAT A HELPER BLOCK IS ALLOWED TO COST, stated as the owner states it: <b>one helper block is worth twenty
     * blocks of walking.</b> If the detour on foot is shorter than that, walk it; if it is longer, place the block.
     *
     * <p>Deliberately NOT {@code placeBlockCost}, and this is the one place in the builder that overrides the bench on
     * purpose. {@code BuilderBench} sets {@code blockPlacementPenalty = 500}, and 500 ticks divided by
     * {@link ActionCosts#WALK_ONE_BLOCK_COST} is <b>108 blocks</b> of walking -- and the schematic-air branch of
     * {@link BuilderCalculationContext#costOfPlacingAt} then multiplied that by
     * {@code placeIncorrectBlockPenaltyMultiplier = 10}, reaching 5000, i.e. <b>1080 blocks</b>. Nothing on this course
     * is 1080 blocks away, so the intended meaning ("only if there is really no other way") never actually applied: the
     * price was so far outside the reachable range that it stopped being a comparison at all.
     *
     * <p>Why a PRICE and not a prohibition, having tried the prohibition twice: A* does not read a large number as
     * "consider last", it reads it as "consider after everything cheaper has been enumerated" -- and {@code COST_INF}
     * does not price an edge, it deletes it. A33 deleted the edge and the run lost 119 cells, because a course that is
     * 24% built has to be bridged to be walked at all. A price cannot do that: every route stays findable, the cheap
     * one simply wins. See the long note in {@code costOfPlacingAt}.
     *
     * <p>Read together with {@code allowParkour = true} in that same constructor. The two are the same decision from
     * both ends -- let it jump the gap, and stop pretending the block that fills the gap is nearly free.
     */
    private static final double HELPER_BLOCK_COST = ActionCosts.WALK_ONE_BLOCK_COST * 20; // 92.66 ticks

    private static final int STANCE_CENTERING_TICKS = 80;
    // The stance oracle validates the ray from the exact block center. Keep the execution pose close enough to that
    // same point that a marginal reach/occlusion result cannot be invalidated by accepting a broad in-cell radius.
    /**
     * How near the middle of its stance the body has to be before a click counts as aimed from the centre.
     *
     * <p>0.0025 means a radius of 0.05 blocks, and that is SMALLER THAN ONE SNEAK STEP. centerInPlacementStance
     * drives the body with a forced movement key while crouching, which moves it about 0.065 blocks per tick, so
     * the approach can only ever step over the target and back: measured on dig15, "atStance=true onGround=true
     * centred=false" every second for the whole run while the tunnel head waited for a swing that never came.
     * A controller whose smallest step is bigger than its tolerance does not converge.
     *
     * <p>0.0225 is a radius of 0.15 blocks -- two sneak steps of room, still deep inside a one-block cell and far
     * inside the face the recovery ray is validated against.
     */
    private static final double STANCE_CENTER_TOLERANCE_SQ = 0.0225D;
    private static final int CELL_DEFER_BASE_TICKS = 40;
    private static final int CELL_DEFER_MAX_TICKS = 640;
    private static final int INTERACT_NO_PROGRESS_TICKS = 80;
    /** Interaction give-ups a cell gets before it is set aside. Three, because the failure this bounds is a
     *  flip-flop -- two are enough to distinguish it from a click that simply lost a race. */
    private static final int INTERACT_GIVE_UPS_BEFORE_RETIRING = 3;
    private static final int BREAK_STALL_MIN_TICKS = 120;

    private HashSet<BetterBlockPos> incorrectPositions;
    /** Top of the sliding area-tool window, published to the pathing thread through {@link #areaBand}. */
    /** Lanes are three wide because that is the width one swing of the area pickaxe clears. */
    private static final int SERPENTINE_LANE_WIDTH = 3;

    /**
     * How long the snake waits for a hotbar slot it has asked for before treating the silence as a fight.
     *
     * <p>One second. A selected-slot packet needs one tick; twenty is generous enough that no amount of ordinary
     * lag reaches it, and short enough that a real fight is reported while the operator is still watching.
     */
    private static final int SNAKE_TOOL_WAIT_LIMIT_TICKS = 20;
    /** Maximum horizontal incidence angle from the selected 3x3 face normal. */
    private static final double SNAKE_MAX_OBLIQUE_COS_SQ = Mth.square(Math.cos(Math.toRadians(35.0D)));

    /** The snake's head: the cell one step ahead at head height, whose swing clears the whole slice. */
    private BetterBlockPos snakeHead;
    /** Where the bot must stand for that swing -- the slice it cleared last. */
    private BetterBlockPos snakeStance;
    /** True while the bot is still on top of the band and has to cut its way in. */
    private boolean snakeEntering;
    /** True for the swings that cut the connector across into the next lane. */
    private boolean snakeTurning;
    /**
     * The face the last forced swing actually went out on, and how long the head has been still since.
     *
     * <p>THE PLANE IS DECIDED BY THE ROTATION THE SERVER LAST RECEIVED, not by the one the client had when it
     * clicked. That is written down twice in this file already -- once as a disproven placement fix whose author
     * reached the same conclusion, and once in the bench's own area mixin, which re-picks with player.pick() at
     * the moment destroyBlock runs. On one machine the two are the same tick and nothing can go wrong. Across a
     * real connection the server is one to three ticks behind, and a head that is mid-turn when the break resolves
     * hands the server the face it was leaving.
     *
     * <p>Which is why this only guards a face CHANGE. Along a lane the head barely moves between swings and no
     * wait is needed; the whole hazard is the four ninety-degree turns per band. Free where it is free, careful
     * where it is not.
     */
    private Direction snakeLastSwungFace;
    private int snakeAimSettledTicks;
    private int snakeFaceChangeWaitTicks;
    /** Last decision line, handed to the client so its trace file can carry the reasoning. */
    private volatile String snakeDiagnosis = "";
    /** One non-centre cell in a damaged 3x3 slice, cleared without letting the generic chooser steal the route. */
    private BetterBlockPos snakeCleanupTarget;
    private final SnakeCleanupWork snakeCleanupWork = new SnakeCleanupWork();
    /** True for the whole tick in which {@link #snakeCleanupTarget} owns the mining action. */
    private boolean snakeCleanupActive;
    /** The ordinary-pick preference was unavailable, so this cleanup click must preserve the Shard's exact face. */
    private boolean snakeCleanupWithAreaTool;
    /** Face whose complete Shard footprint was proven to remain inside the selected clear volume. */
    private Direction snakeCleanupAreaFace;
    /** Face of the current horizontal slice, independent of which safe floor cell the bot stands on. */
    private Direction snakeSliceFace;
    /** Suppresses per-tick repeats while one damaged floor cell is being negotiated. */
    private long snakeLastSafeStanceLogKey = Long.MIN_VALUE;
    /** Debounces the server's centre-first block updates before classifying a slice as genuinely damaged. */
    private long snakeMissingCentreKey = Long.MIN_VALUE;
    private long snakeMissingCentreLastTick = Long.MIN_VALUE;
    private int snakeMissingCentreTicks;
    /**
     * The hotbar slot the snake last asked for, and how many DISTINCT builder ticks it has been asking for it.
     *
     * <p>A wait with no end is not a wait, it is a deadlock. {@link #snakeToolReady} answers "not yet" for one
     * tick so the selected-slot packet reaches the server before the first swing, and that is right -- but it
     * answered "not yet" forever if anything outside put the old tool back, and nothing anywhere noticed.
     *
     * <p>MEASURED 20.08., the owner's 11:50 run: the client's own hand guard forced the Shard back every tick
     * while the snake asked for the ordinary pickaxe to sink the entry shaft of the next band. Slot 0 requested
     * and slot 1 held, 447 ticks running, "Snake: selected hotbar 1" hundreds of times a second, no goal, no
     * path, no message. The dig ended every single time at the step down to the next layer, because that is the
     * first moment in a run at which the two owners of the hand want different tools.
     */
    /**
     * How long the swing gate has been refusing for want of a square stance, and the fallback it eventually arms.
     *
     * <p>RESET WITH THE PER-BUILD STATE AND NOWHERE ELSE. 0.3.202 was bricked by exactly this shape of field
     * being cleared at the top of snakeUpdate, which runs every tick: the counter never advanced, its release
     * never fired, and the gate it guarded became unsatisfiable by any sequence of events.
     */
    private int snakeNotSquareTicks;
    private boolean snakeSingleBlockFallback;
    private boolean snakeRequiresOrdinaryTool;
    private int snakeToolWantedSlot = -1;
    private long snakeToolWaitLastTick = Long.MIN_VALUE;
    private int snakeToolWaitTicks;
    private boolean snakeToolWaitReported;
    /** Normalised client-configured server item name; vanilla item identity cannot distinguish custom tools. */
    private boolean snakeCrossIsX;
    private int snakeStartCross;
    private int snakeStartTravel;
    private int snakeBandTop = Integer.MIN_VALUE;
    private int snakeBandFloor = Integer.MIN_VALUE;
    private int snakeLane;
    private int snakeCursor = Integer.MIN_VALUE;
    /**
     * Connector cell currently being crossed at a lane end.
     *
     * <p>The connector is wider than one block, so its progress must survive ticks. Re-starting the connector loop
     * at its first cell makes a cleared first cell pull the bot backwards as soon as it reaches the second one:
     * first -> second -> first forever. The world records which slices were cleared, but it does not record which
     * cleared connector cell has already been walked; this cursor does.
     */
    private int snakeTurnCross = Integer.MIN_VALUE;
    /** Travel-axis centre of the outermost three-wide end cap through which the current lane change is opened. */
    private int snakeTurnTravel = Integer.MIN_VALUE;
    /** True after this lane's end-cap connector was both cleared and walked; retained until the lane advances. */
    private boolean snakeTurnComplete;
    private int snakeStep = 1;
    private int snakeCrossStep = 1;
    private int snakeLastLoggedLane = Integer.MIN_VALUE;
    /** The current head is deliberately empty: it exists only to keep ownership while walking the exact lane. */
    private boolean snakeTransit;
    /** A completed band is walked once more, after a short gravity settle, before the window may descend. */
    private boolean snakeVerificationActive;
    private boolean snakeRouteComplete;
    private int snakeVerificationSettleTicks;
    /** Immutable identity of the band being re-walked; route setup is allowed to reset {@link #snakeBandTop}. */
    private int snakeVerificationBandTop = Integer.MIN_VALUE;
    private int snakeVerifiedBandTop = Integer.MIN_VALUE;
    /** Integrity counters are intentionally separate from ordinary builder placement/mining metrics. */
    private long snakeFloorRepairs;
    private long snakeFluidSourcesPlugged;
    private long snakeShellRepairs;
    private long snakeVerificationPasses;
    /** Bands that reached a stable, source-free, shell-complete certificate (attempts may be higher after debris). */
    private long snakeVerifiedBands;
    /** Scoped settings owner; activated only when the area-tool snake genuinely starts and restored on teardown. */
    private AutoDigLookProfile autoDigLookProfile;
    /** The only helper cell the current short excavation route may place into. */
    private BetterBlockPos snakeBridgeTarget;
    /** The one cardinal cell the corridor route is currently asking A* for, or null before the first step. */
    private BetterBlockPos snakeStepWaypoint;
    /** Consecutive ticks the route has asked for {@link #snakeStepWaypoint} without the bot ever entering it. */
    private int snakeStepStuckTicks;
    /**
     * How long the corridor step is given to happen by itself before the process places the floor.
     *
     * <p>Long enough that it never preempts the normal case: {@code MovementTraverse} approaches the edge, holds
     * sneak, edges out backwards and back-places, which takes well under a second even at the slowest aim. Short
     * enough that a corridor which is never going to be bridged does not cost a run. The failure it closes is not
     * hypothetical -- {@code snakeBridgePlacementCommand} was written for exactly this and had no call site at all,
     * so "the movement will bridge it" was the whole of the recovery, and when the movement is never produced
     * (search refuses, executor cancels, waypoint out of the licensed lane) nothing places anything, ever.
     */
    private static final int SNAKE_STEP_RESCUE_TICKS = 40;
    /**
     * The level the excavation corridor was last actually STOOD ON, or {@link Integer#MIN_VALUE}.
     *
     * <p>Exists so "has not arrived at the corridor yet" can be told apart from "has fallen out of it". From a
     * single tick the two are identical -- feet at one height, stance at another -- and treating the first as the
     * second ended a run before it took a step. Self-clearing by construction: when the band moves down, the
     * remembered level no longer matches the new stance, so the approach is licensed again until the bot has
     * stood there.
     */
    private int snakeCorridorLevelY = Integer.MIN_VALUE;
    /** Bridge clicks awaiting a standable world-state acknowledgement; normally contains at most one cell. */
    private final Set<BetterBlockPos> snakePendingBridgeRepairs = new HashSet<>();
    private int snakeVerificationRepairSweeps;
    private int snakeVerificationStableTicks;
    private int ordinaryIntegrityStableTicks;
    private long ordinaryIntegrityObservedTick = Long.MIN_VALUE;
    private int ordinaryIntegrityBandTop = Integer.MIN_VALUE;
    private BetterBlockPos ordinaryIntegrityTarget;
    private int ordinaryIntegrityTargetTicks;

    private boolean routeCrossIsX;
    private boolean routeAxisChosen;
    private int routeStartCross;
    private int routeStartTravel;
    private int routeBandTop = Integer.MIN_VALUE;
    private int snakeTraceCountdown;
    // DIAGNOSTIC (added while investigating the horizontal-head stall). Throttled probe of the snake's per-tick
    // state, plus the reason snakeHeadRotation last refused. Remove once the stall is understood.
    private long snakeProbeLastTick = Long.MIN_VALUE;
    private String snakeRotWhy = "never called";

    private volatile int areaBandTopCache = Integer.MIN_VALUE;
    /**
     * The selected area-tool band is a transaction, not a highest-work cache. Once committed it may be released only
     * after the physical verification walk has succeeded; otherwise a cleared top row can silently select a lower
     * band while the player is still standing on the old one.
     */
    private int areaCommittedBandTop = Integer.MIN_VALUE;
    /** Immutable cross-thread snapshot of the area in which routing may break blocks. */
    private volatile AreaBand areaBand;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    // placeFailCounts ist WEG. Ein falsches Ergebnis war bis zur v0.4-Umstellung ein Zaehler, der nach drei
    // Versuchen die Standposition verwarf; jetzt ist es P5 und damit ein globaler Abbruch -- siehe
    // detectWrongPlacementResult. Ein zweiter Versuch am selben Ort ist nach der Spezifikation keine Option mehr.
    private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap cellDeferralCounts;
    private it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap cellDeferredUntilTick;
    /** Pathfinder-laid helper floors that must stay until the placement they unlock has actually completed. The key is
     *  the helper cell and the value the schematic target it serves. Entries may be registered while goals are being
     *  assembled; they become behaviourally relevant only if a throwaway block really appears at the helper cell. */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap temporarySupportTargets =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    // ==============================================================================================================
    // DIE GERUEST-PHASE (S7). Die Anweisung des Eigentuemers, woertlich: "wenn Sachen, nachdem alles andere fertig
    // ist, also nichts mehr platzierbar oder enderbar ist, immer noch in der Parkliste stehen, schalten wir dort
    // erstmals das Platzieren von Scaffoldingbloecken ein, sehr stark begrenzt, und diese werden restlos sofort,
    // nachdem sie den Sinn ihres Nutzens erfuellt haben, entfernt, sodass sie nie zurueckbleiben."
    //
    // DREI EIGENSCHAFTEN, und jede einzelne ist der Grund, warum der erste Anlauf scheiterte:
    //  1. EINE PHASE, KEIN REFLEX. Der erste Versuch reservierte mitten im Normalbetrieb je Zelle eine Stuetze --
    //     17 Reservierungen, 0 gesetzte Bloecke, und der Lauf fiel von 2752 auf 1845 Zellen. Ausgeloest wird erst,
    //     wenn die gewoehnliche Phase erschoepft ist: kein Ziel mehr aus assemble, und PARK ist nicht leer.
    //  2. SEHR STARK BEGRENZT. Hoechstens EINE lebende Geruestzelle, und ein Deckel je Ebene. Ein Bauplan, den
    //     niemand bauen kann, soll ein Urteil ergeben und kein Geruestfest.
    //  3. RESTLOS UND SOFORT WEG. Sobald die bediente Zelle steht, faellt die Ueberschreibung, das Soll springt
    //     auf Luft zurueck -- und ab dann ist der Hilfsblock ein Block, der nicht in den Bauplan gehoert, den die
    //     vorhandene Abbau-Maschinerie ohnehin entfernt (isScaffoldLeftBehind, das sich genau solange
    //     zurueckhaelt, wie temporarySupportStillNeeded gilt).
    //
    // WARUM ES UEBER DEN BAUPLAN LAEUFT und nicht ueber einen zweiten Platzierungspfad: desiredState ist die EINE
    // Stelle, die sagt, was in eine Zelle gehoert. Wird der Hilfsblock dort zum Soll, nimmt er den bewiesenen Weg
    // -- Standplatzsuche, Orientierungspruefung, P5 -- und niemand muss ihn noch einmal bauen. Zwei Mechanismen
    // mit zwei Antworten ist der Fehler, den diese Datei schon dreimal bezahlt hat.
    /** Die eine lebende Geruestzelle (Weltkoordinate), oder {@code null}. Mehr als eine gibt es nie. */
    private BetterBlockPos scaffoldCell;
    private final BuilderScaffoldLedger navigationScaffolds = new BuilderScaffoldLedger();
    private final ExcavationFluidPlugs excavationFluidPlugs = new ExcavationFluidPlugs();
    private final ExcavationActiveClock excavationActiveClock = new ExcavationActiveClock();
    private final ExcavationApproach excavationApproach = new ExcavationApproach();
    private long excavationApproachToolChangedTick = Long.MIN_VALUE;
    private final ExcavationRepairAim excavationRepairAim = new ExcavationRepairAim();
    private String lastExcavationRepairAimTrace;
    private long lastExcavationRepairAimTraceTick;
    private ExcavationFluidPlugs.Hazard blockedFluidPlugThisTick;
    private volatile CleanupEscape cleanupEscape;
    /** Retained on stop/new job: clearing working sets is not server-confirmed removal. */
    private volatile BuilderCleanupDebt cleanupEscapeDebt;
    /** Local observation fence for the existing server-packet callback, not a claim of a protocol action id. */
    private long cleanupServerUpdateSequence;
    private final Set<Long> cleanupEscapeAttemptedOwners = new HashSet<>();
    private boolean scaffoldCleanupActive;
    /** Uses the existing cleanup target ledger while retaining the ordinary working layer mask. */
    private boolean layerCleanupActive;
    private final Set<Long> scaffoldCleanupTargets = new HashSet<>();
    /** Die geparkte Zelle, die sie freimachen soll. */
    private BetterBlockPos scaffoldServes;
    /** Was dort stehen soll, solange die Ueberschreibung gilt. */
    private BlockState scaffoldWanted;
    /** Wie viele Geruestzellen diese Ebene schon verbraucht hat -- der Deckel aus Punkt 2. */
    private int scaffoldsThisLayer;
    /** Stand des Stillstandszaehlers beim Eroeffnen -- der Bezugspunkt fuer "eine NEUE Episode". */
    private int scaffoldFrozenEpisodesAtOpen;
    /**
     * Zellen, an denen ein Geruest in dieser Ebene schon gescheitert ist. Ohne dieses Gedaechtnis waehlt die
     * Phase dieselbe Zelle sofort wieder -- im Lauf 78901334 siebenmal hintereinander 74,-60,73 --, weil sie nur
     * nach Naehe entscheidet und nichts von den Vorgaengern weiss.
     */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet scaffoldFailed =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /** Wie oft die Wirkungspruefung im Schatten ja bzw. nein gesagt haette. Die Zahl, die ueber das
     *  Scharfschalten entscheidet -- und die einzige, die dafuer zaehlt. */
    private int wirkungJa;
    private int wirkungNein;
    /** Das Gedaechtnis der Wirkungspruefung. Ein Kandidat kostet EINE Probe, nicht eine je Tick -- sonst
     *  verbraucht die Schleife ihr Budget jeden Tick an denselben ersten Kandidaten und erreicht die guten nie. */
    private final java.util.HashMap<Long, Boolean> scaffoldProbeCache = new java.util.HashMap<>();
    /**
     * Ob die Wirkungspruefung mitentscheidet oder nur mitzaehlt. Steht auf {@code false}, und das ist eine
     * Produktentscheidung mit Zahlen dahinter -- siehe die Notiz an der Pruefstelle: drei Laeufe scharf
     * (2749/3857/4310 Zellen) gegen fuenf ohne (4307 bis 4321). Praeziser ja, besser nein.
     *
     * <p>Ein benannter Schalter und kein {@code && false}: was abgeschaltet ist, muss man lesen koennen, und der
     * Grund gehoert daneben.
     */
    private static final boolean WIRKUNGSPRUEFUNG_ENTSCHEIDET = true;
    /** Wie viele Kandidaten je Tick durchsimuliert werden. Die Standplatzsuche ist die teuerste Einzeloperation
     *  des Bauers, und die Frage hat im naechsten Tick dieselbe Antwort. */
    private static final int MAX_SCAFFOLD_SIMULATIONEN_JE_TICK = 12;
    /**
     * Der Deckel je Ebene.
     *
     * <p>Acht war eine Schaetzung -- "grosszuegig fuer eine Handvoll haengender Zellen" -- und sie war falsch.
     * GEMESSEN, Lauf 5dfd0d55: OPEN=8, DONE=8, GIVEUP=0. Jedes einzelne Geruest hat sein Ziel erreicht, und
     * trotzdem endete der Bau mit "layer 2 has 7 cell(s) that could not be built": der Deckel war verbraucht,
     * nicht die Moeglichkeiten.
     *
     * <p>Die eigentliche Begrenzung, die der Eigentuemer verlangt hat ("sehr stark begrenzt"), leistet inzwischen
     * etwas Besseres als eine Zahl: eine Zelle, an der ein Geruest scheitert, landet in {@code scaffoldFailed} und
     * wird in dieser Ebene nie wieder versucht. Damit ist ein Bauplan, den niemand geruesten kann, STRUKTURELL
     * gedeckelt -- die Kandidaten gehen aus, und der Bau urteilt. Es kann also nicht mehr passieren, dass ein
     * hoffnungsloser Fall beliebig viele Hilfsbloecke frisst, und genau davor sollte die Zahl schuetzen.
     *
     * <p>ZWEIUNDDREISSIG WAR WIEDER ZU WENIG, und das ist derselbe Fehler wie mit acht, nur eine Groessenordnung
     * hoeher. GEMESSEN, Lauf 45afdb47: "(32/32 in dieser Ebene)" bei T51610, Abbruch bei T51652 -- 42 Ticks
     * spaeter. Ebene 3 hat den Deckel exakt ausgeschoepft, und acht aufwaerts gerichtete Observer standen hinten
     * in der Schlange: fuer sie wurde NIE ein Geruest eroeffnet (Versuche=0, jeder einzelne). Sie waren nicht
     * unbaubar, sie kamen nicht mehr dran.
     *
     * <p>Dass es geht, steht im selben Lauf: von 32 Aufwaerts-Observern der Ebene wurden 24 gebaut, und
     * ZWANZIG davon mit einem Hilfsblock daneben -- 91,-58,74 ueber 92,-58,74, 94,-58,74 ueber 94,-58,75,
     * 105,-58,74 ueber 106,-58,74. Der Eigentuemer hatte genau das vorhergesagt: ohne Hilfsblock ist so ein
     * Observer von keiner einzigen Position aus setzbar.
     *
     * <p>Die Zahl ist deshalb keine Begrenzung mehr, sondern nur noch eine Reissleine. Begrenzt wird die Phase
     * strukturell: jede Zelle bekommt EINEN Versuch, gescheiterte landen in {@code scaffoldFailed} und werden in
     * dieser Ebene nie wieder angefasst. Damit ist die Zahl der Geruestbloecke von selbst durch die Zahl der
     * geparkten Zellen gedeckelt, und ein Bauplan, den niemand geruesten kann, laeuft in endlich vielen
     * Versuchen leer statt beliebig lange weiterzufressen.
     */
    private static final int MAX_SCAFFOLDS_PER_LAYER = 256;

    private long buildTick;
    /**
     * When the work set was last rebuilt from the whole schematic — see {@link #WORK_SET_REFILL_INTERVAL}.
     *
     * <p>Half of {@code MIN_VALUE}, not {@code MIN_VALUE}: the check is a subtraction, and {@code 0 - MIN_VALUE}
     * overflows to a negative number, which would silently mean "never due" on the first pass.
     */
    private long lastFullRecalcTick = Long.MIN_VALUE / 2;
    /** The cell we last pressed a placement for (+ its desired state) — checked on the next recalc to detect a
     *  placement that landed wrong. -1 = none pending. */
    private long lastPlacedCellHash = -1;
    private BlockPos lastPlacedCell;
    /** The tick {@link #lastPlacedCell} was armed. Bounds the settle window before the layer verdict, nothing else. */
    private long lastPlacedCellTick;
    /**
     * How long the layer verdict waits for a confirmed click to become visible in the world.
     *
     * <p>Zehn Ticks bei dreifacher Bench-Geschwindigkeit sind eine halbe Sekunde Spielzeit. Der eigentliche
     * Ausloeser ist der Zustand {@code lastPlacedCell}, nicht diese Zahl -- sie ist die Notbremse, damit ein
     * spurlos verpuffter Klick die Makro-Schleife nicht dauerhaft anhaelt. Keine Messung dahinter, und das steht
     * hier ausdruecklich, damit niemand sie fuer eine haelt.
     */
    private static final int LAYER_SETTLE_MAX_TICKS = 10;
    private BlockState lastPlacedDesired;
    /** Consecutive click-presses we fired at the SAME cell that never made it correct — closes the last loop shape
     *  the gate can't see: the ray passes, we click, but the placement silently never lands (server-side rejection,
     *  region protection). A correctly-placed cell drops off the target list after one press, so re-pressing the
     *  same cell N times can only mean it will never take. -1 = none pending. */
    private long repressCellHash = -1;
    private boolean repressCellTracked;
    private int repressCount;
    /** Coordinates paired with placementTargetLock's packed target key. */
    private BetterBlockPos committedPlaceTarget;
    /** Owns one target and its current click attempt. The target is immutable until a terminal transition, while
     *  support/face/rotation/slot are refreshed from the live sub-block pose every tick. Gate failure transitions to
     *  target-bound stance recovery; another cell cannot steal ownership and reset the recovery history. */
    private final PlacementTargetLock<Placement> placementTargetLock =
            new PlacementTargetLock<>(AIM_RECOVERY_TICKS, ROUTE_NO_CLOSER_TICKS, ROUTE_DEADLINE_TICKS);
    /** Build tick a cell most recently became correct — the one clock a stuck builder cannot stop. */
    private long lastCellCompletedTick;

    private final BuilderProgressWatch progressWatch = new BuilderProgressWatch(
            java.util.concurrent.TimeUnit.SECONDS.toNanos(5), java.util.concurrent.TimeUnit.SECONDS.toNanos(60), 2.0);
    private long confirmedProgressRevision;
    private final ConfirmedBuildActions<BlockState> progressActions = new ConfirmedBuildActions<>((state, expected) -> valid(state, expected, false));
    private PathExecutor progressExecutor;
    private Object progressRoute;
    private Long progressRouteTarget;
    private List<BetterBlockPos> progressRoutePositions = java.util.List.of();
    private int progressRoutePosition = -1;
    private BuilderProgressWatch.Phase progressPhase;
    private boolean progressHold;
    private Goal electedGoal;

    /**
     * The block the builder most recently had to take back out, so the reserved tool slot can hold the right tool.
     *
     * <p>{@link princeps.behavior.InventoryBehavior} keeps hotbar slot 0 stocked with the best tool in the bag, but
     * it asked that question about STONE and nothing else -- so the answer was always a pickaxe. On a map-art
     * resume the demolition work is WOOL, against which a diamond pickaxe is no better than a fist and shears are
     * five times faster than either. Replacing several hundred mismatched pixels with the wrong tool is minutes of
     * standing still per column, and it is invisible from the outside: the bot looks like it is working.
     *
     * <p>Recorded rather than predicted, and recorded where the break is DECIDED rather than where it finishes, so
     * the swap is on its way while the first block of a new material is still coming down. Null until the builder
     * has actually had to break something -- an ordinary build that only places never moves the pickaxe.
     */
    private volatile BlockState blockWorthATool;
    /** Build tick the "no hotbar material" census last printed; assemble runs more than once per tick. */
    private long lastMissingCensusTick = -1L;

    /**
     * Tick of the last "Missing materials for at least" line, and what it said.
     *
     * <p>Both, because two different things must be suppressed. The tick stops it repeating while a shortage
     * lasts; the text stops it repeating when the shortage is unchanged but the clock has run out. A shortage
     * that does not move is one piece of news, however long it goes on.
     */
    private long lastMissingReportTick = -1L;

    private String lastMissingReportText = "";
    /**
     * Why the placement scan walked past a cell it could otherwise have built. Index order matches
     * {@link #SKIP_REASONS}. Pure reporting.
     *
     * <p>The scan has nine ways to skip a cell and not one of them leaves a line, so a cell that is never built and
     * never deferred is indistinguishable from a cell that was never looked at. That cost two refuted diagnoses in one
     * night (A28 window, A29 hotbar): both explained an absence of events that the events themselves could not
     * arbitrate. This is the missing half of DEFER -- the cells the builder passed over in silence.
     */
    private final int[] placementSkips = new int[7];
    private static final String[] SKIP_REASONS =
            {"deferred", "would block own path", "would seal a hanging neighbour", "outranked", "no placement derivable",
             "at head height in own column", "would seal own head-height exit"};
    /**
     * How many cells the head-height rule now ADMITS that it used to discard -- the size of the channel opened at
     * {@code searchForPlacables}, counted rather than assumed.
     *
     * <p>It is not a skip and deliberately not in {@link #placementSkips}: that array answers "why was this cell
     * walked past", and this answers the opposite. It shares the census line because the two only mean anything
     * beside each other -- a large admitted count with an unchanged placed count says the channel is open and
     * empty, which is a different failure from the channel never opening.
     */
    private long headHeightCellsAdmitted;
    private long lastSkipCensusTick = -1L;
    /** Own clock for the world-change and cell-list censuses, so neither can be silenced by the other's guard. */
    private long lastCensusTick = -1L;

    // ==========================================================================================================
    // AKTIV UND PARK -- die beiden Listen der Ziel-Spezifikation, und der Waechter, der die einzige Tuer aus PARK
    // heraus ist.
    //
    // Heute existiert davon nichts: es gibt EINE Menge (incorrectPositions), aus der eine Zelle je nach Grund von
    // einem Verdikt, einer Backoff-Frist oder der Ruhestandsmenge herausgefiltert wird -- drei Mechanismen fuer
    // einen Zustand, und zwei davon haben eine Uhr in sich. Die Spezifikation verlangt das Gegenteil: zwei
    // disjunkte Listen, PARK persistent, und geparkte Zellen pruefen sich NIE von selbst.
    //
    // DER WAECHTER IST BILLIGER ALS ER KLINGT, und das ist der Grund, warum er ohne Rueckwaerts-Index auskommt.
    // Die Freigabebedingung lautet "an einer NACHBARZELLE der geparkten Zelle hat sich die Flaechensituation
    // geaendert". Eine Platzierung veraendert genau eine Zelle P. Eine geparkte Zelle C hat P also genau dann als
    // veraenderten Nachbarn, wenn C ein Nachbar von P ist. Es genuegt daher, nach jeder Platzierung die SECHS
    // Nachbarn von P nachzuschlagen -- nicht die ganze PARK-Liste zu durchlaufen, wie der Pseudocode der
    // Spezifikation es formuliert. Bei 12000 geparkten Zellen ist das der Unterschied zwischen sechs
    // Hash-Zugriffen und zweiundsiebzigtausend Weltabfragen pro gesetztem Block.
    //
    // GEWECKTE ZELLEN KOMMEN AN DEN KOPF, nicht ans Ende. Owner-Entscheidung, und sie hat einen gemessenen Grund:
    // die 448 nach unten zeigenden Kolben in Ebene y=-57 sind nur ueber eine Diagonal-Standposition setzbar, die
    // EINEN festen waagerechten Nachbarn zum Anklicken und EINEN freien senkrechten zum Stehen braucht. Diese
    // Gelegenheit schliesst sich, sobald der letzte senkrechte Nachbar gesetzt ist. Eine Zelle, die hinter alle
    // uebrigen Nachbarn einsortiert wird, verpasst sie zuverlaessig.
    //
    // Vorerst im SCHATTENBETRIEB: beide Listen werden gefuehrt und protokolliert, entscheiden aber noch nichts.
    // So laesst sich vor der Umstellung pruefen, ob der Waechter dieselben Zellen freigibt, die heute die
    // 200-Tick-Nachfuellung zurueckholt -- und eine Luecke faellt VOR der Umstellung auf, nicht danach.

    /** Warum eine Zelle geparkt ist. Genau die drei Gruende der Spezifikation, keine weiteren. */
    private enum ParkReason {
        /** A: kein Nachbar bietet eine Flaeche zum Anklicken. */
        NO_FACE,
        /** B: Flaeche vorhanden, aber keine Standposition erzeugt den gewollten Block. */
        NO_STANCE,
        /** C: Standposition vorhanden, aber in keiner Bahn erreichbar. */
        UNREACHABLE,
    }

    private static final class ParkedCell {
        final BetterBlockPos pos;
        final ParkReason reason;
        final long parkedAtTick;
        /** Die Flaechenlage im Moment des Parkens -- der Waechter vergleicht gegen sie, er wartet nicht. */
        final long faceMaskWhenParked;

        ParkedCell(BetterBlockPos pos, ParkReason reason, long tick, long faceMask) {
            this.pos = pos;
            this.reason = reason;
            this.parkedAtTick = tick;
            this.faceMaskWhenParked = faceMask;
        }
    }

    /**
     * AKTIV.
     *
     * <p>KEINE Einfuege-Reihenfolge, und das ist eine ausdrueckliche Entscheidung des Eigentuemers vom 06.08.:
     * "Kopf der Liste oder oben auf der Liste ist einfach immer das mit der geringsten Distanz. Das soll auch in
     * Zukunft so bleiben. Bedeutet, wenn Sachen aus der Liste geholt werden [...] werden die auch nicht ganz
     * hinten angereiht, sondern die Liste ist immer nach Distanz sortiert."
     *
     * <p>Damit loest sich die Frage "Kopf oder Ende" auf, an der sich der Entwurf zwei Runden lang aufgehalten
     * hat: es gibt keinen Kopf, den man belegen koennte. Die Menge ist ungeordnet, die Reihenfolge entsteht bei
     * der Auswahl aus dem Abstand zum Bot -- genau das, was assemble() ohnehin tut.
     *
     * <p>Und es traegt den Fall, um den es bei der Kopf-Frage ging: eine Zelle wird geweckt, weil NEBEN ihr
     * gerade ein Block gelandet ist, und der Bot steht dort. Sie ist im Moment ihrer Freigabe also die
     * naechstgelegene, ohne dass jemand sie vordraengeln muss.
     */
    private final java.util.HashSet<BetterBlockPos> activeCells = new java.util.HashSet<>();
    /** PARK. Persistent, wird nie neu aufgebaut; Neuzugaenge hinten. */
    private final java.util.LinkedHashMap<Long, ParkedCell> parkedCells = new java.util.LinkedHashMap<>();
    /**
     * Whether this job is an EXCAVATION -- clearing a volume to air -- in which case nothing is ever parked.
     *
     * <p>Parking exists for BUILDING, where a cell can be genuinely impossible until a neighbour exists and the
     * order matters. Digging has neither property: every block goes, the order is free, and a cell that cannot be
     * reached right now becomes reachable the moment its neighbour falls. Holding it back buys nothing and costs
     * the deadlock the owner found -- once everything still open is parked, no cell can finish, nothing wakes
     * anything, and the window may not step down past a park. The bench never showed it because a pristine cube
     * parks nothing at all; the owner's ruling is to dig exactly as the bench does.
     */
    private boolean excavating;
    /** Build tick of the last dry-window park release, so the retry happens once a tick and not per recursion. */
    private long lastParkSweepTick = Long.MIN_VALUE;
    private long cellsParked;
    private long cellsWokenByWatchman;

    private void parkCell(BetterBlockPos pos, ParkReason reason) {
        if (excavating) {
            return; // digging never defers a cell -- see the excavating field for why
        }
        long key = positionKey(pos);
        if (parkedCells.containsKey(key)) {
            return;   // schon geparkt: der Grund von damals bleibt stehen, sonst waere die Liste ein Protokoll
        }
        activeCells.remove(pos);
        // UND AUS DEM ARBEITSSATZ. Die Invariante der Spezifikation lautet "Eine Zelle ist zu jedem Zeitpunkt in
        // genau EINER Liste -- AKTIV oder PARK -- oder gesetzt", und incorrectPositions ist die Liste, die der
        // Rest dieses Prozesses wirklich liest.
        //
        // GEMESSEN, basalt-Lauf c49b7484: ohne diese Zeile stand der Bau ab Tick 9616 bei `layer=2 work=84
        // retired=84` und tat 11 000 Ticks lang nichts. 84 geparkte Zellen blieben im Arbeitssatz, also lieferte
        // recalc() ewig true, also lief der ganze Ebenen-Abschlusszweig NIE -- weder P0 noch P6a noch die
        // Verifikation. Ein Bau, der weder vorankommt noch abbricht, ist der schlechteste aller Zustaende: er
        // sieht von aussen aus wie Arbeit.
        //
        // Der Rueckweg bleibt genau einer: der Waechter nimmt die Zelle aus parkedCells, danach traegt
        // recalcNearby sie beim naechsten Vorbeikommen wieder ein, weil isCellParked dann false ist.
        if (incorrectPositions != null) {
            incorrectPositions.remove(pos);
        }
        parkedCells.put(key, new ParkedCell(pos, reason, buildTick, clickableFaceMask(pos.x, pos.y, pos.z)));
        cellsParked++;
        BuildTrace.cell(buildTick, "PARK", pos.x, pos.y, pos.z, "reason=" + reason);
    }

    /** The active window has run dry; release its parks for the existing recalculation path. */
    private void releaseParkedCellsForRetry() {
        for (long key : parkedCells.keySet()) {
            forgetCellVerdict(key);
        }
        parkedCells.clear();
    }

    /** A released cell must not keep a negative memo or a cached failed stance from its old park. */
    private void forgetCellVerdict(long key) {
        cellVerdicts.remove(key);
        orientedGoalCache.remove(key);
    }

    /**
     * Der Waechter. Laeuft nach JEDER erfolgreichen Platzierung und weckt die geparkten Nachbarn der Zelle, die
     * sich gerade veraendert hat -- in beide Richtungen, denn "weggefallen" zaehlt genauso wie "neu entstanden".
     */
    private void watchmanAfterChangeAt(int x, int y, int z) {
        if (parkedCells.isEmpty()) {
            return;
        }
        // GRUND C IST NICHT LOKAL, und das ist der offene Punkt, den die Spezifikation selbst benennt: "Eine als
        // unerreichbar geparkte Zelle wird nur geweckt, wenn sich an ihrem eigenen Nachbarn etwas aendert. Ein Weg,
        // der sich 15 Bloecke entfernt oeffnet, weckt sie nicht -- obwohl genau das ihr Problem war."
        //
        // GEMESSEN, ringbig-Lauf 20260807-000436, der erste Lauf ohne die alten Uhren: 18 Zellen geparkt, ALLE
        // Grund C, keine einzige A oder B -- und der Waechter gab von 21 Parks nur 3 frei. Der Bot stand am Ende
        // 2600 Ticks ohne Ziel, 29 Glaszellen fehlten. Der 40-640-Tick-Backoff hatte das bis dahin verdeckt: er
        // brachte die Zellen aus dem falschen Grund zurueck, aber er brachte sie zurueck.
        //
        // ENTSCHEIDUNG: A und B bleiben an der Nachbarflaeche -- sie sind Aussagen ueber die UMGEBUNG der Zelle.
        // C ist eine Aussage ueber die WELT: erreichbar oder nicht. Und das Einzige, was die Erreichbarkeit
        // veraendert, ist ein gesetzter Block -- egal wo. Also gibt jede fertige Zelle alle C-Parks frei.
        //
        // Das ist keine Uhr: der Ausloeser ist ein Ereignis, kein Zeitablauf, und ohne Fortschritt passiert nichts.
        // Bezahlbar ist es erst seit dem Knotenbudget -- ein erneuter Versuch kostet jetzt 5000 bzw. 50000 Knoten
        // statt der vollen zwei Sekunden Wanduhr, die eine gescheiterte Suche frueher immer gekostet hat.
        if (!parkedCells.isEmpty()) {
            java.util.Iterator<java.util.Map.Entry<Long, ParkedCell>> it = parkedCells.entrySet().iterator();
            int released = 0;
            while (it.hasNext()) {
                ParkedCell parked = it.next().getValue();
                if (parked.reason != ParkReason.UNREACHABLE) {
                    continue;
                }
                it.remove();
                forgetCellVerdict(positionKey(parked.pos));
                activeCells.add(parked.pos);
                released++;
                cellsWokenByWatchman++;
            }
            if (released > 0) {
                BuildTrace.cell(buildTick, "WAKE-C", x, y, z, "released=" + released + " unreachable cell(s)");
            }
        }
        for (Direction d : SIX_NEIGHBOURS) {
            int nx = x + d.getStepX();
            int ny = y + d.getStepY();
            int nz = z + d.getStepZ();
            long key = positionKey(nx, ny, nz);
            ParkedCell parked = parkedCells.get(key);
            if (parked == null) {
                continue;
            }
            long now = clickableFaceMask(nx, ny, nz);
            if (now == parked.faceMaskWhenParked && parked.reason != ParkReason.NO_FACE) {
                continue;   // the geometry verdict still has the same face set
            }
            // Missing support can become usable without changing the coarse face mask
            // (for example a non-supporting neighbour replaced by a supporting block).
            parkedCells.remove(key);
            forgetCellVerdict(key);
            // Einfach zurueck in die Menge. Wo sie landet, entscheidet der Abstand bei der naechsten Auswahl --
            // siehe activeCells. Kein Vordraengeln, keine Sonderbehandlung.
            activeCells.add(parked.pos);
            cellsWokenByWatchman++;
            BuildTrace.cell(buildTick, "WAKE", nx, ny, nz,
                    "because=" + x + "," + y + "," + z + " changed, was=" + parked.reason
                            + " parkedFor=" + (buildTick - parked.parkedAtTick) + "t");
        }
        // NO_STANCE is not only a property of the clicked face. In a one-layer picture, every newly placed pixel can
        // become the floor for a stance up to three blocks from another pixel. The generic six-neighbour watcher does
        // not see that change, so a row-build cell can stay parked forever with an obsolete stance verdict even while
        // the walking surface grows around it. This is still event-driven (one real world change), not a retry timer.
        if (buildInRows && !parkedCells.isEmpty()) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    long key = positionKey(x + dx, y, z + dz);
                    ParkedCell parked = parkedCells.get(key);
                    if (parked == null || parked.reason != ParkReason.NO_STANCE) {
                        continue;
                    }
                    parkedCells.remove(key);
                    // The face mask may be identical even though a new floor/stance appeared. Without removing the
                    // paired memo, searchForPlacables immediately skips the just-woken cell as the old verdict.
                    forgetCellVerdict(key);
                    activeCells.add(parked.pos);
                    cellsWokenByWatchman++;
                    BuildTrace.cell(buildTick, "WAKE-ROW-STANCE", parked.pos.x, parked.pos.y, parked.pos.z,
                            "because=" + x + "," + y + "," + z + " created a possible stance, parkedFor="
                                    + (buildTick - parked.parkedAtTick) + "t");
                }
            }
        }
    }

    /**
     * Parked cells whose row lies inside the band of the layer being judged -- exactly what P6a asks about.
     *
     * <p>Nicht einfach {@code parkedCells}: die Tuer-Sonderklausel der Ebenenmaske laesst top-down eine Reihe der
     * NAECHSTEN Ebene zu, damit eine Tuer-Oberhaelfte ihre Unterhaelfte mitbringen kann. Eine dort geparkte Zelle
     * gehoert nicht zum Urteil ueber diese Ebene, und sie wuerde es sonst mit LAYER_UNBUILDABLE beenden.
     */
    private List<BetterBlockPos> parkedInBand() {
        List<BetterBlockPos> out = new ArrayList<>();
        if (parkedCells.isEmpty() || origin == null) {
            return out;
        }
        for (ParkedCell parked : parkedCells.values()) {
            int ly = parked.pos.y - origin.getY();
            if (ly >= bandMinYLocal && ly <= bandMaxYLocal) {
                out.add(parked.pos);
            }
        }
        return out;
    }

    /**
     * The P6a report: how many cells are parked for each of the three reasons, and which ones.
     *
     * <p>Named, nicht gerundet. Ein basalt-Lauf hat 245 Zellen ueber drei Ebenen verloren, und 175 davon kamen in
     * KEINER Logzeile vor -- das ist der Zustand, den dieser Report beendet. Die Gruende sind die drei der
     * Spezifikation und sagen jeweils etwas anderes ueber die Welt: A heisst "nichts zum Anklicken", B heisst
     * "Flaeche da, aber keine Stellung erzeugt den gewollten Block", C heisst "erreichbar war es nicht".
     */
    private List<String> parkReport(List<BetterBlockPos> cells, BuilderCalculationContext bcc) {
        int a = 0;
        int b = 0;
        int c = 0;
        for (BetterBlockPos pos : cells) {
            ParkedCell parked = parkedCells.get(positionKey(pos));
            if (parked == null) {
                continue;
            }
            switch (parked.reason) {
                case NO_FACE: a++; break;
                case NO_STANCE: b++; break;
                default: c++; break;
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(a + " with no face to click against (A), " + b + " with a face but no working stance (B), "
                + c + " with a stance but no route (C)");
        int named = 0;
        for (BetterBlockPos pos : cells) {
            if (named >= 12) {
                lines.add("... and " + (cells.size() - named) + " more");
                break;
            }
            ParkedCell parked = parkedCells.get(positionKey(pos));
            if (parked == null) {
                continue;
            }
            BlockState wanted = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos));
            lines.add("  " + pos.x + "," + pos.y + "," + pos.z + "  " + parked.reason
                    + "  wanted=" + (wanted == null ? "?" : blockName(wanted))
                    + "  parked for " + (buildTick - parked.parkedAtTick) + " tick(s)");
            named++;
        }
        return lines;
    }

    // ==========================================================================================================
    // RE-PLANS PER CELL — the owner's own worry, turned into a number.
    //
    // His words: "Hier muessen wir aber sichergehen, dass er nicht aus irgendeinem Grund immer denkt, dass die
    // Welt sich veraendert haette [...] und er auch von daher in einer Endlosschleife der Neuplanungen landet.
    // Weil der absolute Normalfall in 99,99 % der Faelle sollte natuerlich sein, geplant und ausgefuehrt und
    // nichts aendert sich unterwegs."
    //
    // That is a testable claim, and it has never been tested. It is also not hypothetical: run f159bc1d planned a
    // bridging move every tick and refused it every tick -- 921 two-node paths against 922 no-path ticks, 882
    // refusal gaps of exactly two ticks, the bot motionless for 1920 ticks with zero placements. Nothing counted
    // it; it had to be reconstructed afterwards from a log.
    //
    // Counted on the transition into calculating, so one search is one count however many ticks it runs.
    private BetterBlockPos replanWatchCell;
    private int replansForCurrentCell;
    private int worstReplansForOneCell;
    private BetterBlockPos worstReplanCell;
    private boolean sawCalculationInProgress;

    /** One count per STARTED search while the elected cell has not changed. */
    private void trackReplans() {
        boolean calculating = princeps.getPathingBehavior().getInProgress().isPresent();
        if (calculating && !sawCalculationInProgress) {
            if (electedCell != null && electedCell.equals(replanWatchCell)) {
                replansForCurrentCell++;
                if (replansForCurrentCell > worstReplansForOneCell) {
                    worstReplansForOneCell = replansForCurrentCell;
                    worstReplanCell = electedCell;
                }
                // Loud at 3, then rarely: the normal case is ONE plan per cell, so a third is already a finding,
                // and a cell that is being re-planned fifty times should not be able to drown the log it explains.
                if (replansForCurrentCell == 3 || replansForCurrentCell % 25 == 0) {
                    logMechanic("Cell " + electedCell.x + "," + electedCell.y + "," + electedCell.z
                            + " has been re-planned " + replansForCurrentCell + " times without being placed;"
                            + " one plan per cell is the normal case, so this is a defect and not a wait");
                    BuildTrace.cell(buildTick, "REPLAN", electedCell.x, electedCell.y, electedCell.z,
                            "count=" + replansForCurrentCell);
                }
            } else {
                replanWatchCell = electedCell;
                replansForCurrentCell = 1;
            }
        }
        sawCalculationInProgress = calculating;
    }

    // ==========================================================================================================
    // HOW THIS BUILD ENDED. See IBuilderProcess.Ending for why it is a value and not a log line.
    //
    // Deliberately NOT cleared by onLostControl: the client polls AFTER the process has gone inactive, so an
    // ending wiped during teardown is an ending nobody can read. It is cleared when the next build starts, which
    // is the only moment at which the previous answer stops being the truth.
    private Ending ending = Ending.RUNNING;
    private final List<String> endingReport = new ArrayList<>();

    /**
     * Record how this build ended, say it once in the chat, and write it to the trace.
     *
     * <p>The first call wins. A build that has already ended for a named reason must not have that reason
     * overwritten by the teardown that follows it -- otherwise every failure would end up reported as
     * "cancelled", which is exactly what happens today when the client observes an inactive process and guesses.
     */
    private void finishWith(Ending how, String headline, List<String> detail) {
        if (ending != Ending.RUNNING) {
            return;
        }
        ending = how;
        endingReport.clear();
        endingReport.add(headline);
        if (detail != null) {
            endingReport.addAll(detail);
        }
        logDirect(headline);
        for (String line : endingReport.subList(1, endingReport.size())) {
            logDirect("  " + line);
        }
        BuildTrace.cell(buildTick, "ENDING", origin == null ? 0 : origin.getX(),
                origin == null ? 0 : origin.getY(), origin == null ? 0 : origin.getZ(),
                how + " " + headline);
    }

    @Override
    public Ending ending() {
        return ending;
    }

    @Override
    public List<String> endingReport() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(endingReport));
    }

    // ==========================================================================================================
    // ABBRUCH -- wie ein benannter Fehlschlag aus einem Tick herauskommt, ohne den Tick in der Mitte zu zerreissen.
    //
    // Die zwei harten Stopps der Spezifikation (P5 "Platzierung fehlgeschlagen" und P6a/P6b "diese Ebene ist nicht
    // fertigzustellen") entstehen tief in onTick: im Platzierungsbeobachter, im Ebenen-Aufstiegszweig. Von dort aus
    // sofort zu beenden hiesse, onLostControl() mitten in einer Methode zu rufen, die danach genau die Felder
    // weiterliest, die onLostControl() auf null setzt -- schematic, incorrectPositions, origin. Jeder dieser
    // Zugriffe ist eine NullPointerException, die auf den ersten echten Abbruch wartet, und der erste echte
    // Abbruch ist genau der Moment, in dem niemand einen zweiten, unbeteiligten Fehler obendrauf braucht.
    //
    // Ein Abbruch wird deshalb ANGEMELDET, nicht ausgefuehrt. abortBuild() merkt sich den Grund und kehrt zurueck;
    // der naechste Pruefpunkt in onTick fuehrt ihn aus. Die Pruefpunkte liegen so, dass zwischen Anmeldung und
    // Stopp keine Platzierung, kein Abbau und keine Bewegung mehr stattfinden kann -- das ist es, was "globaler
    // Abbruch, kein Retry" bedeuten muss, um etwas wert zu sein.
    private Ending abortPending = Ending.RUNNING;
    private String abortPendingHeadline;
    private List<String> abortPendingDetail;

    /**
     * Ask for a global abort with a named reason. Safe to call from anywhere inside a tick; nothing happens until
     * the next checkpoint.
     *
     * <p>The first request wins, for the same reason {@link #finishWith} lets the first ending win: a failure
     * usually knocks two more things over on its way out, and the third report is never the interesting one.
     */
    private void abortBuild(Ending how, String headline, List<String> detail) {
        if (abortPending != Ending.RUNNING || ending != Ending.RUNNING) {
            return;
        }
        abortPending = how;
        supportRepair = null;
        abortPendingHeadline = headline;
        abortPendingDetail = detail == null ? java.util.Collections.emptyList() : new ArrayList<>(detail);
        BuildTrace.cell(buildTick, "ABORT-REQUESTED", origin == null ? 0 : origin.getX(),
                origin == null ? 0 : origin.getY(), origin == null ? 0 : origin.getZ(),
                how + " " + headline);
    }

    /**
     * Perform a requested abort. Returns true when the build has just been ended, in which case the caller must
     * {@code return null} from onTick at once -- the same return the COMPLETED path uses, and the reason this is a
     * boolean rather than a PathingCommand: null is already the established "this process is finished" answer, so
     * a method returning a command could not distinguish "no abort pending" from "aborted".
     */
    private boolean finishAbortedBuild() {
        if (abortPending == Ending.RUNNING) {
            return false;
        }
        Ending how = abortPending;
        String headline = abortPendingHeadline;
        List<String> detail = abortPendingDetail;
        abortPending = Ending.RUNNING;
        abortPendingHeadline = null;
        abortPendingDetail = null;
        // finishWith BEFORE onLostControl: the teardown clears the working set the report was built from, and an
        // ending recorded afterwards would be the teardown's ending (CANCELLED), not the one that was requested.
        finishWith(how, headline, detail);
        if (Princeps.settings().notificationOnBuildFinished.value) {
            logNotification(headline, true);
        }
        onLostControl();
        return true;
    }
    /** Cells given up on for this sweep. Excluded from the work set so they cannot hold a layer hostage. */
    /** Cached because Direction.values() clones its array per call and this runs on every completion. */
    private static final Direction[] SIX_NEIGHBOURS = Direction.values();
    /** Wie viele Zellen der Waechter geweckt hat. Ersetzt cellsRecalled -- dieselbe Aussage, anderer Mechanismus. */
    /** Placement clicks sent, blocks that landed correctly, and blocks broken. The first-try rate is
     *  landedRight/clicksSent, and it is the number the owner judges this builder by: a build that breaks twice for
     *  every cell it finishes is not building, it is correcting itself. */
    private long clicksSent;
    private long landedRight;
    private long landedWrong;
    private long blocksBroken;
    /** Consecutive failed path calculations. Cleared by any completed cell. @see #reportIfWalledIn */
    private int consecutivePathFailures;
    /** How many consecutive path failures between one 'Walled in?' report and the next. */
    private static final int WALLED_IN_REPORT_EVERY = 500;
    /** How often a committed placement target was abandoned because no stance could place it. @see #walksStarted */
    private long stancelessAbandons;
    /** Interaction give-ups per cell. Its own map because cellDeferralCounts is cleared whenever a cell is briefly
     *  observed valid, which is precisely what a toggling door does on every click. */
    private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap interactGiveUps;
    /** Consecutive ticks spent pausing for a hotbar swap that keeps being refused. */
    private int hotbarFetchRefusedTicks;
    /**
     * How many walks to a planned placement stance were started, and how many of those ended in the cell being built.
     *
     * <p>The number that should have existed before any of this. Five separate attempts at making the builder cleverer
     * about which cell to walk to were reasoned out, implemented, and measured worse -- and the reason they could not be
     * judged on the way in is that nothing counted whether a walk had been worth taking. Log-line frequencies are a
     * proxy for it, and a bad one: 1181 "recovering placement" lines say a lot of walking happened and nothing at all
     * about how much of it was useful.
     *
     * <p>Started when a stance is planned; credited when the cell that stance was for becomes correct. The ratio is the
     * direct measure of the wasted travel the owner watched, so it belongs in the verdict rather than in a log grep.
     */
    private long walksStarted;
    private long walksEndedInPlacement;
    /** Packed key of the cell the current walk is for, or -1 when no walk is in flight. */
    private long walkTargetKey = -1L;
    /** Why the most recent placement simulation returned nothing, in words. Null when it succeeded. */
    private String lastSimulationRefusal;
    /** Last tick's visible rotation, for deciding whether the aim has come to rest. */
    private float prevAimYaw;
    private float prevAimPitch;
    private boolean prevAimValid;
    /** How far the aim moved between the previous tick and this one, in degrees. */
    private float aimMovementDegrees = Float.MAX_VALUE;
    /** Ticks spent waiting for the aim to settle on the current placement, so the wait can never be unbounded. */
    private int aimSettleWaitTicks;
    private BetterBlockPos plannedPlacementStance;
    /** One existing platform, one permanent target; retained while the sneak step crosses the cell boundary. */
    private PlatformTraverseApproach platformTraverseApproach;

    private record PlatformTraverseApproach(Object world, Object player, ISchematic model, Vec3i origin,
                                             BetterBlockPos from, BetterBlockPos target,
                                             BlockState support, BlockState wanted, Goal goal) { }
    /** Travels with the actual immutable path, even while a newer command is cancelling that path. */
    private static final class PlatformTraverseGoal extends GoalBlock {
        private PlatformTraverseGoal(BlockPos destination) { super(destination); }
    }
    private int placementCenteringTicks;
    private int placementCenterSettleTicks;
    private Placement pendingPlacementRequest;
    private Item pendingPlacementItem;
    private long pendingPlacementRequestSerial;
    /** Was the place cooldown still running when the request went out? Then no acknowledgement is expected and its
     *  absence says nothing -- see the S8 seam and {@link BlockPlaceHelper#isThrottled()}. */
    private boolean pendingPlacementRequestThrottled;
    private long observedBlockClickSerial;
    private long unacknowledgedPlacementCellKey = Long.MIN_VALUE;
    private long unacknowledgedPlacementStanceKey = Long.MIN_VALUE;
    private int unacknowledgedPlacementRequests;
    private long ordinaryGateBlockedCellKey = Long.MIN_VALUE;
    private int ordinaryGateBlockedTicks;
    /** Per-cell count of how many times a full cube has been yielded from its current stance. At
     *  {@link #ORDINARY_MAX_YIELDS} the cube escalates to stance recovery instead of yielding again. */
    private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap ordinaryYieldCounts;
    private BetterBlockPos observedBreakTarget;
    private Block observedBreakBlock;
    private int breakNoProgressTicks;
    /** Ticks with neither an accepted build action nor active travel toward an unsatisfied path goal. This can
     *  trigger stance recovery, but never removes an unresolved schematic cell. */
    private int noProgressTicks;
    /**
     * Refusals of an unlicensed helper block in the current episode — see {@link #scaffoldIsLicensedAt}.
     *
     * <p>An EPISODE budget, not a per-cell one, and that is deliberate. Per cell leaves an alternation hole: the route
     * wants a helper block at P1 and is refused, the router returns a route wanting P2 and is refused, then P1 again
     * with a fresh counter — forever, because nothing ever reaches the lapse.
     */
    private int scaffoldVetoTicks;
    /** Tick of the last refusal; an episode is over once a tick passes without one. */
    private long scaffoldVetoLastTick = -1L;
    /** Where the last refusal happened, for the trace line only. */
    private long scaffoldVetoCellKey = -1L;
    /** Whether {@link #SCAFFOLD_VETO_UNSTICK_AT} has already been spent in this episode. See that constant. */
    private boolean scaffoldVetoUnstickTried;
    /** Nanoseconds this tick has spent deriving standing positions. See {@link #STANCE_SEARCH_BUDGET_NANOS}. */
    private long stanceSearchNanos;
    /**
     * The ONE cell currently being built, latched across ticks. See the election in {@code assemble}.
     *
     * <p>Exists because "there is always only one target" was true within a tick and false across them: re-electing by
     * distance from the bot's feet made the winner flip 353 times in 1921 ticks between two cells whose goals pull in
     * opposite directions. Cleared by {@code noteCellCompleted}, by leaving the work set, by deferral, or by losing its
     * proven stance -- never by the bot having moved.
     */
    private BetterBlockPos electedCell;
    /** Removal work owns navigation too, but never asks the placement/hotbar oracle to place AIR. */
    private boolean electedBreak;
    /**
     * TWO PASSES, and this is which one we are in: false = walk only, true = building permitted.
     *
     * <p>The owner's rule, replacing a price with an order: "Erst ein Durchgang ohne Bauerlaubnis. Und dann sollte
     * wirklich dabei nichts rauskommen, also keine Moeglichkeit bestehen, ohne bauen dorthin zu kommen -- in dem Fall
     * wird eine zweite Suche mit Bauerlaubnis gestartet."
     *
     * <p>WHY AN ORDER AND NOT A PRICE. HELPER_BLOCK_COST prices one helper block at twenty blocks of walking, so A*
     * weighs the two against each other in ONE search and builds whenever the detour exceeds twenty -- even where
     * walking was perfectly possible. An order asks a different question: is there ANY walkable route, however long?
     * Only when there is none does building become permissible. Jumping and long detours are explicitly preferred over
     * a single block.
     *
     * <p>It costs almost nothing: the second pass only happens for a cell whose first pass found no route at all, and
     * for such a cell the first search is the cheap one -- it fails fast rather than expanding a large frontier.
     *
     * <p>Reset to false on every new election and on every completed cell, so permission is never inherited from the
     * cell before. Set to true only by a genuinely failed calculation.
     */
    private boolean scaffoldPassAllowed;
    /** How many ticks a DEADGOAL run may go unmentioned before it writes a repeat line. See {@link #traceDeadGoal}. */
    private static final int DEADGOAL_REPEAT_TICKS = 200;
    /** Feet cell of the DEADGOAL run currently open, or {@link Long#MIN_VALUE} when none is. */
    private long deadGoalRunFeet = Long.MIN_VALUE;
    private int deadGoalRunX;
    private int deadGoalRunY;
    private int deadGoalRunZ;
    private long deadGoalRunStart;
    private long deadGoalLastEmit;
    /** Ticks in the open run, and how many of them a line has already reported. The gap between the two is what
     *  {@link #flushDeadGoalRun} has to write out, and keeping both is what makes the collapse lossless. */
    private int deadGoalRunCount;
    private int deadGoalEmittedCount;
    /**
     * Wie oft je Blockart und Ebene schon eine volle Stance-Aufschluesselung geschrieben wurde.
     *
     * <p>Vorher ein GLOBALER Zaehler mit Deckel 5. Gemessen am 02.08.2026 an einem Lauf ueber acht Ebenen und
     * 270166 Ticks: die Aufschluesselung steht **dreimal** in 171 MB, waehrend 4119 Zeilen
     * "stance breakdown suppressed after 5" tragen -- und alle drei stammen aus den Ticks 7179 bis 7181,
     * derselben Blockart, derselben Ebene. Dasselbe gilt fuer den Nachbar-Zensus: fuenf Vorkommen, alle mit
     * unblockers-now=0, auf die sich Iteration 19 gestuetzt hat.
     *
     * <p>Die eine Messung, die erklaeren koennte, WARUM kein Standplatz funktioniert, war damit nach den ersten
     * Sekunden eines Zwei-Stunden-Laufs abgeschaltet. Der Deckel galt danach je (Blockart, Ebene): der Aufwand
     * bleibt beschraenkt -- es sind einige Dutzend statt Tausende --, aber jede Familie in jeder Ebene kommt
     * mindestens einmal zu Wort.
     *
     * <p><b>DAS WAR IMMER NOCH ZU GROB, und zwar genau an der Frage, die zaehlt.</b> Gemessen an Lauf c184cda6:
     * Ebene y=-58 endete mit 88 Truhen, von denen <b>81 gebaut</b> und 7 offen waren, und mit 44 Hoppern, von denen
     * <b>40 gebaut</b> und 4 offen waren. Die Ausrichtung unterscheidet sie nicht -- {@code hopper[facing=south]}
     * steht 9/11, {@code chest[facing=west]} 15/16, jede Richtung hat Erfolge UND Fehlschlaege. Der Unterschied kann
     * also nur die oertliche Lage im Moment des Versuchs sein, und das ist genau der Inhalt dieser Aufschluesselung.
     * Bei einem Deckel von 3 je (Blockart, Ebene) schwiegen 85 der 88 Truhen. Der Eigentuemer hat im Spiel elf
     * fehlende Zellen blockgenau benannt, und das Log konnte zu keiner davon sagen, woran sie gescheitert ist.
     *
     * <p>Der Deckel gilt deshalb jetzt <b>je ZELLE</b>, und Wiederholungen werden gezaehlt statt verschwiegen: die
     * erste Fehlschlagsmeldung einer Zelle traegt die volle Aufschluesselung, jede
     * {@link #STANCE_SUMMARY_REPEAT_EVERY}-te danach ebenfalls (damit sichtbar wird, wie sich die Lage aendert),
     * und dazwischen steht eine Zeile MIT Wiederholungszahl und dem Tick der letzten vollen Ausgabe. Damit erklaert
     * sich jede scheiternde Zelle mindestens einmal selbst.
     *
     * <p>{@link #MAX_STANCE_SUMMARIES_TOTAL} bleibt als Notbremse, weil die Aufschluesselung einige Tausend
     * Raycasts kostet -- aber gross genug, dass sie im Normalfall nicht beisst, und wenn sie beisst, sagt die Zeile
     * es ausdruecklich. Ein Deckel, der stillschweigend zuschlaegt, ist der Fehler, der hier zweimal gemacht wurde.
     */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap stanceFailuresByCell =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap stanceSummaryLastFullTick =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private int stanceSummariesWritten;
    private static final int STANCE_SUMMARY_REPEAT_EVERY = 4;
    private static final int MAX_STANCE_SUMMARIES_TOTAL = 4000;
    private int lastCompletedSize = -1;
    private Goal observedNavigationGoal;
    private PathExecutor observedNavigationExecutor;
    private int bestObservedPathPosition = -1;
    private double bestObservedGoalHeuristic = Double.POSITIVE_INFINITY;
    private final LongOpenHashSet navigationVisitedCells = new LongOpenHashSet();
    /** Interaction-pass safety, PROGRESS-based: the clicks-remaining last seen for the current cell and how many
     *  ticks we've spent on it WITHOUT that number going down. A real toggle sequence steps the state every click
     *  (progress resets the counter), so a stall — never alignable, click never registers — is the only thing that
     *  lets noProgress climb, and it's deferred. This bounds both "never aligns" and "click doesn't take" without ever
     *  punishing a legit 24-click note block. */
    private long interactCellHash = -1;
    private boolean interactCellTracked;
    private int interactClicksLast = -1;
    private int interactNoProgressTicks;
    /** Per-tick memo of the standing-position search (cleared every onTick) so the same target isn't re-simulated
     *  by both the 9-slot and 36-slot assemble passes. Value is wrapped in Optional so a cached "no stance" (null
     *  goal) is distinguished from "not computed yet". */
    private final java.util.HashMap<Long, Optional<Goal>> orientedGoalCache = new java.util.HashMap<>();
    /** Only run the (relatively costly) standing search for targets within this Chebyshev range of the bot — far
     *  cells route via the cheap adjacent goal and get the precise stance once the bot is close. Keeps the search
     *  off the every-tick hot path for large facing-heavy builds (walls of stairs, rows of repeaters). */
    private static final int ORIENTED_SEARCH_RANGE = 6;
    /** Hard ceiling on how many targets get a fresh standing search per tick — the pathfinder only ever needs the
     *  nearest few precise stances, so once this many are computed the rest fall back to the cheap adjacent goal
     *  this tick and are refined on a later one. Guarantees the search can never storm the tick loop. */
    /**
     * SUPERSEDED 05.08.2026 by {@link #STANCE_SEARCH_BUDGET_NANOS}; kept only as an absolute ceiling so one
     * pathological tick cannot spin, and raised accordingly.
     *
     * <p>It was 10, and it was the wrong shape of budget. Counting searches per tick made sense only while the search
     * was reserved for five block types; now every cell gets one, and a count either throttles a cheap tick for no
     * reason or lets an expensive one run long. The two attempts that widened the search both died of the same thing,
     * and the census of run 20260802-231337 is explicit about it: searching 57.1% -> 74.7% of ticks,
     * walking-to-stance 20.0% -> 6.5%, STANCE searches 0 -> 43126 over 39580 ticks. The bot stopped travelling and
     * started computing. What has to be bounded is TIME.
     */
    private static final int MAX_ORIENTED_SEARCHES_PER_TICK = 96;
    /**
     * How long one tick may spend deriving standing positions, the owner's own budget: "solange wir unter zwanzig
     * Millisekunden bleiben, warum nicht die Versuche raushauen".
     *
     * <p>A tick is 50 ms at 20 TPS, so this is 40% of it and leaves the walking its share. Exhausting it is not a
     * failure and nothing is discarded: {@code orientedGoalCache} keeps what was derived, and the cells that did not
     * fit are derived on the following tick. That is the whole difference from a count -- a count refuses the eleventh
     * cheap search, a clock refuses only what there is genuinely no time for.
     */
    private static final long STANCE_SEARCH_BUDGET_NANOS = 20L * 1_000_000L;
    /** Standable stances actually raytraced per search. Nearest-first, so the tail is the walk-furthest end. */
    /**
     * How many STANDABLE candidates may be raycast per stance search. 24 of them was a budget; it is now the whole
     * window, because the arithmetic says the budget was never the expensive part.
     *
     * <p>The window is 7x7 over 3 heights (5 for the upward-look family), so 145 or 243 candidates -- but this counter
     * only advances AFTER the cheap standability filter, and that filter removes most of them: measured, 97 of 145 were
     * not standable, leaving 48. At roughly 12 rays per candidate (up to 6 faces x 1-2 derived aim points) a complete
     * search is therefore about 576 rays, on the order of 1-3 ms against a 16.7 ms tick at TimeScale 3. Going from 24
     * to all 48 costs about 0.6 ms.
     *
     * <p>What the cap cost while it stood: the candidates are sorted by nearness to the BOT, so the 24 that got
     * evaluated were the ones near wherever it happened to stand, and a stance that works could sit at position 30 and
     * never be tried. Measured on the facings row, cell 85,-59,67 wanting observer[facing=west]: every evaluated
     * candidate sat around z=64.5, three blocks off the row, where dx=3.5 and dz=2.5 are close enough that vanilla's
     * dominant-axis rule returns NORTH instead of EAST -- so all 48 reported "wrong block would land" and the cell was
     * declared impossible. The stances that work are the ones at dz=+-1 directly beside the row, which the owner
     * derived by hand for the neighbouring cell 83,-59,67. They were in the window the whole time and were never
     * evaluated.
     *
     * <p>What the frequency costs is a different question and stays bounded elsewhere: 43126 searches on 39580 ticks
     * was measured at 1957 -> 1652 cells ("the bot stopped travelling and started computing"). More rays per search is
     * cheap; more searches per tick is not.
     */
    private static final int MAX_STANCES_EVALUATED_PER_CALL = 145;
    /** How many search passes a cell is held back for sealing a hanging neighbour before the wall wins anyway. */
    private static final int SEAL_HOLD_TICKS = 200;
    /**
     * Deferrals one cell gets before it is retired for this sweep.
     *
     * <p>Six, and this number is measured rather than reasoned. Dropping it to three (with a 150-tick lock deadline) to
     * make the escalation "decisive" cost etz-basalt 418 blocks -- 1438 placed became 1020 -- because a cell that fails
     * three times often succeeds on the fourth once a neighbour lands, and retiring it instead throws that away. The
     * retry sweep does eventually revisit it, but only after every layer is done, which a stalled run never reaches.
     *
     * <p>Patience is cheap while other cells are still being placed, and the deadline plus the backoff already bound
     * the cost. Impatience is only cheap when the cell is genuinely hopeless, and it is not possible to know that from
     * three attempts.
     */
    private static final int CELL_DEFERRALS_BEFORE_RETIRING = 6;
    /**
     * What one STRUCTURAL failure -- "no stance anywhere can place this yet" -- costs against that budget.
     *
     * <p>A weight, not a separate rule, because the two extremes were both measured and both wrong. Treating it like
     * any other miss means six full walks across the build to re-derive an answer the world already gave: the facings
     * scenario burned 51 deferrals and 43 stance changes on ten such cells. Retiring on the first one instead cost
     * etz-basalt 685 blocks -- 2715 placed fell to 2030 -- because in a dense schematic "no stance right now" is
     * usually temporary: the neighbour that will become the support is itself still queued.
     *
     * <p>Applied only where {@link #supportCouldStillArriveThisLayer} says no support can arrive in time, which is the
     * distinction that makes this number safe to raise at all. Without that gate it was applied to every cell that
     * currently had no stance, and every degree of impatience cost etz-basalt heavily: immediate retirement took 2715
     * placed down to 2030, and a weight of three took it to 944, because in a dense schematic "no stance right now" is
     * overwhelmingly about TIMING -- the support is another cell in the same layer that has not had its turn yet.
     *
     * <p>Three, so a genuinely hopeless cell steps aside after two attempts rather than six. The facings scenario is
     * where that matters: a down-facing piston can only be placed by clicking the course ABOVE it, which belongs to a
     * layer that cannot start until this one finishes. Waiting is not patience there, it is deadlock.
     */
    private static final int STRUCTURAL_DEFERRAL_WEIGHT = 3;
    /** Ticks without any cell becoming correct before the watchdog starts narrating, and its repeat interval. Above
     *  the longest legitimate quiet stretch (a walk across a large schematic) and far below a run-killing stall. */
    private static final int WATCHDOG_SILENCE_TICKS = 200;

    /** When the stall watchdog last actually spoke, so a skipped tick delays it rather than silencing it. */
    private long lastWatchdogNarrationTick = Long.MIN_VALUE / 4;
    /** How long the build will pause waiting for a hotbar swap before deciding the swap is not coming. A legitimate
     *  swap lands in one or two ticks; anything beyond this is a refusal in disguise. */
    private static final int HOTBAR_FETCH_GRACE_TICKS = 20;
    /** Ticks of getting nowhere before the bot considers that the door it is standing in might be the reason. Short:
     *  being stuck in a doorway is never productive, and opening a door costs one click. */
    private static final int DOOR_ESCAPE_TICKS = 60;
    /**
     * Ticks a layer may produce nothing at all before it is set aside and the build climbs.
     *
     * <p>Measured from a 322000-tick etz-basalt run rather than chosen: the gap between one cell completing and the
     * next has a MEDIAN of 20 ticks, and 90.4% of them are under 300. So a layer that has produced nothing for 300
     * ticks is fifteen times past normal, and this is a diagnosis, not a guess.
     *
     * <p>It was 900, and that run spent 196100 of its 322000 ticks -- 61% -- inside gaps longer than 200 ticks, spread
     * over 264 of them. Waiting 900 each time is most of a run spent waiting for permission to move on: past t=90000
     * the build added 854 blocks in 230000 ticks, having managed 4135 in the 90000 before it.
     *
     * <p>300 was tried on that evidence and REVERTED. It is right for a schematic small enough to reach its retry
     * sweeps and wrong for the one that matters:
     *
     * <ul>
     *   <li>facings 60 -> 90/96, a new best, server-audited -- and for the first time the build ran the whole pipeline
     *       to its end, climbing every layer and then all three sweeps (20, 11, 10 cells) to print "Build finished".
     *       Vertical observers went to 0 unsatisfied.
     *   <li>basalt 4135 -> 3718 at the same tick (~89000), audited 34.6% -> 26.3%, and the bench called it STALLED at
     *       t=88800 where the 900 run was still trickling forward at t=322000. By that tick it had retired 3219 cells
     *       against roughly 1500 for the 900 run: about twice as fast.
     * </ul>
     *
     * <p>So the trade is time against cells. 900 wastes 61% of a run waiting; 300 sets aside cells that would have
     * completed within the next few hundred ticks, and on a schematic whose sweeps are hours away that is a pure loss.
     * A duration is the wrong control variable for either: the question the give-up actually wants to ask is whether
     * ANY cell in this layer is currently placeable, which is a state and cheap to answer. See docs/UPWARD_LOOK.md.
     *
     * <p>Still comfortably longer than any single cell's retry schedule, so it fires when the LAYER is stuck rather
     * than a cell in it, and nothing is lost either way -- the retry sweeps come back for everything set aside.
     */
    private static final int LAYER_GIVE_UP_TICKS = 900;
    /** The same wait, for a layer whose every remaining cell has already all but exhausted its retry budget. Those are
     *  not about to succeed, so the long wait buys nothing. @see #everyVisibleCellIsNearlyExhausted */
    private static final int LAYER_GIVE_UP_EXHAUSTED_TICKS = 200;
    /** Per-tick aim movement below which the crosshair counts as at rest. Above the humanised tremor floor (~0.05 deg)
     *  and far below the 45-degree boundary that decides a piston's facing. */
    private static final float AIM_SETTLED_DEGREES = 0.25F;
    /** Ticks a placement will wait for a still crosshair before clicking anyway. */
    private static final int AIM_SETTLE_MAX_WAIT_TICKS = 20;

    /**
     * Consecutive still ticks required before the FIRST swing on a new face.
     *
     * <p>Four, because it has to cover the round trip. A movement packet leaves every tick, so four still ticks is
     * 200 ms of the server having been told where the head is -- comfortably past a 150 ms connection, and nothing
     * at all on a local one, where the head is still by then anyway.
     */
    private static final int SNAKE_FACE_CHANGE_SETTLE_TICKS = 4;

    /** How far around its own feet the snake looks for material that fell in behind it. */
    private static final int SNAKE_SWEEP_RADIUS = 4;

    /** Three seconds outside the safe face-angle cone before the slice is taken apart one block at a time instead. */
    private static final int SNAKE_SQUARE_GIVE_UP_TICKS = 60;

    /** Upper bound on that wait. A swing is never traded for a perfectly still crosshair. */
    private static final int SNAKE_FACE_CHANGE_MAX_WAIT_TICKS = 30;
    /** Per-cell count of passes spent held back by {@link #wouldSealPendingHangingNeighbour}. */
    private final java.util.HashMap<Long, Integer> sealHolds = new java.util.HashMap<>();
    /** Cached answer to "does this layer still have structural work?", recomputed once per build tick. */
    private boolean structuralWorkRemains;
    private long structuralScanTick = -1L;

    private int orientedSearchesThisTick;
    private String name;
    /** Explicit caller-selected Map-Art traversal; never inferred from schematic dimensions. */
    private boolean buildInRows;
    /** One-shot hand-off consumed by the three-argument entry point that owns build initialisation. */
    private boolean nextBuildInRows;
    /** Five rows keep two blocks of reach on either side of the walking line while bounding the open front. */
    private static final int BAND_ROWS = Math.max(1, Integer.getInteger("princeps.builder.bandrows", 5));
    /** Ticks on which no currently offered placement cell lay inside the band. */
    private int bandFallbacks;
    /** Fixed five-row band and global longitudinal slice currently allowed to grow in row mode. */
    private volatile int rowActiveBandStart = Integer.MIN_VALUE;
    private volatile int rowActiveFrontier = Integer.MIN_VALUE;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    public int stopAtHeight = 0;

    public BuilderProcess(Princeps princeps) {
        super(princeps);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin, boolean inRows) {
        excavating = false;
        this.nextBuildInRows = inRows;
        build(name, schematic, origin);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        resetAutoDigLookProfile();
        // EVERY job starts as "not an excavation". clearArea sets the flag again immediately after calling this,
        // and it must be cleared on THIS path too: this is the overload clearArea itself uses, so a build that
        // followed a dig would otherwise inherit the flag and quietly stop parking -- turning a fix for digging
        // into a behaviour change for building, which is exactly what it must not be.
        excavating = false;
        // The trace is opened here rather than lazily, so its first line is the first tick of the build and a cell's
        // record can never begin mid-life. Off unless princeps.buildtrace is set; the bench sets it for every run.
        if (Boolean.getBoolean("princeps.buildtrace")) {
            java.nio.file.Path traced = BuildTrace.start(System.getProperty("princeps.bench.run", name));
            if (traced != null) {
                logDirect("Tracing every tick of this build to " + traced.toAbsolutePath());
            }
        }
        // The run's own numbers are cleared HERE, at the start of a build, and not in resetPlacementTracking.
        // resetPlacementTracking runs from onLostControl, which fires the moment a build finishes -- BEFORE the bench
        // reads the verdict -- so every successful run reported clicks=0, stanceless=0 and recalled=0 no matter what
        // it had actually done. A counter that is zeroed before it is read is worse than no counter.
        buildInRows = nextBuildInRows || Boolean.getBoolean("princeps.builder.rows");
        nextBuildInRows = false;
        bandFallbacks = 0;
        rowActiveBandStart = Integer.MIN_VALUE;
        rowActiveFrontier = Integer.MIN_VALUE;
        ending = Ending.RUNNING;   // the only moment the previous answer stops being the truth
        endingReport.clear();
        stancelessAbandons = 0;
        // Same argument as the counters around it, one level up: the world-change census is static (the movement
        // classes that feed it have no builder reference), so without an explicit clear here the previous build's
        // helper blocks would be counted against this one.
        BuildTrace.resetWorldChangeCensus();
        lastCensusTick = -1L;
        clicksSent = 0;
        landedRight = 0;
        landedWrong = 0;
        blocksBroken = 0;
        consecutivePathFailures = 0;
        // Reset, not flushed: a run left open by the previous build belongs to that build's clock and coordinates,
        // and writing it out here would date it to this build's tick 0.
        deadGoalRunFeet = Long.MIN_VALUE;
        deadGoalRunCount = 0;
        deadGoalEmittedCount = 0;
        headHeightCellsAdmitted = 0;   // "since the build began" has to mean THIS build
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        // Each build gets its own diagnostic budget. All three parts, or a second build in the same session inherits
        // a spent budget and goes quiet for exactly the reason this whole mechanism exists to prevent.
        this.stanceFailuresByCell.clear();
        this.stanceSummaryLastFullTick.clear();
        this.stanceSummariesWritten = 0;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Princeps.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Princeps.settings().buildSubstitutes.value);
        }
        if (Princeps.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Princeps.settings().buildSchematicMirror.value);
        }
        if (Princeps.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Princeps.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Princeps.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int driedWaterloggedCells = countWaterloggedCells(this.schematic);
        if (driedWaterloggedCells > 0) {
            logDirect("Builder V2 structural scope: placing " + driedWaterloggedCells
                    + " waterlogged schematic cell(s) dry; water must be filled by a later fluid pass");
        }
        // V2 has no bucket action. Normalize at ONE boundary instead of weakening valid(): every downstream reader
        // (placement simulation, stance search, material demand, DONE detection and the layer gate) must agree on the
        // same dry structural target. Ignoring the property only in valid() would accept an already-wet block where
        // the schematic wants dry, while leaving the placement oracle unable to produce a wet block from air.
        ISchematic structuralSchematic = this.schematic;
        this.schematic = new MaskSchematic(structuralSchematic) {
            @Override
            protected boolean partOfMask(int x, int y, int z, BlockState currentState) {
                return true;
            }

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current,
                                           List<BlockState> approxPlaceable) {
                return structuralDesiredState(super.desiredState(x, y, z, current, approxPlaceable));
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Princeps.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Princeps.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Princeps.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.areaBandTopCache = y + this.schematic.heightY() - 1;
        this.areaCommittedBandTop = Integer.MIN_VALUE;
        this.areaBand = null;
        this.routeAxisChosen = false;
        this.routeBandTop = Integer.MIN_VALUE;
        this.snakeHead = null;
        this.snakeStance = null;
        this.snakeCorridorLevelY = Integer.MIN_VALUE;
        this.snakeEntering = false;
        this.snakeTurning = false;
        snakeLastSwungFace = null;
        snakeAimSettledTicks = 0;
        snakeFaceChangeWaitTicks = 0;
        this.snakeCleanupTarget = null;
        this.snakeCleanupWork.clear();
        this.snakeCleanupActive = false;
        this.snakeCleanupWithAreaTool = false;
        this.snakeCleanupAreaFace = null;
        this.snakeSliceFace = null;
        this.snakeLastSafeStanceLogKey = Long.MIN_VALUE;
        this.snakeMissingCentreKey = Long.MIN_VALUE;
        this.snakeMissingCentreLastTick = Long.MIN_VALUE;
        this.snakeMissingCentreTicks = 0;
        this.snakeNotSquareTicks = 0;
        this.snakeSingleBlockFallback = false;
        this.snakeRequiresOrdinaryTool = false;
        this.snakeToolWantedSlot = -1;
        this.snakeToolWaitLastTick = Long.MIN_VALUE;
        this.snakeToolWaitTicks = 0;
        this.snakeToolWaitReported = false;
        this.snakeBandTop = Integer.MIN_VALUE;
        this.snakeBandFloor = Integer.MIN_VALUE;
        this.snakeCursor = Integer.MIN_VALUE;
        this.snakeTurnCross = Integer.MIN_VALUE;
        this.snakeTurnTravel = Integer.MIN_VALUE;
        this.snakeTurnComplete = false;
        this.snakeLastLoggedLane = Integer.MIN_VALUE;
        this.snakeTransit = false;
        this.snakeVerificationActive = false;
        this.snakeRouteComplete = false;
        this.snakeVerificationSettleTicks = 0;
        this.snakeVerificationBandTop = Integer.MIN_VALUE;
        this.snakeVerifiedBandTop = Integer.MIN_VALUE;
        this.snakeFloorRepairs = 0;
        this.snakeFluidSourcesPlugged = 0;
        this.snakeShellRepairs = 0;
        this.snakeVerificationPasses = 0;
        this.snakeVerifiedBands = 0;
        this.snakeBridgeTarget = null;
        this.snakeStepWaypoint = null;
        this.snakeStepStuckTicks = 0;
        this.snakePendingBridgeRepairs.clear();
        this.snakeVerificationRepairSweeps = 0;
        this.snakeVerificationStableTicks = 0;
        this.ordinaryIntegrityStableTicks = 0;
        this.ordinaryIntegrityObservedTick = Long.MIN_VALUE;
        this.ordinaryIntegrityBandTop = Integer.MIN_VALUE;
        this.ordinaryIntegrityTarget = null;
        this.ordinaryIntegrityTargetTicks = 0;
        this.paused = false;
        this.layer = Princeps.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Princeps.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (princeps.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Princeps.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(princeps.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(princeps.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Princeps.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Princeps.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Princeps.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        warnIfPlacementSimulationIsApproximate();
        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
        // A build re-issued over a paused process never runs through onLostControl() (the "Unable to do it.
        // Pausing." path returns without it), so placement/recovery/re-press tracking MUST be cleared here too —
        // otherwise the new job inherits stale target ownership and retry history from the previous build.
        resetPlacementTracking();
        reportCellsPerLayer();
    }

    /**
     * V2 is the structural builder; water and waterlogging belong to the later fluid pass. This is deliberately a
     * desired-state transformation, not a comparison exception, so every decision in the builder sees one truth.
     */
    static BlockState structuralDesiredState(BlockState desired) {
        if (desired != null
                && desired.hasProperty(BlockStateProperties.WATERLOGGED)
                && desired.getValue(BlockStateProperties.WATERLOGGED)) {
            return desired.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return desired;
    }

    /** Counts the declared deviation before the structural wrapper removes it, so it is never silent. */
    private int countWaterloggedCells(ISchematic active) {
        if (active == null) {
            return 0;
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        List<BlockState> placeable = this.approxPlaceable == null
                ? Collections.emptyList() : this.approxPlaceable;
        int count = 0;
        for (int y = 0; y < active.heightY(); y++) {
            for (int z = 0; z < active.lengthZ(); z++) {
                for (int x = 0; x < active.widthX(); x++) {
                    try {
                        if (!active.inSchematic(x, y, z, air)) {
                            continue;
                        }
                        BlockState desired = active.desiredState(x, y, z, air, placeable);
                        if (desired != null
                                && desired.hasProperty(BlockStateProperties.WATERLOGGED)
                                && desired.getValue(BlockStateProperties.WATERLOGGED)) {
                            count++;
                        }
                    } catch (RuntimeException ignored) {
                        // A throwing adaptive cell is diagnosed by the normal build path; it must not hide the rest
                        // of this one-time scope census.
                    }
                }
            }
        }
        return count;
    }

    /**
     * Says, once per build, how many cells the template names on each y level.
     *
     * <p>Without it there is no denominator. Every statement about progress -- "layer -59 is at 1090" -- is a
     * numerator with nothing under it, and on 03.08. the reference had to be fetched from a dry run of 31.07. to
     * find out that 1090 meant sixty percent. The dry run is a different program on a different day; a run that
     * cannot state its own denominator cannot be checked against itself.
     *
     * <p>One pass over the schematic at build start, so it costs nothing per tick -- which is the only budget this
     * builder is actually short of.
     */
    private void reportCellsPerLayer() {
        ISchematic active = this.schematic;
        Vec3i anchor = this.origin;
        if (active == null || anchor == null) {
            return;
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        // build() runs before the first tick has filled approxPlaceable, and a schematic is free to dereference the
        // list it is handed. An empty list is the honest stand-in: it only affects WHICH state a choosing schematic
        // returns, never whether the cell is named at all, which is the only thing counted here.
        List<BlockState> placeable = this.approxPlaceable == null ? Collections.emptyList() : this.approxPlaceable;
        StringBuilder census = new StringBuilder();
        int total = 0;
        for (int ly = 0; ly < active.heightY(); ly++) {
            int cells = 0;
            for (int lz = 0; lz < active.lengthZ(); lz++) {
                for (int lx = 0; lx < active.widthX(); lx++) {
                    try {
                        // inSchematic AS WELL AS desiredState, and the order matters: MaskSchematic delegates
                        // desiredState straight through without consulting its own mask (MaskSchematic:43), so a
                        // census built on desiredState alone would count the buildSkipBlocks cells the bench has
                        // already excluded -- and then disagree with the bench's own total for no visible reason.
                        if (!active.inSchematic(lx, ly, lz, air)) {
                            continue;
                        }
                        BlockState wanted = active.desiredState(lx, ly, lz, air, placeable);
                        if (wanted != null && !wanted.isAir()) {
                            cells++;
                        }
                    } catch (RuntimeException ignored) {
                        // a schematic that throws for a cell is not a reason to lose the whole census
                    }
                }
            }
            total += cells;
            if (cells > 0) {
                if (census.length() > 0) {
                    census.append(", ");
                }
                census.append("y=").append(anchor.getY() + ly).append(':').append(cells);
            }
        }
        logDirect("Cells the template names, per layer: " + census + "  (total " + total + ")");
    }

    /**
     * Says out loud, once per build, if the exact placement simulation is not actually available.
     *
     * <p>Without MixinBlockItem applied, {@link BlockItemPlacementHelper#placementState} falls back to asking the
     * item's primary block, which cannot choose a wall variant -- so every wall sign, wall torch, wall banner and
     * wall head in the schematic simulates as its standing form, never matches, and is declared unbuildable for the
     * whole run. That is a total, silent loss of a whole block family. It has happened once already, undetected,
     * because a missing Mixin registration throws nothing and logs nothing.
     */
    private void warnIfPlacementSimulationIsApproximate() {
        if (!BlockItemPlacementHelper.wired((BlockItem) net.minecraft.world.item.Items.STONE)) {
            logDirect("BUILDER WARNING: MixinBlockItem is not applied, so placement prediction cannot choose"
                    + " wall-mounted variants. Every wall sign/torch/banner/head in this schematic will be treated as"
                    + " unbuildable. This is a build configuration fault, not a schematic problem.");
        }
    }

    /** What the reserved tool slot should be stocked against right now. See {@link #blockWorthATool}. */
    public Block toolWorkInFront() {
        BlockState state = blockWorthATool;
        return state == null || !isActive() ? Blocks.STONE : state.getBlock();
    }

    public void resume() {
        // A token-owned hold may only be resumed through its validated relocation boundary.
        if (homeRecovery != null) return;
        if (paused && scaffoldMaterialPausedAt != null) {
            if (scaffoldMaterialTarget != null) scaffoldMaterialWaitStarted += Math.max(0, buildTick - scaffoldMaterialPausedAt);
            scaffoldMaterialPausedAt = null;
        }
        if (paused && excavationApproach != null) excavationApproach.suspend();
        paused = false;
    }

    public void pause() {
        if (!paused && scaffoldMaterialTarget != null) scaffoldMaterialPausedAt = buildTick;
        paused = true;
        platformTraverseApproach = null;
        supportRepair = null;
        progressWatch.pause();
        if (excavationApproach != null) excavationApproach.suspend();
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    // Optional concrete capability; it deliberately adds nothing to the shared release API.
    private boolean homeRecoveryEnabled;
    private long homeRecoverySequence;
    private long homeRecoveryAttemptRevision = Long.MIN_VALUE;
    private HomeRecovery homeRecovery;
    private Goal homeFailedRoute;
    private BetterBlockPos homeFailedTarget;
    private long homeFailedRevision = Long.MIN_VALUE;
    private static final long HOME_QUIESCE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);

    private final class HomeRecovery {
        final long id = ++homeRecoverySequence;
        final Object world = ctx.world(), player = ctx.player();
        final ISchematic model = supportModelForDependencies();
        final Vec3i buildOrigin = new Vec3i(origin.getX(), origin.getY(), origin.getZ());
        final long deadline;
        String state = "QUIESCING";
        boolean initiallyQuiesced;
        Vec3 restingPosition;
        int restingTicks;
        HomeRecovery(long now) { deadline = now + HOME_QUIESCE_NANOS; }
        boolean current() {
            return isActive() && !excavating && !buildInRows && world == ctx.world() && player == ctx.player()
                    && model == supportModelForDependencies() && buildOrigin.equals(origin);
        }
    }

    public void setHomeRecoveryEnabled(boolean enabled) {
        homeRecoveryEnabled = enabled;
        if (!enabled && homeRecovery != null) failHomeRecovery(homeRecovery.id);
    }

    public String homeRecoveryState() {
        HomeRecovery hold = homeRecovery;
        if (hold != null && (!hold.current() || !homeRecoveryOwnsPlayer())) hold.state = "FAILED";
        return hold == null ? (homeRecoveryEnabled ? "IDLE" : "DISABLED") : hold.state;
    }

    public long homeRecoveryRequestId() { return homeRecovery == null ? 0 : homeRecovery.id; }

    public void failHomeRecovery(long id) {
        HomeRecovery hold = homeRecovery;
        if (hold == null || hold.id != id) return;
        hold.state = "FAILED";
        if (hold.current()) pause(); // no teardown, no release of the attempt budget
    }

    /** Exact current-owner boundary also used by SurvivalBehavior; never suppress another process's use. */
    public boolean homeRecoveryHoldsUse() {
        return homeRecovery != null && homeRecovery.current() && homeRecoveryOwnsPlayer();
    }

    private boolean homeRecoveryOwnsPlayer() {
        return princeps.getPathingControlManager().mostRecentInControl().orElse(null) == this;
    }

    private boolean homeRecoveryHasAcknowledgement() {
        return pendingPlacementRequest != null || lastPlacedCell != null || navigationScaffolds.awaitingServer()
                || scaffoldCleanupAwaitingServer()
                || cleanupEscapeDebt != null && !cleanupEscapeDebt.discharged();
    }

    private boolean exhaustedUnplacedCleanup() {
        return cleanupEscape != null && cleanupEscape.stage == CleanupStage.BLOCKED && !cleanupEscape.placed
                && "finite helper candidates exhausted".equals(cleanupEscape.blockedReason)
                && !cleanupEscape.probe.isRunning();
    }

    /** Invoked only by this process's real STOP decision, before its ordinary terminal teardown. */
    boolean beginHomeRecovery(BuilderProgressWatch.Phase phase, long now) {
        if (!homeRecoveryEnabled || homeRecovery != null || paused || !isActive() || excavating || buildInRows
                || phase != BuilderProgressWatch.Phase.WORK || homeRecoveryHasAcknowledgement()
                || scaffoldMaterialTarget != null
                || homeRecoveryAttemptRevision == confirmedProgressRevision || ctx.world() == null || ctx.player() == null
                || !ctx.world().hasChunkAt(ctx.playerFeet())) return false;
        boolean exhausted = exhaustedUnplacedCleanup();
        if (cleanupEscape != null && !exhausted) return false;
        Goal actualGoal = princeps.getPathingBehavior().getGoal();
        BetterBlockPos actualTarget = committedPlaceTarget != null ? committedPlaceTarget : electedCell;
        boolean failedRoute = homeFailedRoute != null && homeFailedRevision == confirmedProgressRevision
                && Objects.equals(homeFailedRoute, actualGoal) && Objects.equals(homeFailedTarget, actualTarget);
        boolean negativeLane = laneAProof != null && laneQuestionCurrent(laneAProof, electedGoal, true);
        if (!exhausted && !failedRoute && !negativeLane) return false;
        if (actualTarget != null && !ctx.world().hasChunkAt(actualTarget)) return false;
        if (exhausted) {
            cleanupEscape.probe.cancel();
            cleanupEscape = null; // only a terminal, unplaced episode; retain attempted owners and every debt/ledger
        }
        homeRecoveryAttemptRevision = confirmedProgressRevision;
        homeRecovery = new HomeRecovery(now);
        pause();
        discardLaneQuestion();
        laneAProof = null;
        laneEscalatedCell = null;
        laneBAnswered = false;
        supportRepair = null;
        BuildTrace.cell(buildTick, "HOME-QUIESCING", origin.getX(), origin.getY(), origin.getZ(),
                "id=" + homeRecovery.id + " confirmedWorldChanges=" + confirmedProgressRevision);
        return true;
    }

    private boolean homeRouteEmpty() {
        var pathing = princeps.getPathingBehavior();
        return pathing.getCurrent() == null && pathing.getNext() == null && pathing.getInProgress().isEmpty();
    }

    private boolean homeBodyAtRest() {
        var player = ctx.player();
        BetterBlockPos feet = ctx.playerFeet();
        return ctx.world().hasChunkAt(feet) && ctx.world().hasChunkAt(feet.below())
                && ctx.world().hasChunkAt(feet.above()) && player.onGround() && !player.isUsingItem()
                && !player.isInWater() && !player.isInLava() && !player.isPassenger() && !player.isFallFlying()
                && player.getDeltaMovement().horizontalDistanceSqr() < 0.000001
                && Math.abs(player.getDeltaMovement().y) < 0.1
                && ctx.world().noCollision(player);
    }

    /** Actual owned tick: an unsafe movement may finish, but READY never exposes that unfinished movement. */
    PathingCommand advanceHomeRecovery(long now) {
        HomeRecovery hold = homeRecovery;
        if (hold == null) return null;
        progressHold = true;
        if (!hold.current()) { hold.state = "FAILED"; return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL); }
        if (!hold.initiallyQuiesced && now >= hold.deadline) hold.state = "FAILED";
        var pathing = princeps.getPathingBehavior();
        if (!pathing.cancelSegmentIfSafe()) {
            return continueCurrentRoute(pathing.getGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        var input = princeps.getInputOverrideHandler();
        input.clearAllKeys();
        input.getBlockBreakHelper().stopBreakingBlock();
        input.getBlockPlaceHelper().clearExpectedPlacement();
        // Survival runs later under the actual selected owner. READY requires that teardown to have completed.
        if (!homeRouteEmpty() || homeRecoveryHasAcknowledgement()
                || princeps.getSurvivalBehavior().isConsuming() || !homeBodyAtRest()
                || ctx.minecraft().options.keyUse.isDown() || ctx.minecraft().options.keyAttack.isDown()) {
            hold.restingTicks = 0;
            hold.restingPosition = null;
            if ("READY".equals(hold.state)) hold.state = "QUIESCING";
        } else if (!"FAILED".equals(hold.state)) {
            Vec3 position = ctx.player().position();
            hold.restingTicks = hold.restingPosition != null && hold.restingPosition.distanceToSqr(position) < 0.000001
                    ? hold.restingTicks + 1 : 1;
            hold.restingPosition = position;
            if (hold.restingTicks >= 2) {
                hold.initiallyQuiesced = true;
                hold.state = "READY";
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    public boolean resumeAfterHomeRecovery(long id) {
        HomeRecovery hold = homeRecovery;
        if (hold == null || hold.id != id || !homeRecoveryEnabled || !"READY".equals(homeRecoveryState())
                || !hold.current() || !homeRecoveryOwnsPlayer() || !homeRouteEmpty() || !homeBodyAtRest()
                || princeps.getSurvivalBehavior().isConsuming() || homeRecoveryHasAcknowledgement()
                || ctx.minecraft().options.keyUse.isDown() || ctx.minecraft().options.keyAttack.isDown()) return false;
        // Client has established the server-confirmed landing. Invalidate executable permissions, not the build.
        releasePlacementTarget();
        discardLaneQuestion();
        laneAProof = null;
        laneEscalatedCell = null;
        laneBAnswered = false;
        electedGoal = null;
        orientedGoalCache.clear();
        // A negative route search was about the departure position. Local support/stance facts still apply.
        var parks = parkedCells.entrySet().iterator();
        while (parks.hasNext()) {
            var entry = parks.next();
            if (entry.getValue().reason != ParkReason.UNREACHABLE) continue;
            BetterBlockPos pos = entry.getValue().pos;
            forgetCellVerdict(entry.getKey());
            parks.remove();
            activeCells.add(pos);
            if (incorrectPositions != null) incorrectPositions.add(pos);
        }
        supportRepair = null;
        progressActions.clear();
        // A confirmed relocation ends the old break branch's yield/damage/claim history, not a world action.
        resetBreakBranchTracking();
        resetNavigationProgressTracking();
        progressExecutor = null;
        progressRoute = null;
        progressRouteTarget = null;
        progressRoutePositions = List.of();
        progressRoutePosition = -1;
        homeFailedRoute = null;
        homeFailedTarget = null;
        // One explicit relocation grace period. It grants no confirmed world revision and cannot rearm Home.
        progressWatch.reset();
        homeRecoveryAttemptRevision = confirmedProgressRevision;
        homeRecovery = null;
        resume();
        progressHold = false;
        BuildTrace.cell(buildTick, "HOME-RESUMED", origin.getX(), origin.getY(), origin.getZ(),
                "id=" + id + " fresh route required; confirmedWorldChanges=" + confirmedProgressRevision);
        return true;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Princeps.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Princeps.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, princeps.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.getA());
                build(schematic.getA().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
        // AFTER the build, because build() clears the flag for every job that is not this one.
        excavating = true;
        excavationApproach.start();
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    private BlockPos lastSupportRefusal;
    private BlockPos lastSupportDependent;
    private BuilderSupportDependencies.Reason lastSupportRefusalReason;
    private record SupportRepair(BlockPos target, BlockState state, Object world, Object player,
                                 ISchematic model, long tick) {}
    private SupportRepair supportRepair;

    /** The full model bound to a particular search, retained by its executor even after pause/owner handover. */
    public static final class ModelProtection {
        private final BuilderProcess owner;
        private final ISchematic model;
        private final Vec3i origin;
        private final Object world, player;
        private final List<BlockState> stock;

        ModelProtection(BuilderProcess owner, ISchematic model, Vec3i origin,
                        Object world, Object player, List<BlockState> stock) {
            this.owner = owner;
            this.model = model;
            this.origin = new Vec3i(origin.getX(), origin.getY(), origin.getZ());
            this.world = world;
            this.player = player;
            this.stock = List.copyOf(stock);
        }

        public boolean sameBinding(ModelProtection other) {
            return other != null && owner == other.owner && model == other.model
                    && origin.equals(other.origin) && world == other.world && player == other.player && stock.equals(other.stock);
        }

        public boolean allowsRemoval(BlockPos target, princeps.api.utils.IPlayerContext context,
                                     princeps.api.process.IPrincepsProcess controlling,
                                     java.util.function.BooleanSupplier continuing) {
            if (context.world() != world || context.player() != player) return false;
            BlockState repairing = null;
            if (controlling == owner && owner.isActive() && !owner.paused && !owner.excavating
                    && owner.supportModelForDependencies() == model && origin.equals(owner.origin)) {
                SupportRepair repair = owner.supportRepair;
                boolean held = repair != null && repair.tick() != owner.buildTick && continuing.getAsBoolean();
                repairing = owner.selectedSupportRepairState(target, model, world, player, held);
            }
            // A planning BSI is a snapshot. Re-read the actual world at the final crosshair boundary.
            return BuilderSupportDependencies.currentWorld(model, origin, stock, context.world())
                    .removal(target, repairing).allowed();
        }
    }

    private BlockState selectedSupportRepairState(BlockPos target, ISchematic model,
                                                  Object world, Object player, boolean continuing) {
        SupportRepair repair = supportRepair;
        return repair != null && (repair.tick() == buildTick || repair.tick() == buildTick - 1 && continuing)
                && repair.target().equals(target) && repair.world() == world && repair.player() == player
                && repair.model() == model ? repair.state() : null;
    }

    /** A temporary process can take control without onLostControl; its mining is not this repair's continuation. */
    public void revokeSupportRepair() {
        supportRepair = null;
        if (homeRecovery != null) failHomeRecovery(homeRecovery.id);
    }

    /** Called only after the ordinary direct repair branch has selected and aimed a break-and-replace action. */
    void selectSupportRepair(BlockPos target, BlockState current, BlockState wanted) {
        supportRepair = wanted != null && current != wanted && interactionClicks(current, wanted) < 0
                ? new SupportRepair(target.immutable(), current, ctx.world(), ctx.player(),
                        supportModelForDependencies(), buildTick) : null;
    }

    /** Shared with the navigation cost policy, applied to the real crosshair immediately before mining. */
    public boolean allowsSupportRemoval(BlockPos target) {
        if (platformRouteForbidsMining(princeps.getPathingBehavior().getCurrent())) return false;
        if (!isActive() || paused || excavating) { supportRepair = null; return true; }
        if (platformTraverseApproach != null) return false; // same no-break contract as the one-step route
        boolean continuing = supportRepair != null && supportRepair.tick() != buildTick
                && princeps.getInputOverrideHandler().getBlockBreakHelper().isBreakingBlock();
        return allowsSupportRemoval(target, BuilderSupportDependencies.currentWorld(supportModelForDependencies(), origin,
                approxPlaceable == null ? Collections.emptyList() : approxPlaceable, ctx.world()), continuing);
    }

    /** An unsafe-to-cancel edge route can outlive its owner; its no-mining contract lasts until that route ends. */
    public static boolean platformRouteForbidsMining(PathExecutor executing) {
        return executing != null && executing.getPath().getGoal() instanceof PlatformTraverseGoal;
    }

    boolean allowsSupportRemoval(BlockPos target, BlockStateInterface blocks) {
        return allowsSupportRemoval(target, blocks, false);
    }

    boolean allowsSupportRemoval(BlockPos target, BlockStateInterface blocks, boolean controllerContinuingSameBlock) {
        if (!isActive() || paused || excavating) { supportRepair = null; return true; }
        return allowsSupportRemoval(target, new BuilderSupportDependencies(supportModelForDependencies(), origin,
                approxPlaceable == null ? Collections.emptyList() : approxPlaceable, blocks, ctx.world()), controllerContinuingSameBlock);
    }

    private boolean allowsSupportRemoval(BlockPos target, BuilderSupportDependencies policy, boolean controllerContinuingSameBlock) {
        if (platformTraverseApproach != null) return false;
        ISchematic full = supportModelForDependencies();
        SupportRepair repair = supportRepair;
        BlockState repairing = selectedSupportRepairState(target, full, ctx.world(), ctx.player(), controllerContinuingSameBlock);
        supportRepair = repairing == null ? null : new SupportRepair(repair.target(), repair.state(), repair.world(),
                repair.player(), repair.model(), buildTick);
        BuilderSupportDependencies.Decision decision = policy.removal(target, repairing);
        if (!decision.allowed() || repairing != null && ctx.world().getBlockState(target) != repairing) supportRepair = null;
        if (decision.allowed()) {
            lastSupportRefusal = null;
            lastSupportDependent = null;
            lastSupportRefusalReason = null;
        }
        if (!decision.allowed() && (!target.equals(lastSupportRefusal)
                || decision.reason() != lastSupportRefusalReason
                || !Objects.equals(decision.dependent(), lastSupportDependent))) {
            lastSupportRefusal = target.immutable();
            lastSupportDependent = decision.dependent();
            lastSupportRefusalReason = decision.reason();
            BuildTrace.cell(buildTick, "SUPPORT-PROTECTED", target.getX(), target.getY(), target.getZ(),
                    "reason=" + decision.reason() + " dependent=" + decision.dependent());
        }
        return decision.allowed();
    }

    ISchematic supportModelForDependencies() {
        return realSchematic == null ? schematic : realSchematic;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    /**
     * A block the bot put down purely to stand on, which the schematic never asked for.
     *
     * <p>The owner's requirement is that none of these are left in the finished build, and "build cleverly enough not
     * to need them" is not available: etz-basalt's y=-59 layer holds roughly 1700 cells above a 936-cell one, so many
     * of them float with no neighbour at all and therefore no face to click. A person could not place those either
     * without putting something down first.
     *
     * <p>So they are found rather than book-kept. Anything made of an {@code acceptableThrowawayItems} material, inside
     * the schematic's own bounding box, in a cell the schematic has no opinion about, was put there to stand on -- by
     * this bot, by the pathfinder, or by an earlier run, and it does not matter which. Detection catches all three;
     * tracking every placement would catch only the first.
     */
    @Override
    public boolean templateNamesABlockAt(BlockPos pos) {
        // Deliberately tolerant of every "not running" shape: this is consulted from the movement executor, which
        // runs for ordinary pathing too, and an exception here would take down walking rather than building.
        ISchematic active = this.schematic;
        Vec3i anchor = this.origin;
        if (active == null || anchor == null || pos == null) {
            return false;
        }
        int lx = pos.getX() - anchor.getX();
        int ly = pos.getY() - anchor.getY();
        int lz = pos.getZ() - anchor.getZ();
        if (lx < 0 || ly < 0 || lz < 0
                || lx >= active.widthX() || ly >= active.heightY() || lz >= active.lengthZ()) {
            return false;
        }
        try {
            BlockState wanted = active.desiredState(lx, ly, lz, Blocks.AIR.defaultBlockState(), this.approxPlaceable);
            return wanted != null && !wanted.isAir();
        } catch (RuntimeException ignored) {
            return false;   // a schematic that throws for a cell is not a licence to wreck that cell
        }
    }

    @Override
    public boolean orientationIsLoadBearingAt(BlockPos placeAt) {
        // Dieselbe Toleranz wie templateNamesABlockAt: gefragt wird aus dem Klick-Engpass, der auch fuer
        // gewoehnliches Pathing laeuft, und eine Ausnahme hier wuerde das Laufen beenden statt das Bauen.
        if (Princeps.settings().buildIgnoreDirection.value) {
            return false;   // der Nutzer hat ausdruecklich gesagt, dass ihm die Richtung egal ist
        }
        ISchematic active = this.schematic;
        Vec3i anchor = this.origin;
        if (active == null || anchor == null || placeAt == null) {
            return false;
        }
        int lx = placeAt.getX() - anchor.getX();
        int ly = placeAt.getY() - anchor.getY();
        int lz = placeAt.getZ() - anchor.getZ();
        if (lx < 0 || ly < 0 || lz < 0
                || lx >= active.widthX() || ly >= active.heightY() || lz >= active.lengthZ()) {
            return false;
        }
        try {
            BlockState wanted = active.desiredState(lx, ly, lz, Blocks.AIR.defaultBlockState(), this.approxPlaceable);
            if (wanted == null || wanted.isAir()) {
                return false;
            }
            // DIESELBE Liste, die valid() benutzt, um eine Platzierung als falsch zu erkennen -- sonst verboete
            // diese Sperre Klicks, die niemand beanstandet, oder liesse welche durch, die P5 gleich abbricht.
            // Dazu die gerichtete Familie ueber ihre Property, weil Kolben und Beobachter FACING tragen, ohne
            // HorizontalDirectionalBlock zu sein: genau sie waren der gemessene Schaden.
            for (Property<?> prop : wanted.getProperties()) {
                if (ORIENTATION_PROPS.contains(prop)) {
                    return true;
                }
            }
            return wanted.hasProperty(BlockStateProperties.FACING);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * May a movement put a throwaway block into {@code placeAt} to stand on? See {@link IBuilderProcess} for the run
     * this exists for.
     *
     * <p>Four licences, and each one is a case where the block IS justified:
     * <ol>
     *   <li>no build running — ordinary pathing must be untouched;</li>
     *   <li>the cell is a support the builder itself reserved through {@code temporarySupportGoal}, which is the
     *       deliberate, tracked, self-releasing scaffolding the owner asked for and not a throwaway at all;</li>
     *   <li>a stance has been PROVEN and the bot is routing to it. Then the walk has a purpose, the block serves that
     *       walk, and the ordinary {@code HELPER_BLOCK_COST} price (20 walk-blocks) already decides whether it is
     *       worth it — the owner's own rule for when a helper block is acceptable;</li>
     *   <li>the lapse, which exists because refusing a movement does not make the router route around it.</li>
     * </ol>
     *
     * <p>What is NOT here: the {@code wouldPlaceTemplateBlock} case. Putting the block the template is waiting for
     * into its own cell is free progress rather than scaffolding, and the two call sites keep that distinction
     * themselves — conflating them was already measured wrong once (all six y=-59 "scaffold" coordinates of run
     * 20260802-232954 were the second case).
     *
     * <p>Consulted from the movement executor, so it must never throw and must be cheap: three field reads and a hash
     * lookup on the hot path.
     */
    @Override
    public boolean scaffoldIsLicensedAt(BlockPos placeAt) {
        if (this.schematic == null || placeAt == null) {
            return true;
        }
        if (buildInRows) {
            // Airborne Map Art must advance on cells that belong to the picture itself. Letting navigation invent a
            // traverse bridge or pillar merely moves the old hidden-floor cheat into the player's inventory (and may
            // even consume a palette material such as netherrack when no dedicated cobblestone was supplied).
            // Actual schematic cells are exempted at the movement call sites and in getCostOfPlacingAt, so this veto
            // removes helper geometry only; it cannot veto placing a requested pixel.
            noteScaffoldVetoed(placeAt);
            return false;
        }
        if (temporarySupportTargets.containsKey(positionKey(placeAt.getX(), placeAt.getY(), placeAt.getZ()))) {
            return true;
        }
        IPathExecutor currentRoute = princeps.getPathingBehavior().getCurrent();
        if (currentRoute != null) {
            PlacementLicence routeLicence = currentRoute.placementLicence();
            // A constrained route owns its immutable decision for its whole movement. In particular, a sneak
            // backplace changes playerFeet to the air cell just before the click; the next builder tick may already
            // calculate the following waypoint, but that must not revoke the exact bridge cell snapshotted into the
            // MovementTraverse currently balancing over the edge. UNRESTRICTED is deliberately excluded here or an
            // ordinary path would bypass every builder-side scaffold policy below.
            if (!routeLicence.isUnrestricted() && routeLicence.permitsPlacement(placeAt)) {
                return true;
            }
        }
        if (snakeBridgeTarget != null && snakeBridgeTarget.equals(placeAt)) {
            return true;
        }
        if (NAVIGATION_MAY_NEVER_SCAFFOLD) {
            // The owner's experiment: take the helper block away from the pathfinder ENTIRELY and see whether it can
            // reach its work without one. Drops the "proven stance in flight" licence and the lapse, so this really is
            // never -- the bot either finds a walking route or it does not go.
            noteScaffoldVetoed(placeAt);
            return false;
        }
        // THE SAME RULE THE PLANNER READS, and it must stay that way. When these two disagreed, A* planned a bridging
        // MovementTraverse every tick and the executor refused it every tick: 921 two-node paths against 922
        // no-path ticks in run f159bc1d, 882 refusal gaps of exactly two ticks, the bot motionless, zero placements in
        // 1920 ticks. Any future clause added here has to be mirrored in BuilderCalculationContext.scaffoldLicensed.
        //
        // Two clauses, and the OR is load-bearing:
        //  - electedCell != null -- there IS a latched target cell with a proven standing position and the bot is
        //    walking to it. This is the approach walk, and it is the one the lock below cannot cover: the lock is set
        //    by placementRecoveryCommand or on arrival, so it grants only AFTER the walk it was meant to enable.
        //    Without this clause facings froze solid at pos 84.531,-60.000,66.500 with path=goal-no-path every tick,
        //    because the top stone row's stances sit on top of a two-block wall and a jump gains one block.
        //  - the lock -- the recovery walk, which plans its own single stance and already has a licence today.
        //    Dropping it would take that licence away, so this is an OR and not a replacement.
        if (scaffoldPassAllowed
                && (electedCell != null
                    || (placementTargetLock.hasPlannedStance() && plannedPlacementStance != null))) {
            return true;
        }
        noteScaffoldVetoed(placeAt);
        if (scaffoldVetoTicks > SCAFFOLD_VETO_MAX_TICKS) {
            // Logged rather than silent: "no helper block was placed" and "the veto gave up and it was placed anyway"
            // look identical in a census of leftover blocks, and they mean opposite things about the fix.
            BuildTrace.cell(buildTick, "SCAFFOLD-LAPSED", placeAt.getX(), placeAt.getY(), placeAt.getZ(),
                    "refused " + scaffoldVetoTicks + " ticks without a proven stance; allowing it rather than standing still");
            return true;
        }
        return false;
    }

    /** One increment per tick at most, however many candidate cells the router tries in it. */
    private void noteScaffoldVetoed(BlockPos placeAt) {
        if (scaffoldVetoLastTick != buildTick) {
            scaffoldVetoLastTick = buildTick;
            scaffoldVetoTicks++;
        }
        scaffoldVetoCellKey = positionKey(placeAt.getX(), placeAt.getY(), placeAt.getZ());
    }

    /** Is a refusal episode running right now? Tolerates one tick of slack, because the executor and this process do
     *  not tick in a fixed order. */
    private boolean scaffoldVetoActive() {
        return scaffoldVetoTicks > 0 && scaffoldVetoLastTick >= buildTick - SCAFFOLD_VETO_EPISODE_GAP;
    }

    public void recordNavigationScaffold(BlockPos pos, BlockState before, BlockState after) {
        recordNavigationScaffold(pos, before, after, false);
    }

    public void recordNavigationScaffold(BlockPos pos, BlockState before, BlockState after,
                                         boolean excavationIntegrity) {
        if (isActive()) progressActions.arm(positionKey(pos), before, after);
        // BlockPlaceHelper calls here at local SUCCESS, before publishing its receipt. The escape's OWNED
        // phase alone transfers this one request to the ordinary ledger after both observations agree.
        BuilderCleanupDebt debt = cleanupEscapeDebt;
        if (cleanupEscape != null && debt != null && debt.episode == cleanupEscape
                && debt.world == ctx.world() && debt.pos.equals(pos)) return;
        if (!isActive() || buildInRows || temporarySupportTargets.containsKey(positionKey(pos))) {
            return;
        }
        if (excavating && excavationIntegrity && insideSnakeVolume(pos.getX(), pos.getY(), pos.getZ())) {
            excavationFluidPlugs.record(pos, before, after, excavationActiveClock.now());
        }
        // Exterior seals/supports must survive completion. Treating the roof seal as disposable navigation
        // scaffold made a completed excavation tunnel up its outside wall just to reopen its own roof.
        // Internal plugs still owe removal; ordinary navigation scaffolds retain their existing lifecycle.
        boolean retainedRepair = retainExcavationIntegrity(excavating, excavationIntegrity,
                insideSnakeVolume(pos.getX(), pos.getY(), pos.getZ()));
        if (navigationScaffolds.record(pos, before, after, templateNamesABlockAt(pos) || retainedRepair,
                Princeps.settings().acceptableThrowawayItems.value.contains(after.getBlock().asItem()), buildTick)) {
            logMechanic("SCAFFOLD-REQUEST " + pos.toShortString());
        }
    }

    static boolean retainExcavationIntegrity(boolean excavation, boolean explicitRepair, boolean insideSelection) {
        return excavation && explicitRepair && !insideSelection;
    }

    private boolean insideSnakeVolume(int x, int y, int z) {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        return full != null && origin != null
                && x >= origin.getX() && x < origin.getX() + full.widthX()
                && y >= origin.getY() && y < origin.getY() + full.heightY()
                && z >= origin.getZ() && z < origin.getZ() + full.lengthZ();
    }

    public void observeScaffoldServerChange(BlockPos pos, BlockState state) {
        excavationFluidPlugs.observe(pos, state);
        excavationRepairAim.observe(pos, state);
        long sequence = ++cleanupServerUpdateSequence;
        if (cleanupEscapeDebt != null) cleanupEscapeDebt.serverChanged(ctx.world(), pos, state, sequence, System.nanoTime());
        if (cleanupEscape != null) cleanupEscape.serverChanged(pos, state);
        if (isActive() && progressActions.serverChanged(positionKey(pos), state)) {
            confirmedProgressRevision++;
        }
        boolean wasOwned = navigationScaffolds.contains(pos);
        if (navigationScaffolds.serverChanged(pos, state)) {
            logMechanic("SCAFFOLD-OWNED server confirmed " + pos.toShortString());
        } else if (wasOwned && !navigationScaffolds.contains(pos)) {
            logMechanic("SCAFFOLD-RELEASE server confirmed " + pos.toShortString()
                    + " state=" + blockName(state));
        }
    }

    private List<BetterBlockPos> remainingNavigationScaffolds(BuilderCalculationContext bcc) {
        List<BetterBlockPos> remaining = new ArrayList<>();
        for (BlockPos pos : navigationScaffolds.positions()) {
            remaining.add(new BetterBlockPos(pos));
        }
        return remaining;
    }

    /** A local disappearance/replacement is not the server event that releases our ledger entry. */
    boolean scaffoldCleanupAwaitingServer() {
        if (!layerCleanupActive && !scaffoldCleanupActive) return false;
        for (long key : scaffoldCleanupTargets) {
            BlockPos pos = BlockPos.of(key);
            if (navigationScaffolds.contains(pos) && !navigationScaffolds.owns(pos, ctx.world().getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    /** Queue only this boundary's owned debt, without replacing the working layer by the full model. */
    boolean prepareLayerCleanup(BuilderCalculationContext bcc, boolean topDown) {
        if (excavating || buildInRows) return false;
        if (platformTraverseApproach != null) return true;
        scaffoldCleanupTargets.removeIf(key -> !navigationScaffolds.contains(BlockPos.of(key)));
        Set<Long> runnable = new HashSet<>();
        List<String> protectedHelpers = new ArrayList<>();
        boolean waiting = navigationScaffolds.awaitingServer();
        for (BlockPos pos : navigationScaffolds.positions()) {
            int localY = pos.getY() - origin.getY();
            if (!scaffoldCleanupTargets.contains(pos.asLong())
                    && (topDown ? localY < bandMinYLocal : localY > bandMaxYLocal)) continue;
            if (!bcc.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                waiting = true;
                continue;
            }
            BlockState current = bcc.bsi.get0(pos);
            if (!navigationScaffolds.owns(pos, current)) {
                // Includes Cobble -> WATER: wait for that exact server observation, never demand an AIR frame.
                scaffoldCleanupTargets.add(pos.asLong());
                waiting = true;
                continue;
            }
            String anchorDependency = layerCleanupAnchorDependency(pos, bcc);
            if (anchorDependency != null) {
                protectedHelpers.add(anchorDependency);
                continue;
            }
            BuilderSupportDependencies.Decision protection = bcc.supportDependencies.removal(pos);
            if (!protection.allowed()) {
                if (protection.reason() == BuilderSupportDependencies.Reason.UNKNOWN_WORLD) {
                    waiting = true;
                } else {
                    protectedHelpers.add(pos.toShortString() + ": " + protection.reason()
                            + " dependent=" + protection.dependent());
                }
                continue;
            }
            runnable.add(pos.asLong());
        }
        // A confirmed click anchor can be excluded from the navigation ledger while it serves its target.
        // Name a dependency on an unfinished later layer instead of waiting for that layer behind this gate.
        for (long key : temporarySupportTargets.keySet().toLongArray()) {
            BlockPos pos = BlockPos.of(key);
            int localY = pos.getY() - origin.getY();
            if ((topDown ? localY >= bandMinYLocal : localY <= bandMaxYLocal) && !bcc.bsi.get0(pos).isAir()) {
                String dependency = layerCleanupAnchorDependency(pos, bcc);
                if (dependency != null && !protectedHelpers.contains(dependency)) protectedHelpers.add(dependency);
            }
        }
        if (!runnable.isEmpty()) {
            layerCleanupActive = true;
            if (scaffoldCleanupTargets.addAll(runnable)) incorrectPositions = null;
            return true;
        }
        if (waiting) {
            layerCleanupActive = true;
            return true;
        }
        if (!protectedHelpers.isEmpty()) {
            abortBuild(Ending.LAYER_UNBUILDABLE,
                    "Layer " + layer + " cleanup needs a protected support; layer was not advanced", protectedHelpers);
            return true;
        }
        scaffoldCleanupTargets.clear();
        layerCleanupActive = false;
        return false;
    }

    private String layerCleanupAnchorDependency(BlockPos support, BuilderCalculationContext bcc) {
        long key = positionKey(support);
        if (!temporarySupportTargets.containsKey(key)) return null;
        BlockPos target = BlockPos.of(temporarySupportTargets.get(key));
        ISchematic full = supportModelForDependencies();
        int x = target.getX() - origin.getX(), y = target.getY() - origin.getY(), z = target.getZ() - origin.getZ();
        BlockState current = bcc.bsi.get0(target);
        if (bcc.bsi.worldContainsLoadedChunk(target.getX(), target.getZ())
                && full.inSchematic(x, y, z, current)
                // A click anchor is discharged by the promised placement identity. Neighbour/redstone changes
                // and a remaining interaction do not need another placement; orientation and waterlogging still do.
                && matchesExceptInteraction(current, full.desiredState(x, y, z, current, approxPlaceable))) {
            temporarySupportTargets.remove(key);
            return null;
        }
        return support.toShortString() + " is the click anchor for unfinished target " + target.toShortString();
    }

    /** The actual onTick layer transition; a local removal cannot bypass either the server ledger or S10. */
    boolean advanceLayerIfClean(BuilderCalculationContext bcc, boolean topDown, java.util.function.Consumer<String> log) {
        if (!verifyLayerBeforeLeaving(bcc, log) || prepareLayerCleanup(bcc, topDown)) return false;
        layer++;
        scaffoldsThisLayer = 0;
        scaffoldFailed.clear();
        scaffoldProbeCache.clear();
        return true;
    }

    private boolean verifyLayerBeforeLeaving(BuilderCalculationContext bcc, java.util.function.Consumer<String> log) {
        LayerAudit audit = auditLayer(bcc);
        log.accept("S10 " + audit.headline());
        if (audit.wrong().isEmpty()) return true;
        List<String> report = new ArrayList<>();
        report.add(audit.headline());
        report.add("The work set was empty and nothing is parked, so the builder believed this layer was"
                + " finished. It is not.");
        report.addAll(LayerAudit.name("missing or wrong", audit.wrong(), 12));
        if (audit.unverified() > 0) {
            report.add(audit.unverified() + " further cell(s) could not be checked (chunk not loaded);"
                    + " they are NOT counted as failures.");
        }
        abortBuild(Ending.LAYER_VERIFICATION_FAILED,
                "Build stopped: layer " + layer + " did not verify (" + audit.wrong().size()
                        + " cell(s) missing or wrong)", report);
        return false;
    }

    private boolean isScaffoldLeftBehind(int x, int y, int z, BuilderCalculationContext bcc) {
        BlockState state = bcc.bsi.get0(x, y, z);
        if (state.isAir()) {
            return false;
        }
        if (!Princeps.settings().acceptableThrowawayItems.value.contains(state.getBlock().asItem())) {
            return false;   // not scaffold material; leave it entirely alone
        }
        if (temporarySupportStillNeeded(x, y, z, bcc)) {
            // The old click-anchor experiment placed the right helper and this cleanup removed it before the target
            // could use it, producing hundreds of place/break cycles. A reserved helper is ordinary scaffold again the
            // instant its served target becomes correct; until then it is the click surface that breaks the deadlock.
            return false;
        }
        if (navigationScaffolds.owns(new BlockPos(x, y, z), state)) {
            return true;
        }
        int lx = x - origin.getX();
        int ly = y - origin.getY();
        int lz = z - origin.getZ();
        if (lx < 0 || ly < 0 || lz < 0
                || lx >= schematic.widthX() || ly >= schematic.heightY() || lz >= schematic.lengthZ()) {
            return false;   // outside the build: somebody else's block, and the bounds check matters because the
                            // schematic's cell keys pack coordinates and would wrap a negative into a false hit
        }
        // A cell the schematic wants a BLOCK in is the builder's business through the normal path, not this one. A
        // cell it wants AIR in is exactly where a helper block belongs while it is in use -- and exactly where one
        // must not be left behind.
        //
        // THIS USED TO READ `!schematic.inSchematic(...)`, AND THAT MADE THE WHOLE METHOD DEAD CODE. The default
        // ISchematic.inSchematic is a pure bounds test (`x >= 0 && x < widthX() && ...`), so it is true for every
        // cell inside the box -- including all the cells the template wants empty. The negation was therefore false
        // everywhere the bounds check above had already admitted, so isScaffoldLeftBehind never once returned true,
        // countScaffoldInLayer always counted zero, and the "layer done, but N scaffold block(s) are still standing"
        // warning could not fire. goal.md's criterion 7 -- no foreign block where the template wants air, all
        // cobblestone cleared before a run counts as finished -- had no instrument at all inside the build volume.
        //
        // Asked of the FULL schematic rather than the layer-scoped view, which was the sound half of the old code: a
        // block belonging to a higher layer must not be taken for spare scaffolding merely because its layer has not
        // come round yet. Only "the template wants nothing here" makes it the bot's mess to clear.
        BlockState wanted = schematic.desiredState(lx, ly, lz, state, this.approxPlaceable);
        return wanted == null || wanted.isAir();
    }

    /**
     * Fuehrt eine Frage so aus, als staende an {@code wo} bereits {@code was} -- und stellt die Welt danach wieder her.
     *
     * <h2>Warum beide Haelften, und warum das keine Kuer ist</h2>
     * <p>Der erste Anlauf hat die Annahme NUR der Wegfindung gegeben, ueber {@code BlockStateInterface.get0}. Das
     * war gemessen schlechter als gar keine Pruefung: 4315 -> 2752 Zellen. Der Grund ist, dass die eigentliche
     * Frage -- erzeugt der Klick den gewollten Block? -- durch vanillas {@code getPlacementState} gegen
     * {@code ctx.world()} laeuft. Dort stand der angenommene Block nicht, also fand die Pruefung fuer jeden Fall,
     * in dem der Hilfsblock die Klickflaeche IST, keinen Standplatz und verwarf ausgerechnet den guten Kandidaten.
     * Die Lampe ist genau so ein Fall. Eine Annahme, die nur eine Haelfte erreicht, ist keine halbe Wahrheit,
     * sondern eine falsche.
     *
     * <h2>Warum die Welt dafuer angefasst werden darf</h2>
     * <p>Dieselbe Datei tut es seit Langem mit dem KOERPER: {@code simulatePlacement} setzt Rotation und Position
     * des Spielers, fragt Vanilla und stellt beides im {@code finally} zurueck -- "setPos and not moveTo: no
     * rotation side effect, no collision resolution, no movement bookkeeping -- this is a question, not a step".
     * Der Block folgt derselben Regel. Es ist die Client-Welt, es geht kein Paket hinaus, der Server merkt nichts,
     * und die Ruecknahme steht im {@code finally}.
     *
     * <p>Die Flags sind bewusst eng: keine Nachbar-Benachrichtigung, kein Rendern, keine Drops. Eine Frage soll
     * nichts ausloesen, nicht einmal ein Aufblitzen.
     */
    private <T> T mitAngenommenemBlock(BlockPos wo, BlockState was, BuilderCalculationContext bcc,
                                       java.util.function.Supplier<T> frage) {
        BlockState vorher = ctx.world().getBlockState(wo);
        boolean gesetzt = false;
        try {
            bcc.bsi.nimmBlockAn(wo, was);
            gesetzt = ctx.world().setBlock(wo, was, ANNAHME_FLAGS);
            return frage.get();
        } catch (RuntimeException e) {
            return null;   // eine Frage darf den Bau nie anhalten
        } finally {
            bcc.bsi.vergissAnnahme();
            if (gesetzt) {
                ctx.world().setBlock(wo, vorher, ANNAHME_FLAGS);
            }
        }
    }

    /** UPDATE_INVISIBLE | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS -- ausdruecklich OHNE UPDATE_NEIGHBORS. */
    private static final int ANNAHME_FLAGS = 4 | 16 | 32;

    /** Das Soll an einer LOKALEN Bauplan-Koordinate, solange dort eine Geruestzelle lebt -- sonst {@code null}. */
    private BlockState scaffoldOverrideAt(int lx, int ly, int lz) {
        if (scaffoldCell == null || scaffoldWanted == null || origin == null) {
            return null;
        }
        return scaffoldCell.x - origin.getX() == lx
                && scaffoldCell.y - origin.getY() == ly
                && scaffoldCell.z - origin.getZ() == lz ? scaffoldWanted : null;
    }

    /**
     * Ist die Geruestzelle fertig mit ihrer Aufgabe? Dann faellt die Ueberschreibung -- und ab dem naechsten Tick
     * ist der Hilfsblock ein Block, der nicht in den Bauplan gehoert, den {@code isScaffoldLeftBehind} entfernt.
     *
     * <p>Der Abbau bekommt hier ausdruecklich KEINE eigene Maschinerie. Er ist schon da, er war nur nie ausgeloest.
     */
    private void scaffoldPhaseTick(BuilderCalculationContext bcc) {
        if (scaffoldCell == null) {
            return;
        }
        // EIN GERUEST, DAS SELBST SCHEITERT, WIRD AUFGEGEBEN -- sonst wartet der Bau auf etwas, das nicht kommt.
        //
        // GEMESSEN, Lauf 505618a0: "SCAFFOLD-OPEN 70,-60,90" bei T32111, dann "REPEAT FROZEN ... 120 Ticks:
        // searching" und am Ende CANCELLED statt eines Urteils. Die Geruestzelle war selbst nicht erreichbar, und
        // weil P6a nicht abbrechen darf, solange ein Geruest lebt, drehte sich der Bau bis zum Zeitablauf. Vorher
        // hatte er wenigstens ehrlich geurteilt; das ist keine Verbesserung, sondern eine Verschlechterung.
        //
        // Kein Timer, sondern dieselbe Frage wie ueberall: hat die Zelle ein Urteil bekommen? Wird die
        // Geruestzelle selbst geparkt -- keine Klickflaeche, keine Standposition, in keiner Bahn erreichbar --,
        // dann ist der Versuch beantwortet und P6a darf wieder urteilen.
        // ZWEI ARTEN ZU SCHEITERN, und die erste Fassung kannte nur eine. Eine Geruestzelle bekommt entweder ein
        // negatives Urteil -- dann ist sie geparkt --, oder sie bekommt UEBERHAUPT KEINES und bleibt unentschieden.
        // Der zweite Fall ist gemessen: Lauf 2a3a8436 endete mit OPEN=1, DONE=0, GIVEUP=0 und einem Haenger, weil
        // ihn keine Bedingung erfasste. Der Stillstandsmelder sieht diese Lage laengst und sagt FROZEN dazu; er
        // wurde nur nie gefragt. Das ist keine neue Uhr, sondern eine vorhandene Feststellung.
        boolean geparkt = isCellParked(scaffoldCell.x, scaffoldCell.y, scaffoldCell.z);
        // EINE NEUE Episode seit dem Eroeffnen, nicht der stehende Schalter. isFrozen() beschreibt einen Zustand
        // und bleibt stehen, solange der Bot an derselben Stelle dasselbe tut -- wer ihn als "mein Vorgang ist
        // gescheitert" liest, bekommt fuer jeden neuen Vorgang sofort dasselbe Ja. Lauf 78901334: sieben Versuche
        // hintereinander aufgegeben, jeweils EINEN Tick nach dem Eroeffnen, das Budget in zehn Ticks verbrannt,
        // und kein einziger hatte je eine Chance.
        boolean festgefahren = ActionJournal.frozenEpisodes() > scaffoldFrozenEpisodesAtOpen;
        if (geparkt || festgefahren) {
            BuildTrace.cell(buildTick, "SCAFFOLD-GIVEUP", scaffoldCell.x, scaffoldCell.y, scaffoldCell.z,
                    geparkt ? "die Geruestzelle wurde selbst geparkt; der Versuch ist beantwortet"
                            : "der Bau steht seit 120 Ticks an derselben Stelle; der Versuch ist gescheitert");
            temporarySupportTargets.remove(positionKey(scaffoldCell));
            // UND DIE BEDIENTE ZELLE MUSS ZURUECK IN DIE PARK-LISTE. openScaffoldPhase holt sie heraus, damit sie
            // waehrend des Versuchs wieder Arbeit sein kann. Scheitert der Versuch und bleibt sie draussen, dann
            // sieht P6a sie nicht mehr und kann nicht urteilen -- gebaut werden kann sie aber auch nicht. Sie
            // kreist, und der Bau endet im Zeitablauf statt mit einer Aussage.
            //
            // GEMESSEN, Lauf fd8d0f7c: GIVEUP=1, danach immer wieder "RELEASE 98,-59,98 ... RANGE: player is 27.2
            // blocks from redstone_lamp", bis CANCELLED. Das Leck ist meines, aus der Freigabe in openScaffoldPhase.
            scaffoldFailed.add(positionKey(scaffoldCell));
            if (scaffoldServes != null) {
                // Auch die bediente Zelle. Sonst versucht die Phase es mit dem naechsten Nachbarn weiter und
                // mauert sie am Ende zu -- siehe die Notiz an der Auswahl.
                scaffoldFailed.add(positionKey(scaffoldServes));
            }
            if (scaffoldServes != null) {
                parkCell(scaffoldServes, ParkReason.NO_FACE);
            }
            scaffoldCell = null;
            scaffoldServes = null;
            scaffoldWanted = null;
            return;
        }
        if (scaffoldServes != null && temporarySupportStillNeeded(
                scaffoldCell.x, scaffoldCell.y, scaffoldCell.z, bcc)) {
            return;   // die bediente Zelle steht noch aus
        }
        scaffoldProbeCache.clear();   // die Welt hat sich geaendert, alte Urteile gelten nicht mehr
        BuildTrace.cell(buildTick, "SCAFFOLD-DONE", scaffoldCell.x, scaffoldCell.y, scaffoldCell.z,
                "served=" + (scaffoldServes == null ? "-"
                        : scaffoldServes.x + "," + scaffoldServes.y + "," + scaffoldServes.z)
                        + "; Soll faellt auf Luft zurueck, der Abbau uebernimmt");
        scaffoldCell = null;
        scaffoldServes = null;
        scaffoldWanted = null;
    }

    /**
     * DER AUSLOESER DER PHASE. Aufgerufen erst, wenn die gewoehnliche Phase erschoepft ist -- kein Ziel mehr, und
     * PARK ist nicht leer. Sucht fuer die naechstgelegene geparkte Zelle eine Stelle, an der ein Hilfsblock ihr
     * eine Klickflaeche verschafft, und traegt sie als Soll ein.
     *
     * @return {@code true}, wenn eine Geruestzelle eroeffnet wurde -- dann hat der Bau wieder etwas zu tun
     */
    private BetterBlockPos scaffoldMaterialTarget;
    private long scaffoldMaterialWaitStarted;
    private Long scaffoldMaterialPausedAt;
    private long scaffoldMaterialAttemptTick = Long.MIN_VALUE;
    private static final long SCAFFOLD_MATERIAL_WAIT_TICKS = 120;

    /** True means a checked scaffold or its bounded normal material preparation owns this tick. */
    private boolean openScaffoldPhase(BuilderCalculationContext bcc) {
        if (buildInRows || scaffoldCell != null || parkedCells.isEmpty() || !bcc.hasThrowaway) return false;
        return openScaffoldPhase(bcc, throwawayBlockState(), this::prepareScaffoldMaterial);
    }

    boolean openScaffoldPhase(BuilderCalculationContext bcc, BlockState material,
                              java.util.function.BiPredicate<BetterBlockPos, BlockState> prepareMaterial) {
        return openScaffoldPhase(bcc, material, prepareMaterial, this::logMechanic);
    }

    boolean openScaffoldPhase(BuilderCalculationContext bcc, BlockState material,
                              java.util.function.BiPredicate<BetterBlockPos, BlockState> prepareMaterial,
                              java.util.function.Consumer<String> mechanics) {
        if (buildInRows || scaffoldCell != null || parkedCells.isEmpty() || !bcc.hasThrowaway) {
            return false;
        }
        if (scaffoldsThisLayer >= MAX_SCAFFOLDS_PER_LAYER) {
            // KEIN STILLER DECKEL. Zweimal hat diese Zahl den Bau gestoppt, ohne dass es irgendwo stand -- erst
            // bei acht, dann bei 32 --, und beide Male sah es von aussen aus wie "unbaubar" statt wie "Budget
            // alle". Wenn sie je wieder greift, steht sie im Trace.
            if (scaffoldsThisLayer == MAX_SCAFFOLDS_PER_LAYER) {
                scaffoldsThisLayer++;   // genau einmal melden
                BuildTrace.cell(buildTick, "SCAFFOLD-CAP", 0, 0, 0,
                        "Reissleine bei " + MAX_SCAFFOLDS_PER_LAYER + " Geruesten in Ebene " + layer
                                + "; ab hier wird keines mehr eroeffnet, obwohl noch " + parkedCells.size()
                                + " Zelle(n) geparkt sind");
            }
            return false;
        }
        if (material == null) {
            return false;
        }
        if (scaffoldMaterialTarget != null) {
            BlockState wanted = bcc.getSchematic(scaffoldMaterialTarget.x, scaffoldMaterialTarget.y,
                    scaffoldMaterialTarget.z, bcc.bsi.get0(scaffoldMaterialTarget));
            if (parkedCells.containsKey(positionKey(scaffoldMaterialTarget)) && wanted != null
                    && !wanted.isAir() && hotbarStackThatPlaces(wanted) == null) {
                return prepareMaterial.test(scaffoldMaterialTarget, wanted);
            }
            scaffoldMaterialTarget = null;
        }
        String missingDependency = null;
        BetterBlockPos feet = ctx.playerFeet();
        int wirkungsproben = 0;
        BetterBlockPos besteUngeprueft = null;
        BetterBlockPos bestesZielUngeprueft = null;
        double bestesMassUngeprueft = Double.MAX_VALUE;
        BetterBlockPos beste = null;
        BetterBlockPos bestesZiel = null;
        double bestesMass = Double.MAX_VALUE;
        for (ParkedCell parked : parkedCells.values()) {
            BetterBlockPos zelle = parked.pos;
            // EINER ZELLE, IN DIE LUFT GEHOERT, hilft keine Klickflaeche. Sie kann gar nicht Gegenstand dieser
            // Phase sein, und dass sie es war, ist ein Buchfuehrungsfehler weiter vorn: GEMESSEN im Lauf
            // f0aaafab bekam 90,-58,125 ZWEI Geruestversuche (90,-59,125 und 90,-58,124), obwohl dort nichts
            // hingehoert -- und sie zaehlte am Ende als "cell that could not be built" mit.
            BlockState sollHier = bcc.getSchematic(zelle.x, zelle.y, zelle.z, bcc.bsi.get0(zelle));
            if (sollHier == null || sollHier.isAir()) {
                continue;
            }
            String dependency = scaffoldSupportDiagnostic(bcc, zelle, sollHier, material);
            if (dependency != null) {
                if (missingDependency == null) missingDependency = dependency;
                continue;
            }
            // EIN VERSUCH JE ZELLE, NICHT EINER JE SEITE -- und das ist kein Sparen, sondern Schadensvermeidung.
            //
            // Das Gedaechtnis merkte sich bisher gescheiterte HILFSBLOCK-POSITIONEN, nicht gescheiterte ZELLEN.
            // Also durfte dieselbe Zelle es der Reihe nach mit jedem Nachbarn versuchen, und jeder gesetzte
            // Hilfsblock blieb stehen, bis die Aufraeumung ihn holte.
            //
            // GEMESSEN, Lauf f0aaafab, Observer 90,-58,91: vier Versuche auf allen vier Seiten -- 90,-58,92,
            // 89,-58,91, 91,-58,91, 90,-58,90. Der Eigentuemer hat im Spiel nachgesehen und es benannt: die Zelle
            // war am Ende ringsum verbaut, und dann ist ueberhaupt nichts mehr setzbar. Der Bau hat sich seine
            // eigene Zelle eingemauert; die vier Versuche haben sich gegenseitig die Loesung genommen.
            //
            // Dazu die Groessenordnung: 46 eroeffnete Geruestbloecke in einem Lauf sind weit mehr, als ein paar
            // haengende Zellen rechtfertigen. Der Anspruch ist der gleiche wie bei der Platzierung selbst -- es
            // muss beim ERSTEN Mal sitzen, und genau dafuer gibt es die Simulation.
            if (scaffoldFailed.contains(positionKey(zelle))) {
                continue;
            }
            for (Direction seite : SIX_NEIGHBOURS) {
                BetterBlockPos kandidat = new BetterBlockPos(
                        zelle.x + seite.getStepX(), zelle.y + seite.getStepY(), zelle.z + seite.getStepZ());
                if (scaffoldFailed.contains(positionKey(kandidat))) {
                    continue;   // hier ist es in dieser Ebene schon einmal gescheitert
                }
                if (!scaffoldCellIsAllowed(kandidat, bcc)) {
                    continue;
                }
                // DER HILFSBLOCK MUSS SELBST SETZBAR SEIN, sonst verschiebt er den Stillstand nur.
                //
                // GEMESSEN, Lauf c8dfa95f: die Phase feuerte fuer die Lampe an 98,-59,98 und waehlte 98,-58,98 --
                // den Block DARUEBER, weil er dem Bot naeher lag. Der haengt genauso in der Luft, und 481 Ticks
                // spaeter stand im Log "PARK 98,-58,98 reason=NO_FACE": das Geruest hatte sich selbst geparkt.
                // Ein Hilfsblock ohne eigene Klickflaeche ist kein Ausweg, sondern dieselbe Sackgasse einen Block
                // weiter.
                if (clickableFaceMask(kandidat.x, kandidat.y, kandidat.z) == 0L) {
                    continue;
                }
                // UNTEN ZUERST -- ABER NUR, WENN DIE ZELLE WIRKLICH IN DER LUFT HAENGT.
                //
                // Der Vorzug war richtig gedacht und zu grob gefasst: eine schwebende Zelle braucht einen Halt,
                // und der sitzt unter ihr. Eine Zelle, die auf festem Grund steht, braucht dagegen keinen Halt,
                // sondern eine ANLEGEFLAECHE daneben -- unter ihr ist kein Platz und nichts zu holen.
                //
                // GEMESSEN, Lauf f0aaafab: die drei Truhen bekamen ihren Hilfsblock ausnahmslos UNTER sich --
                // 92,-58,92 ueber 92,-59,92, 93,-58,92 ueber 93,-59,92, 104,-58,92 ueber 104,-59,92 -- und jeder
                // Versuch fuhr fest. Der Eigentuemer hatte die richtige Loesung vorher beschrieben: daneben
                // stehen und gegen die Nachbartruhe klicken. Dorthin kam die Regel nie, weil jeder Seitenplatz
                // eine Million hinter jedem Platz darunter rangierte.
                //
                // canSurvive trennt die beiden Faelle sauber: was ohne Unterlage nicht bestehen kann, haengt in
                // der Luft und wird von unten gestuetzt; alles andere wird von der Seite bedient.
                boolean haengtInDerLuft = false;
                BlockState sollZustand = bcc.getSchematic(zelle.x, zelle.y, zelle.z, bcc.bsi.get0(zelle));
                if (sollZustand != null) {
                    haengtInDerLuft = !sollZustand.canSurvive(ctx.world(), zelle);
                }
                double strafe = haengtInDerLuft
                        ? (seite == Direction.DOWN ? 0.0D : 1_000_000.0D)
                        : (seite == Direction.DOWN ? 1_000_000.0D : 0.0D);
                // DIE WIRKUNGSPRUEFUNG, VORERST IM SCHATTEN -- UND VOR DER BESTENAUSWAHL.
                //
                // Die Stelle ist der Punkt. Beim ersten Anlauf stand sie HINTER mass < bestesMass, lief also nur
                // fuer Kandidaten, die ohnehin schon die besten waren, und meldete 136 ja gegen 15 nein. Scharf
                // geschaltet prueft sie jeden -- auch die schlechten, die vorher nie zu ihr kamen -- und daraus
                // wurden 2 ja gegen 1197 nein. Die 90 Prozent waren die Trefferquote einer VORAUSWAHL, nicht die
                // der Pruefung, und die Entscheidung darauf kostete 4307 auf 2751 Zellen.
                //
                // Ein Schattenbetrieb misst nur dann das Richtige, wenn er GENAU dort steht, wo spaeter
                // entschieden wird. Steht er hinter einem Filter, misst er den Filter. Sie urteilt mit und waehlt noch nicht.
                //
                // Genau das habe ich beim ersten Anlauf falsch gemacht: direkt scharfgeschaltet statt mitlaufen
                // lassen -- und 4315 auf 2752 Zellen verloren, bevor es auffiel. Der Bahn-Treiber wurde in diesem
                // Repo richtig eingefuehrt: ueber tausend Sonden protokolliert, dann mit Zahlen entschieden.
                // Dieselbe Reihenfolge hier. Umgeschaltet wird, wenn der Zensus sagt, dass die Pruefung die
                // Kandidaten trifft, die auch wirklich tragen.
                // DAS URTEIL WIRD BEHALTEN. Ohne das verhungert die Suche: die Schleife laeuft jeden Tick von
                // vorn, verbraucht ihre zwoelf Proben an denselben ersten Kandidaten, und wenn die abgelehnt
                // werden, erreicht sie die guten NIE. Genau daran ist das Scharfschalten gescheitert -- 2 ja
                // gegen 1197 nein, waehrend dieselbe Pruefung im Schatten in 17 von 17 Faellen ja sagte, die
                // Lampe eingeschlossen. Es war nie die Pruefung, es war die Wiederholung.
                //
                // Mit dem Gedaechtnis kostet ein Kandidat EINE Probe, nicht eine je Tick. Das Budget begrenzt
                // damit die Arbeit und nicht mehr die Auswahl: was heute nicht mehr geprueft wird, ist morgen
                // dran, statt fuer immer hinten zu bleiben.
                // OHNE DEN BLOCK IN DER HAND KANN SIE DIE FRAGE NICHT BEANTWORTEN -- also darf sie auch nicht
                // ablehnen. placementStancesFor beginnt mit hotbarStackThatPlaces, und das sieht ausschliesslich
                // die NEUN Schnellleisten-Plaetze. Liegt der Sollblock gerade nicht dort, liefert die ganze
                // Standplatzsuche eine leere Liste -- fuer den echten Bau richtig, denn man kann nur setzen, was
                // in der Hand ist, fuer eine BAULICHE Frage aber falsch: der Bauer tauscht die Leiste staendig.
                //
                // GEMESSEN, Lauf c785623d, und es erklaert die ganze Unbestaendigkeit:
                //     98,-60,98 serves=98,-59,98 oeffnet=nein grund=no hotbar item places redstone_lamp
                // Der Hilfsblock der Lampe wurde nicht aus geometrischen Gruenden verworfen, sondern weil in
                // genau diesem Moment keine Lampe in der Schnellleiste lag. Derselbe Hilfsblock, den dieselbe
                // Pruefung im Schattenlauf siebzehnmal bestaetigt hat.
                //
                // Damit ist es derselbe Fehler, der sich durch den ganzen Tag zieht: "konnte nicht feststellen"
                // wird als "nein" gelesen. Es gibt drei Antworten, nicht zwei -- und die dritte darf nichts
                // entscheiden und nichts merken.
                if (hotbarStackThatPlaces(sollHier) == null) {
                    // No material for the oracle means preparation is missing, not that a helper is useful.
                    // Remember one demand, fetch it normally, then evaluate the same unchanged world again.
                    BuildTrace.cell(buildTick, "SCAFFOLD-PROBE", kandidat.x, kandidat.y, kandidat.z,
                            "serves=" + zelle.x + "," + zelle.y + "," + zelle.z
                                    + " oeffnet=unentschieden (" + blockName(sollHier)
                                    + " liegt gerade nicht in der Schnellleiste)");
                    double massU = kandidat.distanceSq(feet) + strafe;
                    if (massU < bestesMassUngeprueft) {
                        bestesMassUngeprueft = massU;
                        besteUngeprueft = kandidat;
                        bestesZielUngeprueft = zelle;
                    }
                    continue;
                }
                long probenSchluessel = positionKey(kandidat);
                Boolean urteil = scaffoldProbeCache.get(probenSchluessel);
                if (urteil == null) {
                    if (wirkungsproben >= MAX_SCAFFOLD_SIMULATIONEN_JE_TICK) {
                        continue;   // nicht abgelehnt -- nur vertagt
                    }
                    wirkungsproben++;
                    Goal danach = mitAngenommenemBlock(kandidat, material, bcc,
                            () -> standingGoalFor(zelle.x, zelle.y, zelle.z, sollHier, bcc));
                    urteil = danach != null;
                    scaffoldProbeCache.put(probenSchluessel, urteil);
                    if (urteil) {
                        wirkungJa++;
                    } else {
                        wirkungNein++;
                    }
                    // UND BEI EINEM NEIN AUCH DEN GRUND. Ich habe dreimal geraten, warum die Pruefung ablehnt,
                    // und dreimal danebengelegen -- erst sah sie nur die halbe Welt, dann stand sie hinter dem
                    // Filter, zuletzt wurde sie nur dort gemessen, wo es ohnehin laeuft. Die Standplatzsuche kann
                    // selbst sagen, woran es scheitert; stanceRejectionSummary gibt es seit Langem und niemand
                    // hat es an dieser Stelle gefragt.
                    //
                    // Der Vergleich, auf den es ankommt, sind die BEKANNT GUTEN Faelle: die Lampe ueber den Block
                    // darunter und die zwanzig Observer mit ihrem Hilfsblock daneben sind heute nachweislich so
                    // gebaut worden. Sagt die Pruefung dort nein, steht in dieser Zeile, welche Bedingung sie zu
                    // viel verlangt -- und dann ist es kein Raten mehr.
                    String warum = urteil ? "" : " grund=" + mitAngenommenemBlock(kandidat, material, bcc,
                            () -> stanceRejectionSummary(zelle.x, zelle.y, zelle.z, sollHier, bcc));
                    BuildTrace.cell(buildTick, "SCAFFOLD-PROBE", kandidat.x, kandidat.y, kandidat.z,
                            "serves=" + zelle.x + "," + zelle.y + "," + zelle.z
                                    + " oeffnet=" + (urteil ? "ja" : "nein") + warum);
                }
                // WIEDER IM SCHATTEN, und das ist eine Produktentscheidung, keine Kapitulation.
                //
                // GEMESSEN, drei Laeufe scharf gegen fuenf ohne:
                //   ohne Pruefung: 4307 4312 4315 4320 4321 Zellen -- praktisch deckungsgleich, ~40 Geruestbloecke
                //                  bei 72 bis 82 Prozent Erfolg
                //   mit Pruefung:  2749 3857 4310 Zellen, 0 / 6 / 38 Geruestbloecke, aber 43 von 44 sitzen (98 %)
                //
                // Sie macht die Hilfsbloecke also praeziser als je zuvor UND das Ergebnis schlechter: der Median
                // faellt, und die Streuung verdreifacht sich. Lauf 1 zeigt warum -- fuenf Proben, alle fuenf
                // abgelehnt, kein Geruest. Dieselbe Pruefung, die im Schatten bei der Lampe siebzehnmal ja sagte,
                // sagt dort nein. Ihr Urteil haengt am Weltzustand, und der ist von Lauf zu Lauf verschieden.
                //
                // Etwas, das im Mittel verschlechtert und die Streuung verdreifacht, gehoert nicht in den
                // Auslieferungsstand -- auch wenn die Idee richtig ist und die Praezision beeindruckt. Was fehlt,
                // ist nicht Mut, sondern die Erklaerung, WARUM dasselbe Urteil ueber denselben Hilfsblock
                // zwischen zwei Laeufen kippt. Solange die fehlt, misst sie mit und entscheidet nicht.
                if (WIRKUNGSPRUEFUNG_ENTSCHEIDET && !urteil) {
                    continue;   // er wuerde die Zelle nicht oeffnen -- also wird er nicht gesetzt
                }
                double mass = kandidat.distanceSq(feet) + strafe;
                if (mass >= bestesMass) {
                    continue;
                }
                bestesMass = mass;
                beste = kandidat;
                bestesZiel = zelle;
            }
        }
        if (beste == null && besteUngeprueft != null) {
            return prepareMaterial.test(bestesZielUngeprueft, bcc.getSchematic(
                    bestesZielUngeprueft.x, bestesZielUngeprueft.y, bestesZielUngeprueft.z,
                    bcc.bsi.get0(bestesZielUngeprueft)));
        }
        if (beste == null) {
            if (missingDependency != null) {
                abortBuild(Ending.LAYER_UNBUILDABLE, "Build stopped: " + missingDependency,
                        List.of("No helper is authorized while the required survival dependency is missing or unknown."));
                return true;
            }
            return false;
        }
        scaffoldMaterialTarget = null;
        scaffoldCell = beste;
        scaffoldServes = bestesZiel;
        scaffoldWanted = material;
        scaffoldsThisLayer++;
        scaffoldFrozenEpisodesAtOpen = ActionJournal.frozenEpisodes();
        // DER ARBEITSSATZ MUSS NEU GELESEN WERDEN, sonst ist die Geruestzelle nur ein Soll, das niemand sieht.
        //
        // recalc rechnet nur dann vollstaendig, wenn incorrectPositions null ist; sonst sieht recalcNearby
        // ausschliesslich in die Naehe des Bots. Die Geruestzelle liegt aber regelmaessig weit weg -- das ist ja
        // der Grund, warum die bediente Zelle bisher niemand erreicht hat.
        //
        // GEMESSEN, Lauf 1f6cca89: "SCAFFOLD-OPEN 98,-60,98 serves=98,-59,98" bei T30759, also genau der
        // Stuetzblock unter der Lampe -- und in den folgenden 482 Ticks stand der Bot auf
        // pos=73.950,-58.000,87.505, unveraendert bis zur letzten Stelle, rund 25 Bloecke entfernt. Er ist nie
        // losgelaufen, weil es fuer ihn nichts zu tun gab: das Soll war gesetzt, der Arbeitssatz kannte es nicht.
        //
        // Auf null setzen erzwingt die Vollberechnung im naechsten Tick. Das ist kein Trick, sondern die
        // Buchfuehrung: der Bauplan hat sich geaendert, also gilt der daraus abgeleitete Arbeitssatz nicht mehr.
        incorrectPositions = null;
        temporarySupportTargets.put(positionKey(beste), positionKey(bestesZiel));
        // Die bediente Zelle muss zurueck in den Arbeitssatz, sonst wartet der Hilfsblock auf eine Zelle, die
        // niemand mehr ansieht. Freigegeben wird genau so, wie der Waechter es tut -- aus der PARK-Liste heraus
        // und zurueck in die Menge; wo sie landet, entscheidet wieder der Abstand.
        parkedCells.remove(positionKey(bestesZiel));
        activeCells.add(bestesZiel);
        cellsWokenByWatchman++;
        BuildTrace.cell(buildTick, "SCAFFOLD-OPEN", beste.x, beste.y, beste.z,
                "serves=" + bestesZiel.x + "," + bestesZiel.y + "," + bestesZiel.z
                        + " material=" + blockName(material)
                        + " (" + scaffoldsThisLayer + "/" + MAX_SCAFFOLDS_PER_LAYER + " in dieser Ebene)");
        mechanics.accept("Nothing else is placeable and " + parkedCells.size() + " cell(s) are parked; placing one"
                + " temporary support at " + beste.x + "," + beste.y + "," + beste.z
                + " to open " + bestesZiel.x + "," + bestesZiel.y + "," + bestesZiel.z
                + ". It is removed again the moment that cell stands.");
        return true;
    }

    String scaffoldSupportDiagnostic(BuilderCalculationContext bcc, BlockPos target,
                                     BlockState wanted, BlockState material) {
        ISchematic full = supportModelForDependencies();
        try {
            BlockPos support = new BuilderSupportDependencies(full, origin, bcc.placeable, bcc.bsi, ctx.world())
                    .missingExternalSupport(target, wanted, material);
            return support == null ? null : "missing permanent support outside the schematic at "
                    + support.toShortString() + " for " + blockName(wanted) + " at " + target.toShortString();
        } catch (BuilderSupportDependencies.UnknownWorld ignored) {
            return "support dependency is unknown because its block view is unavailable at " + target.toShortString();
        }
    }

    private boolean prepareScaffoldMaterial(BetterBlockPos target, BlockState wanted) {
        return prepareScaffoldMaterial(target, wanted, Princeps.settings().allowInventory.value, slot -> {
            princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            return princeps.getInventoryBehavior().attemptToPutOnHotbar(slot, candidate -> false);
        });
    }

    boolean prepareScaffoldMaterial(BetterBlockPos target, BlockState wanted, boolean inventoryAllowed,
                                   java.util.function.IntPredicate requestSwap) {
        progressHold = true; // Preserve the real cancellation through the existing route-continuation wrapper.
        if (!target.equals(scaffoldMaterialTarget)) {
            scaffoldMaterialTarget = target;
            scaffoldMaterialWaitStarted = buildTick;
            scaffoldMaterialPausedAt = null;
            scaffoldMaterialAttemptTick = Long.MIN_VALUE;
            BuildTrace.cell(buildTick, "SCAFFOLD-WAIT-MATERIAL", target.x, target.y, target.z,
                    "prepare " + blockName(wanted) + " before evaluating a helper; no helper authorized");
        }
        if (buildTick - scaffoldMaterialWaitStarted >= SCAFFOLD_MATERIAL_WAIT_TICKS
                || !inventoryAllowed) {
            abortBuild(Ending.MATERIALS_MISSING, "Build stopped: material is not ready on the hotbar for "
                    + blockName(wanted) + " at " + target.toShortString(),
                    List.of("No unverified scaffold was placed. Prepare the material before restarting."));
            return true;
        }
        if (scaffoldMaterialAttemptTick != buildTick) {
            scaffoldMaterialAttemptTick = buildTick;
            for (int i = 9; i < Math.min(36, approxPlaceable.size()); i++) {
                if (itemCanPlaceBlock(approxPlaceable.get(i), wanted)) {
                    requestSwap.test(i);
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Darf hier ein Hilfsblock stehen? Die Regel des Eigentuemers, unveraendert: NUR wo der Bauplan ohnehin Luft
     * vorsieht, damit er keiner spaeteren Platzierung nach Plan im Weg steht.
     */
    private boolean scaffoldCellIsAllowed(BetterBlockPos kandidat, BuilderCalculationContext bcc) {
        if (origin == null || schematic == null) {
            return false;
        }
        int lx = kandidat.x - origin.getX();
        int ly = kandidat.y - origin.getY();
        int lz = kandidat.z - origin.getZ();
        if (lx < 0 || ly < 0 || lz < 0
                || lx >= schematic.widthX() || ly >= schematic.heightY() || lz >= schematic.lengthZ()) {
            return false;   // ausserhalb des Bauplans wird nichts gesetzt, auch nichts Temporaeres
        }
        BlockState jetzt = bcc.bsi.get0(kandidat);
        if (!jetzt.isAir()) {
            return false;   // nur unberuehrte Luft; nichts wird ueberbaut
        }
        BlockState geplant = bcc.getSchematic(kandidat.x, kandidat.y, kandidat.z, jetzt);
        return geplant == null || geplant.isAir();
    }

    /** Der erste Wegwerfblock aus dem, was gerade setzbar ist. */
    private BlockState throwawayBlockState() {
        if (approxPlaceable == null) {
            return null;
        }
        for (BlockState state : approxPlaceable) {
            if (state != null && !state.isAir()
                    && Princeps.settings().acceptableThrowawayItems.value.contains(state.getBlock().asItem())) {
                return state;
            }
        }
        return null;
    }

    private boolean temporarySupportStillNeeded(int x, int y, int z, BuilderCalculationContext bcc) {
        long supportKey = positionKey(x, y, z);
        if (!temporarySupportTargets.containsKey(supportKey)) {
            return false;
        }
        BlockPos target = BlockPos.of(temporarySupportTargets.get(supportKey));
        BlockState current = bcc.bsi.get0(target);
        BlockState wanted = bcc.getSchematic(target.getX(), target.getY(), target.getZ(), current);
        if (wanted == null) {
            return true;    // fail closed across a transient layer-mask/recalculation change; never pull live support
        }
        if (!valid(current, wanted, false)) {
            return true;
        }
        temporarySupportTargets.remove(supportKey);
        BuildTrace.cell(buildTick, "SUPPORT-RELEASE", x, y, z,
                "served=" + target.getX() + "," + target.getY() + "," + target.getZ());
        return false;
    }

    /** Scaffold blocks still standing in the rows a just-finished layer covers, so the log can name what was missed. */
    // ==========================================================================================================
    // DAS EBENENBAND. EINE Formel, zwei Felder -- und der Grund, warum das ein eigener Abschnitt ist.
    //
    // Vor dieser Zusammenlegung leiteten VIER Stellen dasselbe Band unabhaengig voneinander her: die Maske in
    // onTick, countScaffoldInLayer, die Ebenen-Verifikation und der Client. Drei davon rechneten bedingungslos
    // von unten nach oben -- countScaffoldInLayer nachweislich, es stand woertlich so da. Bei layerOrder=true
    // benennen sie damit die Zeilen am falschen Ende der Schematic.
    //
    // Warum das gefaehrlicher ist als es klingt: driften die Maske und die Verifikation um EINE Zeile, dann
    // prueft S10 ein Band, das nie gebaut wurde, und P6b bricht auf korrekter Arbeit ab -- mit
    // LAYER_VERIFICATION_FAILED, also ausgerechnet der Meldung, die am wenigsten nach Rechenfehler aussieht.
    // Deshalb kommt das Band ab jetzt aus genau EINER Methode, sie ist statisch und weltfrei, und
    // BuilderLayerBandTest ruft GENAU DIESE Methode auf statt eine Kopie der Formel zu pruefen.

    /** Inclusive local-y interval a layer covers. {@code lo > hi} means the layer is empty -- layer 0, or past the top. */
    record LayerBand(int lo, int hi) {
        boolean isEmpty() {
            return lo > hi;
        }

        int size() {
            return isEmpty() ? 0 : hi - lo + 1;
        }
    }

    /**
     * THE definition of the layer band, for both build directions.
     *
     * @param height      schematic height in blocks
     * @param layerHeight rows per layer; values {@code <= 0} behave as 1
     * @param layer       the process's own layer counter -- 1 is the first layer, 0 is "nothing yet"
     * @param topDown     {@code settings().layerOrder}: true builds from the top row downwards
     */
    static LayerBand layerBand(int height, int layerHeight, int layer, boolean topDown) {
        int h = Math.max(1, layerHeight);
        int lo;
        int hi;
        if (topDown) {
            lo = height - layer * h;
            hi = height - 1 - (layer - 1) * h;
        } else {
            lo = (layer - 1) * h;
            hi = layer * h - 1;
        }
        return new LayerBand(Math.max(0, lo), Math.min(height - 1, hi));
    }

    /** How many layers a schematic of this height is cut into. Same clamp on {@code layerHeight} as the band. */
    static int layerCount(int height, int layerHeight) {
        return (height + Math.max(1, layerHeight) - 1) / Math.max(1, layerHeight);
    }

    private int effectiveLayerHeight() {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        return effectiveLayerHeight(Princeps.settings().layerHeight.value, excavating,
                Princeps.settings().layerOrder.value, effectiveAreaBreakSize(), full == null ? 0 : full.heightY());
    }

    /** Ordinary excavation needs room for feet and head inside its active band, even without an area tool. */
    static int effectiveLayerHeight(int requested, boolean excavation, boolean topDown, int areaSize, int height) {
        int configured = Math.max(1, requested);
        // A complete one-high selection remains a surface: its existing open headroom may be used, but a fixed
        // outside roof may not be mined. The last short band of a taller job has the cleared preceding band above.
        return excavation && topDown && areaSize == 1 && height >= 2 ? Math.max(2, configured) : configured;
    }

    /** The band of the layer currently being worked, in LOCAL schematic y. Recomputed once per tick, in onTick. */
    private int bandMinYLocal;
    private int bandMaxYLocal = -1;

    /**
     * Does the schematic mask show ONLY the current layer, or the current layer plus everything below it?
     *
     * <p>KEIN SCHALTER, SONDERN EINE UMBAUSTUFE. Die Spezifikation kennt nur den exklusiven Fall, und der andere
     * Zweig wird mit dem Schritt geloescht, der ihn abschaltet. Er steht hier nur, weil die exklusive Maske erst
     * gefahren werden darf, wenn P6a/P6b scharf sind: ohne sie ist der Ausgang aus einer unfertigen Ebene offen,
     * und eine ausgelassene Zelle koennte dann nie wiederkommen, weil sie ausserhalb der Maske gar nicht mehr
     * existiert. Genau in dieser Reihenfolge steht es in autonomy/BAU-V04-REIHENFOLGE.md, Schritte 19 und 22.
     */
    private static final boolean EXCLUSIVE_LAYER_MASK = false;

    private int countScaffoldInLayer(BuilderCalculationContext bcc, int finishedLayer) {
        // Liest die zwei Felder statt selbst zu rechnen -- das war die dritte der vier Herleitungen, und die
        // einzige, bei der die Fehlrichtung schon im Quelltext stand.
        int loLocal = bandMinYLocal;
        int hiLocal = bandMaxYLocal;
        int found = 0;
        for (int ly = loLocal; ly <= hiLocal; ly++) {
            for (int lz = 0; lz < schematic.lengthZ(); lz++) {
                for (int lx = 0; lx < schematic.widthX(); lx++) {
                    if (isScaffoldLeftBehind(lx + origin.getX(), ly + origin.getY(), lz + origin.getZ(), bcc)) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    /**
     * S10 als Wert: was steht in dieser Ebene wirklich?
     *
     * <p>Die Spezifikation verlangt vor jedem Ebenenwechsel eine Verifikation -- "Prueft, ob alle Zellen der
     * Ebene E gesetzt, am richtigen Ort und richtig orientiert sind" -- und bei einem Nein den Abbruch mit
     * Report. Bis hierher gab es beides nicht: eine Ebene galt als fertig, sobald der Arbeitssatz leer war, und
     * der Arbeitssatz wird auch leer, wenn Zellen still verschwunden sind. Ein basalt-Lauf hat auf diese Weise
     * 245 Zellen ueber drei Ebenen verloren, von denen 175 in KEINER Logzeile vorkamen.
     *
     * <p>Getrennt gezaehlt wird falsch gegen geparkt, und das ist der ganze Unterschied zwischen den beiden
     * Abbruchgruenden: geparkte Zellen sind P6a (der Algorithmus WEISS, dass er sie nicht bauen konnte, und
     * warum), falsche sind P6b (er hielt sie fuer fertig und sie sind es nicht). Die zweite Sorte ist die
     * schlimmere, weil sie bedeutet, dass die Buchfuehrung luegt.
     *
     * <p>Liest {@code realSchematic}, nicht die Maske: die Maske ist waehrend der kumulativen Stufe absichtlich
     * groesser als das Band, und geprueft wird genau das Band.
     */
    private LayerAudit auditLayer(BuilderCalculationContext bcc) {
        List<BetterBlockPos> wrong = new ArrayList<>();
        List<BetterBlockPos> parked = new ArrayList<>();
        int cells = 0;
        int correct = 0;
        int unverified = 0;
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (full != null && origin != null) {
            for (int ly = bandMinYLocal; ly <= bandMaxYLocal; ly++) {
                for (int lz = 0; lz < full.lengthZ(); lz++) {
                    for (int lx = 0; lx < full.widthX(); lx++) {
                        int x = lx + origin.getX();
                        int y = ly + origin.getY();
                        int z = lz + origin.getZ();
                        BlockState current = bcc.bsi.get0(x, y, z);
                        if (!full.inSchematic(lx, ly, lz, current)) {
                            continue;
                        }
                        BlockState desired = full.desiredState(lx, ly, lz, current, approxPlaceable);
                        // DIESELBE Frage wie S0b sie stellt. Wuerde die Verifikation eine andere Zellenmenge
                        // pruefen als die Mikro-Schleife gebaut hat, meldete sie Fehler auf korrekter Arbeit.
                        if (desired == null || notACellOfItsOwn(desired, x, y, z, bcc)) {
                            continue;
                        }
                        cells++;
                        // NICHT GELADEN IST NICHT FALSCH, und diese Unterscheidung ist der Unterschied zwischen
                        // einer brauchbaren Verifikation und einer, die jeden korrekten Bau abbricht. Der
                        // BlockStateInterface liefert fuer einen nicht geladenen Chunk einen Ersatzzustand, nicht
                        // die Wahrheit -- und ein Zipfel der Ebene liegt bei jeder groesseren Schematic ausserhalb
                        // der Renderdistanz. Als "falsch" gezaehlt waere das LAYER_VERIFICATION_FAILED auf
                        // einwandfreier Arbeit. Was frueher einmal korrekt GESEHEN wurde, zaehlt weiter.
                        if (!bcc.bsi.worldContainsLoadedChunk(x, z)) {
                            if (observedCompleted != null && observedCompleted.contains(positionKey(x, y, z))) {
                                correct++;
                            } else {
                                unverified++;
                            }
                            continue;
                        }
                        if (valid(current, desired, false)
                                || isPendingChestPairHalf(current, desired, x, y, z, bcc)) {
                            correct++;
                        } else if (isCellParked(x, y, z)) {
                            parked.add(new BetterBlockPos(x, y, z));
                        } else {
                            wrong.add(new BetterBlockPos(x, y, z));
                        }
                    }
                }
            }
        }
        return new LayerAudit(layer, bandMinYLocal, bandMaxYLocal, cells, correct, unverified, wrong, parked);
    }

    /** What a layer actually looks like after the micro loop has run over it. See {@link #auditLayer}. */
    record LayerAudit(int layer, int loLocal, int hiLocal, int cells, int correct, int unverified,
                      List<BetterBlockPos> wrong, List<BetterBlockPos> parked) {

        boolean isClean() {
            return wrong.isEmpty() && parked.isEmpty();
        }

        String headline() {
            return "layer " + layer + " (local y " + loLocal + ".." + hiLocal + "): " + cells + " cell(s), "
                    + correct + " correct, " + wrong.size() + " wrong, " + parked.size() + " parked, "
                    + unverified + " unverified (chunk not loaded)";
        }

        /** At most {@code max} coordinates, then a count. A layer can have two thousand of these. */
        static List<String> name(String what, List<BetterBlockPos> cells, int max) {
            List<String> lines = new ArrayList<>();
            if (cells.isEmpty()) {
                return lines;
            }
            StringBuilder sb = new StringBuilder(what).append(": ");
            for (int i = 0; i < Math.min(max, cells.size()); i++) {
                BetterBlockPos p = cells.get(i);
                sb.append(i == 0 ? "" : "  ").append(p.x).append(',').append(p.y).append(',').append(p.z);
            }
            if (cells.size() > max) {
                sb.append("  ... and ").append(cells.size() - max).append(" more");
            }
            lines.add(sb.toString());
            return lines;
        }
    }

    /**
     * Would filling this head-height cell leave the bot with no horizontal way out?
     *
     * <p>Required before the head-height rule could be narrowed, and it closes a gap no existing guard covers.
     * Feet free, head free, all four horizontal head cells solid is a 1x1x1 pocket: the bot can neither walk nor
     * jump out of it, and the only escape left is breaking a block it just correctly placed -- possible, but priced
     * at {@code breakCorrectBlockPenaltyMultiplier}, so in practice the layer stalls.
     *
     * <p>Why the three guards that look like they should cover it do not:
     * <ul>
     *   <li>{@code blocksActivePath} consults {@code activePathCells()}, which is {@code emptySet()} whenever no
     *       PathExecutor is alive -- and a block is placed while STANDING. On exactly the ticks this can happen,
     *       that guard is inactive.</li>
     *   <li>{@code wouldSealPendingHangingNeighbour} is about support faces for hanging blocks; it never looks at
     *       the bot.</li>
     *   <li>{@code plannedRank} is explicitly a preference and "never a licence to place nothing".</li>
     * </ul>
     *
     * <p>Cost is constant, not proportional: only a candidate within Chebyshev distance 1 of the bot's own column
     * can be one of its four exits, so this is at most four {@code bsi} lookups and it short-circuits on the first
     * exit that stays open. Measured over five windows: under 15 integer operations per tick, three orders of
     * magnitude below the ~3.1 extra {@code possibleToPlace} calls the narrowed rule costs anyway.
     *
     * <p><b>Kept because it is free, NOT because it is proven.</b> Across those five runs it fired 0 / 80 / 60 / 2 / 1
     * times, and the run where it fired zero times (2605 placed) is indistinguishable from the one where it fired
     * eighty (2650). Not one of the 143 activations has been shown to prevent a stall. The paragraph above says what
     * the gap IS; it does not say that closing it was measured to help, and it must not be cited as if it did.
     * The 0-to-80 spread on otherwise identical code is worth its own note: it is one more demonstration that a
     * single run shows nothing.
     */
    private boolean wouldSealTheBotsOwnHeadHeightExit(BetterBlockPos feet, int x, int y, int z,
                                                      BuilderCalculationContext bcc) {
        if (Math.abs(x - feet.x) > 1 || Math.abs(z - feet.z) > 1) {
            return false;   // too far away to be one of the bot's own exits
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = feet.x + d.getStepX();
            int nz = feet.z + d.getStepZ();
            if (nx == x && nz == z) {
                continue;   // the one that is about to be filled
            }
            if (MovementHelper.isReplaceable(nx, y, nz, bcc.bsi.get0(nx, y, nz), bcc.bsi)) {
                return false;   // at least one way out stays open
            }
        }
        return true;
    }

    /**
     * Traegt der laufende Weg diese Zelle noch -- steht er auf ihr oder fuehrt er durch sie hindurch?
     *
     * <p>Ein Block, den die Navigation gerade als Tritt gesetzt hat, ist kein Ueberbleibsel, solange sie ihn noch
     * braucht. Er gehoert nicht in den Bauplan und sieht deshalb aus wie Muell; der Unterschied liegt nicht im
     * Block, sondern im Zeitpunkt.
     */
    private boolean pathStillStandsOn(int x, int y, int z) {
        IPathExecutor laufend = princeps.getPathingBehavior().getCurrent();
        if (laufend == null || laufend.getPath() == null) {
            return false;
        }
        for (BetterBlockPos schritt : laufend.getPath().positions()) {
            if (schritt.x != x || schritt.z != z) {
                continue;
            }
            if (schritt.y == y + 1 || schritt.y == y) {
                return true;   // er steht darauf, oder er laeuft hindurch
            }
        }
        return false;
    }

    private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = princeps.getPathingBehavior().pathStart();
        int areaSize = effectiveAreaBreakSize();
        List<long[]> ranked = areaSize > 1 ? new ArrayList<>() : Collections.emptyList();
        int bandTop = areaSize > 1 ? areaHighestUnfinishedY(bcc) : Integer.MIN_VALUE;
        if (areaSize > 1) {
            ensureSerpentineRoute(bandTop);
        }
        // THE SNAKE OWNS THE AREA-TOOL MODE. When it has a head, that head IS the answer: one swing clears the whole
        // slice, so there is nothing to rank and nothing to search. The scan below stays exactly as it was for
        // every other mode -- an ordinary pickaxe never reaches this branch.
        BetterBlockPos snakeCell = snakeUpdate(bcc, areaSize, bandTop);
        // A route can become obstructed AFTER A* calculated it. This is the general form of the gravel failure:
        // any falling, piston-moved or server/mod-created block can appear in the next body column while the snake is
        // still aiming at a farther 3x3 head. Movement then asks for an ordinary tool, the head asks for the Shard,
        // and the two correct local decisions fight forever. Preflight the exact cardinal route before either tool is
        // selected and make its nearest obstruction the single-block cleanup target. Cursor and turn state remain
        // untouched; once the path is clear, the original slice resumes on the following tick.
        Optional<Rotation> ordinaryCleanupRotation = excavating && snakeCleanupActive && !snakeCleanupWithAreaTool
                ? snakeOrdinaryCleanupRotation(snakeCleanupTarget, bcc) : Optional.empty();
        // A reachable committed cut owns the action. An observed corridor obstruction may preempt it only after
        // that hit is no longer reachable; excavation paths themselves are deliberately forbidden to mine.
        Optional<BetterBlockPos> routeObstruction = ordinaryCleanupRotation.isPresent()
                ? Optional.empty() : snakeRouteObstruction(bcc);
        if (routeObstruction.isPresent() && !snakeCleanupCutAllowed(routeObstruction.get(), bcc)) {
            return Optional.empty();
        }
        if (routeObstruction.isPresent()) {
            ordinaryCleanupRotation = Optional.empty();
            snakeCleanupTarget = routeObstruction.get();
            snakeCleanupActive = true;
            // Prefer a true single-block tool. If production inventory has none, retain the area tool's exact
            // face-owned rotation instead of handing it a generic ray that could rotate the 3x3 plane.
            snakeCleanupWithAreaTool = snakeCleanupMustUseArea(snakeCleanupTarget, bcc);
            snakeCell = snakeCleanupTarget;
            snakeDiagnosis = "t=" + buildTick + " Snake clears refilled route cell="
                    + snakeCleanupTarget.x + "," + snakeCleanupTarget.y + "," + snakeCleanupTarget.z
                    + " before head=" + (snakeHead == null ? "none"
                            : snakeHead.x + "," + snakeHead.y + "," + snakeHead.z);
        }
        if (buildTick % 40 == 0) { logMechanic("PROBE C tick=" + buildTick + " areaSize=" + areaSize
                + " bandTop=" + bandTop
                + " cell=" + (snakeCell == null ? "none" : snakeCell.x + "," + snakeCell.y + "," + snakeCell.z)
                + " head=" + (snakeHead == null ? "none" : snakeHead.x + "," + snakeHead.y + "," + snakeHead.z)
                + " stance=" + (snakeStance == null ? "none" : snakeStance.x + "," + snakeStance.y + "," + snakeStance.z)
                + " face=" + snakeExpectedFace() + " entering=" + snakeEntering + " cleanup=" + snakeCleanupActive
                + " ready=" + snakeReadyToSwing()
                + " rot=" + snakeHeadRotation().isPresent()); }
        // DIAGNOSTIC (added while investigating the horizontal-head stall), every 20 build ticks.
        if (areaSize > 1 && (snakeProbeLastTick == Long.MIN_VALUE || buildTick - snakeProbeLastTick >= 20)) {
            snakeProbeLastTick = buildTick;
            Optional<Rotation> probeRot = snakeHead == null ? Optional.empty() : snakeHeadRotation();
            logMechanic("SNAKEPROBE cell="
                    + (snakeCell == null ? "none" : snakeCell.x + "," + snakeCell.y + "," + snakeCell.z)
                    + " head=" + (snakeHead == null ? "none" : snakeHead.x + "," + snakeHead.y + "," + snakeHead.z)
                    + " stance=" + (snakeStance == null ? "none"
                            : snakeStance.x + "," + snakeStance.y + "," + snakeStance.z)
                    + " entering=" + snakeEntering + " cleanup=" + snakeCleanupActive
                    + " face=" + snakeExpectedFace() + " bandTop=" + bandTop
                    + " feet=" + ctx.playerFeet().x + "," + ctx.playerFeet().y + "," + ctx.playerFeet().z
                    + " ready=" + snakeReadyToSwing()
                    + " headNeedsClear=" + (snakeHead != null && snakeCellNeedsClear(snakeHead, bcc))
                    + " rot=" + (probeRot.isEmpty() ? "empty(" + snakeRotWhy + ")" : probeRot.get().toString())
                    + " live=" + (snakeCell == null ? "n/a" : String.valueOf(snakeLiveHitMatches(snakeCell)))
                    + " mouseOver=" + describeMouseOver());
        }
        if (snakeHead != null) {
            // A null cell with a non-null head is deliberate: the entry shaft has selected its block, but the bot
            // must reach the exact centre above it before it is allowed to swing. Falling through the shaft also
            // spends a few ticks off the ground. In both cases the snake still owns target selection; letting the
            // generic scan run here would mine an unrelated reachable cell and destroy the deterministic route.
            if (snakeCell != null) {
                Optional<Rotation> targetRotation = snakeCleanupActive
                        ? (snakeCleanupWithAreaTool
                                ? snakeCleanupAreaRotation(snakeCell)
                                : ordinaryCleanupRotation.isPresent() ? ordinaryCleanupRotation
                                        : snakeOrdinaryCleanupRotation(snakeCell, bcc))
                        : snakeHeadRotation();
                if (targetRotation.isPresent()) {
                    return Optional.of(new Tuple<>(snakeCell, targetRotation.get()));
                }
            }
            return Optional.empty();
        }
        int bandFloor = bandTop == Integer.MIN_VALUE ? Integer.MIN_VALUE : bandTop - (areaSize - 1);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = breakScanMinDy(buildInRows, Princeps.settings().breakFromAbove.value); dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && standsOn(x, z, pathStart)
                            && (!buildInRows || rowOwnSupportColumn(x, z, center, pathStart))) {
                        traceRowBreakSkip(x, y, z, "protected support column");
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    // UND NICHTS, WORAUF DER LAUFENDE WEG NOCH TRITT. Die Zeile darueber schuetzt genau EINE
                    // Zelle -- die unter dem Wegbeginn --, und das ist zu wenig: der Bauer greift fuenf Bloecke
                    // weit, der Weg reicht weiter, und ein Trittblock zwei Schritte voraus gehoert nicht in den
                    // Bauplan. Also reisst der Bauer ab, was die Navigation gerade gesetzt hat, und die setzt ihn
                    // wieder.
                    //
                    // GEMESSEN ueber fuenf Laeufe: 189 bis 284 solcher Setz-/Abriss-Zyklen je Lauf, in jedem
                    // einzelnen. Einmal ist es davongelaufen -- Lauf ef41435b, 511 Abrisse bei nur 1012 Zellen,
                    // also die sechsfache Rate, und am Ende drehte sich alles im Kreis: "SCAFFOLD 118,-60,107
                    // ready to place by=ascend" gegen "BREAK 121,-60,110 had=cobblestone", viermal in zwanzig
                    // Ticks, dann Abbruch. Der Bau kam auf ein Drittel der ueblichen Strecke.
                    if (pathStillStandsOn(x, y, z)) {
                        traceRowBreakSkip(x, y, z, "remaining route still uses this column");
                        continue;
                    }
                    // AND NOTHING THAT LEAVES THE BOT ON AN ISLAND. The check above only knows about a route that
                    // already exists; a bot standing still and correcting the cells around it has none, and it
                    // will happily mine away every way off its own block. See breakWouldStrandTheBot.
                    if (breakWouldStrandTheBot(x, y, z, center, bcc)) {
                        traceRowBreakSkip(x, y, z, "it is the last footing connecting the bot to the rest");
                        continue;
                    }
                    // Apply the sliding window before consulting the layer-scoped schematic. Outside the current
                    // mask, getSchematic returns null and an old scaffold-cleanup branch would otherwise bypass it.
                    if (bandTop != Integer.MIN_VALUE && (y > bandTop || y < bandFloor)) {
                        continue;
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        // Nothing wanted here -- but a scaffold block the bot laid to stand on is still its mess to
                        // clear. Picked up opportunistically whenever the bot happens to be near one, which costs no
                        // extra travel at all: it is already standing there.
                        if (isScaffoldLeftBehind(x, y, z, bcc) && !isCellParked(x, y, z)) {
                            BetterBlockPos scaffold = new BetterBlockPos(x, y, z);
                            Optional<Rotation> rot = RotationUtils.reachableForWork(ctx, scaffold,
                                    ctx.playerController().getBlockReachDistance(), false);
                            if (rot.isPresent()) {
                                return Optional.of(new Tuple<>(scaffold, rot.get()));
                            }
                        }
                        continue;
                    }
                    if (isCellParked(x, y, z)) {
                        continue;
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    boolean fluidOnly = excavating ? snakeTreatAsFluid(curr, true)
                            : curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA;
                    if (!(curr.getBlock() instanceof AirBlock) && !fluidOnly && !valid(curr, desired, false)
                            && !isPendingChestPairHalf(curr, desired, x, y, z, bcc)
                            && interactionClicks(curr, desired) < 0) {
                        // interactionClicks >= 0 means the block is right, only its open/delay/mode/note is off — that's
                        // fixed by a right-click (the interaction pass), NEVER by breaking it back to air and re-placing.
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (!excavationMayMinePlug(pos, true)) continue;
                        if (areaSize <= 1) {
                            Optional<Rotation> rot = RotationUtils.reachableForWork(ctx, pos,
                                    ctx.playerController().getBlockReachDistance(), false);
                            if (rot.isPresent()) {
                                return Optional.of(new Tuple<>(pos, rot.get()));
                            }
                            traceRowBreakSkip(x, y, z, "no unobstructed mining ray from current feet");
                            continue;
                        }
                        if (areaBreakWouldCutOwnFooting(pos, pathStart, center, areaSize)) {
                            continue;
                        }
                        long distance = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                        ranked.add(new long[]{serpentineOrder(pos),
                                serpentineCentre(pos, bandTop, areaSize) ? 0 : 1, distance, x, y, z});
                    }
                }
            }
        }
        ranked.sort((a, b) -> {
            if (a[0] != b[0]) return Long.compare(a[0], b[0]);
            if (a[1] != b[1]) return Long.compare(a[1], b[1]);
            return Long.compare(a[2], b[2]);
        });
        for (long[] entry : ranked) {
            BetterBlockPos pos = new BetterBlockPos((int) entry[3], (int) entry[4], (int) entry[5]);
            Optional<Rotation> rot = RotationUtils.reachableForWork(ctx, pos,
                    ctx.playerController().getBlockReachDistance(), false);
            if (rot.isPresent()) {
                return Optional.of(new Tuple<>(pos, rot.get()));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean templatePlacementIsLicensedAt(BlockPos placeAt) {
        if (!buildInRows) {
            return true;
        }
        if (placeAt == null || rowActiveBandStart == Integer.MIN_VALUE
                || rowActiveFrontier == Integer.MIN_VALUE) {
            return false;
        }
        boolean sweepX = sweepAlongX();
        int row = sweepX ? placeAt.getX() : placeAt.getZ();
        int along = sweepX ? placeAt.getZ() : placeAt.getX();
        return rowBandContains(row, rowActiveBandStart, BAND_ROWS) && along == rowActiveFrontier;
    }

    @Override
    public void setAreaToolDisplayName(String displayName) {
        // ONE OWNER FOR THE NAME. Automatic tool selection has to refuse this pickaxe too (ToolSet), so the answer
        // to "is this the area tool" cannot live privately in the builder -- two copies would drift the moment a
        // user renames it, and the drift shows up as a bot that quietly digs its own footing away again.
        AreaTool.setName(displayName);
    }

    @Override
    public boolean isAreaBreakCleanupActive() {
        return snakeCleanupActive;
    }

    private void traceRowBreakSkip(int x, int y, int z, String reason) {
        if (buildInRows && buildTick % 120 == 0) {
            BuildTrace.cell(buildTick, "ROW-BREAK-SKIP", x, y, z, reason);
        }
    }

    static int breakScanMinDy(boolean rowBuild, boolean genericBreakFromAbove) {
        // Map Art lives exactly one block below the player's feet. Its replacement pass therefore has to inspect
        // dy=-1 even when the user's generic builder setting deliberately disables breaking from above.
        return rowBuild || genericBreakFromAbove ? -1 : 0;
    }

    /** Whether the player's current footprint or the route start is carried by this column. */
    private boolean standsOn(int x, int z, BetterBlockPos pathStart) {
        if (x == pathStart.x && z == pathStart.z) {
            return true;
        }
        double halfWidth = ctx.player().getBbWidth() / 2.0D;
        return x >= Mth.floor(ctx.player().getX() - halfWidth)
                && x <= Mth.floor(ctx.player().getX() + halfWidth)
                && z >= Mth.floor(ctx.player().getZ() - halfWidth)
                && z <= Mth.floor(ctx.player().getZ() + halfWidth);
    }

    static boolean rowOwnSupportColumn(int x, int z, BetterBlockPos feet, BetterBlockPos pathStart) {
        // At a block edge the 0.6-wide player box can overlap the neighbour by a few hundredths even though the
        // centre and the active path are carried by the finished pixel under the player. Treating that toe overlap as
        // footing made the first real 128x128 image unable to replace its starter stone. The row build may clear such
        // an adjacent pixel; it still never clears the centre column or the column supporting the current route.
        return (x == feet.x && z == feet.z) || (x == pathStart.x && z == pathStart.z);
    }

    private boolean areaBreakWouldCutOwnFooting(BetterBlockPos pos, BetterBlockPos pathStart,
                                                 BetterBlockPos feet, int size) {
        // THE ONE DELIBERATE EXCEPTION. Cutting into a fresh band means taking the three layers under your own
        // feet and dropping onto the tunnel floor -- three blocks, onto ground that is still solid because the
        // band stops there. Refusing it is what left the bot standing on top of the field with twelve cells mined
        // and nowhere to go. Only the snake's own head qualifies, and only while it is cutting in.
        if (snakeEntering && snakeHead != null && pos.equals(snakeHead)) {
            return false;
        }
        int arm = (size - 1) / 2;
        if (Math.abs(pos.y - (feet.y - 1)) > arm) {
            return false;
        }
        double dx = pos.x + 0.5D - ctx.player().getX();
        double dz = pos.z + 0.5D - ctx.player().getZ();
        boolean spreadAlongX = Math.abs(dz) >= Math.abs(dx);
        for (int side = -arm; side <= arm; side++) {
            int x = pos.x + (spreadAlongX ? side : 0);
            int z = pos.z + (spreadAlongX ? 0 : side);
            if (standsOn(x, z, pathStart)) {
                return true;
            }
        }
        return false;
    }

    /** How far around the feet the escape check looks. Eleven cells across, the same reach the break scan has. */
    private static final int STRAND_SCAN_RADIUS = 5;

    /** Two seconds of the hotbar failing to serve one cell's material before the cell is put back in the queue. */
    private static final int WRONG_ITEM_PATIENCE_TICKS = 40;

    /** Break-branch ticks with nothing changing in the world before the rest of the tick is let through. */
    private static final int BREAK_BRANCH_STARVATION_TICKS = 120;

    /** How long the break branch stands down once it has proven it is achieving nothing. */
    private static final int BREAK_BRANCH_YIELD_TICKS = 100;

    private final BreakBranchProgress breakBranchProgress =
            new BreakBranchProgress(BREAK_BRANCH_STARVATION_TICKS, BREAK_BRANCH_YIELD_TICKS);
    private final BreakTargetObservation breakTargetObservation = new BreakTargetObservation();

    /** Consecutive ticks a committed placement has been held back because the hand held the wrong block. */
    private int wrongItemInHandTicks;

    /**
     * Whether taking this cell out would leave the bot standing on an island of its own making.
     *
     * <p>MEASURED, map-art repair run: correcting a resumed picture means BREAKING every pixel that landed in the
     * wrong colour, and the bot did that to the cells all around itself until the block under its feet was the only
     * one left. With no footing in any direction there was no route anywhere, and it went off the edge into the
     * water. Everything it had left to fix was still standing, perfectly reachable, on the other side of a gap it
     * had dug itself.
     *
     * <p>Two guards existed and neither could see it. {@code pathStillStandsOn} protects the columns the CURRENT
     * route walks over -- and a bot that is standing still, breaking cells within arm's reach, has no route, so it
     * protects nothing. {@link #areaBreakWouldCutOwnFooting} is the real footing check, and it is only consulted
     * when {@code areaBreakSize > 1}; map art breaks one cell at a time, so that branch is never reached.
     *
     * <p>This one is unconditional and local. Only the standing plane can strand anything, so a cell at any other
     * height returns immediately; the cell directly under the body is refused outright; and for the rest, a flood
     * fill over the footing around the feet -- with the candidate already treated as gone -- asks whether anything
     * walkable still connects the feet to the edge of the window. Reaching the edge means a way out exists and the
     * break is safe. It costs at most an eleven-by-eleven fill, and only for candidates in the one plane that can
     * possibly matter.
     *
     * <p>Deliberately a LOCAL question. "Can it still reach every remaining cell of the picture" is the honest one
     * and it is far too expensive to ask per candidate per tick; "is it still connected to anything at all beyond
     * arm's reach" costs nothing and rules out the only failure that has actually happened.
     */
    private boolean breakWouldStrandTheBot(int x, int y, int z, BetterBlockPos feet, BuilderCalculationContext bcc) {
        int planeY = feet.y - 1;
        if (y != planeY) {
            return false; // nothing above or below the standing plane can take the footing away
        }
        if (!MovementHelper.canWalkOn(bcc.bsi, feet.x, planeY, feet.z)) {
            return false; // the body is not standing on this plane, so there is no footing here to protect
        }
        if (x == feet.x && z == feet.z) {
            return true; // never mine the block holding the body up
        }

        final int span = STRAND_SCAN_RADIUS * 2 + 1;
        boolean[] seen = new boolean[span * span];
        int[] queue = new int[span * span];
        int head = 0;
        int tail = 0;
        int startIndex = STRAND_SCAN_RADIUS * span + STRAND_SCAN_RADIUS;
        seen[startIndex] = true;
        queue[tail++] = startIndex;
        while (head < tail) {
            int index = queue[head++];
            int localX = index % span;
            int localZ = index / span;
            if (localX == 0 || localZ == 0 || localX == span - 1 || localZ == span - 1) {
                return false; // the footing still reaches past arm's reach, so there is a way off it
            }
            for (int step = 0; step < 4; step++) {
                int nextLocalX = localX + (step == 0 ? 1 : step == 1 ? -1 : 0);
                int nextLocalZ = localZ + (step == 2 ? 1 : step == 3 ? -1 : 0);
                int nextIndex = nextLocalZ * span + nextLocalX;
                if (seen[nextIndex]) {
                    continue;
                }
                int worldX = feet.x + nextLocalX - STRAND_SCAN_RADIUS;
                int worldZ = feet.z + nextLocalZ - STRAND_SCAN_RADIUS;
                if (worldX == x && worldZ == z) {
                    continue; // the cell under consideration is treated as already gone
                }
                if (!MovementHelper.canWalkOn(bcc.bsi, worldX, planeY, worldZ)) {
                    continue;
                }
                seen[nextIndex] = true;
                queue[tail++] = nextIndex;
            }
        }
        return true; // the fill died inside the window: breaking this would close the last way out
    }

    private boolean areaBreakBelowBand(int x, int y, int z) {
        AreaBand band = areaBand;
        return band != null && band.forbids(x, y, z);
    }

    private int effectiveAreaBreakSize() {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        return effectiveAreaBreakSize(Princeps.settings().areaBreakSize.value, excavating,
                full == null ? 0 : full.widthX(), full == null ? 0 : full.heightY(), full == null ? 0 : full.lengthZ());
    }

    static int effectiveAreaBreakSize(int requested, boolean excavation, int width, int completeHeight, int length) {
        // A one-high complete job has no in-volume horizontal Shard head. Ordinary clearing can work the open
        // surface without inventing a head above the selection or cutting an outside ceiling. A short LAST band
        // of a taller job retains its snake route because the already-cleared band provides headroom above it.
        return ShallowExcavationPolicy.surfaceMode(excavation, completeHeight)
                ? 1 : effectiveAreaBreakSize(requested, excavation, width, length);
    }

    static int effectiveAreaBreakSize(int requested, boolean excavation, int width, int length) {
        // A three-wide snake has no in-volume centre line in a one/two-wide selection. Use ordinary clearing with
        // the shared excavation repair policy; the user's area setting and construction jobs stay intact.
        if (excavation && (width < SERPENTINE_LANE_WIDTH || length < SERPENTINE_LANE_WIDTH)) {
            return 1;
        }
        return Math.max(1, requested);
    }

    /** One immutable publication prevents the pathing thread from observing half of a new band. */
    private static final class AreaBand {
        private final int minX, minZ, maxX, maxZ, minY, floorY;

        private AreaBand(int minX, int minZ, int maxX, int maxZ, int minY, int floorY) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.minY = minY;
            this.floorY = floorY;
        }

        private boolean forbids(int x, int y, int z) {
            return y < floorY && y >= minY && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private int areaHighestUnfinishedY(BuilderCalculationContext bcc) {
        // The SAME fallback assemble() uses a few hundred lines up, and leaving it out here quietly disabled the
        // whole band. `realSchematic` is only ever assigned on the BUILD path; a clear job -- which is what
        // AutoDig's Shard-Pickaxe mode is -- leaves it null forever, so this returned MIN_VALUE on every tick, the
        // three-layer window was never found, and target choice fell back to "nearest to the player". That is
        // exactly the scattered digging the owner described as "really not good".
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (origin == null || full == null) {
            return Integer.MIN_VALUE;
        }
        // A band that has been selected is COMMITTED even after its last ordinary work block disappears. The empty
        // state is exactly when gravity settling and the physical verification walk must run; treating it as a cache
        // miss used to select the next lower Y here and begin descending before that gate could ever execute.
        if (areaCommittedBandTop != Integer.MIN_VALUE) {
            areaBandTopCache = areaCommittedBandTop;
            publishAreaBand(full, areaCommittedBandTop);
            return areaCommittedBandTop;
        }
        if (areaBandTopCache != Integer.MIN_VALUE && areaLayerHasWork(areaBandTopCache, full, bcc)) {
            areaCommittedBandTop = areaBandTopCache;
            publishAreaBand(full, areaBandTopCache);
            return areaBandTopCache;
        }
        for (int y = origin.getY() + full.heightY() - 1; y >= origin.getY(); y--) {
            if (areaLayerHasWork(y, full, bcc)) {
                areaBandTopCache = y;
                areaCommittedBandTop = y;
                publishAreaBand(full, y);
                return y;
            }
        }
        areaBandTopCache = Integer.MIN_VALUE;
        areaBand = null;
        return Integer.MIN_VALUE;
    }

    private void publishAreaBand(ISchematic full, int bandTop) {
        int size = effectiveAreaBreakSize();
        if (size <= 1 || origin == null) {
            areaBand = null;
            return;
        }
        areaBand = new AreaBand(origin.getX(), origin.getZ(),
                origin.getX() + full.widthX() - 1, origin.getZ() + full.lengthZ() - 1,
                origin.getY(), bandTop - (size - 1));
    }

    private boolean areaLayerHasWork(int y, ISchematic full, BuilderCalculationContext bcc) {
        int localY = y - origin.getY();
        if (localY < 0 || localY >= full.heightY()) {
            return false;
        }
        for (int dx = 0; dx < full.widthX(); dx++) {
            for (int dz = 0; dz < full.lengthZ(); dz++) {
                BlockState current = bcc.bsi.get0(origin.getX() + dx, y, origin.getZ() + dz);
                if (current.isAir() || !full.inSchematic(dx, localY, dz, current)) {
                    continue;
                }
                BlockState desired = full.desiredState(dx, localY, dz, current, approxPlaceable);
                if (desired != null && desired.isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drives the area-tool mode as a TUNNEL HEAD instead of a collection of single cells.
     *
     * <p>The per-cell approach cannot work in solid ground, and the bench showed exactly why: the builder asks
     * "walk next to cell X, then break it", and in a solid cube almost every cell is BURIED -- there is no stance
     * beside it, so the path calculation fails. Only the surface is approachable, the bot is standing on that
     * surface, and a three-wide swing there would cut the ground from under its own feet, which the footing guard
     * rightly refuses. Twelve cells at the rim, then deadlock -- measured identically with and without the ordering
     * change, which is what proved the ordering was never the problem.
     *
     * <p>The owner's principle removes the contradiction instead of patching it. The bot never stands on what it
     * swings at: it stands on the BOTTOM layer of the three-layer band, in the tunnel it has just cut, and swings
     * HORIZONTALLY at head height one step ahead. One swing clears the full slice -- three wide, three tall -- and
     * the cell it just emptied is where it steps next. A goal that is guaranteed to exist, because it made it.
     *
     * <p>Everything else follows: lanes three wide because that is the swing's width, reversing at each lane end so
     * no walk is ever a return trip, and dropping three layers when the band is empty. The ordering is no longer a
     * sort that another rule can outvote -- it is the movement itself.
     *
     * @return the cell to aim at, or {@code null} while the snake is walking/settling or the band is finished.
     */
    private BetterBlockPos snakeUpdate(BuilderCalculationContext bcc, int areaSize, int bandTop) {
        snakeHead = null;
        snakeStance = null;
        snakeEntering = false;
        snakeTurning = false;
        // THE SWING MEMORY DOES NOT BELONG HERE. snakeUpdate runs every tick, and clearing the three fields that
        // remember the last face and count the still ticks at the top of it means they are always zero: every
        // swing then looks like a face change, the settled counter can never reach four, the cap can never reach
        // thirty, and the gate below is therefore never satisfied. The bot walks to its start and stands there.
        //
        // Placed here by a careless edit that pattern-matched on the line above and hit two call sites instead of
        // one. They are reset with the rest of the per-build state, where they were always meant to be.
        snakeCleanupTarget = null;
        snakeCleanupActive = false;
        snakeCleanupWithAreaTool = false;
        snakeCleanupAreaFace = null;
        snakeRequiresOrdinaryTool = false;
        snakeSliceFace = null;
        snakeTransit = false;
        if (!snakeVerificationActive) {
            snakeVerifiedBandTop = Integer.MIN_VALUE;
        }
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (areaSize < SERPENTINE_LANE_WIDTH || full == null || origin == null
                || bandTop == Integer.MIN_VALUE || ctx.player() == null) {
            return null;
        }
        // Excavation applies this before the single-block/Shard split. Preserve the existing construction-side
        // area-tool behaviour here without making ordinary construction acquire the excavation profile.
        if (!excavating) enforceAutoDigLookProfile();
        int minX = origin.getX();
        int maxX = minX + full.widthX() - 1;
        int minZ = origin.getZ();
        int maxZ = minZ + full.lengthZ() - 1;
        int bandFloor = Math.max(origin.getY(), bandTop - (SERPENTINE_LANE_WIDTH - 1));
        snakeBandFloor = bandFloor;
        // THE SWING'S 3x3 STANDS UPRIGHT, so its footprint spans headY-1..headY+1 -- and bandFloor is the plane
        // the bot is standing on. bandFloor was clamped to the build; the head was not, and that asymmetry is the
        // whole bug: a band shorter than three, which every cube whose height is not a multiple of three ends on,
        // puts the head ON the floor and sends the swing one layer BELOW the build.
        //
        // Measured on the 20-cube, 19.08.: the last band is two tall (-60..-59), the head landed on -60, and four
        // swings removed twelve blocks at y=-61 -- the ground itself. The bot was left marooned on a three-wide
        // ledge with 385 cells it could no longer reach, and reported "missing floor" against its own digging.
        // The 15-cube never showed it because 15 divides by three and its bands are never short.
        int headY = Math.max(bandTop - 1, bandFloor + 1);

        // A NEW BAND STARTS WHERE THE BOT ALREADY IS. Sending it back to the original corner for every layer would
        // be a full crossing of the field with nothing mined on the way; the snake carries on downward instead.
        if (snakeBandTop != bandTop) {
            snakeBandTop = bandTop;
            snakeRouteComplete = false;
            snakeLane = 0;
            snakeTurnCross = Integer.MIN_VALUE;
            snakeTurnTravel = Integer.MIN_VALUE;
            snakeTurnComplete = false;
            snakeLastLoggedLane = Integer.MIN_VALUE;
            int feetX = ctx.playerFeet().x;
            int feetZ = ctx.playerFeet().z;
            // Lanes across the shorter side: fewer turns, longer straight runs.
            snakeCrossIsX = (maxX - minX) <= (maxZ - minZ);
            int crossMin = snakeCrossIsX ? minX : minZ;
            int crossMax = snakeCrossIsX ? maxX : maxZ;
            int travelMin = snakeCrossIsX ? minZ : minX;
            int travelMax = snakeCrossIsX ? maxZ : maxX;
            int feetCross = snakeCrossIsX ? feetX : feetZ;
            int feetTravel = snakeCrossIsX ? feetZ : feetX;
            boolean crossFromMin = Math.abs(feetCross - crossMin) <= Math.abs(feetCross - crossMax);
            boolean travelFromMin = Math.abs(feetTravel - travelMin) <= Math.abs(feetTravel - travelMax);
            snakeStartCross = crossFromMin ? crossMin : crossMax;
            snakeStartTravel = travelFromMin ? travelMin : travelMax;
            snakeCrossStep = crossFromMin ? 1 : -1;
            snakeStep = travelFromMin ? 1 : -1;
            snakeCursor = snakeStartTravel;
        }

        int crossMin = snakeCrossIsX ? minX : minZ;
        int crossMax = snakeCrossIsX ? maxX : maxZ;
        int travelMin = snakeCrossIsX ? minZ : minX;
        int travelMax = snakeCrossIsX ? maxZ : maxX;

        // Every coordinate is visited, including a slice that is already clear. That is the route invariant: an
        // empty slice is a corridor to walk, not permission to ask A* for a remote stance and let it choose a detour.
        for (int guard = 0; guard < 8192; guard++) {
            int rawCentre = snakeStartCross + snakeCrossStep * (snakeLane * SERPENTINE_LANE_WIDTH + 1);
            if (rawCentre < crossMin - 1 || rawCentre > crossMax + 1) {
                snakeRouteComplete = true;
                snakeWhy("band reports every lane walked, lane=" + snakeLane
                        + " rawCentre=" + rawCentre + " cross=" + crossMin + ".." + crossMax, bcc);
                return null; // every lane of this band has been walked
            }
            // Clamped so the swing always stays inside the field; a last lane narrower than three simply overlaps
            // the one before it, and re-clearing air costs nothing.
            int laneCentre = Math.max(crossMin + 1, Math.min(crossMax - 1, rawCentre));
            boolean forward = (snakeLane & 1) == 0 ? snakeStep > 0 : snakeStep < 0;
            int direction = forward ? 1 : -1;
            if (snakeCursor < travelMin || snakeCursor > travelMax) {
                snakeLane++;
                snakeTurnCross = Integer.MIN_VALUE;
                snakeTurnTravel = Integer.MIN_VALUE;
                snakeTurnComplete = false;
                boolean nextForward = (snakeLane & 1) == 0 ? snakeStep > 0 : snakeStep < 0;
                snakeCursor = nextForward ? travelMin : travelMax;
                continue;
            }
            // Enter vertically through a 3x3 shaft one cell in from both edges. A Shard swing is keyed to the clicked
            // face: UP clears one horizontal layer, so each swing drops exactly one block and can never touch the
            // support below this band's floor. Once feet reach the floor, the same state machine turns horizontal.
            if (ctx.playerFeet().y > bandFloor) {
                int entryTravel = Math.max(travelMin + 1, Math.min(travelMax - 1, snakeCursor));
                int entryX = snakeCrossIsX ? laneCentre : entryTravel;
                int entryZ = snakeCrossIsX ? entryTravel : laneCentre;
                snakeEntering = true;
                snakeStance = new BetterBlockPos(entryX, ctx.playerFeet().y, entryZ);
                snakeHead = snakeStance.below();
                // THE TOOL IS CHOSEN HERE, BEFORE THE READINESS GATE, and that ordering is the whole fix.
                //
                // An UP swing of the area tool clears a horizontal 3x3, so digging down takes the block the bot
                // stands on together with the eight around it: it drops into a hole with no floor under the
                // landing and never regains onGround, which snakeReadyToSwing requires. An ordinary pickaxe takes
                // exactly one block -- an ordinary one-block drop that lands immediately.
                //
                // Choosing it in the break branch (where cleanup chooses its own) cannot work and was measured
                // not working: that branch is only reached once toBreakNearPlayer returns a target, which needs
                // snakeSelectedSliceTarget, which needs snakeReadyToSwing, which needs the onGround that the wrong
                // tool has just destroyed. The selection sat behind the condition it exists to make satisfiable --
                // "tool changes: 0" for a whole run. Planning time has no such circle.
                if (!snakeToolReady(bcc.get(snakeHead), true)) {
                    // A RETURN THAT LEAVES NO LINE BEHIND IS A RETURN NOBODY CAN SEE. snakeDiagnosis is what the
                    // client copies into every row of its trace file, so a null that skips it does not read as
                    // "waiting for a tool" -- it reads as the previous verdict, repeated with the tick stamp it
                    // was made at, for as long as the wait lasts. The owner's 20.08. trace froze on t=1132 for
                    // 339 rows exactly here, and the whole investigation went looking for an engine that had
                    // stopped ticking. It had not stopped for a single tick.
                    snakeWhy("waiting for the ordinary pickaxe before cutting in, wanted hotbar "
                            + (snakeToolWantedSlot + 1) + ", held hotbar "
                            + (ctx.player().getInventory().getSelectedSlot() + 1)
                            + ", waited " + snakeToolWaitTicks + " tick(s)", bcc);
                    return null; // one tick for the slot change to reach the server before the first swing
                }
                return snakeSelectedSliceTarget(bcc);
            }

            // TURN THE SNAKE THROUGH THE END CAP. A three-wide horizontal tunnel cannot move its centre three cells
            // sideways in one step: the next centre is buried behind two fresh columns. The old code nevertheless
            // chose a stance inside that solid lane. Its fallback then clicked arbitrary bottom leftovers from the
            // previous lane, the 3x3 footprint cut y=floor-1, and autodig-shard-check-002 ended at 231/3375 with the
            // fixed goal inside the wall. Clear the connector one VERTICAL cross-plane at a time instead. The prior
            // lane already owns its outer column; in a dense field the three following swings clear the new lane's
            // inner, centre and outer columns, and every stance is the column cleared by the preceding swing.
            boolean atLaneStart = snakeCursor == (forward ? travelMin : travelMax);
            if (snakeLane > 0 && atLaneStart && !snakeTurnComplete) {
                int previousRawCentre = snakeStartCross
                        + snakeCrossStep * ((snakeLane - 1) * SERPENTINE_LANE_WIDTH + 1);
                int previousCentre = Math.max(crossMin + 1, Math.min(crossMax - 1, previousRawCentre));
                int outerCross = Math.max(crossMin, Math.min(crossMax, laneCentre + snakeCrossStep));
                // SWING ONE IN FROM THE WALL, NEVER AT IT. A turn happens at the far end of the travel axis, so
                // snakeCursor IS travelMin or travelMax -- the outer face of the box. The end-cap swing is keyed
                // to a cross-axis face, so its 3x3 lies in the (travel, Y) plane: centred on the wall, a whole
                // row of three falls OUTSIDE the job and only six of the nine cells are work. Centre it one cell
                // in and all nine are, which is the same nine blocks for the same click.
                //
                // The owner derived this by building the route out in wool and counting: "he breaks the outermost
                // block of our area, and only six of it are in the part we want -- take the second block from the
                // outer wall instead and the full three-by-three lands inside." Everywhere else already does
                // exactly that: laneCentre is clamped to crossMin+1..crossMax-1, and the entry shaft clamps its
                // travel the same way. The end-cap was the one place that used the raw cursor.
                //
                // The turn belongs in the OUTERMOST three travel rows. Keeping it at Working Reach made every U-turn
                // visibly happen in rows four through six instead. The bot still does not touch the wall and reverse:
                // normal pathing carries it diagonally from the previous lane onto the new centre at this end cap,
                // then the new lane continues inward without retracing the old one.
                if (snakeTurnTravel == Integer.MIN_VALUE) {
                    snakeTurnTravel = snakeOuterTurnTravel(snakeCursor, direction);
                }
                int turnTravel = snakeTurnTravel;
                if (snakeTurnCross == Integer.MIN_VALUE) {
                    snakeTurnCross = previousCentre + snakeCrossStep;
                }
                for (int turnCross = snakeTurnCross;
                     snakeCrossStep > 0 ? turnCross <= outerCross : turnCross >= outerCross;
                     turnCross += snakeCrossStep) {
                    int stanceCross = turnCross - snakeCrossStep;
                    BetterBlockPos turnHead = snakeCrossIsX
                            ? new BetterBlockPos(turnCross, headY, turnTravel)
                            : new BetterBlockPos(turnTravel, headY, turnCross);
                    BetterBlockPos preferredStance = snakeCrossIsX
                            ? new BetterBlockPos(stanceCross, bandFloor, turnTravel)
                            : new BetterBlockPos(turnTravel, bandFloor, stanceCross);
                    if (!snakeTurnSliceHasWork(turnCross, turnTravel, bcc, bandFloor, bandTop,
                            travelMin, travelMax)) {
                        // During excavation, do not walk into every already-open cross-plane. Verification enters
                        // this route only after its full census found a reach-local source/shell repair, so in that
                        // exceptional case every legal stance remains available until the repair is reached.
                        if (!snakeVerificationActive) {
                            snakeTurnCross = turnCross + snakeCrossStep;
                            continue;
                        }
                        BetterBlockPos transitStance = snakeCrossIsX
                                ? new BetterBlockPos(turnCross, bandFloor, turnTravel)
                                : new BetterBlockPos(turnTravel, bandFloor, turnCross);
                        if (!snakeReachedExactStance(transitStance)) {
                            snakeTurnCross = turnCross;
                            selectSnakeTransit(turnHead, transitStance);
                            return null;
                        }
                        snakeTurnCross = turnCross + snakeCrossStep;
                        continue;
                    }
                    snakeTurnCross = turnCross;
                    snakeTurning = true;
                    selectSnakeHorizontalTarget(turnHead, preferredStance);
                    return snakeSelectedSliceTarget(bcc);
                }
                if (snakeVerificationActive) {
                    snakeTurnCross = Integer.MIN_VALUE;
                    snakeTurnComplete = true;
                } else {
                    // Keep snakeTurnCross on its out-of-range sentinel until this transit finishes. Resetting it here
                    // would reopen the connector from its first cross-plane on every tick.
                    BetterBlockPos centreStance = snakeCrossIsX
                            ? new BetterBlockPos(laneCentre, bandFloor, turnTravel)
                            : new BetterBlockPos(turnTravel, bandFloor, laneCentre);
                    if (!snakeReachedExactStance(centreStance)) {
                        BetterBlockPos transitHead = snakeCrossIsX
                                ? new BetterBlockPos(laneCentre, headY, turnTravel - direction)
                                : new BetterBlockPos(turnTravel - direction, headY, laneCentre);
                        selectSnakeTransit(transitHead, centreStance);
                        return null;
                    }
                    snakeTurnCross = Integer.MIN_VALUE;
                    snakeTurnComplete = true;
                }
            }

            // A cleared route is a live invariant, not a historical fact. Sand, gravel, concrete powder, anvils and
            // server/modded falling blocks are all the same failure here: a non-fluid block has appeared in a slice
            // this lane already cleared. Re-elect the nearest such SLICE before choosing anything farther ahead. That
            // both restores the route and prevents a nearer obstruction from hiding the exact face ray or making the
            // path executor and the area-tool selector fight over two different tools.
            BetterBlockPos refilled = snakeNearestRefilledLaneSlice(bcc, laneCentre, bandFloor, bandTop,
                    travelMin, travelMax, direction);
            if (refilled != null) {
                int refilledTravel = snakeCrossIsX ? refilled.z : refilled.x;
                int feetTravel = snakeCrossIsX ? ctx.playerFeet().z : ctx.playerFeet().x;
                int stanceTravel = snakeRecoveryStanceTravel(refilledTravel, feetTravel, direction,
                        travelMin, travelMax);
                BetterBlockPos recoveryStance = snakeCrossIsX
                        ? new BetterBlockPos(laneCentre, bandFloor, stanceTravel)
                        : new BetterBlockPos(stanceTravel, bandFloor, laneCentre);
                selectSnakeHorizontalTarget(refilled, recoveryStance);
                return snakeSelectedSliceTarget(bcc);
            }

            BetterBlockPos head = snakeCrossIsX
                    ? new BetterBlockPos(laneCentre, headY, snakeCursor)
                    : new BetterBlockPos(snakeCursor, headY, laneCentre);
            if (!snakeSliceHasWork(head, bcc, bandFloor, bandTop)) {
                if (guard > 8000) {
                    snakeWhy("scanned the whole lane and every slice reports no work, cursor=" + snakeCursor
                            + " lane=" + snakeLane + " head=" + head.x + "," + head.y + "," + head.z, bcc);
                }
                // During excavation, an already-cleared slice is reach the bot has earned: keep the square stance
                // and take the next solid plane from up to Working Reach away. Forcing a footstep into every plane
                // made a 4.5-block reach behave like 1.0 and is visibly mechanical. A verification repair walk is
                // different: its complete census already found an open source/shell cell that must become reachable.
                if (!snakeVerificationActive) {
                    snakeCursor += direction;
                    continue;
                }
                BetterBlockPos transitStance = new BetterBlockPos(head.x, bandFloor, head.z);
                if (!snakeReachedExactStance(transitStance)) {
                    selectSnakeTransit(head, transitStance);
                    return null;
                }
                snakeCursor += direction;
                continue;
            }
            // NEVER FOLD THE STANCE ONTO THE HEAD'S OWN COLUMN. At the FIRST slice of a lane there is no cell
            // behind the head that is still inside the field, and clamping "one step back" into range hands back
            // snakeCursor itself -- stance and head end up in the same column, the stance directly under the head.
            // Everything downstream needs those two to be horizontal neighbours: horizontalFaceToward answers null
            // for dx==0 && dz==0, so selectSnakeHorizontalTarget stores the degenerate stance without ever asking
            // findSafeSnakeStance, snakeExpectedFace answers null on |dx|+|dz| != 1, snakeHeadRotation and
            // reachableSnakeLeftover are both empty, snakeSelectedSliceTarget returns null with a live head, and
            // toBreakNearPlayer returns Optional.empty(). Meanwhile playerFeet already EQUALS that stance, so the
            // walking branch cannot issue a goal either and falls through to settleInPlacementStance forever.
            //
            // MEASURED 2026-08-19, run dcf2861d: head 68,-47,67 with stance 68,-48,67, five cells of 3375 and 1930
            // frozen ticks at goal=none, pathing=false, calculating=false, crouched=true. Three of those five cells
            // were the entry shaft; the other two were mined by MovementTraverse walking INTO the degenerate cell.
            //
            // Stand one step in FRONT instead and swing backwards. A Shard swing's 3x3 footprint is keyed to the
            // face AXIS and not its sign, so the nine cells cleared are exactly the same ones -- and the cell in
            // front is the one the bot is already standing on, because the entry shaft is sunk at
            // clamp(travelMin+1, travelMax-1, snakeCursor), which at either lane start resolves to
            // snakeCursor + direction, and the end-cap turn clears that same travel column across the new lane.
            int stepBack = snakeCursor - direction;
            boolean stanceBehind = stepBack >= travelMin && stepBack <= travelMax;
            int stanceTravel = stanceBehind ? stepBack : snakeCursor + direction;
            // Immediately after a turn the body is already centred in the outer end cap. If a gravity update refilled
            // its boundary slice between connector and main scan, take that slice from the same outer-three corridor.
            if (!snakeVerificationActive && atLaneStart && snakeLane > 0
                    && snakeTurnComplete && snakeTurnTravel != Integer.MIN_VALUE) {
                stanceTravel = snakeTurnTravel;
                stanceBehind = true;
            }
            if (stanceTravel < travelMin || stanceTravel > travelMax) {
                snakeCursor += direction; // a lane one slice deep has no stance at all; never fold onto the head
                continue;
            }
            snakeHead = head;
            BetterBlockPos preferredStance = snakeCrossIsX
                    ? new BetterBlockPos(laneCentre, bandFloor, stanceTravel)
                    : new BetterBlockPos(stanceTravel, bandFloor, laneCentre);
            selectSnakeHorizontalTarget(head, preferredStance);
            if (snakeLane != snakeLastLoggedLane) {
                snakeLastLoggedLane = snakeLane;
                logMechanic("Snake: band " + bandFloor + ".." + bandTop + ", lane " + snakeLane
                        + " along " + (snakeCrossIsX ? "z" : "x") + (direction > 0 ? "+" : "-")
                        + ", head " + head.x + "," + head.y + "," + head.z
                        + ", stance " + snakeStance.x + "," + snakeStance.y + "," + snakeStance.z
                        + (stanceBehind ? "" : " (lane start: standing in front, swinging back)")
                        + (snakeEntering ? " (cutting in)" : ""));
            }
            return snakeSelectedSliceTarget(bcc);
        }
        return null;
    }

    /**
     * Says once a second why the snake produced no target, which is the one thing its own log never said.
     *
     * <p>{@code snakeTraceCountdown} was declared for this and never written to, so a stalled tunnel printed its
     * lane once and then nothing at all -- the head, the stance, whether the bot had reached it and whether the
     * centre still held stone were all invisible. Two stalls were diagnosed by reading source and probing the
     * world afterwards, and the second probe was worthless because the bench refills the cube when a run ends.
     * Every fact this line carries is one that had to be inferred instead of read.
     */
    private void snakeWhy(String reason, BuilderCalculationContext bcc) {
        // BUILT EVERY TICK, LOGGED EVERY TWENTY-FIRST. The throttle used to skip the whole method, so the line the
        // client copies into its trace file was refreshed about once a second and repeated verbatim in between --
        // a snapshot from seconds ago printed alongside live positions. Reading the two together made the bot look
        // three blocks from where it actually was, and a whole diagnosis was built on that. The log stays quiet;
        // the trace must not lie.
        BetterBlockPos feet = ctx.playerFeet();
        // STAMPED WITH THE TICK IT WAS MADE. The client copies this line into every row of its trace, but the
        // snake only evaluates on the ticks it runs -- so a single verdict was reappearing in hundreds of rows and
        // reading as hundreds of identical decisions. It made a counter that provably advances every tick look
        // frozen, and sent a whole analysis down the wrong hole. With the tick in the line, a repeat is obvious.
        String line = ("t=" + buildTick + " Snake idle: " + reason
                + " head=" + (snakeHead == null ? "none" : snakeHead.x + "," + snakeHead.y + "," + snakeHead.z)
                + " stance=" + (snakeStance == null ? "none" : snakeStance.x + "," + snakeStance.y + "," + snakeStance.z)
                + " feet=" + feet.x + "," + feet.y + "," + feet.z
                + " atStance=" + (snakeStance != null && feet.equals(snakeStance))
                + " onGround=" + ctx.player().onGround()
                + " y=" + String.format(java.util.Locale.ROOT, "%.3f", ctx.player().position().y)
                + " below=" + blockName(ctx.world().getBlockState(ctx.playerFeet().below()))
                + " centred=" + (snakeStance != null && centeredInPlacementStance(snakeStance))
                + " offset=" + (snakeStance == null ? "?" : String.format(java.util.Locale.ROOT, "%.3f,%.3f",
                        ctx.player().position().x - (snakeStance.x + 0.5D),
                        ctx.player().position().z - (snakeStance.z + 0.5D)))
                + " headNeedsClear=" + (snakeHead != null && snakeCellNeedsClear(snakeHead, bcc))
                + " face=" + snakeExpectedFace()
                + " entering=" + snakeEntering
                + " cleanup=" + snakeCleanupActive
                // WHAT THE CROSSHAIR ACTUALLY HITS, and whether that is why no click is forced. Everything above
                // describes intent; this is the one fact that separates "it never decided to dig" from "it decided
                // and the gate refused". Its absence is why three explanations for a standing bot were guesses.
                // IS IT EVEN TRYING TO WALK? Everything else says where it wants to be; this says whether anything
                // is carrying it there. A bot four cells short of its stance looks identical whether it never asked
                // for a route, asked and got none, or asked and had it torn up again -- and those need different
                // fixes. Measured 20.08.: standing five cells from a block it was aiming at perfectly.
                // THE TWO NUMBERS THAT SETTLE WHY A FINISHED LAYER DOES NOT ADVANCE. "parked" says how many cells
                // the builder is holding back and for what reason; "believesUnfinishedY" says which row it still
                // thinks is open. The owner cleared the surface and watched it stay put, which is either a park
                // nothing can wake or a builder that does not believe its own eyes -- and those look identical
                // from outside. They are not identical inside, and they need different repairs.
                + " parked=" + parkedCells.size() + describeParkReasons()
                // WHAT IS IN THE WAY, in the one cell the answer can be in. Everything above says where the bot
                // wants to go and whether anything is carrying it there; none of it says why the carrying fails,
                // and the excavation lane has exactly three ways to fail a step -- the floor is gone, the body cell
                // holds a block it may not break, or it holds a fluid pathing will not walk into. Those are three
                // different repairs and they were indistinguishable from outside: run a0a00867 was diagnosed from
                // the bot's Y VELOCITY, because -0.005 instead of -0.078 is water and nothing in the log said so.
                + " step=" + describeSnakeCorridorStep()
                + " unfinishedAt=" + describeFirstUnfinishedCell(bcc)
                + " goal=" + (princeps.getPathingBehavior().getGoal() == null
                        ? "none" : princeps.getPathingBehavior().getGoal().toString())
                + " pathing=" + princeps.getPathingBehavior().isPathing()
                + " calculating=" + princeps.getPathingBehavior().getInProgress().isPresent()
                + " hit=" + describeCrosshair()
                + " liveMatch=" + (snakeHead != null && snakeLiveHitMatches(snakeHead)));
        snakeDiagnosis = line;
        if (snakeTraceCountdown-- > 0) {
            return;
        }
        snakeTraceCountdown = 20;
        logMechanic(line);
    }

    /**
     * The next cardinal corridor cell and the reason it is or is not enterable.
     *
     * <p>Reads the world only; it decides nothing. The three verdicts are the three ways the excavation lane can
     * fail a step, and each names the repair that belongs to it: {@code nofloor} is the bridge, {@code wet} is the
     * corridor licence (see {@code BuilderCalculationContext.wadeLicence}), {@code blocked} is a solid cell the
     * lane may not break and therefore a route or a gravity problem, not a fluid one.
     */
    private String describeSnakeCorridorStep() {
        BetterBlockPos feet = ctx.playerFeet();
        if (snakeStance == null || feet.equals(snakeStance) || feet.y != snakeStance.y) {
            return "none";
        }
        BetterBlockPos step = nextSnakeWaypoint(feet, snakeStance);
        BlockState body = ctx.world().getBlockState(step);
        BlockState head = ctx.world().getBlockState(step.above());
        BlockState floor = ctx.world().getBlockState(step.below());
        String verdict = !MovementHelper.canWalkOn(ctx, step.below()) ? "nofloor"
                : !body.getFluidState().isEmpty() || !head.getFluidState().isEmpty() ? "wet"
                : !MovementHelper.canWalkThrough(ctx, step) || !MovementHelper.canWalkThrough(ctx, step.above())
                        ? "blocked" : "open";
        return step.x + "," + step.y + "," + step.z + "/" + verdict
                + " body=" + blockName(body) + " head=" + blockName(head) + " floor=" + blockName(floor)
                + " stuck=" + snakeStepStuckTicks;
    }

    /**
     * The first cell the scan still counts as work, with the block actually standing there.
     *
     * <p>Naming only the ROW was not enough: the owner cleared a surface, watched the bot insist that row was
     * unfinished, and had no way to check who was right. A coordinate can be flown to. If it names a cell that is
     * visibly empty the scan is wrong; if it names gravel that slid in or water that flowed in, the world is.
     */
    private String describeFirstUnfinishedCell(BuilderCalculationContext bcc) {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (origin == null || full == null) {
            return "none";
        }
        int y = areaHighestUnfinishedY(bcc);
        if (y == Integer.MIN_VALUE) {
            return "none";
        }
        int localY = y - origin.getY();
        for (int dx = 0; dx < full.widthX(); dx++) {
            for (int dz = 0; dz < full.lengthZ(); dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                BlockState current = bcc.bsi.get0(x, y, z);
                if (current.isAir() || !full.inSchematic(dx, localY, dz, current)) {
                    continue;
                }
                BlockState desired = full.desiredState(dx, localY, dz, current, approxPlaceable);
                if (desired != null && desired.isAir()) {
                    return x + "," + y + "," + z + "/" + blockName(current);
                }
            }
        }
        return "rowEmpty@y=" + y;
    }

    /** Park reasons, compactly, so a stalled window says WHY it is holding cells back. */
    private String describeParkReasons() {
        if (parkedCells.isEmpty()) {
            return "";
        }
        java.util.EnumMap<ParkReason, Integer> counts = new java.util.EnumMap<>(ParkReason.class);
        for (ParkedCell cell : parkedCells.values()) {
            counts.merge(cell.reason, 1, Integer::sum);
        }
        return counts.toString().replace(" ", "");
    }

    /** The block and face the crosshair is on right now, or "none". */
    private String describeCrosshair() {
        HitResult result = ctx.objectMouseOver();
        if (result == null || result.getType() != HitResult.Type.BLOCK) {
            return "none";
        }
        BlockHitResult block = (BlockHitResult) result;
        return block.getBlockPos().getX() + "," + block.getBlockPos().getY() + "," + block.getBlockPos().getZ()
                + "/" + block.getDirection();
    }

    @Override
    public String areaDigDiagnosis() {
        return snakeDiagnosis + " integrity={bridges=" + snakeFloorRepairs
                + ",sources=" + snakeFluidSourcesPlugged
                + ",shell=" + snakeShellRepairs
                + ",verificationPasses=" + snakeVerificationPasses
                + ",verifiedBands=" + snakeVerifiedBands + "} breakRetry={"
                + princeps.getInputOverrideHandler().getBlockBreakHelper().breakRetryDiagnosis() + "}";
    }

    /** Bounded helper retries belong to the active excavation, never to a subsequent construction action. */
    public boolean mayRetryExcavationBreak() {
        return isActive() && excavating && !paused && abortPending == Ending.RUNNING
                && princeps.getPathingControlManager().mostRecentInControl().orElse(null) == this;
    }

    /**
     * Returns the centre of the selected 3x3 slice, except when that centre is already air.
     *
     * <p>A centre-only route deadlocks on a perfectly ordinary damaged wall: the slice still contains work, but air
     * has no face to ray-trace and therefore cannot be clicked. The route remains owner of the slice and chooses one
     * of its eight leftovers instead. Keeping that choice inside the snake is important; falling through to the
     * generic nearby-block scan is exactly how the old implementation abandoned its lanes and appeared random.
     */
    private BetterBlockPos snakeSelectedSliceTarget(BuilderCalculationContext bcc) {
        if (snakeTransit || snakeHead == null) {
            snakeWhy("walking the already-clear route", bcc);
            return null;
        }
        if (!excavating && !snakeReadyToSwing()) {
            snakeWhy("not ready", bcc);
            return null;
        }
        boolean centreNeedsClear = snakeCellNeedsClear(snakeHead, bcc);
        // A single-block action has no 3x3 plane. Classify it BEFORE the Shard pose gate; water drift may make that
        // pose impossible while the real outline of a leftover remains plainly within ordinary mining reach.
        if (excavating && !snakeEntering) {
            BetterBlockPos retained = snakeCleanupWork.retained(snakeHead, snakeBandTop, bcc::get,
                    cell -> snakeCellNeedsClear(cell, bcc) && !isCellParked(cell.x, cell.y, cell.z)
                            && snakeCleanupCutAllowed(cell, bcc));
            if (retained != null) return selectSnakeCleanup(retained, bcc);
            Optional<BetterBlockPos> sourceBlock = reachableSnakeSourceBlock(bcc);
            if (sourceBlock.isPresent()) return selectSnakeOrdinaryCleanup(sourceBlock.get(), bcc);
        }
        boolean partialHead = excavating
                && snakeHeadNeedsIndividualBreak(bcc.get(snakeHead), ctx.world(), snakeHead);
        // Assess the action we will really execute. A vertical Shard plane may remove the plug's solid support
        // in the same cut, making the whole cut safe even though mining that plug individually is unsafe.
        boolean safeAreaHead = excavating && !snakeEntering && !snakeSingleBlockFallback && !partialHead
                && snakeAreaToolSlot() >= 0 && snakeAreaCutAllowed(snakeHead, snakeExpectedFace());
        if (centreNeedsClear && !safeAreaHead && !excavationMayMinePlug(snakeHead, true)) {
            if (!snakeEntering) {
                Optional<BetterBlockPos> other = reachableSnakeLeftover(bcc);
                if (other.isPresent()) return selectSnakeCleanup(other.get(), bcc);
            }
            snakeWhy("retaining a source plug until its renewing neighbours are sealed", bcc);
            return null;
        }
        snakeRequiresOrdinaryTool = excavating
                && (partialHead || !snakeAreaCutAllowed(snakeHead, snakeExpectedFace()));
        if (snakeRequiresOrdinaryTool && !snakeToolReady(bcc.get(snakeHead), true)) {
            snakeWhy("waiting for an ordinary pickaxe for this individual cut", bcc);
            return null;
        }
        if (centreNeedsClear) {
            resetSnakeMissingCentre();
            if (!snakeEntering && snakeRequiresOrdinaryTool) {
                return selectSnakeOrdinaryCleanup(snakeHead, bcc);
            }
            if (excavating && !snakeReadyToSwing()) {
                snakeWhy("not ready for the selected face", bcc);
                return null;
            }
            if (excavating && !snakeEntering && snakeSingleBlockFallback) {
                return selectSnakeOrdinaryCleanup(snakeHead, bcc);
            }
            // The working case needs a line too, or the trace shows only the last thing that went WRONG and the
            // healthy ticks in between inherit it.
            snakeDiagnosis = "t=" + buildTick + " Snake swing head=" + snakeHead.x + "," + snakeHead.y + ","
                    + snakeHead.z + " stance=" + (snakeStance == null ? "none"
                            : snakeStance.x + "," + snakeStance.y + "," + snakeStance.z)
                    + " face=" + snakeExpectedFace() + " hit=" + describeCrosshair()
                    // HOW FAR THE HEAD MOVED IN THIS TICK, and whether the face is a new one. Without these two
                    // the trace cannot separate "swung at the right block" from "swung at the right block while
                    // still turning onto it" -- and the second is the one that comes out as a rotated 3x3 on a
                    // server with a ping. The bench cannot show it at all, so the trace has to.
                    + " aimd=" + String.format(java.util.Locale.ROOT, "%.2f", aimMovementDegrees)
                    + " settled=" + snakeAimSettledTicks
                    + " faceChanged=" + (snakeExpectedFace() != null && snakeExpectedFace() != snakeLastSwungFace)
                    + " liveMatch=" + snakeLiveHitMatches(snakeHead);
            return snakeHead;
        }
        // DOWNWARD ENTRY OWNS EXACTLY ONE COLUMN. The ordinary pick breaks the centre below the feet, but the
        // server can publish that block as air several builder ticks before fall/onGround catches up. Treating this
        // transient centre-air state like a damaged horizontal 3x3 lets reachableSnakeLeftover choose any of the
        // eight neighbours and widens the shaft just before the player drops. While entering, an empty centre means
        // only one thing: hold all mining input until physics lands us one block lower. snakeUpdate will then either
        // select the next centre below or, at bandFloor, switch back to the ordinary horizontal snake.
        if (snakeMustAwaitEntryLanding(snakeEntering, centreNeedsClear)) {
            resetSnakeMissingCentre();
            snakeWhy("entry centre air, awaiting one-column landing", bcc);
            return null;
        }
        // A server commonly announces the vanilla centre break before the eight custom-tool neighbours. For one or
        // two client ticks that looks exactly like a damaged slice, although no work will remain once those updates
        // arrive. Switching tools in that transient window makes the hotbar chatter on every healthy swing. Require
        // the same centre-air slice to survive three DISTINCT builder ticks before invoking individual cleanup.
        long centreKey = snakeHead.asLong();
        if (snakeMissingCentreKey != centreKey) {
            snakeMissingCentreKey = centreKey;
            snakeMissingCentreLastTick = Long.MIN_VALUE;
            snakeMissingCentreTicks = 0;
        }
        if (snakeMissingCentreLastTick != buildTick) {
            snakeMissingCentreLastTick = buildTick;
            snakeMissingCentreTicks++;
        }
        if (snakeMissingCentreTicks < 3) {
            snakeWhy("centre air, settling", bcc);
            return null;
        }
        Optional<BetterBlockPos> leftover = reachableSnakeLeftover(bcc);
        if (leftover.isEmpty()) {
            snakeWhy("centre air and no reachable leftover", bcc);
            return null;
        }
        snakeCleanupTarget = leftover.get();
        snakeCleanupActive = true;
        snakeCleanupWithAreaTool = snakeCleanupMustUseArea(snakeCleanupTarget, bcc);
        if (excavating && !snakeCleanupWithAreaTool) return selectSnakeOrdinaryCleanup(snakeCleanupTarget, bcc);
        if (snakeCleanupWithAreaTool && !snakeReadyToSwing()) {
            snakeWhy("waiting for a safe Shard cleanup pose", bcc);
            return null;
        }
        // The working case needs a line for the same reason the swing does: without it the trace inherits the last
        // thing that went wrong and a healthy cleanup reads as a stall.
        snakeDiagnosis = "t=" + buildTick + " Snake cleanup target=" + snakeCleanupTarget.x + ","
                + snakeCleanupTarget.y + "," + snakeCleanupTarget.z
                + " head=" + snakeHead.x + "," + snakeHead.y + "," + snakeHead.z
                + " stance=" + (snakeStance == null ? "none"
                        : snakeStance.x + "," + snakeStance.y + "," + snakeStance.z)
                + " withAreaTool=" + snakeCleanupWithAreaTool
                + " face=" + snakeExpectedFace() + " hit=" + describeCrosshair();
        return snakeCleanupTarget;
    }

    private void resetSnakeMissingCentre() {
        snakeMissingCentreKey = Long.MIN_VALUE;
        snakeMissingCentreLastTick = Long.MIN_VALUE;
        snakeMissingCentreTicks = 0;
    }

    private BetterBlockPos selectSnakeOrdinaryCleanup(BetterBlockPos target, BuilderCalculationContext bcc) {
        snakeCleanupTarget = target;
        snakeCleanupActive = true;
        snakeCleanupWithAreaTool = false;
        snakeRequiresOrdinaryTool = true;
        snakeCleanupWork.choose(snakeHead, snakeBandTop, target, bcc.get(target));
        snakeDiagnosis = "t=" + buildTick + " Snake ordinary cleanup target=" + target.toShortString()
                + " head=" + snakeHead.toShortString() + " hit=" + describeCrosshair();
        return target;
    }

    private boolean snakeCleanupMustUseArea(BetterBlockPos target, BuilderCalculationContext bcc) {
        return snakeOrdinaryPickSlot(bcc.get(target)) < 0 || !excavationMayMinePlug(target, false);
    }

    private boolean snakeCleanupCutAllowed(BetterBlockPos target, BuilderCalculationContext bcc) {
        if (!excavating) return true;
        if (!snakeCleanupMustUseArea(target, bcc)) return true;
        if (snakeAreaToolSlot() >= 0 && snakeCleanupAreaRotation(target).isPresent()) return true;
        excavationMayMinePlug(target, true);
        return false;
    }

    private BetterBlockPos selectSnakeCleanup(BetterBlockPos target, BuilderCalculationContext bcc) {
        if (!snakeCleanupMustUseArea(target, bcc)) return selectSnakeOrdinaryCleanup(target, bcc);
        snakeCleanupTarget = target;
        snakeCleanupActive = true;
        snakeCleanupWithAreaTool = true;
        snakeRequiresOrdinaryTool = false;
        return snakeReadyToSwing() ? target : null;
    }

    private Optional<Rotation> snakeOrdinaryCleanupRotation(BetterBlockPos target, BuilderCalculationContext bcc) {
        if (!excavating) return RotationUtils.reachableForWork(ctx, target,
                ctx.playerController().getBlockReachDistance(), false);
        snakeCleanupWork.choose(snakeHead, snakeBandTop, target, bcc.get(target));
        double reach = ctx.playerController().getBlockReachDistance();
        return snakeCleanupWork.rotation(ctx.player().getEyePosition(1.0F), ctx.playerRotations(), reach,
                raw -> RayTraceUtils.rayTraceTowards(ctx.player(), raw, reach, false),
                raw -> RayTraceUtils.rayTraceTowards(ctx.player(),
                        princeps.getLookBehavior().getAimProcessor().peekRotationExact(raw), reach, false),
                () -> RotationUtils.reachableForWork(ctx, target, reach, false));
    }

    /** A source held by a solid outline must be broken before the existing source-plug pass can seal it. */
    private Optional<BetterBlockPos> reachableSnakeSourceBlock(BuilderCalculationContext bcc) {
        if (!excavating || snakeBandFloor == Integer.MIN_VALUE || snakeBandTop == Integer.MIN_VALUE) {
            return Optional.empty();
        }
        BetterBlockPos feet = ctx.playerFeet();
        int radius = Math.min(6, (int) Math.ceil(ctx.playerController().getBlockReachDistance()));
        List<BetterBlockPos> sources = new ArrayList<>();
        // Bounded by normal arm's reach and the three-cell active band, never by total selection volume.
        for (int y = Math.max(feet.y, snakeBandFloor); y <= snakeBandTop; y++) {
            for (int x = feet.x - radius; x <= feet.x + radius; x++) {
                for (int z = feet.z - radius; z <= feet.z + radius; z++) {
                    BetterBlockPos cell = new BetterBlockPos(x, y, z);
                    BlockState state = bcc.get(cell);
                    if (snakeSourceBlockNeedsBreak(state) && snakeCellNeedsClear(cell, bcc)
                            && !isCellParked(x, y, z)) sources.add(cell);
                }
            }
        }
        sources.sort(Comparator.comparingDouble((BetterBlockPos cell) -> cell.distSqr(feet))
                .thenComparingInt(cell -> cell.y).thenComparingInt(cell -> cell.x).thenComparingInt(cell -> cell.z));
        for (BetterBlockPos source : sources) {
            if (RotationUtils.reachableForWork(ctx, source,
                    ctx.playerController().getBlockReachDistance(), false).isPresent()) return Optional.of(source);
        }
        return Optional.empty();
    }

    static boolean snakeSourceBlockNeedsBreak(BlockState state) {
        return state != null && state.getFluidState().isSource() && !snakeTreatAsFluid(state, true);
    }

    static boolean snakeMiningPostureReady(boolean onGround, boolean inWater,
                                           boolean ordinaryCleanup, boolean entering) {
        return onGround || (inWater && ordinaryCleanup && !entering);
    }

    /** First reachable leftover in the exact face-owned 3x3 footprint, in a stable centre-out order. */
    private Optional<BetterBlockPos> reachableSnakeLeftover(BuilderCalculationContext bcc) {
        Direction face = snakeExpectedFace();
        if (face == null) {
            return Optional.empty();
        }
        int[] offsets = {0, -1, 1};
        for (int verticalOrZ : offsets) {
            for (int horizontalOrX : offsets) {
                BetterBlockPos cell;
                if (face.getAxis() == Direction.Axis.Y) {
                    cell = new BetterBlockPos(snakeHead.x + horizontalOrX, snakeHead.y,
                            snakeHead.z + verticalOrZ);
                } else if (face.getAxis() == Direction.Axis.X) {
                    cell = new BetterBlockPos(snakeHead.x, snakeHead.y + verticalOrZ,
                            snakeHead.z + horizontalOrX);
                } else {
                    cell = new BetterBlockPos(snakeHead.x + horizontalOrX, snakeHead.y + verticalOrZ,
                            snakeHead.z);
                }
                if (cell.equals(snakeHead) || !snakeCellNeedsClear(cell, bcc)
                        || !snakeCleanupCutAllowed(cell, bcc)) {
                    continue;
                }
                if (RotationUtils.reachableForWork(ctx, cell,
                        ctx.playerController().getBlockReachDistance(), false).isPresent()) {
                    return Optional.of(cell);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * First non-fluid work block that has appeared inside the exact one-block route to the selected stance.
     *
     * <p>No material-name list is involved. The schematic says the two cells occupied by the walking body must be
     * air; any state that is not air and is still owed as a clear is an obstruction, including vanilla and modded
     * falling blocks. Critically, untouched rock BELOW the feet belongs to the next excavation band and is support,
     * not a route obstruction. The same cardinal stepping helper used by the constrained pathfinder defines the scan,
     * so untouched shoulder lanes can never hijack it.
     */
    private Optional<BetterBlockPos> snakeRouteObstruction(BuilderCalculationContext bcc) {
        if (snakeHead == null || snakeStance == null || snakeBandFloor == Integer.MIN_VALUE
                || snakeBandTop == Integer.MIN_VALUE) {
            return Optional.empty();
        }
        // A ROUTE TO THE STANCE ONLY EXISTS ON THE STANCE'S LEVEL. The scan below steps cardinally at the bot's own
        // height, so from a different level it invents a horizontal corridor towards a cell that is not there and
        // reports its first solid block as an obstruction -- forever. The bot then spends the whole job tunnelling
        // sideways with the ORDINARY pickaxe, one block at a time, and never once swings the wide tool it was told
        // to use. That is what an owner sees as "it is not using the Shard Pickaxe"; it is not a tool bug.
        if (ctx.playerFeet().y != snakeStance.y) {
            return Optional.empty();
        }
        BetterBlockPos route = ctx.playerFeet();
        for (int steps = 0; !route.equals(snakeStance) && steps < 64; steps++) {
            route = nextSnakeWaypoint(route, snakeStance);
            for (int y = route.y + 1; y >= route.y; y--) {
                BetterBlockPos cell = new BetterBlockPos(route.x, y, route.z);
                if (snakeCellNeedsClear(cell, bcc)) {
                    return Optional.of(cell);
                }
            }
        }
        return Optional.empty();
    }

    static boolean snakeRouteBodyIncludesY(int routeFeetY, int blockY) {
        return blockY >= routeFeetY && blockY <= routeFeetY + 1;
    }

    /**
     * A cell carrying a fluid is not something to swing at.
     *
     * <p>The four predicates that decide what the snake still owes all asked the same question -- "is it air? no,
     * then it has to go" -- and water is not air. So a source the dig exposes counts as a breakable block forever:
     * the bot aims at it, clicks, and nothing ever breaks, because a fluid has no hardness and no drop. On the
     * owner's server that is a bot standing in front of a spring holding the mouse down.
     *
     * <p>With area-tool mode on, the generic liquid handling further down onTick cannot rescue it either --
     * toBreakNearPlayer short-circuits as soon as snakeHead is set, so the snake owns target selection outright
     * and its own predicates are the only ones consulted.
     *
     * <p>Excluding it here is the half that stops the stall. Sealing the source is the other half and belongs to
     * placement, which can only happen once the cell has stopped being a mining target.
     */
    /** The centre slice nearest the player that has refilled after this exact lane already cleared it. */
    private BetterBlockPos snakeNearestRefilledLaneSlice(BuilderCalculationContext bcc, int laneCentre,
                                                          int bandFloor, int bandTop, int travelMin, int travelMax,
                                                          int direction) {
        int feetTravel = snakeCrossIsX ? ctx.playerFeet().z : ctx.playerFeet().x;
        int scanMin = Math.max(travelMin, feetTravel - SNAKE_SWEEP_RADIUS);
        int scanMax = Math.min(travelMax, feetTravel + SNAKE_SWEEP_RADIUS);
        BetterBlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int travel = scanMin; travel <= scanMax; travel++) {
            if (!snakeTravelAlreadyCleared(travel, snakeCursor, direction)) {
                continue;
            }
            int stanceTravel = travel + direction;
            if (stanceTravel < travelMin || stanceTravel > travelMax) {
                continue;
            }
            BetterBlockPos head = snakeCrossIsX
                    ? new BetterBlockPos(laneCentre, Math.max(bandTop - 1, bandFloor + 1), travel)
                    : new BetterBlockPos(travel, Math.max(bandTop - 1, bandFloor + 1), laneCentre);
            if (!snakeSliceHasWork(head, bcc, bandFloor, bandTop)) {
                continue;
            }
            int distance = Math.abs(travel - feetTravel);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = head;
            }
        }
        return best;
    }

    static int snakeOuterTurnTravel(int boundaryTravel, int inwardDirection) {
        return boundaryTravel + Integer.signum(inwardDirection);
    }

    static boolean snakeTravelAlreadyCleared(int travel, int cursor, int direction) {
        return direction > 0 ? travel < cursor : travel > cursor;
    }

    static int snakeRecoveryStanceTravel(int targetTravel, int feetTravel, int direction,
                                          int travelMin, int travelMax) {
        int towardFeet = Integer.compare(feetTravel, targetTravel);
        if (towardFeet == 0) {
            towardFeet = -Integer.signum(direction);
        }
        int candidate = targetTravel + towardFeet;
        if (candidate < travelMin || candidate > travelMax) {
            candidate = targetTravel - towardFeet;
        }
        return candidate;
    }

    private boolean snakeIsFluid(BlockState state) {
        return snakeTreatAsFluid(state, excavating);
    }

    static boolean snakeTreatAsFluid(BlockState state, boolean excavation) {
        if (state == null || state.getFluidState().isEmpty()) {
            return false;
        }
        // A waterlogged block still has a solid/plant block to remove. Trying to fill that occupied cell skips the
        // required break and leaves an unreplaceable source in every later band census. Construction keeps its own
        // fluid-pass policy; only an excavation distinguishes the block from the water it will leave behind.
        return !excavation || state.getBlock() instanceof LiquidBlock
                || state.getBlock() instanceof BubbleColumnBlock;
    }

    static boolean snakeSourceReadyForPlug(BlockState state, boolean excavation) {
        return snakeTreatAsFluid(state, excavation) && state.getFluidState().isSource();
    }

    static boolean snakeHeadNeedsIndividualBreak(BlockState state, BlockGetter world, BlockPos at) {
        return !state.isAir() && !snakeTreatAsFluid(state, true) && !state.isCollisionShapeFullBlock(world, at);
    }

    static boolean snakeShellRequiresSeal(BlockState state, boolean replaceable) {
        return (state != null && !state.getFluidState().isEmpty()) || replaceable;
    }

    private boolean snakeCellNeedsClear(BlockPos cell, BuilderCalculationContext bcc) {
        BlockState current = bcc.bsi.get0(cell.getX(), cell.getY(), cell.getZ());
        if (current.isAir() || snakeIsFluid(current)) {
            return false;
        }
        BlockState desired = bcc.getSchematic(cell.getX(), cell.getY(), cell.getZ(), current);
        return desired != null && desired.isAir();
    }

    private boolean snakeReadyToSwing() {
        if (snakeStance == null || snakeHead == null || !ctx.player().onGround()) {
            return false;
        }
        // CLOSE ENOUGH AND LOOKING THE RIGHT WAY IS THE WHOLE REQUIREMENT. What decides a swing is the ORIENTATION
        // of the 3x3 plane, and that follows from the face the ray hits -- not from which cell the feet occupy. A
        // block higher, lower, left or right changes nothing about the nine cells that come out.
        //
        // This used to demand playerFeet().equals(snakeStance) AND a position within 0.05 blocks of that cell's
        // centre, and those two together are what froze every run measured today: arrived, aimed, head still
        // stone, and refused -- because the body was 0.078 blocks off a mark it had no way to hit, since one
        // crouch step is 0.065. Demanding a pose that the controller cannot produce is not strictness, it is a
        // deadlock.
        //
        // Nothing is given up by relaxing it. snakeLiveHitMatches still checks, at the instant of the click, that
        // the live crosshair is on THIS block and on THIS face, and CLICK_LEFT is not forced until it agrees. The
        // exact stance was a second, weaker copy of a test that already exists downstream.
        Direction face = snakeExpectedFace();
        if (face == null) {
            return false;
        }
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 eye = ctx.playerHead();
        Vec3 faceAim = new Vec3(snakeHead.x + 0.5D + face.getStepX() * 0.5D,
                snakeHead.y + 0.5D + face.getStepY() * 0.5D,
                snakeHead.z + 0.5D + face.getStepZ() * 0.5D);
        if (eye.distanceToSqr(faceAim) > reach * reach) {
            return false;
        }
        // SIDEWAYS IS NOT DEPTH, and until now nothing here told them apart.
        //
        // This method decided a swing on reach and footing alone. snakeStance -- the cell the whole route says the
        // bot belongs in -- is read by exactly one place, the walking branch, and that branch is unreachable on
        // every tick a swing is legal. So four shipped fixes that chose a better stance changed a number nobody
        // consulted at the moment it mattered. Measured from two directions: 108 of 376 bench swings and 27% of
        // the owner's own server trace were taken from up to three cells off the axis.
        //
        // Depth stays free -- from one cell back or four the face centre is the same face centre, and that is the
        // reach the digger is built around. What remains forbidden is a mostly-sideways or opposite-side approach;
        // those poses are where a tiny aim correction can expose a different face.
        //
        // The body may approach diagonally by up to 35 degrees. That is deliberately human-sized rather than an
        // exact integer centre-line requirement: a player does not stop mining just because the next path step is
        // still completing. The predicted ray below and the live click gate remain stricter than this pose test --
        // both must hit this exact block AND the selected face, so the Shard can never rotate its 3x3 plane merely
        // because the body was allowed to stand obliquely.
        if (snakeEntering) {
            // Reach is intentionally exploited for horizontal 3x3 work, but not for a vertical drop. The entry cut
            // owns one fixed x/z column: walk onto it, centre, cut exactly the block below, land, then repeat. Allowing
            // a remote DOWN target made the pre-entry corridor look like part of the shaft and produced several
            // unnecessary columns plus bridge/break loops before the bot eventually fell into the real one.
            return snakeEntryPoseReady(ctx.playerFeet(), snakeStance, ctx.player().onGround(),
                    centeredInPlacementStance(snakeStance));
        }
        face = snakeSliceFace;
        if (face == null) {
            return false;
        }
        BetterBlockPos feet = ctx.playerFeet();
        double fromFaceX = eye.x - faceAim.x;
        double fromFaceZ = eye.z - faceAim.z;
        if (snakeWithinFaceAngle(fromFaceX, fromFaceZ, face) && feet.y == snakeStance.y) {
            snakeNotSquareTicks = 0;
            // AND THE LATCH OPENS AGAIN HERE. The fallback below is a deadlock brake, not a verdict on the run: it
            // exists so a pose that cannot be reached does not stop the job. Reaching the pose is the evidence that
            // the reason for it is gone -- this is the branch that PROVES the body is square and the face is the
            // right one, so a wide swing is legal on this very tick.
            //
            // Without the release it was a one-way door, and one-way is what made it a run-killer rather than a
            // slowdown. Measured on the owner's 0.3.211 session (autodig-trace-20260821-165526): the Shard Pickaxe
            // was in hand for ticks 1..28 and then never again for the remaining 1630 ticks. Three seconds of an
            // imperfect stance during the descent -- which is exactly when the feet are NOT at stance height --
            // downgraded every later swing to one block, including the perfectly aimed ones. What the owner saw was
            // a bot digging a three-by-three corridor with an ordinary pickaxe: 147 wide-swing decisions that all
            // took a single block, and 1042 single-cell cleanups to collect the eight cells each of them left.
            //
            // This is NOT the reset that bricked 0.3.202. That one cleared this shape of field unconditionally at
            // the top of snakeUpdate, every tick, which made the gate unsatisfiable. This clears it only where the
            // gate has just been SATISFIED, so it can never erase evidence the gate still needs.
            snakeSingleBlockFallback = false;
            return true;
        }
        // AND IT MAY NOT BECOME A DEADLOCK. If the walk cannot deliver the safe 35-degree cone -- a stance that will
        // not hold the bot, a cell that keeps refilling -- the answer is not to wait forever and not to swing wide.
        // It is to take the slice apart one block at a time with the ordinary pickaxe, which has no plane and
        // therefore cannot be turned. Slow is a cost; a rotated three-by-three is a wrong result.
        if (++snakeNotSquareTicks >= SNAKE_SQUARE_GIVE_UP_TICKS) {
            snakeSingleBlockFallback = true;
            return true;
        }
        return false;
    }

    static boolean snakeEntryPoseReady(BetterBlockPos feet, BetterBlockPos stance,
                                       boolean onGround, boolean centered) {
        return feet != null && feet.equals(stance) && onGround && centered;
    }

    static boolean snakeMustAwaitEntryLanding(boolean entering, boolean centreNeedsClear) {
        return entering && !centreNeedsClear;
    }

    static boolean snakeWithinFaceAngle(double fromFaceX, double fromFaceZ, Direction face) {
        if (face == null || face.getAxis() == Direction.Axis.Y) {
            return false;
        }
        double horizontalSq = fromFaceX * fromFaceX + fromFaceZ * fromFaceZ;
        if (horizontalSq <= 1.0E-9D) {
            return false;
        }
        double axial = fromFaceX * face.getStepX() + fromFaceZ * face.getStepZ();
        return axial > 0.0D && axial * axial >= horizontalSq * SNAKE_MAX_OBLIQUE_COS_SQ;
    }

    /**
     * Keeps a slice fixed while choosing a floor cell that really exists.
     *
     * <p>The first tunnel implementation treated the geometrically ideal cell directly behind every wall as an
     * unconditional {@link GoalBlock}. That is only valid in a pristine cube. If somebody has already mined the
     * block below that cell, the goal still exists as coordinates but can never satisfy {@code onGround}; the bot
     * keeps trying to walk into the hole and the whole snake appears to freeze or shake. A three-wide tunnel already
     * gives us safe alternatives. Prefer the centre line, then either shoulder, and retreat at most four cells while
     * keeping the same wall face. Four is the furthest axial distance whose face centre remains inside normal block
     * reach from a standing eye position.
     */
    private void selectSnakeHorizontalTarget(BetterBlockPos head, BetterBlockPos preferredStance) {
        snakeHead = head;
        snakeSliceFace = horizontalFaceToward(head, preferredStance);
        // Excavation has one legal stance: the centre-line cell immediately behind this slice. A missing floor is
        // repaired by snakeMovementCommand; it is never grounds for findSafeSnakeStance to move to a shoulder.
        snakeStance = preferredStance;
    }

    private void selectSnakeTransit(BetterBlockPos head, BetterBlockPos stance) {
        snakeHead = head;
        snakeStance = stance;
        snakeTransit = true;
        snakeSliceFace = horizontalFaceToward(head, stance);
    }

    private boolean snakeReachedExactStance(BetterBlockPos stance) {
        return ctx.playerFeet().equals(stance) && ctx.player().onGround() && centeredInPlacementStance(stance);
    }

    static boolean snakeBandNeedsVerification(boolean verificationActive, boolean hasGlobalWork,
                                               boolean routeComplete, int routeBandTop,
                                               int committedBandTop, int verifiedBandTop) {
        if (committedBandTop == Integer.MIN_VALUE || verifiedBandTop == committedBandTop) {
            return false;
        }
        if (verificationActive || !hasGlobalWork) {
            return true;
        }
        // routeComplete is mutable route state and can survive one recursive tick. Bind it to the committed band so
        // the old route's completion can never instantly certify a newly selected lower band.
        return routeComplete && routeBandTop == committedBandTop;
    }

    /**
     * Runs after the ordinary work set becomes empty and before areaHighestUnfinishedY is allowed to select a lower
     * band. The pause gives gravity updates time to arrive, then every cell in the complete band is censused for
     * several consecutive ticks. An already-clean band is certified immediately; walking nine hundred empty cells
     * is neither a stronger check nor useful world progress. If the census finds a fallen block, normal excavation
     * resumes and physically returns to it. Only sources or shell gaps need the verification route itself, because
     * their placement remains reach-local and the route is what keeps navigation inside the one-block snake lane.
     */
    private PathingCommand snakeVerificationCommand(BuilderCalculationContext bcc, int areaSize, int bandTop) {
        if (!snakeVerificationActive) {
            snakeVerificationActive = true;
            snakeVerificationBandTop = bandTop;
            snakeVerificationSettleTicks = 8;
            snakeRouteComplete = false;
            snakeBandTop = Integer.MIN_VALUE;
            snakeCursor = Integer.MIN_VALUE;
            snakeHead = null;
            snakeStance = null;
            snakeBridgeTarget = null;
            snakeVerificationPasses++;
            snakeVerificationRepairSweeps = 0;
            snakeVerificationStableTicks = 0;
            logDirect("AutoDig checks completed band y=" + bandTop + ".." + (bandTop - (areaSize - 1))
                    + " before descending");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // snakeUpdate may reset snakeBandTop to initialise a repair walk. The thing being certified must therefore
        // live in its own immutable latch, not in mutable route state or a highest-work scan.
        if (snakeVerificationBandTop != Integer.MIN_VALUE) {
            bandTop = snakeVerificationBandTop;
        }
        if (snakeVerificationSettleTicks > 0) {
            snakeVerificationSettleTicks--;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        int residual = snakeBandResidualCount(bcc, bandTop, areaSize);
        if (residual > 0) {
            snakeVerificationActive = false;
            snakeVerificationBandTop = Integer.MIN_VALUE;
            snakeRouteComplete = false;
            snakeBandTop = Integer.MIN_VALUE;
            snakeCursor = Integer.MIN_VALUE;
            snakeTurnCross = Integer.MIN_VALUE;
            logDirect("AutoDig verification found " + residual
                    + " fallen or remaining block(s); clearing the band again before descent");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // This is the actual completion check: both methods enumerate the WHOLE committed band/shell, independent
        // of player position, current reach and the builder's mutable work set. Require the same empty result for
        // four live ticks so a delayed gravity/fluid update cannot slip through the transition. When it is already
        // clean there is deliberately no pathing command and therefore no false bench "stall" before descent.
        int openSources = snakeBandSourceCount(bcc, bandTop, areaSize);
        int openShell = snakeShellGapCount(bcc, bandTop, areaSize);
        if (openSources == 0 && openShell == 0) {
            if (++snakeVerificationStableTicks < 4) {
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            snakeVerificationActive = false;
            snakeVerifiedBandTop = bandTop;
            snakeVerificationBandTop = Integer.MIN_VALUE;
            snakeVerifiedBands++;
            snakeBridgeTarget = null;
            logDirect("AutoDig band verified clean y=" + bandTop + ".." + (bandTop - (areaSize - 1))
                    + " after complete band census (pass " + snakeVerificationPasses + ")");
            return null;
        }
        snakeVerificationStableTicks = 0;

        // A repair is genuinely pending. Traverse the same legal snake lane until its reach-local placement logic
        // can service it; no side detour, pillar or arbitrary A* scaffold is introduced by verification.
        BetterBlockPos target = snakeUpdate(bcc, areaSize, bandTop);
        if (target != null) {
            // A gravity update landed between the census above and this exact slice. Let fullRecalc own the break on
            // the next tick instead of mining from an empty/stale work set.
            snakeVerificationActive = false;
            snakeVerificationBandTop = Integer.MIN_VALUE;
            snakeRouteComplete = false;
            snakeBandTop = Integer.MIN_VALUE;
            snakeCursor = Integer.MIN_VALUE;
            snakeTurnCross = Integer.MIN_VALUE;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        PathingCommand repair = excavationIntegrityPlacementCommand(bcc,
                princeps.getPathingBehavior().isSafeToCancel());
        if (repair != null) {
            return repair;
        }
        if (snakeHead != null && snakeStance != null) {
            // Standing on the corridor level is what turns the level rule below from an approach into a guard.
            if (ctx.playerFeet().y == snakeStance.y) {
                snakeCorridorLevelY = snakeStance.y;
            }
            if (!ctx.playerFeet().equals(snakeStance)) {
                return snakePathingCommand();
            }
            snakeBridgeTarget = null;
            if (!ctx.player().onGround()) {
                return settleInPlacementStance();
            }
            if (!centeredInPlacementStance(snakeStance)) {
                return centerInPlacementStance(snakeStance);
            }
            return settleInPlacementStance();
        }
        if (!snakeRouteComplete) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        residual = snakeBandResidualCount(bcc, bandTop, areaSize);
        if (residual > 0) {
            snakeVerificationActive = false;
            snakeVerificationBandTop = Integer.MIN_VALUE;
            snakeRouteComplete = false;
            snakeBandTop = Integer.MIN_VALUE;
            snakeCursor = Integer.MIN_VALUE;
            snakeTurnCross = Integer.MIN_VALUE;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        openSources = snakeBandSourceCount(bcc, bandTop, areaSize);
        openShell = snakeShellGapCount(bcc, bandTop, areaSize);
        if (openSources > 0 || openShell > 0) {
            if (++snakeVerificationRepairSweeps > 3) {
                String headline = "AutoDig cannot seal completed band y=" + bandTop + " (sources="
                        + openSources + ", shell gaps=" + openShell + ")";
                abortBuild(Ending.LAYER_VERIFICATION_FAILED, headline, java.util.List.of(
                        "The complete route was walked repeatedly without leaving the excavation corridor.",
                        "No descent was attempted; unresolved fluid or shell cells remain."));
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            snakeRouteComplete = false;
            snakeBandTop = Integer.MIN_VALUE;
            snakeCursor = Integer.MIN_VALUE;
            snakeVerificationSettleTicks = 2;
            snakeVerificationStableTicks = 0;
            logDirect("AutoDig repeats the band check to seal " + openSources + " source(s) and "
                    + openShell + " shell gap(s)");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // The last repair was just observed. Let the complete census above establish four fresh stable ticks before
        // releasing the band; certifying directly in the placement tick would race the server's next fluid update.
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private int snakeBandResidualCount(BuilderCalculationContext bcc, int bandTop, int areaSize) {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (full == null || origin == null) {
            return 0;
        }
        int floor = Math.max(origin.getY(), bandTop - (areaSize - 1));
        int count = 0;
        for (int y = floor; y <= bandTop; y++) {
            int localY = y - origin.getY();
            for (int dx = 0; dx < full.widthX(); dx++) {
                for (int dz = 0; dz < full.lengthZ(); dz++) {
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    BlockState current = bcc.bsi.get0(x, y, z);
                    if (current.isAir() || snakeIsFluid(current)
                            || !full.inSchematic(dx, localY, dz, current)) {
                        continue;
                    }
                    BlockState desired = full.desiredState(dx, localY, dz, current, approxPlaceable);
                    if (desired != null && desired.isAir()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int snakeBandSourceCount(BuilderCalculationContext bcc, int bandTop, int areaSize) {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (full == null || origin == null) {
            return 0;
        }
        int floor = Math.max(origin.getY(), bandTop - (areaSize - 1));
        int count = 0;
        for (int y = floor; y <= bandTop; y++) {
            for (int x = origin.getX(); x < origin.getX() + full.widthX(); x++) {
                for (int z = origin.getZ(); z < origin.getZ() + full.lengthZ(); z++) {
                    if (bcc.bsi.get0(x, y, z).getFluidState().isSource()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Air or fluid in the four side walls for this band, plus the roof when this is the top band. */
    private int snakeShellGapCount(BuilderCalculationContext bcc, int bandTop, int areaSize) {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (full == null || origin == null) {
            return 0;
        }
        int minX = origin.getX(), maxX = minX + full.widthX() - 1;
        int minZ = origin.getZ(), maxZ = minZ + full.lengthZ() - 1;
        int floor = Math.max(origin.getY(), bandTop - (areaSize - 1));
        int gaps = 0;
        for (int y = floor; y <= bandTop; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (snakeShellCellNeedsBlock(bcc, minX - 1, y, z)) gaps++;
                if (snakeShellCellNeedsBlock(bcc, maxX + 1, y, z)) gaps++;
            }
            for (int x = minX; x <= maxX; x++) {
                if (snakeShellCellNeedsBlock(bcc, x, y, minZ - 1)) gaps++;
                if (snakeShellCellNeedsBlock(bcc, x, y, maxZ + 1)) gaps++;
            }
        }
        int top = origin.getY() + full.heightY() - 1;
        if (bandTop == top) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (snakeShellCellNeedsBlock(bcc, x, top + 1, z)) gaps++;
                }
            }
        }
        return gaps;
    }

    private boolean snakeShellCellNeedsBlock(BuilderCalculationContext bcc, int x, int y, int z) {
        BlockState state = bcc.bsi.get0(x, y, z);
        return snakeShellRequiresSeal(state, MovementHelper.isReplaceable(x, y, z, state, bcc.bsi));
    }

    private record SnakeRepair(BetterBlockPos pos, ExcavationRepairPolicy.Kind kind, int distanceSq) {
    }

    private boolean ordinaryExcavation() {
        return excavating && effectiveAreaBreakSize() == 1;
    }

    /** Last-moment guard shared by builder and navigation inputs, including every actual Shard neighbour. */
    public boolean ordinaryExcavationBreakAllowed(BlockPos target) {
        if (!isActive() || !excavating) return true;
        if (paused || abortPending != Ending.RUNNING) {
            return false;
        }
        if (!insideSnakeVolume(target.getX(), target.getY(), target.getZ())) {
            return excavationApproachBreakAllowed(target);
        }
        boolean areaTool = snakeIsAreaTool(ctx.player().getMainHandItem());
        Direction face = ctx.objectMouseOver() instanceof BlockHitResult hit ? hit.getDirection() : null;
        if (areaTool && (face == null || !snakeAreaCutAllowed(target, face))) return false;
        if (!areaTool && !excavationMayMinePlug(target, false)) return false;
        if (!ordinaryExcavation()) return true;
        BlockState state = ctx.world().getBlockState(target);
        int x = target.getX() - origin.getX(), y = target.getY() - origin.getY(), z = target.getZ() - origin.getZ();
        BlockState desired = schematic.inSchematic(x, y, z, state)
                ? schematic.desiredState(x, y, z, state, approxPlaceable) : null;
        return ExcavationRepairPolicy.ordinaryBreakAllowed(true, desired != null && desired.isAir(),
                areaTool);
    }

    private PathExecutor ownedExcavationApproachRoute() {
        if (!isActive() || !excavating || paused || abortPending != Ending.RUNNING
                || princeps.getPathingControlManager().mostRecentInControl().orElse(null) != this) return null;
        PathExecutor route = princeps.getPathingBehavior().getCurrent();
        PathingCommand command = princeps.getPathingControlManager().mostRecentCommand().orElse(null);
        Object commandToken = command instanceof PathingCommandContext contextual
                ? contextual.desiredCalcContext.excavationApproachToken() : null;
        return route != null && excavationApproach.owns(commandToken, route.excavationApproachToken())
                ? route : null;
    }

    private boolean excavationApproachBreakAllowed(BlockPos target) {
        PathExecutor route = ownedExcavationApproachRoute();
        if (route == null || !ExcavationApproach.requiresBreak(route.getPath(), route.getPosition(), target)
                || snakeIsAreaTool(ctx.player().getMainHandItem())
                || excavationApproachToolChangedTick == buildTick) return false;
        var survival = princeps.getSurvivalBehavior();
        if (survival != null && (survival.ownsInventory() || survival.isConsuming())) return false;
        BlockState state = ctx.world().getBlockState(target);
        int ordinary = snakeOrdinaryPickSlot(state);
        return ordinary >= 0 && ordinary == ctx.player().getInventory().getSelectedSlot()
                && (Princeps.settings().allowBreak.value
                    || Princeps.settings().allowBreakAnyway.value.contains(state.getBlock()))
                && state.getFluidState().isEmpty()
                && !MovementHelper.avoidBreaking(princeps.bsi, target.getX(), target.getY(), target.getZ(), state)
                && excavationMayMinePlug(target, false);
    }

    /** Select an eligible ordinary mining tool and settle its slot before the single-cell access cut. */
    public boolean selectExcavationApproachTool(BlockState state) {
        if (ownedExcavationApproachRoute() == null) return false;
        int wanted = snakeOrdinaryPickSlot(state);
        if (wanted < 0) {
            abortBuild(Ending.MATERIALS_MISSING, "AutoDig needs an ordinary mining tool to reach its entry",
                    List.of("Put an ordinary pickaxe, axe, shovel, or hoe on the hotbar to continue."));
        } else if (wanted != ctx.player().getInventory().getSelectedSlot()) {
            ctx.player().getInventory().setSelectedSlot(wanted);
            excavationApproachToolChangedTick = buildTick;
        }
        return true;
    }

    private PathingCommand initialExcavationApproach(Goal requested) {
        if (!excavating || !excavationApproach.pending()) return null;
        Object previous = excavationApproach.token();
        Object token = excavationApproach.commit(requested, ctx.playerFeet());
        if (token == null) return null;
        if (previous != token) princeps.getPathingBehavior().softCancelIfSafe();
        BuilderCalculationContext context = new BuilderCalculationContext(Lane.EXCAVATION_APPROACH);
        snakeDiagnosis = "t=" + buildTick + " AutoDig approaches its initial entry using ordinary route cuts";
        return new PathingCommandContext(excavationApproach.entry(),
                PathingCommandType.SET_GOAL_AND_PATH, context);
    }

    /** An already reachable working cut ends initial travel even when no entry route was needed. */
    void noteExcavationWorkCut(BlockPos target) {
        if (excavating && insideSnakeVolume(target.getX(), target.getY(), target.getZ())) excavationApproach.clear();
    }

    private Optional<ExcavationFluidPlugs.Hazard> excavationPlugHazard(BlockPos target, Direction face,
                                                                     boolean areaTool) {
        if (!excavating || target == null || excavationFluidPlugs.size() == 0) return Optional.empty();
        return excavationFluidPlugs.firstHazard(ExcavationFluidPlugs.footprint(target, face, areaTool), ctx.world(),
                ExcavationFluidPlugs::vanillaSourceConversion);
    }

    /** Mining permission only. Work sets and completion censuses must continue to count the retained plug. */
    private boolean excavationMayMinePlug(BlockPos target, boolean recordBlockedWork) {
        Optional<ExcavationFluidPlugs.Hazard> hazard = excavationPlugHazard(target, null, false);
        if (recordBlockedWork && blockedFluidPlugThisTick == null) blockedFluidPlugThisTick = hazard.orElse(null);
        return hazard.isEmpty();
    }

    private boolean snakeAreaCutAllowed(BlockPos target, Direction face) {
        return snakeAreaFootprintInsideSelection(target, face)
                && (!excavating || snakeAreaFootprintInsideActiveBand(target, face, snakeBandFloor, snakeBandTop))
                && excavationPlugHazard(target, face, true).isEmpty();
    }

    static boolean snakeAreaFootprintInsideActiveBand(BlockPos target, Direction face, int bandFloor, int bandTop) {
        if (target == null || face == null || bandFloor == Integer.MIN_VALUE || bandTop == Integer.MIN_VALUE) {
            return false;
        }
        // Removing a plug's support is a valid simultaneous cut only when that support belongs to this band.
        // Opening the next band's floor would strand the current route, even though it is inside the selection.
        int verticalRadius = face.getAxis() == Direction.Axis.Y ? 0 : 1;
        return target.getY() - verticalRadius >= bandFloor && target.getY() + verticalRadius <= bandTop;
    }

    private PathingCommand ordinaryWetApproachCommand(BuilderCalculationContext bcc, boolean safeToCancel) {
        BetterBlockPos step = ordinaryWetApproachStep(bcc, safeToCancel);
        return step == null ? null : excavationLevelPathingCommand(ctx.playerFeet(), step);
    }

    private BetterBlockPos ordinaryWetApproachStep(BuilderCalculationContext bcc, boolean safeToCancel) {
        if (!ordinaryExcavation() || !safeToCancel || incorrectPositions == null
                || !(ctx.player().onGround() || ctx.player().isInWater())) return null;
        ExcavationRepairPolicy.Bounds bounds = excavationRepairBounds();
        if (bounds == null) return null;
        BetterBlockPos feet = ctx.playerFeet();
        BetterBlockPos bestStep = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BetterBlockPos work : incorrectPositions) {
            BlockState state = bcc.bsi.get0(work);
            BlockState desired = bcc.getSchematic(work.x, work.y, work.z, state);
            if (desired == null || !desired.isAir() || state.isAir() || isCellParked(work.x, work.y, work.z)
                    || (snakeTreatAsFluid(state, true) && !snakeSourceReadyForPlug(state, true))) continue;
            BetterBlockPos step = ExcavationRepairPolicy.ordinaryWetStep(bounds, feet, work,
                    pos -> bcc.bsi.get0(pos.getX(), pos.getY(), pos.getZ()));
            if (step != null && work.distSqr(feet) < bestDistance) {
                bestStep = step;
                bestDistance = work.distSqr(feet);
            }
        }
        return bestStep;
    }

    private boolean shallowExcavation() {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        return full != null && ShallowExcavationPolicy.surfaceMode(excavating, full.heightY());
    }

    private BetterBlockPos shallowCoveredWork(BuilderCalculationContext bcc) {
        if (!shallowExcavation() || incorrectPositions == null) return null;
        for (BetterBlockPos cell : incorrectPositions) {
            if (!snakeCellNeedsClear(cell, bcc)) continue;
            BlockPos roof = cell.above();
            if (ShallowExcavationPolicy.roofBlocksStanding(bcc.get(roof), ctx.world(), roof)) return cell;
        }
        return null;
    }

    private ExcavationRepairPolicy.Bounds excavationRepairBounds() {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        boolean ordinary = ordinaryExcavation();
        if (full == null || origin == null
                || (!ordinary && (snakeHead == null || snakeBandTop == Integer.MIN_VALUE))) return null;
        int floor = ordinary ? origin.getY() + (Princeps.settings().buildInLayers.value ? bandMinYLocal : 0)
                : snakeBandFloor;
        int top = ordinary ? origin.getY() + (Princeps.settings().buildInLayers.value
                ? bandMaxYLocal : full.heightY() - 1) : snakeBandTop;
        ExcavationRepairPolicy.Bounds bounds = new ExcavationRepairPolicy.Bounds(origin.getX(), origin.getX() + full.widthX() - 1,
                origin.getY(), origin.getY() + full.heightY() - 1,
                origin.getZ(), origin.getZ() + full.lengthZ() - 1, floor, top, ordinary);
        // Layer zero is deliberately empty. Its top still equals the selection top, so treating it as a real
        // band would seal the occupied roof entry before the normal layer transition can offer any mining work.
        return bounds.hasActiveBand() ? bounds : null;
    }

    /**
     * Sources and shell gaps are serviced before the next mining click. The scan is deliberately reach-local: the
     * serpentine route visits the whole band, so locality makes every source react immediately without selecting a
     * remote repair that would pull navigation away from the tunnel. A safely cancellable walking command is not a
     * reason to wait: area reach often exposes a shoulder source while the body is still finishing its current step,
     * and those few ticks are enough for water to fan out. The repair preempts that route and the same snake stance
     * resumes immediately afterwards.
     */
    private PathingCommand excavationIntegrityPlacementCommand(BuilderCalculationContext bcc, boolean safeToCancel) {
        // ON GROUND *OR* IN WATER, and the difference is the whole run. This gate is here to keep a FALLING body from
        // placing -- a sensible rule, and it happens to also switch off the one repair that can end a flood, at the
        // exact moment the flood is what put the body in the air.
        //
        // Measured in run e71652ca, and it is a self-locking cycle rather than a stall: water rises, the generic
        // anti-drowning reflex in Movement.update lifts the bot to dest.y+0.6, onGround goes false -- and with it go
        // the swing gate, the bridge, AND this. Nothing is left that could seal a source, so the water never leaves,
        // so the bot never lands. 1191 ticks at y=-50.282 with eighteen sources standing open in reach.
        //
        // A body IN WATER is not a falling body: it is supported, its aim is steady, and it is exactly where the
        // sources it must plug are. The falling case stays refused.
        ExcavationRepairPolicy.Bounds bounds = excavationRepairBounds();
        if (bounds == null || !safeToCancel
                || !(ctx.player().onGround() || ctx.player().isInWater())) {
            return null;
        }
        BetterBlockPos feet = ctx.playerFeet();
        List<SnakeRepair> candidates = new ArrayList<>();
        int reach = Math.max(4, (int) Math.ceil(ctx.playerController().getBlockReachDistance()));
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -2; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    int distanceSq = dx * dx + dy * dy + dz * dz;
                    if (distanceSq > reach * reach) {
                        continue;
                    }
                    int x = feet.x + dx, y = feet.y + dy, z = feet.z + dz;
                    BlockState state = bcc.bsi.get0(x, y, z);
                    ExcavationRepairPolicy.Kind kind = ExcavationRepairPolicy.repair(bounds, x, y, z, state,
                            MovementHelper.isReplaceable(x, y, z, state, bcc.bsi), excavating);
                    if (kind != null) candidates.add(new SnakeRepair(new BetterBlockPos(x, y, z), kind, distanceSq));
                }
            }
        }
        candidates.sort(Comparator.comparingInt((SnakeRepair repair) ->
                        excavating && excavationRepairAim.heldFace(ctx.world(), repair.pos()).isPresent()
                                ? -1 : repair.kind().ordinal())
                .thenComparingInt(SnakeRepair::distanceSq)
                .thenComparingInt(repair -> repair.pos().y)
                .thenComparingInt(repair -> repair.pos().x)
                .thenComparingInt(repair -> repair.pos().z));
        for (SnakeRepair candidate : candidates) {
            BlockState fill = snakeIntegrityBlockState(candidate.pos());
            if (fill == null) {
                abortBuild(Ending.MATERIALS_MISSING,
                        "AutoDig needs a full throwaway block to seal fluids and excavation walls",
                        java.util.List.of("First unresolved cell: " + candidate.pos().x + ","
                                + candidate.pos().y + "," + candidate.pos().z));
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            ExcavationRepairAim.Face heldFace = excavating
                    ? excavationRepairAim.heldFace(ctx.world(), candidate.pos()).orElse(null) : null;
            if (heldFace != null && (!heldFace.targetState().equals(ctx.world().getBlockState(candidate.pos()))
                    || !heldFace.supportState().equals(ctx.world().getBlockState(BlockPos.of(heldFace.support()))))) {
                excavationRepairAim.clear();
                heldFace = null;
            }
            // Keep the exact support/face while solving its rotation again from the current sneaking eye.
            // A different face that happens to sort first is not a reason to abandon a valid ongoing aim.
            java.util.function.Predicate<Placement> repairFaceFilter = excavating
                    ? placement -> excavationRepairFaceReady(placement, candidate, bounds, bcc) : null;
            Optional<Placement> option = possibleToPlace(fill, candidate.pos().x, candidate.pos().y,
                    candidate.pos().z, bcc, heldFace, repairFaceFilter);
            if (option.isEmpty()) {
                Item wanted = fill.getBlock().asItem();
                boolean alreadyOnHotbar = false;
                for (int slot = 0; slot < 9; slot++) {
                    if (ctx.player().getInventory().getNonEquipmentItems().get(slot).getItem() == wanted) {
                        alreadyOnHotbar = true;
                        break;
                    }
                }
                if (!alreadyOnHotbar && princeps.getInventoryBehavior().throwaway(true,
                        stack -> !stack.isEmpty() && stack.getItem() == wanted)) {
                    // A missing hotbar stack is not loss of the held face. Preserve it across the inventory packet.
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            if (option.isEmpty() && heldFace != null) {
                excavationRepairAim.clear();
                option = possibleToPlace(fill, candidate.pos().x, candidate.pos().y, candidate.pos().z, bcc,
                        null, repairFaceFilter);
            }
            if (option.isPresent()) return snakeIntegrityPlacementClick(option.get(), candidate, bcc);
        }
        excavationRepairAim.clear();
        return null;
    }

    private boolean excavationRepairFaceReady(Placement placement, SnakeRepair candidate,
                                               ExcavationRepairPolicy.Bounds bounds, BuilderCalculationContext bcc) {
        BlockState liveTarget = ctx.world().getBlockState(candidate.pos());
        if (ExcavationRepairPolicy.repair(bounds, candidate.pos().x, candidate.pos().y, candidate.pos().z,
                liveTarget, MovementHelper.isReplaceable(candidate.pos().x, candidate.pos().y, candidate.pos().z,
                        liveTarget, bcc.bsi), true) != candidate.kind()) {
            excavationRepairAim.clear();
            return false;
        }
        BlockState liveSupport = ctx.world().getBlockState(placement.placeAgainst);
        VoxelShape supportShape = liveSupport.getShape(ctx.world(), placement.placeAgainst);
        ExcavationRepairAim.Face face = new ExcavationRepairAim.Face(candidate.pos().asLong(),
                placement.placeAgainst.asLong(), placement.side, liveTarget, liveSupport,
                supportShape.isEmpty() ? null : supportShape.bounds());
        boolean ready = excavationRepairAim.mayAim(ctx.world(), face, ctx.player().position(), ctx.player().getBbWidth(),
                ctx.player().isInWater(), !supportShape.isEmpty());
        traceExcavationRepairAim(ready ? "AIM" : "APPROACH", placement);
        return ready;
    }

    private void traceExcavationRepairAim(String phase, Placement placement) {
        String detail = phase + " support=" + placement.placeAgainst.toShortString() + "/" + placement.side;
        String identity = placement.target.toShortString() + " " + detail;
        if (!identity.equals(lastExcavationRepairAimTrace) || buildTick - lastExcavationRepairAimTraceTick >= 20) {
            BuildTrace.cell(buildTick, "DIG-REPAIR-AIM", placement.target.getX(), placement.target.getY(),
                    placement.target.getZ(), detail);
            lastExcavationRepairAimTrace = identity;
            lastExcavationRepairAimTraceTick = buildTick;
        }
    }

    /** Ordinary mining still owes the same dry, closed boundary before it may descend or finish. */
    private PathingCommand ordinaryExcavationIntegrityCommand(BuilderCalculationContext bcc,
                                                              boolean safeToCancel, boolean hasWork) {
        if (!ordinaryExcavation()) return null;
        ExcavationRepairPolicy.Bounds bounds = excavationRepairBounds();
        if (bounds == null) return null;
        if (ordinaryIntegrityBandTop != bounds.bandTop()) {
            ordinaryIntegrityBandTop = bounds.bandTop();
            ordinaryIntegrityStableTicks = 0;
            ordinaryIntegrityObservedTick = Long.MIN_VALUE;
        }
        ExcavationRepairPolicy.Census census = ExcavationRepairPolicy.inspect(bounds,
                pos -> bcc.bsi.get0(pos.getX(), pos.getY(), pos.getZ()), pos -> {
                    BlockState state = bcc.bsi.get0(pos.getX(), pos.getY(), pos.getZ());
                    return MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), state, bcc.bsi);
                });
        PathingCommand hold = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        if (census.clean()) {
            ordinaryIntegrityTarget = null;
            ordinaryIntegrityTargetTicks = 0;
            if (ordinaryIntegrityObservedTick != buildTick) {
                ordinaryIntegrityObservedTick = buildTick;
                ordinaryIntegrityStableTicks++;
            }
            return ordinaryIntegrityStableTicks < 4 ? hold : null;
        }
        ordinaryIntegrityStableTicks = 0;
        if (census.repairs().isEmpty()) {
            ordinaryIntegrityTarget = null;
            ordinaryIntegrityTargetTicks = 0;
            if (census.solid() > 0) {
                // A placement/gravity packet can arrive after recalc. Put that real block back into normal mining.
                if (!hasWork) incorrectPositions = null;
                return hasWork ? null : hold;
            }
            // Finite flow remains unresolved until the world acknowledges AIR; never repeatedly plug its tail.
            return hold;
        }
        BetterBlockPos feet = ctx.playerFeet();
        ExcavationRepairPolicy.Target target = census.repairs().stream()
                .min(Comparator.comparingDouble(repair -> repair.pos().distSqr(feet))).orElseThrow();
        if (target.pos().equals(ordinaryIntegrityTarget)) {
            ordinaryIntegrityTargetTicks++;
        } else {
            ordinaryIntegrityTarget = target.pos();
            ordinaryIntegrityTargetTicks = 0;
        }
        if (ordinaryIntegrityTargetTicks > 600) {
            abortBuild(Ending.LAYER_VERIFICATION_FAILED, "AutoDig cannot reach an unresolved excavation repair",
                    java.util.List.of("Unresolved " + target.kind() + " at " + target.pos(),
                            "The layer remains unfinished; no outside route or vertical scaffold was attempted."));
            return hold;
        }
        PathingCommand repair = excavationIntegrityPlacementCommand(bcc, safeToCancel);
        if (repair != null) return repair;
        if (!safeToCancel || !(ctx.player().onGround() || ctx.player().isInWater())) return hold;
        // The existing one-edge route and bridge licence serve both excavation modes. A wall target itself is
        // outside the selection; its approach cell never is. No remote source is made into an outside GoalBlock.
        BetterBlockPos approach = bounds.approach(feet, target.pos());
        if (approach == null) return hasWork ? null : hold;
        if (!feet.equals(approach)) return excavationLevelPathingCommand(feet, approach);
        if (!centeredInPlacementStance(approach)) return centerInPlacementStance(approach);
        return hold;
    }

    static boolean snakeShellCoordinate(int x, int y, int z, int minX, int maxX, int minZ, int maxZ,
                                        int bandFloor, int bandTop, int selectionTop) {
        boolean sideBand = y >= bandFloor && y <= bandTop
                && (((x == minX - 1 || x == maxX + 1) && z >= minZ && z <= maxZ)
                    || ((z == minZ - 1 || z == maxZ + 1) && x >= minX && x <= maxX));
        boolean roof = bandTop == selectionTop && y == selectionTop + 1
                && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        return sideBand || roof;
    }

    private BlockState snakeIntegrityBlockState(BlockPos at) {
        if (approxPlaceable == null) {
            return null;
        }
        for (BlockState state : approxPlaceable) {
            if (state == null || state.isAir() || state.getBlock() instanceof FallingBlock
                    || !Princeps.settings().acceptableThrowawayItems.value.contains(state.getBlock().asItem())) {
                continue;
            }
            if (state.isCollisionShapeFullBlock(ctx.world(), at) && !placementStateIsGeometrySensitive(state)) {
                return state;
            }
        }
        return null;
    }

    private PathingCommand snakeIntegrityPlacementClick(Placement placement, SnakeRepair repair,
                                                        BuilderCalculationContext bcc) {
        princeps.getLookBehavior().updateTarget(placement.rot, true, AimIntent.PLACE);
        ctx.player().getInventory().setSelectedSlot(placement.hotbarSelection);
        princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        HitResult mouseOver = ctx.objectMouseOver();
        boolean aligned = mouseOver != null && mouseOver.getType() == HitResult.Type.BLOCK
                && ((BlockHitResult) mouseOver).getBlockPos().equals(placement.placeAgainst)
                && ((BlockHitResult) mouseOver).getDirection() == placement.side;
        BlockPlaceHelper helper = princeps.getInputOverrideHandler().getBlockPlaceHelper();
        if (aligned && liveRayWouldPlaceDesired(placement, bcc) && !helper.isThrottled()) {
            Item expected = placement.desired.getBlock().asItem();
            helper.expectExcavationIntegrityPlacement(placement.placeAgainst, placement.side, placement.target,
                    placement.hotbarSelection, expected, () -> liveRayWouldPlaceDesired(placement, bcc));
            String kind = repair.kind() == ExcavationRepairPolicy.Kind.INTERNAL_SOURCE ? "autodig-source"
                    : repair.kind() == ExcavationRepairPolicy.Kind.BRIDGE ? "autodig-bridge" : "autodig-shell";
            BuildTrace.intendWorldChange(kind, placement.target.getX(), placement.target.getY(),
                    placement.target.getZ(), "seal before next excavation action");
            princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            if (repair.kind() == ExcavationRepairPolicy.Kind.INTERNAL_SOURCE) {
                snakeFluidSourcesPlugged++;
            } else if (repair.kind() == ExcavationRepairPolicy.Kind.BRIDGE) {
                snakeFloorRepairs++;
            } else {
                snakeShellRepairs++;
            }
            snakeDiagnosis = "t=" + buildTick + " AutoDig seals " + repair.kind() + " at "
                    + repair.pos().x + "," + repair.pos().y + "," + repair.pos().z;
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private BetterBlockPos findSafeSnakeStance(BetterBlockPos head, BetterBlockPos preferred,
                                                Direction face) {
        if (isStandable(preferred.x, preferred.y, preferred.z)) {
            return preferred;
        }
        // A STANCE STILL BURIED IN ROCK IS NOT SOMETHING TO ROUTE AROUND -- IT IS THE JOB. isStandable also refuses
        // a cell that is simply still solid, which is the normal state of every lane start inside a cube: the
        // pathfinder is meant to mine its way in. Asking it here fired the detour at every lane start and handed
        // back the first standable cell of the bot's OWN approach corridor, one block short of arrival and
        // DIAGONAL to the head -- from where the ray hits intact rock instead of the face the swing is keyed to,
        // so no click is ever forced. Measured: two cells cleared out of 8000, a hundred seconds of "ready=true"
        // with an empty rotation. It was intermittent only because it depends on whether that corridor happens to
        // run at band-floor height.
        //
        // Only a stance that can NEVER hold the bot needs an alternative: one whose FLOOR is gone -- which is what
        // the message below has been claiming all along, for every reason isStandable can refuse.
        if (MovementHelper.canWalkOn(ctx, preferred.below())) {
            return preferred;
        }
        // Choose the shoulder nearest the bot first. It avoids a needless cross-tunnel step without changing the
        // fixed lane order or the face/footprint of the Shard swing.
        int currentSide = face.getAxis() == Direction.Axis.Z
                ? Integer.signum(ctx.playerFeet().x - head.x)
                : Integer.signum(ctx.playerFeet().z - head.z);
        int firstSide = currentSide == 0 ? -1 : currentSide;
        // ONLY THE AXIS. An off-axis stance is now refused by the swing gate, so offering one here would send the
        // bot walking to a cell it can never swing from -- the gate would be unsatisfiable and the walk endless.
        // A null return falls back to preferredStance, which is square by construction at both the lane and the
        // turn, so nothing is lost but the wrong answers.
        int[] sides = {0};
        // SQUARE BEFORE NEAR, and the loops are in this order for that reason alone.
        //
        // They used to run depth on the outside, so every sideways offset at depth one was tried before the cell
        // standing straight in front at depth two. A stance two cells to the side is a DIAGONAL view of the block,
        // and a Shard swing is keyed to the face the ray actually strikes -- so a diagonal view does not merely
        // aim worse, it turns the three-by-three into a different plane and clears nine cells nobody asked for.
        //
        // The owner watched this at the lane change, which is where it bites: coming out of a lane the bot is
        // still standing along the old one, the next slice is off to the side, and a near-but-skewed cell was
        // always available and always preferred. His own words: it stands too far off, the angle is wrong, it
        // does not hit the three-by-three the way it should -- it should step closer or stand somewhere else.
        //
        // Trying every depth square-on before considering any offset is exactly that. Distance costs nothing here:
        // the face centre is the same face centre from one cell back or four, and the retreat is already capped at
        // the furthest that stays inside reach. Only the ANGLE changes what comes out.
        for (int side : sides) {
            for (int depth = 1; depth <= 4; depth++) {
                int x = head.x + face.getStepX() * depth;
                int z = head.z + face.getStepZ() * depth;
                if (face.getAxis() == Direction.Axis.Z) {
                    x += side;
                } else {
                    z += side;
                }
                if (!insideSnakeFootprint(x, z)) {
                    continue;
                }
                BetterBlockPos candidate = new BetterBlockPos(x, preferred.y, z);
                // AND IT HAS TO BE ABLE TO REACH THE BLOCK FROM THERE. The retreat is capped at four cells because
                // four is the furthest axial distance whose face centre still falls inside NORMAL reach -- true at
                // 4.5, false at anything shorter, and the reach is configurable. A stance that cannot swing is not
                // a safe stance, it is a place to stand and wait forever with the crosshair already on the target.
                if (isStandable(candidate.x, candidate.y, candidate.z) && snakeCanSwingFrom(candidate, head)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Whether the head's face centre is inside the configured reach from a standing eye at this stance. */
    private boolean snakeCanSwingFrom(BetterBlockPos stance, BetterBlockPos head) {
        double reach = ctx.playerController().getBlockReachDistance();
        double dx = (head.x + 0.5D) - (stance.x + 0.5D);
        double dy = (head.y + 0.5D) - (stance.y + ctx.player().getEyeHeight());
        double dz = (head.z + 0.5D) - (stance.z + 0.5D);
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private boolean insideSnakeFootprint(int x, int z) {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        return full != null && origin != null
                && x >= origin.getX() && x < origin.getX() + full.widthX()
                && z >= origin.getZ() && z < origin.getZ() + full.lengthZ();
    }

    private static Direction horizontalFaceToward(BlockPos head, BlockPos stance) {
        int dx = stance.getX() - head.getX();
        int dz = stance.getZ() - head.getZ();
        if (dx != 0 && dz == 0) {
            return dx < 0 ? Direction.WEST : Direction.EAST;
        }
        if (dz != 0 && dx == 0) {
            return dz < 0 ? Direction.NORTH : Direction.SOUTH;
        }
        return null;
    }

    /** Whether the nine cells this swing would clear still hold anything the job wants gone. */
    private boolean snakeSliceHasWork(BetterBlockPos head, BuilderCalculationContext bcc, int floorY, int topY) {
        for (int y = floorY; y <= topY; y++) {
            for (int side = -1; side <= 1; side++) {
                int x = head.x + (snakeCrossIsX ? side : 0);
                int z = head.z + (snakeCrossIsX ? 0 : side);
                BlockState current = bcc.bsi.get0(x, y, z);
                if (current.isAir() || snakeIsFluid(current)) {
                    continue;
                }
                BlockState desired = bcc.getSchematic(x, y, z, current);
                if (desired != null && desired.isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a vertical end-cap plane still contains a block belonging to this clear job. */
    private boolean snakeTurnSliceHasWork(int cross, int travel, BuilderCalculationContext bcc,
                                          int floorY, int topY, int travelMin, int travelMax) {
        for (int y = floorY; y <= topY; y++) {
            for (int side = -1; side <= 1; side++) {
                int along = travel + side;
                if (along < travelMin || along > travelMax) {
                    continue;
                }
                int x = snakeCrossIsX ? cross : along;
                int z = snakeCrossIsX ? along : cross;
                BlockState current = bcc.bsi.get0(x, y, z);
                if (current.isAir() || snakeIsFluid(current)) {
                    continue;
                }
                BlockState desired = bcc.getSchematic(x, y, z, current);
                if (desired != null && desired.isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Exact face geometry for a 3x3 swing. A generic reachable ray may legally choose any visible face; for an area
     * tool that changes the entire break plane. The snake instead owns one face: UP while entering, otherwise the
     * vertical face looking back toward its adjacent stance. The predicted, aim-processor-quantised ray must hit both
     * that block and that face before this rotation is offered to the click branch.
     */
    /** DIAGNOSTIC (added while investigating the horizontal-head stall). */
    private String describeMouseOver() {
        HitResult result = ctx.objectMouseOver();
        if (result == null) {
            return "null";
        }
        if (result.getType() != HitResult.Type.BLOCK) {
            return String.valueOf(result.getType());
        }
        BlockHitResult hit = (BlockHitResult) result;
        return hit.getBlockPos().getX() + "," + hit.getBlockPos().getY() + "," + hit.getBlockPos().getZ()
                + "/" + hit.getDirection();
    }

    private Optional<Rotation> snakeHeadRotation() {
        Direction face = snakeExpectedFace();
        if (snakeHead == null || face == null) {
            snakeRotWhy = "no head or no face";
            return Optional.empty();
        }
        if (!snakeEntering) {
            Optional<Rotation> current = excavationCurrentMiningLook(snakeHead, face,
                    !snakeSingleBlockFallback && !snakeRequiresOrdinaryTool);
            if (current.isPresent()) {
                snakeRotWhy = "current ray already matches the selected face";
                return current;
            }
        }
        Vec3 aim = new Vec3(snakeHead.x + 0.5D + face.getStepX() * 0.5D,
                snakeHead.y + 0.5D + face.getStepY() * 0.5D,
                snakeHead.z + 0.5D + face.getStepZ() * 0.5D);
        Vec3 eye = ctx.player().getEyePosition(1.0F);
        if (eye.distanceToSqr(aim) > Mth.square(ctx.playerController().getBlockReachDistance())) {
            snakeRotWhy = "out of reach d=" + String.format(java.util.Locale.ROOT, "%.2f",
                    Math.sqrt(eye.distanceToSqr(aim)));
            return Optional.empty();
        }
        // Looking straight down has no geometrically meaningful yaw, but the Shard tool uses that yaw to select its
        // vertical plane. Pin it to the lane axis during entry; otherwise atan2(0,0) can leave it facing across the
        // lane and open the wrong trench despite hitting the correct UP face.
        Rotation raw = snakeEntering
                ? new Rotation(snakeCrossIsX ? 0.0F : -90.0F, 90.0F)
                : RotationUtils.calcRotationFromVec3d(eye, aim, ctx.playerRotations());
        Rotation actual = princeps.getLookBehavior().getAimProcessor().peekRotationExact(raw);
        HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actual,
                ctx.playerController().getBlockReachDistance(), false);
        if (result == null || result.getType() != HitResult.Type.BLOCK) {
            snakeRotWhy = "predicted ray hit " + (result == null ? "null" : result.getType());
            return Optional.empty();
        }
        BlockHitResult hit = (BlockHitResult) result;
        if (hit.getBlockPos().equals(snakeHead) && hit.getDirection() == face) {
            snakeRotWhy = "ok";
            return Optional.of(raw);
        }
        snakeRotWhy = "predicted ray hit " + hit.getBlockPos().getX() + "," + hit.getBlockPos().getY() + ","
                + hit.getBlockPos().getZ() + "/" + hit.getDirection() + " wanted face " + face;
        return Optional.empty();
    }

    /**
     * Finds a visible Shard face whose ENTIRE 3x3 footprint stays inside the selected clear volume.
     *
     * <p>The original slice face wins when it is safe. At an outer lane, however, centring the Shard on an edge
     * leftover would extend that plane one block beyond the requested pit. A perpendicular visible face keeps the
     * same target but rotates the footprint back into the volume. This is what makes the no-second-pickaxe fallback
     * usable in solid terrain rather than only in the bench's air-surrounded cube.
     */
    private Optional<Rotation> snakeCleanupAreaRotation(BetterBlockPos target) {
        Direction preferred = snakeExpectedFace();
        if (target == null || preferred == null) {
            return Optional.empty();
        }
        Vec3 eye = ctx.player().getEyePosition(1.0F);
        Direction[] candidates = {preferred, Direction.UP, Direction.DOWN, Direction.NORTH,
                Direction.SOUTH, Direction.WEST, Direction.EAST};
        EnumSet<Direction> tried = EnumSet.noneOf(Direction.class);
        for (Direction face : candidates) {
            if (!tried.add(face) || !snakeAreaCutAllowed(target, face)) {
                continue;
            }
            Optional<Rotation> current = excavationCurrentMiningLook(target, face, true);
            if (current.isPresent()) {
                snakeCleanupAreaFace = face;
                return current;
            }
            Vec3 aim = new Vec3(target.x + 0.5D + face.getStepX() * 0.5D,
                    target.y + 0.5D + face.getStepY() * 0.5D,
                    target.z + 0.5D + face.getStepZ() * 0.5D);
            if (eye.distanceToSqr(aim) > Mth.square(ctx.playerController().getBlockReachDistance())) {
                continue;
            }
            Rotation raw = RotationUtils.calcRotationFromVec3d(eye, aim, ctx.playerRotations());
            Rotation actual = princeps.getLookBehavior().getAimProcessor().peekRotationExact(raw);
            HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actual,
                    ctx.playerController().getBlockReachDistance(), false);
            if (result == null || result.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            BlockHitResult hit = (BlockHitResult) result;
            if (hit.getBlockPos().equals(target) && hit.getDirection() == face) {
                snakeCleanupAreaFace = face;
                return Optional.of(raw);
            }
        }
        return Optional.empty();
    }

    /** Target election and cut permissions stay with the builder; this only avoids unnecessary recentering. */
    private Optional<Rotation> excavationCurrentMiningLook(BlockPos target, Direction requiredFace, boolean areaTool) {
        if (!excavating) return Optional.empty();
        double reach = ctx.playerController().getBlockReachDistance();
        return ExcavationMiningLook.retain(true, ctx.playerRotations(), target, requiredFace, areaTool,
                rotation -> RayTraceUtils.rayTraceTowards(ctx.player(), rotation, reach, false),
                face -> snakeAreaCutAllowed(target, face));
    }

    private void enforceAutoDigLookProfile() {
        if (autoDigLookProfile == null) {
            autoDigLookProfile = AutoDigLookProfile.apply(Princeps.settings(), excavating);
            logMechanic("AutoDig look profile pinned: curved break/place aims, aimHold=false, deterministic steering");
        } else {
            autoDigLookProfile.enforce();
        }
    }

    /** A replacement job may start while its predecessor is paused and never receive onLostControl first. */
    void resetAutoDigLookProfile() {
        if (autoDigLookProfile != null) {
            autoDigLookProfile.restore();
            autoDigLookProfile = null;
        }
    }

    private boolean snakeAreaFootprintInsideSelection(BlockPos target, Direction face) {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (target == null || face == null || full == null || origin == null) {
            return false;
        }
        int minX = origin.getX();
        int maxX = minX + full.widthX() - 1;
        int minY = origin.getY();
        int maxY = minY + full.heightY() - 1;
        int minZ = origin.getZ();
        int maxZ = minZ + full.lengthZ() - 1;
        return snakeAreaFootprintInsideBounds(target, face, minX, maxX, minY, maxY, minZ, maxZ);
    }

    static boolean snakeAreaFootprintInsideBounds(BlockPos target, Direction face, int minX, int maxX,
                                                  int minY, int maxY, int minZ, int maxZ) {
        if (target == null || face == null) {
            return false;
        }
        for (int first = -1; first <= 1; first++) {
            for (int second = -1; second <= 1; second++) {
                int x = target.getX();
                int y = target.getY();
                int z = target.getZ();
                if (face.getAxis() == Direction.Axis.Y) {
                    x += first;
                    z += second;
                } else if (face.getAxis() == Direction.Axis.X) {
                    y += first;
                    z += second;
                } else {
                    x += first;
                    y += second;
                }
                if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
                    return false;
                }
            }
        }
        return true;
    }

    private Direction snakeExpectedFace() {
        if (snakeEntering) {
            return Direction.UP;
        }
        if (snakeSliceFace != null) {
            return snakeSliceFace;
        }
        if (snakeHead == null || snakeStance == null
                || (snakeHead.y != snakeStance.y && snakeHead.y != snakeStance.y + 1)) {
            return null;
        }
        int dx = snakeStance.x - snakeHead.x;
        int dz = snakeStance.z - snakeHead.z;
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            return null;
        }
        if (dx < 0) return Direction.WEST;
        if (dx > 0) return Direction.EAST;
        if (dz < 0) return Direction.NORTH;
        return Direction.SOUTH;
    }

    private boolean snakeLiveHitMatches(BetterBlockPos pos) {
        Direction face = snakeCleanupWithAreaTool && pos != null && pos.equals(snakeCleanupTarget)
                ? snakeCleanupAreaFace : snakeExpectedFace();
        return face != null && snakeMiningHitMatches(ctx.objectMouseOver(), pos, face);
    }

    static boolean snakeMiningHitMatches(HitResult result, BlockPos target, Direction requiredFace) {
        return result instanceof BlockHitResult hit && result.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target)
                && (requiredFace == null || hit.getDirection() == requiredFace);
    }

    /**
     * Selects the tool that belongs to the snake's current action and waits one tick after a slot change.
     *
     * <p>Full slices require the named Shard pickaxe. An excavation leftover selects the fastest ordinary mining
     * tool; construction retains its ordinary-pickaxe choice. The ordinary selector excludes the Shard, whose
     * footprint needs the separate wide-action policy. Waiting after a change makes the selected-slot packet
     * precede the mining input instead of relying on client event order.
     */
    private boolean snakeToolReady(BlockState targetState, boolean cleanup) {
        // AN EAT OR A REPAIR OWNS THE HANDS, AND THIS MUST NOT ARGUE WITH IT.
        //
        // A repair parks the pickaxe in the offhand and holds bottles of enchanting for the whole session. Every
        // other consumer of ownsInventory() already stands aside for that -- InventoryBehavior twice, MovementHelper
        // once, the v3 builder -- and this was the only one that did not. It was harmless only because the survival
        // behaviour could never START while the digger was mining, which is precisely the bug just fixed on the
        // other side of it. With eating and repair now able to interrupt a break, this would re-select the pickaxe
        // every tick against a behaviour that re-selects the bottle, and after twenty ticks the run would abort with
        // "something else keeps taking the hand back" -- against its own survival system.
        if (princeps.getSurvivalBehavior() != null && princeps.getSurvivalBehavior().ownsInventory()) {
            snakeToolWantedSlot = -1;
            snakeToolWaitTicks = 0;
            snakeToolWaitReported = false;
            return false; // not ready to swing, but nothing is wrong -- the hands are simply busy staying alive
        }
        int wanted = cleanup ? snakeOrdinaryPickSlot(targetState) : snakeAreaToolSlot();
        if (wanted < 0 && cleanup && excavating
                && (snakeEntering || snakeSingleBlockFallback || snakeRequiresOrdinaryTool)) {
            abortBuild(Ending.MATERIALS_MISSING,
                    "AutoDig needs an ordinary mining tool for this single-block cut",
                    java.util.List.of("This cut needs an individual block target and an ordinary mining tool.",
                            "Put an ordinary pickaxe, axe, shovel, or hoe on the hotbar to continue."));
            return false;
        }
        if (wanted < 0 && cleanup) {
            wanted = snakeAreaToolSlot();
        }
        int held = ctx.player().getInventory().getSelectedSlot();
        if (wanted < 0 || wanted == held) {
            snakeToolWantedSlot = -1;
            snakeToolWaitTicks = 0;
            snakeToolWaitReported = false;
            return true;
        }
        // COUNT THE TICKS, NOT THE CALLS. snakeToolReady runs more than once in a tick -- the planning branch asks
        // before it selects a slice and the break branch asks again before it clicks -- so a call counter would
        // trip on a perfectly healthy run within a second.
        if (snakeToolWantedSlot != wanted) {
            snakeToolWantedSlot = wanted;
            snakeToolWaitLastTick = Long.MIN_VALUE;
            snakeToolWaitTicks = 0;
            snakeToolWaitReported = false;
        }
        if (snakeToolWaitLastTick != buildTick) {
            snakeToolWaitLastTick = buildTick;
            snakeToolWaitTicks++;
        }
        ctx.player().getInventory().setSelectedSlot(wanted);
        // ONCE, AND THEN ONCE A SECOND. This line used to be written on every call of every tick, which is what
        // the owner's log looked like at the stall: hundreds of identical sentences a second, each of them true,
        // none of them saying that they were a repeat. A log that repeats a decision faster than a human can read
        // it hides the decision.
        if (snakeToolWaitTicks == 1 || snakeToolWaitTicks % 20 == 0) {
            logMechanic("Snake: selected hotbar " + (wanted + 1) + " for "
                    + (cleanup ? "individual leftover" : "full 3x3 slice")
                    + (snakeToolWaitTicks > 1 ? " (still waiting, " + snakeToolWaitTicks + " ticks)" : ""));
        }
        if (snakeToolWaitTicks <= SNAKE_TOOL_WAIT_LIMIT_TICKS) {
            return false; // the ordinary one-tick wait, so the slot packet precedes the mining input
        }
        // THE HAND HAS ANOTHER OWNER, and no amount of asking will win an argument the asker cannot see. Standing
        // here forever is the worst of the three possible answers: swinging the wrong tool would cut the floor
        // out from under the bot, and waiting silently is what a whole day of "it just stops after the first
        // layer" was made of. So say exactly what is happening, name both slots, and end the run with a reason
        // the operator can act on.
        if (!snakeToolWaitReported) {
            snakeToolWaitReported = true;
            String wantedName = ctx.player().getInventory().getItem(wanted).getHoverName().getString();
            String heldName = ctx.player().getInventory().getItem(held).getHoverName().getString();
            String headline = "Dig stopped: something else keeps taking the hand back. Asked for hotbar "
                    + (wanted + 1) + " (" + wantedName + ") for " + snakeToolWaitTicks
                    + " ticks and hotbar " + (held + 1) + " (" + heldName + ") kept being put back.";
            logMechanic(headline);
            abortBuild(Ending.LAYER_UNBUILDABLE, headline, java.util.List.of(
                    "The digger chooses its tool per action: the wide pickaxe only for a full 3x3 slice, an",
                    "ordinary one to sink the entry shaft of the next band -- an upward 3x3 swing would take the",
                    "block it is standing on. Another controller is overruling that choice every tick.",
                    "Turn off anything that forces a hotbar slot while the dig runs."));
        }
        return false;
    }

    private int snakeAreaToolSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = ctx.player().getInventory().getItem(slot);
            if (snakeIsAreaTool(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private int snakeOrdinaryPickSlot(BlockState targetState) {
        if (excavating) {
            return ordinaryMiningToolSlot(ctx.player().getInventory().getNonEquipmentItems(), targetState,
                    Princeps.settings().itemSaver.value, Princeps.settings().itemSaverThreshold.value);
        }
        int best = -1;
        double bestSpeed = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = ctx.player().getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.is(ItemTags.PICKAXES) || snakeIsAreaTool(stack)) {
                continue;
            }
            double speed = ToolSet.calculateSpeedVsBlock(stack, targetState);
            if (speed > bestSpeed) {
                best = slot;
                bestSpeed = speed;
            }
        }
        return best;
    }

    /** The single-block excavation action needs the fastest eligible ordinary tool, not always a pickaxe. */
    static int ordinaryMiningToolSlot(List<ItemStack> inventory, BlockState targetState,
                                     boolean itemSaver, int itemSaverThreshold) {
        int best = -1;
        double bestSpeed = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < Math.min(9, inventory.size()); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty() || AreaTool.is(stack)
                    || !(stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)
                        || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES))) continue;
            // Match the ordinary navigation chooser's wear rule. Inventory ownership and the slot-packet wait
            // are still enforced by snakeToolReady; this selector never changes the held item itself.
            if (itemSaver && stack.getMaxDamage() > 1
                    && stack.getDamageValue() + itemSaverThreshold >= stack.getMaxDamage()) continue;
            double speed = ToolSet.calculateSpeedVsBlock(stack, targetState);
            if (speed > bestSpeed) {
                best = slot;
                bestSpeed = speed;
            }
        }
        return best;
    }

    private boolean snakeIsAreaTool(ItemStack stack) {
        return AreaTool.is(stack);
    }

    private static String normaliseToolName(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder normalised = new StringBuilder(value.length());
        value.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(normalised::appendCodePoint);
        return normalised.toString();
    }

    /**
     * Fixes the lanes and the corner this band is walked from.
     *
     * <p>The axis is chosen ONCE per job and never again. It has to be: it decides which way the pickaxe's plane
     * faces, and a plane that flips mid-run turns a tunnel into a scatter of half-cleared slices. The old ordering
     * recomputed that direction per candidate cell from where the player happened to stand, which is precisely why
     * the area-tool mode looked arbitrary.
     *
     * <p>The corner, by contrast, is re-chosen for every new band, and that is deliberate. When a band is finished
     * the bot is standing at the far end of it; sending it back to the original corner to start the level below
     * would be a full crossing of the area with nothing mined on the way. Starting the next band from where it
     * already stands keeps the snake continuous down through the levels.
     */
    private void ensureSerpentineRoute(int bandTop) {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (full == null || origin == null || bandTop == Integer.MIN_VALUE) {
            return;
        }
        int minX = origin.getX();
        int maxX = minX + full.widthX() - 1;
        int minZ = origin.getZ();
        int maxZ = minZ + full.lengthZ() - 1;
        if (!routeAxisChosen) {
            // Lanes across the SHORTER side, so the long runs are the straight ones and the turns stay few.
            routeCrossIsX = (maxX - minX) <= (maxZ - minZ);
            routeAxisChosen = true;
        }
        if (routeBandTop == bandTop) {
            return;
        }
        routeBandTop = bandTop;
        int playerX = Mth.floor(ctx.player().getX());
        int playerZ = Mth.floor(ctx.player().getZ());
        int crossMin = routeCrossIsX ? minX : minZ;
        int crossMax = routeCrossIsX ? maxX : maxZ;
        int travelMin = routeCrossIsX ? minZ : minX;
        int travelMax = routeCrossIsX ? maxZ : maxX;
        int playerCross = routeCrossIsX ? playerX : playerZ;
        int playerTravel = routeCrossIsX ? playerZ : playerX;
        routeStartCross = Math.abs(playerCross - crossMin) <= Math.abs(playerCross - crossMax) ? crossMin : crossMax;
        routeStartTravel = Math.abs(playerTravel - travelMin) <= Math.abs(playerTravel - travelMax)
                ? travelMin : travelMax;
    }

    /**
     * Where a cell falls on the snake: lane first, then position along that lane, reversed on every other one.
     *
     * <p>This is the whole ordering. Sorting by it gives the sequencing rule for free -- every cell of the current
     * slice sorts ahead of every cell of the next one, so a slice cannot be left half-done. That matters because
     * the leftovers are exactly the cells an area swing cannot reach later: the swing clears a plane, and a plane
     * with a hole behind it is a hole nothing will pass again.
     */
    private long serpentineOrder(BetterBlockPos pos) {
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        if (full == null || origin == null || !routeAxisChosen) {
            return 0L;
        }
        int travelSpan = (routeCrossIsX ? full.lengthZ() : full.widthX());
        int cross = routeCrossIsX ? pos.x : pos.z;
        int travel = routeCrossIsX ? pos.z : pos.x;
        int lane = Math.abs(cross - routeStartCross) / SERPENTINE_LANE_WIDTH;
        int along = Math.abs(travel - routeStartTravel);
        // Every other lane is walked backwards. That is the point of a snake rather than a raster: no empty
        // return trip, and the bot is always already standing where the next slice needs it.
        int order = (lane & 1) == 0 ? along : Math.max(0, travelSpan - 1 - along);
        return (long) lane * travelSpan + order;
    }

    private boolean serpentineCentre(BetterBlockPos pos, int bandTop, int size) {
        int arm = (size - 1) / 2;
        if (!routeAxisChosen || bandTop == Integer.MIN_VALUE || pos.y != bandTop - arm) {
            return false;
        }
        int cross = routeCrossIsX ? pos.x : pos.z;
        return Math.floorMod(Math.abs(cross - routeStartCross), SERPENTINE_LANE_WIDTH) == arm;
    }

    /** Nearest reachable cell that holds the right block+orientation but the wrong right-click state (open/delay/
     *  mode/note) — the interaction pass aims here and right-clicks. Returns the cell + a reachable rotation. */
    private Optional<Tuple<BetterBlockPos, Rotation>> toInteractNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        // Cells the committed route still walks through. We must NOT toggle a door/trapdoor/gate we're crossing:
        // the pathfinder opens it to pass, this pass would set it back, and they fight forever (the door open/close
        // loop). Deferred while it's on the path; once nothing beyond it is left to build the route stops crossing
        // it and we set its final state from a spot beside it in peace.
        final Set<BetterBlockPos> pathCells = activePathCells();
        Optional<Tuple<BetterBlockPos, Rotation>> best = Optional.empty();
        long bestDist = Long.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx, y = center.y + dy, z = center.z + dz;
                    if (isCellParked(x, y, z)) {
                        continue;
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    BlockState desired = bcc.getSchematic(x, y, z, curr);
                    if (desired == null || interactionClicks(curr, desired) <= 0) {
                        continue;
                    }
                    if (isTraversalInteraction(desired)
                            && (pathCells.contains(new BetterBlockPos(x, y, z))
                            || pathCells.contains(new BetterBlockPos(x, y + 1, z))
                            || pathCells.contains(new BetterBlockPos(x, y - 1, z)))) {
                        continue; // still walking through this door/trapdoor — don't fight the pathfinder over it
                    }
                    BetterBlockPos pos = new BetterBlockPos(x, y, z);
                    Optional<Rotation> rot = RotationUtils.reachableForWork(ctx, pos,
                            ctx.playerController().getBlockReachDistance(), false);
                    if (!rot.isPresent()) {
                        continue;
                    }
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d < bestDist) {
                        bestDist = d;
                        best = Optional.of(new Tuple<>(pos, rot.get()));
                    }
                }
            }
        }
        return best;
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;
        /** Feet cell from which this immutable click geometry was derived. */
        private final long stanceKey;
        /** The cell this placement fills and the exact state it must produce — the click gate re-simulates the
         *  LIVE crosshair ray against this, so a slab can never be pressed onto the wrong half. */
        private final BlockPos target;
        private final BlockState desired;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot,
                         long stanceKey, BlockPos target, BlockState desired) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
            this.stanceKey = stanceKey;
            this.target = target;
            this.desired = desired;
        }

        /** Retained for binary compatibility with integrations compiled against the former public nested type. */
        @Deprecated
        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot,
                         BlockPos target, BlockState desired) {
            this(hotbarSelection, placeAgainst, side, rot, Long.MIN_VALUE, target, desired);
        }
    }

    /** Placement target with full commitment. The cell stays immutable, but click geometry is deliberately derived
     *  again every tick: residual movement inside one feet block changes the eye position enough to stale a ray even
     *  though the stance key, support, item, and target are unchanged. If the current stance truly becomes unusable,
     *  ownership stays on the same cell while recovery walks to a different concrete stance. */
    private Optional<Placement> stickyPlacement(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        if (electedBreak) {
            // assemble/recalc releases a completed or invalid removal. A nearby placement is not a reason to
            // replace its route, nor is the absence of an item which places AIR a material shortage.
            return Optional.empty();
        }
        if (placementTargetLock.isActive() && committedPlaceTarget != null) {
            int x = committedPlaceTarget.x, y = committedPlaceTarget.y, z = committedPlaceTarget.z;
            BlockState curr = bcc.bsi.get0(x, y, z);
            BlockState desired = bcc.getSchematic(x, y, z, curr);
            long key = positionKey(x, y, z);
            if (desired == null || isSecondaryHalf(desired)
                    || placementSatisfiedOrHandedOff(curr, desired, x, y, z, bcc)
                    || isCellParked(x, y, z)) {
                releasePlacementTarget();
            } else if (!placementTargetLock.owns(key)) {
                // Coordinate/key disagreement is an invariant violation; fail closed into a fresh target selection.
                releasePlacementTarget();
            } else if (!MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi)) {
                // A wrong block landed. Keep target ownership while the higher-priority break branch removes it,
                // then recover from another stance instead of forgetting which placement produced the bad result.
                if (!placementTargetLock.isRecovering()) {
                    placementTargetLock.startRecovery();
                }
                return Optional.empty();
            } else if (placementTargetMustYieldToActivePath(
                    placementTargetLock.isRecovering(), blocksActivePath(activePathCells(), x, y, z, desired))) {
                // Never freeze the path by filling a cell it still traverses. This is a normal deferral, not failure.
                // Recovery is the deliberate exception: it cannot click until its concrete stance is reached,
                // centred, settled and PathExecutor is null. Its route may therefore pass through the still-empty
                // target/head cell. Run 80a3058b found a len9 route for the final up-facing piston, then this branch
                // deleted that very recovery lock on the following tick.
                releasePlacementTarget();
            } else if (placementTargetLock.isRecovering()) {
                desirableOnHotbar.add(desired);
                // Recovery owns movement until one concrete stance has actually arrived, centered, and spent an
                // input-free settle tick. Deriving an attempt earlier freezes another moving-pose ray and recreates
                // the exact 0.3.78 regression at the next stance.
                boolean recoveryPoseReady = placementTargetLock.hasPlannedStance()
                        && plannedPlacementStance != null
                        && placementTargetLock.plannedStanceKey() == positionKey(plannedPlacementStance)
                        && ctx.playerFeet().equals(plannedPlacementStance)
                        && centeredInPlacementStance(plannedPlacementStance)
                        && placementCenterSettleTicks > 0
                        && princeps.getPathingBehavior().getCurrent() == null;
                if (!recoveryPoseReady) {
                    return Optional.empty();
                }
                Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc);
                if (opt.isPresent() && placementTargetLock.mayTryStance(opt.get().stanceKey)) {
                    placementTargetLock.setAttempt(key, opt.get());
                    plannedPlacementStance = null;
                    return opt;
                }
                return Optional.empty(); // recovery command owns movement toward a different stance
            } else {
                desirableOnHotbar.add(desired);
                // Keep only the TARGET sticky. possibleToPlace depends on the player's exact x/z eye position, pose,
                // current support geometry, and hotbar contents; all can change while playerFeet() remains the same.
                // Re-derive that transient plan every tick, as the working 0.3.72/0.3.77 path did. refreshAttempt()
                // intentionally preserves the accumulated gate timeout so a genuinely bad stance is still bounded.
                Optional<Placement> refreshed = possibleToPlace(desired, x, y, z, bcc);
                if (refreshed.isPresent()) {
                    Placement replacement = refreshed.get();
                    Optional<Placement> previous = placementTargetLock.attempt();
                    if (previous.isPresent() && previous.get().stanceKey == replacement.stanceKey) {
                        placementTargetLock.refreshAttempt(key, replacement);
                    } else {
                        // Crossing a feet-cell boundary is a real stance transition, not a sub-block geometry refresh.
                        placementTargetLock.setAttempt(key, replacement);
                    }
                    return refreshed;
                }
                // A missing fresh plan must never fall back to yesterday's eye ray. Ordinary cubes return directly to
                // the GoalAdjacent scheduler; strict blocks keep their target and ask the stance oracle for help.
                // A temporarily empty hotbar is neither kind of geometry failure: release so inventory management can
                // refill it and reacquire the same coordinate on the next pass.
                if (hotbarStackThatPlaces(desired) == null || isOrdinaryFullBlock(desired, committedPlaceTarget)) {
                    yieldPlacementCell(committedPlaceTarget);
                    return Optional.empty();
                }
                placementTargetLock.startRecovery();
                return Optional.empty();
            }
        } else if (placementTargetLock.isActive()) {
            placementTargetLock.release();
        }
        if (electedCell != null) {
            BlockState current = bcc.bsi.get0(electedCell);
            BlockState wanted = bcc.getSchematic(electedCell.x, electedCell.y, electedCell.z, current);
            if (wanted == null || valid(current, wanted, false) || isCellParked(electedCell.x, electedCell.y, electedCell.z)) {
                clearElectedTarget();
            } else {
                desirableOnHotbar.add(wanted);
                if (blocksActivePath(activePathCells(), electedCell.x, electedCell.y, electedCell.z, wanted)) {
                    return Optional.empty();
                }
                Optional<Placement> chosen = possibleToPlace(wanted, electedCell.x, electedCell.y, electedCell.z, bcc);
                chosen.ifPresent(this::acquirePlacementTarget);
                return chosen; // one chosen build cell owns preparation, walking and the eventual click
            }
        }
        Optional<Placement> fresh = searchForPlacables(bcc, desirableOnHotbar);
        fresh.ifPresent(this::acquirePlacementTarget);
        return fresh;
    }

    private void acquirePlacementTarget(Placement placement) {
        long key = positionKey(placement.target);
        if (placementTargetLock.acquire(key, placement)) {
            if (!placement.target.equals(electedCell)) electedGoal = null;
            electedCell = new BetterBlockPos(placement.target);
            BuildTrace.cell(buildTick, "PICK",
                    placement.target.getX(), placement.target.getY(), placement.target.getZ(),
                    "want=" + blockName(placement.desired)
                            + " against=" + placement.placeAgainst.getX() + "," + placement.placeAgainst.getY()
                            + "," + placement.placeAgainst.getZ() + " face=" + placement.side
                            + " rot=" + String.format(java.util.Locale.ROOT, "%.1f,%.1f",
                                    placement.rot.getYaw(), placement.rot.getPitch()));
            committedPlaceTarget = new BetterBlockPos(placement.target);
        }
    }

    /**
     * Guarantees the log can never go quiet while the build is stuck.
     *
     * <p>{@code noProgressTicks} already existed for this, but it lives deep in onTick behind every build-action and
     * recovery return, so the runs that most needed it are the ones where it never incremented. This counts the one
     * thing that cannot be faked -- a cell actually becoming correct -- and it counts from the top of the tick.
     */
    private void narrateIfNoCellHasCompleted() {
        if (paused || incorrectPositions == null || incorrectPositions.isEmpty()) {
            return; // nothing is owed, so silence is not suspicious
        }
        long quiet = buildTick - lastCellCompletedTick;
        // ELAPSED, NOT MODULO -- the same trap the other censuses were just pulled out of, and this is the line
        // it cost the most. A run on 2026-08-18 sat inert for ninety seconds with 1397 cells owed and printed the
        // placement censuses six times and this one ZERO times, because the ticks on which the watchdog is
        // reached and the ticks where quiet%200==0 never coincided. This is the only line that carries goal=,
        // pathing=, crouched= and underFeet=, i.e. the only one that can say WHY a builder is standing still, and
        // it is the one that goes missing exactly when a builder stands still.
        if (quiet < WATCHDOG_SILENCE_TICKS || buildTick - lastWatchdogNarrationTick < WATCHDOG_SILENCE_TICKS) {
            return;
        }
        lastWatchdogNarrationTick = buildTick;
        Goal goal = princeps.getPathingBehavior().getGoal();
        logMechanic("No cell has become correct for " + quiet + " ticks"
                + " (layer=" + layer
                + ", unresolved=" + (incorrectPositions == null ? "?" : incorrectPositions.size())
                + ", parked=" + parkedCells.size()
                + (buildInRows ? ", rowBand=" + bandRearEdge() + "+" + BAND_ROWS
                        + " (fallbacks=" + bandFallbacks + ")" : "")
                // Sichtbar gemacht, weil diese Zahl eine neue Art von Stillstand erzeugen KANN: haelt die
                // Orientierungs-Sperre jeden Klick zurueck, sieht das von aussen aus wie "der Bot tut nichts",
                // und ohne diesen Zaehler wuerde man es an der falschen Stelle suchen.
                + ", aim-holdbacks=" + princeps.getInputOverrideHandler().getBlockPlaceHelper()
                        .getOrientationHoldbacks()
                + ", lanes " + laneCensus()
                + ", lockActive=" + placementTargetLock.isActive()
                + ", target=" + (committedPlaceTarget == null ? "none"
                        : committedPlaceTarget.x + "," + committedPlaceTarget.y + "," + committedPlaceTarget.z)
                + ", feet=" + ctx.playerFeet().x + "," + ctx.playerFeet().y + "," + ctx.playerFeet().z
                // Two questions that cost an afternoon each to answer by reasoning, and one line to answer by
                // measurement: is the body crouched (the ledge guard forces that, and a latched guard would look
                // exactly like a stall), and is it standing on anything (an unplaced cell under the feet is the
                // difference between a bot at a ledge and a bot on solid picture).
                + ", crouched=" + ctx.player().isShiftKeyDown()
                + ", underFeet=" + blockName(ctx.world().getBlockState(ctx.playerFeet().below()))
                // Whether this is a PLACEMENT stall or a NAVIGATION stall is the first thing a reader needs and the
                // hardest thing to infer from the outside. A frozen position with a goal set and no path means the
                // pathfinder cannot get there; a frozen position with no goal at all means nothing even asked it to.
                + ", goal=" + (goal == null ? "none" : goal.toString())
                + ", pathing=" + princeps.getPathingBehavior().isPathing()
                + ", calculating=" + princeps.getPathingBehavior().getInProgress().isPresent()
                + ", noProgressTicks=" + noProgressTicks
                // Step 6 of the owner's plan, reported before it is acted on: when this reaches zero every cell in the
                // work set has a valid negative verdict, i.e. the ordinary phase has genuinely run out of moves and the
                // helper-block phase is the honest next step. Printed first so the number can be trusted before any
                // behaviour is hung on it.
                + ", cellsWithoutAVerdict=" + cellsWithoutAVerdict()
                + "/" + (incorrectPositions == null ? 0 : incorrectPositions.size()) + ")");
    }

    /** Called wherever a cell is observed to satisfy the schematic — the watchdog's only source of truth. */
    private void noteCellCompleted(BetterBlockPos completedCell) {
        breakBranchProgress.observedProgress();
        consecutivePathFailures = 0;
        lastCellCompletedTick = buildTick;
        // A cell became correct, so whatever the refusal was holding up is over. This is the one clock a stuck builder
        // cannot fake, which makes it the right place to hand the episode its full budget back.
        scaffoldVetoTicks = 0;
        scaffoldVetoUnstickTried = false;
        // The latched target is released here and only for real reasons -- a placement is the most real of them.
        if (completedCell.equals(electedCell)) {
            clearElectedTarget();
        }
        // A PLACED BLOCK IS A NEW WORLD, SO EVERY CELL GETS ANOTHER LOOK. The owner's rule, and it turns the deferral
        // backoff from a memory into what it should always have been: "how often has this failed SINCE anything last
        // succeeded". Nothing that was judged before the world changed keeps its verdict.
        //
        // Why this is the fix and not merely a preference. Measured on the facings run e020b4f0, from the builder's own
        // census: of 461 / 676 / 472 skipped cell-looks in three consecutive windows, 426 / 675 / 470 were skipped for
        // "deferred" -- over 95%. Not "would block own path" (zero hits), not geometry. The build was mostly refusing
        // to look at cells it had written off earlier, and while it had nothing left to look at it navigated anyway --
        // which is where the pointless helper blocks came from: SCAFFOLD at 67/79/85,-60,68 all carried
        // "by=ascend for=- stance=- act=searching", i.e. a route serving no target at all.
        //
        // Retirement survives, and gains a sharper meaning rather than losing one: CELL_DEFERRALS_BEFORE_RETIRING now
        // counts consecutive failures with no progress anywhere in between, so it fires only when the build is
        // genuinely stuck rather than after six attempts spread over an hour of successful work. The layer give-up
        // remains the outer escape.
        // ONLY THE DEADLINE, NOT THE COUNTER. Clearing both was tried (04.08.2026) and is the prime suspect for
        // facings 96/96 -> 61/96, because the counter is the only route to termination: cellDeferralCounts is what
        // CELL_DEFERRALS_BEFORE_RETIRING reads, so wiping it on every placement means no cell can ever retire and a
        // hopeless one is re-asked forever. The file already measured the neighbouring mistake -- "cancelling the
        // backoff here ... reads well and measured badly -- the facings scenario fell from 82 of 96 to 47. Retrying
        // every tick means running the stance search every tick."
        //
        // The deadline is the part the owner's rule is actually about ("after a placement he may see the whole world
        // again"), and dropping it costs nothing: a cell that failed before the world changed gets another look.
        // The counter keeps its meaning and gains a better one -- failures since anything last succeeded.
        // Both maps are lazily created and are set back to null by resetPlacementTracking, so they are nullable here.
        if (cellDeferredUntilTick != null) {
            cellDeferredUntilTick.clear();
        }
        // A block landing is the one event that can make a previously hopeless stance work, so it is also the only
        // honest moment to forget that it was hopeless. See stanceFailures for why this is the right lifetime.
        stanceFailures.clear();
        stanceEndorsed.clear();
    }

    /**
     * Stances already PROVEN not to work for a cell, and the stances the search ENDORSED for it.
     *
     * <p>The gap these close is the most expensive thing in a run. {@code placementStancesFor} costs, by its own
     * docstring, "145 stances x 6 faces x 5 points" for a cell no stance can serve, "and it is paid again every tick
     * the cell stays committed -- the visible one-to-two-second freeze before a block: not thinking, just a search
     * with no exit". Nothing remembers the answer, so the same search runs from scratch, forever, for the same
     * negative. Measured on run 20260802-223537: 6195 ticks in {@code target-no-stance} against 70 registered
     * failures -- <b>89 ticks of failing per one counted failure</b> -- and zero {@code STANCE} events all run.
     *
     * <p>The lifetime is deliberately short and is cleared in {@link #noteCellCompleted()}: a placement changes the
     * world, and a stance that failed for want of something to click may work the moment a neighbour lands. So the
     * memory survives exactly as long as nothing is being built -- which is precisely the stall it exists to end --
     * and evaporates the instant real progress happens. That makes a permanently-excluded stance impossible, which
     * is the failure mode any "remember the negative" scheme has to rule out before it is safe.
     *
     * <p>{@code stanceEndorsed} is the other half, and it is a watchdog rather than a mechanism: when a stance the
     * search endorsed then fails the live click, the two are disagreeing about the same question and one of them is
     * wrong. That disagreement is what cost 400 blocks the last time this area was touched, and it was invisible.
     * Now it is a trace line.
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LongOpenHashSet> stanceFailures =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LongOpenHashSet> stanceEndorsed =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    /** Record that {@code cell} could not be placed from where the bot is standing right now. */
    private void noteStanceFailure(int x, int y, int z, BetterBlockPos stance) {
        long cellKey = positionKey(x, y, z);
        long stanceKey = positionKey(stance);
        LongOpenHashSet failed = stanceFailures.get(cellKey);
        if (failed == null) {
            failed = new LongOpenHashSet();
            stanceFailures.put(cellKey, failed);
        }
        if (!failed.add(stanceKey)) {
            return;   // already known; say it once, not once per tick
        }
        LongOpenHashSet endorsed = stanceEndorsed.get(cellKey);
        if (endorsed != null && endorsed.contains(stanceKey)) {
            // THE DISAGREEMENT. The search walked the bot here on the strength of its own simulation and the live
            // click refuses. Named rather than absorbed: an oracle that is quietly wrong sends the bot to endorsed
            // spots it cannot use, which is a livelock wearing the costume of a plan.
            BuildTrace.cell(buildTick, "PREDICTION-MISS", x, y, z,
                    "stance " + stance.x + "," + stance.y + "," + stance.z
                            + " was endorsed by the stance search but the live check refuses it");
            logMechanic("Stance " + stance.x + "," + stance.y + "," + stance.z + " was endorsed for "
                    + x + "," + y + "," + z + " and the live check refuses it -- the search and the click disagree");
        }
    }

    /**
     * A cell for which the search has PROVEN the spot the bot is standing on right now, or null.
     *
     * <p>Fix 1 of 05.08.2026, and the owner's words are the specification: "Platzieren sollte immer ausgeloest werden,
     * sobald die Standposition auf eine der Positionen, welche als moegliche Standflaeche errechnet worden, erreicht ist
     * durch den Wegfinder oder auch einfach, weil er direkt da schon steht."
     *
     * <p>WHAT IT FIXES, measured on run f28e33ae (oriented, 7/7 in 220 ticks): cell 69,-60,67 had 13 proven stances and
     * the trace shows the STANCE line repeating every tick from 26 to 62 while nothing happened. The bot was standing ON
     * one of the 13. The goal is a composite of GoalBlocks over all of them, so it counted as already reached
     * (DEADGOAL at tick 38, then re-issued 27 times), the pathfinder had nothing to do, and no placement was triggered
     * either -- because arriving at a stance and COMMITTING the cell as a target are two different things, and only the
     * second one makes the builder aim and click. The commit came from the 30-tick watchdog at tick 63 and the cell was
     * placed at 81. Two such waits, 55 and 64 ticks, were 54% of that run.
     *
     * <p>Reads {@code stanceEndorsed}, which {@code rememberEndorsedStances} fills from exactly the stances
     * {@code placementStancesFor} proved -- so this can never commit a cell on anything but a proven position.
     */
    private BetterBlockPos cellAlreadyProvenFromHere(BetterBlockPos feet) {
        if (feet == null || incorrectPositions == null || stanceEndorsed.isEmpty()) {
            return null;
        }
        long standingOn = positionKey(feet);
        for (BetterBlockPos pos : incorrectPositions) {
            LongOpenHashSet endorsed = stanceEndorsed.get(positionKey(pos));
            if (endorsed != null && endorsed.contains(standingOn) && !isCellParked(pos.x, pos.y, pos.z)) {
                return pos;
            }
        }
        return null;
    }

    /** Has this cell already refused at least one stance? The signal that it deserves the expensive search. */
    private boolean hasStanceFailures(int x, int y, int z) {
        LongOpenHashSet failed = stanceFailures.get(positionKey(x, y, z));
        return failed != null && !failed.isEmpty();
    }

    // ==========================================================================================================
    // THE VERDICT NOTE -- one note per cell, and the only thing in this file that remembers a NEGATIVE across ticks
    // without a timer in it.
    //
    // The owner's design, and the reason it is a single structure serving three separate needs:
    //
    //   note(C) = "when I last judged C, the clickable faces around it were exactly THIS set,
    //              and the answer was: no face at all / a face, but no stance and no look can produce the block"
    //
    //   * it answers "compute the stance ONCE, not every tick" -- the note outlives the tick, where the existing
    //     orientedGoalCache is cleared at the top of every onTick;
    //   * it IS the waiting list for "hold this cell until a NEW face appears" -- the release condition is simply
    //     that the current face set differs from the noted one;
    //   * and it makes "is anything left to try?" a hash lookup per work-set cell instead of a full stance search,
    //     which is what made that question unaffordable before (24.2 -> 13-18 blocks/min when it was asked often).
    //
    // WHY THE KEY IS THE FACE SET AND NOT A DURATION. A backoff answers "has enough time passed", which is a
    // question about the clock and not about the build -- and nothing about a cell changes on its own, because the
    // bot is the only thing that changes this world. The face set answers "has anything changed that could make the
    // answer different", which is the actual precondition. That is also why this is not the four-times-reverted
    // "only route to cells that already have something to click against": nothing is removed from the target set
    // and nothing is deprioritised. A cell with a valid negative note is skipped for exactly as long as the reason
    // for the negative still holds, and the moment a neighbour lands it is back, at full priority.
    //
    // TERMINATION lives outside this structure, deliberately: when every cell in the work set carries a valid note,
    // the ordinary phase is finished and the helper-block phase takes over. The note is therefore never permanent --
    // it is a queue position, not a retirement.
    //
    // Packed into one long so the map stays primitive: bits 0..5 are the clickable-face mask in
    // Direction.values() order, bits 8..9 the verdict kind. Bit 63 marks the entry as present, so a zero mask with
    // a zero kind is still distinguishable from "no entry".
    private static final long VERDICT_PRESENT = 1L << 63;
    private static final int VERDICT_KIND_SHIFT = 8;
    /** No neighbour offers a face to click at all -- nothing can be placed here until one appears. */
    private static final long VERDICT_NO_FACE = 1L << VERDICT_KIND_SHIFT;
    /** Faces exist, but every stance x every look direction was computed and none produces the wanted state. */
    private static final long VERDICT_NO_STANCE = 2L << VERDICT_KIND_SHIFT;
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap cellVerdicts =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    /**
     * Which of the six neighbours currently offer a face this cell could be placed against.
     *
     * <p>Six lookups, no raycast. {@code canPlaceAgainst} is the same test {@code attemptToPlaceABlock} uses, so the
     * note is keyed on exactly the property the placement depends on.
     */
    private long clickableFaceMask(int x, int y, int z) {
        // A PRE-FILTER MUST NOT BE STRICTER THAN THE THING IT FILTERS, and for a whole day it was.
        //
        // This used MovementHelper.canPlaceAgainst, which is `isBlockNormalCube || GLASS || StainedGlass` -- an
        // inherited pathfinder heuristic that deliberately excludes "weird things that we technically could place
        // against". The stance search it gates asks something quite different at orientationAchievableFrom: it walks
        // supportDirectionsFor(desired) and only skips a neighbour that is REPLACEABLE. A repeater is not replaceable,
        // so the search would evaluate it happily -- it was simply never asked, because this mask had already answered
        // "no face at all" and the verdict note then held the cell until its face set CHANGED, which it never could.
        //
        // MEASURED, run c291d9ca (basalt, 2571/15004, stalled at t=29580): 542 cells were held for "no clickable face"
        // and every single one of them sat in layer y=-59. 392 of them did get a neighbour later and 361 of those were
        // correctly released and re-examined -- the note works. The 31 that never came back are led by 70,-59,122,
        // 122,-59,70 and 126,-59,122, which a comment in this file had already named as "the three cells four runs
        // actually died on", noting that their only solid neighbour is a REPEATER. The neighbour landing did not change
        // the mask, because a repeater was not counted as a face. In the last 1920 ticks of that run the stance search
        // ran ZERO times: the builder was not failing to find stances, it never looked.
        //
        // So the test here is the search's own: is there something there that is not air and not fluid, i.e. something
        // a ray can hit and vanilla can place against. Deliberately NOT changed in MovementHelper -- the pathfinder
        // uses canPlaceAgainst to decide whether it can bridge, and believing it may bridge off redstone wire is a
        // different and much riskier claim. Behind this pre-filter sits the full vanilla placement simulation, which
        // rejects anything that truly does not work, so being generous here costs at most one search per cell.
        long mask = 0L;
        Direction[] all = Direction.values();
        for (int i = 0; i < all.length; i++) {
            Direction d = all[i];
            BlockState there = ctx.world().getBlockState(
                    new BetterBlockPos(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()));
            if (!there.isAir() && there.getFluidState().isEmpty()) {
                mask |= 1L << i;
            }
        }
        return mask;
    }

    /** Note that this cell cannot be built as the world stands, together with the face set that made that true. */
    private void recordCellVerdict(int x, int y, int z, long kind) {
        long packed = VERDICT_PRESENT | kind | clickableFaceMask(x, y, z);
        long key = positionKey(x, y, z);
        if (cellVerdicts.get(key) == packed) {
            return; // same verdict, same world -- nothing new to say and nothing to trace
        }
        cellVerdicts.put(key, packed);
        // Schattenbetrieb: dieselbe Entscheidung, ausgedrueckt in der Sprache der Spezifikation. Grund A ist
        // "keine anliegende Flaeche", Grund B ist "Flaeche da, aber keine Standposition" -- die beiden Verdikte
        // sind bereits genau das, sie hiessen nur nie so.
        parkCell(new BetterBlockPos(x, y, z), kind == VERDICT_NO_FACE ? ParkReason.NO_FACE : ParkReason.NO_STANCE);
        BuildTrace.cell(buildTick, "VERDICT", x, y, z,
                (kind == VERDICT_NO_FACE ? "no clickable face" : "faces exist but no stance works")
                        + " faces=" + Long.toBinaryString(packed & 0x3FL)
                        + " released when a neighbour changes");
    }

    /**
     * Is the last negative verdict for this cell still valid, i.e. has nothing around it changed since?
     *
     * <p>Invalidation is a comparison, not an expiry: if the face set differs the entry is dropped here and the cell
     * is offered again on this very tick.
     */
    private boolean cellVerdictStillHolds(int x, int y, int z) {
        long key = positionKey(x, y, z);
        long packed = cellVerdicts.get(key);
        if ((packed & VERDICT_PRESENT) == 0) {
            return false;
        }
        if ((packed & 0x3FL) == clickableFaceMask(x, y, z)) {
            return true;
        }
        cellVerdicts.remove(key);
        return false;
    }

    /**
     * The PARK list of the target specification, MEASURED before it is built.
     *
     * <p>The spec splits cells into two lists: AKTIV (may be examined) and PARK (cannot be worked on right now,
     * for exactly one of three reasons — A no clickable face, B a face but no standing position, C a standing
     * position but unreachable). Today none of that exists as a data structure; the same three states are spread
     * over a verdict note, a deferral deadline and a retirement set, and no line in any log says how many cells are
     * in which. So the first question anyone asks about the rewrite — "does the new list behave like the old
     * machinery?" — has no before-picture to compare against.
     *
     * <p>This is that before-picture, and it is deliberately read-only: nothing here mutates a map, not even the
     * expired-deadline cleanup {@code isCellDeferred} does on the ordinary read path, because a census that changes
     * what it counts is the measurement trap this project has already paid for twice.
     *
     * <p>It counts what is VISIBLE (the work set, capped at {@code incorrectSize}), not the whole layer — which is
     * itself part of the finding, since the spec's AKTIV list is the entire layer.
     */
    private String parkShadowCensus() {
        if (incorrectPositions == null || incorrectPositions.isEmpty()) {
            return null;
        }
        int active = 0;
        int noFace = 0;
        int noStance = 0;
        int unreachable = 0;
        int retired = 0;
        for (BetterBlockPos pos : incorrectPositions) {
            long key = positionKey(pos);
            if (isCellParked(pos.x, pos.y, pos.z)) {
                retired++;
                continue;
            }
            long packed = cellVerdicts.get(key);
            boolean noteStillHolds = (packed & VERDICT_PRESENT) != 0
                    && (packed & 0x3FL) == clickableFaceMask(pos.x, pos.y, pos.z);
            if (noteStillHolds && (packed & VERDICT_NO_FACE) != 0) {
                noFace++;
            } else if (noteStillHolds && (packed & VERDICT_NO_STANCE) != 0) {
                noStance++;
            } else if (cellDeferredUntilTick != null
                    && cellDeferredUntilTick.getOrDefault(key, Long.MIN_VALUE) > buildTick) {
                unreachable++;
            } else {
                active++;
            }
        }
        return "AKTIV=" + active + " PARK-A(no face)=" + noFace + " PARK-B(no stance)=" + noStance
                + " PARK-C(unreachable)=" + unreachable + " retired=" + retired
                + " of " + incorrectPositions.size() + " visible cells in layer " + layer;
    }

    /** How many cells still carry no valid negative verdict -- the ordinary phase is done when this reaches zero. */
    private int cellsWithoutAVerdict() {
        if (incorrectPositions == null) {
            return 0;
        }
        int open = 0;
        for (BetterBlockPos pos : incorrectPositions) {
            if (!cellVerdictStillHolds(pos.x, pos.y, pos.z)) {
                open++;
            }
        }
        return open;
    }
    // ==========================================================================================================

    /** Stances still worth evaluating for this cell: everything except what has already been proven not to work. */
    private java.util.function.LongPredicate stanceFilterFor(int x, int y, int z) {
        LongOpenHashSet failed = stanceFailures.get(positionKey(x, y, z));
        if (failed == null || failed.isEmpty()) {
            return ignored -> true;
        }
        return stanceKey -> !failed.contains(stanceKey);
    }

    /** Remember what the search endorsed, so a later live refusal at one of them can be called out. */
    private void rememberEndorsedStances(int x, int y, int z, List<BetterBlockPos> stances) {
        if (stances.isEmpty()) {
            return;
        }
        LongOpenHashSet endorsed = new LongOpenHashSet(stances.size());
        for (BetterBlockPos stance : stances) {
            endorsed.add(positionKey(stance));
        }
        stanceEndorsed.put(positionKey(x, y, z), endorsed);
    }


    // giveUpOnLayerIfItHasStalled IST WEG -- die letzte und groesste der Uhren.
    //
    // Sie feuerte, wenn LAYER_GIVE_UP_TICKS lang keine Zelle fertig geworden war, und schob dann den gesamten
    // SICHTBAREN Arbeitssatz in die Ruhestandsmenge: eine Zeitspanne entschied ueber Zellen, ueber die nie ein
    // Urteil gefaellt worden war. Genau das verbietet die Spezifikation -- eine Zelle verlaesst AKTIV nur, weil sie
    // gebaut wurde oder weil P1, P2 oder P4 nein gesagt haben, und der einzige Weg zurueck ist der Waechter.
    // Was sie erreichen sollte (die Ebene nicht ewig haengen lassen), erreicht jetzt P6a: die Ebene endet mit
    // einem benannten Abbruch statt mit einer stillen Massenverabschiedung.

    /**
     * A goal the bot is already standing in is not a goal, and waiting for it to be reached is waiting forever.
     *
     * <p>The single most expensive stall in the traced run, and it is not a pathfinding failure at all.
     * {@code assemble} builds a composite navigation goal out of {@code GoalAdjacent}s, and {@code GoalAdjacent}
     * accepts any cell within one step -- so a composite aimed at several nearby cells is routinely satisfied by the
     * feet before the bot has placed anything. {@code PathingBehavior} then does exactly the right thing and returns
     * without calculating (its line 284: {@code if (goal.isInGoal(ctx.playerFeet())) return false;}), so there is no
     * path and no movement, and the builder waits for an arrival that already happened.
     *
     * <p>Measured on run ce0b0946: 5218 of 20825 ticks -- 25.1% -- were in this state, over 225 separate stretches,
     * 118 of them at least 20 ticks and together holding 4915 ticks. The worst single one: 486 ticks at
     * pos=84.780,-60.000,112.474, identical to three decimals, aim not moving at all, with 39 cells outstanding and
     * none of them deferred. It ended only when a no-progress watchdog fired 115 ticks late, deferred a cell, and the
     * whole thing repeated three more times. The cell it eventually placed took FOUR ticks.
     *
     * <p>So the goal is dropped the moment it is recognised as dead, on the tick it becomes true rather than a
     * hundred ticks later. The next tick's {@code assemble} then builds a fresh one from the current work set, which
     * is what should have happened immediately.
     */
    private void abandonAGoalAlreadySatisfiedWhereWeStand() {
        if (cleanupEscape != null) return; // reaching an intermediate episode goal advances its next bounded action
        Goal goal = princeps.getPathingBehavior().getGoal();
        if (goal == null || princeps.getPathingBehavior().getCurrent() != null) {
            return;   // no goal, or a real path is being walked -- nothing dead about either
        }
        if (!goal.isInGoal(ctx.playerFeet())) {
            return;   // a goal we have not reached is exactly what a goal should be
        }
        traceDeadGoal(goal);
        princeps.getPathingBehavior().secretInternalSetGoal(null);
    }

    /**
     * The DEADGOAL line, collapsed into runs instead of one per tick.
     *
     * <p>It used to write every tick, and {@code "goal=" + goal} serialises a whole {@code JankyComposite} -- up to
     * sixty {@code GoalAdjacent} entries, 832 bytes on average and 3528 at worst. Measured on run 20260802-232954:
     * 17033 lines, 13.5 MB, <b>93.3% of every event byte in the trace</b>, at 0.988 lines per tick once the run had
     * frozen. The string was being BUILT every tick too, on the render thread, for a value that had not changed in
     * ten thousand ticks.
     *
     * <p>Collapsed, not dropped, and the distinction is the whole point: this project has twice mistaken a missing
     * line for a missing event (A28, A29). Every tick is still accounted for -- a run carries {@code repeats=} and
     * the tick it started from, so the totals a census would compute are unchanged. Only the composite is not
     * re-serialised, and only while the bot has not moved.
     */
    private void traceDeadGoal(Goal goal) {
        BetterBlockPos feet = ctx.playerFeet();
        long key = positionKey(feet);
        if (key != deadGoalRunFeet) {
            flushDeadGoalRun();
            deadGoalRunFeet = key;
            deadGoalRunX = feet.x;
            deadGoalRunY = feet.y;
            deadGoalRunZ = feet.z;
            deadGoalRunStart = buildTick;
            deadGoalRunCount = 1;
            deadGoalEmittedCount = 1;
            deadGoalLastEmit = buildTick;
            BuildTrace.cell(buildTick, "DEADGOAL", feet.x, feet.y, feet.z, "goal=" + goal);
            return;
        }
        deadGoalRunCount++;
        if (buildTick - deadGoalLastEmit >= DEADGOAL_REPEAT_TICKS) {
            deadGoalLastEmit = buildTick;
            deadGoalEmittedCount = deadGoalRunCount;
            // No goal string: it is the same composite over the same unmoved feet, and re-serialising it is exactly
            // the cost this exists to remove.
            BuildTrace.cell(buildTick, "DEADGOAL", feet.x, feet.y, feet.z,
                    "repeats=" + deadGoalRunCount + " since=" + deadGoalRunStart);
        }
    }

    /**
     * Closes an open DEADGOAL run so its tail is never lost to the throttle.
     *
     * <p>Reports the run's OWN feet cell, not the current one: by the time a run ends the bot has moved, and a
     * closing line carrying the new position would attribute the whole stretch to a cell it never stood on while it
     * was stuck.
     */
    private void flushDeadGoalRun() {
        if (deadGoalRunFeet != Long.MIN_VALUE && deadGoalRunCount > deadGoalEmittedCount) {
            BuildTrace.cell(buildTick, "DEADGOAL-END", deadGoalRunX, deadGoalRunY, deadGoalRunZ,
                    "repeats=" + deadGoalRunCount + " since=" + deadGoalRunStart);
        }
        deadGoalRunFeet = Long.MIN_VALUE;
        deadGoalRunCount = 0;
        deadGoalEmittedCount = 0;
    }

    /**
     * One line per build tick: where the bot is, exactly where it is looking, and what it is working on.
     *
     * <p>This is the half the ordinary log never had. That log records faults only -- a placement that SUCCEEDS
     * produces no line at all -- which is why 175 missing cells could be invisible in it. Here the position is written
     * to a thousandth of a block and the look to a tenth of a degree, because both misorientation modes seen in the
     * world turn on exactly that: a sticky piston that wanted facing=up holds facing=south, which is a look that was
     * level when it needed to be steep, and only the real yaw and pitch at the moment of the click can show it.
     *
     * <p>The NEAREST field is vanilla's own {@code getNearestLookingDirection} answer for that look, since that single
     * value -- not the angles -- is what decides the facing of every piston, dropper and observer in the schematic.
     */
    /** The CLICK trace line. @see #traceTick for why the live rotation matters more than the intended one. */
    private void traceClick(Placement placement, Rotation rot, Item expectedItem) {
        if (!BuildTrace.isActive()) {
            return;
        }
        Rotation live = ctx.playerRotations();
        Vec3 liveLook = ctx.player().getViewVector(1.0F);
        BuildTrace.cell(buildTick, "CLICK",
                placement.target.getX(), placement.target.getY(), placement.target.getZ(),
                "want=" + blockName(placement.desired)
                        + " intend=" + String.format(java.util.Locale.ROOT, "%.2f,%.2f",
                                rot.getYaw(), rot.getPitch())
                        + " live=" + String.format(java.util.Locale.ROOT, "%.2f,%.2f",
                                live.getYaw(), live.getPitch())
                        + " nearest=" + Direction.getApproximateNearest(
                                liveLook.x, liveLook.y, liveLook.z).getName()
                        + " aimd=" + String.format(java.util.Locale.ROOT, "%.2f", aimMovementDegrees)
                        + " eye=" + String.format(java.util.Locale.ROOT, "%.3f", ctx.player().getEyeY())
                        + " sneak=" + (ctx.player().isCrouching() ? 1 : 0)
                        + " ground=" + (ctx.player().onGround() ? 1 : 0)
                        + " against=" + placement.placeAgainst.getX() + "," + placement.placeAgainst.getY()
                        + "," + placement.placeAgainst.getZ() + " face=" + placement.side
                        + " slot=" + placement.hotbarSelection
                        + " item=" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(expectedItem).getPath());
    }

    private void traceTick() {
        if (!BuildTrace.isActive()) {
            return;
        }
        Rotation rot = ctx.playerRotations();
        Vec3 look = ctx.player().getViewVector(1.0F);
        Direction nearest = Direction.getApproximateNearest(look.x, look.y, look.z);
        String action;
        if (paused) {
            action = "paused";
        } else if (!isActive()) {
            action = "inactive";
        } else if (placementTargetLock.isActive()) {
            // PLACING IST EIN EIGENER EIMER, und ohne ihn ist der Tick-Zensus nicht auswertbar.
            //
            // Gemessen und dann VERWORFEN: ein Zensus ohne diese Zeile meldete 49 Prozent "target-no-stance"
            // und liess daraus schliessen, der Bot haenge die halbe Zeit. Das war ein Messfehler --
            // plannedPlacementStance wird NUR im Recovery-Pfad gesetzt, also trug jede ganz gewoehnliche
            // Platzierung dasselbe Etikett: zielen, klicken, Klickpause abwarten. Steht der Bot still, sagte der
            // Eimer nicht, ob er arbeitet oder wartet, und genau das war die Frage.
            //
            // Ein angemeldeter Klick oder ein Ziel mit gesetztem Setzpunkt heisst: der Bot TUT gerade das, wofuer
            // er da ist. Erst was danach uebrig bleibt, ist Stillstand, den man untersuchen muss.
            if (pendingPlacementRequest != null || committedPlaceTarget != null) {
                action = "placing";
            } else {
                action = plannedPlacementStance == null ? "target-no-stance"
                        : ctx.playerFeet().equals(plannedPlacementStance) ? "at-stance" : "walking-to-stance";
            }
        } else {
            action = "searching";
        }
        net.minecraft.world.phys.Vec3 vel = ctx.player().getDeltaMovement();
        ItemStack held = ctx.player().getInventory().getSelectedItem();
        String hand = held.isEmpty() ? "-"
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
        String path;
        if (princeps.getPathingBehavior().getCurrent() != null) {
            // WELCHE BEWEGUNG GERADE DRAN IST, nicht nur wie lang der Weg ist. "len15" sagt ueber 1881 Ticks
            // dasselbe und erklaert nichts: Lauf da3f8593 endete damit, dass der Bot die Zelle 117,-58,128 mit
            // ihren VIER bewiesenen Standplaetzen nie erreichte, obwohl durchgehend ein Weg berechnet war. Er kam
            // auf 1,06 Bloecke heran, verbrachte 96 Ticks in unmittelbarer Naehe und trat nie darauf; dabei war
            // er wiederholt in der Luft (ground=0, y faellt von -55,7 auf -57), also sprang und fiel er im Kreis.
            // Ohne den Namen der Bewegung, die dort scheitert, ist jede Erklaerung geraten.
            IPathExecutor laufend = princeps.getPathingBehavior().getCurrent();
            String bewegung = "-";
            try {
                java.util.List<princeps.api.pathing.movement.IMovement> zuege = laufend.getPath().movements();
                int i = laufend.getPosition();
                if (i >= 0 && i < zuege.size()) {
                    bewegung = zuege.get(i).getClass().getSimpleName();
                }
            } catch (RuntimeException ignored) {
                // eine Messung darf den Bau nie anhalten
            }
            path = "len" + laufend.getPath().length() + "/" + laufend.getPosition() + ":" + bewegung;
        } else if (princeps.getPathingBehavior().getGoal() != null) {
            // WARUM ES KEINEN WEG GIBT, nicht nur DASS es keinen gibt. "goal-no-path" heisst woertlich nur
            // "ein Ziel ist gesetzt und es laeuft kein Weg" -- und das trifft auf drei voellig verschiedene
            // Lagen zu: der Bot steht schon im Ziel, es rechnet gerade jemand, oder die Suche ist gescheitert.
            //
            // Der Unterschied ist nicht akademisch. Gemessen in Lauf 28b210f3: 55 Strecken von je bis zu 120
            // Ticks unter path=goal-no-path bei lane=B und scaffold=1, also mit Planer und Ausfuehrer einer
            // Meinung -- und im selben Log steht 717-mal "reached goal after N nodes" und KEIN EINZIGES
            // Fehlschlag-Ereignis. Ein Pfadfinder, der nie scheitert, wurde in diesen 120 Ticks nicht gefragt.
            // Welche der drei Lagen das war, sagt keine vorhandene Zeile, und ohne sie ist jeder weitere
            // Schritt geraten.
            Goal g = princeps.getPathingBehavior().getGoal();
            boolean inGoal = g != null && g.isInGoal(ctx.playerFeet());
            boolean calculating = princeps.getPathingBehavior() instanceof princeps.behavior.PathingBehavior pb
                    && pb.getInProgress().isPresent();
            path = inGoal ? "at-goal" : calculating ? "calculating" : "goal-no-path";
        } else {
            path = "-";
        }
        BuildTrace.tick(buildTick, ctx.player().getX(), ctx.player().getY(), ctx.player().getZ(),
                ctx.player().getEyeY(), ctx.player().isCrouching(), ctx.player().onGround(),
                vel.x, vel.y, vel.z,
                rot.getYaw(), rot.getPitch(), nearest.getName(), aimMovementDegrees,
                hand, ctx.player().getInventory().getSelectedSlot(),
                layer, incorrectPositions == null ? -1 : incorrectPositions.size(), parkedCells.size(),
                path, action, committedPlaceTarget == null ? null
                        : committedPlaceTarget.x + "," + committedPlaceTarget.y + "," + committedPlaceTarget.z,
                // DER BAHN-ZUSTAND, weil ohne ihn ein goal-no-path nicht lesbar ist. In Lauf 68095522 standen
                // 32 Strecken von je rund 124 Ticks -- 12 % des ganzen Laufs -- mit path=goal-no-path, waehrend
                // die Sonde derselben Zelle alle sechs Ticks B=COMPLETE positions=3 meldete. Ob der echte Router
                // dabei unter Bahn B fuhr oder noch unter A, entscheidet, wo der Fehler sitzt, und keine der
                // vorhandenen Zeilen sagt es. Das Notizfeld war bei V2 leer.
                "lane=" + (electedCell == null ? "-" : laneForCurrentCell(electedCell) == Lane.A_NO_PLACING ? "A"
                        : laneForCurrentCell(electedCell) == Lane.B_HELPERS_ALLOWED ? "B" : "L")
                        + " scaffold=" + (scaffoldPassAllowed ? 1 : 0)
                        + " elect=" + (electedCell == null ? "-"
                                : electedCell.x + "," + electedCell.y + "," + electedCell.z)
                        // DAS ZIEL DES ECHTEN ROUTERS, weil genau hier die Sonde und er auseinanderlaufen.
                        // Der Widerlegungs-Durchgang hat im selben Stillstandsfenster zwei Suchen nebeneinander
                        // gelegt: die Sonde meldet "reached goal after 519 nodes ... 5ms", der Router frisst
                        // eine PathNode map size von 210998 und rund 2,1 s ohne Ergebnis. Ein Faktor 400 bei
                        // gleichem Start, gleichem Ziel und gleichem Kontext ist unmoeglich -- also ist eine
                        // dieser drei Groessen nicht gleich, und das Ziel ist die einzige, die keine Zeile nennt.
                        + " goal=" + String.valueOf(princeps.getPathingBehavior().getGoal())
                                .replace(' ', '_'));
        // Fed from the same values the trace line carries, deliberately: a recorder that samples the world a second
        // time can disagree with the trace about the tick it is describing, and then the two artefacts cannot be read
        // together -- which is the only way either of them is useful.
        ActionJournal.tick(buildTick,
                String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                        ctx.player().getX(), ctx.player().getY(), ctx.player().getZ()),
                action,
                committedPlaceTarget == null ? "-"
                        : committedPlaceTarget.x + "," + committedPlaceTarget.y + "," + committedPlaceTarget.z,
                hand,
                princeps.getPathingBehavior().getGoal() == null ? "-"
                        : String.valueOf(princeps.getPathingBehavior().getGoal()));

        // Hand the tick and the intent to the writers that have no builder reference -- MovementHelper and
        // MovementPillar place blocks and could previously say neither WHEN nor WHY. See BuildTrace.context.
        BuildTrace.context(buildTick,
                "for=" + (committedPlaceTarget == null ? "-"
                        : committedPlaceTarget.x + "," + committedPlaceTarget.y + "," + committedPlaceTarget.z)
                        + " stance=" + (plannedPlacementStance == null ? "-"
                        : plannedPlacementStance.x + "," + plannedPlacementStance.y + "," + plannedPlacementStance.z)
                        + " act=" + action
                        + " feet=" + ctx.playerFeet().x + "," + ctx.playerFeet().y + "," + ctx.playerFeet().z);
    }

    /** How far the visible aim moved since last tick. Measured once per tick, before anything reads it. */
    private void trackAimMovement() {
        Rotation now = ctx.playerRotations();
        if (prevAimValid) {
            float dy = Math.abs(net.minecraft.util.Mth.wrapDegrees(now.getYaw() - prevAimYaw));
            float dp = Math.abs(now.getPitch() - prevAimPitch);
            aimMovementDegrees = Math.max(dy, dp);
        }
        prevAimYaw = now.getYaw();
        prevAimPitch = now.getPitch();
        prevAimValid = true;
    }

    /**
     * TRIED AND DISPROVEN, kept as a record of what the wrong-facing bug is NOT.
     *
     * <p>The theory: vanilla picks a piston's facing by which axis the view vector points along most, a boundary with
     * no tolerance at all, and the click leaves a tick after the gate checked the rotation -- so a check made mid-turn
     * checks a rotation that no longer exists when it matters. Requiring the crosshair to be at rest first should
     * therefore have removed the last one percent of wrongly-oriented pistons.
     *
     * <p>It did not. A world audit of etz-basalt after this was in place still found 2 of 144 placed sticky pistons
     * facing the wrong way -- the same ~1.4% as before it, and as before the +-3 degree margin. Two independent
     * client-side aim fixes leaving the rate untouched is a fairly clear statement that the divergence is not on the
     * client: the likely remaining explanation is that the SERVER derives the facing from the rotation IT last
     * received, which need not be the one the client simulated against. Verifying that needs a server-side view of the
     * placement, which the bench does not yet have.
     *
     * <p>Unwired rather than deleted so the next person does not spend the same day on it. It stays unwired: a check
     * that costs the build up to 20 ticks per placement and buys nothing measurable is exactly the kind of complexity
     * this builder already has too much of.
     */
    @SuppressWarnings("unused")
    private boolean aimHasSettledFor(BlockState desired) {
        if (desired == null || !desired.hasProperty(BlockStateProperties.FACING)
                && !desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            aimSettleWaitTicks = 0;
            return true;
        }
        if (aimMovementDegrees <= AIM_SETTLED_DEGREES) {
            aimSettleWaitTicks = 0;
            return true;
        }
        if (++aimSettleWaitTicks >= AIM_SETTLE_MAX_WAIT_TICKS) {
            aimSettleWaitTicks = 0;
            return true;    // never trade a placed block for a perfectly still crosshair
        }
        return false;
    }

    /**
     * Opens the door the bot is standing inside, when it has stopped getting anywhere.
     *
     * <p>A doorway is somewhere to walk through, and the pathfinder treats it that way: {@code canWalkThrough} answers
     * YES for every wooden door, so a route may legitimately pass through one. What no part of the pathfinder models is
     * the door the bot is standing IN -- {@link MovementHelper#isDoorPassable} is consulted only from
     * {@code MovementTraverse}, and only ever about doors at the DESTINATION of a step. The closed slab at the SOURCE
     * simply does not exist as far as route planning is concerned.
     *
     * <p>So the bot walks into the doorway, the door blocks the way out along its facing axis, it pushes against it,
     * and the pathfinder happily re-plans the same impossible step forever. In the last basalt run it did that at
     * 93,-60,98 -- one of the farm's four entrance doors -- for the final 4,400 ticks of the run, until the owner
     * opened the door by hand.
     *
     * <p>The fix is the one a player would use without thinking: if you are stuck in a doorway, open the door. It is
     * deliberately not clever -- it does not reason about which way it wants to go or which axis is blocked, because a
     * door that is open blocks nothing at all. Interaction, so no sneak: sneaking with a block in hand places the block
     * instead of using the door.
     */
    private PathingCommand escapeIfStuckInADoorway() {
        if (buildTick - lastCellCompletedTick < DOOR_ESCAPE_TICKS || ticks > 0) {
            return null;
        }
        BetterBlockPos feet = ctx.playerFeet();
        for (BetterBlockPos at : new BetterBlockPos[]{feet, feet.above()}) {
            BlockState state = ctx.world().getBlockState(at);
            Block block = state.getBlock();
            boolean openable = (block instanceof DoorBlock && block != Blocks.IRON_DOOR)
                    || block instanceof FenceGateBlock;
            if (!openable || !state.hasProperty(BlockStateProperties.OPEN)
                    || state.getValue(BlockStateProperties.OPEN)) {
                continue;   // not a door, cannot be hand-opened, or already open -- not this trap
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, at, ctx.playerController().getBlockReachDistance());
            if (!rot.isPresent()) {
                continue;
            }
            princeps.getLookBehavior().updateTarget(rot.get(), true);
            if (ctx.isLookingAt(at) && !ctx.player().isCrouching()) {
                BuildTrace.intendWorldChange("door-escape", at.x, at.y, at.z, "opening to get out of it");
                princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                ticks = 5;
                logMechanic("Standing inside a closed " + blockName(state) + " at " + at.x + "," + at.y + "," + at.z
                        + " with nothing else working; opening it to get out");
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return null;
    }

    /**
     * Shuts any container screen the build has accidentally opened.
     *
     * <p>A schematic is full of blocks that answer a right-click by opening a menu: chests, hoppers, droppers,
     * crafting tables, furnaces, lecterns. etz-basalt alone has 88 chests, 140 hoppers and 32 droppers. One stray
     * right-click on any of them and the client is sitting in a GUI -- and the consequence is out of all proportion to
     * the cause, because {@link princeps.behavior.InventoryBehavior#onTick} returns immediately while a container is
     * open. Its move clock then stops advancing, so the rate limiter refuses every hotbar swap forever, so any layer
     * needing more than the nine materials already in hand can never be served. The build does not crash or complain;
     * it pauses for a swap that will never be granted.
     *
     * <p>That is exactly how a lighthouse run spent 1800 ticks standing still: 1944 "hotbar fetch needed" lines, a
     * released lock, sixteen outstanding cells, and one open crafting table nobody could see.
     *
     * <p>An autonomous builder has no business in a GUI, so this needs no cleverness about which block opened it.
     *
     * <p>A PAUSED builder, however, has no business closing one either. Pause is how a caller says "hands off, I
     * need the screen", and the reason above does not apply to it: a paused build is not going to ask for a hotbar
     * swap, so no swap can be refused and nothing can stall. Closing anyway is pure interference.
     *
     * <p>It cost days. The OneBlock client buys its materials from the DonutSMP auction house and pauses the
     * builder for it; this ran anyway, because it sits ahead of the {@code paused} return further down onTick, and
     * shut the auction window one tick after the server opened it. Every restock failed while the first purchase
     * of a run -- made before any build exists -- always succeeded. Three explanations were reasoned out and
     * measured wrong before a stack trace named this line.
     */
    private void closeAnyContainerScreen() {
        if (ctx.player() == null) {
            return;
        }
        if (paused) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        // The SIGN EDITOR is not a container, and that is why it slipped through: placing a sign makes the server open
        // its edit screen, and a screen with no menu behind it left containerMenu untouched, so the check below never
        // saw it. The owner watched the bot sit in the sign editor mid-build. Until the builder actually writes sign
        // text from the schematic's block entity data, there is nothing to type there and it should simply go away.
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen) {
            logMechanic("The sign editor opened after placing a sign; closing it (sign text is not written yet)");
            mc.setScreen(null);
            return;
        }
        if (ctx.player().containerMenu == ctx.player().inventoryMenu) {
            return;
        }
        logMechanic("A container screen was open (" + ctx.player().containerMenu.getClass().getSimpleName()
                + "); closing it. While one is open no hotbar swap can ever be granted, so the build would stall on"
                + " the next material it needs.");
        if (mc.screen != null) {
            mc.setScreen(null); // routes through the screen's own onClose, which closes the menu server-side too
        } else {
            ctx.player().closeContainer();
        }
    }

    private void releasePlacementTarget() {
        // The lock going away without the cell having been built means whatever walking was done for it was wasted.
        // Simply forgetting the walk here is what makes the ratio honest -- only credited walks count.
        walkTargetKey = -1L;
        placementTargetLock.release();
        committedPlaceTarget = null;
        plannedPlacementStance = null;
        placementCenteringTicks = 0;
        placementCenterSettleTicks = 0;
        pendingPlacementRequest = null;
        pendingPlacementItem = null;
        pendingPlacementRequestThrottled = false;
        resetOrdinaryGateTracking();
        princeps.getInputOverrideHandler().getBlockPlaceHelper().clearExpectedPlacement();
        // The hand goes back to the pool with the target. Held any longer it would protect a slot for a cell that
        // is no longer being built, which on a palette larger than the bar is a slot nobody may use.
        princeps.getInventoryBehavior().clearBuilderHotbarLock();
        resetUnacknowledgedPlacementRequests();
    }

    /**
     * Does this block hang on a wall rather than stand on the ground?
     *
     * <p>Signs, torches, ladders, buttons and levers are placed by clicking the SIDE face of the block they attach
     * to. While the surrounding structure is still going up that face is usually walled in or unreachable, the ray
     * lands on the floor instead, and what would be placed is the standing variant -- so the cell is deferred, over
     * and over, at the cost of a full stance search each time. The owner's basalt schematic spent its throughput
     * exactly there: 52 deferrals of one wall sign while the build crawled.
     *
     * <p>A human hangs the decorations after the walls are up. So does the builder now.
     */
    private static boolean isWallMounted(BlockState state) {
        net.minecraft.world.level.block.Block block = state.getBlock();
        return block instanceof net.minecraft.world.level.block.WallSignBlock
                || block instanceof net.minecraft.world.level.block.WallTorchBlock
                || block instanceof net.minecraft.world.level.block.WallHangingSignBlock
                || block instanceof net.minecraft.world.level.block.WallBannerBlock
                || block instanceof net.minecraft.world.level.block.WallSkullBlock
                || block instanceof net.minecraft.world.level.block.LadderBlock
                || block instanceof net.minecraft.world.level.block.ButtonBlock
                || block instanceof net.minecraft.world.level.block.LeverBlock;
    }

    /**
     * Which neighbour has to be clicked to make this state exist -- read off the state, not searched for.
     *
     * <p>A block has six faces, and for anything that hangs, stands or sticks, exactly one of them is the answer and
     * the state already says which. A wall sign with {@code facing=west} hangs on the block to its EAST; the only
     * click that can produce it is on that block's west face. Trying the other five is not a search, it is five
     * raytraces whose outcome was known before they were cast -- and at five aim points per face, per candidate
     * stance, that was 30 rays where 5 suffice.
     *
     * <p>Returned as the direction FROM the target cell TO the block to click, which is what the placement code
     * calls {@code against}. Null means the state genuinely does not care -- a full cube, a log, a fence -- and
     * every face is a legitimate answer, so the caller falls back to trying them.
     */
    private static Direction requiredSupportDirection(BlockState desired) {
        // Buttons and levers say it outright: they carry the attachment as a property.
        if (desired.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            switch (desired.getValue(BlockStateProperties.ATTACH_FACE)) {
                case FLOOR:
                    return Direction.DOWN;
                case CEILING:
                    return Direction.UP;
                case WALL:
                    return desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                            ? desired.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()
                            : null;
            }
        }
        // NOT a wall hanging sign: it hangs BETWEEN the blocks to its left and right, so its support is perpendicular
        // to facing, not behind it. The rule below would aim the bot at a face that holds nothing.
        if (desired.getBlock() instanceof net.minecraft.world.level.block.WallHangingSignBlock) {
            return null;
        }
        // A HOPPER's facing comes from the face you CLICK, not from where you look:
        // HopperBlock.getStateForPlacement is getClickedFace().getOpposite() (verified in 26.1.2 bytecode, with a
        // vertical result collapsed to DOWN). So the support is determined outright -- click the face F.getOpposite()
        // of the block at T+F -- and there is nothing to search for. etz-basalt has 140 hoppers, all horizontal, and
        // every one of them was being tried against all six faces with the look treated as if it mattered.
        if (desired.getBlock() instanceof net.minecraft.world.level.block.HopperBlock
                && desired.hasProperty(BlockStateProperties.FACING)) {
            Direction facing = desired.getValue(BlockStateProperties.FACING);
            if (facing.getAxis() != Direction.Axis.Y) {
                return facing;
            }
            return Direction.UP;    // facing=down is produced by clicking the UP face of the block above
        }
        // Signs, torches, ladders, banners, skulls: facing points AWAY from the wall they hang on.
        if (isWallMounted(desired) && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return desired.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        return null;
    }

    /** Absolute schematic support cell for states with one exact click/support direction. Package-visible so the
     *  trim regression can verify the real WALL/NORTH-lever geometry instead of only testing a synthetic graph. */
    static BetterBlockPos requiredSupportPosition(BetterBlockPos cell, BlockState desired) {
        Direction direction = requiredSupportDirection(desired);
        if (direction == null) {
            return null;
        }
        return new BetterBlockPos(
                cell.x + direction.getStepX(),
                cell.y + direction.getStepY(),
                cell.z + direction.getStepZ());
    }

    /** The faces worth trying for this state: the one that the state determines, or all six when it determines none. */
    private static Direction[] supportDirectionsFor(BlockState desired) {
        Direction only = requiredSupportDirection(desired);
        if (only != null) {
            return new Direction[] { only };
        }
        // NOT narrowed for look-derived facings. That was tried and it was the single most expensive mistake in this
        // file's history: see requiredLookDirections for the measurement. A block whose facing comes from the LOOK can
        // be clicked against ANY neighbour, and constraining the neighbour to the facing axis starved 624 of
        // etz-basalt's 672 sticky pistons of every clickable face at once.
        //
        // Ordered rather than narrowed, though, because one face IS better than the rest. Clicking the neighbour at
        // T+L, where L is the look direction, sends the ray straight along L: the two competing axes clamp to zero and
        // the aim is dominant by the full 1.5 blocks. Every other face wins on a margin -- for a horizontal look
        // against a lateral face it comes down to 0.52 against 0.50, which is two hundredths of a block between the
        // right facing and the wrong one. So try the sure thing first and keep the others as the fallback that made
        // those pistons placeable at all.
        Direction[] looks = requiredLookDirections(desired);
        if (looks == null) {
            return Direction.values();
        }
        Direction[] ordered = new Direction[Direction.values().length];
        int at = 0;
        for (Direction look : looks) {
            ordered[at++] = look;
        }
        for (Direction d : Direction.values()) {
            boolean alreadyFirst = false;
            for (Direction look : looks) {
                alreadyFirst |= d == look;
            }
            if (!alreadyFirst) {
                ordered[at++] = d;
            }
        }
        return ordered;
    }

    /**
     * The directions the bot must be LOOKING to get this state's facing, or null when the look does not decide it.
     *
     * <p>These are look directions, not faces to click, and the difference is the whole correction. An earlier version
     * of this method returned {@code {facing.getOpposite(), facing}} as the *support* faces, reasoning that the ray
     * runs along L, through the target cell, and strikes the block beyond it. That is one way to do it and it is not
     * the requirement. Vanilla asks only two things, and they are independent: the click must land on a face of a
     * neighbour pointing at the cell (any of the six), and the look's dominant axis must be L. Tying the neighbour to
     * the facing axis threw away the other four.
     *
     * <p>What that cost, measured on etz-basalt: 624 of its 672 sticky pistons are {@code facing=up} in a single row
     * with AIR beneath them in the schematic and their solid neighbour ABOVE, which a bottom-up build has not reached
     * yet. So both faces the rule allowed were air, and the stance report read "of 145 candidate stances: 49 standable,
     * 0 would work [49 nothing solid to click]" -- 49 out of 49, with every other rejection counter at zero. 1548 of
     * that run's 1685 stance failures were this one block. The lateral neighbours were standing there the whole time.
     *
     * <p>Both signs are returned rather than one, because vanilla genuinely is not consistent about which way round it
     * reads the look, and the inconsistency is not guessable. Verified in 26.1.2 bytecode: a PISTON is
     * {@code getNearestLookingDirection().getOpposite()}, while an OBSERVER is
     * {@code getNearestLookingDirection().getOpposite().getOpposite()} -- a double opposite that cancels, so an
     * observer faces exactly where you look and a piston faces exactly away. Two blocks that look like the same family
     * and take opposite conventions. Returning both signs and letting the simulation pick is what makes that
     * survivable; hardcoding per block class would be a list to get wrong for every block nobody thought of.
     *
     * <p>ONLY for the blocks that read the DOMINANT axis of the look. It does not apply to anything that reads only the
     * yaw -- doors, repeaters, comparators, stairs. There the pitch is free, so the bot can face north while aiming
     * steeply down at the block BELOW the cell, and nothing about the aim is determined.
     *
     * @see #mostAlignedPointOnFace which turns one of these into the exact point to aim at
     */
    private static Direction[] requiredLookDirections(BlockState desired) {
        // The six-way FACING property AND a block that derives it from getNearestLookingDirection. Both conditions
        // matter: the property alone would catch shulker boxes and end rods, the block alone would miss nothing but
        // reads less clearly.
        if (!desired.hasProperty(BlockStateProperties.FACING)) {
            return null;
        }
        Block block = desired.getBlock();
        if (!(block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock)
                && !(block instanceof net.minecraft.world.level.block.DispenserBlock)
                && !(block instanceof ObserverBlock)) {
            return null;
        }
        Direction facing = desired.getValue(BlockStateProperties.FACING);
        return new Direction[] { facing.getOpposite(), facing };
    }

    /** How far inside the face's edge a derived aim point sits, so the ray cannot graze past it. */
    private static final double FACE_INSET = 0.02D;

    /**
     * Does placing this state require the bot to look UPWARD? If so it has to stand BELOW the cell, and the usual
     * one-block stance window cannot contain such a stance.
     *
     * <p>The arithmetic, for a lateral support face whose top edge is the highest point on it worth aiming at. Level
     * with the cell the crouched eye sits 1.27 above the feet and the edge only 0.98, so the aim points DOWN. One below,
     * the eye is 0.27 under the edge against a horizontal 1.5 -- still not dominant. Two below it is 1.71 against 0.5
     * from directly beneath, which is dominant by a wide margin. So the window has to reach dy=-2 to contain a working
     * stance at all, and -3 to have any choice among them.
     *
     * <p>Only asked for the vertical half of the family, because the horizontal facings never need it: their look is
     * level, and a level look is available from a level stance.
     */
    private static boolean needsUpwardLook(BlockState desired) {
        Direction[] looks = requiredLookDirections(desired);
        if (looks == null) {
            return false;
        }
        for (Direction look : looks) {
            if (look == Direction.UP) {
                return true;
            }
        }
        return false;
    }

    /** Lowest stance worth considering for this state, relative to the cell. @see #needsUpwardLook */
    private static int lowestStanceOffset(BlockState desired) {
        // -3 rather than -1 for these, and -1 for everything else: the window is walked for every candidate cell, so
        // widening it unconditionally would cost 67% more standability tests on the whole build to serve 4% of it.
        return needsUpwardLook(desired) ? -3 : -1;
    }

    /**
     * Is this cell the standing room that an unplaced upward-look cell above it is going to need?
     *
     * <p>The one place where bottom-up is the wrong order, and it is forced rather than chosen. An upward-look cell has
     * to be placed from BELOW (see {@link #needsUpwardLook}), so the two cells beneath it must be empty when its turn
     * comes -- and in a bottom-up build they were filled two layers earlier. etz-basalt has 224 pistons in exactly that
     * shape: air directly below, a schematic block below that, and it is the block that makes the piston impossible.
     * Standing on it instead puts the bot's head in the piston's own cell, which vanilla will not place into.
     *
     * <p>So those cells wait. Nothing is skipped and nothing is remembered: the answer is recomputed from the world
     * every time, so the moment the cell above goes in -- or gets retired, and stops having a claim -- this returns
     * false and the cell rejoins the work set on the next recalc. Bottom-up layers are cumulative (minY is always 0),
     * which is what makes waiting safe: layer N+2 still has layer N's cells in scope, so the build genuinely comes back
     * for it rather than climbing away.
     *
     * <p>Deliberately NOT symmetric with the downward case. A downward look is served by a stance level with the cell,
     * which the build order already provides, so nothing above ever needs reserving.
     */
    private boolean isReservedStanceSpace(int x, int y, int z, BuilderCalculationContext bcc) {
        for (int up = 1; up <= 2; up++) {
            BlockState above = bcc.getSchematic(x, y + up, z, bcc.bsi.get0(x, y + up, z));
            if (above == null || above.isAir() || !needsUpwardLook(above)) {
                continue;
            }
            if (valid(bcc.bsi.get0(x, y + up, z), above, false)) {
                continue;   // already standing: it has no further use for the space
            }
            if (isCellParked(x, y + up, z)) {
                continue;   // parked, so its claim is void -- never let a set-aside cell block a placeable one
            }
            return true;
        }
        return false;
    }

    /** X=0, Y=1, Z=2. Written out rather than {@code Axis.ordinal()} so a reordered vanilla enum cannot silently
     *  reinterpret every aim point in this file. */
    private static int axisIndex(Direction.Axis axis) {
        switch (axis) {
            case X: return 0;
            case Y: return 1;
            default: return 2;
        }
    }

    /**
     * The point on a support face that makes {@code look} the dominant axis of the aim, or null if this face cannot.
     *
     * <p>A derivation with a closed form, replacing five sampled points and a hope. The aim direction is
     * {@code d = P - eye} and vanilla's {@code getNearestLookingDirection} picks whichever axis of {@code d} is largest,
     * so the job is to choose {@code P} on the face rectangle maximising {@code d} along {@code look} while shrinking
     * the other two components. Each axis has exactly one right answer:
     *
     * <ul>
     *   <li>the face's own normal axis -- fixed, it is the plane the face lies in;
     *   <li>the look's axis, when free -- the far EDGE in that direction, which is what makes the look steep;
     *   <li>the remaining axis -- clamped to the eye, since any offset there is pure competition.
     * </ul>
     *
     * <p>Worked through for the 624 pistons: face is the lateral neighbour's side (normal X), look is DOWN, so the
     * point is the BOTTOM edge of that side face, level in Z with the eye. From a stance one block above the cell and
     * next to it the aim comes out at {@code d = (1.5, -2.25, 0)} -- down by a clear margin, so the piston lands
     * {@code facing=up}. The old sampled points offered y = 0.25, 0.5 and 0.75 of that same face and nothing lower, so
     * against a crouched eye 1.27 up the vertical component could never exceed the horizontal 1.5. Not one of them
     * could have worked, from any stance, ever.
     *
     * <p>Dominance is compared here rather than by asking vanilla, and strictly: the look's component must EXCEED both
     * others, where {@code getNearest} would accept a tie and break it by enum order. A tie is a coin flip decided by
     * floating point, and this is the one decision in the build that must not be a coin flip.
     */
    // Package-private statt private: BuilderAimDominanceTest ruft GENAU diese Methode auf, statt die Formel zu
    // kopieren. Eine Regel, die nur in einer Testkopie steht, kann im Produktionscode still zurueckfallen.
    static Vec3 mostAlignedPointOnFace(Vec3 eye, BlockPos againstPos, AABB aabb, Direction faceNormal,
                                               Direction look) {
        double[] min = { againstPos.getX() + aabb.minX, againstPos.getY() + aabb.minY, againstPos.getZ() + aabb.minZ };
        double[] max = { againstPos.getX() + aabb.maxX, againstPos.getY() + aabb.maxY, againstPos.getZ() + aabb.maxZ };
        double[] e = { eye.x, eye.y, eye.z };
        int fixed = axisIndex(faceNormal.getAxis());
        int lookAxis = axisIndex(look.getAxis());
        double[] p = new double[3];
        for (int a = 0; a < 3; a++) {
            if (a == fixed) {
                p[a] = faceNormal.getAxisDirection() == Direction.AxisDirection.POSITIVE ? max[a] : min[a];
            } else if (a == lookAxis) {
                p[a] = look.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? max[a] - FACE_INSET : min[a] + FACE_INSET;
            } else {
                p[a] = Math.min(Math.max(e[a], min[a] + FACE_INSET), max[a] - FACE_INSET);
            }
        }
        double[] d = { p[0] - e[0], p[1] - e[1], p[2] - e[2] };
        double along = look.getStepX() * d[0] + look.getStepY() * d[1] + look.getStepZ() * d[2];
        for (int a = 0; a < 3; a++) {
            // Also rejects along <= 0 on its own: an aim pointing the wrong way down the look axis loses to any
            // non-negative competitor, including zero.
            //
            // MIT ABSTAND, NICHT KNAPP -- und das ist der Unterschied zwischen "die Richtung stimmt meistens" und
            // "die Richtung steht fest". Vorher hiess die Bedingung `Math.abs(d[a]) >= along`, ein Zielpunkt
            // durfte also mit beliebig kleinem Vorsprung gewinnen.
            //
            // GEMESSEN, basalt 20260807-165024, die eine Zelle von 1446, an der der Lauf endete: 81,-59,121,
            // gewollt sticky_piston[facing=up]. Client und Server hatten NACHWEISLICH dieselbe Rotation
            // (yaw -50,404 / pitch 37,852 auf beiden Seiten, mit Threadnamen belegt) -- und trotzdem kam ein
            // anderer Block heraus. Warum: |y| = 0,6136 gegen |x| = 0,6086. Die senkrechte Komponente gewann um
            // 0,8 Prozent. Auf so einer Kippkante entscheidet nicht mehr die Geometrie, sondern in welcher
            // Reihenfolge zwei Implementierungen dieselben drei Zahlen addieren.
            //
            // Das ist NICHT die im Baum widerlegte Jitter-Pruefung (placementSurvivesAimJitter stoerte die
            // ROTATION und rechnete mit derselben Funktion nach -- gleiche Fehlerrate mit und ohne). Hier wird
            // der ZIELPUNKT verworfen, solange seine Dominanz nicht belastbar ist. Ein Punkt, den es nicht gibt,
            // kann auch nicht knapp gewinnen.
            if (a != lookAxis && Math.abs(d[a]) * AIM_DOMINANCE_MARGIN >= along) {
                return null;
            }
        }
        // UND BEI SENKRECHTER BLICKACHSE GEGEN DIE GANZE WAAGERECHTE, nicht gegen ihre momentane Aufteilung.
        //
        // Die Schleife darueber vergleicht Achse fuer Achse, und genau darin steckt eine Abhaengigkeit vom Yaw:
        // die waagerechte Laenge des Blickvektors ist cos(pitch), unabhaengig vom Yaw -- der Yaw entscheidet nur,
        // wie sie sich auf x und z verteilt. Steht er diagonal, traegt jede Achse rund 71 Prozent davon und beide
        // verlieren gegen y; steht er achsparallel, traegt EINE Achse alles. Ein Zielpunkt, der die Einzelachsen
        // schlaegt, kann also trotzdem kippen, sobald der Server einen anderen Yaw heranzieht als der Client
        // geprueft hat -- und der Server nimmt den Kopf-Yaw, dessen Verzug nicht fest bei eins liegt.
        //
        // GEMESSEN, drei Laeufe, drei Abbrueche, jedes Mal sticky_piston[facing=up] und jedes Mal waagerecht
        // gelandet: 106,-59,82 und 74,-59,86 bei pitch 42,6 und 104,-59,82 bei pitch 44,338. Alle unter 45 Grad.
        // Die Klicks waren armed=yes und die Vorhersage des Clients war fuer SEINEN Yaw richtig: bei 104,-59,82
        // lag der Yaw diagonal (227,5 Grad), also x = 0,524 und z = -0,481 gegen y = -0,703, ein Vorsprung von
        // Faktor 1,34 -- deutlich ueber der Marge. Achsparallel gerechnet traegt dieselbe Waagerechte 0,715 und
        // schlaegt y = 0,699. Zwischen den beiden Faellen liegt nichts als der Yaw. Jede Platzierung, die ein
        // gutes Ergebnis vom Yaw abhaengig macht, ist eine Wette, und der Eigentuemer hat gesagt, dass hier nicht
        // gewettet wird: "Platzierungen duerfen nie fehlschlagen."
        //
        // Die Bedingung entspricht tan(pitch) > 1,15, also einem Blick steiler als 49 Grad. Der Preis ist, dass
        // flache Standplaetze fuer senkrecht ausgerichtete Bloecke verworfen werden und die Standplatzsuche einen
        // steileren finden muss. Das ist der richtige Preis: eine geparkte Zelle kostet Suchzeit, ein gekippter
        // Block kostet nach P5 den ganzen Bau.
        if (look.getAxis().isVertical()) {
            double waagerecht = Math.hypot(d[0], d[2]);
            if (waagerecht * AIM_DOMINANCE_MARGIN >= along) {
                return null;
            }
        }
        return new Vec3(p[0], p[1], p[2]);
    }

    /**
     * Every point on this support face worth casting a ray at, in world coordinates.
     *
     * <p>One method for both callers by construction. The stance search and the live click each used to walk
     * {@code aabbSideMultipliers} themselves, and a builder that decides where to stand by one rule and what to click
     * by another routes the bot to cells it then cannot place -- the failure mode this file has paid for more than once.
     */
    private static List<Vec3> aimPointsOnFace(Vec3 eye, BlockPos againstPos, AABB aabb, Direction against,
                                              BlockState desired) {
        Direction[] looks = requiredLookDirections(desired);
        if (looks == null) {
            List<Vec3> sampled = new ArrayList<>(5);
            for (Vec3 m : aabbSideMultipliers(against)) {
                sampled.add(new Vec3(
                        againstPos.getX() + aabb.minX * m.x + aabb.maxX * (1 - m.x),
                        againstPos.getY() + aabb.minY * m.y + aabb.maxY * (1 - m.y),
                        againstPos.getZ() + aabb.minZ * m.z + aabb.maxZ * (1 - m.z)));
            }
            return sampled;
        }
        // At most one point per candidate look, and usually one in total, because the opposite sign almost always aims
        // backwards and is dropped without a raycast. Six faces x 2 looks replaces six x 5 sampled points, and the
        // points it does return are the only ones that could have produced the wanted facing anyway.
        //
        // A37, REVERTED: "derive the aim from the ONE look the block family needs, not from both signs."
        //
        // The reasoning looked airtight. Two adjacent cells in the facings row, both sticky_piston/piston facing=west,
        // both placed by clicking the top face of the stone directly beneath -- 84,-59,67 succeeded at tick 470 with
        // rot=270.0 (EAST, correct, a piston faces away from the look) while 83,-59,67 had failed at tick 405 with
        // rot=90.0 (WEST, which yields facing=east). So the second entry looked like a sign error worth deleting.
        //
        // MEASURED, facings scenario, immediately: SUCCESS 96/96 -> STALLED 52/96. Forty-four cells lost, stanceless
        // 14 -> 69, walk payoff 67% -> 30%.
        //
        // Why it was wrong: these two entries are not "the right one and a bug". They are two GEOMETRIC DERIVATIONS,
        // and each is validated afterwards by possibleToPlace against vanilla's own simulation. The nominal look a
        // point was derived FOR is not the look the eye ends up having, because mostAlignedPointOnFace clamps the two
        // non-look axes -- so the point derived for the "wrong" sign regularly produces a correct facing anyway, and
        // that is load-bearing. The failed rot=90.0 attempt at tick 405 was therefore not a defect but a candidate
        // being REJECTED, which is the search doing its job; "wrong block would land" in the log is that rejection
        // working, not a bug reporting itself. Deleting the second derivation halved the aim search for the whole
        // upward-look family.
        //
        // So the eleven unnecessary helper blocks in that run remain UNEXPLAINED. What is now ruled out: the price
        // (a stance is chosen before A* is asked), the +-3 stance window (the owner's own working stance sits at
        // dx=-1,dz=+1, inside it), the intra-layer ordering (his click needs no neighbour -- only the stone beneath),
        // and this. Whatever comes next has to be measured before it is believed.
        Direction faceNormal = against.getOpposite();
        List<Vec3> derived = new ArrayList<>(looks.length);
        for (Direction look : looks) {
            Vec3 p = mostAlignedPointOnFace(eye, againstPos, aabb, faceNormal, look);
            if (p != null) {
                derived.add(p);
            }
        }
        return derived;
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        // One pass, wall-mounted blocks included and PREFERRED (see the rank below).
        //
        // This used to hold decorations back until the layer was otherwise finished, on the theory that their wall
        // would then be standing. The theory was half right and the practice was backwards: a sign in a one-block
        // gap is reachable only until the neighbouring block goes in, so waiting is precisely how it is lost. The
        // measurement said so plainly -- 68 of 71 deferrals in the last basalt run were one such sign.
        return searchForPlacables(bcc, desirableOnHotbar, false);
    }

    // ==========================================================================================================
    // DER ENCLOSURE-PLANER IST AUS DEM BAUPFAD RAUS, und das ist der teuerste einzelne Befund dieses Umbaus.
    //
    // GEMESSEN, Thread-Dump des basalt-Laufs 20260807-121852 (Render thread, zweimal im Abstand von 20 s an
    // derselben Stelle): EnclosureAwarePlanner.plan -> wouldStrand -> floodStandable, 105 von 110 Sekunden
    // Laufzeit in genau diesem Aufruf. Der Lauf setzte NULL Bloecke in 693 Sekunden und wurde ohne eine einzige
    // Sample-Zeile abgebrochen. Nicht langsam -- fest.
    //
    // Warum: plan() ist O(n^2) Flood-Fills. Die aeussere Schleife laeuft ueber alle verbliebenen Zellen, die
    // innere ruft fuer jede wouldStrand, und wouldStrand macht einen VOLLSTAENDIGEN Flood-Fill ueber die
    // Arbeitsbox plus einen Reichweitenwuerfel je anderer Zelle. Solange incorrectPositions auf 100 Zellen
    // gedeckelt war, waren das 10 000 Fluten und es fiel als "-7%" auf. S0b der Spezifikation verlangt aber die
    // GANZE Ebene: 936 Zellen auf y=-60, 1821 auf y=-59. Das sind 876 000 bzw. 3,3 Millionen Fluten fuer EINE
    // Ordnung, und sie wird pro Ebene neu gebraucht.
    //
    // Die Ordnung war ausserdem die DRITTE im Haus, neben der Distanzsortierung in assemble und dem
    // electedCell-Latch -- und konkurrierende Ordnungen sind genau das, was hier schon einmal eine Oszillation
    // erzeugt hat. Die Spezifikation kennt keinen Planer: S1 nimmt die naechstgelegene Zelle, und die Sicherheit
    // gegen eingemauerte Zellen liegt bei PARK und dem Waechter -- eine unerreichbar gewordene Zelle parkt mit
    // Grund C und wird geweckt, sobald sich die Welt aendert.
    //
    // Die Klasse und ihr Test bleiben im Baum. Der Algorithmus ist richtig, er ist nur in dieser Groessenordnung
    // nicht bezahlbar; wer ihn spaeter auf einen kleinen Ausschnitt anwenden will, findet ihn unveraendert vor.

    /**
     * Where a cell sits in the LOCAL placement order: 0 goes first, 1 follows. Never a licence to place nothing.
     *
     * <p>Was vom Planer BLEIBT, weil es gemessen etwas wert war und einen Kartenzugriff kostet statt einer Flut.
     * Ein haengender Block wird gegen eine bestimmte Seite geklickt, und die Zelle GEGENUEBER dieser Seite mauert
     * die einzige Linie zu ihr zu. Steht sie zuerst, ist der haengende Block fuer immer unbaubar -- und zwar zum
     * Preis einer vollstaendigen Standplatzsuche bei jedem Wiederholungsversuch. Auf basalt war das die Mehrheit
     * aller Aufschuebe eines Laufs: 68 von 71, jedes Mal so ein Schild.
     *
     * <p>Global war das eine Umsortierung des ganzen Ebenenplans. Lokal ist es dieselbe Aussage ohne Plan: hat
     * diese Zelle eine Stuetzseite, und ist die Zelle vor dieser Seite eine noch ungebaute Vorlagezelle, dann
     * schliesst sich hier gleich ein Fenster -- also zuerst.
     */
    private int plannedRank(int x, int y, int z, BlockState desired, BuilderCalculationContext bcc) {
        Direction support = requiredSupportDirection(desired);
        if (support == null) {
            return 1;   // nothing to seal: any face will do
        }
        Direction view = support.getOpposite();
        int bx = x + view.getStepX();
        int by = y + view.getStepY();
        int bz = z + view.getStepZ();
        BlockState blockerCurrent = bcc.bsi.get0(bx, by, bz);
        BlockState blockerDesired = bcc.getSchematic(bx, by, bz, blockerCurrent);
        if (blockerDesired == null || blockerDesired.isAir()
                || valid(blockerCurrent, blockerDesired, false)) {
            return 1;   // the sealing neighbour is either not wanted or already standing; the window is not closing
        }
        return 0;
    }


    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar,
                                                   boolean structuralOnly) {
        BetterBlockPos center = ctx.playerFeet();
        // Cells the committed route will walk through — never opportunistically place a NON-PASSABLE schematic
        // block (fence/wall/solid) into one of them, or the bot sprints straight into the fence it just set in
        // front of itself. Such cells build fine on a later pass once we are no longer about to walk over them.
        final Set<BetterBlockPos> pathCells = activePathCells();
        Optional<Placement> best = Optional.empty();
        int bestRank = Integer.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (buildInRows && !bcc.rowTemplatePlacementIsLicensedAt(x, y, z)) {
                        continue; // the near-player click pass obeys the same global Map-Art slice as target election
                    }
                    // Hoisted, not asked per note: this loop runs 11x7x11 times a tick, and a string built for every
                    // one of them would be an instrument that measures its own cost. Free when nothing is watched.
                    final boolean watched = CellWatch.isWatched(x, y, z);
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        if (watched) {
                            CellWatch.note(buildTick, x, y, z, "scan", "outside the current layer mask");
                        }
                        continue; // irrelevant
                    }
                    if (isSecondaryHalf(desired)) {
                        if (watched) {
                            CellWatch.note(buildTick, x, y, z, "scan", "secondary half, fills from its base cell");
                        }
                        continue; // a door-upper/bed-head fills itself from the base cell
                    }
                    if (structuralOnly && isWallMounted(desired)) {
                        if (watched) {
                            CellWatch.note(buildTick, x, y, z, "scan", "wall-mounted, held for the structural pass");
                        }
                        continue; // hang it once the walls are up -- see isWallMounted
                    }
                    // THE VERDICT NOTE, consulted before anything expensive happens. Six neighbour lookups against a
                    // stored mask, and if nothing around the cell has changed since the last full computation there is
                    // provably nothing new to find -- so the cell is skipped without a raycast, without a stance
                    // search, and without a penalty. The moment a neighbour lands the mask differs, the entry is
                    // dropped inside cellVerdictStillHolds, and the cell is a candidate again on that same tick.
                    if (cellVerdictStillHolds(x, y, z)) {
                        if (watched) {
                            CellWatch.note(buildTick, x, y, z, "scan",
                                    "negative verdict still valid -- no neighbour has changed since");
                        }
                        // Still declared as wanted material: a cell held back for want of a FACE may well be waiting
                        // on a neighbour whose own block has to be fetched to the hotbar first, and dropping the
                        // declaration here is how a build starves itself of the very item that would release it.
                        desirableOnHotbar.add(desired);
                        continue;
                    }
                    if (isCellParked(x, y, z)) {
                        if (watched) {
                            CellWatch.note(buildTick, x, y, z, "scan", "on deferral backoff or retired");
                        }
                        // Retry later -- but still declare the material as wanted. A cell deferred BECAUSE its item
                        // was not on the hotbar can only come back if something fetches that item, and skipping the
                        // whole cell here meant nothing ever did: deferred for want of a torch, never given a torch
                        // because it was deferred. The lighthouse sat in that loop with torches in its own pockets.
                        BlockState deferredCurrent = bcc.bsi.get0(x, y, z);
                        if (MovementHelper.isReplaceable(x, y, z, deferredCurrent, bcc.bsi)
                                && !valid(deferredCurrent, desired, false)) {
                            desirableOnHotbar.add(desired);
                            placementSkips[0]++;
                        }
                        continue;
                    }
                    // A40, REVERTED: skipping a cell WITHOUT a verdict while the bot stands in it. The observation was
                    // real -- facings run a4932f6b, tick 362, target 95,-59,67, pos=95.493,-59.000,67.503, all 145
                    // candidate stances reporting "would place nothing (... something (possibly the bot) is standing in
                    // it)" while the stone beneath had stood since tick 276. The stance search excludes the target as a
                    // STANCE already; what poisoned the candidates was the body, because simulatePlacement checks the
                    // REAL player while the search only moves a hypothetical eye (placementPlausible ignoreSelf).
                    //
                    // But removing the penalty removes the only thing that gets the bot OFF the cell: undeferred, the
                    // cell stays the nearest work, GoalAdjacent counts it as reached, nothing paths, the body stays.
                    // Measured: facings 96/96 -> 57/96. The fixpoint is documented at the allowSameLevel fallback.
                    // The real fix is an actual STEP OFF before evaluating, not the absence of a deferral.
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (watched && !(MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi)
                            && !valid(curr, desired, false))) {
                        CellWatch.note(buildTick, x, y, z, "scan", valid(curr, desired, false)
                                ? "already satisfied, nothing to do"
                                : "occupied by " + blockName(curr) + ", not replaceable");
                    }
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        // HEAD HEIGHT. The rule used to throw away the whole dy==1 plane inside the 11x11 window; it
                        // now throws away only the bot's OWN column, which is the part vanilla backs up.
                        //
                        // Where the old rule is right: in the bot's own column, dy==1 is its head, and vanilla
                        // refuses to materialise a block inside a player -- CollisionGetter.isUnobstructed passes a
                        // literal null as the "except" argument (MC 26.1.2, offset 18 aconst_null), so nobody is
                        // excluded and every player has blocksBuilding=true. Skipping there costs nothing and saves
                        // a placement that could not land.
                        //
                        // Where it was never justified: every other column. It came in unchanged with the Baritone
                        // import (git log -S finds no commit for it in this project), and its price is measured:
                        //   - the bot stands at y-1 of the target layer for 39.6% of that layer's ticks, and while
                        //     the layer above is unbuilt "air above it" is true for essentially every cell of it,
                        //   - so for 39.6% of the ticks the scan could offer NOTHING on the layer being built,
                        //   - and of 2128 PICK events in run 20260803-003717, 751 sat at dy=-1, 1377 at dy=0 and
                        //     exactly ZERO at dy=+1. The channel has never once been used.
                        // Meanwhile 487 of the 489 cells layer -59 ended up short were never clicked even once, in
                        // every run measured -- the layer fails at being OFFERED work, not at placing it.
                        //
                        // Everything admitted here still has to pass possibleToPlace, which asks vanilla with the
                        // real body. A candidate whose block would land inside the bot dies there, on a measurement,
                        // instead of dying here on a blanket rule.
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
                            if (dx == 0 && dz == 0) {
                                placementSkips[5]++;
                                if (watched) {
                                    CellWatch.note(buildTick, x, y, z, "scan",
                                            "at head height in the bot's own column");
                                }
                                continue;
                            }
                            if (wouldSealTheBotsOwnHeadHeightExit(center, x, y, z, bcc)) {
                                placementSkips[6]++;
                                if (watched) {
                                    CellWatch.note(buildTick, x, y, z, "scan",
                                            "at head height and would seal the bot's last horizontal exit");
                                }
                                continue;
                            }
                            headHeightCellsAdmitted++;
                            if (watched) {
                                CellWatch.note(buildTick, x, y, z, "scan",
                                        "at head height, admitted -- not the bot's own column");
                            }
                        }
                        if (blocksActivePath(pathCells, x, y, z, desired)) {
                            placementSkips[1]++;
                            if (watched) {
                                CellWatch.note(buildTick, x, y, z, "scan", "would build into the committed route");
                            }
                            continue; // don't build into our own walking path this pass
                        }
                        if (wouldSealPendingHangingNeighbour(x, y, z, bcc)) {
                            placementSkips[2]++;
                            if (watched) {
                                CellWatch.note(buildTick, x, y, z, "scan",
                                        "would seal a still-missing hanging neighbour");
                            }
                            continue; // hang the neighbour first -- see the method
                        }
                        desirableOnHotbar.add(desired);
                        // Take the SAFEST cell in reach, not the first one the scan happens to touch. The scan order
                        // is an artefact of three nested loops; the planner's order is the one that keeps the bot
                        // from sealing itself -- or a niche -- away from the cells it still has to reach.
                        // No special case for wall-mounted blocks, in EITHER direction. Both were tried and both
                        // were measured wrong: holding them until the layer was otherwise done loses the window in
                        // which a sign in a one-block gap is still reachable, and giving them priority made the bot
                        // chase cells it could not place -- idle seconds went from 4% to 47% and not one sign was
                        // placed for it. A real fix has to make the ORDER aware of line-of-sight to a support face,
                        // not bolt a preference on top -- which is exactly what plannedRank now is, and all it is.
                        int rank = plannedRank(x, y, z, desired, bcc);
                        // `best.isEmpty()` first, deliberately: without it a bare `rank < bestRank` refuses every
                        // cell once bestRank is already the lowest rank in play. The order is a PREFERENCE among
                        // placeable cells, never a licence to place nothing.
                        if (!(best.isEmpty() || rank < bestRank)) {
                            placementSkips[3]++;
                            if (watched) {
                                CellWatch.note(buildTick, x, y, z, "scan",
                                        "outranked: rank=" + rank + " loses to the best so far at " + bestRank);
                            }
                        }
                        if (best.isEmpty() || rank < bestRank) {
                            Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc);
                            if (opt.isEmpty()) {
                                placementSkips[4]++;
                                // possibleToPlace asks only about the stance the bot is standing in RIGHT NOW -- it
                                // does not search. So this is "not placeable from here", never "not placeable" --
                                // which is exactly what makes it worth REMEMBERING rather than re-deriving.
                                //
                                // ONLY BLAME A STANCE THE BOT ACTUALLY WENT TO. This used to record the current feet
                                // cell unconditionally, and the scan walks 11x7x11 cells EVERY TICK -- so every cell
                                // the bot merely passed through was written off as "tried and useless" for every
                                // target within five blocks of it, at ~847 verdicts per tick. The list is only cleared
                                // when some cell completes (see stanceFailures), so while the build is stuck it can
                                // only grow, and the good stances fall out of the candidate set one after another
                                // until placementStancesFor's first pass returns nothing and the SCAFFOLDING pass is
                                // all that is left. That is the mechanism that turns one failed attempt into fifty,
                                // and it is why helper blocks appear where the owner can place the block by hand from
                                // a plain stance.
                                //
                                // A stance is only evidence about itself when the bot was standing ON it on purpose.
                                // Walking past is not an attempt.
                                if (plannedPlacementStance != null && center.equals(plannedPlacementStance)) {
                                    noteStanceFailure(x, y, z, center);
                                }
                                if (watched) {
                                    CellWatch.note(buildTick, x, y, z, "scan",
                                            "not placeable from the current stance " + ctx.playerFeet());
                                }
                            }
                            if (opt.isPresent()) {
                                if (watched) {
                                    CellWatch.note(buildTick, x, y, z, "scan", "PLACEABLE from here, rank=" + rank);
                                }
                                bestRank = rank;
                                best = opt;
                                if (rank <= 0) {
                                    // <= 0, not == 0: a hanging block pulled ahead of the head of the order lands on
                                    // -1, and an exact test would walk the whole scan past the very cell it just
                                    // decided was most urgent.
                                    return best; // nothing can outrank the head of the order
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Would placing here wall off the one face a still-missing hanging block has to be clicked from?
     *
     * <p>The layer order already prefers the hanging block first, but an order is a preference: a cell the bot cannot
     * reach this second is skipped, and the neighbour goes in anyway. This is the hard half of the same rule, and it
     * is a rule about geometry, so it is exact. If the desired state at {@code C.relative(d)} must be clicked against
     * its {@code d}-side support, then the only sight line to that support runs through C -- so C is the seal.
     *
     * <p>Bounded, because a preference that never yields is a stall. If the hanging block has not gone in after
     * {@link #SEAL_HOLD_TICKS}, something other than the seal is wrong with it and the wall is worth more than the
     * sign; the hold is released and said out loud rather than left to look like a mystery pause.
     */
    private boolean wouldSealPendingHangingNeighbour(int x, int y, int z, BuilderCalculationContext bcc) {
        for (Direction d : Direction.values()) {
            int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
            BlockState hanging = bcc.getSchematic(nx, ny, nz, bcc.bsi.get0(nx, ny, nz));
            if (hanging == null || hanging.isAir() || requiredSupportDirection(hanging) != d) {
                continue;
            }
            BlockState there = bcc.bsi.get0(nx, ny, nz);
            if (!MovementHelper.isReplaceable(nx, ny, nz, there, bcc.bsi) || valid(there, hanging, false)) {
                continue; // already built, nothing left to seal
            }
            long key = positionKey(new BetterBlockPos(x, y, z));
            int held = sealHolds.merge(key, 1, Integer::sum);
            if (held <= SEAL_HOLD_TICKS) {
                return true;
            }
            if (held == SEAL_HOLD_TICKS + 1) {
                logMechanic("Placing " + x + "," + y + "," + z + " even though it seals "
                        + blockName(hanging) + " at " + nx + "," + ny + "," + nz
                        + " -- held it back for " + SEAL_HOLD_TICKS + " passes and the block never went in");
            }
            return false;
        }
        return false;
    }

    /** Feet positions of the REMAINING route from the current node forward (empty when idle) — used to avoid
     *  building a non-passable block into a step we are about to take. Only the remaining path is used: cells
     *  already walked past are no longer a step we can take, so as the bot advances along a 1-wide line those
     *  cells fall out of this set behind it and get built — the deferral can never livelock. */
    private Set<BetterBlockPos> activePathCells() {
        PathExecutor exec = princeps.getPathingBehavior().getCurrent();
        if (exec == null || exec.getPath() == null) {
            return java.util.Collections.emptySet();
        }
        List<BetterBlockPos> positions = exec.getPath().positions();
        int from = Math.max(0, Math.min(exec.getPosition(), positions.size()));
        if (from >= positions.size()) {
            return java.util.Collections.emptySet();
        }
        return new java.util.HashSet<>(positions.subList(from, positions.size()));
    }

    /** A normal placement must not close its current route. A recovery route owns no placement input while moving, so
     * yielding its target merely destroys the route to the stance that would make the placement possible. */
    static boolean placementTargetMustYieldToActivePath(boolean recovering, boolean blocksActivePath) {
        return !recovering && blocksActivePath;
    }

    /** True if placing the non-passable [desired] at (x,y,z) would block a step the route is about to take: either
     *  the cell itself is a path node (feet), or it sits at head height of a path node (the node below). */
    private boolean blocksActivePath(Set<BetterBlockPos> pathCells, int x, int y, int z, BlockState desired) {
        if (pathCells.isEmpty() || desired.isPathfindable(PathComputationType.LAND)) {
            return false; // no route, or a passable block that never blocks walking
        }
        return pathCells.contains(new BetterBlockPos(x, y, z))
                || pathCells.contains(new BetterBlockPos(x, y - 1, z));
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return placementPlausible(pos, state, false);
    }

    /**
     * @param ignoreSelf exclude the bot's own body from the obstruction test.
     *
     * <p>Two callers with opposite needs. The stance SEARCH asks "could this block go here from somewhere?" -- and
     * there the bot's current position is irrelevant, because it is about to walk away: counting itself made a bot
     * standing in a doorway veto all 47 stances from which it could have placed that door, forever. The PLACEMENT
     * path asks "can I click it right now?" -- and there the bot's body absolutely counts, because vanilla will
     * refuse a block that would materialise inside a player. Answering the second question with the first one's
     * rule made the builder commit to cells it could not place from where it stood, and wall throughput fell from
     * 132 to 37 blocks per minute.
     */
    public boolean placementPlausible(BlockPos pos, BlockState state, boolean ignoreSelf) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(ignoreSelf ? ctx.player() : null,
                voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BuilderCalculationContext bcc) {
        return possibleToPlace(toPlace, x, y, z, bcc, null, null, null);
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BuilderCalculationContext bcc,
                                                ExcavationRepairAim.Face requiredFace,
                                                java.util.function.Predicate<Placement> faceFilter) {
        return possibleToPlace(toPlace, x, y, z, bcc, requiredFace, faceFilter, null);
    }

    /** Optional failure counts from the actual click derivation, not a second geometry approximation. */
    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z,
                                               BuilderCalculationContext bcc, int[] rejected) {
        return possibleToPlace(toPlace, x, y, z, bcc, null, null, rejected);
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BuilderCalculationContext bcc,
                                                ExcavationRepairAim.Face requiredFace,
                                                java.util.function.Predicate<Placement> faceFilter, int[] rejected) {
        BlockStateInterface bsi = bcc.bsi;
        for (Direction against : supportDirectionsFor(toPlace)) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            if (requiredFace != null && (placeAgainstPos.asLong() != requiredFace.support()
                    || against.getOpposite() != requiredFace.side())) continue;
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                if (rejected != null) rejected[0]++;
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BetterBlockPos(x, y, z))) {
                if (rejected != null) rejected[1]++;
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                if (rejected != null) rejected[2]++;
                continue;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                if (rejected != null) rejected[3]++;
                continue;
            }
            AABB aabb = shape.bounds();
            Vec3 clickEye = RayTraceUtils.inferSneakingEyePosition(ctx.player());
            for (Vec3 aimPoint : aimPointsOnFace(clickEye, placeAgainstPos, aabb, against, toPlace)) {
                Rotation rot = RotationUtils.calcRotationFromVec3d(clickEye, aimPoint, ctx.playerRotations());
                Rotation actualRot = princeps.getLookBehavior().getAimProcessor().peekRotationExact(rot);
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot, x, y, z, bcc);
                    if (hotbar.isPresent()) {
                        Placement option = new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot,
                                positionKey(ctx.playerFeet()), new BetterBlockPos(x, y, z), toPlace);
                        if (faceFilter == null || faceFilter.test(option)) return Optional.of(option);
                    }
                    if (rejected != null) rejected[5]++;
                } else {
                    if (rejected != null) rejected[4]++;
                }
            }
        }
        return Optional.empty();
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot,
                                                 int x, int y, int z, BuilderCalculationContext bcc) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            BlockState wouldBePlaced = simulatePlacement(stack, (BlockHitResult) result, rot);
            if (placementResultAccepted(wouldBePlaced, desired, x, y, z, bcc)) {
                // accept "right block + right facing, only the open/delay/mode/note default differs" — place it now,
                // the interaction pass right-clicks it to the target state afterwards (valid() stays strict so the
                // build isn't 'done' until that toggle lands).
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /** One acceptance predicate shared by planning, stance selection, and the live click gate. Keeping these paths
     *  identical prevents another special block from being routeable by one simulation but rejected by another. */
    private boolean placementResultAccepted(BlockState result, BlockState desired, int x, int y, int z,
                                            BuilderCalculationContext bcc) {
        return result != null
                && (valid(result, desired, true)
                || isPendingChestPairHalf(result, desired, x, y, z, bcc)
                || interactionClicks(result, desired) >= 0);
    }

    /** A placement lock may hand ownership to chest-pair completion or the interaction pass as well as exact match. */
    private boolean placementSatisfiedOrHandedOff(BlockState current, BlockState desired, int x, int y, int z,
                                                  BuilderCalculationContext bcc) {
        return valid(current, desired, false)
                || isPendingChestPairHalf(current, desired, x, y, z, bcc)
                || interactionClicks(current, desired) >= 0;
    }

    /**
     * A double chest completes in two clicks: the FIRST placed half is a lone SINGLE chest, and only when the
     * SECOND half is placed against it does vanilla join both to LEFT/RIGHT. So a just-placed SINGLE chest whose
     * schematic wants a paired half (and whose partner cell is still to be built with the matching facing) is a
     * legitimate INTERMEDIATE, not a wrong placement — accept it so the builder proceeds to the partner instead of
     * breaking the first chest forever.
     */
    private boolean isPendingChestPairHalf(BlockState current, BlockState desired, int x, int y, int z, BuilderCalculationContext bcc) {
        if (desired == null || !(desired.getBlock() instanceof ChestBlock) || current.getBlock() != desired.getBlock()) {
            return false;
        }
        ChestType desiredType = desired.getValue(ChestBlock.TYPE);
        if (desiredType == ChestType.SINGLE
                || current.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                || current.getValue(ChestBlock.FACING) != desired.getValue(ChestBlock.FACING)) {
            return false;
        }
        BlockPos partnerPos = ChestBlock.getConnectedBlockPos(new BlockPos(x, y, z), desired);
        BlockState partnerCurrent = bcc.bsi.get0(partnerPos);
        BlockState partnerDesired = bcc.getSchematic(partnerPos.getX(), partnerPos.getY(), partnerPos.getZ(), partnerCurrent);
        return partnerDesired != null
                && partnerDesired.getBlock() == desired.getBlock()
                && partnerDesired.getValue(ChestBlock.FACING) == desired.getValue(ChestBlock.FACING)
                && partnerDesired.getValue(ChestBlock.TYPE) == desiredType.getOpposite()
                && MovementHelper.isReplaceable(partnerPos.getX(), partnerPos.getY(), partnerPos.getZ(), partnerCurrent, bcc.bsi);
    }

    /** Inventory-availability test: an item can BUILD a desired state iff it places the same BLOCK — the exact
     *  final state (slab half, stair facing, chest LEFT/RIGHT) is produced by the placement geometry, not by the
     *  item, so a `bottom` slab item legitimately supplies a `top` slab cell (fixed the 'have 0' false-missing). */
    /**
     * Can what this inventory slot places produce the desired block?
     *
     * <p>Block identity alone is too narrow: one item routinely places TWO blocks. A torch becomes {@code torch} on
     * the ground and {@code wall_torch} on a side; the same holds for every sign, banner, head and coral fan. The
     * slot's approximated state is the standing form, so a schematic asking for the wall form was judged
     * unbuildable and the whole build paused with "a required build material is missing" -- while the torches sat
     * in the bot's inventory. Comparing the placing ITEM covers that entire family in one rule; blocks with no item
     * form (fire, piston heads, redstone wire) still fall through to the identity check.
     */
    private static boolean itemCanPlaceBlock(BlockState available, BlockState desired) {
        if (available.getBlock() == desired.getBlock()) {
            return true;
        }
        net.minecraft.world.item.Item availableItem = available.getBlock().asItem();
        return availableItem != net.minecraft.world.item.Items.AIR
                && availableItem == desired.getBlock().asItem();
    }

    /**
     * The state vanilla would produce if [stack] were right-clicked with exactly [hit] while the player faces
     * [rot]; null when the stack is no BlockItem or the placement is impossible. This is the SAME prediction the
     * search phase trusts — the click gate re-runs it against the LIVE crosshair ray, because a prediction made
     * from the crouched eye and a click fired from the still-standing eye can land on different halves of a face
     * (the wrong-half slab loop seen live).
     */
    private BlockState simulatePlacement(ItemStack stack, BlockHitResult hit, Rotation rot) {
        return simulatePlacement(stack, hit, rot, null);
    }

    /**
     * @param bodyAt when non-null, the feet position the simulation should pretend the player occupies.
     *
     * <p>WHY THE BODY HAS TO MOVE FOR THE SEARCH. Vanilla decides the placement through
     * {@code BlockItem.getPlacementState}, which ends in {@code isUnobstructed} -- and that passes a literal null as
     * its "except" argument (26.1.2, offset 18 aconst_null), so NOBODY is excluded and the player's own body blocks
     * its own placement. There is no flag to pass; the only honest way to ask "could I place this if I stood there" is
     * to let the body be there while asking.
     *
     * <p>What it costs when the body is not moved, measured twice on two independent scenarios:
     *
     * <pre>
     *   oriented, run 5146df14:  the 7th cell (oak_door at 73,-60,67) never placed. The bot ended at
     *                            pos=73.397,-60.000,67.845 -- inside that very cell -- and reported
     *                            attempt=12 / failure #19 with "nothing in this layer can ever support it",
     *                            which is plainly false: the floor beneath it was already there.
     *   facings,  run a4932f6b:  target 95,-59,67, pos=95.493,-59.000,67.503, all 145 candidate stances
     *                            answering "would place nothing (... something (possibly the bot) is standing in it)"
     *                            while the stone beneath had stood since tick 276.
     * </pre>
     *
     * The verdict was never about the cell, it was about where the bot happened to stand -- and it repeated because
     * nothing changes between two identical questions asked from the same spot.
     *
     * <p>Only the STANCE SEARCH passes a body position. The three other callers ask about the here and now, where the
     * real body is exactly the right thing to test: predicting success for a click that vanilla will then refuse is
     * the failure mode this whole file is built around avoiding.
     *
     * <p>The rotation is already saved and restored the same way two lines below, which is the precedent this follows:
     * a simulation may borrow the player's state as long as it always gives it back, including on an exception.
     */
    private BlockState simulatePlacement(ItemStack stack, BlockHitResult hit, Rotation rot, Vec3 bodyAt) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
            return null;
        }
        float originalYaw = ctx.player().getYRot();
        float originalPitch = ctx.player().getXRot();
        float originalHeadYaw = ctx.player().getYHeadRot();
        Vec3 originalPos = bodyAt == null ? null : ctx.player().position();
        BlockState wouldBePlaced;
        BlockPlaceContext meme;
        // the state depends on the facing of the player sometimes — ALWAYS restore the visible rotation, even if
        // getStateForPlacement throws for some odd block, or the on-screen aim would be left stuck at the simulated
        // angle (harmless per call, but this runs many times per tick from the standing-position search).
        // THE ROTATION WE WRITE MUST BE THE ROTATION VANILLA READS. Without this, setYRot below was pointless: vanilla
        // reads the look through getViewYRot(1.0F), MixinClientPlayerEntity injects at its HEAD, and FlowCam returned
        // the rotation actually APPLIED this tick instead -- so every simulated stance was judged against wherever the
        // camera pointed. Proof from the full trace of run 526a0201: 195 of 219 distinct (yaw,pitch) pairs produced more
        // than one outcome, which is impossible for a pure function of look and hit point. See FlowCam.suspendView.
        FlowCam.suspendView();
        try {
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            // UND DEN KOPF, sonst wirkt die uebergebene Rotation nur ueber den Pitch.
            //
            // Vanilla leitet die Orientierung ueber getNearestLookingDirection -> getViewVector ->
            // LivingEntity.getViewYRot ab, und das liest yHeadRot -- nicht yRot. setYRot allein aendert also am
            // simulierten YAW gar nichts: die Simulation urteilte weiter mit dem Kopf, in den der Bot GERADE
            // schaut, und beantwortete damit eine andere Frage als die gestellte. Der Pitch ging durch
            // (getViewXRot liest xRot), die Waagerechte nicht -- deshalb halfen alle bisherigen
            // Rotations-Korrekturen nur halb.
            //
            // GEMESSEN, basalt 20260807-2249, Zelle 121,-59,115, gewollt sticky_piston[facing=up]: die
            // Server-Zeile bei der Ausfuehrung las yaw=-142,040 aber viewYRot=179,485. Mit 179,485 und dem Pitch
            // 43,485 gewinnt |z|=0,7255 gegen |y|=0,6882 -- north, also Kolben nach south, und south steht in der
            // Welt. Die Pruefung auf genau diese Mischung war zu dem Zeitpunkt schon eingebaut und hat trotzdem
            // ja gesagt: weil der uebergebene Yaw nirgends ankam.
            ctx.player().setYHeadRot(rot.getYaw());
            if (bodyAt != null) {
                // setPos and not moveTo: no rotation side effect, no collision resolution, no movement bookkeeping --
                // this is a question, not a step. Restored in the finally below together with the rotation.
                ctx.player().setPos(bodyAt.x, bodyAt.y, bodyAt.z);
            }
            meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    hit
            ) {}); // that {} gives us access to a protected constructor lmfao
            // Ask the ITEM, not its primary block. A sign item is a StandingAndWallBlockItem whose getBlock() is the
            // STANDING sign; the standing-or-wall decision lives in the item's own getPlacementState override. Going
            // through the block skipped that override entirely, so every wall sign, wall torch, wall banner and wall
            // skull simulated as its standing variant -- which never matches the schematic, so every stance was
            // rejected and the cell was deferred forever. No stance and no aim could have fixed it: the geometry was
            // right and the question was wrong.
            //
            // Through the shared helper, NOT a cast here: the cast only works when MixinBlockItem is registered, and
            // it was not -- so the version of this line that cast directly would have thrown ClassCastException on
            // the first sign it simulated. One call site, one implementation, one place to check.
            wouldBePlaced = BlockItemPlacementHelper.placementState((BlockItem) stack.getItem(), meme);
        } finally {
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            ctx.player().setYHeadRot(originalHeadYaw);
            if (originalPos != null) {
                ctx.player().setPos(originalPos.x, originalPos.y, originalPos.z);
            }
            // In the finally, and after the state is restored: the camera must never be left suspended, or the view
            // stops interpolating for the rest of the session and the 20 Hz stepping this mixin exists to remove comes
            // back -- a cosmetic bug that would be blamed on anything but a missing decrement.
            FlowCam.resumeView();
        }
        // Two very different failures, kept apart. Reporting both as "canPlace false" cost real debugging time: a
        // soul_sand cell that was plainly empty air reported "canPlace false", which says the target is occupied, and
        // sent the search for an obstruction that did not exist. In fact getPlacementState had returned null -- and
        // BlockItem.getPlacementState returns null when the state cannot SURVIVE there, or when the placed block's
        // collision box would intersect an entity, which includes the bot's own body. Those are three distinguishable
        // situations and a diagnostic that merges them describes none of them.
        lastSimulationRefusal = wouldBePlaced == null
                ? (desiredCannotSurviveOrIsObstructed(stack, meme)
                        ? "the block cannot survive there, or something (possibly the bot) is standing in it"
                        : "vanilla refused the placement outright")
                : (!meme.canPlace() ? "the target cell is not replaceable" : null);
        if (wouldBePlaced == null || !meme.canPlace()) {
            return null;
        }
        return wouldBePlaced;
    }

    /** Would pressing right-click RIGHT NOW produce the desired state? Simulated against the LIVE crosshair ray —
     *  the very ray BlockPlaceHelper will click with — using the currently selected stack and live rotation, so
     *  prediction and execution provably agree (closes the crouched-eye vs standing-eye half divergence). */
    /**
     * Separates "this state cannot exist here" from "vanilla said no for some other reason".
     *
     * <p>{@code BlockItem.getPlacementState} folds two checks into one null: {@code canSurvive} and
     * {@code isUnobstructed}. The second one is the surprising one, because it tests the placed block's collision box
     * against ENTITIES -- so the bot standing in the cell it is trying to fill refuses its own placement, silently and
     * indistinguishably from a support problem.
     */
    private boolean desiredCannotSurviveOrIsObstructed(ItemStack stack, BlockPlaceContext ctxToTest) {
        if (!(stack.getItem() instanceof BlockItem item)) {
            return false;
        }
        BlockState raw = item.getBlock().getStateForPlacement(ctxToTest);
        if (raw == null) {
            return false; // the block itself declined; not a survive/obstruction matter
        }
        BlockPos at = ctxToTest.getClickedPos();
        return !raw.canSurvive(ctx.world(), at)
                || !ctx.world().isUnobstructed(raw, at, net.minecraft.world.phys.shapes.CollisionContext.of(ctx.player()));
    }

    /**
     * TRIED, UNPROVEN, AND UNWIRED -- kept only so the reasoning is not repeated.
     *
     * <p>The theory: vanilla decides a look-derived facing by which axis the view vector points along most, a boundary
     * with no tolerance at all, so an aim point sitting on it makes the placement a coin toss. Requiring the point to
     * survive a few degrees of nudge should therefore have removed the wrongly-oriented pistons.
     *
     * <p>The world audit says otherwise: ~1.4% of placed sticky pistons faced the wrong way with this in place, the
     * same rate as without it, and the same rate again after a second, independent aim fix. Two client-side theories,
     * no movement in the number.
     *
     * <p>And it was not cheap. It runs four extra vanilla placement simulations for every accepted aim point, on the
     * hottest path in the builder -- the stance search is already up to 24 stances x 6 faces x 5 points -- and it fires
     * for every piston, observer, dropper, repeater and door, which in etz-basalt is thousands of cells. The owner
     * watching the bot stand and visibly think was watching this, among other things. An unproven check that expensive
     * has no business on that path.
     */
    @SuppressWarnings("unused")
    private boolean placementSurvivesAimJitter(ItemStack stack, BlockHitResult hit, Rotation rot, BlockState desired,
                                               int x, int y, int z, BuilderCalculationContext bcc) {
        if (desired == null || !desired.hasProperty(BlockStateProperties.FACING)
                && !desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return true;
        }
        final float margin = 3.0F;
        for (int i = 0; i < 4; i++) {
            float dy = i == 0 ? margin : i == 1 ? -margin : 0.0F;
            float dp = i == 2 ? margin : i == 3 ? -margin : 0.0F;
            BlockState nudged = simulatePlacement(stack, hit, new Rotation(rot.getYaw() + dy, rot.getPitch() + dp));
            if (!placementResultAccepted(nudged, desired, x, y, z, bcc)) {
                return false;
            }
        }
        return true;
    }

    private boolean liveRayWouldPlaceDesired(Placement placement, BuilderCalculationContext bcc) {
        HitResult mouseOver = ctx.objectMouseOver();
        if (mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockHitResult liveHit = (BlockHitResult) mouseOver;
        if (!liveHit.getBlockPos().equals(placement.placeAgainst)
                || liveHit.getDirection() != placement.side
                || !liveHit.getBlockPos().relative(liveHit.getDirection()).equals(placement.target)) {
            return false;
        }
        ItemStack held = ctx.player().getInventory().getNonEquipmentItems().get(placement.hotbarSelection);
        // GEGEN DIE ROTATION DES SERVERS SIMULIEREN, NICHT GEGEN DIE EIGENE. Das ist der ganze Fix, und er ist
        // gemessen, nicht vermutet.
        //
        // Der Trefferpunkt (liveHit) ist richtig so, wie er hier steht: GENAU dieser geht im
        // ServerboundUseItemOnPacket raus, der Server uebernimmt ihn. Die ORIENTIERUNG dagegen leitet vanilla aus
        // getNearestLookingDirection() ab, also aus dem Blickvektor der Entitaet auf der SERVERSEITE -- und im
        // Paket steht keine Rotation. Der Server nimmt sie aus dem zuletzt EMPFANGENEN Bewegungspaket, und das
        // Klickpaket verlaesst den Client davor (belegt am Bytecode: Princeps' Tick-Einstieg liegt in
        // Minecraft.tick bei Quellzeile 1942, sendPosition() erst bei 1973).
        //
        // Solange der Blick steht, ist das folgenlos -- alt gleich neu. Bewegt er sich im Klick-Tick noch, ist es
        // das nicht: basalt-Lauf 20260807-160142, 1076 Klicks von beiden Seiten mitgeschrieben, 234 Abweichungen,
        // bei ACHT davon kippte die abgeleitete Richtung. Spitzenwert 70 Grad Yaw und 33,9 Grad Pitch.
        //
        // Client-Trefferpunkt PLUS Server-Rotation ist damit genau das Modell dessen, was der Server ausrechnen
        // wird. Faellt die Pruefung durch, haelt die Orientierungs-Sperre den Klick einen Tick zurueck, bis die
        // richtige Rotation gesendet WURDE. Deterministisch statt wahrscheinlich.
        Rotation asTheServerSeesIt = princeps.getLookBehavior().getRotationTheServerHas()
                .orElseGet(ctx::playerRotations);
        // BEIDE MUESSEN STIMMEN, dann muss auf nichts gewartet werden.
        //
        // Der Server rechnet die Orientierung aus einer MISCHUNG: Kopf-Yaw (eine Sendung alt, weil yHeadRot erst
        // im Entity-Tick nachgezogen wird) und aktuellem Pitch. Gemessen an der Server-Zeile im Moment der
        // Ausfuehrung: yaw=127,616 pitch=43,717 viewYRot=85,819 viewXRot=43,717.
        //
        // Der erste Anlauf hat stattdessen GEWARTET, bis sich nichts mehr bewegt -- korrekt, aber teuer: basalt
        // fiel von 2740 auf 1023 gesetzte Zellen und von 84,1 auf 31,4 Bloecke pro Minute. Der Bot stand richtig,
        // aber er stand.
        //
        // Verlangt man dagegen, dass die Platzierung unter BEIDEN Rotationen den gewollten Block erzeugt -- unter
        // der zuletzt gesendeten UND unter der Mischung --, dann ist es gleichgueltig, welche der Server
        // heranzieht. Geklickt wird, sobald das ERGEBNIS eindeutig ist, nicht wenn die Rotation stillsteht. Das
        // kostet keinen Tick Wartezeit und verwirft nur die Momente, in denen die beiden auseinanderfallen.
        // ALLE JUNGEN KANDIDATEN, nicht nur einer -- weil der Verzug nicht fest bei eins liegt.
        //
        // Die Ein-Schritt-Mischung war nachweislich richtig: fuenf Stichproben aus dem basalt-Lauf 20260807-2308,
        // Vorhersage gegen Server-Zeile, jedes Mal auf drei Nachkommastellen identisch (102,-60,113
        // mixYaw=134,868 gegen viewYRot=134,868; ...,114 123,516 gegen 123,516; ...,115 108,236 gegen 108,236).
        // Trotzdem fiel Zelle 114,-59,74 nach 1975 gesetzten Zellen um: dort hatte der Client denselben Yaw
        // zweimal gesendet, die Mischung war also gleich der aktuellen Rotation, die Pruefung fand keinen
        // Widerspruch -- und mit dieser Rotation (129,268 / 41,694) waere das Ergebnis auch richtig gewesen,
        // senkrechte Komponente 0,665 gegen 0,578 waagerecht, also down, also Kolben nach up. East steht in der
        // Welt. Der Server hat einen Yaw benutzt, der NOCH WEITER zurueckliegt als eine Sendung.
        //
        // Die Verzugstiefe zu erraten waere Glueckssache, auf Ruhe zu warten kostete 84,1 -> 31,4 Bloecke pro
        // Minute. Beides ist unnoetig: geprueft werden einfach alle jungen Kandidaten, und geklickt wird nur,
        // wenn die Platzierung unter JEDEM von ihnen den gewollten Block ergibt. Bei ruhigem Zielen liegen sie
        // ohnehin dicht beieinander und die Pruefung kostet nichts; nur wenn die juengste Blickgeschichte eine
        // Richtungsgrenze ueberquert, haelt die Orientierungs-Sperre den Klick einen Tick zurueck -- und genau
        // dann ist das Ergebnis auch nicht vorhersagbar.
        for (Rotation candidate : princeps.getLookBehavior().getRotationsTheServerMightUse()) {
            if (candidate.equals(asTheServerSeesIt)) {
                continue; // wird unten ohnehin geprueft
            }
            BlockState underThatHead = simulatePlacement(held, liveHit, candidate);
            if (!placementResultAccepted(underThatHead, placement.desired,
                    placement.target.getX(), placement.target.getY(), placement.target.getZ(), bcc)) {
                return false;
            }
        }
        // UND DIE ROTATION MUSS STEHEN. Der Server wertet den Klick nicht beim Eintreffen aus, sondern bei der
        // Ausfuehrung auf dem Server-Thread, und wendet dazwischen die Bewegungspakete an, die mit in der
        // Warteschlange liegen. Bewegt sich der Blick noch, entsteht der Blockzustand also aus einer Rotation,
        // die hier niemand geprueft hat -- belegt mit Threadnamen an handleUseItemOn und an der Zelle 77,-59,75
        // durchgerechnet (siehe ILookBehavior.rotationHasSettled). Steht der Blick, sind Eintreffen und
        // Ausfuehrung dieselbe Rotation, und die Orientierung ist vorhersagbar.
        //
        // Nur fuer Bloecke, deren Richtung ueberhaupt zaehlt: fuer einen Stein waere das ein Tick Wartezeit
        // ohne jeden Gegenwert.
        // KEINE RUHE-BEDINGUNG MEHR an dieser Stelle. Sie stand hier und hat funktioniert -- null
        // Fehlorientierungen -- aber sie hat den Bau halbiert: 2740 auf 1023 Zellen, 84,1 auf 31,4 Bloecke pro
        // Minute, weil auf den schweren Ebenen staendig auf einen stillstehenden Blick gewartet wurde. Die
        // Mischungspruefung oben leistet dasselbe ohne Wartezeit; siehe dort.
        BlockState simulated = simulatePlacement(held, liveHit, asTheServerSeesIt);
        return placementResultAccepted(simulated, placement.desired,
                placement.target.getX(), placement.target.getY(), placement.target.getZ(), bcc);
    }

    /** Die Eigenschaften des Sollzustands als {@code [a=1,b=2]}, oder leer, wenn er keine hat. Nur fuer die
     *  LAND-Zeile: {@code blockName} allein verschweigt genau die Eigenschaft, um die es beim Abbruch geht. */
    private static String desiredPropsForTrace(BlockState desired) {
        if (desired == null || desired.getProperties().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        for (net.minecraft.world.level.block.state.properties.Property<?> p : desired.getProperties()) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(p.getName()).append('=').append(desired.getValue(p));
        }
        return sb.append(']').toString();
    }

    /** Reconcile last tick's requested placement with the execution helper. A request rejected by click cooldown,
     *  busy hands, survival consumption, or the controller is not a press and must not advance the re-press guard. */
    private void observePendingPlacementRequest() {
        BlockPlaceHelper helper = princeps.getInputOverrideHandler().getBlockPlaceHelper();
        long serial = helper.getSuccessfulBlockInteractions();
        if (pendingPlacementRequest != null) {
            BlockPlaceHelper.SuccessfulBlockInteraction ack = helper.getLastSuccessfulBlockInteraction();
            boolean hasNewAck = ack != null
                    && ack.getSerial() > pendingPlacementRequestSerial
                    && ack.getSerial() > observedBlockClickSerial;
            boolean matchesRequest = hasNewAck && pendingPlacementItem != null
                    && ack.matchesMainHandPlacement(
                    pendingPlacementRequest.placeAgainst,
                    pendingPlacementRequest.side,
                    pendingPlacementRequest.target,
                    pendingPlacementRequest.hotbarSelection,
                    pendingPlacementItem
            );
            if (matchesRequest) {
                rememberPlacement(pendingPlacementRequest);
                resetUnacknowledgedPlacementRequests();
                noProgressTicks = 0;
            } else if (hasNewAck) {
                long targetKey = positionKey(pendingPlacementRequest.target);
                if (placementTargetLock.owns(targetKey)) {
                    placementTargetLock.startRecovery(pendingPlacementRequest.stanceKey);
                }
                resetUnacknowledgedPlacementRequests();
                logMechanic("Controller acknowledged a different block-use than the committed placement at "
                        + pendingPlacementRequest.target.getX() + "," + pendingPlacementRequest.target.getY() + ","
                        + pendingPlacementRequest.target.getZ() + "; retrying that cell from another stance");
            } else if (!pendingPlacementRequestThrottled) {
                recordUnacknowledgedPlacementRequest(pendingPlacementRequest);
            }
            pendingPlacementRequest = null;
            pendingPlacementItem = null;
            pendingPlacementRequestThrottled = false;
        }
        observedBlockClickSerial = Math.max(observedBlockClickSerial, serial);
    }

    private void recordUnacknowledgedPlacementRequest(Placement placement) {
        long cellKey = positionKey(placement.target);
        if (unacknowledgedPlacementCellKey != cellKey
                || unacknowledgedPlacementStanceKey != placement.stanceKey) {
            unacknowledgedPlacementCellKey = cellKey;
            unacknowledgedPlacementStanceKey = placement.stanceKey;
            unacknowledgedPlacementRequests = 1;
        } else {
            unacknowledgedPlacementRequests++;
        }
        if (unacknowledgedPlacementRequests < PLACE_REQUEST_NO_ACK_RECOVER_AT) {
            return;
        }
        if (placementTargetLock.owns(cellKey) && !placementTargetLock.isRecovering()) {
            placementTargetLock.startRecovery(placement.stanceKey);
        }
        logMechanic("Placement controller did not accept " + unacknowledgedPlacementRequests
                + " valid requests at " + placement.target.getX() + "," + placement.target.getY() + ","
                + placement.target.getZ() + "; keeping the cell and trying another stance");
        resetUnacknowledgedPlacementRequests();
    }

    private void resetUnacknowledgedPlacementRequests() {
        unacknowledgedPlacementCellKey = Long.MIN_VALUE;
        unacknowledgedPlacementStanceKey = Long.MIN_VALUE;
        unacknowledgedPlacementRequests = 0;
    }

    /**
     * Bounds a break branch that otherwise preempts every placement/recovery path forever. The deadline scales with
     * vanilla destroy speed, so legitimate slow blocks get ample time while protected/unbreakable blocks yield.
     */
    private boolean breakMadeNoProgress(BetterBlockPos target, BlockState current) {
        if (!target.equals(observedBreakTarget) || current.getBlock() != observedBreakBlock) {
            observedBreakTarget = new BetterBlockPos(target);
            observedBreakBlock = current.getBlock();
            breakNoProgressTicks = 0;
            return false;
        }
        breakNoProgressTicks++;
        float destroyProgress = current.getDestroyProgress(ctx.player(), ctx.world(), target);
        int expectedTicks = destroyProgress > 0.0F
                ? (int) Math.ceil(1.0D / destroyProgress) : BREAK_STALL_MIN_TICKS;
        long scaledDeadline = (long) expectedTicks * 3L + 40L;
        int deadline = (int) Math.min(Integer.MAX_VALUE,
                Math.max(BREAK_STALL_MIN_TICKS, scaledDeadline));
        return breakNoProgressTicks >= deadline;
    }

    private void resetBreakProgressTracking() {
        observedBreakTarget = null;
        observedBreakBlock = null;
        breakNoProgressTicks = 0;
    }

    private void rememberPlacement(Placement placement) {
        long hash = positionKey(placement.target);
        // Re-press guard: a cell that actually took our block drops off the target list, so pressing the SAME cell
        // again means the previous press never stuck. detectWrongPlacementResult only reacts once a WRONG block
        // lands; if the placement silently never lands at all (server rejection / protection) that check keeps
        // waiting forever. Counting presses closes that last loop shape.
        if (repressCellTracked && repressCellHash == hash) {
            if (++repressCount >= PLACE_REPRESS_RECOVER_AT) {
                // P5, zweite Gestalt: die Platzierung ist nicht falsch gelandet, sie ist GAR NICHT gelandet. Acht
                // vom Controller BESTAETIGTE Klicks auf dieselbe Zelle, und die Zelle ist immer noch offen -- das
                // ist keine Wartezeit und keine Uhr (gezaehlt werden Ereignisse, nicht Ticks), sondern eine Aussage
                // ueber die Welt: hier laesst sich nicht bauen, obwohl Simulation, Standposition und Weg alle ja
                // gesagt haben. Frueher wurde daraus eine andere Standposition; nach der Spezifikation ist es
                // derselbe Abbruch wie ein falsch gelandeter Block, denn falsch ist dieselbe Annahme.
                List<String> report = new ArrayList<>();
                report.add("Cell " + placement.target.getX() + "," + placement.target.getY() + ","
                        + placement.target.getZ() + " took " + repressCount
                        + " acknowledged clicks and never changed.");
                report.add("wanted: " + blockName(placement.desired));
                report.add("layer " + layer + ", " + (incorrectPositions == null ? 0 : incorrectPositions.size())
                        + " cell(s) still open, " + parkedCells.size() + " parked");
                report.add("The server accepted every click and the world did not move. The build stops here.");
                abortBuild(Ending.PLACEMENT_FAILED, "Build failed: placement never landed at "
                        + placement.target.getX() + "," + placement.target.getY() + ","
                        + placement.target.getZ(), report);
                repressCellHash = -1;
                repressCellTracked = false;
                repressCount = 0;
                lastPlacedCellHash = -1;
                lastPlacedCell = null;
                lastPlacedDesired = null;
                return;
            }
        } else {
            repressCellHash = hash;
            repressCellTracked = true;
            repressCount = 1;
        }
        lastPlacedCell = placement.target;
        lastPlacedDesired = placement.desired;
        lastPlacedCellHash = hash;
        lastPlacedCellTick = buildTick;
        if (placementTargetLock.owns(hash)) {
            placementTargetLock.markAimReady(hash);
        }
    }

    private void trackGateBlocked(Placement placement) {
        long hash = positionKey(placement.target);
        if (isOrdinaryFullBlock(placement)) {
            if (ordinaryGateBlockedCellKey != hash) {
                ordinaryGateBlockedCellKey = hash;
                ordinaryGateBlockedTicks = 0;
            }
            if (++ordinaryGateBlockedTicks >= ORDINARY_GATE_YIELD_TICKS) {
                yieldOrRecoverOrdinaryCube(placement);
            }
            return;
        }
        resetOrdinaryGateTracking();
        if (placementTargetLock.owns(hash)
                && placementTargetLock.markAimBlocked(hash, placement.stanceKey)) {
            logMechanic("Aim gate stayed blocked at " + placement.target.getX() + ","
                    + placement.target.getY() + "," + placement.target.getZ()
                    + "; keeping the target and trying another stance");
        }
    }

    private void resetOrdinaryGateTracking() {
        ordinaryGateBlockedCellKey = Long.MIN_VALUE;
        ordinaryGateBlockedTicks = 0;
    }

    /**
     * Ordinary full cubes (glass, ice, stone...) have no placement-controlled state. They still pass the exact live
     * support/face/target simulation before a click, but an aligned transient mismatch must not send them through
     * the expensive orientation stance oracle. Yield briefly, keep the cell unresolved, and let GoalAdjacent flow.
     */
    private boolean isOrdinaryFullBlock(Placement placement) {
        return isOrdinaryFullBlock(placement.desired, placement.target);
    }

    private boolean isOrdinaryFullBlock(BlockState state, BlockPos pos) {
        return !shouldUseStanceRecovery(state, state.isCollisionShapeFullBlock(ctx.world(), pos));
    }

    static boolean shouldUseStanceRecovery(BlockState state, boolean fullCollisionCube) {
        return !fullCollisionCube || placementStateIsGeometrySensitive(state);
    }

    public static boolean placementStateIsGeometrySensitive(BlockState state) {
        if (state == null) {
            return true;
        }
        for (Property<?> property : state.getProperties()) {
            if (!AUTO_RESOLVED_PROP_NAMES.contains(property.getName())) {
                return true;
            }
        }
        return false;
    }

    private void detectWrongPlacementResult(BuilderCalculationContext bcc) {
        if (lastPlacedCell == null || lastPlacedDesired == null) {
            return;
        }
        BlockState now = bcc.bsi.get0(lastPlacedCell.getX(), lastPlacedCell.getY(), lastPlacedCell.getZ());
        if (MovementHelper.isReplaceable(lastPlacedCell.getX(), lastPlacedCell.getY(), lastPlacedCell.getZ(), now, bcc.bsi)) {
            return; // nothing landed yet (still air) — keep waiting; the next placement press overwrites this slot
        }
        lastPlacedCellHash = -1;
        BlockPos cell = lastPlacedCell;
        lastPlacedCell = null;
        BlockState desired = lastPlacedDesired;
        lastPlacedDesired = null;
        // LAND is the other half of CLICK. Together they read as one sentence: intended this angle, sent that angle,
        // wanted this state, got that one -- which is the whole of a misorientation on a single pair of lines.
        boolean landedCorrect = valid(now, desired, false);
        boolean acceptable = landedCorrect
                || isPendingChestPairHalf(now, desired, cell.getX(), cell.getY(), cell.getZ(), bcc)
                || isPendingSecondaryHalf(desired, cell.getX(), cell.getY(), cell.getZ(), bcc)
                || interactionClicks(now, desired) >= 0;
        // MESSFALLE, hier geschlossen: landedWrong stand UEBER der Ausnahmepruefung und zaehlte damit jede
        // Truhenhaelfte, jede Tuer-/Bett-Zweithaelfte und jeden Block, dem nur noch sein Rechtsklick fehlt, als
        // Fehlschlag -- obwohl der Klick in allen drei Faellen genau das getan hat, was er sollte. Solange
        // clicksSent gar keinen Schreiber hatte, war placementQuality() ohnehin unlesbar; seit S8 mitzaehlt ist
        // landedRight/clicksSent die Kennzahl, an der der Owner diesen Builder misst.
        //
        // Die LAND-Trace-Zeile behaelt absichtlich landedCorrect: sie beantwortet die andere Frage ("war es EXAKT
        // der gewollte Zustand"), und die bleibt fuer die Fehlersuche an Ausrichtungen die interessantere.
        if (acceptable) {
            landedRight++;
        } else {
            landedWrong++;
        }
        BuildTrace.cell(buildTick, "LAND", cell.getX(), cell.getY(), cell.getZ(),
                // DAS SOLL ALS GANZER ZUSTAND, nicht nur als Blockname. Zwei Laeufe endeten an derselben Zeile --
                // "want=sticky_piston got=[extended=false,facing=north]" -- und die sagt nicht, ob up gewollt war
                // (dann ist es die Pitch-Familie: bei rot=...,42.6 ist die waagerechte Komponente 0,736 gegen
                // 0,677 senkrecht, ein Kolben KANN da nicht nach oben zeigen) oder south (dann ist es eine
                // Vertauschung um 180 Grad und eine ganz andere Ursache). Ohne das Soll ist die Zeile, die den
                // Bau abbricht, genau an der Stelle stumm, an der sie sprechen muesste.
                "want=" + blockName(desired) + desiredPropsForTrace(desired)
                        + " got=" + now + " match=" + (landedCorrect ? "yes" : "no")
                        + (acceptable && !landedCorrect ? " (accepted: half or toggle still pending)" : ""));
        if (acceptable) {
            return; // landed correctly, a half still forming, or the right block awaiting only its right-click toggle
        }
        // P5. DIES IST DER HARTE ABBRUCH DER SPEZIFIKATION, und der Owner hat begruendet, warum er hart sein muss:
        // "An dieser Stelle war die Platzierung simuliert, geplant und der Weg gefahren. Schlaegt sie trotzdem fehl,
        // ist eine Annahme des Algorithmus falsch. Das soll sichtbar werden statt weggeparkt zu bleiben."
        //
        // Was hier ERSATZLOS entfaellt, ist placeFailCounts mit PLACE_FAIL_RECOVER_AT=3: dreimal denselben falschen
        // Block setzen, ihn zweimal wieder wegschlagen und es aus einer anderen Standposition erneut versuchen. Das
        // hat den Bau nie repariert, es hat den Defekt bezahlt -- jeder Durchlauf kostete einen Abbau, eine
        // Neuplanung und eine Fahrt, und am Ende stand die Zelle trotzdem falsch oder gar nicht. Genau das ist die
        // Sorte Selbstheilung, die einen Bau stundenlang beschaeftigt aussehen laesst, ohne dass er vorankommt.
        //
        // Die drei Ausnahmen darueber sind KEINE Aufweichung: eine Truhenhaelfte, eine Tuer-/Bett-Zweithaelfte und
        // ein Block, dem nur noch sein Rechtsklick fehlt, sind keine fehlgeschlagenen Platzierungen, sondern
        // Zwischenzustaende, die dieser Beobachter sonst falsch liest.
        List<String> report = new ArrayList<>();
        report.add("Cell " + cell.getX() + "," + cell.getY() + "," + cell.getZ()
                + " was simulated, planned and walked to -- and the click produced the wrong block.");
        // Der VOLLE Zustand, nicht nur der Blockname. Der erste scharfe P5-Abbruch ueberhaupt meldete
        // "wanted: sticky_piston / got: sticky_piston[extended=false,facing=east]" -- die beiden Zeilen sahen
        // gleich aus und die eigentliche Aussage (welche Richtung war gewollt?) fehlte genau dort, wo sie
        // hingehoert. Eine Fehlorientierung ist der haeufigste P5-Fall; sie muss aus dem Report ablesbar sein.
        report.add("wanted: " + desired);
        report.add("got:    " + now);
        report.add("layer " + layer + ", " + (incorrectPositions == null ? 0 : incorrectPositions.size())
                + " cell(s) still open, " + parkedCells.size() + " parked");
        report.add("An assumption of the algorithm is wrong here. The build stops rather than hiding it.");
        abortBuild(Ending.PLACEMENT_FAILED, "Build failed: wrong block at " + cell.getX() + "," + cell.getY()
                + "," + cell.getZ(), report);
    }

    /** Temporarily yield one unresolved cell so other cells can advance. Unlike the former strike/skip path this
     *  never removes the cell from incorrectPositions or completion verification; it is retried after a bounded
     *  cooldown, with exponential spacing for genuinely server-rejected cells. */
    /**
     * Defer a cell and say, in the same line, what is actually wrong with it.
     *
     * <p>The bare {@link #deferCell(BlockPos, String)} reason describes the WATCHDOG that fired ("route made no
     * observable progress") -- which is a symptom every stuck cell shares. {@link #diagnoseCell} names the cause
     * (MATERIAL / SUPPORT / STANCE / DERIVATION / ...), and it was only ever reachable from a message that a
     * deferral itself suppresses: the deferral resets the no-progress counter before the 120-tick diagnostic can
     * print. So a stalled build produced a log full of symptoms and not one cause.
     */
    private void deferCell(BlockPos cell, String reason, BuilderCalculationContext bcc) {
        String detail = reason;
        if (bcc != null) {
            try {
                detail = reason + "; " + diagnoseCell(new BetterBlockPos(cell), bcc);
            } catch (RuntimeException ignored) {
                // A diagnosis is a nicety; never let it take down the build it is describing.
            }
        }
        deferCell(cell, detail);
    }

    /**
     * Defer a cell that failed for a STRUCTURAL reason -- the world, as it stands, offers nowhere to place it from.
     *
     * <p>Counts several strikes at once rather than one, so such a cell reaches retirement in two attempts instead of
     * six. "No standable, occlusion-safe stance exists" is not a transient miss like a blocked route or a busy
     * inventory; it is a statement about the world, and re-asking costs a full walk across the build to be told the
     * same thing. But it is not permanent either -- the support it needs may be a neighbour that is itself still
     * queued -- which is why this is a weight and not an immediate retirement. See {@link #STRUCTURAL_DEFERRAL_WEIGHT}
     * for what each extreme cost when it was measured.
     *
     * <p>That is not a small cost. The facings scenario spent 51 deferrals and 43 stance changes on ten cells that
     * could never have worked: every down-facing piston needs something ABOVE it to click while looking up, and in a
     * bottom-up build the course above does not exist yet. Ten cells, sixty pointless walks -- which is exactly the
     * back-and-forth the owner watched on the basalt farm.
     *
     * <p>Retiring immediately does not abandon them. The retry sweep revisits every retired cell once the rest of the
     * build stands, which is precisely when the course above them exists and the placement becomes possible.
     */
    private void deferCellStructural(BlockPos cell, String reason, BuilderCalculationContext bcc) {
        deferCell(cell, reason, bcc, STRUCTURAL_DEFERRAL_WEIGHT);
    }

    /**
     * Could the support this cell is missing still turn up while the builder is on THIS layer?
     *
     * <p>This is the question that separates the two ways a cell can have nothing to click against, and getting it
     * wrong in either direction was expensive. Treat every such cell as merely unlucky and ten hopeless ones cost
     * sixty pointless walks. Treat them all as hopeless and etz-basalt loses 1,700 blocks, because there the missing
     * neighbour is nearly always another cell in the same layer that simply has not had its turn yet.
     *
     * <p>{@code bcc.getSchematic} already answers it, and for free: it is scoped to the current layer, so a neighbour
     * that only exists further up returns null. An unbuilt neighbour it DOES return is work still queued for this
     * layer -- the support is coming, so wait. Nothing but null means whatever could have held this cell up belongs to
     * a layer that cannot be reached until this one finishes, which is a deadlock unless the cell steps aside.
     *
     * <p>That is exactly the split between the two scenarios: basalt's floating cells have unbuilt neighbours beside
     * them in the same layer, while a down-facing piston's only possible support is the course ABOVE it.
     */
    private boolean supportCouldStillArriveThisLayer(BlockPos cell, BuilderCalculationContext bcc) {
        for (Direction d : Direction.values()) {
            int nx = cell.getX() + d.getStepX();
            int ny = cell.getY() + d.getStepY();
            int nz = cell.getZ() + d.getStepZ();
            BlockState current = bcc.bsi.get0(nx, ny, nz);
            BlockState desired = bcc.getSchematic(nx, ny, nz, current);
            if (desired != null && !desired.isAir() && !valid(current, desired, false)) {
                return true;
            }
        }
        return false;
    }

    private void deferCell(BlockPos cell, String reason) {
        deferCell(cell, reason, null, 1);
    }

    private void deferCell(BlockPos cell, String reason, BuilderCalculationContext bcc, int weight) {
        if (bcc != null) {
            try {
                reason = reason + "; " + diagnoseCell(new BetterBlockPos(cell), bcc);
            } catch (RuntimeException ignored) {
                // a diagnosis is a nicety, never worth taking the build down for
            }
        }
        deferCellWeighted(cell, reason, weight);
    }

    /**
     * A route attempt gave up. Release the target and let the selection choose again — do NOT park.
     *
     * <p>THIS IS NOT REASON C, and telling the two apart is what the first measurement without the old clocks
     * cost. Reason C of the specification is a considered verdict: both lanes searched, within their node budgets,
     * and neither found a route. What reaches this method is something else entirely — "the route made no
     * observable progress for 30 ticks", "path calculation repeatedly failed". Those are timeouts of the machinery
     * that is being removed, not statements about the world.
     *
     * <p>Measured, ringbig run 20260807-000436: treating them as reason C parked 18 cells, every one of them
     * perfectly buildable, and the run ended 29 glass cells short with the bot standing still for 2600 ticks. The
     * old backoff had been papering over it by bringing the cells back for the wrong reason.
     *
     * <p>So this releases the committed target and returns. The cell stays in the working set and will be picked
     * again — which is what the specification's own loop does after a failed attempt: back to P0. Termination for
     * a genuinely unreachable cell comes from P4 once the lane driver decides it, not from a stopwatch here.
     */
    private void deferCellWeighted(BlockPos cell, String reason, int weight) {
        long key = positionKey(new BetterBlockPos(cell));
        if (placementTargetLock.owns(key)) {
            releasePlacementTarget();
        }
        // Named, because a released target that keeps being re-selected is the shape a livelock has, and the only
        // way to see one coming is to be able to count it.
        BuildTrace.cell(buildTick, "RELEASE", cell.getX(), cell.getY(), cell.getZ(), "why=" + reason);
        if (buildTick % 200 == 0) {
            logMechanic("Released the target at " + cell.getX() + "," + cell.getY() + "," + cell.getZ()
                    + " (" + reason + "); it stays in the working set");
        }
    }

    static boolean deferralMayBeScheduled(long activeDeadline, long currentTick) {
        return activeDeadline <= currentTick;
    }

    /** A non-escalating one-scheduler-tick yield for transient geometry/inventory state. */
    private void yieldPlacementCell(BlockPos cell) {
        if (cellDeferredUntilTick == null) {
            cellDeferredUntilTick = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        }
        long hash = positionKey(cell);
        long until = buildTick + ORDINARY_CELL_YIELD_TICKS;
        if (!cellDeferredUntilTick.containsKey(hash) || cellDeferredUntilTick.get(hash) < until) {
            cellDeferredUntilTick.put(hash, until);
        }
        if (placementTargetLock.owns(hash)) {
            releasePlacementTarget();
        } else {
            resetOrdinaryGateTracking();
        }
    }

    /**
     * A full cube's GoalAdjacent stance kept producing an aligned-but-invalid ray. For the first few times just yield
     * (the settle window may still land it, and neighbours drain meanwhile). Once it has been yielded
     * {@link #ORDINARY_MAX_YIELDS} times, the stance is genuinely bad (occluded / edge geometry) — reposition via the
     * same walk-center-derive recovery oriented blocks use. Recovery walks a finite set of stances and defers the
     * cell itself if all are exhausted, so this always terminates. Never removes the cell from the unresolved set.
     */
    private void yieldOrRecoverOrdinaryCube(Placement placement) {
        long hash = positionKey(placement.target);
        if (ordinaryYieldCounts == null) {
            ordinaryYieldCounts = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        }
        int yields = ordinaryYieldCounts.addTo(hash, 1) + 1;
        if (yields >= ORDINARY_MAX_YIELDS && placementTargetLock.owns(hash)
                && !placementTargetLock.isRecovering()) {
            ordinaryYieldCounts.remove(hash);
            resetOrdinaryGateTracking();
            long failedStance = placementTargetLock.attempt().map(p -> p.stanceKey)
                    .orElseGet(() -> positionKey(ctx.playerFeet()));
            placementTargetLock.startRecovery(failedStance);
            logMechanic("Full cube at " + placement.target.getX() + "," + placement.target.getY() + ","
                    + placement.target.getZ() + " unplaceable from this stance after " + yields
                    + " yields; repositioning");
            return;
        }
        yieldPlacementCell(placement.target);
    }

    /**
     * Is this cell parked, i.e. out of the working set until the watchman lets it back in?
     *
     * <p>THE ONE CHOKE POINT, and it no longer has a clock in it. Every scan loop -- placement, break, interact,
     * stance search -- already consulted this method, which is why replacing its BODY replaces the selection
     * behaviour of the whole builder at once instead of in eleven places.
     *
     * <p>What it used to do: consult a retirement set, then a per-cell deadline, and report the cell as available
     * again the moment {@code buildTick} passed that deadline -- while removing the entry as a side effect of
     * being asked. That is precisely what the specification forbids: "geparkte Zellen pruefen sich NIE von
     * selbst". A cell came back because time had passed, not because anything about the world had changed, and
     * nothing about a cell changes on its own -- the bot is the only thing that changes this world.
     *
     * <p>Read-only now, and that matters: a census that asks this question must not alter the answer.
     */
    private boolean isCellParked(int x, int y, int z) {
        return !parkedCells.isEmpty() && parkedCells.containsKey(positionKey(x, y, z));
    }

    // unretireNeighboursOf IST WEG. Es war der Vorlaeufer des Waechters und beantwortete dieselbe Frage schlechter:
    // es holte Zellen aus der Ruhestandsmenge zurueck, wenn NEBEN ihnen etwas gelandet war, aber ueber einen
    // Deferral-Zaehler statt ueber die Flaechenlage -- also nach Versuchsbudget statt nach dem, was sich in der
    // Welt geaendert hat. watchmanAfterChangeAt vergleicht stattdessen die gespeicherte Klickflaechen-Maske gegen
    // die aktuelle und weckt genau die Zellen, fuer die sich wirklich etwas geaendert hat. Zwei Wege aus einer
    // Wartemenge sind einer zu viel: die Spezifikation laesst genau einen zu.

    /**
     * When path calculation has failed over and over, say WHERE the bot is standing and what is around it.
     *
     * <p>Written because a 322000-tick etz-basalt run produced 191373 lines reading "Open set size: 0, PathNode map
     * size: 15" and not one of them said why. The shape of that run is a switch, not a slope: two such failures before
     * tick 100000, then a steady 19300 per 20000 ticks for the rest of the run, and the build's placement count flat
     * from tick 125840 onward. A search that expands fifteen nodes and empties its open set is a bot in a pocket, and
     * fifteen nodes is about a three-block room. Which room, and what walls it, is the one fact 191373 lines omitted.
     *
     * <p>Costs nothing until the build is already failing: it prints once per {@link #WALLED_IN_REPORT_EVERY}
     * consecutive failures, and the counter is cleared by any successful placement.
     */
    private void reportIfWalledIn(BetterBlockPos feet) {
        if (++consecutivePathFailures % WALLED_IN_REPORT_EVERY != 0) {
            return;
        }
        StringBuilder around = new StringBuilder();
        for (int dy = -1; dy <= 2; dy++) {
            around.append(" y").append(dy >= 0 ? "+" : "").append(dy).append('[');
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0 && dy >= 0 && dy <= 1) {
                        around.append('@');   // the bot's own body
                        continue;
                    }
                    BlockState st = ctx.world().getBlockState(
                            new BetterBlockPos(feet.x + dx, feet.y + dy, feet.z + dz));
                    around.append(st.isAir() ? '.' : (st.getFluidState().isEmpty() ? '#' : '~'));
                }
                around.append(dz < 1 ? '/' : ']');
            }
        }
        logMechanic("Walled in? " + consecutivePathFailures + " path calculations in a row have failed from "
                + feet.x + "," + feet.y + "," + feet.z + ". Neighbourhood ('.' air, '#' solid, '~' fluid,"
                + " '@' the bot), read as rows of z-1/z/z+1 each x-1..x+1:" + around);
    }

    private boolean hasDeferredCells() {
        if (cellDeferredUntilTick == null || cellDeferredUntilTick.isEmpty()) {
            return false;
        }
        cellDeferredUntilTick.long2LongEntrySet().removeIf(e -> e.getLongValue() <= buildTick);
        return !cellDeferredUntilTick.isEmpty();
    }

    private void clearCellRetryHistory(long key) {
        if (cellDeferralCounts != null) {
            cellDeferralCounts.remove(key);
        }
        if (cellDeferredUntilTick != null) {
            cellDeferredUntilTick.remove(key);
        }
        if (ordinaryYieldCounts != null) {
            ordinaryYieldCounts.remove(key);
        }
        if (repressCellTracked && repressCellHash == key) {
            repressCellTracked = false;
            repressCellHash = -1;
            repressCount = 0;
        }
    }

    /** The incorrect cell closest to [from], used only to give periodic diagnostics concrete coordinates. */
    private BetterBlockPos nearestIncorrect(BetterBlockPos from) {
        BetterBlockPos best = null;
        long bestDist = Long.MAX_VALUE;
        for (BetterBlockPos p : incorrectPositions) {
            long dx = p.x - from.x, dy = p.y - from.y, dz = p.z - from.z;
            long d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }


    /** Nearest cell that assemble() can currently route to with the available inventory, for temporary route
     *  rotation after repeated calculation/stagnation failures. It is never removed from completion accounting. */
    /** The axis along which the bounded band advances; rows themselves run along the longer side. */
    private boolean sweepAlongX() {
        ISchematic active = realSchematic != null ? realSchematic : schematic;
        return active == null || active.widthX() <= active.lengthZ();
    }

    /** Fixed band containing the oldest unresolved row, including PARK, or MIN_VALUE when no row remains. */
    private int bandRearEdge() {
        if (buildInRows && rowActiveBandStart != Integer.MIN_VALUE) {
            return rowActiveBandStart;
        }
        if ((incorrectPositions == null || incorrectPositions.isEmpty()) && parkedCells.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        boolean sweepX = sweepAlongX();
        int rear = Integer.MAX_VALUE;
        if (incorrectPositions != null) {
            for (BetterBlockPos pos : incorrectPositions) {
                rear = Math.min(rear, sweepX ? pos.x : pos.z);
            }
        }
        // PARK cells are deliberately absent from incorrectPositions in builder/v3. Omitting them here lets the
        // band advance past the exact hole it exists to keep open, so a 1023/1024 picture can surround its last cell.
        for (ParkedCell parked : parkedCells.values()) {
            int localY = origin == null ? 0 : parked.pos.y - origin.getY();
            if (origin != null && (localY < bandMinYLocal || localY > bandMaxYLocal)) {
                continue;
            }
            rear = Math.min(rear, sweepX ? parked.pos.x : parked.pos.z);
        }
        if (rear == Integer.MAX_VALUE) {
            return Integer.MIN_VALUE;
        }
        int bandOrigin = sweepX ? origin.getX() : origin.getZ();
        return rowBandStart(rear, bandOrigin, BAND_ROWS);
    }

    static int rowBandStart(int row, int origin, int width) {
        int boundedWidth = Math.max(1, width);
        return row == Integer.MIN_VALUE
                ? Integer.MIN_VALUE
                : origin + Math.floorDiv(row - origin, boundedWidth) * boundedWidth;
    }

    static boolean rowBandContains(int row, int rear, int width) {
        return rear != Integer.MIN_VALUE && row >= rear && row <= rear + Math.max(1, width) - 1;
    }

    /** Alternating bands avoid an empty 128-block return walk: even bands advance, odd bands come straight back. */
    static boolean rowBandRunsForward(int rear, int origin, int width) {
        return (Math.floorDiv(rear - origin, Math.max(1, width)) & 1) == 0;
    }

    /**
     * Pins both target election and movement-side template placement to one global cross-slice. The frontier is taken
     * from every unresolved cell, not merely from cells whose material happens to be in the hotbar this tick.
     */
    /** Pins target election and movement-side template placement to one global cross-slice. */
    private void refreshRowFrontier(BuilderCalculationContext bcc) {
        if (!buildInRows || incorrectPositions == null || origin == null || bcc == null) {
            rowActiveBandStart = Integer.MIN_VALUE;
            rowActiveFrontier = Integer.MIN_VALUE;
            return;
        }
        boolean sweepX = sweepAlongX();
        ISchematic full = realSchematic != null ? realSchematic : schematic;
        int rowOrigin = sweepX ? origin.getX() : origin.getZ();
        int rowLimit = rowOrigin + (sweepX ? full.widthX() : full.lengthZ()) - 1;
        int alongOrigin = sweepX ? origin.getZ() : origin.getX();
        int alongLimit = alongOrigin + (sweepX ? full.lengthZ() : full.widthX()) - 1;
        int bandStart = rowActiveBandStart == Integer.MIN_VALUE ? rowOrigin : rowActiveBandStart;

        while (bandStart <= rowLimit) {
            boolean forward = rowBandRunsForward(bandStart, rowOrigin, BAND_ROWS);
            int along = forward ? alongOrigin : alongLimit;
            int stop = forward ? alongLimit : alongOrigin;
            int step = forward ? 1 : -1;
            while (true) {
                List<BetterBlockPos> unfinishedSlice = new ArrayList<>();
                int lastRow = Math.min(rowLimit, bandStart + BAND_ROWS - 1);
                for (int row = bandStart; row <= lastRow; row++) {
                    for (int ly = bandMinYLocal; ly <= bandMaxYLocal; ly++) {
                        int x = sweepX ? row : along;
                        int y = origin.getY() + ly;
                        int z = sweepX ? along : row;
                        BlockState current = bcc.bsi.get0(x, y, z);
                        BlockState desired = bcc.getSchematic(x, y, z, current);
                        if (desired != null && !valid(current, desired, false)
                                && !notACellOfItsOwn(desired, x, y, z, bcc)) {
                            unfinishedSlice.add(new BetterBlockPos(x, y, z));
                        }
                    }
                }
                if (!unfinishedSlice.isEmpty()) {
                    rowActiveBandStart = bandStart;
                    rowActiveFrontier = along;
                    // recalc's locality window is not the global ordering oracle. Seed the licensed slice explicitly
                    // so all five cells are offered even when only one of them happened to enter that window.
                    for (BetterBlockPos pos : unfinishedSlice) {
                        if (!isCellParked(pos.x, pos.y, pos.z)) {
                            incorrectPositions.add(pos);
                        }
                    }
                    return;
                }
                if (along == stop) {
                    break;
                }
                along += step;
            }
            bandStart += BAND_ROWS;
        }
        rowActiveBandStart = Integer.MIN_VALUE;
        rowActiveFrontier = Integer.MIN_VALUE;
    }

    private BetterBlockPos nearestRoutableUnresolved(BetterBlockPos from, BuilderCalculationContext bcc) {
        BetterBlockPos best = null;
        long bestDist = Long.MAX_VALUE;
        for (BetterBlockPos p : incorrectPositions) {
            if (isCellParked(p.x, p.y, p.z)) {
                continue;
            }
            BlockState current = bcc.bsi.get0(p);
            BlockState desired = bcc.getSchematic(p.x, p.y, p.z, current);
            if (desired == null) {
                continue;
            }
            boolean replaceable = MovementHelper.isReplaceable(p.x, p.y, p.z, current, bcc.bsi);
            boolean placementCell = !(desired.getBlock() instanceof AirBlock)
                    && (replaceable || current.getBlock() instanceof LiquidBlock);
            boolean actionable = placementCell
                    ? placementCellIsRoutable(
                            isSecondaryHalf(desired),
                            containsBlockState(approxPlaceable, desired),
                            desired.canSurvive(ctx.world(), p))
                    : interactionClicks(current, desired) > 0
                    || !valid(current, desired, false);
            if (!actionable) {
                continue;
            }
            long dx = p.x - from.x, dy = p.y - from.y, dz = p.z - from.z;
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDist) {
                bestDist = distance;
                best = p;
            }
        }
        return best;
    }

    /** Recovery evaluates the world after leaving the current pose; true means no non-player obstruction remains. */
    static boolean recoveryCandidateMayBePlanned(boolean plausibleIgnoringCurrentPlayer) {
        return plausibleIgnoringCurrentPlayer;
    }

    /** A placement target is routable only when assemble() could give it a real placement goal now. In particular,
     *  a wall lever whose supporting block is still absent must not be selected by the no-progress watchdog as if a
     *  route had failed; there is no legal goal for it until that support exists. */
    static boolean placementCellIsRoutable(boolean secondaryHalf,
                                           boolean materialAvailable,
                                           boolean canSurviveNow) {
        return !secondaryHalf && materialAvailable && canSurviveNow;
    }

    /** Current-state diagnosis. Unlike the old fall-through "AIM" label, every class below performs the check it
     *  names and distinguishes travel/routing from click derivation and the live gate. */
    private String diagnoseCell(BetterBlockPos cell, BuilderCalculationContext bcc) {
        BlockState curr = bcc.bsi.get0(cell.x, cell.y, cell.z);
        BlockState desired = bcc.getSchematic(cell.x, cell.y, cell.z, curr);
        if (desired == null) {
            return "OUT-OF-BOUNDS: no schematic block here";
        }
        String want = blockName(desired);
        if (!MovementHelper.isReplaceable(cell.x, cell.y, cell.z, curr, bcc.bsi)) {
            if (interactionClicks(curr, desired) > 0) {
                return "INTERACTION: right block, wrong toggle (open/delay/mode/note) for " + want;
            }
            return "BREAK: wrong block " + blockName(curr) + " present, can't clear it (want " + want + ")";
        }
        boolean inInventory = approxPlaceable != null && containsBlockState(approxPlaceable, desired);
        if (!inInventory) {
            return "MATERIAL: no item in inventory that places " + want;
        }
        if (hotbarStackThatPlaces(desired) == null) {
            return "HOTBAR: " + want + " is in inventory but not ready on the hotbar";
        }
        if (!desired.canSurvive(ctx.world(), new BlockPos(cell.x, cell.y, cell.z))) {
            return "SUPPORT: nothing to hold " + want + " here (needs a neighbour/scaffold first)";
        }
        BetterBlockPos feet = ctx.playerFeet();
        long dx = cell.x - feet.x, dy = cell.y - feet.y, dz = cell.z - feet.z;
        long distanceSquared = dx * dx + dy * dy + dz * dz;
        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) > 5) {
            return "RANGE: player is " + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(distanceSquared))
                    + " blocks from " + want + "; no placement click is currently derivable";
        }
        if (possibleToPlace(desired, cell.x, cell.y, cell.z, bcc).isPresent()) {
            return "GATE: a concrete support/face plan exists now, but alignment or the live ray has not accepted it";
        }
        if (placementStancesFor(cell.x, cell.y, cell.z, desired, bcc, 1, true, ignored -> true).isEmpty()) {
            // Carry the breakdown here too: this path reports the same wall as the "stances exhausted" deferral, and
            // without the counts it is impossible to tell "nowhere to stand" from "the click would place the wrong
            // thing" -- which are opposite problems with opposite fixes.
            return "STANCE: no standable, occlusion-safe stance can currently place " + want + " -- "
                    + stanceRejectionSummary(cell.x, cell.y, cell.z, desired, bcc);
        }
        Goal goal = princeps.getPathingBehavior().getGoal();
        if (princeps.getPathingBehavior().calcFailedLastTick()) {
            return "PATH: route calculation failed before a placement attempt for " + want;
        }
        return goal != null && goal.isInGoal(feet)
                ? "DERIVATION: current goal is satisfied, but this feet cell cannot derive a real click for " + want
                : "ROUTE: a different valid stance exists and still needs to be reached for " + want;
    }

    private static String blockName(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** Collision-free coordinate identity within Minecraft's supported world bounds. */
    private static long positionKey(BlockPos pos) {
        return pos.asLong();
    }

    private static long positionKey(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** A completed/invalid target releases both the cell and its last usable navigation goal. */
    private void clearElectedTarget() {
        discardLaneQuestion();
        laneAProof = null;
        laneEscalatedCell = null;
        laneBAnswered = false;
        platformTraverseApproach = null;
        electedCell = null;
        electedGoal = null;
        electedBreak = false;
        scaffoldPassAllowed = false;
    }

    /** Unknown is a missing observation, not permission to select a different build cell. */
    Goal acceptElectedVerdict(CellUrteil verdict) {
        if (verdict instanceof CellUrteil.Setzen setzen) electedGoal = setzen.ziel();
        else if (verdict instanceof CellUrteil.Parken) clearElectedTarget();
        // Keep the chosen cell through a material wait, but never reuse an edge action whose proof was withdrawn.
        if (electedGoal instanceof PlatformTraverseGoal && platformTraverseApproach == null) return null;
        return electedGoal;
    }

    /** Stable across equivalent Goal/PathExecutor reconstruction; no coordinates are retained per route. */
    private record ProgressRoute(Long target, long hash, int length) { }

    static int progressPathPosition(PathExecutor path) {
        if (path == null || path.failed() || path.finished()) return -1;
        int position = path.getPosition();
        return position >= 0 && position < path.getPath().positions().size() ? position : -1;
    }

    private PathingCommand observeBuilderProgress() {
        progressHold = false;
        if (schematic == null) {
            progressWatch.reset();
            return null;
        }
        BuilderProgressWatch.Phase phase = BuilderProgressWatch.Phase.WORK;
        BetterBlockPos target = committedPlaceTarget != null ? committedPlaceTarget : electedCell;
        Long targetKey = target == null ? null : positionKey(target);
        double distance = 0;
        Object route = null;
        int routePosition = -1;
        Long miningTarget = null;
        double miningProgress = 0;
        if (paused) {
            phase = BuilderProgressWatch.Phase.PAUSED;
        } else if (ctx.world() == null || ctx.player() == null
                || !ctx.world().getChunkSource().hasChunk(ctx.playerFeet().x >> 4, ctx.playerFeet().z >> 4)
                || target != null && !ctx.world().getChunkSource().hasChunk(target.x >> 4, target.z >> 4)) {
            phase = BuilderProgressWatch.Phase.WAIT_CHUNK;
        } else {
            if (target != null) {
                distance = Math.sqrt(ctx.player().position().distanceToSqr(target.x + 0.5, target.y, target.z + 0.5));
                BuilderCalculationContext bcc = new BuilderCalculationContext();
                BlockState wanted = bcc.getSchematic(target.x, target.y, target.z, bcc.bsi.get0(target));
                BlockState currentState = bcc.bsi.get0(target);
                if (wanted != null && !wanted.isAir() && !containsBlockState(approxPlaceable(36), wanted)
                        && !valid(currentState, wanted, false)
                        && MovementHelper.isReplaceable(target.x, target.y, target.z, currentState, bcc.bsi)) {
                    phase = BuilderProgressWatch.Phase.WAIT_MATERIAL;
                }
            }
            if (phase == BuilderProgressWatch.Phase.WORK) {
                var controller = (princeps.utils.accessor.IPlayerControllerMP) ctx.minecraft().gameMode;
                BlockPos breaking = controller.getCurrentBlock();
                if (controller.isHittingBlock() && breaking != null && !ctx.world().getBlockState(breaking).isAir()) {
                    phase = BuilderProgressWatch.Phase.MINING;
                    progressActions.arm(positionKey(breaking), ctx.world().getBlockState(breaking), Blocks.AIR.defaultBlockState());
                    miningTarget = positionKey(breaking);
                    miningProgress = Math.max(0, controller.getDestroyProgress());
                } else if (lastPlacedCell != null || pendingPlacementRequest != null) {
                    phase = BuilderProgressWatch.Phase.WAIT_CONFIRMATION;
                }
            }
            PathExecutor current = princeps.getPathingBehavior().getCurrent();
            int currentPosition = progressPathPosition(current);
            if (currentPosition >= 0) {
                if (current != progressExecutor || !Objects.equals(targetKey, progressRouteTarget)) {
                    Object previousRoute = progressRoute;
                    int reachedPreviousNode = Objects.equals(targetKey, progressRouteTarget)
                            ? progressRoutePositions.indexOf(ctx.playerFeet()) : -1;
                    long hash = 0xcbf29ce484222325L;
                    for (BetterBlockPos pos : current.getPath().positions()) {
                        hash = (hash ^ positionKey(pos)) * 0x100000001b3L;
                    }
                    progressRoute = new ProgressRoute(targetKey, hash, current.getPath().positions().size());
                    progressWatch.beginRoute(progressRoute, currentPosition);
                    // A shortened/replanned path starts at node zero again. Credit the physical feet reaching a
                    // later node of its predecessor, never the replacement executor's creation or cancel sentinel.
                    route = reachedPreviousNode > progressRoutePosition ? previousRoute : progressRoute;
                    routePosition = reachedPreviousNode > progressRoutePosition ? reachedPreviousNode : currentPosition;
                    progressExecutor = current;
                    progressRouteTarget = targetKey;
                    progressRoutePositions = java.util.List.copyOf(current.getPath().positions());
                    progressRoutePosition = currentPosition;
                } else {
                    route = progressRoute;
                    routePosition = currentPosition;
                    progressRoutePosition = Math.max(progressRoutePosition, currentPosition);
                }
            }
        }
        BuilderProgressWatch.Decision decision = progressWatch.observe(System.nanoTime(), new BuilderProgressWatch.Sample(
                phase, targetKey, distance, route, routePosition, confirmedProgressRevision, miningTarget, miningProgress));
        if (phase != progressPhase || decision != BuilderProgressWatch.Decision.CONTINUE) {
            progressPhase = phase;
            String detail = "phase=" + phase + " target=" + (target == null ? "none" : target.toShortString())
                    + " quietSeconds=" + java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(progressWatch.quietNanos())
                    + " distance=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
                    + " pathNode=" + routePosition + " confirmedWorldChanges=" + confirmedProgressRevision;
            BuildTrace.cell(buildTick, "PROGRESS-" + decision, target == null ? 0 : target.x,
                    target == null ? 0 : target.y, target == null ? 0 : target.z, detail);
            logMechanic("Builder progress: " + detail);
            if (decision == BuilderProgressWatch.Decision.STOP) {
                progressHold = true;
                if (beginHomeRecovery(phase, System.nanoTime())) return advanceHomeRecovery(System.nanoTime());
                abortBuild(phase == BuilderProgressWatch.Phase.WAIT_MATERIAL ? Ending.MATERIALS_MISSING : Ending.PLACEMENT_FAILED,
                        "Build stopped: no confirmed world action or route progress for 60 active seconds",
                        java.util.List.of(detail, "All unresolved cells are retained; no automatic restart was requested."));
                princeps.getInputOverrideHandler().clearAllKeys();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        if (phase == BuilderProgressWatch.Phase.WAIT_CHUNK || phase == BuilderProgressWatch.Phase.WAIT_MATERIAL) {
            progressHold = true;
            princeps.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (decision == BuilderProgressWatch.Decision.DIAGNOSE && phase == BuilderProgressWatch.Phase.WORK) {
            Goal currentGoal = princeps.getPathingBehavior().getGoal();
            if (currentGoal != null) {
                return continueCurrentRoute(currentGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
        }
        return null;
    }

    private boolean navigationMadeProgress(BetterBlockPos feet, Goal activeGoal, boolean calcFailed) {
        return !calcFailed && progressWatch.advancedLastSample();
    }

    private void resetNavigationProgressTracking() {
        observedNavigationGoal = null;
        observedNavigationExecutor = null;
        bestObservedPathPosition = -1;
        bestObservedGoalHeuristic = Double.POSITIVE_INFINITY;
        navigationVisitedCells.clear();
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            // A horizontal face has only one height, so refining it vertically is meaningless -- what a steep or shallow
            // approach needs here is lateral spread, and the corners are what a ray from an awkward angle can still see
            // when the centre is occluded by the block's own neighbour.
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9),
                        new Vec3(0.25, 1, 0.25), new Vec3(0.75, 1, 0.25), new Vec3(0.25, 1, 0.75), new Vec3(0.75, 1, 0.75)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9),
                        new Vec3(0.25, 0, 0.25), new Vec3(0.75, 0, 0.25), new Vec3(0.25, 0, 0.75), new Vec3(0.75, 0, 0.75)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                // Two points down the middle of a side face is a thin target. Everything that hangs ON a wall --
                // torches, signs, banners, ladders, buttons -- can only be placed by hitting that face, and from a
                // stance beside it the ray arrives at a steep angle and clips the block's top or a neighbour
                // instead: 78 "ray missed the face" against 0 out-of-reach on the lighthouse's wall torches. Offer
                // the centre and the lateral thirds as well, so a stance that can see any part of the face works.
                double lateralA = side.getStepX() == 0 ? 0.25 : x;
                double lateralB = side.getStepX() == 0 ? 0.75 : x;
                double lateralAz = side.getStepZ() == 0 ? 0.25 : z;
                double lateralBz = side.getStepZ() == 0 ? 0.75 : z;
                return new Vec3[]{
                        new Vec3(x, 0.5, z),
                        new Vec3(x, 0.25, z),
                        new Vec3(x, 0.75, z),
                        new Vec3(lateralA, 0.5, lateralAz),
                        new Vec3(lateralB, 0.5, lateralBz),
                        // FINER VERTICALLY, 05.08.2026, at the owner's request, and it is the sub-block half that
                        // matters. A slab, a stair or a trapdoor takes its `type`/`half` from WHERE IN THE TARGET CELL
                        // the ray lands, not from the look direction -- upper half gives top, lower half gives bottom --
                        // and 0.25/0.5/0.75 down the middle is a coarse net for a property with no tolerance. Cell
                        // 68,-60,67 of the oriented scenario wanted stone_slab[type=top] and the search reported 314x
                        // "wrong block would land" against 0 that would work.
                        //
                        // Deliberately applied HERE rather than at either caller, because both the derivation
                        // (orientationAchievableFrom) and the live check (possibleToPlace) come through this one
                        // function -- the owner's point being that navigation is precise to about 99.9%, so the angle
                        // that was perfect for the derived stance need not be perfect on arrival, and the live check
                        // must therefore keep its own say with the same resolution.
                        new Vec3(x, 0.1, z),
                        new Vec3(x, 0.9, z),
                        new Vec3(lateralA, 0.25, lateralAz),
                        new Vec3(lateralB, 0.25, lateralBz),
                        new Vec3(lateralA, 0.75, lateralAz),
                        new Vec3(lateralB, 0.75, lateralBz),
                };
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return holdStillWithoutTearingUpTheRoute(onTick(calcFailed, isSafeToCancel, 0));
    }

    /**
     * A tick that clicked nothing has not earned the right to destroy the route the builder asked for one tick ago.
     *
     * <p>THE STALL, finally. A map art near the end stands still for minutes with cells owed, and the census reads
     * {@code goal=none, pathing=false, calculating=false} every single time -- while the pathfinder solves that
     * very goal in 0 ms about fourteen times a second, over a thousand times inside one stall. Both halves are
     * true at once, and this is how.
     *
     * <p>{@code CANCEL_AND_SET_GOAL} is destructive. {@code PathingControlManager} answers it by nulling the goal
     * and calling {@code cancelSegmentIfSafe()}, which cancels the in-flight A* AND throws away a path that had
     * already been computed. {@code REVALIDATE} and {@code FORCE_REVALIDATE} only ever START a search, and only
     * when none is running. So a builder that alternates one routing tick with one holding tick can never keep a
     * route for two consecutive ticks: it starts a route on tick N and destroys it on tick N+1, for as long as
     * you care to watch. The operator watched it as a route line that blinked.
     *
     * <p>It bites at the end of a picture because that is where the bot must MOVE to place anything. Building a
     * row, it stands on the row it just finished, and the only clickable face for the next row is the north face
     * of the block under its own feet -- a plane its eye is already past. possibleToPlace ray-traces from the real
     * eye and returns empty for every cell ("500x no placement derivable", five windows running), so the builder
     * correctly asks to step one block forward and bridge. That step is the route this was tearing up.
     *
     * <p>Which is exactly why shoving the body did nothing and steering it over the edge by hand worked: the
     * shove does not carry the eye across the face plane, and the bot could not walk itself there.
     *
     * <p>A tick that really clicks still cancels -- standing still while clicking is what the cancel is FOR. Keys
     * are cleared at the top of every tick, so a forced click can only have been forced by this tick, which makes
     * it the exact test for "did this tick act".
     */
    PathingCommand holdStillWithoutTearingUpTheRoute(PathingCommand command) {
        if (command == null
                || command.commandType != PathingCommandType.CANCEL_AND_SET_GOAL
                || command.goal != null) {
            return command; // not an idle hold -- leave it exactly as it is
        }
        if (paused || progressHold || cleanupEscape != null || incorrectPositions == null || incorrectPositions.isEmpty()) {
            return command; // nothing owed, so cancelling is the right answer
        }
        if (princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                || princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT)) {
            return command; // a real click this tick: hold the body still, that is what the cancel is for
        }
        Goal inFlight = princeps.getPathingBehavior().getGoal();
        if (inFlight == null
                && !princeps.getPathingBehavior().isPathing()
                && princeps.getPathingBehavior().getInProgress().isEmpty()) {
            return command; // no route to protect
        }
        return continueCurrentRoute(inFlight, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    /** A continuation must retain the route's rules as well as its goal. A plain command makes PathingBehavior
     * construct a generic context, briefly allowing routes that the next builder tick cannot execute. Preserve
     * the exact snapshot, including lane A/B and AutoDig's licensed initial approach. New route
     * commands still choose their own context; without an existing snapshot there is nothing to retain. */
    private PathingCommand continueCurrentRoute(Goal goal, PathingCommandType type) {
        CalculationContext context = princeps.getPathingBehavior().secretInternalGetCalculationContext();
        return context == null ? new PathingCommand(goal, type) : new PathingCommandContext(goal, type, context);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        // ABBRUCH-PRUEFPUNKT 1 von 3. Ganz vorn, vor buildTick++ und vor jedem frueheren return: ein Abbruch, der
        // im vorigen Tick oder in einem tieferen Rekursionsschritt angemeldet wurde, darf keinen weiteren Tick
        // Arbeit ausloesen.
        if (finishAbortedBuild()) {
            return null;
        }
        if (recursions == 0 && homeRecovery != null) {
            buildTick++;
            return advanceHomeRecovery(System.nanoTime());
        }
        if (recursions == 0) {
            buildTick++;
            var survival = princeps.getSurvivalBehavior();
            breakBranchProgress.beginTick(paused, survival != null && survival.ownsInventory(),
                    survival != null && survival.isConsuming());
            var currentWorld = ctx.world();
            if (currentWorld != null && breakTargetObservation.observe(currentWorld,
                    currentWorld::hasChunkAt, currentWorld::getBlockState)) {
                breakBranchProgress.observedProgress();
            }
            if (excavating) {
                PathingCommand previousCommand = princeps.getPathingControlManager().mostRecentCommand().orElse(null);
                excavationApproach.observeCommand(previousCommand instanceof PathingCommandContext contextual
                        ? contextual.desiredCalcContext.excavationApproachToken() : null);
                excavationApproach.observe(ctx.playerFeet());
                if (!paused) enforceAutoDigLookProfile();
                excavationActiveClock.tick(paused, survival != null && survival.ownsInventory(),
                        survival != null && survival.isConsuming());
                if (paused || (survival != null && (survival.ownsInventory() || survival.isConsuming()))) {
                    excavationFluidPlugs.suspendRouteProgress();
                }
            }
            if (calcFailed) {
                homeFailedRoute = princeps.getPathingBehavior().getGoal();
                homeFailedTarget = committedPlaceTarget != null ? committedPlaceTarget : electedCell;
                homeFailedRevision = confirmedProgressRevision;
            }
            observePendingPlacementRequest();
            // Runs BEFORE anything can return. Every previous stall guard sat further down onTick, behind a dozen
            // early returns, so the one situation they existed for -- a branch that returns the same command every
            // tick -- was precisely the situation they could not see. Placing this first is the whole design.
            Set<BlockPos> unconfirmedScaffolds = navigationScaffolds.unconfirmedBefore(buildTick - 400);
            if (!unconfirmedScaffolds.isEmpty()) {
                abortBuild(Ending.PLACEMENT_FAILED, "Build stopped: navigation scaffold placement was not confirmed by the server",
                        unconfirmedScaffolds.stream().map(BlockPos::toShortString).toList());
            }
            excavationFluidPlugs.unconfirmedBefore(excavationActiveClock.now() - 400).ifPresent(pos ->
                    abortBuild(Ending.PLACEMENT_FAILED,
                            "AutoDig stopped: fluid plug placement was not confirmed by the server",
                            List.of("Unconfirmed source plug: " + pos.toShortString(),
                                    "Repeated requests and old fluid updates do not restart the 400-active-tick limit.")));
            PathingCommand progressDecision = observeBuilderProgress();
            if (progressDecision != null) {
                return progressDecision;
            }
            closeAnyContainerScreen();
            narrateIfNoCellHasCompleted();
            trackAimMovement();
            abandonAGoalAlreadySatisfiedWhereWeStand();
            trackReplans();
            traceTick();
            if (buildTick % 40 == 0) { logMechanic("PROBE A tick=" + buildTick + " paused=" + paused
                    + " incorrect=" + (incorrectPositions == null ? -1 : incorrectPositions.size())
                    + " lastCellCompleted=" + lastCellCompletedTick
                    + " lockActive=" + placementTargetLock.isActive()
                    + " pathing=" + princeps.getPathingBehavior().isPathing()
                    + " current=" + (princeps.getPathingBehavior().getCurrent() != null)); }
            // ABBRUCH-PRUEFPUNKT 1b, und er schliesst ein beweisbares Loch. Pruefpunkt 1 steht VOR
            // observePendingPlacementRequest() -- und genau dort entsteht P5 in seiner zweiten Gestalt, weil
            // observePendingPlacementRequest ueber rememberPlacement laeuft. Ohne diese zweite Pruefung liefe die
            // angemeldete Bitte noch durch recalc(); meldet recalc im selben Tick "nichts mehr offen", erreicht die
            // Ausfuehrung den COMPLETED-Arm, und finishWith ist first-call-wins: der Bau meldete "Done building"
            // und das anschliessende resetPlacementTracking() loeschte die Abbruchbitte. Ein Erfolg, den es nie gab.
            // Pruefpunkt 3 liegt HINTER diesem Zweig und kann ihn nicht sehen.
            if (finishAbortedBuild()) {
                return null;
            }
            // THE WATCHDOG FINALLY DOES SOMETHING. It has narrated stalls since the day it was written and never
            // once acted on one; a picture at 91% sat inert for ninety seconds while it wrote the same line nine
            // times. Placed here, in the block that runs ahead of every early return, for the same reason the
            // narration is: a livelock is precisely the state in which the rest of onTick never gets reached.
            // Progress decisions never invent a nearby movement. The elected target owns the route.
        }
        orientedGoalCache.clear(); // the standing-position search is memoized per target for the duration of one tick
        orientedSearchesThisTick = 0;
        stanceSearchNanos = 0L;
        // An episode ends the moment a tick passes with no refusal in it, so a single refused route months apart never
        // accumulates toward the lapse. Decays here rather than on a timer: this teardown is the one place guaranteed
        // to run every tick whatever the builder decided.
        if (scaffoldVetoTicks > 0 && scaffoldVetoLastTick < buildTick - SCAFFOLD_VETO_EPISODE_GAP) {
            scaffoldVetoTicks = 0;
            scaffoldVetoCellKey = -1L;
            scaffoldVetoUnstickTried = false;
        }
        approxPlaceable = approxPlaceable(36);
        if (princeps.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        princeps.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (excavating && Princeps.settings().areaBreakSize.value > 1 && effectiveAreaBreakSize() == 1
                && !(princeps.getSurvivalBehavior() != null && princeps.getSurvivalBehavior().ownsInventory())
                && snakeOrdinaryPickSlot(Blocks.STONE.defaultBlockState()) < 0) {
            abortBuild(Ending.MATERIALS_MISSING,
                    "AutoDig needs an ordinary pickaxe for this selection",
                    java.util.List.of("A 3x3 swing cannot stay inside this selection.",
                            "Put an ordinary pickaxe on the hotbar to continue."));
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (scaffoldCleanupActive && realSchematic != null) {
            schematic = realSchematic;
        }
        if (Princeps.settings().buildInLayers.value && !scaffoldCleanupActive) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            final boolean topDownLayers = Princeps.settings().layerOrder.value;
            // DIE EINE HERLEITUNG. Beide Felder werden hier gesetzt und nirgends sonst; countScaffoldInLayer und
            // die Ebenen-Verifikation lesen sie, statt die Formel ein zweites und drittes Mal zu schreiben.
            int areaSize = effectiveAreaBreakSize();
            LayerBand band;
            if (areaSize > 1 && topDownLayers && areaBandTopCache != Integer.MIN_VALUE) {
                int top = Math.min(realSchematic.heightY() - 1, areaBandTopCache - origin.getY());
                band = new LayerBand(Math.max(0, top - (areaSize - 1)), top);
            } else {
                band = layerBand(realSchematic.heightY(), effectiveLayerHeight(),
                        layer, topDownLayers);
            }
            bandMinYLocal = band.lo();
            bandMaxYLocal = band.hi();
            int minYInclusive;
            int maxYInclusive;
            if (EXCLUSIVE_LAYER_MASK) {
                // S0b der Spezifikation: "Nur die Zellen der Ebene E." Zellen hoeherer UND niedrigerer Ebenen
                // existieren fuer das System nicht.
                minYInclusive = band.lo();
                maxYInclusive = band.hi();
            } else if (topDownLayers) {
                // Kumulativ: das Band plus alles, was schon gebaut ist. Damit bleiben fertige Zellen "korrekt",
                // statt beim naechsten Vollscan wieder als Arbeit aufzutauchen.
                minYInclusive = band.lo();
                maxYInclusive = realSchematic.heightY() - 1;
            } else {
                minYInclusive = 0;
                maxYInclusive = band.hi();
            }
            schematic = layerMask(realSchematic, minYInclusive, maxYInclusive, topDownLayers);
        }
        // DER SCHALTER (Schritt 24). Der Kontext dieses Ticks traegt die Bahn der Zelle, die gerade gefahren wird.
        //
        // electedCell ist ein Latch und ueberlebt den Tick, also ist die Frage hier beantwortbar, obwohl assemble()
        // erst weiter unten laeuft: gefragt wird nach der Zelle, zu der wir GERADE unterwegs sind. Genau ein
        // Kontext je Tick, und Planer wie Fahrt lesen denselben -- das ist die Bedingung, an der der erste Versuch
        // gescheitert ist (921 geplante Bruecken gegen 922 verweigerte, Bot 1920 Ticks bewegungslos).
        Lane laneThisTick = scaffoldCleanupActive || layerCleanupActive
                ? Lane.A_NO_PLACING : laneForCurrentCell(electedCell);
        // Und dasselbe fuer die Ausfuehrungsseite. scaffoldIsLicensedAt -- das globale Praedikat, das eine laufende
        // Bewegung fragt, ob sie einen Wegwerfblock setzen darf -- haengt ab jetzt an DERSELBEN Entscheidung.
        // Vorher hing es an scaffoldPassAllowed, das ein einziges calcFailed fuer den Rest der Zelle oeffnete:
        // der Planer plante unter der einen Regel und der Mover fuhr unter einer anderen.
        scaffoldPassAllowed = laneThisTick == Lane.B_HELPERS_ALLOWED;
        BuilderCalculationContext scanContext = new BuilderCalculationContext(laneThisTick);
        if (cleanupEscape != null) return driveCleanupEscape(isSafeToCancel, scanContext);
        if (platformTraverseApproach != null) {
            platformTraverseGoal(platformTraverseApproach.target, scanContext, true);
            if (scanContext.platformApproach != platformTraverseApproach) {
                scanContext = new BuilderCalculationContext(laneThisTick);
            }
        }
        // GANZ FRUEH IM TICK, nicht am Ende. Zwei Fragen haengen daran und beide muessen JEDEN Tick beantwortet
        // werden: hat die Geruestzelle ihren Zweck erfuellt (dann faellt ihr Soll auf Luft zurueck und der
        // vorhandene Abbau raeumt sie weg), und ist sie gescheitert (dann wird sie aufgegeben)?
        //
        // Am Ende des Ticks stand sie falsch. Der Bau kehrt auf einem Dutzend Wege vorher zurueck -- und
        // ausgerechnet der festgefahrene Zustand ist einer davon, also lief die Aufgabe-Regel genau dann nicht,
        // wenn sie gebraucht wurde. GEMESSEN, Lauf 9a9ac075: Geruest bei T30422 eroeffnet, FROZEN bei T31074,
        // GIVEUP=0, Ende im Zeitablauf. Die Regel war da, sie kam nur nie an die Reihe.
        scaffoldPhaseTick(scanContext);
        boolean hasWork = recalc(scanContext);
        if (hasWork) ordinaryIntegrityStableTicks = 0;

        // BAND COMPLETION IS ITS OWN STATE TRANSITION. It cannot live under !recalc: a clear job normally keeps the
        // thousands of cells in lower bands in its global work set, so recalc remains true while the current 3-high
        // route is already complete. That made the old verification branch structurally unreachable and allowed
        // areaHighestUnfinishedY to select the lower band immediately.
        //
        // The committed band is held independently of both the global work set and mutable snake route state. A full
        // route completion (or an actually empty work set) starts the settle + complete world-state census. Only its
        // successful certificate releases the latch and permits discovery of a lower band.
        int areaSize = effectiveAreaBreakSize();
        int committedAreaTop = areaCommittedBandTop;
        if (areaSize > 1 && origin != null && schematic != null
                && (snakeVerificationActive || parkedCells.isEmpty())
                && snakeBandNeedsVerification(snakeVerificationActive, hasWork, snakeRouteComplete,
                        snakeBandTop, committedAreaTop, snakeVerifiedBandTop)) {
            int verificationTop = snakeVerificationActive
                    ? snakeVerificationBandTop : committedAreaTop;
            PathingCommand verification = snakeVerificationCommand(scanContext, areaSize, verificationTop);
            if (snakeVerifiedBandTop != verificationTop) {
                return verification == null
                        ? new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL)
                        : verification;
            }

            areaCommittedBandTop = Integer.MIN_VALUE;
            areaBandTopCache = Integer.MIN_VALUE;
            areaBand = null;
            snakeRouteComplete = false;
            snakeBandTop = Integer.MIN_VALUE;
            int nextTop = areaHighestUnfinishedY(scanContext);
            if (nextTop != Integer.MIN_VALUE) {
                logDirect("Area window steps down to y=" + nextTop + ".." + (nextTop - (areaSize - 1)));
            }
            // Recalculate against the newly committed wrapper (or the truly empty schematic) in this same tick.
            // Continuing with hasWork from the old band would make the transition depend on stale membership.
            if (recursions < 40) {
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (!hasWork) {
            // A PARK THAT NOTHING CAN WAKE IS A DEADLOCK, and reaching here means the active set is empty. Parked
            // cells are released by an EVENT -- a finished cell -- so once everything still open is parked, no cell
            // can finish, nothing wakes anything, and the window may not step down either, because stepping down
            // requires no parks. The bot then stands with no target at all. The comment on watchmanAfterChangeAt
            // already records this shape from a bench run: 18 cells parked, all reason C, 2600 ticks without a
            // target. Measured again on the owner's server, 20.08.: the last healthy decision was a swing at build
            // tick 1033, and the next 2143 ticks produced no decision at all, no target, no work.
            //
            // The window running dry IS the event. Releasing the parks here is a genuine retry, not a clock and not
            // a skip: whatever is still impossible parks itself again on the next pass, and nothing is dropped.
            if (!parkedCells.isEmpty() && lastParkSweepTick != buildTick) {
                lastParkSweepTick = buildTick;
                logMechanic("Released " + parkedCells.size() + " parked cell(s): the active set is empty, so no"
                        + " finished cell can wake them and the window cannot step down past them");
                releaseParkedCellsForRetry();
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            // P0 IST BEANTWORTET: die AKTIV-Menge dieser Ebene ist leer. Alles, was noch offen ist, ist geparkt --
            // und ab hier urteilt die Makro-Schleife.
            //
            // SETTLE-SPERRE ZUERST. Ein bestaetigter Klick, dessen Ergebnis noch nicht bewertet ist, haelt das
            // Urteil an. Ohne sie wuerde die Ebene in genau dem Tick verifiziert, in dem der letzte Block schon
            // geklickt, aber noch nicht in der Welt sichtbar ist -- und P6b meldete einen Fehler, den es nicht gibt.
            //
            // DIE AUSWERTUNG MUSS HIER SELBST LAUFEN, und das ist keine Feinheit, sondern der Unterschied zwischen
            // einer Sperre und einem Deadlock: detectWrongPlacementResult -- die EINZIGE Stelle, die lastPlacedCell
            // wieder auf null setzt -- steht weiter unten in onTick, hinter diesem Zweig. Eine Sperre, die vorher
            // zurueckkehrt, verhindert also genau die Auswertung, auf die sie wartet. Gemessen: basalt stand ab
            // Tick ~2000 bei layer=1, work=0, parked=0 endlos still, mit fertig gebauter Ebene 0 (936 von 936).
            detectWrongPlacementResult(scanContext);
            if (finishAbortedBuild()) {
                return null;   // P5 hat aus der Auswertung heraus abgebrochen
            }
            if (lastPlacedCell != null) {
                if (buildTick - lastPlacedCellTick < LAYER_SETTLE_MAX_TICKS) {
                    // Immer noch nichts in der Welt zu sehen: der Klick ist unterwegs. Einen Tick warten.
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                // DIE NOTBREMSE, und sie ist bewusst KEINE Wiederholungsuhr. Sie begrenzt eine BEOBACHTUNG, nicht
                // eine Entscheidung: bleibt die Zelle nach einer halben Sekunde immer noch ersetzbar, ist dort
                // nichts gelandet, und die Ebenen-Verifikation unten sieht das ohnehin und nennt die Zelle. Ohne
                // diese Grenze koennte ein Klick, der spurlos verpufft, die Makro-Schleife fuer immer anhalten --
                // und ein Deadlock waere die schlechteste aller Meldungen.
                logMechanic("Nothing landed at " + lastPlacedCell.getX() + "," + lastPlacedCell.getY() + ","
                        + lastPlacedCell.getZ() + " within " + LAYER_SETTLE_MAX_TICKS
                        + " ticks; letting the layer verification judge it");
                lastPlacedCell = null;
                lastPlacedDesired = null;
                lastPlacedCellHash = -1;
            }
            // P6a. "PARK-Liste leer? Ja -> S10. Nein -> ABBRUCH mit Report der Restzellen."
            //
            // Gezaehlt wird NUR im Band dieser Ebene. Die Tuer-Sonderklausel der Maske laesst top-down eine Reihe
            // der naechsten Ebene zu; eine dort geparkte Zelle gehoert nicht zu diesem Urteil.
            List<BetterBlockPos> parkedHere = parkedInBand();
            // ...UND GENAU HIER IST DER MOMENT DER GERUEST-PHASE, nicht erst weiter unten.
            //
            // Der Eigentuemer hat sie so bestimmt: "wenn Sachen, NACHDEM ALLES ANDERE FERTIG IST, also nichts
            // mehr platzierbar oder enderbar ist, immer noch in der Parkliste stehen". Das ist wortwoertlich diese
            // Zeile -- die Ebene ist abgearbeitet, PARK ist nicht leer. Der zweite Aufrufer weiter unten deckt nur
            // den Fall ab, dass MITTEN in einer Ebene nichts mehr geht; er hat der Lampe nie geholfen, weil sie
            // erst am Ebenenende zum letzten Hindernis wird und P6a dort sofort abbrach.
            //
            // GEMESSEN, Lauf 7a10f3da: "VERDICT 98,-59,98 no clickable face faces=0" und im selben Tick
            // "ABORT-REQUESTED LAYER_UNBUILDABLE". Die Phase kam nie an die Reihe. Dass sie geholfen haette,
            // steht per RCON fest: an 98,-61,98 liegt ein schlichter Stein, auf dessen Oberkante entsteht der
            // Hilfsblock 98,-60,98, und auf dessen Oberkante die Lampe. Zwei Klicks -- der Eigentuemer hat die
            // Kette benannt, und die Welt bestaetigt sie.
            //
            // Oeffnet die Phase eine Zelle, ist die Ebene nicht mehr fertig: die bediente Zelle geht zurueck in
            // den Arbeitssatz, und der Bau laeuft ganz gewoehnlich weiter, statt zu urteilen.
            if (!parkedHere.isEmpty() && openScaffoldPhase(scanContext)) {
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            // EIN LAUFENDES GERUEST IST KEIN LEERSTAND. openScaffoldPhase gibt false zurueck, solange eine
            // Geruestzelle lebt -- das ist die Begrenzung "hoechstens eine" und richtig so. P6a hat dieses false
            // aber als "nichts mehr zu tun" gelesen und mitten in der Arbeit abgebrochen.
            //
            // GEMESSEN, Lauf bac6a9b2: "SCAFFOLD-OPEN 73,-60,90" bei T32606, "ABORT-REQUESTED LAYER_UNBUILDABLE"
            // bei T32692 -- 86 Ticks spaeter, das dritte Geruest war noch nicht gesetzt. Die Phase arbeitet die
            // geparkten Zellen der Naehe nach ab, je Zelle einige hundert Ticks, und die Lampe an 98,-59,98 kam
            // deshalb nie an die Reihe: sie liegt am weitesten weg und der Bau war vorher zu Ende.
            if (scaffoldCell != null) {
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            parkedHere = parkedInBand();
            if (!parkedHere.isEmpty()) {
                abortBuild(Ending.LAYER_UNBUILDABLE,
                        "Build stopped: layer " + layer + " has " + parkedHere.size()
                                + " cell(s) that could not be built",
                        parkReport(parkedHere, scanContext));
                return finishAbortedBuild() ? null
                        : new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            PathingCommand integrity = ordinaryExcavationIntegrityCommand(scanContext, isSafeToCancel, false);
            if (integrity != null) return integrity;
            if (Princeps.settings().buildInLayers.value && layer * effectiveLayerHeight()
                    < stopAtHeight) {
                int finishedLayer = layer;
                if (!advanceLayerIfClean(scanContext, Princeps.settings().layerOrder.value, this::logMechanic)) {
                    return finishAbortedBuild() ? null
                            : new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                logDirect("Starting layer " + finishedLayer);
                // DIE REKURSION BLEIBT, bewusst. Sie ist durch recursions > 100 gedeckelt und eine Schematic hat
                // hier achtzehn Ebenen; auch unter der exklusiven Maske, wo jede fertige Ebene leer ist und der Bot
                // sich in einem Tick hochhangelt, bleibt das eine Groessenordnung unter dem Deckel. Der Umbau in
                // eine Schleife waere achtzig Zeilen Einrueckung und haette EINEN echten Nebeneffekt: `ticks`,
                // `approxPlaceable` und der Cache-Reset liefen dann einmal je Tick statt einmal je Aufstieg. Das ist
                // eine Verhaltensaenderung ohne Nutzen an dieser Stelle.
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            if (!verifyLayerBeforeLeaving(scanContext, this::logMechanic)) {
                return finishAbortedBuild() ? null
                        : new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            // KEINE RETRY-SWEEPS MEHR. Was hier stand, war das Gegenteil der Spezifikation: bis zu MAX_RETRY_SWEEPS
            // ganze Durchlaeufe ueber die fertige Schematic, um Zellen zurueckzuholen, die unterwegs "in den
            // Ruhestand" geschickt worden waren -- und danach ein logDirect("Build finished with N cell(s) unbuilt"),
            // dem trotzdem COMPLETED folgte. Ein Bau, der sich als fertig meldet und dabei Zellen fehlen, ist genau
            // der Vertrauensverlust, den der Ergebniskanal beenden soll. Beide Zweige sind ersatzlos entfallen: mit
            // P6a kann eine Ebene gar nicht mehr mit offenen Zellen verlassen werden, also gibt es nichts
            // nachzuholen.
            if (navigationScaffolds.awaitingServer() || scaffoldCleanupAwaitingServer()) {
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            List<BetterBlockPos> remainingScaffolds = remainingNavigationScaffolds(scanContext);
            if (!remainingScaffolds.isEmpty()) {
                scaffoldCleanupActive = true;
                remainingScaffolds.forEach(pos -> scaffoldCleanupTargets.add(pos.asLong()));
                incorrectPositions = null;
                logMechanic("SCAFFOLD-CLEANUP " + remainingScaffolds.size() + " owned navigation block(s)");
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            Vec3i repeat = Princeps.settings().buildRepeat.value;
            int max = Princeps.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                finishWith(Ending.COMPLETED, "Done building", null);
                if (Princeps.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            scaffoldCleanupActive = false;
            layerCleanupActive = false;
            scaffoldCleanupTargets.clear();
            origin = new BlockPos(origin).offset(repeat);
            if (!Princeps.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        refreshRowFrontier(scanContext);
        // The scan context was created before recalc knew the new global slice. Row-mode path search receives a fresh
        // immutable snapshot, so it cannot bridge through a later slice while target election is still on this one.
        // The scan context predates the new global slice. Row-mode path search receives a fresh immutable snapshot.
        BuilderCalculationContext bcc = buildInRows ? new BuilderCalculationContext(laneThisTick) : scanContext;
        // ABBRUCH-PRUEFPUNKT 3 von 3. Hinter dem Ebenen-Aufstiegszweig, wo P6a (PARK nicht leer) und P6b (Ebene
        // fehlerhaft) entstehen. Der Zweig darueber kehrt bei Erfolg selbst zurueck; erreicht die Ausfuehrung diese
        // Zeile, hat die Ebene weitergearbeitet -- und dann darf ein dort angemeldeter Abbruch nicht bis zum
        // naechsten Tick warten, weil dazwischen der Abbau und die Platzierung dieses Ticks liegen.
        if (finishAbortedBuild()) {
            return null;
        }
        if (Princeps.settings().distanceTrim.value) {
            trim(bcc);
        }

        // A placement the controller accepted last tick has settled: if the cell now holds a non-replaceable state
        // that does NOT match what we wanted, the click produced the wrong result (e.g. the wrong slab half).
        // Repeated wrong results reject only that stance; the schematic cell remains unresolved.
        // MUST run BEFORE the break branch: the wrong-placed block is always within reach, so toBreakNearPlayer
        // returns it every tick and would otherwise preempt this check until the evidence is broken away —
        // leaving the fail counter at zero and the loop uncontained (verified failure mode).
        detectWrongPlacementResult(bcc);
        // ABBRUCH-PRUEFPUNKT 2 von 3. Direkt hinter dem Platzierungsbeobachter, weil P5 genau dort entsteht -- und
        // weil der naechste Block der Abbau ist: ein falsch gesetzter Block liegt immer in Reichweite, also wuerde
        // toBreakNearPlayer ihn im selben Tick wegschlagen und damit den Beweis vernichten, den der Report nennt.
        if (finishAbortedBuild()) {
            return null;
        }

        // Before anything else that could keep failing: if the reason nothing works is a door the bot is standing in,
        // no amount of stance searching or deferring will help, and all of it is wasted.
        PathingCommand doorEscape = escapeIfStuckInADoorway();
        if (doorEscape != null) {
            return doorEscape;
        }

        if (buildTick % 40 == 0) { logMechanic("PROBE B tick=" + buildTick + " reached the break call"); }
        blockedFluidPlugThisTick = null;
        Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        boolean miningPostureReady = snakeMiningPostureReady(ctx.player().onGround(), ctx.player().isInWater(),
                ordinaryExcavation() || (excavating && snakeCleanupActive && !snakeCleanupWithAreaTool), snakeEntering);
        if (buildTick % 40 == 0) { logMechanic("PROBE B2 tick=" + buildTick + " toBreak=" + toBreak.isPresent()
                + " safeToCancel=" + isSafeToCancel + " onGround=" + ctx.player().onGround()
                + " breakActiveTick=" + breakBranchProgress.activeTick()
                + " yieldRemaining=" + breakBranchProgress.yieldRemaining()
                + " nonProgress=" + breakBranchProgress.nonProgressTicks()); }
        PathExecutor plugRoute = princeps.getPathingBehavior().getCurrent();
        var excavationSurvival = princeps.getSurvivalBehavior();
        boolean excavationHandsBorrowed = excavationSurvival != null
                && (excavationSurvival.ownsInventory() || excavationSurvival.isConsuming());
        if (blockedFluidPlugThisTick != null
                && !excavationHandsBorrowed
                && insideSnakeVolume(ctx.playerFeet().x, ctx.playerFeet().y, ctx.playerFeet().z)) {
            excavationFluidPlugs.observeRouteProgress(plugRoute, ctx.playerFeet());
        }
        // Check before a repair can arm CLICK_RIGHT. First server confirmations anywhere in the current plug
        // ledger and real, non-repeated route advancement renew this clock; merely aiming or retrying does not.
        if (!excavationHandsBorrowed && excavationFluidPlugs.waitExpired(
                toBreak.isEmpty() ? blockedFluidPlugThisTick : null, excavationActiveClock.now())) {
            abortBuild(Ending.LAYER_VERIFICATION_FAILED, "AutoDig cannot safely remove a renewing fluid plug",
                    java.util.List.of("Retained plug: " + blockedFluidPlugThisTick.plug(),
                            "Unsealed horizontal sources: " + blockedFluidPlugThisTick.sources(),
                            "No safe cut, newly confirmed source seal or route advancement for 200 active ticks.",
                            "An additional safe access route is required; the plug was not removed."));
            finishAbortedBuild();
            return null;
        }
        PathingCommand snakeRepair = excavationIntegrityPlacementCommand(bcc, isSafeToCancel);
        if (snakeRepair != null) {
            return snakeRepair;
        }
        if (toBreak.isPresent()) {
            PathingCommand wetApproach = ExcavationMiningApproach.finishWetStep(excavating,
                    princeps.getPathingBehavior().getCurrent(), ctx.playerFeet(), toBreak.get().getA(),
                    pos -> bcc.bsi.get0(pos.getX(), pos.getY(), pos.getZ()));
            if (wetApproach != null) return wetApproach;
        }
        // The branch guard covers changing targets that the per-cell deadline cannot. Its evidence is actual
        // observed block progress or increasing controller damage, never a cleared CLICK_LEFT request or the
        // placement-only WORLD census. Its own yield and borrowed hands cannot age another starvation interval.
        boolean claimsBreakBranch = toBreak.isPresent() && isSafeToCancel && miningPostureReady;
        BetterBlockPos breakCandidate = toBreak.isPresent() ? toBreak.get().getA() : null;
        float controllerDamage = claimsBreakBranch
                ? princeps.getInputOverrideHandler().getBlockBreakHelper().breakingProgressAt(breakCandidate)
                : Float.NaN;
        if (breakBranchProgress.shouldYield(claimsBreakBranch, breakCandidate, controllerDamage)) {
            toBreak = Optional.empty();
        }
        if (breakBranchProgress.startedYield()) {
            logMechanic("The break branch spent " + (BREAK_BRANCH_STARVATION_TICKS + 1)
                    + " active ticks without observed block progress or increasing controller damage. Yielding for "
                    + BREAK_BRANCH_YIELD_TICKS + " active ticks so placement/recovery can run.");
        }
        if (toBreak.isPresent() && isSafeToCancel && miningPostureReady) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().getB();
            BetterBlockPos pos = toBreak.get().getA();
            BlockState breakState = bcc.get(pos);
            blockWorthATool = breakState;
            if (breakMadeNoProgress(pos, breakState)) {
                princeps.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
                if (pos.equals(snakeCleanupTarget)) snakeCleanupWork.clear();
                deferCell(pos, "breaking made no block-state progress for " + breakNoProgressTicks + " ticks");
                resetBreakProgressTracking();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            // Breaking is a build action too, and until now it left no record at all -- which is why "the bot kept
            // tearing pistons out again" could be watched on screen and not found in any file.
            blocksBroken++;
            breakTargetObservation.claim(ctx.world(), pos, breakState);
            BuildTrace.cell(buildTick, "BREAK", pos.x, pos.y, pos.z, "had=" + blockName(breakState));
            boolean snakeOwnedTarget = snakeHead != null
                    && (pos.equals(snakeHead) || pos.equals(snakeCleanupTarget));
            boolean toolReady;
            if (snakeOwnedTarget) {
                // THE DESCENT USES THE ORDINARY PICKAXE, and that single choice is what makes it possible.
                //
                // An UP swing of the area tool clears a horizontal 3x3, so digging straight down always takes the
                // block the bot is standing on along with the eight around it: measured "atStance=true
                // centred=true onGround=false" -- arrived, aimed, and in mid-air, where snakeReadyToSwing rightly
                // refuses to swing, so the descent never took a second step. An ordinary pickaxe takes exactly the
                // one block under the feet, which is an ordinary one-block drop: it lands, it is on the ground
                // again, and it digs the next one. The special tool is for the tunnel, where the bot stands beside
                // what it swings at; it is the wrong tool for standing on top of it.
                boolean singleBlockSwing = snakeEntering
                        || snakeSingleBlockFallback
                        || snakeRequiresOrdinaryTool
                        || (snakeCleanupActive && pos.equals(snakeCleanupTarget));
                toolReady = snakeToolReady(breakState, singleBlockSwing);
            } else {
                MovementHelper.switchToBestToolFor(ctx, breakState);
                toolReady = !ordinaryExcavation() || !snakeIsAreaTool(ctx.player().getMainHandItem());
            }
            if (ctx.player().isCrouching() && !snakeOwnsTheStance()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                //
                // It holds a crouch that is already on, which is right for a builder mid-placement and wrong for
                // the digger: a crouch that arrived from anywhere would be held here for the rest of the swing,
                // so leaving this ungated would keep the very duck the two helpers above just stopped starting.
                princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            boolean exactSnakeFace = snakeHead != null && ((pos.equals(snakeHead) && !snakeCleanupActive)
                    || (snakeCleanupWithAreaTool && pos.equals(snakeCleanupTarget)));
            if (excavating && !snakeEntering) {
                Direction requiredFace = exactSnakeFace
                        ? snakeCleanupWithAreaTool && pos.equals(snakeCleanupTarget)
                                ? snakeCleanupAreaFace : snakeExpectedFace()
                        : null;
                rot = excavationCurrentMiningLook(pos, requiredFace,
                        snakeIsAreaTool(ctx.player().getMainHandItem())).orElse(rot);
            }
            princeps.getLookBehavior().updateTarget(rot, true, AimIntent.BREAK);
            boolean breakAimReady = exactSnakeFace
                    ? snakeLiveHitMatches(pos)
                    : excavating && (snakeOwnedTarget || ordinaryExcavation()) ? snakeMiningHitMatches(ctx.objectMouseOver(), pos, null)
                    : ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot);
            // DIAGNOSTIC (added while investigating the horizontal-head stall).
            if (snakeOwnedTarget) {
                logMechanic("SNAKECLICK pos=" + pos.x + "," + pos.y + "," + pos.z
                        + " toolReady=" + toolReady + " aimReady=" + breakAimReady
                        + " exactFace=" + exactSnakeFace + " face=" + snakeExpectedFace()
                        + " mouseOver=" + describeMouseOver()
                        + " hand=" + ctx.player().getMainHandItem().getHoverName().getString());
            }
            if (buildTick % 20 == 0) { logMechanic("PROBE D tick=" + buildTick + " pos=" + pos.x + "," + pos.y + "," + pos.z
                    + " toolReady=" + toolReady + " aimReady=" + breakAimReady + " exactFace=" + exactSnakeFace
                    + " owned=" + snakeOwnedTarget); }
            // THE SETTLED ROTATION HAS TO HAVE BEEN SENT, not merely reached. aimHasSettledFor further down this
            // file asked only whether the head was still THIS tick, which a head that has just arrived always is
            // -- so it passed instantly and bought nothing measurable, and its author recorded exactly that. Two
            // to four consecutive still ticks is the difference: it is the head standing on the new face for long
            // enough that the movement packet carrying it has reached the server before the click does.
            boolean aimReceivedByServer = true;
            Direction swingFace = snakeExpectedFace();
            boolean needsSnakeFaceSettle = snakeOwnedTarget && (!excavating || exactSnakeFace);
            if (needsSnakeFaceSettle && swingFace != null && swingFace != snakeLastSwungFace) {
                if (aimMovementDegrees <= AIM_SETTLED_DEGREES) {
                    snakeAimSettledTicks++;
                } else {
                    snakeAimSettledTicks = 0;
                }
                snakeFaceChangeWaitTicks++;
                // NEVER A DEADLOCK, AND NEVER A DEADBAND. The cap is the same promise the placement version made:
                // a swing is never traded for a perfectly still crosshair. And because this releases on a COUNT of
                // still ticks rather than on a tolerance the head keeps drifting in and out of, it cannot become
                // the 1.5 Hz stutter that retired remainWithExistingLookDirection on 0.3.101.
                aimReceivedByServer = snakeAimSettledTicks >= SNAKE_FACE_CHANGE_SETTLE_TICKS
                        || snakeFaceChangeWaitTicks >= SNAKE_FACE_CHANGE_MAX_WAIT_TICKS;
            }
            if (toolReady && breakAimReady && aimReceivedByServer) {
                selectSupportRepair(pos, breakState, bcc.getSchematic(pos.x, pos.y, pos.z, breakState));
                progressActions.arm(positionKey(pos), breakState, Blocks.AIR.defaultBlockState());
                noteExcavationWorkCut(pos);
                princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                if (needsSnakeFaceSettle && swingFace != null) {
                    snakeLastSwungFace = swingFace;
                }
                snakeAimSettledTicks = 0;
                snakeFaceChangeWaitTicks = 0;
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // A temporary branch yield does not replenish the same target's material-aware break budget.
        // breakMadeNoProgress resets it when a different target or block is actually observed.
        // WALKING, for the snake only. Nothing in reach means the head is one step further than the bot is
        // standing, so the goal is the slice it cleared last -- a cell that is guaranteed to exist and guaranteed
        // to be reachable, because the bot made it. This is what the per-cell approach could not offer: a goal
        // beside a BURIED cell does not exist, which is why path calculation kept failing in solid ground.
        if (snakeHead != null && snakeStance != null) {
            // Standing on the corridor level is what turns the level rule below from an approach into a guard.
            if (ctx.playerFeet().y == snakeStance.y) {
                snakeCorridorLevelY = snakeStance.y;
            }
            if (!ctx.playerFeet().equals(snakeStance)) {
                return snakePathingCommand();
            }
            snakeBridgeTarget = null;
            if (!ctx.player().onGround()) {
                return settleInPlacementStance();
            }
            if (!centeredInPlacementStance(snakeStance)) {
                return centerInPlacementStance(snakeStance);
            }
            // If the exact predicted face ray is temporarily unavailable, hold the proven pose. Falling through to
            // placement/ordinary routing would surrender snake ownership and can only make that geometry less true.
            return settleInPlacementStance();
        }

        // The other half of DEFER: what the placement scan walked past without a word. Printed on the build clock, so
        // it appears whether or not anything was placed -- a silent scan is exactly the case worth seeing.
        // ELAPSED, NOT MODULO. A census gated on `buildTick % 200 == 0` fires only if that exact tick reaches
        // it, and the branches above this line return on some ticks and not others. Measured 2026-08-18: a
        // ninety-second stall produced NINE watchdog lines and not one census, because the census ticks kept
        // landing in the early-return phase of a short cycle -- the code below was running most ticks, and
        // the log said the builder had gone silent. That reading cost an afternoon. Asking "has it been 200
        // ticks" instead means a skipped census fires on the next tick that gets here.
        if (buildTick - lastSkipCensusTick >= 200) {
            lastSkipCensusTick = buildTick;
            int total = 0;
            for (int n : placementSkips) {
                total += n;
            }
            if (total > 0) {
                StringBuilder census = new StringBuilder();
                for (int i = 0; i < placementSkips.length; i++) {
                    if (placementSkips[i] == 0) {
                        continue;
                    }
                    if (census.length() > 0) {
                        census.append(", ");
                    }
                    census.append(placementSkips[i]).append("x ").append(SKIP_REASONS[i]);
                }
                logMechanic("Placement scan skipped " + total + " cell-looks since the last census: " + census
                        // Beside the skips on purpose: "N skipped" and "M admitted at head height" only mean
                        // something together. A large M with an unchanged placed count says the channel opened and
                        // led nowhere -- a different failure from the channel never opening, and the two are
                        // indistinguishable if only one of them is printed.
                        + "; head-height cells admitted since the build began: " + headHeightCellsAdmitted);
            }
            java.util.Arrays.fill(placementSkips, 0);
        }

        // THE TWO CENSUSES THE REWRITE IS MEASURED AGAINST, printed on the build clock into the ordinary game log so
        // they survive every gate stage, including the ones that run without -BuildTrace.
        //
        // The first is the acceptance criterion of the whole measurement layer: every world change this bot causes
        // has to be attributable to something that declared itself. `unattributed` is the number that must be zero
        // before any rule of the form "this lane never places a block" can be believed -- and it was NOT zero before
        // this existed, because MovementPillar placed without writing anything at all.
        //
        // The second is the before-picture of the AKTIV/PARK split, so the new lists can be compared against the
        // machinery they replace rather than against a memory of it.
        if (buildTick - lastCensusTick >= 200) {
            lastCensusTick = buildTick;
            long unattributed = BuildTrace.unattributedWorldChanges();
            logMechanic("World changes: " + BuildTrace.worldChanges() + " total ("
                    + BuildTrace.worldChangeCensus() + ")"
                    + (unattributed == 0 ? "; all attributed"
                            : "; " + unattributed + " UNATTRIBUTED -- something forces a right-click without"
                                    + " declaring itself, see BuildTrace.intendWorldChange"));
            String park = parkShadowCensus();
            if (park != null) {
                logMechanic("Cell lists: " + park);
            }
            // Die Zahl, an der das Loeschen der Wachhunde haengt. Steht hier neben dem Park-Zensus, weil beide
            // dieselbe Frage aus zwei Richtungen beantworten: was WEISS das System ueber seine Zellen, und was
            // TUT es damit.
            String schatten = schattenZensus();
            if (schatten != null) {
                logMechanic(schatten);
            }
            if (wirkungJa + wirkungNein > 0) {
                logMechanic("Geruest-Wirkungspruefung im Schatten: " + (wirkungJa + wirkungNein) + " Proben, "
                        + wirkungJa + " haetten die Zelle geoeffnet, " + wirkungNein + " nicht"
                        + " (sie waehlt noch nicht mit)");
            }
            String fremde = fremdeSpielerZensus();
            if (fremde != null) {
                logMechanic(fremde);
            }
            if (!parkedCells.isEmpty() || cellsWokenByWatchman > 0) {
                int a = 0;
                int b = 0;
                int c = 0;
                for (ParkedCell parked : parkedCells.values()) {
                    switch (parked.reason) {
                        case NO_FACE: a++; break;
                        case NO_STANCE: b++; break;
                        default: c++; break;
                    }
                }
                logMechanic("PARK (shadow): " + parkedCells.size() + " parked (A=" + a + " B=" + b + " C=" + c
                        + "), " + cellsParked + " parked in total, " + cellsWokenByWatchman
                        + " woken by the watchman");
            }
            if (worstReplanCell != null && worstReplansForOneCell > 1) {
                logMechanic("Worst re-planning so far: " + worstReplansForOneCell + " plans for one cell ("
                        + worstReplanCell.x + "," + worstReplanCell.y + "," + worstReplanCell.z
                        + "); the target is exactly one");
            }
        }

        List<BlockState> desirableOnHotbar = new ArrayList<>();
        boolean canOwnPlacementAttempt = isSafeToCancel && ctx.player().onGround();
        Optional<Placement> toPlace = canOwnPlacementAttempt
                ? stickyPlacement(bcc, desirableOnHotbar) : Optional.empty();
        if (toPlace.isPresent() && isSafeToCancel && ctx.player().onGround()) {
            if (ticks > 0) {
                // The click helper is still throttled after a prior action. Hold the committed feet cell instead of
                // falling through to assemble(), which could start a new route and invalidate the committed target.
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            Placement placement = toPlace.get();
            Rotation rot = placement.rot;
            princeps.getLookBehavior().updateTarget(rot, true, AimIntent.PLACE);
            ctx.player().getInventory().setSelectedSlot(placement.hotbarSelection);
            // THE HAND BELONGS TO THIS CELL UNTIL THE CELL IS DONE WITH IT.
            //
            // lockBuilderHotbarSlot has existed since Builder V3 and had no caller anywhere in the engine, so the
            // protection it describes was never in force for the builder that actually ships. Without it the slot
            // chosen one tick can be swapped out by the hotbar fetch on the next, which is why a placement that was
            // planned, aimed and walked to could still arrive holding a different colour. The wrong-item guard a
            // few lines below then holds the click back, the cell is eventually deferred, and the bot stands there
            // -- the "it jst stays here" the customer filmed.
            //
            // Released in releasePlacementTarget, i.e. on completion, deferral or loss of stance, so no slot is
            // ever held by a cell the builder has stopped caring about.
            princeps.getInventoryBehavior().lockBuilderHotbarSlot(placement.hotbarSelection);
            princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            boolean aligned = (ctx.isLookingAt(placement.placeAgainst)
                    && ((BlockHitResult) ctx.objectMouseOver()).getDirection().equals(placement.side))
                    || ctx.playerRotations().isCloseTo(rot, 0.75F, 0.75F);
            // THE HAND MUST HOLD WHAT THE PLAN ASKED FOR, and until now nothing checked that.
            //
            // MEASURED, six aborts in one map art: "wanted white_wool, got pale_oak_planks" and "wanted
            // light_gray_wool, got orange_wool" -- always two materials the picture itself uses. The click was
            // fine, the aim was fine, the cell was fine; the hand was holding a different block. A map art cycles
            // seventeen materials through seven usable hotbar slots, so InventoryBehavior is swapping constantly,
            // and a placement planned one tick can be clicked the next with a different item in that slot.
            //
            // The guard that should have caught it could not, because of the two lines below: expectedItem was
            // read OUT OF THE SLOT at click time and then handed to the helper as the thing to verify. Checking
            // the hand against the hand always passes. The plan has known the answer all along -- placement.desired
            // is the exact state this click has to produce -- so the expectation now comes from there.
            //
            // Refusing costs one tick: the hotbar fetch is already trying to serve this material, the placement is
            // re-derived every tick anyway, and the operator's own workaround for the abort was simply to start
            // again. After a couple of seconds of the slot never coming right the cell is deferred instead, so one
            // awkward material can never hold the whole picture up.
            Item plannedItem = placement.desired.getBlock().asItem();
            Item itemInHand = ctx.player().getInventory().getNonEquipmentItems()
                    .get(placement.hotbarSelection).getItem();
            if (plannedItem != Items.AIR && itemInHand != plannedItem) {
                wrongItemInHandTicks++;
                if (wrongItemInHandTicks % 20 == 1) {
                    logMechanic("Click held back for " + placement.target.getX() + "," + placement.target.getY()
                            + "," + placement.target.getZ() + ": hotbar slot " + placement.hotbarSelection
                            + " holds " + itemInHand + " but this cell wants " + plannedItem
                            + " -- waiting for the swap rather than placing the wrong block");
                }
                if (wrongItemInHandTicks > WRONG_ITEM_PATIENCE_TICKS) {
                    deferCell(new BetterBlockPos(placement.target),
                            "the hotbar never served " + plannedItem + " for this cell");
                    releasePlacementTarget();
                    wrongItemInHandTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            wrongItemInHandTicks = 0;
            if (aligned && liveRayWouldPlaceDesired(placement, bcc)) {
                placementTargetLock.markAimReady(positionKey(placement.target));
                resetOrdinaryGateTracking();
                Item expectedItem = plannedItem;
                princeps.getInputOverrideHandler().getBlockPlaceHelper().expectMainHandPlacement(
                        placement.placeAgainst,
                        placement.side,
                        placement.target,
                        placement.hotbarSelection,
                        expectedItem,
                        // DIESELBE Frage noch einmal, aber im Moment des Klicks. liveRayWouldPlaceDesired simuliert
                        // die Platzierung aus dem LIVE-Strahl und der LIVE-Rotation; hier oben lief sie mit der
                        // Rotation vom Anfang des Ticks, und dazwischen dreht der Look-Prozessor weiter. Bei einem
                        // Kolben entscheidet genau diese Differenz ueber facing.
                        () -> liveRayWouldPlaceDesired(placement, bcc)
                );
                // S8: the one placement the schematic actually asked for. Declared like every other world change, so
                // the census can subtract it and what remains is exactly the helper blocks.
                BuildTrace.intendWorldChange("build", placement.target.getX(), placement.target.getY(),
                        placement.target.getZ(), "against " + placement.placeAgainst.getX() + ","
                                + placement.placeAgainst.getY() + "," + placement.placeAgainst.getZ()
                                + " side=" + placement.side);
                princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                pendingPlacementRequest = placement;
                progressActions.arm(positionKey(placement.target), ctx.world().getBlockState(placement.target), placement.desired);
                pendingPlacementItem = expectedItem;
                pendingPlacementRequestSerial = observedBlockClickSerial;
                // DIE DROSSELUNG IST KEIN FEHLSCHLAG, und ab dem scharfen P5 ist dieser Unterschied ein Bauabbruch
                // wert. BlockPlaceHelper.tick verwirft einen erzwungenen Klick lautlos, solange sein rightClickTimer
                // laeuft (BlockPlaceHelper:67) -- kein Ack, keine Spur. Bis hierher zaehlte jeder dieser Ticks als
                // "der Controller hat eine gueltige Anfrage nicht angenommen"; das ist er nicht, er ist die eigene
                // Klickpause des Bots. Gemerkt statt sofort ausgewertet, weil die Antwort erst im naechsten Tick
                // kommt, wenn observePendingPlacementRequest nachsieht.
                BlockPlaceHelper placeHelper = princeps.getInputOverrideHandler().getBlockPlaceHelper();
                pendingPlacementRequestThrottled = placeHelper.isThrottled();
                if (!pendingPlacementRequestThrottled) {
                    // S8, der einzige Engpass, durch den ein Bauklick geht. clicksSent hatte bis hierher KEINEN
                    // Schreiber -- placementQuality()[0] war immer 0 und damit die Erstversuchsquote
                    // landedRight/clicksSent, die Kennzahl des Owners, nicht berechenbar.
                    clicksSent++;
                }
            } else if (aligned) {
                // The converged live crosshair ray would produce the wrong state (for example, the prediction ran
                // from the crouched eye while the live eye has not settled yet). Count only a
                // CONVERGED aim whose freshly-derived live placement is still invalid. Normal head rotation is not a
                // failed stance; counting it was the f9c76d4 regression that made the 60-tick recovery fire on glass.
                trackGateBlocked(placement);
            } else {
                resetOrdinaryGateTracking();
                long targetKey = positionKey(placement.target);
                double yawError = Mth.degreesDifference(
                        ctx.playerRotations().getYaw(), rot.getYaw());
                double pitchError = ctx.playerRotations().getPitch() - rot.getPitch();
                if (placementTargetLock.owns(targetKey)
                        && placementTargetLock.markAimConverging(
                        targetKey, Math.hypot(yawError, pitchError), AIM_NO_CLOSER_TICKS)) {
                    if (isOrdinaryFullBlock(placement)) {
                        yieldOrRecoverOrdinaryCube(placement);
                    } else {
                        placementTargetLock.startRecovery(placement.stanceKey);
                        logMechanic("Placement aim stopped converging at " + placement.target.getX() + ","
                                + placement.target.getY() + "," + placement.target.getZ()
                                + "; keeping the target and trying another stance");
                    }
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (!canOwnPlacementAttempt && placementTargetLock.isActive()) {
            // A non-cancellable movement/airborne tick is not a failed placement stance. Preserve the route that is
            // already making the player safe; never fall through to assemble() and replace it with another target.
            Goal activeGoal = princeps.getPathingBehavior().getGoal();
            return continueCurrentRoute(activeGoal,
                    activeGoal == null ? PathingCommandType.SET_GOAL_AND_PATH
                            : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        // Interaction pass: a placed block whose only fault is a right-click state (trapdoor/door/gate open, repeater
        // delay, comparator mode, note pitch). Aim at it and right-click WITHOUT sneaking (sneaking suppresses the
        // block's use() — the toggle we want), stepping the state one click every few ticks until it matches.
        Optional<Tuple<BetterBlockPos, Rotation>> toInteract = toInteractNearPlayer(bcc);
        if (toInteract.isPresent() && isSafeToCancel && ctx.player().onGround()) {
            BetterBlockPos pos = toInteract.get().getA();
            Rotation rot = toInteract.get().getB();
            princeps.getLookBehavior().updateTarget(rot, true);
            // actively interacting = progress: keep the idle watchdog from starting another recovery while mid-toggle
            // (the between-click throttle would otherwise fall through to it).
            noProgressTicks = 0;
            long hash = positionKey(pos);
            int clicksLeft = interactionClicks(bcc.bsi.get0(pos.x, pos.y, pos.z),
                    bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos.x, pos.y, pos.z)));
            if (!interactCellTracked || interactCellHash != hash) {
                interactCellHash = hash;
                interactCellTracked = true;
                interactClicksLast = clicksLeft;
                interactNoProgressTicks = 0;
            } else if (clicksLeft >= 0 && clicksLeft < interactClicksLast) {
                interactClicksLast = clicksLeft; // the toggle stepped — real progress, reset the stall clock
                interactNoProgressTicks = 0;
            } else if (++interactNoProgressTicks >= INTERACT_NO_PROGRESS_TICKS) {
                // Never alignable, or the click won't take: yield it temporarily so other cells can advance.
                //
                // Counted in its OWN map, because the ordinary deferral escalation cannot bound this loop. Watched
                // live on etz-basalt: cell 98,-60,93, a pale_oak_door, logged this line 106 times at a flat 40-tick
                // backoff -- the floor, never the 80/160/320/640 the escalation should have produced -- while the
                // build placed NOTHING from t=54400 to t=72400. The delay stays at the floor because
                // cellDeferralCounts keeps being reset, and the only thing that resets it is clearCellRetryHistory,
                // called when a cell is observed COMPLETE. So the cell is flip-flopping between valid and invalid,
                // which is exactly what clicking a door does: `open` is interaction-settable and every right-click
                // toggles it. Six deferrals are therefore never reached, the cell is immortal, and the layer give-up
                // cannot help because the cell simply comes back.
                //
                // This counter is never cleared by an observation, only by resetPlacementTracking, so a cell that
                // cannot converge is retired after INTERACT_GIVE_UPS_BEFORE_RETIRING tries and the build moves on.
                // It can only ever END a loop: a cell whose interaction succeeds leaves through the branch above and
                // never reaches this line.
                if (interactGiveUps == null) {
                    interactGiveUps = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
                }
                int giveUps = interactGiveUps.addTo(hash, 1) + 1;
                if (giveUps >= INTERACT_GIVE_UPS_BEFORE_RETIRING) {
                    // PARKEN STATT IN DEN RUHESTAND SCHICKEN. Der Unterschied ist der Rueckweg: die Ruhestandsmenge
                    // wurde erst durch einen Retry-Sweep geleert (den es nicht mehr gibt), PARK oeffnet der Waechter,
                    // sobald sich an einem Nachbarn die Flaechenlage aendert. Grund B ist die naechstliegende
                    // Lesart -- die Zelle HAT eine Flaeche, aber von keiner erreichten Stellung aus bewirkt der
                    // Klick etwas. Kein Grund C: erreichbar war sie ja, der Schalter geht nur nicht.
                    parkCell(new BetterBlockPos(pos), ParkReason.NO_STANCE);
                    if (incorrectPositions != null) {
                        incorrectPositions.remove(new BetterBlockPos(pos));
                    }
                    logMechanic("Parking cell " + pos.x + "," + pos.y + "," + pos.z + " after " + giveUps
                            + " interaction attempts that made no progress; its toggle cannot be reached by clicking");
                    if (placementTargetLock.owns(hash)) {
                        releasePlacementTarget();
                    }
                    interactCellHash = -1;
                    interactCellTracked = false;
                    interactClicksLast = -1;
                    interactNoProgressTicks = 0;
                    return null;
                }
                deferCell(pos, "interaction made no progress for " + interactNoProgressTicks + " ticks (attempt "
                        + giveUps + " of " + INTERACT_GIVE_UPS_BEFORE_RETIRING + ")");
                interactCellHash = -1;
                interactCellTracked = false;
                interactClicksLast = -1;
                interactNoProgressTicks = 0;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            // Click only when the throttle is up AND we're genuinely standing: a lingering crouch from a prior
            // placement would suppress use(). Between clicks we still hold here (return below), never idling.
            HitResult liveHit = ctx.objectMouseOver();
            boolean lookingAtInteraction = liveHit != null && liveHit.getType() == HitResult.Type.BLOCK
                    && ((BlockHitResult) liveHit).getBlockPos().equals(pos);
            if (ticks <= 0 && lookingAtInteraction && !ctx.player().isCrouching()) {
                BuildTrace.intendWorldChange("interact", pos.x, pos.y, pos.z,
                        "stepping the state, " + clicksLeft + " click(s) left");
                progressActions.arm(positionKey(pos), bcc.bsi.get0(pos), bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos)));
                princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                ticks = 5; // one click per few ticks so each registers as a single state step
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (Princeps.settings().allowInventory.value) {
            // Demand has two sources and only the first was ever heard: the scan box around the bot, and the cells in
            // the work set that are starving for a material sitting in the backpack. See appendStarvedWorkSetMaterials.
            List<BlockState> hotbarDemand = new ArrayList<>(desirableOnHotbar);
            appendStarvedWorkSetMaterials(bcc, hotbarDemand);
            if (electedCell != null && !electedBreak) {
                BlockState wanted = bcc.getSchematic(electedCell.x, electedCell.y, electedCell.z, bcc.bsi.get0(electedCell));
                if (wanted != null) {
                    hotbarDemand.clear();
                    hotbarDemand.add(wanted);
                }
            }
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : hotbarDemand) {
                for (int i = 0; i < 9; i++) {
                    if (itemCanPlaceBlock(approxPlaceable.get(i), desired)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            // Rate-limited on the BUILD tick, not on noProgressTicks. noProgressTicks is reset to zero by any
            // navigation progress, so on a bot that is walking while short of materials the "% 60" never applied and
            // this printed on every single tick -- 1944 lines in one 3300-tick lighthouse run, drowning the log it
            // was supposed to explain.
            if (!noValidHotbarOption.isEmpty() && buildTick % 60 == 0) {
                StringBuilder wanted = new StringBuilder();
                java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
                for (BlockState desired : noValidHotbarOption) {
                    distinct.add(blockName(desired));
                }
                for (String name : distinct) {
                    if (wanted.length() > 0) {
                        wanted.append(", ");
                    }
                    wanted.append(name);
                }
                // The hotbar census is the missing half. "3 slots in use" does not say WHICH material is squatting on
                // a slot the current layer needs, and without that the next reader is left guessing exactly as long.
                StringBuilder hotbar = new StringBuilder();
                for (int i = 0; i < 9; i++) {
                    if (i > 0) {
                        hotbar.append(' ');
                    }
                    ItemStack st = ctx.player().getInventory().getNonEquipmentItems().get(i);
                    hotbar.append(i).append('=').append(st.isEmpty() ? "-"
                            : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
                }
                // DISTINCT slots, not usefulSlots.size(). usefulSlots gets one entry per WANTED STATE -- the loop above
                // adds the first slot that serves each and moves on -- so ten states served by slot 0 put ten 0s in it.
                // The printed ratio was therefore a material count wearing a slot count's units: a basalt run reported
                // "36/9 slots useful", which reads as an impossibility and hid the only number that decides anything.
                //
                // It decides everything because usefulSlots is the EVICTION VETO: attemptToPutOnHotbar below refuses
                // any slot this collection contains. Once all nine distinct slots serve something wanted, no slot may
                // be freed, and a material that is not already on the bar can never get onto it -- however badly one
                // cell needs it. etz-basalt wants 36 materials and the bar holds 9, so this is not a corner case.
                java.util.TreeSet<Integer> distinctUseful = new java.util.TreeSet<>(usefulSlots);
                logMechanic("Hotbar fetch needed for " + wanted
                        + " (" + distinctUseful.size() + "/9 distinct slots vetoed from eviction"
                        + (distinctUseful.size() >= 9 ? " -- NO SLOT CAN BE FREED" : "")
                        + ", " + usefulSlots.size() + " wanted states served)"
                        + "; hotbar [" + hotbar + "]"
                        + "; last swap refusal: " + princeps.getInventoryBehavior().lastSwapRefusal());
            }
            // Iterate by DEMAND first, backpack slot second. The old order fetched whichever backpack slot happened
            // to match anything wanted, so with more wanted materials than hotbar slots it fetched an arbitrary one,
            // evicted something else that was equally wanted, and did the same again next tick -- thrashing without
            // ever serving the cell actually being built. desirableOnHotbar is ordered by proximity to the bot, so
            // taking the first entry serves the nearest work first and the set converges.
            boolean fetchAttempted = false;
            // InventoryBehavior refuses swaps while a right click is forced. Fetching is preparation for a click,
            // so release the stale click before asking it to move the required material onto the hotbar.
            princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            // A strict Map-Art slice can require more materials than there are hotbar slots. If all nine slots are
            // protected by nearby demand, the material for the cell currently being built must still be allowed in.
            // THE CLICK MUST NOT BLOCK ITS OWN PREPARATION. InventoryBehavior refuses every swap while a
            // right-click is forced, and it returns BEFORE advancing its move clock, so the rate limiter then
            // refuses forever after. The builder holds exactly that click while placing -- so "place" and "fetch
            // what placing needs" locked each other out, and the visible result is a bot that stands, twitches and
            // never finishes. Releasing it here makes fetching a PRECONDITION of the click instead of its rival:
            // no block is clicked while the material for it is still on its way to the hand.
            princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            // AND THE VETO MUST NOT OUTRANK THE CELL BEING BUILT. usefulSlots protects every slot serving anything
            // wanted nearby; once all nine do, no slot can be freed and a tenth material can never reach the bar --
            // the code above says so itself and prints NO SLOT CAN BE FREED. A picture uses up to thirty-two
            // materials against nine slots, so that is the ordinary case rather than a corner one. When the bar is
            // fully vetoed, the material the build is actually waiting on wins.
            java.util.function.Predicate<Integer> evictionVeto =
                    new java.util.TreeSet<>(usefulSlots).size() >= 9 ? slot -> false : usefulSlots::contains;
            outer:
            for (BlockState desired : noValidHotbarOption) {
                for (int i = 9; i < 36; i++) {
                    if (itemCanPlaceBlock(approxPlaceable.get(i), desired)) {
                        fetchAttempted = true;
                        if (princeps.getInventoryBehavior().attemptToPutOnHotbar(i, evictionVeto)) {
                            hotbarFetchRefusedTicks = 0;
                            break outer;
                        }
                        // Awaiting an inventory move, so pause -- but BOUNDED. This return jumps over the rest of
                        // onTick, which is where stance recovery, deferral and every stall report live, so a refusal
                        // that repeats is a refusal nothing can see or escalate. That is not hypothetical: it is how
                        // lighthouse spent 1800 ticks at 69,-58,69 with the lock released, 16 cells outstanding, and
                        // no target -- pausing for a swap that was never going to be granted.
                        //
                        // After the grace period the pause is abandoned and the tick continues. The cells wanting the
                        // unreachable material will then be deferred and eventually retired by the normal path, and
                        // the build gets on with the cells whose materials ARE in hand.
                        // A swap is one placement's preparation, not a reason to freeze the whole builder. Continue
                        // the tick and retry the cell once its material has reached the hand.
                        // NO PAUSE FOR A SWAP. Waiting was the last third of the ordering problem: fetching a
                        // material is a PREPARATION STEP of one placement, not a reason to stop the whole process.
                        // Pausing costs on two levels -- it freezes stance recovery, deferral and every stall
                        // report for as long as it lasts, and a strict slice order needs a specific colour far
                        // more often than a free choice does, so the pauses stop being rare. Measured: with the
                        // rigid five-wide order the build halted after 25 cells waiting for white_wool, while the
                        // same build with a free choice ran 1181 cells without stopping once.
                        //
                        // Now the tick simply carries on. The cell whose colour is still travelling is not placed
                        // this tick and is picked up again as soon as it is in hand; everything else in the tick --
                        // walking, stance work, the reports -- keeps running.
                        hotbarFetchRefusedTicks++;
                        if (hotbarFetchRefusedTicks % 200 == 0) {
                            logMechanic("Still cannot get " + blockName(desired) + " onto the hotbar after "
                                    + hotbarFetchRefusedTicks + " ticks ("
                                    + princeps.getInventoryBehavior().lastSwapRefusal()
                                    + "); building on without it rather than pausing forever");
                        }
                        break outer;
                    }
                }
            }
            if (!fetchAttempted) {
                hotbarFetchRefusedTicks = 0;
            }
        }

        PathingCommand recovery = placementRecoveryCommand(bcc, calcFailed);
        if (recovery != null) {
            return recovery;
        }

        // A failed generic search is not a verdict on the separately licensed, already-clear water step.
        // Read the existing step before judging a one-high remainder. Construct its command only when it wins:
        // excavationLevelPathingCommand can perform a bridge repair and must not run during a speculative check.
        boolean shallowSurface = shallowExcavation();
        BetterBlockPos shallowWetStep = shallowSurface ? ordinaryWetApproachStep(bcc, isSafeToCancel) : null;

        // Reaching here means no build action fired. Navigation toward an unsatisfied goal is legitimate progress:
        // never punish a distant glass cell merely because the walk lasts longer than six seconds.
        if (recursions == 0 && incorrectPositions != null && !incorrectPositions.isEmpty()) {
            int completed = observedCompleted == null ? 0 : observedCompleted.size();
            BetterBlockPos feet = ctx.playerFeet();
            Goal activeGoal = princeps.getPathingBehavior().getGoal();
            boolean completedChanged = completed != lastCompletedSize;
            if (completedChanged) {
                resetNavigationProgressTracking();
            }
            boolean navigationProgress = navigationMadeProgress(feet, activeGoal, calcFailed);
            // Reachable ordinary cuts have already had their turn above. A complete one-high selection under a
            // fixed roof cannot manufacture another standing-height route by repeatedly releasing the same goal.
            // Keep any accessible work, and end explicitly when the actual search failed without new world work.
            boolean shallowRouteVerdictReady = calcFailed && !completedChanged && toBreak.isEmpty()
                    && isSafeToCancel && (ctx.player().onGround() || ctx.player().isInWater())
                    && !ctx.player().isUsingItem()
                    && !(princeps.getSurvivalBehavior() != null && princeps.getSurvivalBehavior().ownsInventory());
            BetterBlockPos covered = shallowRouteVerdictReady ? shallowCoveredWork(bcc) : null;
            if (ShallowExcavationPolicy.stopAfterFailedRoute(shallowSurface, calcFailed,
                    completedChanged, covered != null, shallowWetStep != null)) {
                abortBuild(Ending.LAYER_UNBUILDABLE, "AutoDig cannot reach the remaining one-block-high area",
                        java.util.List.of("A fixed roof blocks standing headroom above " + covered.toShortString() + ".",
                                "No safe route was found. The remaining cells are unfinished and the fixed roof stays protected."));
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            // THE SECOND TRIGGER, AND IT IS DELIBERATELY OUTSIDE THE BRANCH BELOW.
            //
            // The recovery below can only be reached when navigationMadeProgress says NO -- and walking in a circle
            // says YES. Measured on the doors scenario, run e399defa: 10 of 24 iron doors unplaced, and while nothing
            // had been built for 800 ticks the log read
            //
            //     No cell has become correct for 800 ticks ... target=none, pathing=true, noProgressTicks=2
            //
            // noProgressTicks never left 0-5, so a threshold of UNSTICK_AT_TICKS = 30 could not be reached. Why it
            // could not: with nothing placeable from where the bot stands, assemble builds ONE composite goal over
            // every remaining cell at once (ten GoalAdjacent/GoalBlock groups in that run). Its centre of gravity is
            // a spot equidistant from all of them -- 73..75,-60,73 -- and from there not one of them is reachable.
            // The bot shuffles inside half a block, the goal is rebuilt constantly, and navigationMadeProgress hands
            // out its "one grace tick for a genuinely new route" over and over.
            //
            // The consequence was total: 14842 stance searches, found=6 every time, and ZERO AIM, ZERO CLICK, ZERO
            // DEFER in the whole run. No committed target means no deferral, so no escalation, no retirement, and no
            // layer give-up either -- the cell could not even step aside. The run ended on the bench's stall detector.
            //
            // So the trigger is hung on the one counter that cannot be faked. {@link #narrateIfNoCellHasCompleted}
            // already says why it is the right one: "This counts the one thing that cannot be faked -- a cell
            // actually becoming correct". In that same run it reported 200/400/600/800 reliably while noProgressTicks
            // sat at 2. The working instrument was present all along; the recovery was wired to the broken one.
            //
            // Nothing new is built here. This commits a target and walks to a stance that works for it, using the
            // machinery that already exists -- and once a target IS committed,
            // enforcePlacementTargetDeadline can finally do its job and defer the cell if the stance does not pay off.
            // COMMIT IMMEDIATELY, DO NOT DELIBERATE AGAIN. Reaching this point already means no build action fired,
            // i.e. nothing is placeable from where the bot stands -- so the answer "which cell, and from where" is
            // needed NOW, not after a timeout. It used to wait 60 quiet ticks and that made this a
            // safety net rather than the rule; the doors run showed what the rule was instead: target=none for 2186 of
            // 2340 ticks, 14842 stance searches, 0 clicks, and the same six workable stances re-derived every tick
            // without ever being used. Searching is not free and re-searching an unchanged answer is pure loss.
            //
            // Firing every tick is safe because the LOCK is the gate: once acquirePlacementRecovery takes it,
            // isActive() is true and this branch cannot re-enter until the cell is placed or
            // enforcePlacementTargetDeadline tears the lock off and defers it. So this commits once, walks there, and
            // executes -- which is the whole of "he has already found something that works, so just do it".
            //
            // AND IT IS GATED AGAIN, because firing every tick was measured worse where stances are scarce. This path
            // asks placementStancesFor with placementTargetLock::mayTryStance, so every pass CONSUMES a candidate by
            // marking it tried. At 60x the frequency the pool is spent 60x faster, and the cell then reports "all
            // stances exhausted" for a reason that is purely an artefact of how often it was asked:
            //
            //   facings, ungated:  57/96 and 50/96, bpm 13.0 / 18.3, stanceless 126   (reproduced twice)
            //   facings, gated:    96/96,           bpm 24.2,        stanceless  64
            //
            // Seven cells on y=-59 were left, and a layered build cannot pass them, so they also cost the 32 stone
            // cells of the row above. The doors scenario meanwhile IMPROVED with the ungated version (1040 -> 540
            // ticks, payoff 100%), because there every cell is reachable from many sides and spending the pool costs
            // nothing. So the trigger is right and its frequency was wrong: commit promptly, but not before the
            // opportunistic scan has had a fair chance, or the commitment eats the very options it needs.
            // THE WATCHDOG IS GONE, 05.08.2026, by the owner's decision: "Es gibt nur den Hauptweg und keinen anderen.
            // Da gibt es keinen Plan B." Both time-based commitments -- this one at UNSTICK_AFTER_QUIET_TICKS = 60 and
            // the one at UNSTICK_AT_TICKS = 30 below -- are removed. What remains is the main path: every cell has its
            // standing positions computed, only a proven position is ever walked to, and a cell with none is postponed
            // until its neighbour faces change.
            //
            // WHY IT HAD TO GO, and the third reason is the one that settled it:
            //  1. IT WAITED. In run f28e33ae (oriented, 7/7) two waits of 55 and 64 ticks were 54% of the whole run,
            //     both of them the 30-tick counter running down while cell 69,-60,67 already had 13 proven stances and
            //     the bot was standing on one of them.
            //  2. IT DISAGREED WITH THE SEARCH. It committed through nearestRecoverablePlacementTarget, a different
            //     filter set, so it routed to cells the main path had judged unbuildable -- two mechanisms, two answers,
            //     which is exactly what the owner's design forbids.
            //  3. IT PLACED WRONG BLOCKS. Run cd0798ae, 43 wrong landings against 2703 correct. Two the owner watched
            //     live: 77,-59,75 wanted sticky_piston[facing=up] and got facing=east from an aim of pitch 42.1, and
            //     103,-59,81 got facing=north from pitch 40.7. A piston takes its facing from the DOMINANT axis of the
            //     look, so facing=up needs pitch > 45 -- below that the horizontal component wins and a horizontal
            //     facing is not bad luck but arithmetic. Both clicks carried act=target-no-stance, i.e. the cell was
            //     committed with NO planned stance and aimed live. The main path would have simulated pitch 42.1,
            //     obtained facing=east, and rejected the position.
            //
            // Kept deliberately: acquirePlacementRecovery / placementRecoveryCommand themselves, because they are the
            // machinery that commits a target and routes to an EXACT stance cell with GoalBlock. Fix 1 below still uses
            // them -- but only ever for a cell whose stance the search has already proven from where the bot stands.

            // THE SECOND PASS, and its trigger is the only thing that may open it: the walk-only search came back with
            // nothing at all. Not "the detour was long" -- nothing. Jumping and arbitrarily long routes were already on
            // the table in the first pass, so reaching here means the cell is genuinely unreachable on foot as the
            // world stands, which is precisely the owner's condition for allowing a helper block.
            //
            // Deliberately keyed on calcFailed rather than on a tick count: a timer would open the permission for a
            // search that simply had not finished, and then the price is back to being the only thing deciding.
            // SCAFFOLD-PASS IST WEG, und mit ihm der Mischbetrieb. Was hier stand, war eine ZWEITE, unabhaengige
            // Entscheidung darueber, ob ein Hilfsblock erlaubt ist: ein einziges calcFailed oeffnete sie, danach war
            // sie fuer den Rest des Baus offen (scaffoldPassAllowed wurde nur beim Zellenwechsel zurueckgesetzt).
            // Die Spezifikation kennt genau EINE solche Entscheidung, und das ist die Bahn -- "Ein Weg, der ohne
            // Hilfsbloecke geplant wurde, wird auch ohne Hilfsbloecke gefahren. Es gibt keinen Mischbetrieb."
            // Gesetzt wird das Feld jetzt ausschliesslich aus der Bahnwahl, siehe laneForCurrentCell.
            if (completedChanged || navigationProgress) {
                lastCompletedSize = completed;
                noProgressTicks = 0;
            } else {
                // SOFORT, not after 30 ticks. Standing on a position the search has already proven is the one case where
                // there is nothing left to compute and nothing left to walk -- so waiting for a watchdog to notice is
                // pure lost time. Placed BEFORE the counter branch below so it cannot be reached by the wait at all.
                if (!placementTargetLock.isActive()) {
                    BetterBlockPos provenHere = cellAlreadyProvenFromHere(feet);
                    if (provenHere != null
                            && acquirePlacementRecovery(provenHere, "standing on a stance already proven for this cell")) {
                        PathingCommand started = placementRecoveryCommand(bcc, false);
                        if (started != null) {
                            return started;
                        }
                    }
                }
                noProgressTicks++;
                // The 30-tick commitment stood here and is removed with the 60-tick one above -- see the note there for
                // the three measured reasons. noProgressTicks itself is kept: it still feeds the deferral and route
                // rotation below, which give up on a CELL rather than choosing a target, and those are not a second
                // target selection.
                boolean committedWrongBlockRouteFailed = placementTargetLock.isActive()
                        && committedPlaceTarget != null
                        && !MovementHelper.isReplaceable(
                        committedPlaceTarget.x, committedPlaceTarget.y, committedPlaceTarget.z,
                        bcc.bsi.get0(committedPlaceTarget), bcc.bsi)
                        && calcFailed && noProgressTicks >= UNSTICK_AT_TICKS;
                if (committedWrongBlockRouteFailed) {
                    BetterBlockPos stalled = new BetterBlockPos(committedPlaceTarget);
                    deferCell(stalled, calcFailed
                            ? "path calculation repeatedly failed while routing to the wrong placed block"
                            : "route to the wrong placed block made no observable progress", bcc);
                    noProgressTicks = 0;
                    resetNavigationProgressTracking();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                boolean rotateFailedRoute = electedCell == null && !placementTargetLock.isActive()
                        && ((calcFailed && noProgressTicks >= UNSTICK_AT_TICKS) || noProgressTicks >= 120);
                if (rotateFailedRoute) {
                    BetterBlockPos stalled = nearestRoutableUnresolved(feet, bcc);
                    String why = calcFailed
                            ? "path calculation repeatedly failed"
                            : "route made no observable node or goal-distance progress";
                    if (stalled == null) {
                        // Nothing ROUTABLE is left, and that used to be the end of the line: this branch simply did
                        // nothing, the tick fell through, and the same nothing happened on every subsequent tick. The
                        // build then sat in a state it reported honestly and could not leave -- basalt spent 1900
                        // ticks printing "no actionable progress" against two cells, one soul_sand and one door, with
                        // 13,999 cells left and the layer 934/936 complete.
                        //
                        // "I cannot route to any of them" is not a reason to stop deciding. Fall back to the nearest
                        // unresolved cell whatever its routability, defer it, and let the ordinary escalation retire
                        // it so the layer can finish. The retry sweep comes back for it once the rest stands, which
                        // is also when it is most likely to have become reachable.
                        // ...und der Rueckfall auf "verschieben" war selbst die Sackgasse. deferCellWeighted, die
                        // Endstation aller deferCell-Ueberladungen, gibt die Sperre frei, druckt RELEASE und
                        // loggt gelegentlich -- mehr nicht. Kein Strike, keine Frist, keine Stilllegung; der
                        // Parameter weight wird entgegengenommen und nie benutzt. Die "ordinary escalation", auf
                        // die der Absatz darueber verweist, gibt es seit dem Ausbau der Uhren und Retry-Sweeps
                        // nicht mehr, und die Aufrufer glauben sie weiter.
                        //
                        // GEMESSEN, Lauf cee7e964: dieselbe Zelle 98,-59,97 (ein Hebel, dessen Halt noch fehlt)
                        // elfmal im Abstand von genau 120 Ticks freigegeben, 1200 Ticks ohne eine einzige
                        // Platzierung, dann ENDING CANCELLED durch Zeitablauf. Der Bau steht bei 2747 Zellen --
                        // dieselbe Wand wie in den drei Laeufen davor (2724, 2749, 2752).
                        //
                        // Die Spezifikation kennt fuer genau diese Lage keine Verschiebung, sondern das Parken:
                        // eine Zelle, die die Welt derzeit nicht hergibt, wird mit ihrem Grund geparkt, der
                        // Waechter weckt sie, sobald sich ein Nachbar aendert, und P6a faellt am Ebenenende ein
                        // Urteil darueber. Grund A, denn genau das ist der Befund -- kein Nachbar bietet, woran
                        // der Block haengen koennte.
                        //
                        // NICHT GEMESSEN. Der Bench war beim Schreiben belegt; der statische Beweis oben traegt,
                        // die Wirkung auf den Lauf nicht. Erste Messung, sobald er frei ist.
                        // NUR, WENN ES EINE AUSSAGE UEBER DIE WELT IST. nearestIncorrect liefert die naechste
                        // unaufgeloeste Zelle, nicht zwingend die mit dem fehlenden Halt -- und eine Zelle, die
                        // gerade nur nicht routbar ist, gehoert nach der Spezifikation ausdruecklich NICHT
                        // geparkt: "Ein Timeout ist NICHT Grund C." Deshalb entscheidet hier canSurvive und
                        // nichts anderes: ein Hebel ohne Halt, eine Fackel ohne Wand, ein Draht ohne Boden sind
                        // Grund A, alles uebrige bleibt in AKTIV und wird neu gewaehlt.
                        BetterBlockPos festgefahren = nearestIncorrect(feet);
                        BlockState willDorthin = festgefahren == null ? null
                                : bcc.getSchematic(festgefahren.x, festgefahren.y, festgefahren.z,
                                        bcc.bsi.get0(festgefahren.x, festgefahren.y, festgefahren.z));
                        boolean haltFehlt = willDorthin != null
                                && !willDorthin.canSurvive(ctx.world(), festgefahren);
                        if (festgefahren != null && haltFehlt) {
                            parkCell(festgefahren, ParkReason.NO_FACE);
                            BuildTrace.cell(buildTick, "PARK-STUCK", festgefahren.x, festgefahren.y, festgefahren.z,
                                    "nothing routable is left anywhere; parked instead of released so the watchman "
                                            + "can wake it when a neighbour changes");
                            noProgressTicks = 0;
                            resetNavigationProgressTracking();
                            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                        }
                        // Sonst wie bisher: freigeben und neu waehlen lassen. Das aendert zwar nachweislich
                        // wenig, aber es ist das, was die Spezifikation fuer einen Timeout vorsieht -- und ein
                        // Rueckfall auf gar nichts waere die Sackgasse, die der Absatz oben schon einmal
                        // beschrieben hat.
                        stalled = festgefahren;
                        why = why + " and no unresolved cell is routable at all";
                    }
                    if (stalled != null) {
                        schattenUrteil("Wachhund Route", stalled, bcc);
                        deferCell(stalled, why, bcc);
                        noProgressTicks = 0;
                        resetNavigationProgressTracking();
                        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                }
                if (noProgressTicks >= 120 && noProgressTicks % 120 == 0) {
                    BetterBlockPos diagnostic = committedPlaceTarget != null
                            ? committedPlaceTarget : nearestIncorrect(feet);
                    schattenUrteil("Wachhund Stillstand", diagnostic, bcc);
                    String detail = diagnostic == null ? "" : "; nearest unresolved "
                            + diagnostic.x + "," + diagnostic.y + "," + diagnostic.z + " — "
                            + diagnoseCell(diagnostic, bcc);
                    logMechanic("Build has made no actionable progress for " + noProgressTicks
                            + " ticks; all unresolved cells are retained and path/stance recovery will keep retrying"
                            + detail);
                }
            }
        }

        PathingCommand wetApproach = shallowSurface
                ? shallowWetStep == null ? null : excavationLevelPathingCommand(ctx.playerFeet(), shallowWetStep)
                : ordinaryWetApproachCommand(bcc, isSafeToCancel);
        if (wetApproach != null) return wetApproach;
        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                PathingCommand integrity = ordinaryExcavationIntegrityCommand(bcc, isSafeToCancel, true);
                if (integrity != null) return integrity;
                if (hasDeferredCells()) {
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                // HIER UND NUR HIER BEGINNT DIE GERUEST-PHASE. Dass wir an dieser Zeile stehen, IST die
                // Bedingung, die der Eigentuemer genannt hat: assemble hat zweimal nichts gefunden, einmal mit
                // den neun Feldern der Schnellleiste und einmal mit dem ganzen Inventar. Es ist also nichts mehr
                // zu setzen und nichts mehr abzubauen. Steht dann noch etwas in der PARK-Liste, ist genau der
                // Moment gekommen, in dem ein Hilfsblock erlaubt wird -- und keinen Tick frueher.
                if (openScaffoldPhase(bcc)) {
                    // Ab jetzt gibt es wieder ein Soll: die Geruestzelle selbst. Der naechste Tick findet sie
                    // ueber den ganz gewoehnlichen Weg.
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                String resourceBlocker = unresolvedResourceBlocker(bcc, approxPlaceable);
                if (resourceBlocker == null) {
                    if (noProgressTicks == 0 || noProgressTicks % 120 == 0) {
                        logMechanic("No safe action is currently derivable; keeping the build unresolved and retrying");
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                finishWith(Ending.MATERIALS_MISSING, "Unable to continue: " + resourceBlocker,
                        java.util.Collections.singletonList("The build is paused; supply the material and start it again."));
                pause();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        PathingCommand route = finishBuilderRoute(goal, electedGoal == null ? goal : electedGoal,
                PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
        return startCleanupEscape(bcc, isSafeToCancel) ? cleanupHold() : route;
    }

    /** All new builder routes service the same target-bound probe, including early recovery returns.
     * The work question excludes opportunistic cleanup fallbacks: reaching another helper is not a proof that
     * the selected placement stance can be reached. The actual router still receives its full navigation goal. */
    private PathingCommand finishBuilderRoute(Goal routeGoal, Goal workGoal, PathingCommandType type,
                                             BuilderCalculationContext context) {
        PlatformTraverseApproach platform = platformTraverseApproach;
        boolean platformStep = platform != null && platform.target.equals(electedCell);
        if (platformStep) {
            routeGoal = platformTraverseApproach.goal;
            workGoal = platformTraverseApproach.goal;
        }
        driveLanesInShadow(workGoal);
        // A consumed negative answer can withdraw this exact offer and clear its selected work.
        // Never retain the marked goal while silently replacing its no-mining context with ordinary rules.
        if (platformStep && (platformTraverseApproach != platform || !platform.target.equals(electedCell))) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        PathingCommand initialApproach = initialExcavationApproach(routeGoal);
        if (initialApproach != null) return initialApproach;
        Lane lane = platformStep ? Lane.A_NO_PLACING : laneForCurrentCell(electedCell);
        // Consuming A=NONE can change permissions in this very tick. Never dispatch the old A snapshot for B.
        BuilderCalculationContext routeContext = context.lane == lane && context.platformApproach == platformTraverseApproach
                ? context : new BuilderCalculationContext(lane);
        scaffoldPassAllowed = lane == Lane.B_HELPERS_ALLOWED;
        return new PathingCommandContext(routeGoal, type, routeContext);
    }

    private enum CleanupStage { CANDIDATE, PREFIX_PROOF, PRESENT_PROOF, REMOVED_PROOF,
        WALK_PREFIX, PLACE, WAIT_PLACE, WALK_PRESENT, DOWNWARD, WALK_REMOVED, BLOCKED }

    private record CleanupBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** A finite local action chain. Its three search contexts never escape into the movement executor. */
    private final class CleanupEscape {
        final BetterBlockPos owner = electedCell;
        final Goal ownerGoal = electedGoal;
        final Object world = ctx.world();
        final ISchematic model = realSchematic == null ? schematic : realSchematic;
        final BlockPos buildOrigin = new BlockPos(origin);
        final BetterBlockPos start = ctx.playerFeet();
        BetterBlockPos proofStart = start;
        final BuilderCalculationContext initialRules;
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        final PathProbe probe = new PathProbe("cleanup-one-helper");
        final CleanupBounds bounds;
        final Map<Long, BlockState> snapshot = new HashMap<>();
        final ArrayDeque<BetterBlockPos> candidates = new ArrayDeque<>();
        final ArrayDeque<BetterBlockPos> stances = new ArrayDeque<>();
        final List<BetterBlockPos> floors = new ArrayList<>();
        final List<BetterBlockPos> originalSupports = new ArrayList<>();
        CleanupStage stage = CleanupStage.CANDIDATE;
        BetterBlockPos helper, stance, floor;
        BlockState material;
        Goal queryGoal;
        BetterBlockPos queryStart;
        CleanupEscapeContext queryContext;
        boolean prefixProved, presentProved, removedProved, placed;
        int placementCenteringTicks, placementSettleTicks;
        double placementFailedCenterDistanceSq = Double.POSITIVE_INFINITY;
        boolean placementAimReported;
        boolean placementMaterialWaitReported;
        boolean worldInvalidated;
        Placement placementRequest;
        CleanupEscapeContext realRouteContext;
        Goal realRouteGoal;
        BetterBlockPos realRouteDestination;
        boolean realRouteMayMine;
        String blockedReason;

        CleanupEscape(BuilderCalculationContext bcc) {
            initialRules = bcc;
            int radius = (int) Math.ceil(ctx.playerController().getBlockReachDistance()) + 1;
            bounds = new CleanupBounds(start.x - radius, start.x + radius,
                    Math.min(owner.y - 1, start.y - bcc.maxFallHeightNoWater - 2), start.y + 3,
                    start.z - radius, start.z + radius);
            // A margin covers jump overshoot/head checks. Any unobserved/unloaded area refuses the episode.
            for (int x = bounds.minX - 5; x <= bounds.maxX + 5; x++) {
                for (int z = bounds.minZ - 5; z <= bounds.maxZ + 5; z++) {
                    if (!bcc.bsi.worldContainsLoadedChunk(x, z)) { block("unloaded local proof area"); return; }
                    for (int y = bounds.minY - 2; y <= bounds.maxY + 3; y++) {
                        snapshot.put(BlockPos.asLong(x, y, z), ctx.world().getBlockState(new BlockPos(x, y, z)));
                    }
                }
            }
            List<BetterBlockPos> ordered = new ArrayList<>();
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    for (int y = bounds.minY + 1; y < start.y; y++) {
                        BetterBlockPos at = new BetterBlockPos(x, y, z);
                        if (!dryClear(at, bcc) || !MovementHelper.canWalkOn(bcc, x, y - 1, z)) continue;
                        // The permanent destination must stay valid after removing all original helper debt.
                        if (cleanupPermanentFloor(at.below(), bcc)
                                && y <= owner.y && cleanupCanSwingFrom(at, owner)) floors.add(at);
                        if (cleanupMayOccupy(at, bcc) && !at.equals(owner)) ordered.add(at);
                    }
                }
            }
            ordered.sort(Comparator.<BetterBlockPos>comparingInt(p -> -p.y)
                    .thenComparingDouble(p -> p.distSqr(owner)).thenComparingInt(p -> p.x).thenComparingInt(p -> p.z));
            candidates.addAll(ordered);
            if (floors.isEmpty()) block("no independent permanent cleanup floor");
        }

        void block(String why) {
            if (stage == CleanupStage.BLOCKED) return;
            probe.cancel();
            stage = CleanupStage.BLOCKED;
            blockedReason = why;
            princeps.getInputOverrideHandler().getBlockPlaceHelper().clearExpectedPlacement();
            BuildTrace.cell(buildTick, "CLEANUP-ESCAPE-BLOCKED", owner.x, owner.y, owner.z, why);
        }

        boolean identityCurrent() {
            return world == ctx.world() && model == (realSchematic == null ? schematic : realSchematic)
                    && buildOrigin.equals(origin) && owner.equals(electedCell) && ownerGoal == electedGoal
                    && !buildInRows && System.nanoTime() < deadline;
        }

        boolean rulesCurrent(BuilderCalculationContext now) {
            return initialRules.maxFallHeightNoWater == now.maxFallHeightNoWater
                    && initialRules.allowDownward == now.allowDownward && initialRules.allowParkour == now.allowParkour
                    && initialRules.allowParkourAscend == now.allowParkourAscend
                    && initialRules.allowParkourPlace == now.allowParkourPlace
                    && initialRules.allowBreak == now.allowBreak && initialRules.allowBreakAnyway.equals(now.allowBreakAnyway)
                    && initialRules.canSprint == now.canSprint;
        }

        void serverChanged(BlockPos at, BlockState state) {
            BlockState before = snapshot.get(at.asLong());
            if (before != null && !(placed && at.equals(helper)) && !before.equals(state)) worldInvalidated = true;
        }

        boolean worldCurrent() { return !worldInvalidated; }

        /** Full comparison only at proof/action boundaries; ordinary route ticks use packet invalidation. */
        boolean verifyWorld() {
            if (worldInvalidated || world != ctx.world()) return false;
            for (Map.Entry<Long, BlockState> entry : snapshot.entrySet()) {
                BlockPos at = BlockPos.of(entry.getKey());
                if (placed && at.equals(helper)) continue; // the sole declared mutation of this episode
                if (!ctx.world().hasChunkAt(at) || !entry.getValue().equals(ctx.world().getBlockState(at))) {
                    worldInvalidated = true; return false;
                }
            }
            return true;
        }
    }

    /** Strict dry navigation. Ordinary Lane A alone still permits template placement and mining. */
    final class CleanupEscapeContext extends BuilderCalculationContext {
        private final CleanupBounds bounds;
        private final BlockPos mineOnly;
        private final BlockState mineState;
        private final BuilderCleanupDebt permittedDebt;
        private final boolean hypotheticalMine;

        CleanupEscapeContext(CleanupBounds bounds, BlockPos mineOnly, BlockState mineState,
                             BlockPos assumeAt, BlockState assumedState) {
            super(Lane.A_NO_PLACING, null, false);
            this.bounds = bounds;
            this.mineOnly = mineOnly;
            this.mineState = mineState;
            this.hypotheticalMine = mineOnly != null && mineOnly.equals(assumeAt);
            this.permittedDebt = mineOnly != null && assumeAt == null ? cleanupEscapeDebt : null;
            if (assumeAt != null) bsi.nimmBlockAn(assumeAt, assumedState);
            // Each context and BSI is private and immutable for the full worker lifetime, including cancellation.
        }

        @Override public double costOfPlacingAt(int x, int y, int z, BlockState current) { return COST_INF; }
        @Override public PlacementLicence placementLicence() { return PlacementLicence.NONE; }
        @Override public boolean mayUsePathingBarriers() { return false; }
        @Override public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if (mineOnly == null || mineOnly.getX() != x || mineOnly.getY() != y || mineOnly.getZ() != z
                    || mineState == null || current.getBlock() != mineState.getBlock()
                    || (!allowBreak && !allowBreakAnyway.contains(current.getBlock()))
                    || isPossiblyProtected(x, y, z)) return COST_INF;
            // PRESENT proves only a hypothetical edge. Real execution must retain its current, revocable
            // ownership: AIR followed by an identical foreign block never reacquires that permission.
            if (!hypotheticalMine && (permittedDebt == null || permittedDebt != cleanupEscapeDebt
                    || permittedDebt.episode != cleanupEscape
                    || !permittedDebt.mayMine(ctx.world(), mineOnly, current))) return COST_INF;
            return super.breakCostMultiplierAt(x, y, z, current);
        }
        @Override public boolean isPathPositionAllowed(int x, int y, int z) {
            return bounds.contains(x, y, z) && bsi.isLoaded(x, z)
                    && get(x, y, z).getFluidState().isEmpty() && get(x, y + 1, z).getFluidState().isEmpty()
                    && get(x, y - 1, z).getFluidState().isEmpty();
        }
    }

    private boolean dryClear(BlockPos feet, BuilderCalculationContext bcc) {
        return bcc.get(feet).isAir() && bcc.get(feet.above()).isAir()
                && bcc.get(feet.below()).getFluidState().isEmpty();
    }

    private boolean cleanupMayOccupy(BlockPos at, BuilderCalculationContext bcc) {
        if (!bcc.hasThrowaway || !Princeps.settings().allowPlace.value || !bcc.get(at).isAir()
                || bcc.isPossiblyProtected(at.getX(), at.getY(), at.getZ())
                || !bcc.worldBorder.canPlaceAt(at.getX(), at.getZ())) return false;
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        int x = at.getX() - origin.getX(), y = at.getY() - origin.getY(), z = at.getZ() - origin.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= full.widthX() || y >= full.heightY() || z >= full.lengthZ()) return true;
        try {
            BlockState wanted = full.desiredState(x, y, z, bcc.get(at), approxPlaceable);
            return wanted != null && wanted.isAir();
        } catch (RuntimeException invalidModel) { return false; }
    }

    private boolean cleanupPermanentFloor(BlockPos at, BuilderCalculationContext bcc) {
        if (navigationScaffolds.contains(at) || isScaffoldLeftBehind(at.getX(), at.getY(), at.getZ(), bcc)
                || incorrectPositions != null && incorrectPositions.contains(new BetterBlockPos(at))) return false;
        ISchematic full = realSchematic == null ? schematic : realSchematic;
        int x = at.getX() - origin.getX(), y = at.getY() - origin.getY(), z = at.getZ() - origin.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= full.widthX() || y >= full.heightY() || z >= full.lengthZ()) return true;
        BlockState current = bcc.get(at);
        try {
            BlockState wanted = full.desiredState(x, y, z, current, approxPlaceable);
            return wanted != null && !wanted.isAir() && wanted.equals(current);
        } catch (RuntimeException invalidModel) { return false; }
    }

    /** The finite original support columns involved in this candidate, never the new helper itself. */
    private void cleanupOriginalSupports(CleanupEscape e, BlockPos base, BuilderCalculationContext bcc) {
        if (!navigationScaffolds.owns(base, bcc.get(base))) return;
        for (int step : new int[]{-1, 1}) {
            for (int y = base.getY(); y >= e.bounds.minY && y <= e.bounds.maxY; y += step) {
                BetterBlockPos at = new BetterBlockPos(base.getX(), y, base.getZ());
                if (!navigationScaffolds.owns(at, bcc.get(at))) break;
                if (!e.originalSupports.contains(at)) e.originalSupports.add(at);
            }
        }
    }

    private boolean cleanupCanSwingFrom(BetterBlockPos stance, BetterBlockPos target) {
        Vec3 eye = new Vec3(stance.x + 0.5, stance.y + ctx.player().getEyeHeight(Pose.STANDING), stance.z + 0.5);
        double reach = ctx.playerController().getBlockReachDistance();
        for (Direction face : Direction.values()) {
            Vec3 hitAt = Vec3.atCenterOf(target).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (eye.distanceTo(hitAt) > reach) continue;
            BlockHitResult hit = ctx.world().clip(new net.minecraft.world.level.ClipContext(eye,
                    hitAt.add(hitAt.subtract(eye).normalize().scale(0.001)),
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, ctx.player()));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return true;
        }
        return false;
    }

    private boolean startCleanupEscape(BuilderCalculationContext bcc, boolean safeToCancel) {
        if (cleanupEscape != null || !safeToCancel || !ctx.player().onGround() || buildInRows || !electedBreak
                || electedCell == null || laneAProof == null || !laneQuestionCurrent(laneAProof, electedGoal, false)
                || !navigationScaffolds.owns(electedCell, ctx.world().getBlockState(electedCell))
                || cleanupEscapeAttemptedOwners.contains(electedCell.asLong())
                || cleanupEscapeDebt != null && !cleanupEscapeDebt.discharged()) return false;
        if (!laneQuestionCurrent(laneAProof, electedGoal, true)) {
            // Accepted navigation permission can survive movement; a new escape needs a fresh negative search
            // from the actual body/world revision. Re-ask instead of upgrading that older fact into a proof.
            discardLaneQuestion();
            laneAProof = null;
            laneEscalatedCell = null;
            return false;
        }
        cleanupEscapeAttemptedOwners.add(electedCell.asLong());
        cleanupEscape = new CleanupEscape(bcc);
        BuildTrace.cell(buildTick, "CLEANUP-ESCAPE", electedCell.x, electedCell.y, electedCell.z,
                "finite candidates=" + cleanupEscape.candidates.size() + " permanentFloors=" + cleanupEscape.floors.size());
        return true;
    }

    private PathingCommand cleanupHold() {
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private boolean askCleanup(CleanupEscape e, BetterBlockPos start, Goal goal, CleanupEscapeContext context) {
        if (!e.identityCurrent() || !ctx.playerFeet().equals(e.proofStart) || !e.verifyWorld()) {
            e.block("proof identity/start/world changed"); return false;
        }
        e.queryStart = start;
        e.queryGoal = goal;
        e.queryContext = context;
        return e.probe.start(ctx, start, goal, context, LANE_B_BUDGET,
                Princeps.settings().primaryTimeoutMS.value, Princeps.settings().failureTimeoutMS.value);
    }

    static boolean cleanupCompleteDryPath(PathProbe.Result answer, BetterBlockPos start, Goal goal, int maxDryFall) {
        if (answer == null || !answer.reachedGoal() || answer.path == null || answer.path.positions().isEmpty()
                || !start.equals(answer.path.getSrc()) || !goal.isInGoal(answer.path.getDest())) return false;
        List<BetterBlockPos> positions = answer.path.positions();
        for (int i = 1; i < positions.size(); i++) {
            if (positions.get(i - 1).y - positions.get(i).y > maxDryFall) return false;
        }
        return true;
    }

    private PathingCommand cleanupRoute(CleanupEscape e, BetterBlockPos destination, boolean mayMineHelper) {
        if (e.realRouteContext == null || !destination.equals(e.realRouteDestination) || mayMineHelper != e.realRouteMayMine) {
            BlockPos mine = mayMineHelper ? e.helper : null;
            e.realRouteContext = new CleanupEscapeContext(e.bounds, mine, e.material, null, null);
            e.realRouteGoal = new GoalBlock(destination);
            e.realRouteDestination = destination;
            e.realRouteMayMine = mayMineHelper;
        }
        return new PathingCommandContext(e.realRouteGoal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, e.realRouteContext);
    }

    private PathingCommand driveCleanupEscape(boolean safeToCancel, BuilderCalculationContext ordinary) {
        CleanupEscape e = cleanupEscape;
        observeCleanupInteraction(e);
        if (!safeToCancel && princeps.getPathingBehavior().isPathing()) {
            return continueCurrentRoute(princeps.getPathingBehavior().getGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if (!e.identityCurrent() || !e.worldCurrent() || !e.rulesCurrent(ordinary)) {
            e.block("owner/model/world/rules changed or finite episode expired");
        }
        if (e.stage == CleanupStage.BLOCKED) return cleanupHold();
        if (e.placed && (cleanupEscapeDebt == null || cleanupEscapeDebt.state() == BuilderCleanupDebt.State.REPLACED)) {
            e.block("helper ownership revoked by server state"); return cleanupHold();
        }

        if (e.stage == CleanupStage.CANDIDATE) {
            if (!e.worldCurrent()) { e.block("local world changed before proof"); return cleanupHold(); }
            if (e.stances.isEmpty()) {
                if (e.candidates.isEmpty()) { e.block("finite helper candidates exhausted"); return cleanupHold(); }
                e.helper = e.candidates.removeFirst();
                e.material = snakeIntegrityBlockState(e.helper);
                if (e.material == null || !cleanupMayOccupy(e.helper, ordinary)) return cleanupHold();
                e.originalSupports.clear();
                cleanupOriginalSupports(e, e.owner, ordinary);
                cleanupOriginalSupports(e, e.helper.below(), ordinary);
                // Use normal inventory rules; no direct inventory write and no permission override.
                princeps.getInventoryBehavior().throwaway(true, stack -> !stack.isEmpty()
                        && stack.getItem() == e.material.getBlock().asItem());
                if (hotbarStackThatPlaces(e.material) == null) {
                    e.candidates.addFirst(e.helper); return cleanupHold();
                }
                e.stances.addAll(placementStancesFor(e.helper.x, e.helper.y, e.helper.z, e.material, ordinary,
                        Integer.MAX_VALUE, true, key -> {
                            BlockPos p = BlockPos.of(key);
                            return e.bounds.contains(p.getX(), p.getY(), p.getZ())
                                    && dryClear(p, ordinary) && isStandable(p.getX(), p.getY(), p.getZ());
                        }));
                if (e.stances.isEmpty()) return cleanupHold();
            }
            e.stance = e.stances.removeFirst();
            e.prefixProved = e.presentProved = e.removedProved = false;
            e.placementCenteringTicks = e.placementSettleTicks = 0;
            e.placementFailedCenterDistanceSq = Double.POSITIVE_INFINITY;
            e.placementAimReported = false;
            e.placementMaterialWaitReported = false;
            CleanupEscapeContext context = new CleanupEscapeContext(e.bounds, null, null, null, null);
            if (askCleanup(e, e.proofStart, new GoalBlock(e.stance), context)) e.stage = CleanupStage.PREFIX_PROOF;
            return cleanupHold();
        }

        if (e.stage == CleanupStage.PREFIX_PROOF || e.stage == CleanupStage.PRESENT_PROOF
                || e.stage == CleanupStage.REMOVED_PROOF) {
            PathProbe.Result answer = e.probe.poll();
            if (answer == null) return cleanupHold();
            if (!ctx.playerFeet().equals(e.proofStart) || !e.verifyWorld()) {
                e.block("start/world changed while proving an escape leg"); return cleanupHold();
            }
            if (!cleanupCompleteDryPath(answer, e.queryStart, e.queryGoal, e.queryContext.maxFallHeightNoWater)) {
                e.stage = CleanupStage.CANDIDATE; return cleanupHold();
            }
            if (e.stage == CleanupStage.PREFIX_PROOF) {
                e.prefixProved = true;
                CleanupEscapeContext present = new CleanupEscapeContext(e.bounds, null, null, e.helper, e.material);
                if (askCleanup(e, e.stance, new GoalBlock(e.helper.above()), present)) e.stage = CleanupStage.PRESENT_PROOF;
            } else if (e.stage == CleanupStage.PRESENT_PROOF) {
                e.presentProved = true;
                CleanupEscapeContext downward = new CleanupEscapeContext(e.bounds, e.helper, e.material, e.helper, e.material);
                double cost = MovementDownward.cost(
                        downward, e.helper.x, e.helper.y + 1, e.helper.z);
                if (!Double.isFinite(cost) || cost >= COST_INF) { e.stage = CleanupStage.CANDIDATE; return cleanupHold(); }
                CleanupEscapeContext removed = new CleanupEscapeContext(e.bounds, null, null, e.helper, Blocks.AIR.defaultBlockState());
                Goal floors = new GoalComposite(e.floors.stream()
                        .filter(at -> e.originalSupports.stream().allMatch(support -> cleanupCanSwingFrom(at, support)))
                        .map(GoalBlock::new).toArray(Goal[]::new));
                if (askCleanup(e, e.helper, floors, removed)) e.stage = CleanupStage.REMOVED_PROOF;
            } else {
                e.removedProved = true;
                e.floor = answer.path.getDest();
                e.stage = CleanupStage.WALK_PREFIX;
                BuildTrace.cell(buildTick, "CLEANUP-ESCAPE-PROVED", e.owner.x, e.owner.y, e.owner.z,
                        "helper=" + e.helper.toShortString() + " stance=" + e.stance.toShortString()
                                + " permanentFloor=" + e.floor.toShortString() + " independent PREFIX/PRESENT/REMOVED complete");
            }
            return cleanupHold();
        }

        if (e.stage == CleanupStage.WALK_PREFIX) {
            PathingCommand approach = driveCleanupPlacementApproach(e);
            if (approach != null) return approach;
        }
        if (e.stage == CleanupStage.PLACE) {
            if (!centeredInPlacementStance(e.stance)) {
                e.stage = CleanupStage.WALK_PREFIX;
                return driveCleanupPlacementApproach(e);
            }
            if (!e.prefixProved || !e.presentProved || !e.removedProved || !cleanupMayOccupy(e.helper, ordinary)
                    || !ctx.player().onGround() || !ctx.playerFeet().equals(e.stance)) {
                e.block("placement prerequisites no longer hold"); return cleanupHold();
            }
            if (!e.worldCurrent()) { e.block("world changed during aim"); return cleanupHold(); }
            if (hotbarStackThatPlaces(e.material) == null) {
                if (!e.placementMaterialWaitReported) {
                    e.placementMaterialWaitReported = true;
                    BuildTrace.cell(buildTick, "CLEANUP-ESCAPE-MATERIAL", e.owner.x, e.owner.y, e.owner.z,
                            "helper=" + e.helper.toShortString() + " material=" + blockName(e.material)
                                    + " waiting for normal hotbar supply; stance not rejected");
                }
                princeps.getInventoryBehavior().throwaway(true, stack -> !stack.isEmpty()
                        && stack.getItem() == e.material.getBlock().asItem());
                return cleanupHold(); // missing material is not evidence against the stance
            }
            return deriveCleanupPlacement(e, ordinary);
        }
        if (e.stage == CleanupStage.WAIT_PLACE) {
            if (cleanupEscapeDebt.state() == BuilderCleanupDebt.State.REMOVED) {
                e.block("server rejected or removed the requested helper"); return cleanupHold();
            }
            if (cleanupEscapeDebt.state() != BuilderCleanupDebt.State.OWNED) {
                if (cleanupEscapeDebt.mayRequest()) cleanupPlacementClick(e, e.placementRequest, ordinary);
                return cleanupHold();
            }
            if (!e.verifyWorld() || !ordinary.get(e.helper).equals(e.material)) {
                e.block("world changed after helper confirmation"); return cleanupHold();
            }
            // The existing ledger must not acquire this new block from a request or client prediction alone.
            if (navigationScaffolds.record(e.helper, Blocks.AIR.defaultBlockState(), e.material,
                    templateNamesABlockAt(e.helper), Princeps.settings().acceptableThrowawayItems.value
                            .contains(e.material.getBlock().asItem()), buildTick)) {
                navigationScaffolds.serverChanged(e.helper, e.material); // replay the exact observation already held by OWNED debt
            }
            e.stage = CleanupStage.WALK_PRESENT;
        }
        if (e.stage == CleanupStage.WALK_PRESENT) {
            if (!ctx.playerFeet().equals(e.helper.above()) || !ctx.player().onGround()) {
                return cleanupRoute(e, e.helper.above(), false);
            }
            if (!cleanupEscapeDebt.mayMine(ctx.world(), e.helper, ordinary.get(e.helper))
                    || !e.verifyWorld() || !MovementHelper.canWalkOn(ordinary, e.helper.x, e.helper.y - 1, e.helper.z)) {
                e.block("downward support or ownership changed"); return cleanupHold();
            }
            CleanupEscapeContext downward = new CleanupEscapeContext(e.bounds, e.helper, e.material, null, null);
            double cost = MovementDownward.cost(downward,
                    e.helper.x, e.helper.y + 1, e.helper.z);
            if (!Double.isFinite(cost) || cost >= COST_INF) { e.block("real downward movement is forbidden"); return cleanupHold(); }
            e.stage = CleanupStage.DOWNWARD;
        }
        if (e.stage == CleanupStage.DOWNWARD) {
            PathingCommand descending = driveCleanupDownward(e, ordinary);
            if (descending != null) return descending;
        }
        if (e.stage == CleanupStage.WALK_REMOVED) {
            if (!ctx.playerFeet().equals(e.floor) || !ctx.player().onGround()) return cleanupRoute(e, e.floor, false);
            if (!e.verifyWorld() || !cleanupEscapeDebt.discharged() || !dryClear(e.floor, ordinary)
                    || !MovementHelper.canWalkOn(ordinary, e.floor.x, e.floor.y - 1, e.floor.z)
                    || !cleanupPermanentFloor(e.floor.below(), ordinary)
                    || !cleanupCanSwingFrom(e.floor, e.owner)
                    || !e.originalSupports.stream().allMatch(support -> cleanupCanSwingFrom(e.floor, support))) {
                e.block("permanent cleanup stance did not verify"); return cleanupHold();
            }
            BuildTrace.cell(buildTick, "CLEANUP-ESCAPE-LANDED", e.owner.x, e.owner.y, e.owner.z,
                    "one helper removed; original owner retained; permanent feet=" + e.floor.toShortString());
            cleanupEscape = null;
            return cleanupHold();
        }
        return cleanupHold();
    }

    /** Derive the actual click before treating an approximately centered stance as unreachable. */
    private PathingCommand deriveCleanupPlacement(CleanupEscape e, BuilderCalculationContext ordinary) {
        int[] rejected = new int[6];
        Optional<Placement> placement = possibleToPlace(e.material, e.helper.x, e.helper.y, e.helper.z, ordinary, rejected);
        if (placement.isPresent()) {
            if (!e.placementAimReported) {
                e.placementAimReported = true;
                BuildTrace.cell(buildTick, "CLEANUP-ESCAPE-AIM", e.owner.x, e.owner.y, e.owner.z,
                        "helper=" + e.helper.toShortString() + " stance=" + e.stance.toShortString()
                                + " pose=" + ctx.player().position() + " live placement derived; no server ACK yet");
            }
            cleanupPlacementClick(e, placement.get(), ordinary);
        } else {
            double dx = ctx.player().position().x - (e.stance.x + 0.5D);
            double dz = ctx.player().position().z - (e.stance.z + 0.5D);
            double distanceSq = dx * dx + dz * dz;
            // The ordinary 0.15 arrival tolerance is larger than a near-reach click's margin. It is not a
            // negative click proof. Reuse one existing centering pulse and settle, then ask the real ray again.
            // Each further pulse requires measured approach toward the center; repeated/worse poses stop here.
            if (rejected[4] > 0 && rejected[1] == 0 && rejected[2] == 0 && rejected[3] == 0 && rejected[5] == 0
                    && distanceSq > 0 && distanceSq < e.placementFailedCenterDistanceSq
                    && ++e.placementCenteringTicks < STANCE_CENTERING_TICKS) {
                if (!e.verifyWorld()) { e.block("world changed before click recentering"); return cleanupHold(); }
                if (Double.isInfinite(e.placementFailedCenterDistanceSq)) {
                    BuildTrace.cell(buildTick, "CLEANUP-ESCAPE-RECENTER", e.owner.x, e.owner.y, e.owner.z,
                            "helper=" + e.helper.toShortString() + " stance=" + e.stance.toShortString()
                                    + " pose=" + ctx.player().position() + " live ray missed; approaching proved center");
                }
                e.placementFailedCenterDistanceSq = distanceSq;
                e.placementSettleTicks = 0;
                e.stage = CleanupStage.WALK_PREFIX;
                return centerInPlacementStance(e.stance);
            }
            rejectCleanupPlacementStance(e, "centered live derivation failed: noSolid=" + rejected[0]
                    + " cannotSurvive=" + rejected[1] + " obstructed=" + rejected[2] + " emptyShape=" + rejected[3]
                    + " rayMiss=" + rejected[4] + " itemStateRejected=" + rejected[5]);
        }
        return cleanupHold();
    }

    private PathingCommand driveCleanupPlacementApproach(CleanupEscape e) {
        if (!ctx.playerFeet().equals(e.stance) || !ctx.player().onGround()) {
            e.placementSettleTicks = 0;
            return cleanupRoute(e, e.stance, false);
        }
        if (!e.verifyWorld()) { e.block("world changed before actual placement"); return cleanupHold(); }
        // GoalBlock arrives anywhere in the cell; the placement oracle proved its crouched CENTER.
        // Reuse the ordinary actor's bounded centering and one input-free settling tick before deriving a click.
        if (!centeredInPlacementStance(e.stance) || princeps.getPathingBehavior().getCurrent() != null) {
            e.placementSettleTicks = 0;
            if (++e.placementCenteringTicks >= STANCE_CENTERING_TICKS) {
                rejectCleanupPlacementStance(e, "could not settle at the proved placement center");
                return cleanupHold();
            }
            if (princeps.getPathingBehavior().getCurrent() != null) return cleanupHold();
            if (e.placementCenteringTicks == 1) {
                BuildTrace.cell(buildTick, "CLEANUP-ESCAPE-CENTER", e.owner.x, e.owner.y, e.owner.z,
                        "helper=" + e.helper.toShortString() + " stance=" + e.stance.toShortString()
                                + " pose=" + ctx.player().position());
            }
            return centerInPlacementStance(e.stance);
        }
        if (e.placementSettleTicks++ == 0) return settleInPlacementStance();
        e.stage = CleanupStage.PLACE;
        return null;
    }

    /** Consume this concrete stance once. Re-prove alternatives from the actual body, under the same deadline. */
    private void rejectCleanupPlacementStance(CleanupEscape e, String why) {
        if (e.placed || e.placementRequest != null) { e.block("cannot reject a stance with outstanding helper debt"); return; }
        e.probe.cancel();
        BuildTrace.cell(buildTick, "CLEANUP-ESCAPE-STANCE-REJECT", e.owner.x, e.owner.y, e.owner.z,
                "helper=" + e.helper.toShortString() + " stance=" + e.stance.toShortString()
                        + " pose=" + ctx.player().position() + " " + why);
        e.proofStart = ctx.playerFeet();
        e.prefixProved = e.presentProved = e.removedProved = false;
        e.realRouteContext = null;
        e.stage = CleanupStage.CANDIDATE;
    }

    /** The real Downward phase: an already removed helper may be entered, but never mined a second time. */
    private PathingCommand driveCleanupDownward(CleanupEscape e, BuilderCalculationContext ordinary) {
        BlockState actual = ordinary.get(e.helper);
        if (!actual.isAir() && !cleanupEscapeDebt.mayMine(ctx.world(), e.helper, actual)) {
            e.block("downward helper is no longer owned"); return cleanupHold();
        }
        if (!ctx.playerFeet().equals(e.helper) || !ctx.player().onGround()
                || !cleanupEscapeDebt.discharged()) return cleanupRoute(e, e.helper, true);
        if (!e.verifyWorld() || !actual.isAir()) {
            e.block("world changed after downward removal"); return cleanupHold();
        }
        e.stage = CleanupStage.WALK_REMOVED;
        return null;
    }

    private void observeCleanupInteraction(CleanupEscape e) {
        if (e.placementRequest == null || cleanupEscapeDebt == null) return;
        Placement placement = e.placementRequest;
        BlockPlaceHelper.SuccessfulBlockInteraction receipt =
                princeps.getInputOverrideHandler().getBlockPlaceHelper().getLastSuccessfulBlockInteraction();
        if (receipt != null && receipt.matchesMainHandPlacement(placement.placeAgainst, placement.side, placement.target,
                placement.hotbarSelection, placement.desired.getBlock().asItem())) {
            cleanupEscapeDebt.interactionObserved(ctx.world(), e, receipt.getSerial(), System.nanoTime());
        }
    }

    private void cleanupPlacementClick(CleanupEscape e, Placement placement, BuilderCalculationContext bcc) {
        if (placement == null || !e.identityCurrent() || !e.worldCurrent()) return;
        if (princeps.getSurvivalBehavior() != null && princeps.getSurvivalBehavior().ownsInventory()) return;
        princeps.getLookBehavior().updateTarget(placement.rot, true, AimIntent.PLACE);
        ctx.player().getInventory().setSelectedSlot(placement.hotbarSelection);
        princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        HitResult hit = ctx.objectMouseOver();
        BlockPlaceHelper placer = princeps.getInputOverrideHandler().getBlockPlaceHelper();
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || placer.isThrottled()
                || !((BlockHitResult) hit).getBlockPos().equals(placement.placeAgainst)
                || ((BlockHitResult) hit).getDirection() != placement.side || !liveRayWouldPlaceDesired(placement, bcc)) return;
        if (e.placementRequest == null) {
            if (!e.verifyWorld()) { e.block("world changed at actual placement"); return; }
            e.placementRequest = placement;
            cleanupEscapeDebt = new BuilderCleanupDebt(ctx.world(), e, e.helper, e.material,
                    placer.getSuccessfulBlockInteractions(), cleanupServerUpdateSequence, System.nanoTime());
            progressActions.arm(positionKey(e.helper), Blocks.AIR.defaultBlockState(), e.material);
            e.placed = true;
            e.stage = CleanupStage.WAIT_PLACE;
        }
        BuilderCleanupDebt debt = cleanupEscapeDebt;
        if (debt == null || debt.episode != e || !debt.mayRequest()) return;
        Item expected = placement.desired.getBlock().asItem();
        placer.expectMainHandPlacement(placement.placeAgainst, placement.side, placement.target,
                placement.hotbarSelection, expected, () -> cleanupEscape == e && e.identityCurrent()
                        && e.worldCurrent() && cleanupEscapeDebt == debt && debt.mayRequest()
                        && e.stage == CleanupStage.WAIT_PLACE && liveRayWouldPlaceDesired(placement, bcc));
        BuildTrace.intendWorldChange("cleanup-helper", e.helper.x, e.helper.y, e.helper.z,
                "one proved helper for retained BREAK owner");
        princeps.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
    }

    // ==========================================================================================================
    // DER BAHN-TREIBER, VORERST IM SCHATTEN (Schritt 23 der BAU-V04-REIHENFOLGE).
    //
    // Die Spezifikation kennt zwei Betriebsarten der Navigation: Bahn A darf unter keinen Umstaenden einen Block
    // setzen und hat ein Knotenbudget von 5000; Bahn B darf Hilfsbloecke setzen und bekommt 50000. Findet keine
    // von beiden einen Weg, ist das P4 negativ und die Zelle parkt mit Grund C.
    //
    // Hier wird bisher nur GEFRAGT, nicht entschieden: die Fahrt laeuft weiter unter dem LEGACY-Kontext. Der Grund
    // ist eine Zahl, die noch niemand hat -- wie oft findet Bahn A ueberhaupt einen vollstaendigen Weg? Liegt der
    // Anteil unter ~90 %, ist nicht die Welt das Problem, sondern das Budget, und dann waere ein Umschalten auf
    // Bahn A eine Verschlechterung, die wie ein Weltbefund aussieht. Diese Frage beantwortet der Schattenbetrieb,
    // und er kostet nichts ausser der Sonde selbst.
    //
    // PARTIAL ZAEHLT NICHT ALS JA. A* liefert routinemaessig einen Weg, der naeher kommt ohne anzukommen; ihn als
    // Erfolg zu lesen ist genau, wie eine Bahn, die nicht setzen darf, irgendwohin laeuft und nie eskaliert.
    //
    // Die Sonde stoert die laufende Fahrt nicht: PathProbe baut einen eigenen AStarPathFinder und fasst weder
    // `current` noch `next` noch `goal` von PathingBehavior an.

    private final PathProbe laneProbe = new PathProbe("builder-lane");
    /** Which cell the in-flight probe is answering about. Null when nothing is being asked. */
    private BetterBlockPos laneProbeCell;
    /** Which lane the in-flight probe is asking under. */
    private Lane laneProbeLane = Lane.LEGACY;
    private final long[] laneOutcomes = new long[8];   // A: COMPLETE/PARTIAL/NONE/ERROR, then B
    /** Budget of lane A. 5000 Knoten sind bei gemessenen ~115000 Knoten/s rund 43 ms. */
    private static final SearchBudget LANE_A_BUDGET = SearchBudget.ofNodes(5000, "lane-A");
    /** Budget of lane B. 50000 Knoten sind rund 435 ms -- zehnmal A, und immer noch ein Viertel der zwei
     *  Sekunden, die eine erfolglose Suche ohne Budget IMMER kostet. */
    private static final SearchBudget LANE_B_BUDGET = SearchBudget.ofNodes(50000, "lane-B");

    /**
     * Which lane the current cell is driven under. A is the normal case; B only after A has provably failed.
     *
     * <p>OPTIMISTISCH A, ESKALATION AUF BEWEIS -- und das ist keine Abkuerzung, sondern genau die Rangfolge der
     * Spezifikation: "Bahn A ist der Normalfall. Bahn B ist die Eskalation. Sie wird ausschliesslich betreten,
     * wenn Bahn A gescheitert ist." Die Alternative waere, jede Zelle erst die Sonde abwarten zu lassen, bevor
     * ueberhaupt gefahren wird -- ein Tick Stillstand je Zelle, bei 1183 Zellen rund 9 % des Laufs, und das fuer
     * eine Frage, deren Antwort im Schattenlauf 1483 von 1483 Mal "ja" lautete (Median 1 ms).
     */
    private Lane laneForCurrentCell(BetterBlockPos cell) {
        return cell != null && cell.equals(laneEscalatedCell) && helpersAllowedForElectedWork()
                && laneQuestionCurrent(laneAProof, laneAProof == null ? null : laneAProof.goal, false)
                ? Lane.B_HELPERS_ALLOWED : Lane.A_NO_PLACING;
    }

    /** The one cell lane A has provably failed on. At most one at a time -- there is only ever one target. */
    private BetterBlockPos laneEscalatedCell;

    private ISchematic layerMask;
    private ISchematic layerMaskSource;
    private int layerMaskMinY;
    private int layerMaskMaxY;
    private boolean layerMaskTopDown;

    /** Stable identity for the same tick-produced model mask; a genuine model/band/rule change gets a new mask. */
    private ISchematic layerMask(ISchematic realSchematic, int minYInclusive, int maxYInclusive, boolean topDownLayers) {
        if (layerMask != null && layerMaskSource == realSchematic && layerMaskMinY == minYInclusive
                && layerMaskMaxY == maxYInclusive && layerMaskTopDown == topDownLayers) return layerMask;
        layerMaskSource = realSchematic;
        layerMaskMinY = minYInclusive;
        layerMaskMaxY = maxYInclusive;
        layerMaskTopDown = topDownLayers;
        layerMask = new ISchematic() {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                BlockState geruest = BuilderProcess.this.scaffoldOverrideAt(x, y, z);
                if (geruest != null) {
                    return geruest;
                }
                return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
            }

            @Override
            public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                if (BuilderProcess.this.scaffoldOverrideAt(x, y, z) != null) {
                    // Die Geruestzelle liegt regelmaessig UNTER dem Ebenenband -- das ist ja der Grund, warum
                    // die bediente Zelle in der Luft haengt. Ohne diese Zeile waere sie ausserhalb des
                    // Arbeitssatzes und niemand wuerde sie je setzen.
                    return true;
                }
                if (!ISchematic.super.inSchematic(x, y, z, currentState)
                        || !realSchematic.inSchematic(x, y, z, currentState)) {
                    return false;
                }
                if (y >= minYInclusive && y <= maxYInclusive) {
                    return true;
                }
                // A top-down layer containing a door UPPER must atomically include its LOWER one row below.
                // Otherwise the upper is intentionally non-placeable, the base is outside the wrapper, and the
                // layer can never finish or expand.
                if (!topDownLayers || y != minYInclusive - 1) {
                    return false;
                }
                BlockState lower = realSchematic.desiredState(
                        x, y, z, currentState, BuilderProcess.this.approxPlaceable);
                if (lower == null || !(lower.getBlock() instanceof DoorBlock)
                        || lower.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                    return false;
                }
                BlockPos aboveWorld = new BlockPos(
                        BuilderProcess.this.origin.getX() + x,
                        BuilderProcess.this.origin.getY() + y + 1,
                        BuilderProcess.this.origin.getZ() + z);
                BlockState aboveCurrent = ctx.world().getBlockState(aboveWorld);
                if (!realSchematic.inSchematic(x, y + 1, z, aboveCurrent)) {
                    return false;
                }
                BlockState upper = realSchematic.desiredState(
                        x, y + 1, z, aboveCurrent, BuilderProcess.this.approxPlaceable);
                return upper != null && upper.getBlock() == lower.getBlock()
                        && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
            }

            @Override
            public void reset() {
                realSchematic.reset();
            }

            @Override
            public int widthX() {
                return realSchematic.widthX();
            }

            @Override
            public int heightY() {
                return realSchematic.heightY();
            }

            @Override
            public int lengthZ() {
                return realSchematic.lengthZ();
            }
        };
        return layerMask;
    }

    private LaneQuestion laneQuestion;
    private LaneQuestion laneAProof;
    private boolean laneBAnswered;

    /** The actual question, including the build/model snapshot and the start from which a negative answer applies. */
    private record LaneQuestion(BetterBlockPos cell, Goal goal, Goal targetGoal, BetterBlockPos start,
                                BuilderCalculationContext context, BlockState targetState,
                                boolean cleanup, long worldRevision, BetterBlockPos scaffold, BlockState scaffoldState) { }

    private boolean laneQuestionCurrent(LaneQuestion question, Goal goal, boolean pending) {
        if (question == null || !question.cell.equals(electedCell) || !Objects.equals(question.goal, goal)
                || !Objects.equals(question.targetGoal, electedGoal) || question.cleanup != scaffoldCleanupActive
                || !Objects.equals(question.scaffold, scaffoldCell) || !Objects.equals(question.scaffoldState, scaffoldWanted)
                || question.context.platformApproach != platformTraverseApproach
                || question.context.schematic != schematic || question.context.rowMode != buildInRows
                || buildInRows && (question.context.rowBandStart != rowActiveBandStart
                    || question.context.rowFrontier != rowActiveFrontier)
                || origin == null || question.context.originX != origin.getX()
                || question.context.originY != origin.getY() || question.context.originZ != origin.getZ()
                || question.context.world != ctx.world()
                || !question.targetState.equals(ctx.world().getBlockState(question.cell))) return false;
        int count = question.context.placeable.size();
        if (approxPlaceable == null || approxPlaceable.size() < count) return false;
        // These are inventory approximations, whose simulated facing changes with the player's look/position.
        // Routing uses their block/item identity; a head turn must not revoke the same available material.
        for (int slot = 0; slot < count; slot++) {
            if (question.context.placeable.get(slot).getBlock() != approxPlaceable.get(slot).getBlock()) return false;
        }
        return !pending || question.start.equals(ctx.playerFeet()) && question.worldRevision == confirmedProgressRevision;
    }

    private void discardLaneQuestion() {
        if (laneProbe != null) laneProbe.cancel();
        laneQuestion = null;
        laneProbeCell = null;
    }

    private boolean helpersAllowedForElectedWork() {
        // An owned helper must not create a new helper debt to remove itself. A separate bounded descent contract
        // is required before that transition can be enabled. Ordinary target removals keep the normal lane rules.
        return !scaffoldCleanupActive && (!electedBreak || !navigationScaffolds.contains(electedCell));
    }

    private void driveLanesInShadow(Goal goal) {
        if (goal == null || electedCell == null) {
            discardLaneQuestion();
            laneAProof = null;
            laneEscalatedCell = null;
            return;
        }
        if (laneAProof != null && !laneQuestionCurrent(laneAProof, goal, false)) {
            laneAProof = null;
            laneEscalatedCell = null;
            laneBAnswered = false;
        }
        if (laneQuestion != null && !laneQuestionCurrent(laneQuestion, goal, true)) {
            discardLaneQuestion(); // a completed old worker is discarded under the same request boundary
        }
        PathProbe.Result answer = laneProbe.poll();
        if (answer != null && laneQuestion != null) {
            LaneQuestion answered = laneQuestion;
            Lane answeredLane = laneProbeLane;
            int base = answeredLane == Lane.A_NO_PLACING ? 0 : 4;
            int slot = switch (answer.outcome) {
                case COMPLETE -> 0;
                case PARTIAL -> 1;
                case NONE -> 2;
                default -> 3;
            };
            laneOutcomes[base + slot]++;
            if (BuildTrace.isActive()) {
                BuildTrace.cell(buildTick, "LANE", answered.cell.x, answered.cell.y, answered.cell.z,
                        (answeredLane == Lane.A_NO_PLACING ? "A" : "B") + "=" + answer.outcome
                                + " positions=" + answer.positions + " ms=" + answer.millis
                                + " negativeEvidence=" + answer.failedToReachGoal()
                                + " probegoal=" + String.valueOf(answered.goal).replace(' ', '_'));
            }
            laneQuestion = null;
            laneProbeCell = null;
            if (answeredLane == Lane.A_NO_PLACING && answer.failedToReachGoal()) {
                laneAProof = answered;
                laneBAnswered = false;
                if (!helpersAllowedForElectedWork()) {
                    BuildTrace.cell(buildTick, "LANE-CLEANUP-BOUND", answered.cell.x, answered.cell.y, answered.cell.z,
                            "no complete A route; helper removal requires a bounded descent and A-only cleanup proof");
                    return;
                }
                laneEscalatedCell = answered.cell;
                if (princeps.getPathingBehavior() instanceof princeps.behavior.PathingBehavior pb) {
                    pb.getInProgress().ifPresent(search -> search.cancel());
                }
                BuildTrace.cell(buildTick, "LANE-ESCALATE", answered.cell.x, answered.cell.y, answered.cell.z,
                        "fresh lane A found no complete route within " + LANE_A_BUDGET + "; helper blocks now allowed");
                askLane(Lane.B_HELPERS_ALLOWED, answered.cell, goal);
                return;
            }
            if (answeredLane == Lane.B_HELPERS_ALLOWED && answer.failedToReachGoal()) {
                parkCell(answered.cell, ParkReason.UNREACHABLE);
                if (incorrectPositions != null) incorrectPositions.remove(answered.cell);
                if (placementTargetLock.owns(positionKey(answered.cell))) releasePlacementTarget();
                clearElectedTarget();
                return;
            }
            if (answeredLane == Lane.B_HELPERS_ALLOWED && answer.reachedGoal()) laneBAnswered = true;
            // ERROR, timeout and chunk-limited partial paths are unknown, never a licence or a parking verdict.
            return;
        }
        if (laneProbe.isRunning() || laneQuestion != null) return;
        if (laneAProof != null) {
            if (helpersAllowedForElectedWork() && !laneBAnswered) askLane(Lane.B_HELPERS_ALLOWED, electedCell, goal);
            return;
        }
        askLane(Lane.A_NO_PLACING, electedCell, goal);
    }

    private void askLane(Lane lane, BetterBlockPos cell, Goal goal) {
        BuilderCalculationContext probeContext;
        try {
            probeContext = new BuilderCalculationContext(lane);
        } catch (RuntimeException e) {
            return; // an unasked question says nothing about reachability
        }
        BetterBlockPos start = ctx.playerFeet();
        boolean started = laneProbe.start(ctx, start, goal, probeContext,
                lane == Lane.A_NO_PLACING ? LANE_A_BUDGET : LANE_B_BUDGET,
                Princeps.settings().primaryTimeoutMS.value, Princeps.settings().failureTimeoutMS.value);
        if (started) {
            laneProbeCell = cell;
            laneProbeLane = lane;
            laneQuestion = new LaneQuestion(cell, goal, electedGoal, start, probeContext,
                    probeContext.bsi.get0(cell), scaffoldCleanupActive, confirmedProgressRevision, scaffoldCell, scaffoldWanted);
        }
    }
    /** A-COMPLETE/PARTIAL/NONE/ERROR then B-COMPLETE/PARTIAL/NONE/ERROR. Read by the narration. */
    private String laneCensus() {
        return "A=" + laneOutcomes[0] + "/" + laneOutcomes[1] + "/" + laneOutcomes[2] + "/" + laneOutcomes[3]
                + " B=" + laneOutcomes[4] + "/" + laneOutcomes[5] + "/" + laneOutcomes[6] + "/" + laneOutcomes[7]
                + " (complete/partial/none/error)";
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        // A PARKED CELL HAD NO WAY BACK. That is what this interval buys, and it is the whole fix.
        //
        // trim() drops every cell more than ~14 blocks from the bot and writes a PARK event for it. Refilling only on
        // an EMPTY set meant those cells never returned: the set is never empty, because whatever is near the bot
        // keeps it populated, and trim itself only replaces the set while the replacement is non-empty. The circle
        // closed on itself -- a parked cell is not in the work set, so assemble never routes to it, so the bot never
        // walks within recalcNearby's radius of it, so it is never re-added.
        //
        // Measured on run edcd60b2, the 75 sticky_piston cells that layer y=-59 ended without:
        //   71 of 75 carry NO DEFER at all -- they were never attempted, only parked
        //   every single one carries a PARK; not one carries a PICK
        //   12 of them have no ENTER either, because recalcNearby adds silently and only fullRecalc writes ENTER
        // Their geometry is indistinguishable from the 419 that succeeded: click face standing, somewhere to stand
        // within two blocks, and 181 of those 419 were placed while standing ON the course being built.
        //
        // The trim itself stays: it buys locality cheaply and widening the cap instead measured worse (-7% at
        // incorrectSize 1000). What was wrong is only that the discarded cells never came back.
        // NO CLOCK. The 200-tick refill existed because a trimmed cell had no way back; now a cell only ever
        // leaves the working set by being BUILT or by being PARKED, and the only way back is the watchman. Refilling
        // on a timer would hand parked cells a second door, which is the one thing the specification forbids.
        //
        // The empty case stays: an empty set means every cell of this layer is either finished or parked, and that
        // is precisely the question P0 asks before the layer is judged.
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    /**
     * Keeps the work set local -- but stops doing so once locality has demonstrably run out of work.
     *
     * <p>The radius trim keeps only cells within {@code distSqr <= 200} (about 14 blocks) and replaces the work set
     * with them, guarded solely by "the replacement is not empty". That guard protects against emptying the set. It
     * does NOT protect against shrinking it to a handful of cells that cannot be placed, and in a corner of the build
     * that is exactly what it does.
     *
     * <p>Measured on run 8d8e288f, the episode that ENDED the run -- 993 ticks from tick 52068, position
     * {@code 127.331,-58.000,122.500} identical to three decimals, {@code aimd=0.00}, {@code path=-},
     * cobblestone in hand, at the far corner of the x/z 67..129 box:
     *
     * <pre>
     *   work=2          two cells in the set, while layer y=-58 still had 333 open
     *   ENTER 500       five refills of 100 cells each (WORK_SET_REFILL_INTERVAL = 200 ticks over 993)
     *   PARK  490       parked again within a tick or two of every refill
     * </pre>
     *
     * The arithmetic closes exactly: the refill added the cells back and this method threw them straight out again.
     * The previous fix (refilling at all) was necessary and not sufficient, and the comment above {@code recalc} says
     * as much -- "what was wrong is only that the discarded cells never came back" was half the circle.
     *
     * <p>Two changes came out of that, in this order:
     *
     * <ol>
     *   <li><b>The trim yields when nothing has completed for {@link #TRIM_SUSPEND_AFTER_QUIET_TICKS}.</b> Measured:
     *       the terminal 993-tick episode became 180, total dead-goal time 9062 -> 7775 ticks, and the work set at the
     *       end of the run stood at 100 instead of 2. Kept, and now a backstop rather than the main rule.</li>
     *   <li><b>The radius became a nearest-N.</b> The suspension removed the terminal stall but not the underlying
     *       defect: in the very next run, of the 142 cells layer y=-58 ended without, <b>112 carried an ENTER and a
     *       PARK and no DEFER</b> -- offered, parked, and never once attempted. A hard cutoff makes locality decide
     *       MEMBERSHIP; sorting and capping makes it decide ORDER, which is all it was ever for. See the note at the
     *       cap below for why this is not the widening that measured worse.</li>
     * </ol>
     */
    /**
     * Disabled: distance is not a reason to park a cell.
     *
     * <p>This used to replace the working set with the nearest {@code incorrectSize} cells and write a PARK event
     * for everything it dropped. That is a fourth park reason -- "too far away" -- and the specification has
     * exactly three. Worse, it was self-defeating: a cell dropped for distance is no longer routed to, so the bot
     * never walks near it, so it is never re-added. Measured on run edcd60b2: of the 75 sticky_piston cells layer
     * y=-59 ended without, 71 carried no attempt at all -- every one of them had been parked for distance and
     * never came back.
     *
     * <p>Locality is not lost by removing it: the working set is sorted by distance at selection time, so the
     * nearest cell is still the one worked on. What is gone is only the pretence that a distant cell does not
     * exist.
     */
    private void trim(BuilderCalculationContext bcc) {
        // deliberately empty -- see above
    }

    /**
     * A radius trim may not split an attachment from the schematic block it needs to exist. Run 698d2fe3 retained the
     * north lever at 98,-59,97 (distance squared 198) but parked its redstone-lamp support at 98,-59,98 (227), deleting
     * a healthy len57 route to the lamp and leaving the impossible lever as permanent work=1. Resolve to a fixed point
     * so a short attachment chain remains coherent as one local work component.
     */
    static <T> HashSet<T> dependencyClosedSelection(
            Collection<T> initiallyRetained,
            java.util.function.Function<T, T> unresolvedDependency) {
        HashSet<T> retained = new HashSet<>(initiallyRetained);
        ArrayDeque<T> pending = new ArrayDeque<>(retained);
        while (!pending.isEmpty()) {
            T dependency = unresolvedDependency.apply(pending.removeFirst());
            if (dependency != null && retained.add(dependency)) {
                pending.addLast(dependency);
            }
        }
        return retained;
    }

    /** The still-wrong schematic block that must exist before {@code cell} can be placed, if that dependency is part
     *  of this build layer. Temporary throwaway support in schematic-air cells remains temporarySupportGoal's job. */
    private BetterBlockPos unresolvedRequiredSupport(BetterBlockPos cell, BuilderCalculationContext bcc) {
        BlockState current = bcc.bsi.get0(cell);
        BlockState desired = bcc.getSchematic(cell.x, cell.y, cell.z, current);
        if (desired == null) {
            return null;
        }
        BetterBlockPos support = requiredSupportPosition(cell, desired);
        if (support == null) {
            return null;
        }
        if (isCellParked(support.x, support.y, support.z)) {
            return null;
        }
        BlockState supportCurrent = bcc.bsi.get0(support);
        BlockState supportDesired = bcc.getSchematic(
                support.x, support.y, support.z, supportCurrent);
        if (supportDesired == null || supportDesired.isAir()
                || valid(supportCurrent, supportDesired, false)) {
            return null;
        }
        return support;
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Princeps.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (valid(bcc.bsi.get0(x, y, z), desired, false)
                                || isPendingChestPairHalf(bcc.bsi.get0(x, y, z), desired, x, y, z, bcc)
                                || notACellOfItsOwn(desired, x, y, z, bcc)) {
                            if (incorrectPositions.remove(pos)) {
                                BuildTrace.cell(buildTick, "DONE", x, y, z, "got=" + blockName(bcc.bsi.get0(x, y, z)));
                                // DER WAECHTER, an der einzigen Stelle, die weiss WELCHE Zelle korrekt geworden ist.
                                // noteCellCompleted() weiss es nicht -- es bekommt keine Koordinate -- und genau
                                // deshalb konnte die Freigabe bisher nur traege beim naechsten Ansehen passieren.
                                activeCells.remove(pos);
                                parkedCells.remove(positionKey(x, y, z));
                                watchmanAfterChangeAt(x, y, z);
                                noteCellCompleted(pos);
                                if (walkTargetKey == positionKey(pos)) {
                                    walksEndedInPlacement++;   // that walk was worth taking
                                    walkTargetKey = -1L;
                                }
                            }
                            long key = positionKey(pos);
                            observedCompleted.add(key);
                            clearCellRetryHistory(key);
                        } else if (!isCellParked(x, y, z)) {
                            // GEPARKTE ZELLEN GEHOEREN NICHT IN DEN ARBEITSSATZ, und das war bis hierher der Grund,
                            // warum die Ebene nie fertig werden konnte: parkCell entfernte eine Zelle nur aus
                            // activeCells, recalcNearby trug sie im selben Tick wieder in incorrectPositions ein,
                            // also lieferte recalc() ewig true und der ganze Ebenen-Abschlusszweig -- P0, P6a, die
                            // Verifikation -- war unerreichbar, solange auch nur eine Zelle geparkt war.
                            // Die Spezifikation trennt die beiden Listen aus genau diesem Grund: "Eine Zelle ist zu
                            // jedem Zeitpunkt in genau einer Liste -- AKTIV oder PARK -- oder gesetzt."
                            incorrectPositions.add(pos);
                            observedCompleted.remove(positionKey(pos));
                            if (CellWatch.isWatched(x, y, z)) {
                                CellWatch.note(buildTick, x, y, z, "window",
                                        "in the work set via recalcNearby, want=" + blockName(desired)
                                                + " have=" + blockName(bcc.bsi.get0(x, y, z)));
                            }
                        } else if (CellWatch.isWatched(x, y, z)) {
                            CellWatch.note(buildTick, x, y, z, "window", "retired, kept out of the work set");
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        lastFullRecalcTick = buildTick;
        incorrectPositions = new HashSet<>();
        // Collected first, capped afterwards -- see the comment at the end of this method for why the old early return
        // was the wrong shape. The scan itself is unchanged, so this costs one pass over the schematic where it used to
        // cost a partial one, plus a sort of what it found. fullRecalc runs only when recalcNearby leaves the set
        // empty, not per tick, and the bench's own "measured N client ticks/real second" line is the guard: if this
        // ever became hot, that number would fall below the ask.
        List<BetterBlockPos> candidates = new ArrayList<>();
        if (scaffoldCleanupActive) {
            candidates.addAll(remainingNavigationScaffolds(bcc));
        } else {
            for (long key : scaffoldCleanupTargets) {
                BlockPos pos = BlockPos.of(key);
                if (navigationScaffolds.owns(pos, bcc.bsi.get0(pos)) && !isCellParked(pos.getX(), pos.getY(), pos.getZ())) {
                    candidates.add(new BetterBlockPos(pos));
                }
            }
        }
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        BlockState desired = schematic.desiredState(x, y, z, current, this.approxPlaceable);
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), desired, false)
                                || isPendingChestPairHalf(bcc.bsi.get0(blockX, blockY, blockZ), desired, blockX, blockY, blockZ, bcc)
                                || notACellOfItsOwn(desired, blockX, blockY, blockZ, bcc)) {
                            long key = positionKey(blockX, blockY, blockZ);
                            observedCompleted.add(key);
                            clearCellRetryHistory(key);
                        } else if (!isCellParked(blockX, blockY, blockZ)) {
                            candidates.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(positionKey(blockX, blockY, blockZ));
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(positionKey(blockX, blockY, blockZ))
                            && !isCellParked(blockX, blockY, blockZ)) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        candidates.add(new BetterBlockPos(blockX, blockY, blockZ));
                    }
                }
            }
        }
        // The cap now selects the NEAREST cells rather than the first ones the scan happened to reach.
        //
        // The loops above walk y from 0, then z, then x, and the old code returned the moment the set passed
        // incorrectSize. So the work window was always the lowest, most-northerly, most-westerly corner of whatever was
        // still unbuilt -- with no relation to where the bot was standing. On etz-basalt that is why, of the 5325 cells
        // a run sets aside, only 1663 distinct ones ever appear in a deferral line: at least 69% were discarded having
        // never been offered to the search, because the window was full of a corner they were nowhere near.
        //
        // Widening the window instead was tried and measured worse (incorrectSize 1000: -7% at t=150000 where the
        // spread is 2.7%, audit 25.4% against 27.2-31.0%) -- searchForPlacables walks the whole set every tick, so more
        // cells is a longer walk for the same few reachable ones. The cap is buying LOCALITY. This keeps the cap and
        // buys the locality honestly.
        // NO CAP. S0b of the specification: "NUR die Zellen der Ebene E in die AKTIV-Liste" -- all of them, not
        // the nearest hundred. The cap was locality bought by pretending the rest of the layer did not exist, and
        // with the timed refill gone it would be a one-way door: a cell outside the window is never offered, never
        // parked, and therefore never woken either. Locality now comes from the distance sort at selection time,
        // which is where the owner put it: "Kopf der Liste ist einfach immer das mit der geringsten Distanz."
        int cap = Integer.MAX_VALUE;
        if (candidates.size() <= cap) {
            incorrectPositions.addAll(candidates);
            if (BuildTrace.isActive()) {
                for (BetterBlockPos pos : incorrectPositions) {
                    BuildTrace.cell(buildTick, "ENTER", pos.x, pos.y, pos.z,
                            "window=" + incorrectPositions.size() + "/" + candidates.size() + " layer=" + layer);
                }
            }
            return;
        }
        // ENTER, one line per cell that makes it into the window. With DROP on the other side, a cell that is never
        // built now has a provable status: it was offered and failed, it was thrown away, or it was NEVER OFFERED --
        // and that third case was invisible until this pair existed.
        BetterBlockPos feet = ctx.playerFeet();
        candidates.sort(java.util.Comparator.comparingLong(p -> {
            long dx = p.x - feet.x;
            long dy = p.y - feet.y;
            long dz = p.z - feet.z;
            return dx * dx + dy * dy + dz * dz;
        }));
        incorrectPositions.addAll(candidates.subList(0, cap));
        if (BuildTrace.isActive()) {
            for (BetterBlockPos pos : incorrectPositions) {
                BuildTrace.cell(buildTick, "ENTER", pos.x, pos.y, pos.z,
                        "window=" + incorrectPositions.size() + "/" + candidates.size() + " layer=" + layer);
            }
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    /** The first cell whose desired block nothing in {@code available} can place, or null when materials suffice. */
    private BetterBlockPos firstMissingMaterial(BuilderCalculationContext bcc, List<BlockState> available) {
        for (BetterBlockPos pos : incorrectPositions) {
            BlockState current = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, current);
            // notACellOfItsOwn statt isSecondaryHalf: dieselbe Frage, eine Definition weiter. Ein Kolbenkopf hat
            // keinen Gegenstand, der ihn setzt -- gefragt wird hier aber, ob dem Bau MATERIAL fehlt, und die
            // Antwort darauf war fuer eine Schematic mit ausgefahrenem Kolben "ja, fuer immer": der ganze Bau
            // endete in MATERIALS_MISSING an einer Zelle, die niemand je haette setzen sollen.
            if (desired != null && !notACellOfItsOwn(desired, pos.x, pos.y, pos.z, bcc)
                    && !(desired.getBlock() instanceof AirBlock)
                    && (current.getBlock() instanceof LiquidBlock
                    || MovementHelper.isReplaceable(pos.x, pos.y, pos.z, current, bcc.bsi))
                    && !containsBlockState(available, desired)) {
                return pos;
            }
        }
        return null;
    }


    /**
     * Materials wanted by cells in the WORK SET that no hotbar slot can place but the backpack can — appended to the
     * hotbar demand so something actually fetches them.
     *
     * <p>THE STARVATION LOOP THIS CLOSES. Hotbar demand came from one source only: {@code desirableOnHotbar}, which
     * {@link #searchForPlacables} fills from an 11x7x11 box around the bot's feet. {@link #assemble} meanwhile decides
     * what to WALK to from {@code approxPlaceable.subList(0, 9)} — the hotbar — and a cell whose material is not on it
     * lands in {@code missing}, gets no goal, and leaves no trace event at all. So a cell eight blocks away whose
     * material is in the backpack was invisible to the goal chooser, and invisible to the fetch as well, because the
     * fetch only hears about cells the bot is already standing next to. It could not become a target without the
     * material, and could not get the material without becoming a target.
     *
     * <p>Measured in run 20260802-212908 with the census moved somewhere it could actually fire: 32 to 51 cells in
     * that state at once, led by {@code redstone_wire} — the very material of the cell whose absence leaves this
     * schematic's {@code sticky_piston}s with nothing to click against. The same closed loop is already recognised
     * and fixed one level down, for DEFERRED cells, in {@link #searchForPlacables}: "deferred for want of a torch,
     * never given a torch because it was deferred". This is that fix for the population one level up.
     *
     * <p>Only cells the backpack CAN serve. A material that is genuinely absent is not a fetch problem, and adding it
     * here would push a real shortage into a loop that cannot resolve it — {@code unresolvedResourceBlocker} is what
     * names that case, and it must keep being the one that does.
     *
     * <p>Nearest first, and appended rather than prepended: work the bot is standing in front of keeps priority, so
     * this can only add fetches that would otherwise never have happened, never displace one that would.
     */
    private void appendStarvedWorkSetMaterials(BuilderCalculationContext bcc, List<BlockState> demand) {
        if (incorrectPositions == null || incorrectPositions.isEmpty()
                || approxPlaceable == null || approxPlaceable.size() < 9) {
            return;
        }
        List<BlockState> hotbar = approxPlaceable.subList(0, 9);
        List<BetterBlockPos> starved = new ArrayList<>();
        for (BetterBlockPos pos : incorrectPositions) {
            if (isCellParked(pos.x, pos.y, pos.z)) {
                continue;   // the deferral branch of the scan already declares these
            }
            BlockState current = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, current);
            if (desired == null || desired.isAir() || isSecondaryHalf(desired)) {
                continue;
            }
            if (!MovementHelper.isReplaceable(pos.x, pos.y, pos.z, current, bcc.bsi)
                    || valid(current, desired, false)) {
                continue;   // nothing to place here
            }
            if (containsBlockState(hotbar, desired) || !containsBlockState(approxPlaceable, desired)) {
                continue;   // already servable, or genuinely absent
            }
            starved.add(pos);
        }
        if (starved.isEmpty()) {
            return;
        }
        BetterBlockPos feet = ctx.playerFeet();
        starved.sort(java.util.Comparator.comparingLong(p -> {
            long dx = p.x - feet.x;
            long dy = p.y - feet.y;
            long dz = p.z - feet.z;
            return dx * dx + dy * dy + dz * dz;
        }));
        for (BetterBlockPos pos : starved) {
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos));
            if (desired != null) {
                demand.add(desired);
            }
        }
    }

    private String unresolvedResourceBlocker(BuilderCalculationContext bcc, List<BlockState> available) {
        BetterBlockPos missing = firstMissingMaterial(bcc, available);
        if (missing != null) {
            // Name the block and the cell. "A material is missing" sends whoever reads it hunting through an
            // inventory of twenty stacks with no idea which one to look for -- and on a real schematic that is the
            // difference between a five-minute fix and an afternoon.
            BlockState current = bcc.bsi.get0(missing);
            BlockState desired = bcc.getSchematic(missing.x, missing.y, missing.z, current);
            return "a required build material is missing: nothing in the inventory places "
                    + (desired == null ? "?" : blockName(desired))
                    + " at " + missing.x + "," + missing.y + "," + missing.z;
        }
        for (BetterBlockPos pos : incorrectPositions) {
            BlockState current = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, current);
            if (desired == null || !(current.getBlock() instanceof LiquidBlock
                    || (excavating && snakeTreatAsFluid(current, true)))
                    || !(desired.getBlock() instanceof AirBlock)) {
                continue;
            }
            if (excavating ? !snakeSourceReadyForPlug(current, true) : MovementHelper.possiblyFlowing(current)) {
                continue; // transient world condition: retain and retry, do not turn it into a material pause
            }
            if (!bcc.hasThrowaway) {
                return "removing source liquid at " + pos.x + "," + pos.y + "," + pos.z
                        + " requires an allowed throwaway block";
            }
        }
        return null;
    }

    /**
     * Builds the pathing goal, and if every unresolved cell happens to be on deferral backoff, builds it again
     * ignoring the backoff.
     *
     * <p>Deferral is meant to stop the builder HAMMERING a cell it cannot currently place. It was also, accidentally,
     * stopping it from WALKING toward that cell -- {@link #assemble} skips deferred cells, so once the last
     * non-deferred cell was gone there was no goal at all, and no goal means the bot stands perfectly still.
     *
     * <p>Which is the worst possible thing to do, because standing still is what guarantees the retry fails too: the
     * backoff expires, the cell is attempted again from the exact spot it already failed from, and is deferred for
     * twice as long. A lighthouse run sat at 73,-58,71 for 10,600 ticks doing this, retiring 61 cells one after
     * another without placing a single block -- from a shaft it could simply have jumped out of.
     *
     * <p>So the backoff now governs the placement attempt only. If nothing else is left to do, the bot goes and stands
     * next to the deferred cell, and by the time the backoff lapses it is somewhere new -- which is the entire point
     * of retrying.
     */
    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        Goal goal = assemble(bcc, approxPlaceable, logMissing, false);
        if (goal == null && hasDeferredCells()) {
            // TRIED AND REJECTED: cancelling the backoff here, on the reasoning that a wait buys nothing when there is
            // no other work to make room for. It reads well and measured badly -- the facings scenario fell from 82 of
            // 96 to 47. Retrying every tick means running the stance search every tick, and that search is expensive
            // (24 stances x 6 faces x 5 aim points, each now re-simulated four more times for the jitter margin). The
            // tick budget went into re-deriving the same answer instead of into building.
            //
            // The backoff is therefore doing a second job nobody designed it for: rate-limiting an expensive search.
            // Left alone until that search is cheap enough for the reasoning to hold.
            goal = assemble(bcc, approxPlaceable, false, true);
            if (goal != null && buildTick % 120 == 0) {
                logMechanic("Nothing is currently placeable, so routing toward a deferred cell instead of standing"
                        + " still -- a retry from the same failed spot fails the same way");
            }
        }
        return goal;
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing,
                          boolean includeDeferred) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> interactable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> noLongerWork = new ArrayList<>();
        incorrectPositions.forEach(pos -> {
            final boolean watched = CellWatch.isWatched(pos.x, pos.y, pos.z);
            if (!includeDeferred && isCellParked(pos.x, pos.y, pos.z)) {
                if (watched) {
                    CellWatch.note(buildTick, pos.x, pos.y, pos.z, "assemble", "skipped: on deferral backoff");
                }
                return;
            }
            BlockState state = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
            if (desired == null) {
                noLongerWork.add(pos);
                if (watched) {
                    CellWatch.note(buildTick, pos.x, pos.y, pos.z, "assemble",
                            "dropped: outside the current layer mask");
                }
            } else if ((state.getBlock() instanceof LiquidBlock
                    || (excavating && snakeTreatAsFluid(state, true)))
                    && desired.getBlock() instanceof AirBlock) {
                // A source can be displaced by pathing onto the cell above: CalculationContext selects an allowed
                // throwaway for the supporting block, then the normal break pass removes it to produce AIR. Flowing
                // liquid has no stable block to displace, so retain it unresolved until the world settles.
                if (excavating ? !snakeSourceReadyForPlug(state, true) : MovementHelper.possiblyFlowing(state)) {
                    flowingLiquids.add(pos);
                } else if (bcc.hasThrowaway) {
                    sourceLiquids.add(pos);
                }
            } else if (isSecondaryHalf(desired)
                    && (state.getBlock() instanceof AirBlock || state.getBlock() instanceof LiquidBlock)) {
                // Door upper / bed head completes automatically when its primary half is placed.
            } else if (desired.getBlock() instanceof AirBlock) {
                if (state.isAir()) noLongerWork.add(pos);
                else breakable.add(pos);
            } else if (state.getBlock() instanceof AirBlock
                    || state.getBlock() instanceof LiquidBlock
                    || MovementHelper.isReplaceable(pos.x, pos.y, pos.z, state, bcc.bsi)) {
                if (containsBlockState(approxPlaceable, desired)) {
                    placeable.add(pos);
                    if (watched) {
                        CellWatch.note(buildTick, pos.x, pos.y, pos.z, "assemble",
                                "placeable: material reachable, want=" + blockName(desired));
                    }
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                    // THE silent exit of A29's post-mortem, now audible for the cell under investigation.
                    if (watched) {
                        CellWatch.note(buildTick, pos.x, pos.y, pos.z, "assemble",
                                "MISSING MATERIAL: nothing in approxPlaceable places " + blockName(desired));
                    }
                }
            } else if (interactionClicks(state, desired) > 0) {
                // Right block, right facing — only its open/delay/mode/note is off. Route to it and right-click,
                // don't break it: breaking would just re-place the default state and loop.
                interactable.add(pos);
            } else {
                breakable.add(pos);
            }
        });
        incorrectPositions.removeAll(noLongerWork);
        if (electedBreak) {
            if (breakable.contains(electedCell)) {
                // Preserve the exact chosen removal while the body moves. This path deliberately precedes every
                // placement/material gate; only the real classification above may revoke it.
                return electedGoal;
            }
            clearElectedTarget();
        }
        // THE CENSUS, MOVED OUT OF A BRANCH IT COULD NOT REACH.
        //
        // It used to sit inside `if (toBreak.isEmpty())`, which is itself only reached when `toPlace` is empty -- so it
        // reported the cells with no hotbar material ONLY on ticks when the builder had nothing whatsoever to do. On a
        // run that always has something placeable somewhere, that is never, and A29 read the resulting zero as "missing
        // was empty all run" and closed the hotbar hypothesis on it. The zero was the census never being asked.
        //
        // Here it is asked every 200 build ticks whatever else is happening, which is the only position from which it
        // can say anything about a cell that is starved while the bot is busy elsewhere. Pure reporting: `missing` is
        // read, never written, and no branch below depends on this block.
        if (!missing.isEmpty() && buildTick - lastMissingCensusTick >= 200) {
            lastMissingCensusTick = buildTick;
            int cells = missing.values().stream().mapToInt(Integer::intValue).sum();
            String top = missing.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(6)
                    .map(e -> e.getValue() + "x " + blockName(e.getKey()))
                    .collect(Collectors.joining(", "));
            logMechanic("Cells with no hotbar material: " + cells + " in " + missing.size()
                    + " materials; top: " + top);
        }
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        // interaction cells route exactly like break cells (get within reach); the onTick interaction pass then
        // right-clicks instead of mining, and toBreakNearPlayer already refuses to break them.
        interactable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        // TRIED AND REJECTED: routing only to cells that already have something to click against, and holding the rest
        // back until nothing anchored is left. The reasoning is sound -- walking to a cell with no solid neighbour
        // cannot end in a placement, and its support is usually a cell further down the same queue -- but it took the
        // `oriented` scenario from 7/7 to 1/7, with the bot standing next to the cell it was deprioritised away from.
        //
        // That is the fourth attempt in a row at making the builder smarter about WHICH cell to walk to, and the fourth
        // to measure worse (2715 -> 2030 -> 944 for the retirement variants, 82 -> 47 for the backoff one). The
        // selection loop is more tightly coupled than it looks, and guessing at it one heuristic at a time is not
        // working. What is missing is not another rule but a way to see the cost: a counter for walks that ended in a
        // placement versus walks that ended in nothing. Without that, every one of these is a coin toss dressed up as
        // an argument.
        // ONE CANDIDATE AT A TIME, NEAREST FIRST -- the owner's part 1, and the reason the previous revision died.
        //
        // This used to build a goal for EVERY placeable cell and hand the pathfinder a composite of all of them. That
        // was survivable while the goal was a cheap GoalAdjacent, and became fatal the moment every goal required a
        // stance search: run 60d528e6 produced 444,891 STANCE events over 5620 ticks -- about 79 searches per tick --
        // spent 86% of its ticks in act=searching, and made exactly ONE walk in the entire run. 162 of 15004 cells.
        //
        // The 20 ms clock did not save it, and could not: it bounds the work per tick, not the WASTE. The waste was
        // structural. A cell whose position IS proven gets no verdict note (correctly -- the note records negatives),
        // so it lived only in orientedGoalCache, which is cleared at the top of every tick. Every proven cell was
        // therefore re-derived from scratch every single tick, for a composite that only ever needed one of them.
        //
        // So the loop stops at the first candidate that yields a proven position. Skipped candidates cost a hash lookup
        // each (the verdict note), not a search. Nearest first, because the nearest reachable work is the cheapest, and
        // because it keeps the old behaviour's priority: work the bot is standing in front of goes first.
        // AND IT STAYS ELECTED. "Es gibt immer nur EIN Ziel" has to hold ACROSS ticks, not only within one, and the
        // first version of this loop got that wrong in a way that deadlocked the build.
        //
        // MEASURED, run acf66470 at feet 87,-59,123: the election below re-ran every tick with ctx.playerFeet() as the
        // sort key, so the winner was always the cell directly north of wherever the bot happened to stand. Two
        // candidates, 87,-59,122 (26 proven stances) and 88,-59,122 (3), each won while the bot was in the column south
        // of it. Their goals are DISJOINT: pursuing 88,-59,122 aims at a stance in the other column, which carries the
        // bot across x=88.0, which elects the other cell, whose goal is symmetric in x and carries it back.
        // 353 flips in 1921 ticks, cycle length 10, the whole time inside a 1.66 x 0.47 block box. One placement.
        //
        // The stance composite was NOT the culprit and is left alone: standingGoalFor sorts from the TARGET cell
        // (nearestToPlayer = false), so its order cannot move with the bot -- proven by every STANCE line for a cell
        // being byte-identical across 1083 and 856 events.
        //
        // Worse than the loop itself: each flip counted as "a genuinely new route" in navigationMadeProgress and was
        // granted a grace tick, which set noProgressTicks = 0. The oscillation switched off the very counter meant to
        // notice it -- 1800 stalled ticks at noProgressTicks 0..2 -- so no watchdog could have caught this either.
        //
        // The latch is released only by something REAL: the cell became correct, it is gone from the work set, it got
        // deferred, or it no longer yields a proven position. Never by the bot having moved.
        // Only the offer set is constrained. The placement solver, facing simulation and target election below stay
        // untouched; builder/v3's geometry remains the source of truth. The rear edge is pinned to the oldest open
        // row, so the front cannot surround a hole before that row has drained.
        List<BetterBlockPos> offered = placeable;
        if (buildInRows && rowActiveBandStart != Integer.MIN_VALUE
                && rowActiveFrontier != Integer.MIN_VALUE) {
            boolean sweepX = sweepAlongX();
            List<BetterBlockPos> slice = new ArrayList<>();
            for (BetterBlockPos pos : placeable) {
                if (rowBandContains(sweepX ? pos.x : pos.z, rowActiveBandStart, BAND_ROWS)
                        && (sweepX ? pos.z : pos.x) == rowActiveFrontier) {
                    slice.add(pos);
                }
            }
            List<BetterBlockPos> anchored = new ArrayList<>();
            for (BetterBlockPos pos : slice) {
                if (hasSolidNeighbour(pos, bcc)) {
                    anchored.add(pos);
                }
            }
            offered = !anchored.isEmpty() ? anchored : slice;
            if (slice.isEmpty()) {
                // A missing palette item at the global frontier is a material request, not permission to build a later
                // slice. Keeping the offer empty makes the existing material pause/restock path handle it.
                // A missing palette item at the frontier is a restock request, not permission to build a later slice.
                bandFallbacks++;
            }
            if (logMissing && buildTick % 200 == 0) {
                int bandOrigin = sweepX ? origin.getX() : origin.getZ();
                boolean forward = rowBandRunsForward(rowActiveBandStart, bandOrigin, BAND_ROWS);
                logMechanic("Band: rows " + rowActiveBandStart + ".."
                        + (rowActiveBandStart + BAND_ROWS - 1) + " on " + (sweepX ? "x" : "z") + ", "
                        + (forward ? "vorwaerts" : "rueckwaerts") + " global slice at "
                        + (sweepX ? "z=" : "x=") + rowActiveFrontier + " with " + offered.size()
                        + " anchored of " + slice.size() + " placeable cell(s)");
            }
        }

        List<Goal> toPlace = new ArrayList<>();
        BetterBlockPos here = ctx.playerFeet();
        List<BetterBlockPos> byDistance = new ArrayList<>(offered);
        byDistance.sort(java.util.Comparator
                .comparingLong((BetterBlockPos p) -> {
                    long dx = p.x - here.x;
                    long dy = p.y - here.y;
                    long dz = p.z - here.z;
                    return dx * dx + dy * dy + dz * dz;
                })
                // Deterministic tie-break. Without one, two equidistant cells fall back on the work set's insertion
                // order, which is scan order and moves with the bot -- a second, latent flip source right next to the
                // one that just cost a run.
                .thenComparingInt(p -> p.x).thenComparingInt(p -> p.y).thenComparingInt(p -> p.z));
        // The latched cell is tried FIRST, whatever the distances now say.
        if (electedCell != null) {
            if (!incorrectPositions.contains(electedCell) || isCellParked(electedCell.x, electedCell.y, electedCell.z)) {
                clearElectedTarget();
            } else {
                BlockState wanted = bcc.getSchematic(electedCell.x, electedCell.y, electedCell.z, bcc.bsi.get0(electedCell));
                // A hotbar swap or an exhausted per-tick search budget is not a world invalidation.
                Goal rowGoal = mapArtPlacementGoal(electedCell, bcc);
                CellUrteil verdict = wanted != null && hotbarStackThatPlaces(wanted) == null
                        ? new CellUrteil.Unbekannt("waiting for the chosen material on the hotbar")
                        : rowGoal != null ? new CellUrteil.Setzen(rowGoal) : urteileUeber(electedCell, bcc);
                Goal held = acceptElectedVerdict(verdict);
                if (electedCell != null) {
                    if (held != null) toPlace.add(held);
                    byDistance = java.util.Collections.emptyList();
                }
            }
        }
        for (BetterBlockPos pos : byDistance) {
            final boolean watched = CellWatch.isWatched(pos.x, pos.y, pos.z);
            if (placeable.contains(pos.below()) || placeable.contains(pos.below(2))) {
                if (watched) {
                    CellWatch.note(buildTick, pos.x, pos.y, pos.z, "goal",
                            "held back: a cell below it is still queued, so its support is not in yet");
                }
                continue;
            }
            Goal goal = placementGoal(pos, bcc);
            if (goal != null) {
                toPlace.add(goal);
                if (watched) {
                    CellWatch.note(buildTick, pos.x, pos.y, pos.z, "goal", "routed: " + goal);
                }
                if (!pos.equals(electedCell)) {
                    electedCell = pos;
                    electedGoal = goal;
                    // Every new target starts in the walk-only pass. Permission is never inherited.
                    scaffoldPassAllowed = false;
                    BuildTrace.cell(buildTick, "ELECT", pos.x, pos.y, pos.z,
                            "latched as the single target until it is placed, deferred or loses its stance");
                }
                break;   // one target, proven, exact. Nothing else is walked to.
            }
            if (watched) {
                CellWatch.note(buildTick, pos.x, pos.y, pos.z, "goal", "placementGoal returned no goal");
            }
        }
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));
        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (logMissing && !missing.isEmpty()) {
                // SAID ONCE, AND SAID IN WORDS SOMEBODY CAN ACT ON.
                //
                // Every other report in this method is throttled and this one never was, so it went out on every
                // tick that found nothing to place and nothing to break -- which, while a colour is missing, is
                // every tick. A customer filmed his chat filling with it. Worse, it went out in BlockState's own
                // toString: "456x Block{minecraft:white_wool}" reads like a count of missing ITEMS, and the number
                // is a count of CELLS. The owner read his own log and concluded the bot had placed a wrong block.
                //
                // Now: at most one line per ten seconds, and never twice for the same shortage, phrased as the
                // cells that are waiting and the material they are waiting for.
                String text = missing.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .map(e -> String.format("%d cell(s) waiting for %s", e.getValue(), blockName(e.getKey())))
                        .collect(Collectors.joining("\n"));
                // AND, not OR. A shortage changes constantly while the build eats into it, so "print when the text
                // is new" is not a throttle at all -- it is the old every-tick behaviour with extra steps. Both
                // conditions have to hold: ten seconds must have passed AND it must have something new to say.
                if (buildTick - lastMissingReportTick >= 200 && !text.equals(lastMissingReportText)) {
                    lastMissingReportTick = buildTick;
                    lastMissingReportText = text;
                    logDirect("Nothing in the inventory can fill these cells:");
                    logDirect(text);
                }
            }
            // The "cells with no hotbar material" census used to be here. It is now above, before the two returns that
            // made this line unreachable on any tick with work in it -- see the comment there.
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        if (electedCell == null && !breakable.isEmpty()) {
            BetterBlockPos target = breakable.stream().min(java.util.Comparator
                    .comparingDouble((BetterBlockPos pos) -> pos.distSqr(here))
                    .thenComparingInt(pos -> pos.x).thenComparingInt(pos -> pos.y).thenComparingInt(pos -> pos.z))
                    .orElseThrow();
            electedCell = target;
            electedGoal = breakGoal(target, bcc);
            electedBreak = true;
            scaffoldPassAllowed = false;
            BuildTrace.cell(buildTick, "ELECT-BREAK", target.x, target.y, target.z,
                    "latched removal; no placement material required");
            return electedGoal;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    /** Blocks whose final facing is chosen by the player's yaw at placement (so WHERE the bot stands matters):
     *  a "facing" property that a naive adjacent approach can get wrong. Slabs (type) and logs (axis) are excluded
     *  on purpose — they place fine from any side, and running the standing search for them would be pure overhead. */
    private static boolean isOrientationSensitive(BlockState state) {
        if (state == null) {
            return false;
        }
        // ONLY the blocks whose facing genuinely depends on WHERE the player stands AND that the default "get
        // adjacent" goal gets wrong: doors, beds, redstone diodes (repeater/comparator), observers, trapdoors.
        // Everything else with a facing (stairs, chests, furnaces, ...) places fine from any side, so running the
        // standing search for them is pure overhead — and worse, its over-approximation can park the bot idle on a
        // false-positive stance (the "just stands there" the user hit live). Keep this list tight.
        Block b = state.getBlock();
        // Pistons and dispensers were added here once and it cost etz-basalt 400 blocks: 1438 placed fell to 1025 and
        // stayed there when the change was the only difference. They do take their facing from
        // getNearestLookingDirection, so the reasoning was sound -- but routing all 2048 of basalt's pistons through
        // the oriented standing search replaces a cheap GoalAdjacent with a specific stance, and in a sparse layer that
        // stance usually does not exist yet, so the bot idles instead of building what it can.
        //
        // The project has been here before (0.3.59 -> 0.3.60, same list, same idle). The lesson holds: this list is
        // about which blocks the DEFAULT goal gets wrong, not which blocks have a facing. Piston orientation is handled
        // where it belongs -- in the stance ordering, which costs nothing when no stance fits.
        return b instanceof DoorBlock || b instanceof BedBlock || b instanceof DiodeBlock
                || b instanceof ObserverBlock || b instanceof TrapDoorBlock;
    }

    /** The UPPER half of a door or the HEAD of a bed — the second cell of a two-block placement that vanilla fills
     *  automatically when the BASE (door lower / bed foot) is placed. It can never be placed directly, so the builder
     *  must not chase it as its own target (that's the bot fumbling at the door): it completes on its own once the
     *  base goes down; if the base is temporarily unbuildable both cells remain unresolved for a later retry. */
    /** An interaction whose open/closed state changes whether the bot can walk through/over the block — door,
     *  trapdoor, fence gate. These conflict with the pathfinder (which opens them to cross) and so are deferred
     *  while the bot is still traversing them. Repeater delay / comparator mode / note pitch never affect walking. */
    private static boolean isTraversalInteraction(BlockState desired) {
        if (desired == null) {
            return false;
        }
        Block b = desired.getBlock();
        return b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock;
    }

    private static boolean isSecondaryHalf(BlockState desired) {
        if (desired == null) {
            return false;
        }
        Block b = desired.getBlock();
        if (b instanceof DoorBlock) {
            return desired.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
        }
        if (b instanceof BedBlock) {
            return desired.getValue(BedBlock.PART) == BedPart.HEAD;
        }
        return false;
    }

    /**
     * A half that is not missing so much as not due yet: it appears by itself the moment its partner is placed.
     *
     * <p>A door's upper half and a bed's head are not independently placeable -- one item creates both blocks -- and
     * every placement path here already refuses to target them ({@link #isSecondaryHalf}). What no path did was stop
     * COUNTING them as unresolved, and on a layered build that is the difference between a stall and a build: the
     * upper half sits in its own layer, nothing can ever place it there, and the layer cannot complete. A lighthouse
     * run reported "no actionable progress" against 71,-58,74 -- a door's upper half -- eighteen times in a row,
     * diagnosing "nothing to hold spruce_door here", which was true and unfixable and not the actual problem.
     *
     * <p>So while the partner is still missing, this half is simply not work. If the partner IS in place and this half
     * still disagrees, that is a real fault and it stays counted.
     */
    private boolean isPendingSecondaryHalf(BlockState desired, int x, int y, int z, BuilderCalculationContext bcc) {
        if (!isSecondaryHalf(desired)) {
            return false;
        }
        int px = x;
        int py = y;
        int pz = z;
        if (desired.getBlock() instanceof DoorBlock) {
            py = y - 1;                       // the lower half is always directly below
        } else {
            Direction footward = desired.getValue(BedBlock.FACING).getOpposite();
            px = x + footward.getStepX();
            pz = z + footward.getStepZ();
        }
        BlockState partnerDesired = bcc.getSchematic(px, py, pz, bcc.bsi.get0(px, py, pz));
        if (partnerDesired == null || partnerDesired.getBlock() != desired.getBlock()) {
            return false;   // no partner in the schematic: this really is an orphan half, keep reporting it
        }
        return !valid(bcc.bsi.get0(px, py, pz), partnerDesired, false);
    }

    /**
     * Is this position named by the schematic but NOT a piece of work of its own?
     *
     * <p>DIES IST DIE EINZIGE DEFINITION DAVON, WAS EINE ZELLE IST, und sie muss einzig bleiben. S0b der
     * Spezifikation laedt "die Zellen der Ebene E" -- wenn zwei Stellen im Prozess verschieden beantworten, welche
     * Positionen das sind, dann zaehlt die Ebenen-Verifikation (S10) andere Zellen als die Mikro-Schleife gebaut
     * hat, und P6b bricht mit LAYER_VERIFICATION_FAILED auf korrekter Arbeit ab.
     *
     * <p>Drei Familien gehoeren hierher, und alle drei aus demselben Grund: es gibt keinen Klick, der sie erzeugt.
     * <ul>
     *   <li><b>Kolbenkopf und wandernder Kolben.</b> Beide entstehen ausschliesslich dadurch, dass ein Kolben
     *       ausfaehrt. Kein Gegenstand setzt sie. Als Arbeit behandelt sind sie eine Zelle, die nie fertig werden
     *       kann -- und weil kein Gegenstand sie setzt, meldet {@code firstMissingMaterial} fuer den GANZEN Bau
     *       MATERIALS_MISSING, also die falsche Antwort auf die falsche Frage.</li>
     *   <li><b>Fluessigkeitsquellen.</b> Wasser und Lava kommen aus einem Eimer, nicht aus einem Blockgegenstand;
     *       die Engine traegt keine Eimer, der Fluessigkeitsdurchgang des Clients tut es. Geprueft wird der BLOCK,
     *       nicht der Fluidzustand -- eine geflutete Treppe hat ebenfalls einen Fluidzustand und ist sehr wohl
     *       setzbar.</li>
     *   <li><b>Zweite Haelften.</b> Tuer oben, Bettkopf, hohe Pflanze oben: sie erscheinen, wenn die erste Haelfte
     *       gesetzt wird. Die Frage nach Tuer und Bett gehoert {@link #isPendingSecondaryHalf} und wird hier
     *       DELEGIERT statt nachgebaut, damit es sie genau einmal gibt.</li>
     * </ul>
     */
    private boolean notACellOfItsOwn(BlockState desired, int x, int y, int z, BuilderCalculationContext bcc) {
        if (desired == null) {
            return false;
        }
        Block block = desired.getBlock();
        // Nach Blockidentitaet, nicht nach Klasse: PistonHeadBlock und MovingPistonBlock liegen im Unterpaket
        // ...block.piston und sind ueber den Sammelimport nicht sichtbar. Zwei Konstanten sagen dasselbe und
        // koennen sich nicht durch ein Mapping-Update verschieben.
        if (block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON) {
            return true;
        }
        if (block instanceof LiquidBlock) {
            return true;
        }
        if (block instanceof DoublePlantBlock
                && desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        return isPendingSecondaryHalf(desired, x, y, z, bcc);
    }

    /** The hotbar stack (slots 0-8) that would place this block, or null if we aren't currently holding it. */
    /**
     * The hotbar stack that can place {@code desired}, or null.
     *
     * <p>Matched by ITEM, not by block identity. A torch on the hotbar is {@code Blocks.TORCH}; a schematic asking
     * for {@code wall_torch} found no match and the cell was deferred as "in inventory but not ready on the hotbar"
     * -- while the torch sat in hand. Worse, the fetch path (itemCanPlaceBlock) already compared by item and so
     * considered the material present, and never fetched anything: one half of the builder saw a torch the other
     * half could not use, forever. Same rule on both sides ends that standoff.
     */
    private ItemStack hotbarStackThatPlaces(BlockState desired) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) {
                continue;
            }
            net.minecraft.world.level.block.Block block = ((BlockItem) s.getItem()).getBlock();
            if (block == desired.getBlock()
                    || (s.getItem() != net.minecraft.world.item.Items.AIR
                        && s.getItem() == desired.getBlock().asItem())) {
                return s;
            }
        }
        return null;
    }

    /** Uses the same walk-through/walk-on rules as the pathfinder; slabs, stairs, paths, and clearable vegetation
     *  remain legal recovery stances while fluids (where onGround can never pass) do not. */
    private boolean isStandable(int x, int y, int z) {
        BetterBlockPos feetPos = new BetterBlockPos(x, y, z);
        BlockState feet = ctx.world().getBlockState(feetPos);
        BlockState head = ctx.world().getBlockState(feetPos.above());
        return feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && MovementHelper.canWalkThrough(ctx, feetPos)
                && MovementHelper.canWalkThrough(ctx, feetPos.above())
                && MovementHelper.canWalkOn(ctx, feetPos.below());
    }

    /** The stance search already owns the current block view; reuse it for all pathing predicates. */
    private boolean isStandable(int x, int y, int z, BlockStateInterface bsi) {
        BetterBlockPos feetPos = new BetterBlockPos(x, y, z);
        BlockState feet = ctx.world().getBlockState(feetPos);
        BlockState head = ctx.world().getBlockState(feetPos.above());
        return feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && MovementHelper.canWalkThrough(bsi, x, y, z)
                && MovementHelper.canWalkThrough(bsi, x, y + 1, z)
                && MovementHelper.canWalkOn(bsi, x, y - 1, z);
    }

    /**
     * Somewhere the bot could stand -- either because there is already a floor, or because it can lay one.
     *
     * <p>{@link #isStandable} asks only about the world as it is, and for a sparse structure that is the wrong
     * question. etz-basalt's lowest layer is 936 blocks spread over a 63x63 footprint, so it is mostly air: almost
     * nowhere at the next level up has a floor yet. The stance search rejected 96 of 145 candidates as "not standable"
     * for that reason alone, including every stance that could have produced the 1264 cells whose facing is UP or DOWN
     * -- those need the bot beside the cell looking steeply down, and beside the cell is exactly where there was no
     * floor.
     *
     * <p>The pathfinder can already bridge and pillar to reach a goal; that is what {@code hasThrowaway} and the
     * scaffold block are for. Filtering candidate stances by the CURRENT floor threw away the stances it would have
     * built its way to. A missing floor is allowed when the cell below could hold a placed block and is outside
     * the schematic, or is explicitly AIR in an ordinary 3D build. A real requested template block stays reserved;
     * row mode keeps its exact-template-pixel exception below and does not scaffold into planned AIR.
     *
     * <p>An unreachable stance costs a bounded walk that {@code routeTick} already gives up on. Refusing to consider it
     * costs the cell.
     */
    private boolean isStandableOrScaffoldable(int x, int y, int z, BuilderCalculationContext bcc) {
        if (isStandable(x, y, z, bcc.bsi)) {
            return true;
        }
        BetterBlockPos feetPos = new BetterBlockPos(x, y, z);
        if (!MovementHelper.canWalkThrough(bcc.bsi, x, y, z)
                || !MovementHelper.canWalkThrough(bcc.bsi, x, y + 1, z)) {
            return false;   // the body does not fit; no amount of scaffolding helps
        }
        BetterBlockPos below = feetPos.below();
        BlockState currentFloor = ctx.world().getBlockState(below);
        if (!MovementHelper.isReplaceable(below.x, below.y, below.z, currentFloor, bcc.bsi)) {
            return false;   // something is already there that is not a floor (a fluid, a plant); leave it alone
        }
        BlockState plannedFloor = bcc.getSchematic(below.x, below.y, below.z, currentFloor);
        if (buildInRows && bcc.rowTemplatePlacementIsLicensedAt(below.x, below.y, below.z)
                && plannedFloor != null && !plannedFloor.isAir()
                && containsBlockState(bcc.placeable, plannedFloor)
                && !placementStateIsGeometrySensitive(plannedFloor)) {
            // This is not scaffolding: the route can extend the walking surface by putting the exact requested map
            // pixel under its next step. MovementHelper independently verifies wouldPlaceTemplateBlockAt and selects
            // that state, while the row-mode licence still forbids every placement where the schematic wants air.
            return true;
        }
        if (!bcc.hasThrowaway) {
            return false;   // no template pixel here and nothing to build an ordinary floor out of
        }
        return plannedFloor == null || (!buildInRows && plannedFloor.isAir());
    }

    /** Raytrace from a hypothetical eye using the same quantized rotation and world clip as possibleToPlace. */
    private BlockHitResult placementRayFrom(Vec3 eye, Rotation rawRotation, double reach) {
        Rotation actual = princeps.getLookBehavior().getAimProcessor().peekRotationExact(rawRotation);
        Vec3 direction = RotationUtils.calcLookDirectionFromRotation(actual);
        Vec3 end = eye.add(direction.x * reach, direction.y * reach, direction.z * reach);
        HitResult hit = ctx.world().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, ctx.player()));
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? (BlockHitResult) hit : null;
    }

    /**
     * Eye/body positions inside one candidate stance. A centred crouching player cannot see the outward side face of
     * the block under their feet, yet that is the face used to bridge a horizontal picture into open air. A human
     * sneaks toward the edge. Only row builds receive those two extra positions; the centred candidate, current
     * server-side rotation model, hypothetical-body collision check and placement simulation all remain unchanged.
     */
    private Vec3[] placementEyeCandidates(BetterBlockPos stance, double eyeHeight, int tx, int tz) {
        Vec3 center = new Vec3(stance.x + 0.5, stance.y + eyeHeight, stance.z + 0.5);
        if (!buildInRows) {
            return new Vec3[]{center};
        }
        double dx = (tx + 0.5) - center.x;
        double dz = (tz + 0.5) - center.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4) {
            return new Vec3[]{center};
        }
        double ux = dx / length;
        double uz = dz / length;
        return new Vec3[]{
                center,
                center.add(ux * 0.30, 0.0, uz * 0.30),
                center.add(ux * 0.45, 0.0, uz * 0.45),
        };
    }

    /** A row-build candidate without a solid neighbour has no vanilla placement face yet. */
    private boolean hasSolidNeighbour(BetterBlockPos pos, BuilderCalculationContext bcc) {
        for (Direction side : SIX_NEIGHBOURS) {
            BlockState neighbour = bcc.bsi.get0(pos.x + side.getStepX(), pos.y + side.getStepY(),
                    pos.z + side.getStepZ());
            if (!neighbour.isAir() && neighbour.blocksMotion()) {
                return true;
            }
        }
        return false;
    }

    /** Would a real crouched placement from the center of a candidate feet cell land the desired state? This mirrors
     *  possibleToPlace: same support face, quantized aim, occlusion clip, and placement-result predicate. */
    private boolean orientationAchievableFrom(Vec3 eye, int tx, int ty, int tz, BlockState desired, ItemStack stack,
                                              double reach, BuilderCalculationContext bcc) {
        return orientationAchievableFrom(eye, tx, ty, tz, desired, stack, reach, bcc, null, null);
    }

    private boolean orientationAchievableFrom(Vec3 eye, int tx, int ty, int tz, BlockState desired, ItemStack stack,
                                              double reach, BuilderCalculationContext bcc, int[] stages) {
        return orientationAchievableFrom(eye, tx, ty, tz, desired, stack, reach, bcc, stages, null);
    }

    /**
     * @param stages optional diagnostic tally, incremented per rejected attempt:
     *               [0] nothing solid to click against, [1] out of reach, [2] the ray missed the intended face,
     *               [3] the simulated placement was not the block we want. Null in the hot path. Counting inside
     *               the real method rather than in a parallel copy is deliberate: a diagnostic that mirrors the
     *               logic it explains drifts away from it and then explains the wrong thing.
     */
    private boolean orientationAchievableFrom(Vec3 eye, int tx, int ty, int tz, BlockState desired, ItemStack stack,
                                              double reach, BuilderCalculationContext bcc, int[] stages,
                                              List<String> rejectedSamples) {
        BlockPos targetPos = new BlockPos(tx, ty, tz);
        // Match the real search's own gates (possibleToPlace): if the block can't even survive here, or its collision
        // box is obstructed, no stance helps — bail so we never route the bot to a dead spot it'll idle on.
        // Counted separately: these are the only rejections no stance can fix, and leaving them uncounted produced
        // the nonsense line "48 stances cannot aim, 0 rejections" that hid the self-obstruction bug for three runs.
        if (!desired.canSurvive(ctx.world(), targetPos)) {
            if (stages != null) {
                stages[4]++;
            }
            return false;
        }
        // Stance search: ignore our own body, we are evaluating where to STAND, not clicking from here.
        if (!placementPlausible(targetPos, desired, true)) {
            if (stages != null) {
                stages[5]++;
            }
            return false;
        }
        int solidNeighbours = 0;
        // Only the face(s) the desired state actually allows. For a wall sign that is one, not six.
        for (Direction against : supportDirectionsFor(desired)) {
            BlockPos placeAgainstPos = new BlockPos(tx, ty, tz).relative(against);
            BlockState placeAgainstState = bcc.bsi.get0(placeAgainstPos.getX(), placeAgainstPos.getY(), placeAgainstPos.getZ());
            // NOTE: no per-direction tally here. A wall torch has one solid neighbour and five of air, so counting
            // each airy direction reported "144 nothing solid to click" for a cell whose wall was standing right
            // there -- a number that reads like the cause and is pure arithmetic. stages[0] is raised once, at the
            // end, only if NO direction offered anything to click.
            if (MovementHelper.isReplaceable(placeAgainstPos.getX(), placeAgainstPos.getY(), placeAgainstPos.getZ(), placeAgainstState, bcc.bsi)) {
                continue;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            solidNeighbours++;
            AABB aabb = shape.bounds();
            for (Vec3 hitVec : aimPointsOnFace(eye, placeAgainstPos, aabb, against, desired)) {
                if (eye.distanceTo(hitVec) > reach) {
                    if (stages != null) {
                        stages[1]++;
                    }
                    continue;
                }
                Rotation rawRot = RotationUtils.calcRotationFromVec3d(eye, hitVec, ctx.playerRotations());
                Rotation actualRot = princeps.getLookBehavior().getAimProcessor().peekRotationExact(rawRot);
                BlockHitResult hit = placementRayFrom(eye, rawRot, reach);
                if (hit == null || !hit.getBlockPos().equals(placeAgainstPos)
                        || hit.getDirection() != against.getOpposite()) {
                    if (stages != null) {
                        stages[2]++;
                    }
                    continue;
                }
                // THE BODY GOES WHERE THE EYE IS. This is the stance search: the question is "if I stood at this
                // candidate, would the wanted block land", and the bot is by definition NOT there yet. Asking it with
                // the body still at the old spot answered a different question and answered it wrong -- twice measured,
                // see simulatePlacement. The feet are the eye minus the crouched eye height, which is exactly how the
                // eye was derived from the candidate a few lines up in placementStancesFor.
                // Same expression placementStancesFor used to build the eye from the candidate, so this inverts it
                // exactly rather than approximately. Even if a caller ever passes a standing eye, the 0.35 difference
                // stays inside the same cell, which is all the obstruction test cares about.
                Vec3 bodyAt = new Vec3(eye.x, eye.y - ctx.player().getEyeHeight(Pose.CROUCHING), eye.z);
                BlockState result = simulatePlacement(stack, hit, actualRot, bodyAt);
                if (placementResultAccepted(result, desired, tx, ty, tz, bcc)) {
                    return true;
                }
                // EVERY DISCARDED CANDIDATE, NAMED. BuildTrace.rejected and its -PfullTrace flag existed all along but
                // were wired only into the FROZEN v3 tree, so in the engine actually being run the switch wrote
                // nothing -- which is why "what did it try and why did each attempt fail" could not be answered from a
                // log. Behind the flag because this is the hottest path the builder has: with it off the cost is one
                // static boolean, with it on it is one line per candidate per face per aim point.
                // Gated on the WATCH LIST as well as the flag. Unbounded, this writes one line per candidate per face
                // per aim point -- with 145 candidates and thousands of searches per run that is millions of lines, and
                // an instrument that has to be grepped out of its own noise is not an instrument. Naming the cell in
                // autonomy/watch-cells.txt turns it into a few hundred lines about exactly the question being asked.
                // FULL STATE, not blockName. The first version of this line wrote blockName(result), which strips the
                // properties -- so 623608 recorded rejections all read "would=observer want=observer" and could not
                // answer the only question they existed for, namely WHICH facing would have landed. An instrument that
                // omits the field under investigation is worse than none, because it looks like evidence.
                //
                // Scoped to the watch list as well as the flag, and that is a correction of the owner's "let it write
                // millions": unscoped, the client spent so long writing that the bench never reported a single sample
                // and the bot only twitched -- so there was no behaviour left to diagnose. Naming the cell in
                // autonomy/watch-cells.txt yields the same information about that cell at a hundredth of the cost.
                if (BuildTrace.isVerbose() && CellWatch.isWatched(tx, ty, tz)) {
                    BuildTrace.rejected("wrong-block would=" + (result == null ? "nothing" : String.valueOf(result))
                                    + " want=" + String.valueOf(desired)
                                    + " yaw=" + String.format(java.util.Locale.ROOT, "%.0f", actualRot.getYaw())
                                    + " pitch=" + String.format(java.util.Locale.ROOT, "%.0f", actualRot.getPitch())
                                    + " against=" + placeAgainstPos + " face=" + against.getOpposite(),
                            tx + "," + ty + "," + tz,
                            String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", bodyAt.x, bodyAt.y, bodyAt.z));
                }
                if (stages != null) {
                    stages[3]++;
                    if (rejectedSamples != null && rejectedSamples.size() < 3) {
                        // What WOULD land, named. "Wrong block" is not actionable; "we aimed at the floor below and
                        // got facing=north when the schematic wants east" points straight at the cause.
                        rejectedSamples.add("from " + String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f",
                                eye.x, eye.y, eye.z)
                                + " clicking " + against.getOpposite() + " of "
                                + hit.getBlockPos().getX() + "," + hit.getBlockPos().getY() + "," + hit.getBlockPos().getZ()
                                + " yaw=" + String.format(java.util.Locale.ROOT, "%.0f", actualRot.getYaw())
                                + " would place " + (result == null
                                        ? "nothing (" + (lastSimulationRefusal == null
                                                ? "vanilla refused it" : lastSimulationRefusal) + ")"
                                        : result.toString()));
                    }
                }
            }
        }
        if (stages != null && solidNeighbours == 0) {
            stages[0]++;
        }
        return false;
    }

    /** Route to the standing cell(s) from which the desired orientation is actually placeable (nearest first).
     *  Returns null for orientation-free blocks, when we aren't holding the item, or when no such stance is found —
     *  in every such case placementGoal falls back to its normal "get adjacent" goal. */
    private Goal orientedStandingGoal(int tx, int ty, int tz, BlockState desired, BuilderCalculationContext bcc) {
        // TWO reasons to spend the precise search, and the second one is new.
        //
        // The first is the block: doors, beds, diodes, observers and trapdoors take their facing from where the bot
        // STANDS, so "any adjacent cell" is not merely worse for them, it is often wrong. That list is deliberately
        // short. Widening it to pistons and dispensers was measured on 25.07 and cost about 400 placed blocks --
        // 2048 pistons were sent through the precise search, and in a thin layer the concrete stance usually does not
        // exist, so they lost the cheap adjacent goal and idled instead. The lesson recorded then: the list is "which
        // blocks the DEFAULT goal gets wrong", not "which blocks have a facing".
        //
        // A SECOND trigger was tried on 02.08. and MEASURED WORSE -- run 20260802-231337, 1652 placed against 1957 at
        // a tick-matched cut, rates comparable. The idea was to escalate by EVIDENCE: give the precise search to any
        // cell that had already refused a stance, on the reasoning that the failure memory below makes the search
        // affordable by stopping it from re-deriving the same negative. The reasoning was wrong, and the census says
        // exactly how:
        //
        //     searching          57.1% -> 74.7%
        //     walking-to-stance  20.0% ->  6.5%
        //     target-no-stance   19.5% -> 15.5%
        //     STANCE searches        0 -> 43126   (on 39580 ticks: more than one per tick)
        //
        // The symptom it aimed at did improve -- target-no-stance fell by four points -- and it did not matter,
        // because the search ate the tick budget that the walking needed. The bot stopped travelling and started
        // computing. That is the same trade the 25.07 attempt lost by ~400 blocks, and it loses again at a different
        // trigger, which is worth recording: the cost of this search is not per CELL, it is per TICK, and no
        // cleverness about which cells deserve it changes that.
        // THE WEICHE IS GONE, 05.08.2026, at the owner's instruction: "Es gibt nichts anderes außer diese
        // Standflächensuche. Sie wird jedes Mal gemacht." Every cell gets a computed stance, not just the five types
        // above, and there is no second way to choose a placement target any more.
        //
        // Both measurements above were taken against a BROKEN INSTRUMENT and neither survives it. simulatePlacement
        // wrote the rotation under test with setYRot/setXRot, but vanilla reads the look only through getViewYRot,
        // where the FlowCam mixin injected at HEAD and returned wherever the camera happened to point -- so every
        // stance this search ever "proved" was judged against an unrelated angle. Proof needing no vanilla assumption:
        // of 219 distinct (yaw,pitch) pairs in one traced cell, 195 produced more than one outcome, which is
        // impossible for a pure function of look and hit point. Fixed the same day (FlowCam.suspendView), after which
        // facings went 59/96 -> 96/96 and oriented 6/7 -> 7/7. A search that was returning noise idling the bot says
        // nothing about a search that works, so the 400 blocks and the 1652-against-1957 both have to be re-measured.
        //
        // The SECOND reason is independent of orientation and is why this is not merely a tidier structure. A stance is
        // only returned if placing from it actually works, and placementStancesFor excludes the whole target column for
        // the body -- so the head-in-the-target-cell position can never come back. GoalAdjacent accepts exactly that
        // position (distance 1), which is the documented fixpoint at the GoalAdjacent fallback below: run
        // 20260802-232954 froze 11070 ticks on 70,-59,122 with the eye provably inside the target. The owner watched
        // the same failure live on the facings top row on 05.08. Stone got there because stone was not on the list.
        //
        // What this costs is a TICK budget, not a per-cell one, and that is the trap both earlier attempts fell into --
        // see the caller, which now spends real time rather than counting searches.
        return standingGoalFor(tx, ty, tz, desired, bcc);
    }

    /**
     * Why not one stance worked, counted in the same terms {@link #placementStancesFor} uses.
     *
     * <p>"All stances were exhausted" is three different bugs wearing one sentence: nowhere to stand, nowhere that
     * can aim, or every workable stance already burned by the retry lock. A fix has to know which — the counts say
     * so directly instead of leaving it to be inferred from a stalled bot.
     */
    private String stanceRejectionSummary(int tx, int ty, int tz, BlockState desired, BuilderCalculationContext bcc) {
        // Walking 145 stances x every face x every hit point is a few thousand raycasts. That is affordable to
        // explain a stall, not to narrate one: a wedged build defers the same cell over and over, and paying this
        // each time would slow the very run being diagnosed. The first few tell the whole story.
        if (desired == null) {
            return "no desired state";
        }
        long cellKey = positionKey(tx, ty, tz);
        int failures = stanceFailuresByCell.addTo(cellKey, 1) + 1;
        boolean firstForThisCell = failures == 1;
        // NO PERIODIC REFRESH. Every fourth failure used to get a fresh full account, and the account costs a few
        // thousand raycasts -- so a cell that fails forty times paid for ten of them. Measured across four runs, the
        // build rate sat at 13.0-18.3 blocks/min against 24.2 before this logging changed, in EVERY run regardless of
        // which logic change was in: that is the signature of a diagnostic eating the tick budget, not of a decision
        // going wrong. One full account per cell keeps the property the owner asked for -- every failing cell explains
        // itself at least once -- without paying for it repeatedly.
        boolean periodicRefresh = false;
        if (!firstForThisCell && !periodicRefresh) {
            // Never silent about the repetition itself: the count, and where the last full account stands. The
            // containsKey check matters -- if the total cap bit on this cell's FIRST failure there is no earlier
            // account, and "unchanged since tick 0" would send a reader looking for a line that was never written.
            String reference = stanceSummaryLastFullTick.containsKey(cellKey)
                    ? "unchanged since tick " + stanceSummaryLastFullTick.get(cellKey)
                    : "no full account has ever been written for this cell";
            return "stance breakdown " + reference
                    + " (failure #" + failures + " for this cell; next full account at #"
                    + ((failures / STANCE_SUMMARY_REPEAT_EVERY + 1) * STANCE_SUMMARY_REPEAT_EVERY) + ")";
        }
        if (stanceSummariesWritten >= MAX_STANCE_SUMMARIES_TOTAL) {
            // Says so out loud, with the number, so nobody reads a missing account as a missing problem.
            return "stance breakdown withheld: the run has already written " + MAX_STANCE_SUMMARIES_TOTAL
                    + " full accounts (failure #" + failures + " for this cell)";
        }
        stanceSummariesWritten++;
        stanceSummaryLastFullTick.put(cellKey, buildTick);
        ItemStack stack = hotbarStackThatPlaces(desired);
        if (stack == null) {
            return "no hotbar item places " + blockName(desired);
        }
        double reach = ctx.playerController().getBlockReachDistance();
        double eyeHeight = ctx.player().getEyeHeight(Pose.CROUCHING);
        int total = 0;
        int alreadyTried = 0;
        int notStandable = 0;
        int cannotAim = 0;
        int[] stages = new int[6];
        List<String> samples = new ArrayList<>();
        String aimDetail = "";
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = lowestStanceOffset(desired); dy <= 1; dy++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) {
                        continue; // can't stand inside the target column
                    }
                    total++;
                    BetterBlockPos c = new BetterBlockPos(tx + dx, ty + dy, tz + dz);
                    // Count the lock's exclusions, but do NOT stop there: once the lock has burned through every
                    // stance the answer "145 already tried" explains nothing at all. What a fix needs to know is
                    // whether those stances were ever any good -- so the standability and aim checks run regardless.
                    if (!placementTargetLock.mayTryStance(positionKey(c))) {
                        alreadyTried++;
                    }
                    if (!isStandable(c.x, c.y, c.z)) {
                        notStandable++;
                        continue;
                    }
                    boolean aimed = false;
                    for (Vec3 eye : placementEyeCandidates(c, eyeHeight, tx, tz)) {
                        if (orientationAchievableFrom(eye, tx, ty, tz, desired, stack, reach, bcc,
                                stages, samples)) {
                            aimed = true;
                            break;
                        }
                    }
                    if (!aimed) {
                        cannotAim++;
                    }
                }
            }
        }
        if (cannotAim > 0) {
            aimDetail = " [aim rejections: " + stages[0] + " nothing solid to click, " + stages[1] + " out of reach, "
                    + stages[2] + " ray missed the face, " + stages[3] + " wrong block would land, " + stages[4] + " block cannot survive there, " + stages[5] + " space obstructed]";
            if (!samples.isEmpty()) {
                aimDetail += " want " + desired + "; e.g. " + String.join(" | ", samples);
            }
        }
        int workable = total - notStandable - cannotAim;
        // NACHBAR-ZENSUS, rein diagnostisch. Aus BEFUND-942-extern.md Abschnitt 3.
        //
        // `0 would work` bei `nothing solid to click` auf Stufe 0 heisst woertlich: die Zelle hat in der Welt keinen
        // einzigen festen Nachbarn, an den geklickt werden koennte. Damit steht genau eine Frage offen, und sie
        // entscheidet zwischen zwei voellig verschiedenen Reparaturen:
        //
        //   Gibt es einen Nachbarn, den die Schematic WILL, der noch offen ist, und der JETZT setzbar waere?
        //
        //   ja  -> Baureihenfolge-Fehler: der Builder waehlt die Zelle, die er nicht klicken kann, statt den
        //          Nachbarn, der sie klickbar machen wuerde. Reparatur ist eine Prioritaet in der Zielauswahl.
        //   nein -> echter Support-Deadlock. Dann hilft nur ein temporaerer Stuetzblock, und das ist ein grosser,
        //          riskanter Eingriff (isStandableOrScaffoldable), den ohne diese Zahl niemand anfassen darf.
        //
        // Kein Verhaltenspfad wird beruehrt: die Methode baut einen String. Die Drosselung ueber
        // stanceSummariesLogged/MAX_STANCE_SUMMARIES oben gilt automatisch mit.
        String census = "";
        if (workable == 0 && stages[0] > 0) {
            StringBuilder around = new StringBuilder();
            int unblockers = 0;
            for (Direction side : SIX_NEIGHBOURS) {
                int nx = tx + side.getStepX(), ny = ty + side.getStepY(), nz = tz + side.getStepZ();
                BlockState there = bcc.bsi.get0(nx, ny, nz);
                BlockState wants = bcc.getSchematic(nx, ny, nz, there);
                boolean stillOpen = incorrectPositions != null
                        && incorrectPositions.contains(new BetterBlockPos(nx, ny, nz));
                boolean placeableNow = wants != null && !wants.isAir() && stillOpen
                        && !placementStancesFor(nx, ny, nz, wants, bcc, 1, false, ignored -> true).isEmpty();
                if (placeableNow) {
                    unblockers++;
                }
                if (around.length() > 0) {
                    around.append(", ");
                }
                around.append(side.getName()).append('=').append(blockName(there))
                        .append('/').append(wants == null ? "-" : blockName(wants))
                        .append(stillOpen ? "/open" : "/done")
                        .append(placeableNow ? "/PLACEABLE" : "");
            }
            census = " [neighbours: " + around + "; unblockers-now=" + unblockers + "]";
        }
        return "of " + total + " candidate stances: " + alreadyTried + " already tried, "
                + notStandable + " not standable, " + cannotAim + " standable but cannot aim "
                + blockName(desired) + " into place, " + workable + " would work"
                + (workable > 0 && alreadyTried >= total
                        ? " -- the retry lock has burned every one of them" : "")
                + aimDetail + census;
    }

    /** Concrete, individually identifiable stance cells. Recovery must plan exactly one stance at a time so a failed
     *  path or click can reject that stance without losing target ownership or guessing which composite member won. */
    private List<BetterBlockPos> placementStancesFor(int tx, int ty, int tz, BlockState desired,
                                                     BuilderCalculationContext bcc, int limit,
                                                     boolean nearestToPlayer,
                                                     java.util.function.LongPredicate allowed) {
        if (desired == null) {
            return java.util.Collections.emptyList();
        }
        ItemStack stack = hotbarStackThatPlaces(desired);
        if (stack == null) {
            return java.util.Collections.emptyList();
        }
        double reach = ctx.playerController().getBlockReachDistance();
        double eyeHeight = ctx.player().getEyeHeight(Pose.CROUCHING);
        List<BetterBlockPos> candidates = new ArrayList<>();
        int lowestDy = lowestStanceOffset(desired);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = lowestDy; dy <= 1; dy++) {
                    // A shared block cell does not imply a shared collision volume. Thin panels can be placed
                    // beside a centred player in this very cell. Keep the candidate: standability, the actual ray,
                    // and vanilla item placement with the hypothetical body still decide whether it is usable.
                    candidates.add(new BetterBlockPos(tx + dx, ty + dy, tz + dz));
                }
            }
        }
        BetterBlockPos playerFeet = ctx.playerFeet();
        // The face that has to be clicked is fixed by the state, and so is the side of the target you have to be
        // standing on to see it: a west-facing sign hangs on the east neighbour's west face, which is only visible
        // from the west. Stances on the far side are not merely worse, they are geometrically impossible -- yet
        // nearest-first ordering put them first whenever the bot happened to approach from that side, and the
        // evaluation cap then spent itself on all of them before reaching a single one that could work. That is the
        // sign the basalt run deferred 68 times.
        Direction support = requiredSupportDirection(desired);
        final int sx = support == null ? 0 : support.getStepX();
        final int sy = support == null ? 0 : support.getStepY();
        final int sz = support == null ? 0 : support.getStepZ();
        // For a block whose facing is read off the player's LOOK direction -- doors, repeaters, comparators,
        // observers, trapdoors -- the stance is not merely a place to stand from which the face is visible. The yaw is
        // a CONSEQUENCE of where you stand, and the yaw is what decides the facing, so only stances lying on the axis
        // the block faces can produce it at all. Perpendicular stances make the bot look sideways and it places the
        // block turned 90 degrees, every time, from every one of them.
        //
        // That mattered because the evaluation cap below is ordered nearest-to-the-PLAYER: with the bot standing
        // somewhere unhelpful, all 24 evaluated stances were the useless nearby ones and the one workable stance was
        // never reached. A lighthouse door failed this way from all 145 candidates while the bot was, at other moments,
        // standing on the exact cell it needed -- 335 rejections all reading "wrong block would land".
        //
        // Which SIGN of the axis is right differs by block (a door faces where you look, a repeater faces away), so
        // this deliberately does not encode that -- it prefers BOTH ends of the facing axis and lets the simulation,
        // which is authoritative, pick. Cheap, general, and it cannot be wrong about a block it has never met.
        final boolean lookDerivedFacing = isOrientationSensitive(desired)
                && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
        final Direction.Axis facingAxis = lookDerivedFacing
                ? desired.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis() : null;
        // A VERTICAL facing is the same problem rotated, and by far the bigger half of it: 1264 of etz-basalt's cells
        // are pistons, observers and droppers pointing up or down. Vanilla reads those off getNearestLookingDirection,
        // so the bot has to be looking steeply DOWN to place a facing=up block -- which means standing level with the
        // cell, or just above it, and RIGHT NEXT to it. Two blocks away the look flattens out, the horizontal
        // component wins, and it places the piston sideways every single time. That is precisely what the run showed:
        // "want facing=up ... would place facing=east", from a stance 4 blocks off.
        //
        // Keyed on the LOOK, not on the facing, because for half of this family they are opposites. A piston faces
        // AWAY from where you look and an observer faces exactly WHERE you look, so ordering by facing put every
        // observer's workable stances -- the ones below it -- on the wrong side of the +1000000 penalty, behind the
        // 24-stance evaluation cap, out of 243 candidates. All 112 vertical observers in etz-basalt were unreachable
        // for that reason alone, and widening the stance window downward could not help them while this stood.
        //
        // The fallback is facing.getOpposite() rather than facing so that everything else with a vertical FACING --
        // barrels, end rods, shulker boxes, hoppers -- keeps exactly the ordering it has today. This hunk therefore
        // changes the behaviour of vertical observers and of nothing else.
        final Direction verticalLook;
        if (desired.hasProperty(BlockStateProperties.FACING)
                && desired.getValue(BlockStateProperties.FACING).getAxis() == Direction.Axis.Y) {
            Direction facing = desired.getValue(BlockStateProperties.FACING);
            verticalLook = desired.getBlock() instanceof ObserverBlock ? facing : facing.getOpposite();
        } else {
            verticalLook = null;
        }
        candidates.sort(Comparator.comparingLong(p -> {
            int ox = nearestToPlayer ? playerFeet.x : tx;
            int oy = nearestToPlayer ? playerFeet.y : ty;
            int oz = nearestToPlayer ? playerFeet.z : tz;
            long dx = p.x - ox, dy = p.y - oy, dz = p.z - oz;
            long distSq = dx * dx + dy * dy + dz * dz;
            // Signed distance along the support axis: at most 0 means we are on the visible side of that face.
            long side = (long) (p.x - tx) * sx + (long) (p.y - ty) * sy + (long) (p.z - tz) * sz;
            long key = side > 0 ? 10000 + distSq : distSq;
            if (facingAxis != null) {
                int along = facingAxis == Direction.Axis.X ? p.x - tx : p.z - tz;
                int across = facingAxis == Direction.Axis.X ? p.z - tz : p.x - tx;
                // Squarely on the axis first, then near it, then everything else.
                if (across != 0 || along == 0) {
                    key += Math.abs(across) == 1 && along != 0 ? 100_000 : 1_000_000;
                }
            }
            if (verticalLook != null) {
                // To look DOWN at the cell, be level with it or above; to look UP, level or below. And be adjacent --
                // the steepness of the look is what decides the axis, and steepness falls off with distance.
                int dyRel = p.y - ty;
                boolean rightSide = verticalLook == Direction.DOWN ? dyRel >= 0 : dyRel <= 0;
                int horizontal = Math.max(Math.abs(p.x - tx), Math.abs(p.z - tz));
                if (!rightSide) {
                    key += 1_000_000;
                } else if (horizontal > 1) {
                    key += 100_000L * horizontal;
                }
            }
            return key;
        }));
        List<BetterBlockPos> result = new ArrayList<>();
        // Evaluating a stance costs a raytrace per face per hit point. A cell that no stance can serve therefore
        // costs the FULL product -- 145 stances x 6 faces x 5 points -- and it is paid again every tick the cell
        // stays committed. That is the visible one-to-two-second freeze before a block: not thinking, just a search
        // with no exit. The candidates are sorted nearest-first, so the ones beyond this cap were never going to be
        // chosen anyway; capping trades a stance the bot would have had to walk furthest to for a responsive tick.
        // TWO passes, and the order matters more than it looks. Pass one considers only stances with a floor already
        // under them; pass two also allows stances the bot would have to lay a floor for.
        //
        // Not one combined pass, because of the evaluation cap below: scaffoldable stances are plentiful (any air cell
        // with air beneath it qualifies) and they sort by distance like everything else, so mixing them in lets them
        // consume the entire budget and push the stance that already works past the cap. That is not hypothetical --
        // combining them turned the `oriented` scenario from 7/7 into a deterministic 6/7, three runs out of three.
        //
        // Real floor first therefore costs nothing and never regresses. The fallback only runs when the answer would
        // otherwise be "no stance at all", which is precisely the sparse-layer case it exists for.
        for (int pass = 0; pass < 2; pass++) {
            final boolean allowScaffolding = pass == 1;
            if (allowScaffolding && (!result.isEmpty() || (!bcc.hasThrowaway && !buildInRows))) {
                break;
            }
            int evaluated = 0;
            for (BetterBlockPos c : candidates) {
                long stanceKey = positionKey(c);
                boolean standable = allowScaffolding
                        ? isStandableOrScaffoldable(c.x, c.y, c.z, bcc)
                        : isStandable(c.x, c.y, c.z);
                if (!allowed.test(stanceKey) || !standable) {
                    if (BuildTrace.isVerbose() && CellWatch.isWatched(tx, ty, tz)) {
                        BuildTrace.rejected(!standable ? "not-standable" : "stance-already-tried",
                                tx + "," + ty + "," + tz, c);
                    }
                    continue;
                }
                if (++evaluated > MAX_STANCES_EVALUATED_PER_CALL) {
                    if (BuildTrace.isVerbose() && CellWatch.isWatched(tx, ty, tz)) {
                        // The one rejection that is not about the world at all -- it says the SEARCH stopped, not that
                        // the stance failed. Worth its own line, because a cell reported as "no stance works" after
                        // hitting this is making a claim it never actually tested.
                        BuildTrace.rejected("budget-exhausted after=" + MAX_STANCES_EVALUATED_PER_CALL
                                        + " of=" + candidates.size(),
                                tx + "," + ty + "," + tz, c);
                    }
                    break;
                }
                for (Vec3 eye : placementEyeCandidates(c, eyeHeight, tx, tz)) {
                    if (orientationAchievableFrom(eye, tx, ty, tz, desired, stack, reach, bcc)) {
                        result.add(c);
                        break;
                    }
                }
                if (result.size() >= limit) {
                    return result;
                }
            }
        }
        return result;
    }

    /** The standing cells from which [desired] is actually placeable at the target, or null when none exist. */
    private Goal standingGoalFor(int tx, int ty, int tz, BlockState desired, BuilderCalculationContext bcc) {
        // Stances already proven not to work are excluded rather than re-derived. The parameter for this has existed
        // since the method was written and every caller passed "allow everything", so a cell that could not be placed
        // from the spot the bot was standing on was offered that same spot again on the next tick, and the next.
        // EVERY workable stance goes to the pathfinder, not the first six. The goal built below is a composite -- "get
        // to any one of these" -- so A* picks the cheapest route among them, and handing it more options costs nothing
        // now that MAX_STANCES_EVALUATED_PER_CALL evaluates the whole window anyway: the rays have already been cast,
        // and stopping at six only throws away answers that were already paid for. A seventh candidate that happens to
        // sit on the side the bot is approaching from can be worth several blocks of walking.
        List<BetterBlockPos> stances = placementStancesFor(tx, ty, tz, desired, bcc,
                Integer.MAX_VALUE, false, stanceFilterFor(tx, ty, tz));
        rememberEndorsedStances(tx, ty, tz, stances);
        if (!stances.isEmpty()) {
            BuildTrace.cell(buildTick, "STANCE", tx, ty, tz,
                    "found=" + stances.size() + " nearest=" + stances.get(0));
        }
        List<Goal> goals = new ArrayList<>(stances.size());
        stances.forEach(c -> goals.add(new GoalBlock(c)));
        return goals.isEmpty() ? null : new GoalComposite(goals.toArray(new Goal[0]));
    }

    private boolean acquirePlacementRecovery(BetterBlockPos target, String reason) {
        if (target == null) {
            return false;
        }
        long key = positionKey(target);
        if (placementTargetLock.isActive()) {
            return placementTargetLock.owns(key);
        }
        // NON-BLAMING recovery, and the lock's own javadoc is the argument: startRecovery() exists "for stale
        // inventory, support, POSE, or route state where no placement attempt actually proved the current feet cell
        // invalid". Its callers pass "path calculation failed", "no actionable progress" and "no cell has become
        // correct for N ticks" -- and not one of them is proof about the CELL. It nevertheless used
        // acquireForRecovery(key, currentFeet), which records the cell the bot is standing in as rejected.
        //
        // That made the self-clearance bootstrap below impossible rather than merely unlikely: the bootstrap plans the
        // cell the bot stands in, and that cell was rejected one statement earlier, every single time. Run e7dd25d0
        // died on it at tick 7220 (IllegalArgumentException: cannot reuse a rejected placement stance). Guarding the
        // bootstrap with mayTryStance stops the crash but turns the fix into a silent no-op, which is worse -- so the
        // repair belongs here, at the false accusation, not at the symptom.
        //
        // A stance that genuinely cannot place is still rejected: rejectPlannedPlacementStance() does that after a
        // centred attempt has actually failed, which is the only moment the evidence exists.
        if (!placementTargetLock.acquire(key, null)) {
            return false;
        }
        placementTargetLock.startRecovery();
        committedPlaceTarget = new BetterBlockPos(target);
        plannedPlacementStance = null;
        logMechanic("Recovering placement at " + target.x + "," + target.y + "," + target.z
                + " from a different stance (" + reason + ")");
        // Closes a real gap: this is where 6 of the 8 stance commitments in the 96/96 run 50f40a49 were made, and it
        // wrote nothing to the trace at all. The two watchdogs that reach here print different reason strings to the
        // rotated game log and were otherwise indistinguishable -- so "the commitment came 22 ticks earlier" was only
        // provable by grepping a gzipped log next to the trace.
        BuildTrace.cell(buildTick, "RECOVER", target.x, target.y, target.z,
                "reason=" + reason + " noProgress=" + noProgressTicks + " veto=" + scaffoldVetoTicks);
        return true;
    }

    private void rejectPlannedPlacementStance() {
        placementTargetLock.rejectPlannedStance();
        plannedPlacementStance = null;
        placementCenteringTicks = 0;
        placementCenterSettleTicks = 0;
    }

    private boolean centeredInPlacementStance(BetterBlockPos stance) {
        double dx = ctx.player().position().x - (stance.x + 0.5D);
        double dz = ctx.player().position().z - (stance.z + 0.5D);
        return dx * dx + dz * dz <= STANCE_CENTER_TOLERANCE_SQ;
    }

    /** GoalBlock is satisfied anywhere inside a cell, while the recovery ray is validated at its center. Sneak-walk
     *  to that exact sub-block pose before declaring the stance unable to derive a click. */
    /**
     * Whether the excavating snake is the thing steering the body right now.
     *
     * <p>Only asked so the two stance helpers below can skip the crouch. The crouch is there to stop a BUILDER
     * walking off the bridge it just placed -- a real hazard, because the block it stands on is one it laid and
     * the next step is over nothing. The digger has no bridge. It stands on the floor of the band, in the tunnel
     * it cut, and everything below that floor is still solid rock, because bands are cleared from the top down.
     *
     * <p>The owner watched the result and named it exactly: it ducks for a tick between swings, for nothing.
     */
    private boolean snakeOwnsTheStance() {
        return snakeHead != null && snakeStance != null;
    }

    /**
     * Keeps normal path execution (and therefore its human-looking steering), but gives it only the next short
     * excavation waypoint. A missing support under that waypoint is the one place this route may bridge. Parkour is
     * disabled in the accompanying calculation context, so a ravine becomes a normal sneak bridge rather than a jump;
     * every other scaffold, including a pillar, has infinite cost and is refused again by the immutable route licence.
     */
    private PathingCommand snakePathingCommand() {
        BetterBlockPos feet = ctx.playerFeet();
        BetterBlockPos goal = snakeStance;
        PathingCommand initialApproach = initialExcavationApproach(new GoalBlock(goal));
        if (initialApproach != null) return initialApproach;
        // AutoDig may descend only through its owned one-block entry cut. If ordinary pathing sees a vertical
        // difference here, the bot has left the band floor (normally by falling into an unrepaired gap). Asking A*
        // for the remote stance would license a stair or pillar recovery and silently violate the excavation route.
        // Stop with an explicit report instead: the safe recovery is to fix the bridge logic, not build upward.
        if (feet.y != goal.y) {
            if (snakeCorridorLevelY != goal.y) {
                // NOT THERE YET IS NOT THE SAME AS HAVING FALLEN OUT. The bot starts wherever the player was
                // standing, and the ordinary way to use this feature is to put the box around yourself and press
                // enter -- which leaves the bot at the BOTTOM of a volume that is worked from the top. Every such
                // start looked exactly like the failure this guard was written for and ended the run on its first
                // tick with "a layer ended with cells that could not be built", before a single block was touched.
                //
                // Getting TO the work is travel, not corridor: hand it to ordinary navigation, the same thing that
                // walks the bot to a dig site in the first place. The corridor rule takes over the moment the bot
                // has actually stood on that level, and from then on leaving it is still an abort.
                snakeBridgeTarget = null;
                return new PathingCommandContext(new GoalBlock(goal.x, goal.y, goal.z),
                        PathingCommandType.SET_GOAL_AND_PATH, new BuilderCalculationContext(Lane.A_NO_PLACING));
            }
            abortBuild(Ending.LAYER_UNBUILDABLE,
                    "AutoDig left its level one-block route at " + feet.x + "," + feet.y + "," + feet.z,
                    java.util.List.of(
                            "Expected corridor stance: " + goal.x + "," + goal.y + "," + goal.z,
                            "No stair, pillar, or vertical detour was attempted."));
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return excavationLevelPathingCommand(feet, goal);
    }

    private PathingCommand excavationLevelPathingCommand(BetterBlockPos feet, BetterBlockPos goal) {
        // A bridge is counted only after the world acknowledges a standable block at the licensed cell. Planning a
        // bridge is not evidence that it happened, and incrementing here previously made the integrity report claim a
        // repair even in the run that fell into the cavity.
        Iterator<BetterBlockPos> pending = snakePendingBridgeRepairs.iterator();
        while (pending.hasNext()) {
            BetterBlockPos repaired = pending.next();
            if (!MovementHelper.canWalkOn(ctx, repaired)) {
                continue;
            }
            pending.remove();
            snakeFloorRepairs++;
            snakeDiagnosis = "t=" + buildTick + " AutoDig bridge confirmed at " + repaired.x + ","
                    + repaired.y + "," + repaired.z;
            logDirect("AutoDig bridged corridor floor at " + repaired.x + "," + repaired.y + "," + repaired.z);
        }
        BetterBlockPos waypoint = nextSnakeWaypoint(feet, goal);
        BetterBlockPos floor = waypoint.below();
        boolean missingFloor = !MovementHelper.canWalkOn(ctx, floor);
        snakeBridgeTarget = missingFloor ? floor : null;
        if (missingFloor) {
            snakePendingBridgeRepairs.add(floor);
        }
        // ONE STEP, ONE CLOCK. The waypoint changes the moment the bot enters it, so a counter that survives the
        // change is measuring a step that is still being taken; a counter that resets on it is measuring a step
        // that is not happening. Nothing else in the snake could tell those apart -- nudgeOffAStallSpot shuffled
        // the body between two cells for 1900 ticks in run a0a00867 and each shuffle looked like progress.
        if (waypoint.equals(snakeStepWaypoint)) {
            snakeStepStuckTicks++;
        } else {
            snakeStepWaypoint = waypoint;
            snakeStepStuckTicks = 0;
        }
        BuilderCalculationContext routeContext = new BuilderCalculationContext(Lane.EXCAVATION_PATH, waypoint);
        // THE RECOVERY THAT WAS WRITTEN AND NEVER CALLED. A missing corridor floor is the snake's own repair, not
        // a favour it asks of the pathfinder: the route licenses exactly this cell, the block is a throwaway, and
        // the click is the same reach-local placement every source and shell gap already goes through. Handing it
        // to MovementTraverse's back-place first is still right -- it is the human-looking version and it works --
        // but "first" has to have an "otherwise", and until now it did not.
        if (missingFloor && snakeStepStuckTicks >= SNAKE_STEP_RESCUE_TICKS
                && !princeps.getPathingBehavior().isPathing()) {
            return snakeBridgePlacementCommand(floor, routeContext);
        }
        // Keep the normal pathfinder in charge of the one-cardinal move. MovementTraverse is the human bridge
        // primitive: it approaches the edge, holds sneak, moves backward over it, backplaces the one licensed floor
        // block, and only then finishes the step. The route context forbids parkour, every other placement, every
        // navigation break, stair and pillar; therefore there is no alternative route for A* to invent here.
        return new PathingCommandContext(new GoalBlock(waypoint.x, waypoint.y, waypoint.z),
                PathingCommandType.SET_GOAL_AND_PATH, routeContext);
    }

    private PathingCommand snakeBridgePlacementCommand(BetterBlockPos floor, BuilderCalculationContext bcc) {
        BlockState fill = snakeIntegrityBlockState(floor);
        if (fill == null) {
            abortBuild(Ending.MATERIALS_MISSING,
                    "AutoDig needs a full throwaway block to bridge its route",
                    java.util.List.of("First missing floor cell: " + floor.x + "," + floor.y + "," + floor.z));
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Optional<Placement> option = possibleToPlace(fill, floor.x, floor.y, floor.z, bcc);
        if (option.isEmpty()) {
            Item wanted = fill.getBlock().asItem();
            boolean alreadyOnHotbar = false;
            for (int slot = 0; slot < 9; slot++) {
                if (ctx.player().getInventory().getNonEquipmentItems().get(slot).getItem() == wanted) {
                    alreadyOnHotbar = true;
                    break;
                }
            }
            if (!alreadyOnHotbar) {
                princeps.getInventoryBehavior().throwaway(true,
                        stack -> !stack.isEmpty() && stack.getItem() == wanted);
            }
            snakeDiagnosis = "t=" + buildTick + " AutoDig waits to bridge " + floor.x + "," + floor.y + ","
                    + floor.z + " from the current corridor edge";
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return snakeIntegrityPlacementClick(option.get(),
                new SnakeRepair(floor, ExcavationRepairPolicy.Kind.BRIDGE, 0), bcc);
    }

    static BetterBlockPos nextSnakeWaypoint(BetterBlockPos feet, BetterBlockPos goal) {
        int dx = goal.x - feet.x;
        int dz = goal.z - feet.z;
        if (feet.y != goal.y || Math.abs(dx) + Math.abs(dz) <= 1) {
            return goal;
        }
        // Turns can present a diagonal final stance. Hand A* one cardinal cell at a time; it still owns the
        // movement and steering, while a remote goal can no longer justify a tour around the excavation.
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return new BetterBlockPos(feet.x + Integer.signum(dx), feet.y, feet.z);
        }
        return new BetterBlockPos(feet.x, feet.y, feet.z + Integer.signum(dz));
    }

    static boolean excavationPlacementAllowed(long bridgeKey, int x, int y, int z) {
        return bridgeKey != Long.MIN_VALUE && BlockPos.asLong(x, y, z) == bridgeKey;
    }

    private PathingCommand centerInPlacementStance(BetterBlockPos stance) {
        Vec3 start = ctx.player().position();
        Vec3 center = new Vec3(stance.x + 0.5D, start.y, stance.z + 0.5D);
        float currentYaw = ctx.playerRotations().getYaw();
        float idealYaw = RotationUtils.calcRotationFromVec3d(start, center, ctx.playerRotations()).getYaw();
        // NO CROUCH WHERE THERE IS NOTHING TO FALL OFF -- see snakeOwnsTheStance.
        if (!snakeOwnsTheStance()) {
            princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }
        MovementOption.getOptions(
                Mth.sin(currentYaw * DEG_TO_RAD_F),
                Mth.cos(currentYaw * DEG_TO_RAD_F),
                false
        ).min(Comparator.comparingDouble(option -> option.distanceToSq(
                Mth.sin(idealYaw * DEG_TO_RAD_F),
                Mth.cos(idealYaw * DEG_TO_RAD_F)
        ))).ifPresent(option -> {
            princeps.getInputOverrideHandler().setInputForceState(option.input1(), true);
            if (option.input2() != null) {
                princeps.getInputOverrideHandler().setInputForceState(option.input2(), true);
            }
        });
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand settleInPlacementStance() {
        // Same reason as centerInPlacementStance, and here it never did anything for the digger anyway: this is
        // the branch for a body that is not on the ground yet, and crouching in mid-air changes nothing at all.
        if (!snakeOwnsTheStance()) {
            princeps.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /** Drives the target-bound recovery phase. Exactly one concrete stance is routed at a time; arrival without a
     *  derivable click, a failed calculation, or lack of distance progress rejects only that stance. */
    private PathingCommand placementRecoveryCommand(BuilderCalculationContext bcc, boolean calcFailed) {
        if (!placementTargetLock.isRecovering() || committedPlaceTarget == null) {
            return null;
        }
        BetterBlockPos target = committedPlaceTarget;
        long targetKey = positionKey(target);
        BlockState current = bcc.bsi.get0(target.x, target.y, target.z);
        BlockState desired = bcc.getSchematic(target.x, target.y, target.z, current);
        if (!placementTargetLock.owns(targetKey) || desired == null || isSecondaryHalf(desired)
                || placementSatisfiedOrHandedOff(current, desired, target.x, target.y, target.z, bcc)) {
            releasePlacementTarget();
            return null;
        }
        if (isCellParked(target.x, target.y, target.z)) {
            releasePlacementTarget();
            return null;
        }
        // A wrong landed block must first be broken. Preserve lock ownership and let assemble/toBreak route to it.
        if (!MovementHelper.isReplaceable(target.x, target.y, target.z, current, bcc.bsi)) {
            return null;
        }

        // A missing stack prevents a placement probe; it does not reject a stance. Keep the target and its
        // recovery state while normal inventory handling supplies the item, without advancing route failures.
        if (hotbarStackThatPlaces(desired) == null) {
            return null;
        }

        Goal platformGoal = platformTraverseGoal(target, bcc, true);
        if (platformGoal != null) {
            return finishBuilderRoute(platformGoal, platformGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH, bcc);
        }

        BetterBlockPos feet = ctx.playerFeet();
        if (!ctx.player().onGround() || !princeps.getPathingBehavior().isSafeToCancel()) {
            placementCenterSettleTicks = 0;
            Goal hold = plannedPlacementStance == null
                    ? princeps.getPathingBehavior().getGoal() : new GoalBlock(plannedPlacementStance);
            return continueCurrentRoute(hold, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        boolean horizontallyAdjacent = feet.y == target.y
                && Math.abs(feet.x - target.x) + Math.abs(feet.z - target.z) == 1;
        if (!placementTargetLock.hasPlannedStance()
                // The lock refuses a stance it has already rejected for this target, by throwing -- and this bootstrap
                // plans the cell the bot is STANDING IN, which is exactly the cell most likely to have been tried and
                // rejected a moment ago. Without this guard the bootstrap violates the lock's own invariant and takes
                // the client down: run e7dd25d0 died at tick 7220 with
                //   IllegalArgumentException: cannot reuse a rejected placement stance
                //     at PlacementTargetLock.planStance(:232) <- placementRecoveryCommand(:5657)
                // one tick after "Recovering placement at 78,-59,122 from a different stance". 555 green tests did not
                // catch it because the throw needs a runtime lock state, not a policy input.
                //
                // Skipping the bootstrap here is the right fallback rather than clearing the rejection: the rejection
                // is evidence that this stance did not work, and the ordinary recovery below will look for another one.
                && placementTargetLock.mayTryStance(positionKey(feet))
                && recoveryNeedsSelfClearanceBootstrap(
                        horizontallyAdjacent,
                        centeredInPlacementStance(feet),
                        isStandable(feet.x, feet.y, feet.z),
                        placementPlausible(target, desired),
                        placementPlausible(target, desired, true))) {
            // GoalAdjacent is cell-exact, not pose-exact. In run 3176d387 it stopped at z=99.73253 in the correct
            // neighbour cell, but the 0.6-wide body still protruded 0.03253 into piston target 88,-59,100. Recovery's
            // hypothetical placement simulations then all inherited that REAL entity collision and falsely reported
            // that no stance existed. Centre the already-safe adjacent cell first; this uses no extra helper block and
            // makes both the live click and every later hypothetical stance evaluation honest.
            plannedPlacementStance = new BetterBlockPos(feet);
            placementCenteringTicks = 0;
            placementCenterSettleTicks = 0;
            placementTargetLock.planStance(positionKey(plannedPlacementStance), 0L);
            return centerInPlacementStance(plannedPlacementStance);
        }
        PathingCommand plannedRoute = placementRecoveryForPlannedStance(bcc, feet, calcFailed);
        if (plannedRoute != null) return plannedRoute;

        List<BetterBlockPos> alternatives = placementStancesFor(target.x, target.y, target.z, desired, bcc,
                1, true, placementTargetLock::mayTryStance);
        if (alternatives.isEmpty()) {
            // ...unless the reason is the bot's own body. Then "no stance works" is not a fact about the cell, and
            // charging it a deferral is charging it for where the bot happened to stand. See the long note in
            // searchForPlacables: on a one-block-thick structure the bot walks THROUGH the cells it must build, so this
            // fires routinely, and six such deferrals retire a cell that was never unbuildable at all.
            // A40, REVERTED: "if the bot is standing in the target, return without deferring -- stepping off is all it
            // needs." It is not all it needs, and the reason is written down four hundred lines below in the note at
            // the allowSameLevel fallback: with the cell NOT deferred it stays the nearest work, assemble keeps it in
            // the goal, GoalAdjacent counts a cell the bot is standing on as reached, so nothing paths, the body never
            // leaves, and the placement stays impossible. That is a fixpoint, and it is the one that froze run
            // 20260802-232954 for 11070 ticks on 70,-59,122 with the eye provably inside the target.
            //
            // The deferral was doing load-bearing work here: taking the cell out of the running is what lets the goal
            // move the bot somewhere else, which is what gets it off the cell. Measured, facings: 96/96 -> 57/96.
            //
            // The right fix is the one the owner named -- STEP OFF the cell and then evaluate -- which needs an actual
            // move, not the absence of a penalty. Until that exists, the penalty is the cheaper of two evils.
            // Six neighbour lookups decide how patient to be, and they cost nothing next to the stance search that
            // just failed. An earlier version asked this by running that whole search a SECOND time without the
            // already-tried filter, which answered a different and less useful question at many times the price.
            // Counted here because walksStarted CANNOT see this. That counter sits below this early return, so it is
            // only ever incremented once a stance has been chosen -- which means walk efficiency reports zero cost for
            // exactly the failure mode that dominates a large schematic: a cell with nothing to click, walked to and
            // then abandoned. Judging this builder by walks=/paidOff= alone was reading a metric blind to its own
            // biggest expense.
            stancelessAbandons++;
            // THE VERDICT NOTE. The full search has just come back empty, so this is the moment the answer is known:
            // note it against the face set that produced it, and the cell steps out of the running until a neighbour
            // changes -- not until a timer runs down. Two kinds, because they need different follow-ups: with no face
            // at all nothing can help but a new neighbour, while "faces exist and none of them works" is what the
            // helper-block phase later has to solve.
            recordCellVerdict(target.x, target.y, target.z,
                    clickableFaceMask(target.x, target.y, target.z) == 0L
                            ? VERDICT_NO_FACE : VERDICT_NO_STANCE);
            String why = "all currently valid placement stances were exhausted -- "
                    + stanceRejectionSummary(target.x, target.y, target.z, desired, bcc);
            if (supportCouldStillArriveThisLayer(target, bcc)) {
                deferCell(target, why, bcc);
            } else {
                deferCellStructural(target, "nothing in this layer can ever support it -- " + why, bcc);
            }
            return null;
        }
        plannedPlacementStance = alternatives.get(0);
        placementCenteringTicks = 0;
        placementCenterSettleTicks = 0;
        long dx = feet.x - plannedPlacementStance.x;
        long dy = feet.y - plannedPlacementStance.y;
        long dz = feet.z - plannedPlacementStance.z;
        placementTargetLock.planStance(positionKey(plannedPlacementStance),
                dx * dx + dy * dy + dz * dz);
        // A walk begins here: a concrete stance has been chosen and the bot is about to travel to it.
        walksStarted++;
        walkTargetKey = targetKey;
        Goal stanceGoal = new GoalBlock(plannedPlacementStance);
        return finishBuilderRoute(stanceGoal, stanceGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH, bcc);
    }

    /** Continues the chosen recovery stance after the caller's material, ownership and safe-body checks. */
    private PathingCommand placementRecoveryForPlannedStance(BuilderCalculationContext bcc,
                                                            BetterBlockPos feet, boolean calcFailed) {
        BetterBlockPos target = committedPlaceTarget;
        if (placementTargetLock.hasPlannedStance()) {
            if (plannedPlacementStance == null
                    || placementTargetLock.plannedStanceKey() != positionKey(plannedPlacementStance)) {
                rejectPlannedPlacementStance();
            } else if (calcFailed && !ctx.playerFeet().equals(plannedPlacementStance)) {
                logMechanic("No path to placement stance " + plannedPlacementStance.x + ","
                        + plannedPlacementStance.y + "," + plannedPlacementStance.z + "; trying another");
                reportIfWalledIn(feet);
                rejectPlannedPlacementStance();
            } else {
                long dx = feet.x - plannedPlacementStance.x;
                long dy = feet.y - plannedPlacementStance.y;
                long dz = feet.z - plannedPlacementStance.z;
                long distanceSquared = dx * dx + dy * dy + dz * dz;
                boolean rejectedDuringCentering = false;
                if (distanceSquared == 0 && !centeredInPlacementStance(plannedPlacementStance)) {
                    placementCenterSettleTicks = 0;
                    if (princeps.getPathingBehavior().getCurrent() != null) {
                        // First cancel the now-satisfied cell-level route. Segment cancellation can clear forced
                        // movement keys, so the ORIGINAL intent here was not to charge a centering attempt until the
                        // next tick owns movement.
                        //
                        // But the cancel is not guaranteed to take: if a path is present again on the very next tick,
                        // this returns the same command forever, charging nothing. That is not a hypothetical -- it
                        // froze a lighthouse run for 1740 ticks at 70,-59,69 with zero log lines, because no counter
                        // here advanced and the stall reporting lives further down onTick, past this return.
                        //
                        // A cancel that has to be repeated is itself a failure to progress, so it is charged like one.
                        // The counter is still generous enough for the handful of ticks a real cancel needs.
                        if (++placementCenteringTicks < STANCE_CENTERING_TICKS) {
                            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                        }
                        logMechanic("Could not clear the route to center in placement stance "
                                + plannedPlacementStance.x + "," + plannedPlacementStance.y + ","
                                + plannedPlacementStance.z + " after " + placementCenteringTicks
                                + " cancel attempts; trying another stance");
                        rejectPlannedPlacementStance();
                        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                    if (++placementCenteringTicks < STANCE_CENTERING_TICKS) {
                        return centerInPlacementStance(plannedPlacementStance);
                    }
                    logMechanic("Could not center within placement stance " + plannedPlacementStance.x + ","
                            + plannedPlacementStance.y + "," + plannedPlacementStance.z + "; trying another");
                    rejectPlannedPlacementStance();
                    rejectedDuringCentering = true;
                } else {
                    placementCenteringTicks = 0;
                    if (distanceSquared != 0) {
                        placementCenterSettleTicks = 0;
                    }
                    if (distanceSquared == 0 && placementCenterSettleTicks++ == 0) {
                        // One input-free tick removes residual momentum. stickyPlacement gets one settled, centered
                        // derivation attempt next tick before ARRIVED is allowed to reject this stance.
                        return settleInPlacementStance();
                    }
                }
                if (!rejectedDuringCentering) {
                    PlacementTargetLock.RouteDecision decision =
                            placementTargetLock.routeTick(distanceSquared, distanceSquared == 0, progressWatch.advancedLastSample());
                    if (decision == PlacementTargetLock.RouteDecision.KEEP_MOVING) {
                        Goal stanceGoal = new GoalBlock(plannedPlacementStance);
                        return finishBuilderRoute(stanceGoal, stanceGoal,
                                PathingCommandType.REVALIDATE_GOAL_AND_PATH, bcc);
                    }
                    if (decision == PlacementTargetLock.RouteDecision.ARRIVED) {
                        // stickyPlacement already tried possibleToPlace earlier this tick. Arrival with no attempt
                        // after centering means this real pose cannot produce a click.
                        // This rejection used to be SILENT, which is why "stances were exhausted" could be observed
                        // live without ever revealing its cause. Name the reason: the bot reached exactly the stance
                        // the oracle asked for, centred within tolerance, and STILL could not derive a click — so the
                        // stance oracle and the click deriver disagree, and this line says about what.
                        logMechanic("Stance " + plannedPlacementStance.x + "," + plannedPlacementStance.y + ","
                                + plannedPlacementStance.z + " reached and centred but no placement derivable for "
                                + target.x + "," + target.y + "," + target.z
                                + " (" + diagnoseCell(target, bcc) + "); rejecting stance");
                        rejectPlannedPlacementStance();
                    } else {
                        plannedPlacementStance = null; // lock rejected/cleared it on RETRY_STANCE
                        placementCenteringTicks = 0;
                        placementCenterSettleTicks = 0;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Whether recovery must first clear the bot's tiny current overlap before asking vanilla to simulate other poses.
     * A persistent world obstruction is never ignored; only the difference between the self-sensitive and
     * ignore-current-player checks can trigger this bootstrap.
     */
    static boolean recoveryNeedsSelfClearanceBootstrap(boolean horizontallyAdjacent,
                                                        boolean centered,
                                                        boolean standable,
                                                        boolean plausibleWithCurrentPlayer,
                                                        boolean plausibleIgnoringCurrentPlayer) {
        return horizontallyAdjacent && !centered && standable
                && !plausibleWithCurrentPlayer && plausibleIgnoringCurrentPlayer;
    }

    /**
     * Was ueber eine Zelle bekannt ist, wenn man sie ansieht. VOLLSTAENDIG: drei Faelle, kein vierter, kein null.
     *
     * <p><b>Warum es diesen Typ gibt.</b> Ein Wachhund ist immer ein Gestaendnis: es gibt einen Pfad, auf dem das
     * System zu keiner Entscheidung kommt. Die Spezifikation kennt nur zwei Zustaende -- eine Zelle ist jetzt
     * setzbar, oder sie ist mit Grund A/B/C geparkt und der Waechter weckt sie. "Seit 120 Ticks kein Fortschritt"
     * kann darin gar nicht vorkommen. Dass der Bauer trotzdem sieben solche Uhren traegt, beweist einen dritten,
     * unbenannten Zustand: unentschieden.
     *
     * <p>Und man sieht genau, wo er entsteht. {@code placementGoal} gab {@code null} aus fuenf verschiedenen
     * Gruenden zurueck -- kein Halt, schon beurteilt, keine Klickflaeche, keine Standposition, und: die
     * Standplatzsuche kam in diesem Tick nicht mehr dran. Vier Aussagen und ein Nicht-Ereignis, ununterscheidbar
     * fuer den Aufrufer. Wer sie nicht unterscheiden kann, kann nichts entscheiden, und dann braucht er eine Uhr.
     */
    sealed interface CellUrteil {
        /** Sie ist jetzt setzbar, und hier ist das Ziel, zu dem gelaufen wird. */
        record Setzen(Goal ziel) implements CellUrteil { }

        /** Sie ist es nicht, und der Grund ist eine Aussage ueber die WELT. Der Waechter weckt sie wieder. */
        record Parken(ParkReason grund, String beleg) implements CellUrteil { }

        /**
         * Es wurde nichts festgestellt -- kein Urteil, kein Park, nur: diesmal nicht angesehen. Ein Budget lief
         * ab, ein Chunk fehlt. Das ist ausdruecklich KEIN Grund zu parken; eine Zelle auf Beweise festzunageln,
         * die nie erhoben wurden, waere schlimmer als sie noch einmal anzusehen.
         */
        record Unbekannt(String warum) implements CellUrteil { }
    }

    /**
     * Heutiges Verhalten, unveraendert: ein Ziel oder {@code null}. Implementiert ueber {@link #urteileUeber},
     * damit es die Entscheidung nicht ZWEIMAL gibt -- zwei Mechanismen mit zwei Antworten ist genau der Fehler,
     * den dieses Repo schon dreimal bezahlt hat (siehe die Notiz an {@code aimPointsOnFace}).
     */
    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        Goal rowGoal = mapArtPlacementGoal(pos, bcc);
        return rowGoal != null ? rowGoal
                : urteileUeber(pos, bcc) instanceof CellUrteil.Setzen setzen ? setzen.ziel() : null;
    }

    private Goal mapArtPlacementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (buildInRows) {
            BlockState current = bcc.bsi.get0(pos);
            BlockState wanted = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
            if (wanted != null && !wanted.isAir()
                    && MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), current, bcc.bsi)
                    && containsBlockState(bcc.placeable, wanted)
                    && !placementStateIsGeometrySensitive(wanted)
                    && MovementHelper.isBlockNormalCube(wanted)) {
                // A Map-Art pixel is the walking surface itself. Routing onto the cell above it makes traverse place
                // that exact pixel while advancing, including the two outer cells of a five-wide slice that have no
                // separate stance beyond the picture edge. The global frontier licence still prevents any later slice
                // or neighbouring band from being used as an early bridge.
                // Walk onto the Map-Art pixel itself: traverse places it as the next piece of the licensed slice.
                return new GoalBlock(pos.above());
            }
        }
        return null;
    }

    /**
     * Keep a current, permanent full-cube platform by letting the normal one-step Traverse fill the adjacent
     * template cube beneath its destination. Unlike a standing-cell oracle this action deliberately crosses
     * the edge before the block exists. Retain the original support until the target changes; re-deriving it
     * from playerFeet while sneaking would replace it with the still-empty target and abandon a valid action.
     */
    Goal platformTraverseGoal(BlockPos pos, BuilderCalculationContext bcc, boolean remember) {
        if (electedCell != null && !electedCell.equals(pos)) return null;
        if (buildInRows || excavating || paused || schematic == null || origin == null
                || incorrectPositions == null || !incorrectPositions.contains(new BetterBlockPos(pos))) {
            if (remember) platformTraverseApproach = null;
            return null;
        }
        BlockState current = bcc.bsi.get0(pos);
        BlockState wanted = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
        ISchematic full = supportModelForDependencies();
        PlatformTraverseApproach previous = platformTraverseApproach;
        // A new action starts its own one-step route, never a NEXT segment of an older ordinary path whose
        // immutable goal/licences would survive splicing. Continue only this exact already-marked action.
        PathExecutor executing = princeps.getPathingBehavior().getCurrent();
        if (executing != null && (previous == null || executing.getPath().getGoal() != previous.goal)) {
            if (remember) platformTraverseApproach = null;
            return null;
        }
        if (previous != null && (!previous.target.equals(pos) || previous.world != ctx.world()
                || previous.player != ctx.player() || previous.model != full || !previous.origin.equals(origin)
                || !previous.wanted.equals(wanted))) {
            if (remember) platformTraverseApproach = null;
            return null;
        }
        BetterBlockPos from = previous == null ? ctx.playerFeet() : previous.from;
        BetterBlockPos target = new BetterBlockPos(pos);
        BlockPos supportPos = from.below();
        if (wanted == null || wanted.isAir() || !current.isAir() || !wanted.getFluidState().isEmpty()
                || !full.inSchematic(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ(), current)
                || !wanted.equals(full.desiredState(pos.getX() - origin.getX(), pos.getY() - origin.getY(),
                        pos.getZ() - origin.getZ(), current, bcc.placeable))
                || wanted.getBlock() instanceof FallingBlock || placementStateIsGeometrySensitive(wanted)
                || !wanted.isCollisionShapeFullBlock(ctx.world(), pos)
                || from.y != pos.getY() + 1
                || Math.abs(from.x - pos.getX()) + Math.abs(from.z - pos.getZ()) != 1
                || !bcc.bsi.worldContainsLoadedChunk(from.x, from.z)
                || !bcc.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())
                || !ctx.player().onGround() || Math.abs(ctx.player().position().y - from.y) > 0.01
                || !(ctx.playerFeet().equals(from) || ctx.playerFeet().equals(target.above()))
                || hotbarStackThatPlaces(wanted) == null
                || !wanted.canSurvive(ctx.world(), pos) || isReservedStanceSpace(pos.getX(), pos.getY(), pos.getZ(), bcc)) {
            if (remember && previous != null) platformTraverseApproach = null;
            return null;
        }
        BlockState support = bcc.bsi.get0(supportPos);
        int lx = supportPos.getX() - origin.getX(), ly = supportPos.getY() - origin.getY(), lz = supportPos.getZ() - origin.getZ();
        if (!full.inSchematic(lx, ly, lz, support)
                || !support.equals(full.desiredState(lx, ly, lz, support, bcc.placeable))
                || support.isAir() || !support.getFluidState().isEmpty() || support.getBlock() instanceof FallingBlock
                || !support.isCollisionShapeFullBlock(ctx.world(), supportPos)
                || (previous != null && !support.equals(previous.support))
                || !bcc.bsi.get0(from).isAir() || !bcc.bsi.get0(from.above()).isAir()
                || !bcc.bsi.get0(target.above()).isAir() || !bcc.bsi.get0(target.above(2)).isAir()
                || !ctx.world().getBlockState(supportPos).equals(support)
                || !ctx.world().getBlockState(pos).isAir()) {
            if (remember && previous != null) platformTraverseApproach = null;
            return null;
        }
        double cost = MovementTraverse.cost(bcc, from.x, from.y, from.z, target.x, target.z);
        if (!Double.isFinite(cost) || cost >= COST_INF) {
            if (remember && previous != null) platformTraverseApproach = null;
            return null;
        }
        if (previous != null) return previous.goal;
        Goal goal = new PlatformTraverseGoal(target.above());
        if (remember) platformTraverseApproach = new PlatformTraverseApproach(ctx.world(), ctx.player(), full,
                new Vec3i(origin.getX(), origin.getY(), origin.getZ()), from, target, support, wanted, goal);
        return goal;
    }

    /**
     * Dieselbe Untersuchung wie {@link #placementGoal}, aber sie sagt, WAS sie festgestellt hat.
     *
     * <p>Seiteneffekte und Reihenfolge sind woertlich die alten: dieselben Verdikt-Notizen, derselbe
     * {@code orientedGoalCache}, dieselbe Budgetbuchung. Geaendert sind ausschliesslich die Rueckgabewerte.
     * Solange nur {@code placementGoal} sie liest, ist der Umbau verhaltensneutral -- was der Schattenbetrieb
     * daneben protokolliert, ist die Differenz zwischen dem, was das System TUT, und dem, was es WEISS.
     */
    CellUrteil urteileUeber(BlockPos pos, BuilderCalculationContext bcc) {
        return urteileUeber(pos, bcc, true);
    }

    /**
     * @param darfArbeiten {@code false} macht die Untersuchung rein lesend: keine Standplatzsuche, keine
     *        Verdikt-Notiz, kein verbrauchtes Budget. Der Schattenbetrieb MUSS so fragen -- eine Beobachtung, die
     *        das Suchbudget des Ticks aufbraucht, veraendert genau die Groesse, die sie messen soll, und diese
     *        Falle hat dieses Projekt schon zweimal bezahlt. Gelesen wird dann nur, was ohnehin schon dasteht;
     *        was noch niemand untersucht hat, heisst folgerichtig {@link CellUrteil.Unbekannt}.
     */
    CellUrteil urteileUeber(BlockPos pos, BuilderCalculationContext bcc, boolean darfArbeiten) {
        BlockState current = bcc.bsi.get0(pos);
        if (!(current.getBlock() instanceof LiquidBlock)
                && !MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), current, bcc.bsi)) {
            return new CellUrteil.Setzen(new GoalPlace(pos));
        }
        BlockState wanted = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
        if (wanted != null && !wanted.canSurvive(ctx.world(), pos)) {
            // A lever, repeater, wire, torch, etc. whose support has not been built is not a navigation target yet.
            // Keeping it in a GoalComposite can make the whole composite already satisfied from an adjacent pose,
            // preventing the one independent support cell from receiving a path at all.
            //
            // Grund A: die Welt bietet nicht, woran der Block haengen koennte. Ein Nachbar aendert das, und genau
            // darauf horcht der Waechter -- deshalb ist das ein Park und keine Wiedervorlage.
            if (darfArbeiten) {
                recordCellVerdict(pos.getX(), pos.getY(), pos.getZ(), VERDICT_NO_FACE);
            }
            return new CellUrteil.Parken(ParkReason.NO_FACE,
                    "kein Halt fuer " + blockName(wanted) + " (canSurvive=false)");
        }
        Goal platformGoal = platformTraverseGoal(pos, bcc, darfArbeiten);
        if (platformGoal != null) return new CellUrteil.Setzen(platformGoal);
        // For a block whose facing is decided by WHERE the player stands (doors, repeaters, comparators, observers...)
        // route the bot to a standing cell from which the EXACT desired orientation is actually placeable, instead of
        // "any adjacent" — which can strand it on a side from which the wanted facing is impossible (the door the
        // user had to nudge by hand). Only picks the goal; the real possibleToPlace + live-ray gate still guard the
        // actual click, so a wrong guess here only ever costs a wasted walk, never a wrong block.
        // ==========================================================================================================
        // TWO SEPARATE QUESTIONS, and confusing them was my mistake on 05.08.2026. The owner corrected it in these
        // words: "Es ist nicht zu Zelle laufen theoretisch, sondern es ist zu Position laufen, welche bereits bewiesen
        // funktioniert. Und es hat nichts mit Nachbarflaeche in diesem Moment zu tun -- die Nachbarflaeche ist im
        // ersten Teil, wo der naechste Kandidat gefunden wird."
        //
        // PART 1: is this cell a candidate for the next placement? The neighbour face is the cheap first question here,
        // and ONLY here. It is not a routing rule, which is what I had wrongly read it as -- and it is therefore also
        // not the four-times-reverted "only route to cells that already have something to click against", because
        // nothing is deprioritised and nothing is held for a duration.
        //
        // PART 2: where may the bot walk? To a PROVEN POSITION, and to nothing else. Never to a cell, never to
        // "somewhere adjacent". standingGoalFor returns GoalBlock on the exact stance cells, and a stance is only
        // returned when the simulation produced the wanted block state from it with the body kept out of the entire
        // target column.
        // ==========================================================================================================
        int px = pos.getX();
        int py = pos.getY();
        int pz = pos.getZ();
        if (cellVerdictStillHolds(px, py, pz)) {
            // already judged, and nothing around it has changed since -- also ist das Urteil von damals noch das
            // Urteil von jetzt, und es steht in der Notiz.
            long notiert = cellVerdicts.get(positionKey(pos));
            return new CellUrteil.Parken(
                    (notiert & VERDICT_NO_STANCE) != 0 ? ParkReason.NO_STANCE : ParkReason.NO_FACE,
                    "Urteil von frueher gilt noch (keine Nachbarflaeche hat sich geaendert)");
        }
        if (clickableFaceMask(px, py, pz) == 0L) {
            // NO SCAFFOLDING HERE. It was here for one revision and the owner took it straight back out: "Nix mit ein
            // Block Geruest. Keinen Geruestquatsch hier. Das koennen wir spaeter hinzufuegen. Aber jetzt erst mal
            // kriegen wir die Basics vernuenftig hin." No face means postponed, and nothing else.
            if (darfArbeiten) {
                recordCellVerdict(px, py, pz, VERDICT_NO_FACE);
            }
            return new CellUrteil.Parken(ParkReason.NO_FACE, "kein Nachbar bietet eine Flaeche zum Anklicken");
        }
        // NO RANGE LIMIT. A 6-block gate used to stand here, so whether a cell got a computed position depended on
        // where the bot happened to be standing -- the "roughly" being removed.
        long key = positionKey(pos);
        Optional<Goal> cached = orientedGoalCache.get(key);
        // placementStancesFor also returns empty when no hotbar item can simulate this placement. That is not
        // geometric evidence: parking it would suppress material demand and survive a refill of the hotbar.
        // Keep already proven goals/facts, but do not search, spend budget, or cache a negative without the item.
        if (cached == null && (wanted == null || hotbarStackThatPlaces(wanted) == null)) {
            return new CellUrteil.Unbekannt("kein passendes Material in der Schnellleiste fuer die Standplatzpruefung");
        }
        if (cached == null
                && darfArbeiten
                && stanceSearchNanos < STANCE_SEARCH_BUDGET_NANOS
                && orientedSearchesThisTick < MAX_ORIENTED_SEARCHES_PER_TICK) {
            long startedAt = System.nanoTime();
            orientedSearchesThisTick++;
            cached = Optional.ofNullable(orientedStandingGoal(px, py, pz, wanted, bcc));
            stanceSearchNanos += System.nanoTime() - startedAt;
            orientedGoalCache.put(key, cached);
            if (!cached.isPresent()) {
                recordCellVerdict(px, py, pz, VERDICT_NO_STANCE);
            }
        }
        if (cached != null) {
            // Judged this tick or earlier in it: the proven positions, or nothing. The verdict note above is what makes
            // this affordable -- orientedGoalCache is cleared every tick, so without the note every cell was searched
            // again EVERY tick, which is what took basalt to 45 of 15004 cells with walks=0 on the first attempt.
            return cached.map(ziel -> (CellUrteil) new CellUrteil.Setzen(ziel))
                    .orElseGet(() -> new CellUrteil.Parken(ParkReason.NO_STANCE,
                            "Flaechen vorhanden, aber keine Standposition erzeugt den gewollten Block"));
        }
        // The tick's 20 ms ran out before this cell was reached. Deliberately NOT a verdict: nothing was judged, so
        // nothing may be recorded, or the cell would be held on evidence never gathered. Retried next tick.
        //
        // GOALADJACENT IS GONE FROM HERE. Both forms were "roughly" and both are removed:
        //  - GoalAdjacent(pos, pos, true) accepted ANY neighbour including the cell whose y is one BELOW the target,
        //    i.e. standing with the HEAD inside the cell being filled. That is the fixpoint that froze run
        //    20260802-232954 for 11070 ticks on 70,-59,122 with the eye provably inside the target -- and the same
        //    failure the owner watched live on the facings top stone row on 05.08.
        //  - GoalAdjacent(pos, pos.relative(facing), ...) proved only that a FACE exists, never that any stance can
        //    produce the wanted state from it.
        //
        // THIS SUPPRESSION WAS MEASURED AND REVERTED ONCE, 04.08.2026: oriented 7/7 -> 6/7, the bot frozen at
        // 73,-60,67 from tick ~2140 to the end. Two things are different now, and this file's rule is that a measured
        // revert may only be retried with a new idea:
        //  1. the verdict note exists and releases a held cell IN THE TICK its neighbour lands, because the release
        //     condition is the neighbour face mask itself -- so "standing beside it in advance", which was the reason
        //     given for keeping the fallback, no longer buys anything;
        //  2. and the decisive one: when that standstill was measured, THE STANCE SEARCH WAS BROKEN. simulatePlacement
        //     wrote the rotation under test with setYRot/setXRot while vanilla read the look through getViewYRot, where
        //     the FlowCam mixin returned wherever the camera pointed. "Nothing is proven anywhere" was therefore the
        //     normal case, and a suppression on top of a search returning noise had to freeze. After the fix the same
        //     search takes facings from 59/96 to 96/96.
        //
        // What is still missing is step 7 as a PHASE: "nothing is placeable anywhere" should trigger deliberate
        // scaffolding for a whole pass, not only the one-step support above. Until then that state ends the build --
        // honestly, and visibly in the trace, instead of being papered over by a walk to nowhere.
        //
        // HIER WOHNT DER DRITTE ZUSTAND, und deshalb hat er jetzt einen Namen. Die Zelle bekommt kein Ziel und kein
        // Urteil -- nicht weil die Welt etwas gesagt haette, sondern weil niemand hingesehen hat. Fuer den Aufrufer
        // war das bisher dasselbe null wie "kein Halt" und "keine Standposition", und aus dieser Ununterscheidbarkeit
        // sind die sieben Wachhunde entstanden.
        return new CellUrteil.Unbekannt(!darfArbeiten
                ? "nur gelesen, und es stand noch nichts da"
                : orientedSearchesThisTick >= MAX_ORIENTED_SEARCHES_PER_TICK
                        ? "Standplatzsuche: " + MAX_ORIENTED_SEARCHES_PER_TICK + " Suchen in diesem Tick verbraucht"
                        : "Standplatzsuche: Zeitbudget des Ticks aufgebraucht, bevor diese Zelle drankam");
    }

    // ==============================================================================================================
    // DER SCHATTENBETRIEB. Er entscheidet nichts und aendert nichts -- er schreibt nur auf, was die totale
    // Untersuchung ueber eine Zelle GEWUSST haette, in genau dem Moment, in dem ein Wachhund anschlaegt.
    //
    // Die Frage, die er beantworten soll, ist die einzige, die ueber das Loeschen der sieben Wachhunde entscheidet:
    // schlagen sie auf Zellen an, die die Untersuchung laengst beurteilt haette (dann sind sie ueberfluessig und
    // verdecken nur ein fehlendes Parken), oder auf Zellen, ueber die wirklich nichts bekannt ist (dann fehlt
    // vorher etwas anderes -- naemlich Budget, um sie ueberhaupt anzusehen).
    //
    // Genauso ist der Bahn-Treiber eingefuehrt worden, und dort hat es getragen: erst tausend Sonden im Schatten,
    // dann die Entscheidung mit Zahlen statt mit Zutrauen.
    // ==============================================================================================================

    private int schattenSetzen;
    private int schattenParken;
    private int schattenUnbekannt;

    /** Was die totale Untersuchung ueber diese Zelle sagt -- rein lesend, ohne Budget zu verbrauchen. */
    private void schattenUrteil(String anlass, BlockPos zelle, BuilderCalculationContext bcc) {
        if (zelle == null || bcc == null) {
            return;
        }
        CellUrteil urteil;
        try {
            urteil = urteileUeber(zelle, bcc, false);
        } catch (RuntimeException e) {
            return;   // eine Beobachtung darf den Bau unter keinen Umstaenden anhalten
        }
        String text;
        if (urteil instanceof CellUrteil.Setzen) {
            schattenSetzen++;
            text = "SETZEN";
        } else if (urteil instanceof CellUrteil.Parken parken) {
            schattenParken++;
            text = "PARKEN " + parken.grund() + " -- " + parken.beleg();
        } else {
            schattenUnbekannt++;
            text = "UNBEKANNT -- " + ((CellUrteil.Unbekannt) urteil).warum();
        }
        BuildTrace.cell(buildTick, "URTEIL", zelle.getX(), zelle.getY(), zelle.getZ(),
                anlass + ": " + text);
    }

    /**
     * Wer ausser dem Bot in dieser Welt steht -- und das gehoert in JEDEN Bericht.
     *
     * <p>Am 09.08. starben zwei Laeufe frueh und an derselben Zelle, 937 und 247 statt der ueblichen 4300. Ich
     * habe daraufhin meinen eigenen Code verdaechtigt und war schon beim Bisektieren, als der Eigentuemer sagte:
     * "Ich stand gegebenenfalls grade im Weg. Ich war auf dem Boden der Platte." Genau das war es, und es stand
     * in keiner Zeile. Ein Ausreisser muss von selbst sagen koennen, dass jemand drinstand -- sonst kostet er
     * Laeufe UND eine falsche Verdaechtigung.
     */
    private String fremdeSpielerZensus() {
        try {
            java.util.List<String> andere = new java.util.ArrayList<>(2);
            for (net.minecraft.world.entity.player.Player p : ctx.world().players()) {
                if (p != ctx.player()) {
                    andere.add(p.getName().getString());
                }
            }
            if (andere.isEmpty()) {
                return null;
            }
            return "ACHTUNG: " + andere.size() + " fremde(r) Spieler in der Welt (" + String.join(", ", andere)
                    + ") -- Messwerte dieses Laufs sind nicht vergleichbar";
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Der Zensus fuer die Verdikt-Zeile am Ende: wie oft die Wachhunde auf etwas anschlugen, das schon feststand. */
    private String schattenZensus() {
        int gesamt = schattenSetzen + schattenParken + schattenUnbekannt;
        if (gesamt == 0) {
            return null;
        }
        return "Wachhund-Schatten: " + gesamt + " Alarme -- schon beurteilt (PARKEN) " + schattenParken
                + ", waere setzbar gewesen " + schattenSetzen
                + ", wirklich unbekannt " + schattenUnbekannt;
    }

    /**
     * Routes into a floating target so the pathfinder must pillar/bridge a throwaway into the air cell directly below.
     * That one block is both floor and click face: after arriving, GoalAdjacent moves the body to a real side stance,
     * the builder places the target against the helper, and {@link #isScaffoldLeftBehind} removes the helper as soon as
     * the target is correct.
     *
     * <p>This is deliberately the one-step form requested by the owner, not a general scaffold planner. It never writes
     * into a schematic block, never replaces a non-air world state, never props up a block that would fall/break after
     * cleanup, and never runs while any ordinary solid click face already exists.</p>
     */
    private Goal temporarySupportGoal(BlockPos target, BlockState wanted, BuilderCalculationContext bcc) {
        if (wanted == null) {
            return null;
        }
        boolean hasClickSurface = false;
        for (Direction side : SIX_NEIGHBOURS) {
            if (MovementHelper.canPlaceAgainst(ctx, target.relative(side))) {
                hasClickSurface = true;
                break;
            }
        }
        BlockPos support = target.below();
        BlockState supportCurrent = bcc.bsi.get0(support);
        BlockState supportWanted = bcc.getSchematic(
                support.getX(), support.getY(), support.getZ(), supportCurrent);
        int localSupportX = support.getX() - origin.getX();
        int localSupportY = support.getY() - origin.getY();
        int localSupportZ = support.getZ() - origin.getZ();
        boolean supportInsideSchematic = localSupportX >= 0 && localSupportY >= 0 && localSupportZ >= 0
                && localSupportX < schematic.widthX()
                && localSupportY < schematic.heightY()
                && localSupportZ < schematic.lengthZ();
        if (!temporarySupportMayBeRouted(
                bcc.hasThrowaway,
                wanted.canSurvive(ctx.world(), target),
                wanted.getBlock() instanceof FallingBlock,
                hasClickSurface,
                supportCurrent.is(Blocks.AIR),
                supportInsideSchematic && (supportWanted == null || supportWanted.isAir()))) {
            return null;
        }
        long supportKey = positionKey(support);
        long targetKey = positionKey(target);
        boolean newlyReserved = !temporarySupportTargets.containsKey(supportKey)
                || temporarySupportTargets.get(supportKey) != targetKey;
        temporarySupportTargets.put(supportKey, targetKey);
        if (newlyReserved) {
            BuildTrace.cell(buildTick, "SUPPORT-GOAL", support.getX(), support.getY(), support.getZ(),
                    "serves=" + target.getX() + "," + target.getY() + "," + target.getZ()
                            + " want=" + blockName(wanted));
        }
        return new GoalPlace(support);
    }

    static boolean temporarySupportMayBeRouted(boolean hasThrowaway,
                                                boolean targetSurvivesWithoutSupport,
                                                boolean fallingTarget,
                                                boolean hasClickSurface,
                                                boolean supportIsPristineAir,
                                                boolean schematicWantsAirAtSupport) {
        return hasThrowaway
                && targetSurvivesWithoutSupport
                && !fallingTarget
                && !hasClickSurface
                && supportIsPristineAir
                && schematicWantsAirAtSupport;
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (buildInRows) {
            // A one-layer Map Art is its own floor. Stand above a horizontal neighbour so the wrong starter block
            // can be replaced without making the target itself the protected footing column.
            // A one-layer Map Art is its own floor. GoalBreak deliberately rejects every stance above the block,
            // which leaves no reachable goal once the only wrong cell is the original starter block: every same-level
            // stance is occupied by the finished picture and standing on the target itself is protected from mining.
            // Stand one block above a horizontal neighbour instead. That keeps both feet on an already-built pixel,
            // leaves the target out of the player's footing column and puts it within normal survival reach.
            return rowBreakGoal(pos);
        }
        if (Princeps.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    static Goal rowBreakGoal(BlockPos pos) {
        int feetY = pos.getY() + 1;
        return new GoalComposite(
                new GoalBlock(pos.getX() - 1, feetY, pos.getZ()),
                new GoalBlock(pos.getX() + 1, feetY, pos.getZ()),
                new GoalBlock(pos.getX(), feetY, pos.getZ() - 1),
                new GoalBlock(pos.getX(), feetY, pos.getZ() + 1)
        );
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            // Standing directly below a placement target puts that target in the player's head column. The placement
            // scan correctly refuses to materialise a block in the body, so this pose can never be "adjacent enough".
            // Run 9f951b3e froze for 4,454 ticks at feet 80,-60,68 below its last cell 80,-59,68 because this goal said
            // arrived while the click gate said impossible. Reject the geometry here, once, for every caller: pathing
            // must choose a real side/upper stance and can lay a scaffold step on the way when the stance lacks a floor.
            if (x == this.x && y == this.y - 1 && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    @Override
    public void onLostControl() {
        resetAutoDigLookProfile();
        // Only if nothing has already claimed the ending: a build that finished, or that aborted for a named
        // reason, passes through here too, and its reason must survive the teardown that reports it.
        if (ending == Ending.RUNNING && schematic != null) {
            finishWith(Ending.CANCELLED, "Build cancelled", null);
        }
        BuildTrace.stop();
        buildInRows = false;
        nextBuildInRows = false;
        bandFallbacks = 0;
        rowActiveBandStart = Integer.MIN_VALUE;
        rowActiveFrontier = Integer.MIN_VALUE;
        areaBand = null;
        areaBandTopCache = Integer.MIN_VALUE;
        areaCommittedBandTop = Integer.MIN_VALUE;
        snakeVerificationBandTop = Integer.MIN_VALUE;
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Princeps.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
        resetPlacementTracking();
    }

    /** Shared by new-job and teardown reset, including replacement of a paused job. */
    void resetBreakBranchTracking() {
        breakBranchProgress.clear();
        breakTargetObservation.clear();
    }

    /** Clears per-build placement/recovery/deferral tracking. Called from BOTH lifecycle
     *  entry points — {@link #onLostControl()} (teardown) and {@link #build(String, ISchematic, Vec3i)} (new job) —
     *  because a re-issued build does not always pass through onLostControl() first. Keep this the single source
     *  of truth so a field added to one path can never be forgotten in the other. */
    private void resetPlacementTracking() {
        resetBreakBranchTracking();
        excavationApproach.clear();
        excavationApproachToolChangedTick = Long.MIN_VALUE;
        homeRecoveryEnabled = false;
        homeRecovery = null;
        homeRecoveryAttemptRevision = Long.MIN_VALUE;
        homeFailedRoute = null;
        homeFailedTarget = null;
        homeFailedRevision = Long.MIN_VALUE;
        scaffoldMaterialTarget = null;
        scaffoldMaterialPausedAt = null;
        scaffoldMaterialAttemptTick = Long.MIN_VALUE;
        lastSupportRefusal = null;
        lastSupportDependent = null;
        lastSupportRefusalReason = null;
        supportRepair = null;
        if (cleanupEscape != null) cleanupEscape.probe.cancel();
        cleanupEscape = null;
        cleanupEscapeAttemptedOwners.clear();
        // cleanupEscapeDebt deliberately survives. Foreign replacement revokes mining, not the audit record.
        progressWatch.reset();
        progressActions.clear();
        confirmedProgressRevision = 0;
        progressExecutor = null;
        progressRoute = null;
        progressRouteTarget = null;
        progressRoutePositions = java.util.List.of();
        progressRoutePosition = -1;
        progressPhase = null;
        progressHold = false;
        clearElectedTarget();
        navigationScaffolds.clear();
        excavationFluidPlugs.clear();
        excavationActiveClock.clear();
        excavationRepairAim.clear();
        lastExcavationRepairAimTrace = null;
        blockedFluidPlugThisTick = null;
        layerMask = null;
        layerMaskSource = null;
        scaffoldCleanupActive = false;
        layerCleanupActive = false;
        scaffoldCleanupTargets.clear();
        // Die beiden Listen gehoeren zu EINEM Bauauftrag. Eine PARK-Liste, die einen Auftrag ueberlebt,
        // beschreibt eine Welt, die es nicht mehr gibt -- das ist der eine Fall, in dem 'persistent'
        // ausdruecklich nicht gilt.
        activeCells.clear();
        parkedCells.clear();
        cellsParked = 0;
        cellsWokenByWatchman = 0;
        // Eine angemeldete, nie ausgefuehrte Abbruchbitte darf keinen neuen Auftrag toeten. Das ENDING bleibt
        // dagegen stehen -- der Client liest es erst, nachdem der Prozess inaktiv geworden ist.
        abortPending = Ending.RUNNING;
        abortPendingHeadline = null;
        abortPendingDetail = null;
        replanWatchCell = null;
        replansForCurrentCell = 0;
        worstReplansForOneCell = 0;
        worstReplanCell = null;
        cellVerdicts.clear(); // a new build is a new world; no note from the last one may survive it

        CellWatch.reset();   // the dedupe state is per build; carrying it over hides the first verdict of the next one
        ActionJournal.reset();
        stanceFailures.clear();
        stanceEndorsed.clear();
        cellDeferralCounts = null;
        cellDeferredUntilTick = null;
        temporarySupportTargets.clear();
        // Sonst ueberlebt eine Bauplan-Ueberschreibung den Bau, der sie gesetzt hat -- und der naechste Bau
        // faende an dieser Stelle einen Hilfsblock im Soll, den niemand bestellt hat.
        scaffoldCell = null;
        scaffoldServes = null;
        scaffoldWanted = null;
        scaffoldsThisLayer = 0;
        scaffoldFailed.clear();
        scaffoldProbeCache.clear();
        ordinaryYieldCounts = null;
        sealHolds.clear();
        interactGiveUps = null;
        hotbarFetchRefusedTicks = 0;
        buildTick = 0;
        lastFullRecalcTick = Long.MIN_VALUE / 2;
        lastCellCompletedTick = 0;
        lastPlacedCellHash = -1;
        lastPlacedCell = null;
        lastPlacedDesired = null;
        repressCellHash = -1;
        repressCellTracked = false;
        repressCount = 0;
        releasePlacementTarget();
        pendingPlacementRequestSerial = 0;
        observedBlockClickSerial = princeps.getInputOverrideHandler().getBlockPlaceHelper()
                .getSuccessfulBlockInteractions();
        noProgressTicks = 0;
        lastCompletedSize = -1;
        resetNavigationProgressTracking();
        resetBreakProgressTracking();
        interactCellHash = -1;
        interactCellTracked = false;
        interactClicksLast = -1;
        interactNoProgressTicks = 0;
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Princeps.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public int retiredCellCount() {
        // Parked cells ARE what this used to count: cells set aside so the layer can be judged. The bench reads it
        // to tell "working through hard cells" from "dead", and a value that silently went to zero would change the
        // bench verdict without anything saying so.
        return parkedCells.size();
    }

    @Override
    public long[] stanceFailureCounts() {
        return new long[]{stancelessAbandons, cellsWokenByWatchman};
    }

    @Override
    public long[] placementQuality() {
        return new long[]{clicksSent, landedRight, landedWrong, blocksBroken};
    }

    @Override
    public long[] walkEfficiency() {
        return new long[]{walksStarted, walksEndedInPlacement};
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Princeps.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            net.minecraft.world.level.block.Block block = ((BlockItem) stack.getItem()).getBlock();
            BlockState itemState = block
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            // getStateForPlacement returns null for context-sensitive blocks (doors/beds/some multi-part) when
            // simulated against this dummy feet-up context. approxPlaceable is only ever matched at BLOCK level
            // (itemCanPlaceBlock / containsBlockState), so fall back to the item's own default block state instead
            // of AIR — otherwise a door/bed/chest we actually HAVE is falsely reported as a missing material and the
            // whole build stalls. Orientation is enforced later at possibleToPlace + the live-ray click gate, never here.
            result.add(itemState != null ? itemState : block.defaultBlockState());
            // </toxic cloud>
        }
        return result;
    }

    private static boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        if (first.equals(second)) {
            return true; // exact match — fast path
        }
        boolean ignoreDirection = Princeps.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Princeps.settings().buildIgnoreProperties.value;
        for (Property<?> prop : first.getProperties()) {
            if (first.getValue(prop).equals(second.getValue(prop))) {
                continue;
            }
            // environment-derived / interaction-only property: a placed fence/wall/pane/trapdoor is CORRECT regardless
            // of this value, so a mismatch here must never make the builder break-and-retry it forever.
            if (beyondOurControl(first.getBlock(), prop.getName())) {
                continue;
            }
            if (ignoreDirection && ORIENTATION_PROPS.contains(prop)) {
                continue;
            }
            if (ignoredProps.contains(prop.getName())) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Is this property beyond anything a builder can do -- neither placeable nor right-clickable?
     *
     * <p>Almost all of the classification is by property name alone, but {@code open} is the one property whose class
     * depends on the block carrying it. A wooden door, trapdoor or fence gate opens under a right-click, so it belongs
     * to the interaction pass. An IRON one moves only under redstone: placement cannot set it and
     * {@link #interactionClicks} correctly refuses it. That combination is a trap -- the cell is permanently invalid,
     * and a permanently invalid cell that still looks placeable gets broken and re-placed, so the builder would break
     * and re-place the same 40 iron trapdoors in etz-basalt for as long as the run lasted, never advancing the layer.
     *
     * <p>An iron trapdoor placed closed IS built correctly. Whether it is open afterwards is the redstone's business.
     */
    private static boolean beyondOurControl(Block block, String prop) {
        if (AUTO_RESOLVED_PROP_NAMES.contains(prop)) {
            return true;
        }
        return "open".equals(prop) && (block == Blocks.IRON_DOOR || block == Blocks.IRON_TRAPDOOR);
    }

    /** True when current already has the right block and every non-interaction, non-environment property matches —
     *  i.e. the block is correctly placed and oriented, and only a right-click-settable state (open/delay/mode/note)
     *  still differs. Such a block must be INTERACTED with, never broken-and-replaced. */
    private static boolean matchesExceptInteraction(BlockState current, BlockState desired) {
        if (desired == null || current.getBlock() != desired.getBlock()) {
            return false;
        }
        for (Property<?> p : desired.getProperties()) {
            String n = p.getName();
            if (INTERACTION_PROP_NAMES.contains(n) || AUTO_RESOLVED_PROP_NAMES.contains(n)) {
                continue;
            }
            if (!current.getValue(p).equals(desired.getValue(p))) {
                return false;
            }
        }
        return true;
    }

    /** How many right-clicks bring current's interactive state to desired's: repeater DELAY (+1 each, wraps 4→1),
     *  comparator MODE (toggle), note-block NOTE (+1 each, wraps 24→0), trapdoor/door/gate OPEN (toggle). Returns 0
     *  if already correct, and -1 if this is NOT interaction-fixable here (wrong block, wrong orientation, or an
     *  iron door/trapdoor a bare hand can't move). */
    private int interactionClicks(BlockState current, BlockState desired) {
        if (!matchesExceptInteraction(current, desired)) {
            return -1;
        }
        Block b = current.getBlock();
        if (b == Blocks.IRON_DOOR || b == Blocks.IRON_TRAPDOOR) {
            return -1; // only redstone toggles these — a right-click does nothing
        }
        if (b instanceof RepeaterBlock) {
            int d = current.getValue(RepeaterBlock.DELAY);
            int want = desired.getValue(RepeaterBlock.DELAY);
            return ((want - d) % 4 + 4) % 4;
        }
        if (b instanceof ComparatorBlock) {
            return current.getValue(ComparatorBlock.MODE) != desired.getValue(ComparatorBlock.MODE) ? 1 : 0;
        }
        if (b instanceof net.minecraft.world.level.block.DaylightDetectorBlock) {
            // Same shape as the comparator: one right-click flips it. Listed because `inverted` is in
            // INTERACTION_PROP_NAMES, and a property claimed there with no branch here returns -1 and sends the cell
            // to the break-and-replace path, which can never set it either -- an infinite loop instead of one click.
            return !current.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.INVERTED)
                    .equals(desired.getValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties.INVERTED)) ? 1 : 0;
        }
        if (b instanceof NoteBlock) {
            int d = current.getValue(NoteBlock.NOTE);
            int want = desired.getValue(NoteBlock.NOTE);
            return ((want - d) % 25 + 25) % 25;
        }
        if (b instanceof TrapDoorBlock) {
            return current.getValue(TrapDoorBlock.OPEN) != desired.getValue(TrapDoorBlock.OPEN) ? 1 : 0;
        }
        if (b instanceof DoorBlock) {
            return current.getValue(DoorBlock.OPEN) != desired.getValue(DoorBlock.OPEN) ? 1 : 0;
        }
        if (b instanceof FenceGateBlock) {
            return current.getValue(FenceGateBlock.OPEN) != desired.getValue(FenceGateBlock.OPEN) ? 1 : 0;
        }
        return -1;
    }

    private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (itemCanPlaceBlock(testee, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Princeps.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Princeps.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Princeps.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Princeps.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Princeps.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    /**
     * The owner's two lanes, expressed as the only thing that separates them: whether a route may place a block.
     *
     * <p>{@code LEGACY} is today's behaviour and stays the default until the lane driver is switched on, so
     * introducing this changes nothing that can be measured.
     */
    public enum Lane {
        /** Bahn A: no block, in planning and in execution. */
        A_NO_PLACING,
        /** Bahn B: helper blocks where the template wants air. Entered only after A found nothing. */
        B_HELPERS_ALLOWED,
        /** AutoDig: a short path may bridge exactly its next centre-line floor cell and nowhere else. */
        EXCAVATION_PATH,
        /** The initial journey to the selected entry; its current movement alone may make ordinary access cuts. */
        EXCAVATION_APPROACH,
        /** What the builder did before the lanes existed. */
        LEGACY,
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        /** Unmasked model and original world captured before the path worker starts; excavation opts out. */
        private final BuilderSupportDependencies supportDependencies;
        private final ModelProtection modelProtection;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final long excavationBridgeKey;
        private final BetterBlockPos excavationRouteStart;
        private final BetterBlockPos excavationRouteWaypoint;
        /**
         * May the SEARCH plan a helper block at all, snapshotted once per calculation.
         *
         * <p>THE BUG THIS CLOSES, and it is the same trap twice in one day. {@code scaffoldIsLicensedAt} was consulted
         * at execution time only -- {@code MovementHelper:1175} and {@code MovementPillar:292}, and no cost function
         * anywhere. So A* went on planning the very movement the executor is categorically forbidden to run, and the
         * result was a perfect two-stroke: tick N a two-node path (one MovementTraverse bridging a gap), the executor
         * refuses it and calls {@code PathExecutor.cancel()}, tick N+1 there is no path so a fresh calculation starts,
         * tick N+2 it returns the identical path. Measured in run f159bc1d over its last 1920 ticks: 921 len2 ticks of
         * which 914 carry a SCAFFOLD-REFUSED, 922 goal-no-path ticks of which 914 carry none, 882 refusal gaps of
         * exactly two ticks, and the bot's pos byte-identical for 31 ticks at a time. The owner saw it as the drawn
         * route changing twenty times a second while the bot stood still.
         *
         * <p>{@code MovementHelper:1136} had already written the rule down: "a cost governs which path the SEARCH
         * picks, not what a running movement does". Both halves are needed -- the cost so the route is never planned,
         * the execution veto so it can never happen anyway.
         *
         * <p>Snapshotted rather than read live because path calculation runs off the game thread, and the lock it
         * reads is game-thread state. A snapshot that goes stale mid-calculation costs one discarded path; a data race
         * costs a crash nobody can reproduce.
         */
        private final boolean scaffoldLicensed;
        /** Which of the owner's two lanes this context expresses. See {@link Lane}. */
        private final Lane lane;
        private final boolean rowMode;
        private final boolean ordinaryExcavationMode;
        private final boolean excavationMode;
        private final Object approachToken;
        private final List<ItemStack> approachTools;
        private final boolean approachItemSaver;
        private final int approachItemSaverThreshold;
        private final int fullWidth, fullHeight, fullLength;
        private final ExcavationFluidPlugs fluidPlugSnapshot;
        private final boolean rowSweepAlongX;
        private final int rowBandStart;
        private final int rowFrontier;
        private final PlatformTraverseApproach platformApproach;

        public BuilderCalculationContext() {
            this(Lane.LEGACY, null);
        }

        public BuilderCalculationContext(Lane lane) {
            this(lane, null);
        }

        private BuilderCalculationContext(Lane lane, BetterBlockPos excavationRouteWaypoint) {
            this(lane, excavationRouteWaypoint, true);
        }

        private BuilderCalculationContext(Lane lane, BetterBlockPos excavationRouteWaypoint, boolean permitFallWater) {
            super(BuilderProcess.this.princeps, true, permitFallWater);
            this.lane = lane;
            this.ordinaryExcavationMode = ordinaryExcavation();
            this.excavationMode = excavating;
            this.approachToken = lane == Lane.EXCAVATION_APPROACH ? excavationApproach.token() : null;
            this.approachItemSaver = Princeps.settings().itemSaver.value;
            this.approachItemSaverThreshold = Princeps.settings().itemSaverThreshold.value;
            this.approachTools = approachToken == null ? List.of()
                    : ctx.player().getInventory().getNonEquipmentItems().stream().limit(9).map(ItemStack::copy).toList();
            ISchematic full = realSchematic == null ? BuilderProcess.this.schematic : realSchematic;
            this.fullWidth = full.widthX();
            this.fullHeight = full.heightY();
            this.fullLength = full.lengthZ();
            this.fluidPlugSnapshot = excavating ? excavationFluidPlugs.snapshot() : new ExcavationFluidPlugs();
            this.platformApproach = platformTraverseApproach;
            this.excavationRouteStart = lane == Lane.EXCAVATION_PATH ? ctx.playerFeet() : null;
            this.excavationRouteWaypoint = lane == Lane.EXCAVATION_PATH ? excavationRouteWaypoint : null;
            this.rowMode = buildInRows;
            this.rowSweepAlongX = sweepAlongX();
            this.rowBandStart = rowActiveBandStart;
            this.rowFrontier = rowActiveFrontier;
            // A Map-Art route may advance by placing the exact requested pixel beneath its next step. Those pixels
            // can live anywhere in the 36-slot inventory, not just the hotbar; InventoryBehavior performs the swap.
            // Generic builder routing keeps its stable nine-slot snapshot unchanged.
            this.placeable = approxPlaceable(buildInRows ? 36 : 9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();
            this.supportDependencies = excavating ? null : new BuilderSupportDependencies(
                    supportModelForDependencies(), new Vec3i(originX, originY, originZ), placeable, bsi, ctx.world());
            this.modelProtection = excavating ? null : new ModelProtection(BuilderProcess.this,
                    supportModelForDependencies(), new Vec3i(originX, originY, originZ), ctx.world(), ctx.player(), placeable);
            this.excavationBridgeKey = snakeBridgeTarget == null
                    ? Long.MIN_VALUE : snakeBridgeTarget.asLong();
            // THE ONE PLACE THE TWO LANES DIFFER, and it is deliberately the only one: everything else about the
            // two contexts is identical, so a route that lane A cannot find and lane B can differs by exactly the
            // permission to put a block down -- not by favouring, not by budget, not by geometry.
            if (buildInRows) {
                // Mirror scaffoldIsLicensedAt exactly: a search must never offer an airborne Map-Art route whose
                // bridge/pillar the executor will reject. Template-block placement remains the explicit exemption in
                // getCostOfPlacingAt below and is therefore still normal, free build progress.
                this.scaffoldLicensed = false;
            } else {
                switch (lane) {
                    case A_NO_PLACING:
                    case EXCAVATION_APPROACH:
                        this.scaffoldLicensed = false;
                        break;
                    case B_HELPERS_ALLOWED:
                        this.scaffoldLicensed = true;
                        break;
                    case EXCAVATION_PATH:
                        this.scaffoldLicensed = this.excavationBridgeKey != Long.MIN_VALUE;
                        break;
                    default:
                        this.scaffoldLicensed = !NAVIGATION_MAY_NEVER_SCAFFOLD
                                && scaffoldPassAllowed
                                && (electedCell != null
                                    || (placementTargetLock.hasPlannedStance() && plannedPlacementStance != null));
                        break;
                }
            }

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;

            // THE BUILDER MAY JUMP GAPS. Off by default in the library, and the bench does not enable it, so every
            // one-block hole in a half-built floor was a wall -- and the only way through a wall is to fill it with a
            // helper block. That is why 171 of 174 helper blocks in run bc6db984 went into y=-60 (the floor) and 123 of
            // them sat 20-34 blocks out at the rim, where the floor is sparsest. Jumping is free and leaves nothing
            // behind; bridging costs a block that may become built in. allowParkourAscend is already true by default
            // and only takes effect together with this, so the jump-and-climb case comes along.
            //
            // Note what this does NOT change: stepping UP one block was always allowed (MovementAscend needs no
            // setting). This is about crossing gaps, not about height. And allowParkourPlace stays as the user set it
            // -- see the field comment in CalculationContext.
            //
            // EXCEPT ON A PICTURE, WHERE A GAP IS NOT A HOLE IN THE FLOOR -- IT IS THE VOID.
            //
            // The paragraph above is right for a structure that stands on the ground: a one-block hole in a
            // half-built floor is a wall, jumping it is free, and filling it with a helper block costs a block that
            // may become built in. Map Art in row mode is the case it does not describe. The picture is one block
            // thick with open air beneath it, so the gaps in that floor are not holes -- they are the unbuilt part
            // of the picture with nothing at all below. A jump commits the body before it can know it has landed,
            // and here the price of not landing is not a stumble: it is the whole inventory and the bot.
            //
            // A customer filmed the result twice and died of it once ("fell from a high place"), and the module on
            // the client side had to grow a whole fall-recovery machine to climb back out of falls of 61, 20 and 16
            // blocks. None of that is repair; it is the consequence of planning a route no builder should ever be
            // offered. In row mode the frontier already decides the order the cells are reached in, and every one
            // of them is reached by walking on picture that already exists.
            this.allowParkour = lane != Lane.EXCAVATION_PATH && !buildInRows;
        }

        public ModelProtection modelProtection() { return modelProtection; }

        @Override
        public Object excavationApproachToken() {
            return approachToken;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            BlockPos pos = new BlockPos(x, y, z);
            if ((scaffoldCleanupActive || layerCleanupActive) && scaffoldCleanupTargets.contains(pos.asLong())
                    && (current.isAir() || navigationScaffolds.owns(pos, current))) {
                return Blocks.AIR.defaultBlockState();
            }
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public boolean isPathPositionAllowed(int x, int y, int z) {
            if (platformApproach != null) {
                BetterBlockPos from = platformApproach.from, target = platformApproach.target;
                return y == from.y && ((x == from.x && z == from.z) || (x == target.x && z == target.z));
            }
            if (lane != Lane.EXCAVATION_PATH) {
                return true;
            }
            // One command, one cardinal edge. A* still produces and drives the normal MovementTraverse (including
            // human steering and sneak-backplace), but it cannot trade the licensed bridge for a shoulder detour,
            // fall, stair or pillar. Repeating this with the next waypoint yields the strict one-block snake lane.
            return excavationRouteStart != null && excavationRouteWaypoint != null
                    && y == excavationRouteStart.y
                    && ((x == excavationRouteStart.x && z == excavationRouteStart.z)
                        || (x == excavationRouteWaypoint.x && z == excavationRouteWaypoint.z));
        }

        private boolean rowTemplatePlacementIsLicensedAt(int x, int y, int z) {
            if (!rowMode) {
                return true;
            }
            int row = rowSweepAlongX ? x : z;
            int along = rowSweepAlongX ? z : x;
            return rowBandContains(row, rowBandStart, BAND_ROWS) && along == rowFrontier;
        }

        /*
         * A33, REVERTED: "helper block costs COST_INF until a path calculation has failed, then ten blocks of walking".
         *
         * The reasoning was sound about A* -- a price cannot express "last resort", because a high cost puts the bridge
         * behind an ENUMERATION of every cheaper state, and that enumeration is what exhausts the budget (measured on
         * run bc6db984: 1.2M movements considered, open set 31000, no path, while the bot had a throwaway and nothing
         * refused it). The implementation was wrong on two counts, and the measurement said so at once:
         *
         *   walks 196 -> 276, paidOff 169 (86.2%) -> 134 (48.6%);  placed 2750 -> 2631;  layer y=-59 open 7 -> 126
         *
         *   1. COST_INF does not make an edge expensive, it DELETES it. Helper blocks are not only for the last
         *      unreachable cells -- 123 to 174 are placed in an ordinary run, because a course that is 24% filled has
         *      to be bridged constantly just to walk it. Forbidding them by default removed hundreds of perfectly
         *      normal routes that happened to contain one bridge block.
         *   2. The lock re-armed on every completed cell, so a route planned while unlocked became invalid the moment
         *      anything landed anywhere. That is the "plans a route, discards it, never executes one" the owner watched
         *      on screen: 40% more routes walked and only half of them ending in a placement, where the run before this
         *      change landed 86%. (Reported once as "paidOff=1 of 276" -- a misread of a log line truncated at 200
         *      characters, mid-number. The regression is real and is a halving, not a collapse.)
         *
         * So the escalation has to be a property of the SEARCH (two passes over one goal, the second one only if the
         * first returns no path) and not of the cost function -- and it must never remove an edge that ordinary
         * movement depends on. Left as a comment rather than a flag, because a dormant switch here reads like an
         * option and it is not one.
         */
        /**
         * The execution-side half of the same rule the cost function above expresses.
         *
         * <p>Read once when this context's route is created and never again. Lane A forbids every placement; lane B
         * allows one only where the TEMPLATE WANTS AIR, which is the owner's standing rule for helper blocks in his
         * own words: "nur dort, wo in der Schematic Luft vorgesehen wäre. Nicht so, dass diese im Weg sein könnten
         * bei einer späteren Blockplatzierung nach Plan."
         *
         * <p>It closes over the schematic and the origin, both fixed for the whole build, so nothing inside it can
         * change while a route is in flight — which is the entire difference to the field it replaces.
         */
        @Override
        public PlacementLicence placementLicence() {
            if (lane == Lane.EXCAVATION_APPROACH) return PlacementLicence.NONE;
            switch (lane) {
                case A_NO_PLACING:
                    return PlacementLicence.NONE;
                case B_HELPERS_ALLOWED: {
                    final ISchematic fixed = this.schematic;
                    final int ox = this.originX;
                    final int oy = this.originY;
                    final int oz = this.originZ;
                    final List<BlockState> stock = this.placeable;
                    return PlacementLicence.where(packed -> {
                        BlockPos at = BlockPos.of(packed);
                        int lx = at.getX() - ox;
                        int ly = at.getY() - oy;
                        int lz = at.getZ() - oz;
                        if (lx < 0 || ly < 0 || lz < 0
                                || lx >= fixed.widthX() || ly >= fixed.heightY() || lz >= fixed.lengthZ()) {
                            return true;   // outside the build volume: not the template's business at all
                        }
                        try {
                            BlockState wanted = fixed.desiredState(lx, ly, lz, Blocks.AIR.defaultBlockState(), stock);
                            return wanted == null || wanted.isAir();
                        } catch (RuntimeException ignored) {
                            return false;  // a schematic that throws for a cell is not a licence to wreck that cell
                        }
                    }, "template wants air");
                }
                case EXCAVATION_PATH: {
                    final long only = this.excavationBridgeKey;
                    return PlacementLicence.excavationBridge(only);
                }
                default:
                    return PlacementLicence.UNRESTRICTED;
            }
        }

        /**
         * The corridor may be ankle-deep. Everything else stays a wall.
         *
         * <p>An excavation exposes the sources it is cutting through, and water in a tunnel it has already cleared is
         * an ordinary consequence, not a defect the route can wait out: the source that feeds it is often past the
         * flooded stretch, and the reach-local seal cannot get to it from here. Meanwhile pathing calls a fluid at
         * level != 8 impassable, {@code getMiningDurationTicks} turns that into COST_INF, and this lane may not
         * break, detour, parkour or scaffold its way around -- so one flowing cell in a one-cell corridor is the end
         * of the run. Run a0a00867: 2106 of 10800 cells, then 1900 ticks of nothing at 77,-51,68 with 77,-51,69 wet.
         *
         * <p>Licensed here and nowhere else, and only for the two body cells of the single cardinal step this
         * context was built for -- the same two columns {@link #isPathPositionAllowed} already restricts the search
         * to. Water only: {@code WadeLicence} is asked about a cell, and no caller ever names one holding lava.
         */
        @Override
        public WadeLicence wadeLicence() {
            if (lane != Lane.EXCAVATION_PATH
                    || excavationRouteStart == null || excavationRouteWaypoint == null) {
                return WadeLicence.NONE;
            }
            final int startX = excavationRouteStart.x;
            final int startZ = excavationRouteStart.z;
            final int stepX = excavationRouteWaypoint.x;
            final int stepZ = excavationRouteWaypoint.z;
            final int feetY = excavationRouteStart.y;
            return WadeLicence.where(packed -> {
                BlockPos at = BlockPos.of(packed);
                if (at.getY() != feetY && at.getY() != feetY + 1) {
                    return false;   // feet and head of the corridor, never the floor and never the ceiling
                }
                return (at.getX() == startX && at.getZ() == startZ)
                        || (at.getX() == stepX && at.getZ() == stepZ);
            }, "AutoDig corridor step");
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (platformApproach != null && !platformApproach.target.equals(new BlockPos(x, y, z))) return COST_INF;
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (lane == Lane.EXCAVATION_PATH && !excavationPlacementAllowed(excavationBridgeKey, x, y, z)) {
                return COST_INF;
            }
            // TELL THE PLANNER, NOT ONLY THE EXECUTOR. The owner's objection to the first attempt, and he was right:
            // the veto sat at execution time, so A* went on planning routes that build and the bot discovered the ban
            // one movement at a time -- 552 refused ascends, 248 of them at the single cell 75,-60,68, because a
            // refused movement does not make the router avoid the node. Basalt fell to 1997/15004 measuring nothing
            // except how often it walked into an invisible wall.
            //
            // A price cannot express "impossible", and HELPER_BLOCK_COST below is a price. COST_INF is the only thing
            // the search reads as "there is no route through here", which is what makes it look for a walking one.
            //
            // Still allowed: filling a cell the template names with the block it is waiting for, when the executor
            // will really do it. That is free progress, not scaffolding, and the two must not be conflated -- the
            // exemption mirrors the one at the executor site exactly.
            // AIR in placeable represents an empty/non-block inventory slot. Matching that sentinel to template
            // AIR cannot authorize a helper: lane A's executor still carries PlacementLicence.NONE.
            if (!scaffoldLicensed
                    && !(sch != null && !sch.isAir() && containsBlockState(placeable, sch) && !placementStateIsGeometrySensitive(sch)
                        && rowTemplatePlacementIsLicensedAt(x, y, z))) {
                return COST_INF;
            }
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return HELPER_BLOCK_COST; // the owner's price: one helper block is worth twenty blocks of walking
                }
                if (containsBlockState(placeable, sch)) {
                    // FREE to fill a planned cell with the block it is waiting for -- but only when the executor will
                    // actually do it. selectThrowawayForLocation refuses to use a geometry-sensitive block as a
                    // stepping stone (a piston placed by the movement placer takes whatever facing the walk happens
                    // to produce, and the builder then has to break it again). Pricing that free is how the router
                    // plans a route the executor then declines every tick, which is a stall with no counter on it.
                    // Planner and executor answer the same question here, deliberately.
                    if (placementStateIsGeometrySensitive(sch)) {
                        return COST_INF;
                    }
                    if (!rowTemplatePlacementIsLicensedAt(x, y, z)) {
                        return COST_INF;
                    }
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // A HELPER BLOCK INTO A CELL THE TEMPLATE NAMES. Forbidden outright, not priced.
                //
                //
                // This is the case where the pathfinder drops a throwaway into a cell that wants a REAL block it
                // cannot currently place. Priced at 15x placeBlockCost it happened routinely, and the owner watched
                // the consequence live in run 20260802-212908:
                //
                //   Deferring unresolved cell at 112,-59,124 ...
                //     (BREAK: wrong block cobblestone present, can't clear it (want sticky_piston))
                //
                // The cell is then occupied AND unbuildable, which is strictly worse than the detour the price was
                // supposed to buy. The owner's rule is that a helper block may only go where the template wants AIR,
                // and must come out again -- so this branch is an invariant, not a cost trade-off. A cost always
                // loses eventually; that is what 15x demonstrated.
                //
                // WHY THE PREVIOUS ATTEMPT AT THIS FAILED, and why this one is different: raising
                // placeIncorrectBlockPenaltyMultiplier to 1_000_000 was tried and immobilised the bot, because that
                // multiplier is shared with the branch ABOVE -- the schematic-AIR cells, which are the whole empty
                // volume of the farm and exactly where bridging has to happen. Pricing both out left nowhere to
                // walk. The two cases are distinguishable right here ("the template says air" vs "the template says
                // a block"), so only the second one is closed and the first keeps its ordinary price.
                return COST_INF;
            } else {
                if (hasThrowaway) {
                    return HELPER_BLOCK_COST; // outside the blueprint, so also a helper block, so the same price
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public boolean placedBlockIsStandable(int x, int y, int z, BlockState current) {
            BlockState sch = getSchematic(x, y, z, current);
            // Not in the schematic, or a schematic AIR cell -> a generic full-cube throwaway is placed -> standable.
            if (sch == null || sch.getBlock() instanceof AirBlock) {
                return true;
            }
            // The schematic's OWN block is placed here (costOfPlacingAt makes that FREE). It is only a valid
            // pillar / step-up base if it is a full standable cube — a schematic fence/wall/pane/thin block is not,
            // so refuse to plan a step-up that would loop jumping on a block you cannot stand on.
            return MovementHelper.isBlockNormalCube(sch);
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if (platformApproach != null) return COST_INF;
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            if (fluidPlugSnapshot.size() > 0 && fluidPlugSnapshot.firstHazard(List.of(new BlockPos(x, y, z)),
                    bsi.access, ExcavationFluidPlugs::vanillaSourceConversion).isPresent()) return COST_INF;
            if (supportDependencies != null && !supportDependencies.removal(new BlockPos(x, y, z)).allowed()) {
                return COST_INF;
            }
            boolean outsideSelection = x < originX || x >= originX + fullWidth
                    || y < originY || y >= originY + fullHeight || z < originZ || z >= originZ + fullLength;
            if (lane == Lane.EXCAVATION_APPROACH && outsideSelection) {
                return approachToken != null && current.getFluidState().isEmpty()
                        && ordinaryMiningToolSlot(approachTools, current, approachItemSaver,
                            approachItemSaverThreshold) >= 0 ? 1 : COST_INF;
            }
            if (excavationMode && outsideSelection) return COST_INF;
            // The snake itself owns every excavation break, including the exact 3x3 face and its rotation settle.
            // Navigation is only allowed to walk that cleared corridor and bridge its one licensed floor cell. If A*
            // may break here it can tunnel sideways, shave the ceiling, or invent a stair around a ravine; all three
            // abandon the route and bypass the Shard face gate.
            if (lane == Lane.EXCAVATION_PATH) {
                return COST_INF;
            }
            if (areaBreakBelowBand(x, y, z)) {
                return COST_INF;
            }
            // The picture is the walking surface. Mining any support below its footprint turns an ordinary route into
            // a fall beneath the only stances from which the remaining pixels can be placed. Scope this invariant to
            // the caller-selected row mode so generic structures retain builder/v3's established routing costs.
            if (buildInRows && origin != null && realSchematic != null && y < origin.getY()
                    && x >= origin.getX() && x < origin.getX() + realSchematic.widthX()
                    && z >= origin.getZ() && z < origin.getZ() + realSchematic.lengthZ()) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (!ExcavationRepairPolicy.navigationMayMine(ordinaryExcavationMode, sch)) {
                // Generic single-block navigation may clear only AIR cells owned by this excavation layer.
                // Outside access tunnels, repaired shell blocks and lower-band shortcuts are never mining work.
                return COST_INF;
            }
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Princeps.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
