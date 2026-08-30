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

import princeps.Princeps;
import princeps.behavior.LookBehavior;
import princeps.api.utils.input.Input;
import princeps.api.pathing.movement.ActionCosts;
import princeps.api.process.IBuilderProcess;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.schematic.FillSchematic;
import princeps.api.schematic.ISchematic;
import princeps.api.schematic.IStaticSchematic;
import princeps.api.schematic.MaskSchematic;
import princeps.api.schematic.MirroredSchematic;
import princeps.api.schematic.RotatedSchematic;
import princeps.api.schematic.SubstituteSchematic;
import princeps.api.schematic.format.ISchematicFormat;
import princeps.api.utils.Rotation;
import princeps.pathing.movement.CalculationContext;
import princeps.process.builder.BuildTrace;
import princeps.utils.BlockStateInterface;
import princeps.utils.PrincepsProcessHelper;
import princeps.utils.schematic.MapArtSchematic;
import princeps.utils.schematic.SchematicSystem;
import princeps.utils.schematic.SelectionSchematic;
import princeps.utils.schematic.litematica.LitematicaHelper;
import princeps.utils.schematic.schematica.SchematicaHelper;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongPredicate;

/**
 * Builder V3 — the engine that proves the whole build before placing the first block.
 *
 * <p>The outermost layer, and the only class in {@code princeps.process.builder.v3} allowed to touch the client. It
 * owns the two things the core deliberately cannot: {@link V3Settings#capture()}, which is the single
 * {@code Princeps.settings()} read in the package, and every {@code ctx.*} / {@code princeps.*} call. Everything
 * below it takes values. That is not architecture for its own sake — a class that reads {@code Princeps.settings()}
 * cannot be instantiated in a unit test at all, so pushing those reads here is what makes the planner and the oracle
 * testable, and testable is the difference between this attempt and the last one.
 *
 * <h2>Shape of a run</h2>
 * <ol>
 *   <li>{@code build(...)} captures the settings, snapshots the world into a {@link PredictedWorld}, and runs the
 *       planner to completion — before the bot takes a step. Seconds, not hours.</li>
 *   <li>The {@link PlanReport} says READY with counts, or INCOMPLETE with every blocking cell named and the cells
 *       that would unblock them. INCOMPLETE does not start.</li>
 *   <li>{@code onTick} walks the frozen {@link BuildPlan}. It makes no decisions: take the next action, run the state
 *       machine, confirm, advance.</li>
 * </ol>
 *
 * <h2>Rules this class must keep, each of which has already cost a day somewhere</h2>
 * <ul>
 *   <li>{@code clearAllKeys()} at the top of every {@code onTick}, then re-assert every key that should be held.
 *       Forced inputs are sticky and nothing clears them for you.</li>
 *   <li>Never return null from {@code onTick} while {@link #isActive()} — the control manager throws.</li>
 *   <li>Never break and place in the same tick: {@code CLICK_LEFT} clears {@code CLICK_RIGHT} before the helpers
 *       tick, and the pending placement commit is consumed with it, silently.</li>
 *   <li>Arm {@code expectMainHandPlacement} and force {@code CLICK_RIGHT} in the SAME tick, or the commit evaporates
 *       with no diagnostic.</li>
 *   <li>Two-phase handoff before any manual WASD: return a bare {@code CANCEL_AND_SET_GOAL} first, wait for
 *       {@code getCurrent() == null}, set keys the tick after. Setting keys in the same tick as the cancel wipes
 *       them — a measured 1740-tick freeze.</li>
 *   <li>Zero the per-run counters in {@link #build}, never in {@link #onLostControl()}: {@code onLostControl} fires
 *       when the build FINISHES, before the bench reads the verdict, so clearing there reports zeros for every
 *       successful run.</li>
 *   <li>Diagnostics go through {@code logMechanic}, never {@code logDebug} — {@code chatDebug} defaults off and the
 *       stdout fallback is commented out, so {@code logDebug} reaches chat, stdout and the log file equally: not at
 *       all.</li>
 *   <li>{@link #isActive()} is {@code schematic != null}. The bench stops with INACTIVE the moment it reads false,
 *       so it must stay true across planning, not just across execution.</li>
 * </ul>
 */
public final class PlannedBuilderProcess extends PrincepsProcessHelper implements IBuilderProcess {

    /**
     * The executor's cell automaton. One owner per state, and the ownership table is the point: two consumers reaching
     * for look, hotbar and movement without precedence is what tore down 32 already-placed cells in a traced V2 run,
     * one of them 48 times.
     *
     * <pre>
     * state      movement     look         hotbar
     * EQUIP      -            -            executor
     * TRAVEL     pathfinder   pathfinder   locked
     * APPROACH   executor     executor     locked
     * AIM        -            executor     locked
     * GATE/CLICK -            executor     locked
     * CONFIRM    -            executor     locked
     * </pre>
     */
    public enum State {

        /** Take the next action from the frozen order. */
        SELECT,

        /** Put the item on the hotbar and select the planned slot. The only state that may touch the inventory. */
        EQUIP,

        /** {@code GoalBlock(stance)} — the exact cell, never an adjacent goal. A goal already satisfied where the
         *  bot stands produces no path and no arrival, which is the 486-tick standstill in one sentence. */
        TRAVEL,

        /** Sneak-walk to the planned approach point, tolerance 0.08 blocks. A regular state, not a recovery path. */
        APPROACH,

        /** Turn to the planned rotation through the aim curve — several ticks, monotone, deterministic. */
        AIM,

        /** Wait until the LIVE ray produces the planned result and the place helper is not throttled. */
        GATE,

        /** Exactly one click, with the expected placement armed in the same tick. */
        CLICK,

        /** Server acknowledgement received AND the state held for three ticks. The client predicts a placement
         *  optimistically; a protection plugin reverts it two to six ticks later, and a builder that trusts its own
         *  prediction has already moved on. */
        CONFIRM,

        /** Run the cell's follow-up actions — interaction, sign text, fluid. */
        ACT,

        /** Advance. */
        DONE,

        /** The live world disagrees with the plan's preconditions. Re-plan immediately from the current world; no
         *  waiting, no backoff, no watchdog. */
        DIVERGED,

        /** Three structurally different plans and the server confirmed none of them. The cell is named and the build
         *  stops. This is the one terminal state, and it is loud on purpose. */
        BLOCKED
    }

    private ISchematic schematic;
    private String name;
    private Vec3i origin;

    /** Captured once per build, in {@link #build}. Never re-read mid-run: a plan proven under one set of acceptance
     *  rules and executed under another proves nothing. */
    private V3Settings settings;

    private PredictedWorld world;
    /** The frozen target states for the current planning generation. Kept during execution so a server packet for a
     *  cell already retired can still be judged against the target rather than against stale executor bookkeeping. */
    private SchematicView view;
    private BuildPlan plan;
    private PlanReport report;

    /** The oracle the dry run proved this plan with. Kept because the executor's click gate re-derives the predicted
     *  state from the LIVE ray with it, and a gate judging by different rules than the proof did would let exactly the
     *  wrong block through. Rebuilt by every dry run, including a re-plan's. */
    private PlacementOracle oracle;

    /** The cell automaton. Created when a dry run says READY and dropped on stand-down; null means there is nothing to
     *  execute, which is a different thing from "the plan is empty". */
    private CellExecutor executor;

    /**
     * The server's own answer about every click. One per process instance, not one per build: the event bus has no
     * deregistration, so registering per build would leave a listener behind for every build of the session, each one
     * still counting packets. Armed with {@link ServerAck#activate()} and disarmed with {@link ServerAck#deactivate()}.
     */
    private ServerAck ack;

    /**
     * What the server refused, across plans. The one piece of state a re-plan inherits, and the reason re-planning
     * terminates: a refused {@code (cell, stance, face)} triple is vetoed in every later plan, and a cell refused three
     * structurally different ways allows nothing at all and comes back as an ordinary blocker.
     */
    private final EvidenceJournal journal = new EvidenceJournal();

    /** How many times the plan has been rebuilt from the live world after a divergence. Names the artefact files of
     *  each generation so a re-plan cannot overwrite the plan it replaced. */
    private int replans;

    /** The previous divergence reason, and how many times it has repeated unchanged across a fresh re-plan. See
     *  {@link #IDENTICAL_DIVERGENCES_BEFORE_BLOCKED}. */
    private String lastDivergence;
    private int identicalDivergences;

    /** Index into {@link BuildPlan#actions()}. The whole of the executor's decision-making. */
    private int actionIndex;

    /**
     * How many layers one dry run proves before handing the body back its legs.
     *
     * <p>One. The whole basalt plan is tens of minutes during which the bot cannot move, because the dry run holds the
     * game thread; the bottom layer alone plans in six seconds and then takes minutes to build. Planning the next
     * layer while the current one is being built spends time the build was going to spend anyway.
     *
     * <p>It gives up nothing the frozen plan guaranteed. Every action of the layer in hand is still proved before a
     * single click, and the next layer is derived from the world the bot has by then actually built — which is
     * stronger evidence than a prediction of it, not weaker.
     */
    private static final int LAYERS_PER_DRY_RUN = 1;

    /** Set by the dry run: there are layers this plan deliberately did not cover. See {@link #LAYERS_PER_DRY_RUN}. */
    private boolean moreLayersPending;

    private State state;
    private boolean paused;

    /** Per-run counters. Zeroed in {@link #build}, NOT in {@link #onLostControl()} — see the class javadoc. */
    private long clicksSent;
    private long landedRight;
    private long landedWrong;
    private long blocksBroken;
    private long walksStarted;
    private long walksEndedInPlacement;
    private long divergences;

    /** Cells placed exactly as planned — same order, same stance, same aim point, one click, no divergence — over
     *  cells placed. The headline metric; target is 100 %, and every shortfall names a cell and a reason. */
    private long planFidelityNumerator;
    private long planFidelityDenominator;

    /** Ticks since {@link #build} — the first column of every {@link BuildTrace} line, so a cell's whole life sorts. */
    private long buildTick;

    /** True only while THIS process owns the trace. {@code BuildTrace} is a static singleton shared with the V2
     *  engine, so an unconditional {@code stop()} in {@link #onLostControl()} would close a trace this process never
     *  opened. {@code onLostControl} is called on every registered process by {@code cancelEverything}, which makes
     *  that a real cross-engine hazard rather than a theoretical one. */
    private boolean tracing;

    /** Names the three plan files on disk. Fixed per run, never timestamped — see {@link PlanTraceWriter#runId}. */
    private String runId;

    /** Why the build stopped, in one line, or null. The HONEST channel for "I cannot proceed": the bench scores
     *  {@code isPaused() == true} as FAILURE, so a blocked V3 must never signal by pausing. It names the cause here,
     *  in the log and in the trace, and then stops being active. */
    private String blockedReason;

    /** Cells the executor has confirmed, keyed by {@code BlockPos.asLong}. Read by rule N1 — see
     *  {@link #pathfinderMayMutateAt}. Deliberately NOT keyed by {@code PlacementGeometry.positionKey}: this set is
     *  built and read only here, and mixing key schemes across classes is how a lookup silently misses. */
    private final LongOpenHashSet completedCells = new LongOpenHashSet();

    /** Cells the pathfinder may never fill as throwaways, even outside the schematic box: every future stance, its
     *  floor, action target, clicked neighbour and sampled aim ray in the frozen plan. */
    private final LongOpenHashSet protectedPathCells = new LongOpenHashSet();

    /** Last accepted state for every completed schematic cell. This starts at the placement action's predicted state
     *  and may advance to the final schematic state after an interaction. A later packet that matches neither is a
     *  rollback and immediately re-opens the cell. */
    private final Map<Long, BlockState> completedExpected = new HashMap<>();

    /** Late authoritative changes are observed from the packet listener and consumed at the top of the next process
     *  tick. Keeping packet observation and replanning separate prevents a packet callback from replacing the
     *  executor in the middle of its own tick. */
    private final List<ServerInvalidation> serverInvalidations = new ArrayList<>();
    private String unloadedCompletedProof;

    private record ServerInvalidation(BlockPos cell, BlockState expected, BlockState observed) {
    }

    /** Degrees the VISIBLE aim moved since the previous tick. A click fired mid-swing reaches the server at an angle
     *  the planner never computed, and this is the only column that can show it after the fact. */
    private float aimMovementDegrees;
    private float prevAimYaw;
    private float prevAimPitch;
    private boolean prevAimValid;
    /** Identity of the last route written as a P line; node lists are recorded once, not once per movement tick. */
    private Object lastTracedPath;

    public PlannedBuilderProcess(Princeps princeps) {
        super(princeps);
    }

    /** Is this the engine the session selected? The unselected engine is inert: it refuses {@link #build} and reports
     *  {@link #isActive()} false whatever its own state, so exactly one builder can ever be in control. */
    private boolean selected() {
        return princeps.getBuilderProcess() == this;
    }

    /**
     * How often one divergence may survive a re-plan unchanged before the build stops by name.
     *
     * <p>Not a watchdog and not a timer — it is a fixed-point detector. A divergence reason carries the cell and the
     * fact that went wrong, so the SAME sentence after a plan freshly derived from the current world is proof that
     * re-planning is not going to change the answer, and one more lap would produce the same sentence again. The
     * design's terminating mechanism is the rejection journal, which only sees clicks the server refused; this covers
     * the other half, where the world contradicts the plan before a click is ever sent and no evidence accumulates.
     *
     * <p>Three rather than two so that two genuinely distinct cells failing the same way in a row cannot end a run,
     * and it deliberately matches {@link EvidenceJournal#REJECTIONS_BEFORE_BLOCKED} — the two halves of "I have tried
     * this three ways" should not have two different numbers.
     */
    private static final int IDENTICAL_DIVERGENCES_BEFORE_BLOCKED = 3;

    // ------------------------------------------------------------------------------------------- IBuilderProcess

    /**
     * Capture the world, plan the entire build, and report. Does not move the bot.
     *
     * <p>Replicates V2's wrapper chain — substitutes, mirror, rotation, mask, the three {@code schematicOrientation}
     * axes — because those are user-facing behaviour rather than engine behaviour, and a schematic that builds
     * mirrored under one engine and not the other is a bug report nobody can read.
     */
    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        if (!selected()) {
            // Both engines are registered; only one may ever be active. Refusing here rather than only reporting
            // isActive() false means the unselected engine cannot even be handed a schematic by a command that got a
            // stale reference.
            logMechanic("v3: refusing build '" + name + "' — the session selected the v2 engine");
            return;
        }

        // The trace is opened HERE, not lazily, so its first line is the first tick of the build and no cell's record
        // can begin mid-life. A fault-only log is what hid 175 missing cells for eight sessions; this engine's whole
        // instrumentation story is that the record exists before the first decision does.
        this.runId = PlanTraceWriter.runId(name);
        // Made now, not when the planner first has something to write, so a run that produces no plan still leaves an
        // obvious empty place to look rather than nothing at all.
        PlanTraceWriter.directory();
        if (Boolean.getBoolean("princeps.buildtrace")) {
            Path traced = BuildTrace.start(this.runId);
            this.tracing = traced != null;
            if (traced != null) {
                logDirect("Tracing every tick of this build to " + traced.toAbsolutePath());
            }
        }

        // Zeroed HERE and never in onLostControl(): onLostControl fires the moment a build FINISHES, which is before
        // the bench reads the verdict, so clearing there made every successful run report zeros.
        this.clicksSent = 0;
        this.landedRight = 0;
        this.landedWrong = 0;
        this.blocksBroken = 0;
        this.walksStarted = 0;
        this.walksEndedInPlacement = 0;
        this.divergences = 0;
        this.planFidelityNumerator = 0;
        this.planFidelityDenominator = 0;
        this.buildTick = 0;
        this.blockedReason = null;
        this.completedCells.clear();
        this.protectedPathCells.clear();
        this.completedExpected.clear();
        this.serverInvalidations.clear();
        this.unloadedCompletedProof = null;
        this.actionIndex = 0;
        this.moreLayersPending = false;
        this.plan = null;
        this.report = null;
        this.world = null;
        this.view = null;
        this.oracle = null;
        this.executor = null;
        this.paused = false;
        this.prevAimValid = false;
        this.aimMovementDegrees = 0F;
        this.lastTracedPath = null;
        this.replans = 0;
        this.lastDivergence = null;
        this.identicalDivergences = 0;
        // Forgotten at build start alongside the counters: a cell the last build could not get past may be perfectly
        // placeable in this one, because the thing that refused it was somebody else's rule and rules change.
        this.journal.reset(0L);

        this.name = name;
        this.schematic = applyUserSchematicTransforms(schematic);
        this.origin = orientedOrigin(schematic, origin);
        this.settings = V3Settings.capture();
        this.state = State.SELECT;

        BuildTrace.cell(this.buildTick, "BUILD", this.origin.getX(), this.origin.getY(), this.origin.getZ(),
                "engine=v3 name=" + name + " dims=" + this.schematic.widthX() + "x" + this.schematic.heightY()
                        + "x" + this.schematic.lengthZ() + " run=" + this.runId);
        logMechanic("v3: build '" + name + "' origin=" + this.origin.getX() + "," + this.origin.getY() + ","
                + this.origin.getZ() + " dims=" + this.schematic.widthX() + "x" + this.schematic.heightY()
                + "x" + this.schematic.lengthZ());

        armAcknowledgements();
        runDryRun();
    }

    /**
     * Put the packet listener on the bus once, and arm it for this build.
     *
     * <p>The four seams are wired here and nowhere else, because they are the four things the listener may not reach
     * for itself: the client thread, the live world, the acceptance rules, and the journal.
     *
     * <p>{@code itemVerify = true} in the matcher, deliberately different from the executor's {@code false}. This one
     * asks "was my click accepted", which is the question {@code placementResultAccepted} asks and which the
     * {@code buildIgnoreExisting} and {@code buildValidSubstitutes} escapes must not answer; the executor's hold asks
     * "is this cell done", where they must. V2 keeps the two apart for the same reason.
     */
    private void armAcknowledgements() {
        if (this.ack == null) {
            this.ack = new ServerAck(
                    task -> ctx.minecraft().execute(task),
                    pos -> {
                        // Null after a world change: the listener is deactivated on WorldEvent, but a packet already
                        // in flight can reach a tick that has not run yet, and a null world here would throw out of
                        // the netty bounce with no owner.
                        net.minecraft.world.level.Level level = ctx.world();
                        return level == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
                    },
                    (observed, desired) -> {
                        V3Settings frozen = this.settings;
                        return frozen != null && frozen.valid(observed, desired, true);
                    },
                    new ServerAck.ChangeSink() {
                        @Override
                        public void onServerBlockChange(BlockPos pos, BlockState state) {
                            observeServerBlockChange(pos, state);
                        }

                        @Override
                        public void onChunkUnload(int chunkX, int chunkZ) {
                            observeServerChunkUnload(chunkX, chunkZ);
                        }
                    },
                    PlannedBuilderProcess.this::logMechanic);
            this.ack.registerWith(princeps);
        }
        this.ack.activate();
    }

    // ------------------------------------------------------------------------------------------------ the dry run

    /**
     * Plan the entire build and report, before the bot takes a step. Sets {@link #plan} only on READY.
     *
     * <p>Synchronous, on the main thread, on purpose. {@link PredictedWorld#capture} may only run here — it is the one
     * place a {@code BlockStateInterface} can be built at all — and the plan that follows is pure computation over the
     * snapshot, so handing it to a worker would buy a responsive title screen at the cost of a concurrency seam with
     * no consumer: {@link #isActive()} is {@code schematic != null}, which stays true across a synchronous plan just as
     * it would across an asynchronous one. The freeze is the dry run's whole runtime and is the number 5.11 asks to be
     * measured; it is logged below rather than left to be guessed at.
     *
     * <p>This is also the re-plan (E-D): {@link #replan()} calls exactly this method, so a divergence an hour into a
     * build is answered by the same code, the same policy and the same determinism as the first plan. The only thing
     * that carries over is {@link #journal}, and it carries over as an INPUT — see the overload of
     * {@link OrderPlanner#plan} taking a {@link PlacementOracle.StanceFilter}.
     *
     * <p>Every failure mode here ends the same way — {@link #blockedBy}, by name. A dry run that cannot answer must
     * not fall through into a build, because falling through into a build is the engine being replaced.
     */
    private void runDryRun() {
        long startedNanos = System.nanoTime();
        PredictedWorld snapshot;
        SchematicView view;
        HotbarSchedule.InventorySnapshot inventory;
        PlanReport planned;
        try {
            snapshot = captureWorld();
            inventory = captureInventory();
            // The schematic is resolved against the UNTOUCHED snapshot (SchematicView reads world.original), so the
            // view and the world below it describe the same instant even though the planner then mutates the world.
            view = SchematicView.capture(this.name, this.schematic, this.origin, snapshot,
                    ledgerPlaceable(inventory), this.settings.waterlogging());

            PlacementOracle oracle = new PlacementOracle(this.settings, PlayerPose.CROUCHED);
            // Kept: the executor's click gate re-derives the predicted state from the LIVE ray with this same oracle,
            // and a gate that judged by different rules than the proof did is a gate that lets the wrong block through.
            this.oracle = oracle;
            // The identity quantiser: the dry run proves geometry, and the executor's aim curve is what has to hit the
            // proven rotation rather than the other way round. A quantiser here would prove a plan against angles the
            // planner invented and the look behaviour never produces.

            // Rule 1 of the scaffold: a helper block must be a material the schematic never asks for, or the ledger
            // cannot tell a leftover from a finished cell. Empty is a real outcome for a cobblestone schematic carrying
            // only cobblestone, and it is a named limitation in the report rather than a silent fallback.
            Optional<Item> scaffoldItem = ScaffoldPlanner.chooseScaffoldItem(view, inventory,
                    Princeps.settings().acceptableThrowawayItems.value);
            if (scaffoldItem.isEmpty()) {
                logMechanic("v3: no throwaway material that the schematic does not itself use — "
                        + "any cell that needs a helper block will be reported as blocked");
            }
            // THROWAWAY_SLOT and not a free slot of our choosing: InventoryBehavior.onTick re-asserts the first
            // acceptable throwaway into slot 8 every tick (trap 1.29), so planning the helper block anywhere else
            // means the plan and the inventory automaton fight over one slot for the length of the build.
            ScaffoldPlanner scaffold = new ScaffoldPlanner(oracle, this.settings, SolveBudget.DEFAULT,
                    scaffoldItem.orElse(null), HotbarSchedule.THROWAWAY_SLOT);

            // The journal goes in as a filter rather than being consulted afterwards: a plan that proposed a triple the
            // server has already refused and was then filtered would still have SPENT that cell's solve on it, and the
            // second-best solution would never be found. The planner ANDs it with its own within-plan vetoes.
            // The dry run owns the game thread for its whole duration, so its only way to say it is alive is the log.
            OrderPlanner planner = new OrderPlanner(oracle, scaffold)
                    .onProgress(this::logMechanic)
                    .planAtMostLayers(LAYERS_PER_DRY_RUN);
            planned = planner.plan(view, snapshot, this.settings, SolveBudget.DEFAULT,
                    inventory, ctx.playerFeet(), this.journal);
            this.moreLayersPending = planner.truncated();
        } catch (RuntimeException e) {
            // A planner bug must surface as a named stop, not as an exception thrown out of a chat command that leaves
            // the process holding a schematic and no plan — that state passes isActive() and would then be executed.
            // The stack trace goes to the log because the message alone never identifies which of eleven classes threw.
            logMechanic("v3: dry run threw " + e);
            e.printStackTrace();
            blockedBy("the dry run failed: " + e);
            return;
        }

        // The planner mutates the world it plans against and leaves it holding the FINISHED build — its own javadoc
        // says so and points here. Rewinding it is what makes the field mean what the executor needs it to mean: the
        // world at build start, so that "does the live world still match" is a comparison against the premise rather
        // than against the conclusion. Without this, world.get() answers with the completed schematic for every cell
        // the plan covers and every divergence check reads clean.
        snapshot.clearDeltas();
        this.world = snapshot;
        this.view = view;
        this.report = planned;

        List<Path> written = PlanTraceWriter.writeAll(artefactId(), planned.plan(), planned);
        long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
        // The duration is logged and deliberately NOT written into any of the three files: two runs of the same input
        // must produce byte-identical output, and a clock in the artefact would defeat the reproducibility check that
        // is this engine's sharpest regression test (plan 13).
        logMechanic("v3: dry run finished in " + millis + " ms — " + planned.headline());
        logDirect(planned.headline());
        for (Path file : written) {
            logMechanic("v3: wrote " + file.toAbsolutePath());
        }
        BuildTrace.cell(this.buildTick, "PLAN", this.origin.getX(), this.origin.getY(), this.origin.getZ(),
                "status=" + planned.status() + " proven=" + planned.proven() + " provisional=" + planned.provisional()
                        + " cells=" + planned.cellsTotal() + " actions=" + planned.plan().size()
                        + " blockers=" + planned.blockers().size() + " gen=" + this.replans + " ms=" + millis);

        if (planned.status() != PlanReport.Status.READY) {
            // The first few blockers by name, in chat, and the file for the rest: a report on a 63x63 schematic can
            // name thousands of cells, and a chat flood is how the ONE line that matters gets scrolled away.
            reportBlockers(planned);
        }

        // Build everything that is proven.
        //
        // There used to be a second gate here — a RUNNABLE PREFIX that stopped the order at the first INTERACT,
        // WRITE_SIGN or FILL_FLUID, because ActionRunner implemented all three and nothing constructed one. It was
        // never a design position, only a gate on an unfinished seam, and it was expensive: etz-basalt stopped after
        // 894 of 4383 actions because action 894 was a repeater interaction, so 32 right-clicks cost 3489 placements.
        // CellExecutor.delegate closes that seam, so the prefix is gone rather than merely widened.
        //
        // What the prefix protected is now structural instead. It was never allowed to be a FILTER: skipping an
        // unrunnable action and carrying on would place the blocks after it while quietly abandoning a sign's text or
        // a repeater's delay, and the cell would then read as satisfied because the block is standing. That guarantee
        // now comes from the frozen order itself — every action of a cell sits in it, and the executor advances only
        // on a confirmation — rather than from refusing to start.
        //
        // What is NOT given up is the honesty about an INCOMPLETE plan: every cell with no solution is still named up
        // front, in chat and in the report file, rather than discovered three hours in.
        if (planned.plan().size() == 0) {
            String externallyBlocked = this.journal.externallyBlockedCells().isEmpty() ? ""
                    : " — " + this.journal.blockedReport();
            String why = planned.status() == PlanReport.Status.READY
                    ? "the plan is empty — every cell the schematic asks for already holds what it wants"
                    : "plan incomplete — " + planned.blockers().size() + " cells have no solution; see "
                            + planFile("-report.txt") + externallyBlocked;
            blockedBy(why);
            return;
        }

        this.plan = planned.plan();
        this.actionIndex = 0;
        this.state = State.SELECT;
        startExecutor();
        if (this.moreLayersPending) {
            logMechanic("v3: this plan covers one layer; the next is planned once this one stands");
        }
    }

    /**
     * Hand the proven order to the cell automaton.
     *
     * <p>The executor is rebuilt per plan rather than merely re-{@code begin}-ed, because it holds the oracle and the
     * frozen settings the plan was proven under and a re-plan produces a new oracle over the same settings. One owner
     * per state (E-C): the process owns the plan and the counters, the executor owns look, movement, hotbar and the
     * click, and neither reaches into the other's half.
     */
    private void startExecutor() {
        protectPlanCells();
        this.executor = new CellExecutor(princeps, ctx, this.oracle, this.settings, this.view, this::calcContext,
                new CountingListener());
        // The acknowledgement is polled, never ticked, from inside the executor: the process ticks it once per tick in
        // executePlannedAction so it advances exactly once whatever the automaton is doing, and so its latency clock
        // keeps running across TRAVEL legs that never ask it anything.
        this.executor.acknowledgements(new ExecutorAcknowledgements());
        this.executor.begin(this.plan);
    }

    /** How many blocked cells go to chat before the reader is sent to the file instead. */
    private static final int BLOCKERS_IN_CHAT = 5;

    /** Where one of the three artefacts is, for a message that tells the reader where to look. {@code directory()}
     *  returns null on an unwritable disk, and a stop reason reading {@code null/etz-report.txt} would send whoever
     *  reads it looking for a directory called null. */
    private String planFile(String suffix) {
        Path dir = PlanTraceWriter.directory();
        String file = artefactId() + suffix;
        return dir == null ? file + " (could not be written)" : dir.resolve(file).toString();
    }

    /**
     * What this generation's three files are named after: {@link #runId} for the first plan, and {@code runId.rN} for
     * the {@code N}-th re-plan.
     *
     * <p>A re-plan must not overwrite the plan it replaced. The two together are the evidence for "the world changed
     * and this is how the answer changed"; one file that is silently the newest of a series is evidence of nothing.
     * Still no timestamp anywhere, so the reproducibility check (plan 13) survives — the generation number is derived
     * from the run, not from a clock.
     */
    private String artefactId() {
        return this.replans == 0 ? this.runId : this.runId + ".r" + this.replans;
    }

    private void reportBlockers(PlanReport planned) {
        int shown = Math.min(BLOCKERS_IN_CHAT, planned.blockers().size());
        for (int index = 0; index < shown; index++) {
            PlanReport.Blocker blocker = planned.blockers().get(index);
            logDirect("  blocked at " + blocker.cell().getX() + "," + blocker.cell().getY() + ","
                    + blocker.cell().getZ() + "  " + blocker.desired().getBlock().getDescriptionId()
                    + "  reason: " + blocker.dominantReason());
        }
        if (planned.blockers().size() > shown) {
            logDirect("  ... and " + (planned.blockers().size() - shown) + " more, all named in the report file");
        }
    }

    /**
     * The world as it stands, plus {@link PredictedWorld#DEFAULT_MARGIN} of context on every side.
     *
     * <p>Main thread only, and this is the reason the whole dry run is: {@code BlockStateInterface}'s constructor
     * throws {@code IllegalStateException} off the main thread by design. Built WITHOUT the threaded chunk copy — the
     * copy exists for the pathing thread, and paying for it here would duplicate every loaded chunk in the region to
     * hand it to a reader on the very thread that owns the originals.
     *
     * <p>{@code worldContainsLoadedChunk} and not the Princeps chunk cache: a cached chunk is a MEMORY of the world,
     * and the difference between PROVEN and PROVISIONAL (5.10) is the whole value of the proof.
     */
    private PredictedWorld captureWorld() {
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        Vec3i min = this.origin;
        // Math.max(0, size - 1) mirrors SchematicView.capture exactly: a degenerate zero-extent schematic must give
        // the two of them the same box, or the view holds cells the world never captured.
        Vec3i max = new Vec3i(
                this.origin.getX() + Math.max(0, this.schematic.widthX() - 1),
                this.origin.getY() + Math.max(0, this.schematic.heightY() - 1),
                this.origin.getZ() + Math.max(0, this.schematic.lengthZ() - 1));
        return PredictedWorld.capture(bsi::get0, bsi::worldContainsLoadedChunk, min, max,
                PredictedWorld.DEFAULT_MARGIN, this.settings);
    }

    /**
     * The 36 survival slots, frozen. Hotbar 0-8 then the backpack, the layout
     * {@link HotbarSchedule.InventorySnapshot} documents and {@code Inventory.getNonEquipmentItems()} produces.
     *
     * <p>Every stack is COPIED. The live {@code ItemStack} is mutated in place as the player consumes, so a snapshot
     * holding the live objects would report the count at the moment it is READ rather than the count it was taken at —
     * and the material ledger's whole job is to answer that question about the start of the build.
     */
    private HotbarSchedule.InventorySnapshot captureInventory() {
        List<ItemStack> live = ctx.player().getInventory().getNonEquipmentItems();
        List<ItemStack> slots = new ArrayList<>(HotbarSchedule.INVENTORY_SLOTS);
        for (int index = 0; index < HotbarSchedule.INVENTORY_SLOTS; index++) {
            slots.add(index < live.size() ? live.get(index).copy() : ItemStack.EMPTY);
        }
        return new HotbarSchedule.InventorySnapshot(slots, ctx.player().getAbilities().instabuild);
    }

    /**
     * What {@link ISchematic#desiredState} is told the builder can place — the material ledger's item set, exactly as
     * {@link SchematicView#capture} asks for, and NOT {@link #getApproxPlaceable()}.
     *
     * <p>That method returns empty on purpose (see its javadoc): it is the per-tick question, and a schematic that
     * chose a different state depending on what the bot happened to be holding this tick could not be planned against
     * at all. The inventory snapshot is the answer to the same question asked once, at build start, which is what
     * makes it usable as a premise.
     *
     * <p>Deterministic because {@code distinctItems()} sorts by registry id: two runs against the same inventory hand
     * the schematic the same list in the same order, whatever order the slots happen to be in.
     */
    private List<BlockState> ledgerPlaceable(HotbarSchedule.InventorySnapshot inventory) {
        List<BlockState> placeable = new ArrayList<>();
        for (Item item : inventory.distinctItems()) {
            if (item instanceof BlockItem blockItem) {
                placeable.add(blockItem.getBlock().defaultBlockState());
            }
        }
        return placeable;
    }

    /**
     * The user-facing schematic transforms, applied in V2's order: substitutes, mirror, rotation, skip mask.
     *
     * <p>These are the only {@code Princeps.settings()} reads in this class besides {@link V3Settings#capture()}, and
     * they are deliberately not part of that snapshot: nothing below this class ever sees them. They are baked into
     * the {@link ISchematic} before the world snapshot is taken, so the planner and the oracle receive a plain
     * schematic and stay settings-free — which is the property that makes them unit-testable at all.
     *
     * <p>Replicated rather than reinvented because they are user-facing behaviour, not engine behaviour, and a
     * schematic that builds mirrored under one engine and upright under the other is a bug report nobody can read.
     */
    private ISchematic applyUserSchematicTransforms(ISchematic raw) {
        ISchematic result = raw;
        if (!Princeps.settings().buildSubstitutes.value.isEmpty()) {
            result = new SubstituteSchematic(result, Princeps.settings().buildSubstitutes.value);
        }
        if (Princeps.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            result = new MirroredSchematic(result, Princeps.settings().buildSchematicMirror.value);
        }
        if (Princeps.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            result = new RotatedSchematic(result, Princeps.settings().buildSchematicRotation.value);
        }
        return new MaskSchematic(result) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic, so desiredState is never null here.
                return !Princeps.settings().buildSkipBlocks.value
                        .contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
    }

    /** The three {@code schematicOrientation} axes, which shift the origin by the schematic's own extent. */
    private Vec3i orientedOrigin(ISchematic raw, Vec3i origin) {
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Princeps.settings().schematicOrientationX.value) {
            x += raw.widthX();
        }
        if (Princeps.settings().schematicOrientationY.value) {
            y += raw.heightY();
        }
        if (Princeps.settings().schematicOrientationZ.value) {
            z += raw.lengthZ();
        }
        return new Vec3i(x, y, z);
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (format.isEmpty()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            // Litematica throws on an unknown property value and kills the whole parse; say so rather than returning
            // a bare false that reads as "wrong file extension".
            logDirect("Could not parse " + schematic.getName() + ": " + e);
            return false;
        }
        build(name, applyMapArtAndSelection(origin, parsed), origin);
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
        if (!SchematicaHelper.isSchematicaPresent()) {
            logDirect("Schematica is not present");
            return;
        }
        Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
        if (schematic.isEmpty()) {
            logDirect("No schematic currently open");
            return;
        }
        IStaticSchematic raw = schematic.get().getA();
        BlockPos origin = schematic.get().getB();
        build(raw.toString(), applyMapArtAndSelection(origin, raw), origin);
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (!LitematicaHelper.isLitematicaPresent()) {
            logDirect("Litematica is not present");
            return;
        }
        // if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
        if (!LitematicaHelper.hasLoadedSchematic(i)) {
            logDirect(String.format("List of placements has no entry %s", i + 1));
            return;
        }
        Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
        Vec3i correctedOrigin = schematic.getB();
        build(schematic.getA().toString(), applyMapArtAndSelection(correctedOrigin, schematic.getA()),
                correctedOrigin);
    }

    /**
     * The user-facing stop. The build keeps its plan and its counters; nothing is torn down.
     *
     * <p>The pending acknowledgement IS dropped, and that is not tidiness. {@link ServerAck} measures its window
     * against {@link #buildTick}, which keeps running while paused, but the executor's own clock does not — so a click
     * left in flight across a pause of any length comes back {@code TIMED_OUT} the instant the build resumes, for a
     * cell that may well have landed perfectly. A verdict nobody is waiting for is worth less than no verdict.
     */
    @Override
    public void pause() {
        this.paused = true;
        if (this.ack != null) {
            this.ack.clearExpectation();
        }
    }

    /** Note for anyone tempted to use this as a "blocked" channel: the bench scores {@code isPaused() == true} as
     *  FAILURE. A V3 that wants to say "I cannot proceed because of X" says it through the report and the log, and
     *  stops being active. */
    @Override
    public boolean isPaused() {
        return this.paused;
    }

    @Override
    public void resume() {
        this.paused = false;
    }

    /** Explicit volume clearing. Deliberately separate from building: the engine breaks only what stands in the way
     *  of a planned action and never empties cells the schematic happens to call air as a side effect. */
    @Override
    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()),
                Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    /**
     * Empty, permanently — not a placeholder. (It read "empty until the hotbar schedule exists (W3)"; the schedule
     * now exists, and the answer is still empty, for the reason below rather than for want of one.)
     *
     * <p>The list means "states this builder would accept", and a schematic consults it to pick a state the builder
     * will not choke on. V3 answers that question differently: it accepts exactly what it planned, and the plan is
     * made against a material ledger rather than against whatever happens to be on the hotbar this tick. Returning a
     * live per-tick guess would be a second, contradicting answer.
     */
    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>();
    }

    /**
     * Empty, both of them. These two report the bounds of {@code mineInLayers}-style layer limiting to a HUD, and
     * V3's layer order is a property of the frozen plan rather than of a global setting — there is no "current layer"
     * that a live reader could act on without also reading the plan.
     */
    @Override
    public Optional<Integer> getMinLayer() {
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        return Optional.empty();
    }

    /**
     * Always 0. V3 has no retirement — there is no defer, no backoff, no layer amnesty and no watchdog, so a cell is
     * open, done, or named as blocked, and nothing is ever quietly set aside.
     *
     * <p>Consequence for the bench, which must be understood before reading a STALLED verdict: its stall test counts
     * growth in this number as progress, so a V3 run is judged on placements alone. Compare with {@code -PnoStall}.
     */
    @Override
    public int retiredCellCount() {
        return 0;
    }

    @Override
    public long[] walkEfficiency() {
        return new long[]{this.walksStarted, this.walksEndedInPlacement};
    }

    /** {@code {targets abandoned for want of any stance, retired cells brought back}}. Both are structurally zero
     *  here: a cell with no stance never enters the plan in the first place, it is named in the report instead. */
    @Override
    public long[] stanceFailureCounts() {
        return new long[]{0L, 0L};
    }

    @Override
    public long[] placementQuality() {
        return new long[]{this.clicksSent, this.landedRight, this.landedWrong, this.blocksBroken};
    }

    @Override
    public long[] executionQuality() {
        return new long[]{this.divergences, this.replans};
    }

    // ------------------------------------------------------------------- rule N1: no schematic block from pathing

    /**
     * Always null, for every cell, on purpose.
     *
     * <p>Rule N1: the pathfinder must never place a schematic block. {@code InventoryBehavior} asks this to pick a
     * throwaway, and a non-null answer lets it hand the movement placer the schematic's OWN block as a stepping
     * stone — with whatever facing falls out of wherever the bot happened to be looking. Measured on run ce0b0946:
     * 32 cells torn down, 25 of them never clicked by the builder at all, ONE of them broken forty-eight times, plus
     * every wrongly-oriented piston in the finished world. None of those placements came from the builder.
     *
     * <p>Null makes the selector fall through to {@code acceptableThrowawayItems}, which is the only material the
     * pathfinder is allowed to put down. The cost half of the same rule lives in {@link #calcContext()}.
     *
     * <p>Unconditional rather than "null inside the box": outside the box null is already the contract, and inside
     * the box a completed cell holds a block, so nothing is choosing a throwaway for it either. There is no cell for
     * which a non-null answer would help, and a guard that could be true is a guard that will one day be true.
     */
    @Override
    public BlockState placeAt(int x, int y, int z, BlockState current) {
        return null;
    }

    /** Engine-independent: a pure vanilla collision query, identical to V2's, kept on the interface only because
     *  {@code BackfillProcess} needs it and reaches the builder to get it. */
    @Override
    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(ctx.world(), pos);
        return shape.isEmpty()
                || ctx.world().isUnobstructed(null, shape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * The calculation context every {@code PathingCommandContext} this process issues must carry.
     *
     * <p>The cost half of rule N1. The base context gives both throwaway placement and ordinary block breaking finite
     * costs, so a route straight through the blueprint is merely expensive rather than forbidden. Here every mutation
     * inside the schematic's bounding box, and at every future plan-critical cell outside it, is {@code COST_INF}:
     * navigation routes around the build instead of placing through it or mining a completed cell for headroom.
     *
     * <p>Rebuilt per call, never cached: a {@code CalculationContext} reads every setting exactly once and snapshots
     * the world, so one held across ticks plans against a world that no longer exists.
     */
    public CalculationContext calcContext() {
        return new PlannedCalculationContext();
    }

    /** Whether navigation may place or break at this cell. See {@link #calcContext()}.
     *
     *  <p>The raw bounding box, deliberately, not {@code inSchematic}: a mask says a cell does not matter to the
     *  SCHEMATIC, while N1 is about the space the plan reasons over — every stance, ray path and scaffold cell the
     *  oracle proved lives in the box whether or not the mask cares about it. */
    private boolean pathfinderMayMutateAt(int x, int y, int z) {
        ISchematic current = this.schematic;
        Vec3i at = this.origin;
        if (current == null || at == null) {
            return true;
        }
        return pathMutationAllowed(at, current.widthX(), current.heightY(), current.lengthZ(),
                this.protectedPathCells::contains, x, y, z);
    }

    /** Pure N1 predicate, pinned headlessly: the whole schematic box and all future plan-critical cells are immutable
     *  to navigation. Builder actions, not path movements, own every mutation there. */
    static boolean pathMutationAllowed(Vec3i origin, int sizeX, int sizeY, int sizeZ, LongPredicate protectedCell,
                                       int x, int y, int z) {
        int lx = x - origin.getX();
        int ly = y - origin.getY();
        int lz = z - origin.getZ();
        boolean inside = lx >= 0 && lx < sizeX && ly >= 0 && ly < sizeY && lz >= 0 && lz < sizeZ;
        return !inside && (protectedCell == null || !protectedCell.test(BlockPos.asLong(x, y, z)));
    }

    /** Rebuild N1's outside-the-box protection deterministically from the entire frozen order. */
    private void protectPlanCells() {
        this.protectedPathCells.clear();
        if (this.plan == null) {
            return;
        }
        for (int index = 0; index < this.plan.size(); index++) {
            BuildAction action = this.plan.action(index);
            protect(action.cell());
            BlockPos stance = pathStanceOf(action);
            protect(stance);
            protect(stance == null ? null : stance.below());
            PlacementSolution solution = solutionOf(action);
            if (solution != null) {
                protect(solution.against());
            }
            protectAimRay(pathApproachOf(action), pathAimPointOf(action));
        }
    }

    private void protect(BlockPos cell) {
        if (cell != null) {
            this.protectedPathCells.add(BlockPos.asLong(cell.getX(), cell.getY(), cell.getZ()));
        }
    }

    /** Quarter-block sampling is finer than a voxel edge and deterministically covers every cell an action ray uses. */
    private void protectAimRay(Vec3 approach, Vec3 aim) {
        if (approach == null || aim == null) {
            return;
        }
        Vec3 eye = PlayerPose.CROUCHED.eyeAt(approach);
        int steps = Math.max(1, (int) Math.ceil(eye.distanceTo(aim) * 4.0D));
        for (int step = 0; step <= steps; step++) {
            double fraction = (double) step / (double) steps;
            protect(BlockPos.containing(eye.lerp(aim, fraction)));
        }
    }

    private static BlockPos pathStanceOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().stance();
            case BuildAction.JumpPlace jump -> jump.solution().stance();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution().stance();
            case BuildAction.Break broken -> broken.stance();
            case BuildAction.RemoveScaffold removed -> removed.stance();
            case BuildAction.Interact interact -> interact.stance();
            case BuildAction.FillFluid fluid -> fluid.stance();
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    private static Vec3 pathApproachOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().approach();
            case BuildAction.JumpPlace jump -> jump.solution().approach();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution().approach();
            case BuildAction.Break broken -> broken.approach();
            case BuildAction.RemoveScaffold removed -> removed.approach();
            case BuildAction.Interact interact -> interact.approach();
            case BuildAction.FillFluid fluid -> fluid.approach();
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    private static Vec3 pathAimPointOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().aimPoint();
            case BuildAction.JumpPlace jump -> jump.solution().aimPoint();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution().aimPoint();
            case BuildAction.Break broken -> broken.aimPoint();
            case BuildAction.RemoveScaffold removed -> removed.aimPoint();
            case BuildAction.Interact interact -> interact.aimPoint();
            case BuildAction.FillFluid fluid -> fluid.aimPoint();
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    /** Book a cell as done, so rule N1 stops guarding it. Called from {@link CountingListener#onCellConfirmed} — that
     *  is, on the server's answer plus a hold, never on the client's optimistic prediction. Real schematic cells only;
     *  a helper block's cell is deliberately not booked, because the plan takes the helper away again and the freed
     *  cell would then be the one hole inside the bounding box that rule N1 exists to close. */
    void markCompleted(BlockPos cell, BlockState expected) {
        long key = BlockPos.asLong(cell.getX(), cell.getY(), cell.getZ());
        this.completedCells.add(key);
        this.completedExpected.put(key, expected);
    }

    /**
     * Consume every authoritative world change, including packets for cells whose expectation settled long ago.
     *
     * <p>A completed placement may legitimately move once more: repeaters, comparators and other interaction-planned
     * blocks first hold {@link PlacementSolution#predicted()} and are then changed to the schematic's final state by
     * ACT. Such a final state advances the tracked expectation. Every other change re-opens the cell exactly once,
     * books a revert in the progress journal and schedules an immediate re-plan.
     */
    private void observeServerBlockChange(BlockPos cell, BlockState observed) {
        long key = BlockPos.asLong(cell.getX(), cell.getY(), cell.getZ());
        BlockState expected = this.completedExpected.get(key);
        if (expected == null || !this.completedCells.contains(key)) {
            this.journal.observeWorldChange(cell);
            return;
        }

        BlockState desired = this.view == null ? null : this.view.desired(cell);
        boolean reachedFinal = desired != null && this.settings != null
                && this.settings.valid(observed, desired, false);
        if (reachedFinal) {
            this.completedExpected.put(key, observed);
            this.journal.observeWorldChange(cell);
            return;
        }
        if (observed.equals(expected)) {
            this.journal.observeWorldChange(cell);
            return;
        }

        // Removal before recording makes duplicate update packets idempotent: only the first can add a revert and a
        // re-plan request.
        this.completedCells.remove(key);
        this.completedExpected.remove(key);
        this.journal.recordServerInvalidation(cell, this.buildTick);
        this.serverInvalidations.add(new ServerInvalidation(cell.immutable(), expected, observed));
    }

    /** Completed evidence cannot survive its chunk: a cached/remembered state is not a live server proof. */
    private void observeServerChunkUnload(int chunkX, int chunkZ) {
        int invalidated = 0;
        BlockPos first = null;
        for (long key : new ArrayList<>(this.completedExpected.keySet())) {
            BlockPos cell = BlockPos.of(key);
            if ((cell.getX() >> 4) != chunkX || (cell.getZ() >> 4) != chunkZ) {
                continue;
            }
            if (first == null) {
                first = cell;
            }
            this.completedExpected.remove(key);
            this.completedCells.remove(key);
            invalidated++;
        }
        if (invalidated > 0) {
            this.unloadedCompletedProof = "chunk " + chunkX + "," + chunkZ + " unloaded and invalidated "
                    + invalidated + " completed-cell proof" + (invalidated == 1 ? "" : "s")
                    + ", first at " + BuildAction.describePos(first);
        }
    }

    private final class PlannedCalculationContext extends CalculationContext {

        private PlannedCalculationContext() {
            // Threaded copy of the world, like every other pathing context: the path calculation does not run on the
            // client thread and a shared BlockStateInterface is not safe to hand it.
            super(PlannedBuilderProcess.this.princeps, true);
        }

        @Override
        public boolean mayUsePathingBarriers() {
            // The action plan owns every intended door/gate state change. Letting navigation toggle one behind its
            // back invalidates the predicted world and every still-frozen placement proof that depends on it.
            return false;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (!pathfinderMayMutateAt(x, y, z)) {
                return ActionCosts.COST_INF;
            }
            return super.costOfPlacingAt(x, y, z, current);
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if (!pathfinderMayMutateAt(x, y, z)) {
                return ActionCosts.COST_INF;
            }
            return super.breakCostMultiplierAt(x, y, z, current);
        }
    }

    // ------------------------------------------------------------------------------------------ IPrincepsProcess

    /** {@code schematic != null}, across planning as well as execution — the bench stops with INACTIVE the moment it
     *  reads false, so this must stay true while the planner runs and not only while blocks are going down. Gated on
     *  {@link #selected()} so the engine the session did not choose is unconditionally inactive. */
    @Override
    public boolean isActive() {
        return this.schematic != null && selected();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        this.buildTick++;
        trackAimMovement();
        // Forced inputs are sticky and nothing clears them for you. Clear first, then re-assert everything that
        // should be held this tick — the discipline the V2 builder keeps at the top of its own onTick.
        // Sampled HERE, immediately before the clear and therefore at the only moment in the frame where the record
        // is complete: everything the previous tick asserted is still held, including the pathing behaviour's own
        // inputs, which are set AFTER this process returns its command and would be missed by any sampling at the end
        // of it. One tick late, and labelled keys-1 to say so.
        sampleHeldKeys();
        princeps.getInputOverrideHandler().clearAllKeys();
        // The owner's speed/realism dial, re-asserted every tick because it expires every tick. Asserted here rather
        // than at the five updateTarget call sites so that everything the build turns its head for -- placements,
        // breaks and the delegated sign and bucket actions alike -- turns at one speed.
        // Only while the BUILD owns the head. During TRAVEL the head belongs to the path -- the steering turns the
        // body by turning the head, and MOVE_FORWARD goes wherever the head points -- so reshaping the turn curve
        // there means reshaping the pathing's own steering with a number chosen for placing blocks. The dial was
        // documented as the builder's from the day it was written; it was simply asserted every tick regardless of
        // whether the builder was looking at anything.
        if (this.state != State.TRAVEL) {
            LookBehavior.requestAimCurveTurnTicks(
                    Math.min(5.0, Math.max(1.0, Princeps.settings().builderTurnTicks.value)));
        }
        traceTick();

        if (this.plan == null) {
            // Defensive, and it should be unreachable: every path out of runDryRun that leaves plan null has already
            // called blockedBy, which clears the schematic, and isActive() is false from that moment. Reaching here
            // means build() never ran at all — say so rather than improvise, because a builder that walks off without
            // a proven order is the engine being replaced.
            return standDown("no plan — build() never produced one");
        }
        if (this.paused) {
            // The user-facing control, not the blocked channel. Stand still and keep the plan: V2 answers a pause the
            // same way, and it must NOT go through standDown, which would end the build outright.
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return executePlannedAction(calcFailed, isSafeToCancel);
    }

    /**
     * The executor's tick.
     *
     * <p>Four lines of substance and that is the point: the process owns the plan, the counters and the stand-down;
     * the executor owns look, movement, hotbar and the click. Every decision that could be made here is made there,
     * and the only thing this method does with an {@link CellExecutor.Outcome} is translate its status into the
     * process's vocabulary.
     *
     * <p>Order within the tick matters twice. The acknowledgement is advanced FIRST, so the automaton's
     * {@code CONFIRM} reads a verdict from this tick rather than the previous one, and so its latency clock keeps
     * running across the TRAVEL legs that never poll it. The stall check comes second, before the automaton is given
     * another cell to spend clicks on.
     *
     * <p>Every pathing command that carries a goal is a {@code PathingCommandContext} over {@link #calcContext()} —
     * the executor builds them that way. A plain {@code PathingCommand} would fall back to the generic calculation
     * context, which prices a throwaway placement inside the blueprint at {@code blockPlacementPenalty} instead of
     * {@code COST_INF}, and rule N1 would quietly stop holding.
     */
    private PathingCommand executePlannedAction(boolean calcFailed, boolean isSafeToCancel) {
        if (this.executor == null) {
            // Unreachable by construction — runDryRun creates it on the same line that sets the plan — but a null here
            // would be an NPE inside a process tick, thirty frames from anything that names the build.
            return standDown("the plan is proven but no executor was created for it");
        }
        if (this.unloadedCompletedProof != null) {
            return standDown(this.unloadedCompletedProof);
        }
        if (!this.serverInvalidations.isEmpty()) {
            return replanAfterServerInvalidation();
        }
        if (this.ack != null) {
            // Pushed in rather than read inside ServerAck, which stays settings-free so it can be tested headless.
            this.ack.setHoldFloorTicks((int) Math.round(Princeps.settings().builderAckHoldTicks.value));
            this.ack.tick(this.buildTick);
        }
        if (this.journal.netProgressStalled(this.buildTick)) {
            // Placements are being undone as fast as they are made. Not a timeout: it needs a full window, at least one
            // server-confirmed revert inside it, and net progress at or below zero.
            return standDown(this.journal.stallReport(this.buildTick));
        }

        CellExecutor.Outcome outcome = this.executor.onTick(calcFailed, isSafeToCancel);
        // Mirrored so the NEXT tick's trace line and every public getter describe where the executor actually is. Done
        // after the tick and not before, because before is the previous tick's answer.
        this.state = this.executor.processState();
        this.actionIndex = this.executor.actionIndex();

        return switch (outcome.status()) {
            case RUNNING -> outcome.command();
            case FINISHED -> finished();
            case DIVERGED -> diverged(outcome);
            case BLOCKED -> standDown(outcome.reason());
        };
    }

    /**
     * Re-open work a late server packet changed after its acknowledgement hold had already completed.
     *
     * <p>This is world divergence, not a refusal of the executor's current placement triple, so it deliberately
     * bypasses {@link #recordServerRefusal(BuildAction)}. The packet callback already booked the revert exactly once.
     */
    private PathingCommand replanAfterServerInvalidation() {
        ServerInvalidation first = this.serverInvalidations.get(0);
        int count = this.serverInvalidations.size();
        this.serverInvalidations.clear();
        this.unloadedCompletedProof = null;
        this.divergences++;
        if (this.ack != null) {
            this.ack.clearExpectation();
        }
        String reason = "late server change re-opened " + BuildAction.describePos(first.cell()) + ": "
                + BuildAction.describeState(first.expected()) + " -> " + BuildAction.describeState(first.observed())
                + (count == 1 ? "" : " (" + count + " completed cells changed)");
        logMechanic("v3: " + reason);
        BuildTrace.cell(this.buildTick, "LATE_INVALIDATION", first.cell().getX(), first.cell().getY(),
                first.cell().getZ(), "expected=" + BuildAction.describeState(first.expected())
                        + " observed=" + BuildAction.describeState(first.observed()) + " count=" + count);
        replan();
        if (this.plan == null) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Every action confirmed. Ends the build the same way a stand-down does — inactive, trace flushed — but leaves
     * {@link #blockedReason} null, which is the only difference the bench can see between finishing and giving up.
     */
    private PathingCommand finished() {
        if (this.moreLayersPending) {
            return nextLayer();
        }
        String summary = "v3: build '" + this.name + "' complete — " + this.plan.size() + " actions, "
                + this.landedRight + " cells placed, " + this.blocksBroken + " broken, " + this.divergences
                + " divergences, " + this.replans + " re-plans, fidelity "
                + String.format(java.util.Locale.ROOT, "%.1f", planFidelity()) + "%";
        logMechanic(summary);
        logDirect(summary);
        BlockPos feet = ctx.playerFeet();
        BuildTrace.cell(this.buildTick, "FINISHED", feet.getX(), feet.getY(), feet.getZ(),
                "actions=" + this.plan.size() + " placed=" + this.landedRight + " replans=" + this.replans);
        this.state = State.DONE;
        standDownQuietly();
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    /**
     * The layer in hand is built. Prove the next one against the world that now actually holds it.
     *
     * <p>Deliberately NOT {@link #replan()}, and the difference is the whole point: a re-plan is what happens when the
     * world contradicted the plan, it counts against fidelity, and a bench run that needed one is graded DIVERGED.
     * This contradicted nothing. Every action of the finished layer landed exactly as proved; there simply were more
     * layers, and they were left for here on purpose. Counting it as a divergence would make the honest thing look
     * like a failure and would hide a real one behind it.
     *
     * <p>The executor is torn down the same way, because the next plan is proved under its own oracle and one owner
     * per state means the old automaton must not survive its plan.
     */
    private PathingCommand nextLayer() {
        BlockPos feet = ctx.playerFeet();
        logMechanic("v3: layer built — " + this.landedRight + " cells placed so far; planning the next from "
                + BuildAction.describePos(feet));
        BuildTrace.cell(this.buildTick, "NEXT_LAYER", feet.getX(), feet.getY(), feet.getZ(),
                "placed=" + this.landedRight + " actions=" + this.plan.size());
        this.plan = null;
        if (this.executor != null) {
            this.executor.reset();
        }
        this.executor = null;
        this.actionIndex = 0;
        this.state = State.SELECT;
        runDryRun();
        // Whatever the dry run decided it has already said, in chat and in the report file: a fresh plan to execute,
        // or a named blocker. Either way this tick is over.
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    /**
     * The live world contradicted the plan. Re-plan from the world as it now is, immediately — no waiting, no backoff,
     * no watchdog, and no separate emergency path (E-D).
     *
     * <p>Two things happen before the re-plan. Anything the SERVER refused is written into the journal, so the next
     * plan cannot propose the same triple and three structurally different refusals retire the cell by name. And the
     * pending expectation is dropped, because a verdict that arrives for a cell nobody is building any more would be
     * read against whatever the next plan is doing.
     */
    private PathingCommand diverged(CellExecutor.Outcome outcome) {
        BuildAction action = this.executor.currentAction();
        recordServerRefusal(action);
        if (this.ack != null) {
            this.ack.clearExpectation();
        }

        String reason = outcome.reason() == null ? "unstated" : outcome.reason();
        if (reason.equals(this.lastDivergence)) {
            this.identicalDivergences++;
        } else {
            this.lastDivergence = reason;
            this.identicalDivergences = 1;
        }
        if (this.identicalDivergences >= IDENTICAL_DIVERGENCES_BEFORE_BLOCKED) {
            // A freshly derived plan produced the same sentence three times. Re-planning is a fixed point here and one
            // more lap would produce it a fourth time; say so with the sentence itself rather than looping in silence.
            return standDown("re-planning cannot get past this: " + reason + " (unchanged across "
                    + this.identicalDivergences + " plans)");
        }

        replan();
        if (this.plan == null) {
            // runDryRun has already stood down by name — INCOMPLETE, or the planner threw. Nothing to add.
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Write a refusal into the journal, but only one the SERVER actually stated.
     *
     * <p>{@link EvidenceJournal.Reason} means "what the executor verified"; nothing in it is inferred. A geometric
     * divergence — a neighbour that vanished, a stance that stopped being standable — is the world moving, not evidence
     * against a way of placing the cell, and banning a triple for it would retire perfectly good geometry three
     * unlucky mobs later.
     *
     * <p>The guard on {@link ServerAck#lastCell()} is what keeps a settled verdict from an earlier cell out of this
     * cell's record.
     */
    private void recordServerRefusal(BuildAction action) {
        if (this.ack == null || action == null) {
            return;
        }
        BlockPos cell = action.cell();
        BlockPos last = this.ack.lastCell();
        if (cell == null || last == null || !last.equals(cell)) {
            return;
        }
        ServerAck.Outcome verdict = this.ack.outcome();
        EvidenceJournal.Reason reason = EvidenceJournal.reasonFor(verdict);
        if (reason == null) {
            return;
        }
        if (verdict == ServerAck.Outcome.REVERTED) {
            this.journal.recordReverted(this.buildTick);
        }
        PlacementSolution solution = solutionOf(action);
        EvidenceJournal.CellStatus status = this.journal.reject(cell,
                solution == null ? null : solution.stance(),
                solution == null ? null : solution.face(), reason, this.buildTick);
        logMechanic("v3: journal " + reason + " at " + BuildAction.describePos(cell) + " -> " + status);
        BuildTrace.cell(this.buildTick, "REFUSED", cell.getX(), cell.getY(), cell.getZ(),
                "reason=" + reason + " status=" + status);
    }

    /** The geometry a rejection is recorded against, or null for an action that has none. */
    private static PlacementSolution solutionOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution();
            case BuildAction.PlaceScaffold scaffold -> scaffold.solution();
            default -> null;
        };
    }

    /**
     * {@link ServerAck} in the executor's vocabulary.
     *
     * <p>Reads the outcome rather than advancing it — {@link #executePlannedAction} already ticked it this tick, so
     * this is the verdict as of now and asking twice would consume a hold tick the automaton has not counted.
     *
     * <p>{@link ServerAck.Outcome#TIMED_OUT} maps to UNKNOWN and not to REVERTED, deliberately: silence is evidence,
     * not a rejection, and the executor's own confirmation window turns it into a divergence one tick later, at which
     * point {@link #recordServerRefusal} files it as {@link EvidenceJournal.Reason#NO_CONFIRMATION}. Mapping it to
     * REVERTED here would say the server took the block away, which it never said.
     */
    private final class ExecutorAcknowledgements implements CellExecutor.Acknowledgements {

        private BlockPos cell;
        private long executorArmedTick = Long.MIN_VALUE;
        private boolean armed;

        @Override
        public boolean available() {
            return PlannedBuilderProcess.this.ack != null && PlannedBuilderProcess.this.ack.isActive();
        }

        @Override
        public boolean expectPlacement(BlockPos cell, BlockPos against, Direction face, BlockState expectedAfter,
                                       long armedTick) {
            ServerAck server = PlannedBuilderProcess.this.ack;
            if (server == null || !server.isActive()) {
                this.armed = false;
                return false;
            }
            if (!server.expectPlacement(cell, against, face, expectedAfter,
                    PlannedBuilderProcess.this.buildTick)) {
                this.armed = false;
                return false;
            }
            this.cell = cell.immutable();
            this.executorArmedTick = armedTick;
            this.armed = true;
            return true;
        }

        @Override
        public boolean expectBreak(BlockPos cell, BlockState expectedAfter, long armedTick) {
            ServerAck server = PlannedBuilderProcess.this.ack;
            if (server == null || !server.isActive()) {
                this.armed = false;
                return false;
            }
            if (!server.expectBreak(cell, expectedAfter, PlannedBuilderProcess.this.buildTick)) {
                this.armed = false;
                return false;
            }
            this.cell = cell.immutable();
            this.executorArmedTick = armedTick;
            this.armed = true;
            return true;
        }

        @Override
        public boolean expectSign(BlockPos cell, boolean frontSide, java.util.List<String> exactLines,
                                  long armedTick) {
            ServerAck server = PlannedBuilderProcess.this.ack;
            if (server == null || !server.isActive()) {
                this.armed = false;
                return false;
            }
            if (!server.expectSign(cell, frontSide, exactLines, PlannedBuilderProcess.this.buildTick)) {
                this.armed = false;
                return false;
            }
            this.cell = cell.immutable();
            this.executorArmedTick = armedTick;
            this.armed = true;
            return true;
        }

        @Override
        public boolean watching() {
            ServerAck server = PlannedBuilderProcess.this.ack;
            return server != null && server.isActive() && this.armed && server.holding();
        }

        @Override
        public Ack poll(BlockPos cell, long armedTick) {
            ServerAck server = PlannedBuilderProcess.this.ack;
            if (server == null || !server.isActive()) {
                return Ack.UNAVAILABLE;
            }
            if (!this.armed || this.cell == null || !this.cell.equals(cell)
                    || this.executorArmedTick != armedTick) {
                return Ack.CANCELLED;
            }
            BlockPos last = server.lastCell();
            if (last == null || !last.equals(cell)) {
                return Ack.CANCELLED;
            }
            return switch (server.outcome()) {
                case CONFIRMED -> Ack.CONFIRMED;
                case REVERTED, WRONG_STATE -> Ack.REVERTED;
                case TIMED_OUT -> Ack.TIMED_OUT;
                case NOT_SENT -> Ack.NOT_SENT;
                case UNLOADED -> Ack.UNLOADED;
                case IDLE -> Ack.CANCELLED;
                case PENDING -> Ack.UNKNOWN;
            };
        }

        @Override
        public void clear() {
            this.cell = null;
            this.executorArmedTick = Long.MIN_VALUE;
            this.armed = false;
            if (PlannedBuilderProcess.this.ack != null) {
                PlannedBuilderProcess.this.ack.clearExpectation();
            }
        }
    }

    /**
     * Everything the executor does, counted as it happens.
     *
     * <p>The executor holds no counters of its own — it reports events and the process decides what they mean — which
     * is what lets the same automaton be driven by the bench, by a test harness or by this class without three copies
     * of the arithmetic.
     */
    private final class CountingListener implements CellExecutor.Listener {

        @Override
        public void onClickSent(BuildAction action) {
            PlannedBuilderProcess.this.clicksSent++;
        }

        @Override
        public void onCellConfirmed(BuildAction action, boolean ideal) {
            PlannedBuilderProcess.this.landedRight++;
            PlannedBuilderProcess.this.planFidelityDenominator++;
            if (ideal) {
                PlannedBuilderProcess.this.planFidelityNumerator++;
            }
            PlannedBuilderProcess.this.journal.recordPlaced(PlannedBuilderProcess.this.buildTick);
            // A confirmed cell is progress, so the fixed-point detector starts again from nothing. Without this it
            // would count three identical divergences separated by a thousand good cells as a stuck build, and its
            // stop reason — "unchanged across N plans" — would be a sentence that was not true.
            PlannedBuilderProcess.this.lastDivergence = null;
            PlannedBuilderProcess.this.identicalDivergences = 0;
            // Only a real schematic cell is booked as complete. A helper block also holds a block right now, but the
            // plan takes it away again before the next layer — and rule N1 reads this set to decide where the
            // PATHFINDER may put a throwaway, so booking a scaffold cell here would open the one hole inside the
            // bounding box that the rule exists to close, the moment the helper comes down.
            if (action instanceof BuildAction.Place place) {
                markCompleted(place.cell(), place.solution().predicted());
            }
        }

        @Override
        public void onLandedWrong(BuildAction action, BlockState found) {
            PlannedBuilderProcess.this.landedWrong++;
        }

        @Override
        public void onBlockBroken(BlockPos cell) {
            PlannedBuilderProcess.this.blocksBroken++;
        }

        @Override
        public void onWalkStarted(BuildAction action) {
            PlannedBuilderProcess.this.walksStarted++;
        }

        @Override
        public void onWalkArrived(BuildAction action) {
            PlannedBuilderProcess.this.walksEndedInPlacement++;
        }

        @Override
        public void onDivergence(BuildAction action, String reason) {
            PlannedBuilderProcess.this.divergences++;
        }
    }

    /**
     * The honest "I cannot proceed" channel, and the only one this engine has.
     *
     * <p>It does NOT pause. The bench scores {@code isPaused() == true} as the PAUSED verdict, which is a failure, so
     * pausing to mean "blocked" reports a failure for the wrong reason and hides the actual one. Instead the reason
     * is named three times — chat, {@code latest.log} via {@code logMechanic}, and the trace as a {@code BLOCKED}
     * event on the bot's own cell so it sorts with everything else that tick — the state goes to
     * {@link State#BLOCKED}, and the process stops being active. The bench then reads INACTIVE, which is honest: the
     * builder really is no longer building, and the reason is one grep away.
     *
     * <p>Returns DEFER rather than a goal command: standing down must not claim pathing control on the way out, or
     * every other process gets {@code onLostControl} for a tick because this one gave up.
     */
    private PathingCommand standDown(String reason) {
        blockedBy(reason);
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    /**
     * The stand-down itself, without the pathing command — {@link #build} stands down too, and it has no
     * {@link PathingCommand} to return.
     *
     * <p>One body rather than two (E-C): the naming, the trace event, the {@code schematic = null} and the trace flush
     * must happen together or a stop is visible through one channel and not the others, and "the bench said INACTIVE
     * but nothing says why" is the exact failure this whole channel exists to prevent.
     */
    private void blockedBy(String reason) {
        this.blockedReason = reason;
        this.state = State.BLOCKED;
        BlockPos feet = ctx.playerFeet();
        BuildTrace.cell(this.buildTick, "BLOCKED", feet.getX(), feet.getY(), feet.getZ(), "reason=" + reason);
        logMechanic("v3: BLOCKED — " + reason);
        logDirect("Builder V3 stopped: " + reason);
        standDownQuietly();
    }

    /**
     * Leave the world alone and stop being active, without saying why.
     *
     * <p>Shared by {@link #blockedBy} and {@link #finished()}, because ending well and ending badly must release
     * exactly the same things — a packet listener still armed after a finished build settles the NEXT build's first
     * click against this one's expectation, and an unflushed {@code BuildTrace} loses up to 512 lines whichever way the
     * run ended. What the two do differently is upstream of here: one sets {@link #blockedReason} and one does not,
     * and that field is the only thing that tells a finished run from an abandoned one.
     */
    private void standDownQuietly() {
        // isActive() reads this; clearing it is what makes the stand-down visible to the control manager and the
        // bench.
        this.schematic = null;
        if (this.ack != null) {
            // Disarmed, not deregistered: the bus cannot forget a listener, so the instance stays and the next build
            // re-arms it.
            this.ack.deactivate();
        }
        if (this.executor != null) {
            this.executor.reset();
        }
        // The trace is closed HERE and not left to onLostControl, because onLostControl is never going to come: the
        // control manager only calls it on processes still in its active list, and this one just left it. An
        // unflushed BuildTrace loses up to 512 lines, which are exactly the lines explaining why the build stopped.
        if (this.tracing) {
            BuildTrace.stop();
            this.tracing = false;
        }
    }

    @Override
    public void onLostControl() {
        if (this.tracing) {
            BuildTrace.stop();
            this.tracing = false;
        }
        // isActive() reads schematic, so this field in particular MUST be cleared here: a process that stays active
        // after being cancelled makes cancelEverything throw on world exit.
        this.schematic = null;
        this.name = null;
        this.origin = null;
        this.settings = null;
        this.world = null;
        this.view = null;
        this.plan = null;
        this.report = null;
        this.runId = null;
        this.actionIndex = 0;
        this.state = null;
        this.paused = false;
        this.completedCells.clear();
        this.protectedPathCells.clear();
        this.completedExpected.clear();
        this.serverInvalidations.clear();
        this.unloadedCompletedProof = null;
        // Trap 6: onLostControl also runs on world exit, and everything it drops must leave the object safe to query.
        // The executor's reset() is written for exactly that; the acknowledgement's deactivate() matters more, because
        // an armed listener holds a BlockPos in a world that no longer exists and the next build's first tick would
        // settle against it.
        if (this.executor != null) {
            this.executor.reset();
        }
        this.executor = null;
        this.oracle = null;
        if (this.ack != null) {
            this.ack.deactivate();
        }
        this.prevAimValid = false;
        this.aimMovementDegrees = 0F;
        this.lastTracedPath = null;
        // The per-run counters and blockedReason survive: this method runs when a build FINISHES, before anything
        // reads the verdict, and a number cleared before it is read is worse than no number.
    }

    @Override
    public String displayName0() {
        return this.paused ? "Builder V3 Paused" : "Building " + this.name;
    }

    // ------------------------------------------------------------------------------------------ instrumentation

    /** How far the visible aim moved since last tick. Measured once per tick, before anything reads it. */
    private void trackAimMovement() {
        Rotation now = ctx.playerRotations();
        if (this.prevAimValid) {
            float dy = Math.abs(Mth.wrapDegrees(now.getYaw() - this.prevAimYaw));
            float dp = Math.abs(now.getPitch() - this.prevAimPitch);
            this.aimMovementDegrees = Math.max(dy, dp);
        }
        this.prevAimYaw = now.getYaw();
        this.prevAimPitch = now.getPitch();
        this.prevAimValid = true;
    }

    /**
     * The one line per tick. Emitted before anything decides what to do, so the record of a tick exists even when the
     * tick ends in an exception.
     *
     * <p>{@code work} is actions left in the frozen order, {@code retired} is structurally 0 — V3 never sets a cell
     * aside — and {@code act} is the cell automaton's state, which is the whole of this executor's decision-making.
     */
    private void traceTick() {
        if (!BuildTrace.isActive()) {
            return;
        }
        Rotation rot = ctx.playerRotations();
        Vec3 look = ctx.player().getViewVector(1.0F);
        Direction nearest = Direction.getApproximateNearest(look.x, look.y, look.z);
        Vec3 vel = ctx.player().getDeltaMovement();
        ItemStack held = ctx.player().getInventory().getSelectedItem();
        String hand = held.isEmpty() ? "-" : BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
        String path;
        if (princeps.getPathingBehavior().getCurrent() != null) {
            path = "len" + princeps.getPathingBehavior().getCurrent().getPath().length();
        } else if (princeps.getPathingBehavior().getGoal() != null) {
            path = "goal-no-path";
        } else {
            path = "-";
        }
        BuildAction action = currentAction();
        BuildTrace.tick(this.buildTick, ctx.player().getX(), ctx.player().getY(), ctx.player().getZ(),
                ctx.player().getEyeY(), ctx.player().isCrouching(), ctx.player().onGround(),
                vel.x, vel.y, vel.z,
                rot.getYaw(), rot.getPitch(), nearest.getName(), this.aimMovementDegrees,
                hand, ctx.player().getInventory().getSelectedSlot(),
                action == null ? -1 : action.layer(),
                this.plan == null ? -1 : this.plan.size() - this.actionIndex,
                0, path,
                this.paused ? "paused" : this.state == null ? "idle" : this.state.name().toLowerCase(java.util.Locale.ROOT),
                traceTarget(action), traceDetail());
    }

    /**
     * The forensic tail of a trace line: what the body was TOLD to do, and what the engine was thinking.
     *
     * <p>Written for every tick and not only for failures, because the questions that have cost this project whole
     * runs were never "what went wrong" but "what was it doing" -- a body that holds a live path, a stable goal and
     * does not move produces no error at all, and there was nothing in the record to read. Keys close that: a walk
     * that presses nothing and a walk that presses forward into a wall look identical from the outside and are
     * different defects.
     *
     * <p>The note is the engine's own sentence about why it is waiting, which is the closest thing it has to a
     * thought. It is already written for every wait; it simply never reached the trace.
     */
    private String traceDetail() {
        // The PREVIOUS tick's keys, not this tick's. traceTick runs immediately after clearAllKeys(), so reading
        // the handler here can only ever answer "none" -- by construction, before any controller has re-asserted
        // anything. That made the field look like proof that the body was told nothing, which it was not: it was a
        // measurement taken at the one instant where the answer is fixed. Sampled at the END of the tick instead,
        // when every controller has had its say, and reported one tick late with the label to match.
        String keys = this.keysLastTick;
        String note = this.executor == null ? null : this.executor.waitNote();
        StringBuilder out = new StringBuilder(160);
        out.append("keys-1=").append(keys == null || keys.isEmpty() ? "-" : keys)
                .append(" held=").append(ctx.player().getMainHandItem().getCount())
                .append(" why=").append(note == null || note.isBlank() ? "-" : note.replace(' ', '_'));

        // The pathing layer, which is where the last stall actually lived and where nothing was written down. A body
        // holding a finished path, told to walk it, pressing NOTHING is invisible from above: every field the
        // executor logs looks healthy. These are the ones that separate "no path" from "on a path it cannot follow".
        princeps.api.pathing.path.IPathExecutor current = princeps.getPathingBehavior().getCurrent();
        if (current == null) {
            if (this.lastTracedPath != null) {
                BuildTrace.path(this.buildTick, "none");
                this.lastTracedPath = null;
            }
            out.append(" exec=- move=- node=- nodedist=- topos=- state=nopath");
        } else {
            var path = current.getPath();
            traceRoute(current, path);
            int at = current.getPosition();
            int len = path == null ? -1 : path.length();
            out.append(" exec=").append(at).append('/').append(len);
            if (path != null && at >= 0 && at < path.movements().size()) {
                out.append(" move=").append(path.movements().get(at).getClass().getSimpleName());
            } else {
                out.append(" move=-");
            }
            if (path != null && at >= 0 && at < len) {
                var node = path.positions().get(at);
                // Where the executor thinks it is versus where the body is. A body that has drifted off its own node
                // is exactly the shape that produces a finished path and no inputs, and it cannot be seen from the
                // path length alone -- which is all the trace used to carry.
                double dx = ctx.player().getX() - (node.getX() + 0.5D);
                double dz = ctx.player().getZ() - (node.getZ() + 0.5D);
                out.append(" node=").append(node.getX()).append(',').append(node.getY()).append(',').append(node.getZ())
                        .append(" nodedist=").append(String.format(java.util.Locale.ROOT, "%.3f", Math.hypot(dx, dz)))
                        .append(" nodedy=").append(node.getY() - Mth.floor(ctx.player().getY()));
                // The node the executor is HEADING FOR, which is the one the body should be moving toward and the
                // one the previous version of this line silently was not: getPosition() is where the executor thinks
                // the body IS. Reading the current node as if it were the next is how "he presses forward the wrong
                // way" and "he has drifted off his own node" became indistinguishable.
                if (at + 1 < len) {
                    var ahead = path.positions().get(at + 1);
                    double ndx = (ahead.getX() + 0.5D) - ctx.player().getX();
                    double ndz = (ahead.getZ() + 0.5D) - ctx.player().getZ();
                    out.append(" next=").append(ahead.getX()).append(',').append(ahead.getY()).append(',')
                            .append(ahead.getZ())
                            .append(" nextstate=").append(traceState(ctx.world().getBlockState(ahead)))
                            .append(" nextdist=").append(String.format(java.util.Locale.ROOT, "%.3f",
                                    Math.hypot(ndx, ndz)))
                            // Where the body would have to LOOK to walk there, against where it is looking. Forward
                            // goes where the head points, so this difference is the whole question.
                            .append(" nextyaw=").append(String.format(java.util.Locale.ROOT, "%.1f",
                                    Math.toDegrees(Math.atan2(-ndx, ndz))))
                            .append(" yaw=").append(String.format(java.util.Locale.ROOT, "%.1f",
                                    Mth.wrapDegrees(ctx.player().getYRot())));
                } else {
                    out.append(" next=- nextdist=- nextyaw=- yaw=-");
                }
            } else {
                out.append(" node=- nodedist=- nodedy=-");
            }
            // The interface carries only the path and the index; everything that says WHY it is not moving lives on
            // the concrete executor, so it is read when present and named as absent when not.
            if (current instanceof princeps.pathing.path.PathExecutor concrete) {
                out.append(" state=").append(concrete.failed() ? "failed"
                                : concrete.finished() ? "finished" : "running")
                        .append(" sprint=").append(concrete.isSprinting() ? 1 : 0)
                        .append(" break=").append(concrete.toBreak().size())
                        .append(" place=").append(concrete.toPlace().size())
                        .append(" walkinto=").append(concrete.toWalkInto().size());
            } else {
                out.append(" state=? sprint=? break=? place=? walkinto=?");
            }
        }
        // What the body is standing on and in, so a stall on a shape (soul sand, ice, a slab lip) is legible without
        // anyone having to go and stand there in game.
        BlockPos feet = ctx.playerFeet();
        Direction heading = Direction.fromYRot(ctx.player().getYRot());
        BlockPos ahead = feet.relative(heading);
        out.append(" feet=").append(feet.getX()).append(',').append(feet.getY()).append(',').append(feet.getZ())
                .append(" on=").append(traceState(ctx.world().getBlockState(feet.below())))
                .append(" in=").append(traceState(ctx.world().getBlockState(feet)))
                .append(" aheadpos=").append(ahead.getX()).append(',').append(ahead.getY()).append(',')
                .append(ahead.getZ())
                .append(" ahead=").append(traceState(ctx.world().getBlockState(ahead)))
                .append(" hcol=").append(ctx.player().horizontalCollision ? 1 : 0)
                .append(" vcol=").append(ctx.player().verticalCollision ? 1 : 0)
                .append(" slot=").append(ctx.player().getInventory().getSelectedSlot())
                .append(" paused=").append(this.paused ? 1 : 0);
        return out.toString();
    }

    /** Write every node, its live state and the movement joining it to the next node once for each new route. */
    private void traceRoute(princeps.api.pathing.path.IPathExecutor current,
                            princeps.api.pathing.calc.IPath path) {
        if (path == null || path == this.lastTracedPath) {
            return;
        }
        this.lastTracedPath = path;
        StringBuilder route = new StringBuilder(Math.max(128, path.length() * 48));
        route.append("len=").append(path.length()).append(" exec=").append(current.getPosition()).append(' ');
        var positions = path.positions();
        var movements = path.movements();
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) {
                route.append(' ');
            }
            BlockPos node = positions.get(i);
            route.append(i).append(':').append(node.getX()).append(',').append(node.getY()).append(',')
                    .append(node.getZ()).append('{').append(traceState(ctx.world().getBlockState(node))).append('}');
            if (i < movements.size()) {
                route.append('-').append(movements.get(i).getClass().getSimpleName()).append("->");
            }
        }
        BuildTrace.path(this.buildTick, route.toString());
    }

    /** What was actually held at the END of the previous tick, once every controller had set its inputs. */
    private String keysLastTick;

    /** Called at the top of {@code onTick}, before the clear, so it reads what the PREVIOUS tick ended up
     *  holding after every controller -- including the pathing behaviour -- had set its inputs. */
    private void sampleHeldKeys() {
        if (!BuildTrace.isActive()) {
            return;
        }
        StringBuilder held = new StringBuilder();
        for (Input input : Input.values()) {
            if (princeps.getInputOverrideHandler().isInputForcedDown(input)) {
                if (held.length() > 0) {
                    held.append('+');
                }
                held.append(input.name());
            }
        }
        this.keysLastTick = held.toString();
    }

    /** Registry name plus every state property, with whitespace removed so it remains one trace field. */
    private static String traceState(BlockState state) {
        return state == null ? "-" : state.toString().replace(' ', '_');
    }

    /** The action the executor is on, or null before there is a plan or after the last one. */
    private BuildAction currentAction() {
        if (this.plan == null || this.actionIndex < 0 || this.actionIndex >= this.plan.size()) {
            return null;
        }
        return this.plan.action(this.actionIndex);
    }

    private String traceTarget(BuildAction action) {
        if (action == null) {
            return null;
        }
        BlockPos cell = action.cell();
        // SwapHotbar has no cell at all; the action kind is still worth seeing on the tick line.
        return cell == null ? action.kind().name() : cell.getX() + "," + cell.getY() + "," + cell.getZ();
    }

    // ------------------------------------------------------------------------------------------------ V3 surface

    /** The plan currently being executed, or null before {@link #build} has produced one. */
    public BuildPlan plan() {
        return this.plan;
    }

    /** The dry run's verdict for the current build. Null before {@link #build}. */
    public PlanReport report() {
        return this.report;
    }

    /** Where the executor is in the frozen order. */
    public int actionIndex() {
        return this.actionIndex;
    }

    public State state() {
        return this.state;
    }

    /**
     * Plan fidelity: cells placed exactly as planned over cells placed, in percent.
     *
     * <p>The one number this engine is judged by. It replaces the old ideal-placement rate because it is stricter and
     * because every point it is short of 100 names a cell and a reason, which the old number never did.
     */
    public double planFidelity() {
        // 100 before the first cell, so a run that has placed nothing does not read as a total failure of fidelity.
        return this.planFidelityDenominator == 0 ? 100.0D
                : 100.0D * this.planFidelityNumerator / this.planFidelityDenominator;
    }

    /**
     * Why the build stopped, or null while it has not.
     *
     * <p>The channel {@link IBuilderProcess} does not have. A V3 that cannot proceed must not say so by pausing — the
     * bench scores {@code isPaused() == true} as a failure verdict — so it names the cause here and goes inactive.
     * See {@link #standDown}.
     */
    public String blockedReason() {
        return this.blockedReason;
    }

    /** The run id the three {@code plan/} files are named after, or null outside a build. */
    public String runId() {
        return this.runId;
    }

    /**
     * Re-plan from the current world after a divergence.
     *
     * <p>Same code, same policy, same determinism as the initial plan — it calls {@link #runDryRun()}, which
     * re-captures the world and the inventory from where the bot is standing right now. The {@link #journal} is
     * carried over as an INPUT, so the new plan cannot return a solution the server already refused; that, and not a
     * retry counter, is what makes "re-plan on rejection" terminate instead of looping at click cadence.
     *
     * <p>Decision E-D in one method: there are two re-entries into planning — the first plan and this one — and they
     * are the same mechanism. There is no emergency path, no partial repair and no resume-from-saved-plan, because
     * {@code todo} is defined as "every cell that is not already correct" and re-deriving it is strictly more honest
     * than restoring a plan the world may have moved out from under.
     *
     * <p>Safe to call from outside a build: without a schematic there is nothing to re-plan and it says so rather
     * than planning against null.
     */
    public void replan() {
        if (this.schematic == null || this.settings == null) {
            logMechanic("v3: replan ignored — no build is running");
            return;
        }
        this.replans++;
        this.plan = null;
        if (this.executor != null) {
            this.executor.reset();
        }
        this.executor = null;
        this.actionIndex = 0;
        this.moreLayersPending = false;
        this.state = State.SELECT;
        logMechanic("v3: re-planning (generation " + this.replans + ") from " + BuildAction.describePos(ctx.playerFeet())
                + " — journal holds " + this.journal.describe());
        BlockPos feet = ctx.playerFeet();
        BuildTrace.cell(this.buildTick, "REPLAN", feet.getX(), feet.getY(), feet.getZ(),
                "generation=" + this.replans + " placed=" + this.landedRight);
        runDryRun();
    }
}
