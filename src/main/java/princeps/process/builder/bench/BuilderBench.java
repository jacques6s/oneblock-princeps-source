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

package princeps.process.builder.bench;

import net.minecraft.core.BlockPos;
import princeps.api.utils.BetterBlockPos;
import net.minecraft.world.level.block.state.BlockState;
import princeps.Princeps;
import princeps.api.event.events.TickEvent;
import princeps.api.utils.Helper;
import princeps.behavior.Behavior;
import princeps.utils.BlockStateInterface;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Automated test bench for the schematic builder.
 *
 * <p>Motivation: builder regressions have historically only surfaced in the owner's live game, days after the change
 * shipped (the 0.3.77 disaster). This bench closes that loop in-process: it plants a synthetic schematic with known
 * geometry, runs the real builder against the real world, and <em>independently</em> verifies the result by comparing
 * every target cell against the world — it never trusts the builder's own bookkeeping. Every sample and the final
 * verdict go to the game log with a {@code [BENCH]} marker, so a whole run is machine-readable from
 * {@code latest.log} without watching the screen.
 *
 * <p>Verdicts: {@code SUCCESS} (every target cell matches and the frozen plan ended without a divergence),
 * {@code DIVERGED} (the cells match only after a rejected action and re-plan), {@code PAUSED} (the builder gave up
 * — an honest blocker), {@code INACTIVE} (the process stopped with cells missing), {@code TIMEOUT} (neither finished
 * nor paused within the budget — the shape of a livelock), and {@code STALLED} (progress flatlined while the builder
 * still claimed to be active, including a cleanup action after the final schematic cell).
 */
public final class BuilderBench extends Behavior implements Helper {

    /** Geometry a scenario pins and the bench therefore enforces. Everything else (connection, redstone, waterlog
     *  state) is environment-resolved and legitimately differs from the placed state.
     *
     *  <p>{@code shape} is deliberately NOT here. A stair's shape is decided by its neighbours, not by whoever
     *  places it — vanilla recomputes it on every neighbour change. Demanding it made the bench fail builds that
     *  were correct, and it must not hold the builder to a standard the game does not allow. */
    private static final Set<String> PINNED_PROPS =
            Set.of("type", "facing", "half", "axis", "hinge");

    /** Sample cadence in ticks — 1s at normal tick rate. */
    private static final int SAMPLE_INTERVAL = 20;
    /**
     * How long a run may go without any newly-correct cell before it is called stalled.
     *
     * <p>Must exceed the builder's own retry schedule, or the bench calls a stall that is really a wait. A cell whose
     * support is not there yet -- a ladder before its wall, in the run that exposed this -- is deferred with an
     * escalating backoff up to CELL_DEFER_MAX_TICKS (640). At 600 the bench declared the build dead 40 ticks before
     * the client would have tried again. Three times the longest backoff leaves room for a cell to be retried, fail,
     * and be retried again before anyone calls it stuck.
     */
    private static final int STALL_TICKS = 3 * 640;

    /**
     * Disables the STALLED verdict, so a run spends its whole tick budget and "placed at tick T" becomes comparable
     * between runs. Set by {@code -PnoStall}.
     *
     * <p>Not a convenience. The stall test asks whether the work set shrank or the RETIRED set grew since the last
     * progress tick, and neither is monotone on a large schematic: the give-up re-retires cells that are already
     * retired, so {@code retiredCells.size()} often does not move, and a run can be declared stalled while the builder
     * is still working. Measured on three runs of the same builder: 1641 placed by t=26240, 2629 by t=55380, and 5069
     * by t=321700. The spread is not the builder, it is when this test happened to fire -- and every basalt comparison
     * made before this switch existed was confounded by it.
     */
    private static final boolean NO_STALL = Boolean.getBoolean("princeps.bench.nostall");

    /** Set {@code -Dprinceps.bench=<scenario>} to run a scenario automatically once the world is joined — this is
     *  what makes an unattended run possible: launch, join, prepare, build, verify, report, all without input. */
    private static final String AUTO_SCENARIO = resolveAutoScenario();

    /** Env var first (inherited by the forked game JVM, so `PRINCEPS_BENCH=ring7 ./gradlew runClient` just works),
     *  system property as the explicit override. */
    private static String resolveAutoScenario() {
        String env = System.getenv("PRINCEPS_BENCH");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return System.getProperty("princeps.bench");
    }
    /**
     * Identity of THIS launch, from {@code -Dprinceps.bench.run}. Every bench line carries it, so a driver can tell
     * this run's verdict from the one still sitting in the log file from the previous run — log4j only rolls
     * {@code latest.log} seconds into a launch, and a driver that greps for a bare "verdict=" reads the last run's
     * answer and calls it this one's. That mis-scored a whole scenario before the run id existed.
     */
    private static final String RUN_ID = System.getProperty("princeps.bench.run", "");

    /** Log prefix: {@code [BENCH]}, carrying the run id when there is one. */
    private static String tag() {
        return RUN_ID.isEmpty() ? "[BENCH]" : "[BENCH] run=" + RUN_ID;
    }

    /** {@code -Dprinceps.bench.keep=true} keeps the client in the world after the verdict instead of halting — the
     *  mode to use when a human joins to watch, so the finished build stays on screen. */
    private static final boolean KEEP_ALIVE = Boolean.getBoolean("princeps.bench.keep")
            || "1".equals(System.getenv("PRINCEPS_BENCH_KEEP"));

    /** Ticks to wait after joining before driving anything, so chunks and the player have settled. */
    private static final int AUTO_TELEPORT_TICK = 40;
    private static final int AUTO_SETUP_TICK = 80;
    private static final int AUTO_START_TICK = 160;

    /** Where an unattended run always starts from. Override with {@code -Dprinceps.bench.anchor=x,y,z}; the default
     *  is the standing height of the flat bench world, well away from spawn clutter. */
    private static final BlockPos AUTO_ANCHOR = resolveAnchor();

    private static BlockPos resolveAnchor() {
        String raw = System.getProperty("princeps.bench.anchor");
        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split(",");
            if (parts.length == 3) {
                try {
                    return new BlockPos(Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()));
                } catch (NumberFormatException ignored) {
                    // fall through to the default rather than failing a whole unattended run over a typo
                }
            }
        }
        return new BlockPos(64, -60, 64);
    }

    private boolean autoDone;
    private int autoTicks;
    /** The "no item exists for this block" notice belongs in the log once. */
    private boolean reportedUnobtainable;
    /** Set the moment the bot has been stocked. From then on the inventory is the builder's, not the bench's. */
    private boolean materialsProvisioned;

    private BenchSchematics.Scenario scenario;
    private BlockPos origin;
    private boolean running;
    private int ticks;
    private int lastProgressTick;

    /** Ten seconds. Nothing this bench measures recovers from standing perfectly still for that long. */
    private static final int FROZEN_ABORT_TICKS = 200;
    private BetterBlockPos frozenAt;
    private float frozenYaw = Float.NaN;
    private float frozenPitch = Float.NaN;
    private int frozenSinceTick;
    private int lastRemaining = Integer.MAX_VALUE;
    /** Retired-cell count at the last observed progress, so a rising count counts as progress exactly once. */
    /** Ticks at which placed-so-far is recorded. The two least noisy points on this bench. @see #onTick */
    private static final int[] CHECKPOINT_TICKS = {50_000, 150_000};
    /** Placed-so-far at each checkpoint, or -1 if the run never got that far. */
    private final int[] checkpointPlaced = {-1, -1};
    private int lastRetired;
    private int peakRemaining;

    public BuilderBench(Princeps princeps) {
        super(princeps);
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Start a scenario at {@code origin}. The caller is responsible for the world being suitable (flat, clear, and
     * the player in creative with the needed blocks) — {@link #setupCommands} lists what a driver should run first.
     */
    public void start(BenchSchematics.Scenario scenario, BlockPos origin) {
        start(scenario, origin, true);
    }

    /**
     * Starts the verifier and builder against an environment a specialised driver has already prepared.
     *
     * <p>The ordinary bench owns its floor, inventory and clear commands, so {@link #start} keeps preparing them.
     * Airborne Map Art is deliberately different: its driver has already waited for the one-block launch platform
     * and auction stand-in deliveries to reach the client. Replaying the generic setup here used to clear that sole
     * block in the same tick the builder was released. The replacement {@code setblock} arrived a few client ticks
     * later, by which point the bot was already below the picture and could not recover.
     *
     * <p>Skipping only that duplicate preparation also keeps the material test honest. The generic refill lays every
     * item into predetermined slots and adds cobblestone; the Map-Art driver instead models {@code /ah ... stack} by
     * delivering normal stacks and leaves all hotbar management to the builder.
     */
    public void startPrepared(BenchSchematics.Scenario scenario, BlockPos origin) {
        start(scenario, origin, false);
    }

    private void start(BenchSchematics.Scenario scenario, BlockPos origin, boolean prepareEnvironment) {
        this.scenario = scenario;
        this.origin = origin;
        this.running = true;
        this.ticks = 0;
        this.lastProgressTick = 0;
        this.lastRemaining = Integer.MAX_VALUE;
        this.lastRetired = 0;
        java.util.Arrays.fill(this.checkpointPlaced, -1);
        this.peakRemaining = scenario.cellCount();
        this.pendingBuildTicks = 0;
        logMechanic(tag() + " start scenario=" + scenario.name
                + " cells=" + scenario.cellCount()
                + " origin=" + origin.getX() + "," + origin.getY() + "," + origin.getZ());
        if (prepareEnvironment) {
            // The generic bench owns its own environment: clear the volume, lay a floor, go creative and stock the
            // materials. Specialised benches can prepare a different environment and enter through startPrepared.
            for (String cmd : setupCommands(scenario, origin)) {
                sendCommand(cmd);
            }
            // Materials are handed over exactly ONCE, and the auto-setup pass has already done it. Writing them again
            // here rewrote fixed slots the bot had meanwhile reorganised for itself -- it can move items out of its own
            // backpack, that is what allowInventory is for -- and every rewrite put a duplicate where a material used
            // to be. Hand over the stock, then keep out of the inventory entirely.
            if (!materialsProvisioned) {
                refillMaterials(scenario);
            }
        }
        writeCellManifest();
        applyProductionBuildSettings();
        if (scenario.name.startsWith("dig")) {
            applyClearProfileLikeTheClient();
            // The fill that CREATES the pit is a chat command, and a chat command is not executed the instant
            // it is sent. Starting the build in the same tick asks the builder to clear a volume that is still
            // air, so an all-air scenario is satisfied before it begins and the process reports itself done
            // after twenty ticks with nothing placed. That is not a builder result, it is a race, and it wasted
            // an A/B run that looked like two identical clean verdicts.
            pendingBuildTicks = FILL_SETTLE_TICKS;
            return;
        }
        princeps.getBuilderProcess().build(scenario.name, scenario, origin);
    }

    /**
     * Put the builder in the exact configuration the shipped client uses for an autonomous build.
     *
     * <p>A bench that runs the builder in a mode the customer never runs grades the wrong thing. Princeps defaults
     * to free-order building; OneBlock's autonomous builder (PrincepsBridge.startSchematicBuild) instead builds
     * strictly one layer at a time, bottom-up, and refuses to advance past an incomplete layer. That is a
     * completely different traversal, with different failure modes -- a cell floating out of reach is a dead end in
     * free order, but simply not due yet when its layer has not been reached. The bench must mirror it, or its
     * verdicts describe a build nobody performs.
     */
    /**
     * A dig scenario must run the CLEAR profile the shipped client uses, not the build profile.
     *
     * <p>The bench graded the dig path green while the owner watched the same path shake on screen, and the
     * reason was this: AutoDig replaces the look settings before it starts -- the base hunter's smoothness
     * preset, its turn rate, a damped humanisation, a narrower reach, and the aim hold -- and the bench applied
     * none of them. Two different look profiles are two different systems; whether an aim loop settles or
     * oscillates is decided entirely by its gain, so a bench on other numbers cannot answer the question being
     * asked of it.
     *
     * <p>Values mirror PrincepsBridge + ModuleAutoDig at Smoothness.BALANCED, which is the default a customer
     * gets. Keep the two in step: every setting missing here is a class of bug the bench cannot see.
     */
    private void applyClearProfileLikeTheClient() {
        princeps.api.Settings s = Princeps.settings();
        // FREE ORDER, not layers. PrincepsBridge.startSchematicClear turns layering off for exactly one
        // reason: a bottom-up layer constraint on a CLEAR makes the bot mine the floor out from under the
        // debris still standing above it, so it can never satisfy the bottom layer and never advances.
        // dig15 failed on precisely that -- 3240 cells unsatisfied behind a bottom layer of 204 -- while the
        // small dig7x3x7 got away with it because three layers is not enough rope to hang yourself with.
        s.buildInLayers.value = false;
        // The aim hold is the thing under test, so it is switchable from the command line rather than
        // recompiled: -Dprinceps.bench.aimhold=false runs the same scenario without it, which is the only
        // way to attribute a change in the shake numbers to it rather than to the weather.
        boolean aimHold = !"false".equalsIgnoreCase(System.getProperty("princeps.bench.aimhold", "true"));
        // ModuleBaseHunter.Smoothness.BALANCED, applied via PrincepsBridge.applyBaseHuntSmoothness
        s.humanizedLookTurnMaxSpeed.value = 62.0D;
        s.humanizedLookTurnMinSpeed.value = 4.2D;
        s.humanizedLookTurnGain.value = 0.5D;
        s.humanizedLookMaxCruiseYaw.value = 22.5D;
        s.humanizedLookMaxCruisePitch.value = 39.0D;
        s.humanizedLookAimCurvePeakScale.value = 1.0D;
        s.humanizedLookAimCurveTurnTicks.value = 4.0D;   // applyBaseHuntTurnTicks(BALANCED)
        // PrincepsBridge.applyLookHumanisation -- damped to 0.222 of full strength
        s.randomLooking.value = 0.12D * 0.222D;
        s.randomLooking113.value = 0.18D * 0.222D;
        s.microJitterMinDegrees.value = 0.10D * 0.222D;
        s.microJitterMaxDegrees.value = 0.80D * 0.222D;
        s.humanizedLookTremorDegrees.value = 0.0D;
        // The two that decide whether the aim loop settles at all.
        s.blockReachDistance.value = 4.0F;
        s.remainWithExistingLookDirection.value = aimHold;
        logMechanic(tag() + " clear profile applied (client-equivalent look settings, layers=off, aimHold=" + aimHold + ")");
    }

    private void applyProductionBuildSettings() {
        // MIRRORS PrincepsBridge.applyExactBuildProfile + startSchematicBuild in the OneBlock client. Keep the two
        // in step: every setting missing here has already cost a run. Layer mode was missing, so the bench built in
        // free order and reported a scaffolding dead-end that the layered client never reaches; allowInventory was
        // missing, so the bot could not fetch its 10th material and stalled on a door sitting in its own backpack.
        // Pinning the WHOLE profile ends that one-setting-at-a-time discovery.
        //
        // Note: the `princeps` field shadows the package name, so this goes through the static accessor.
        princeps.api.Settings s = Princeps.settings();
        s.allowBreak.value = true;
        s.allowPlace.value = true;
        s.allowInventory.value = true; // client default UseInventory=true; without it only 9 materials are reachable
        s.allowPlaceInFluidsSource.value = true;
        s.allowPlaceInFluidsFlow.value = true;
        s.breakCorrectBlockPenaltyMultiplier.value = 1_000_000.0D;
        // The counterpart, and it only became load-bearing once the bot was given a scaffold block. Its default of 2
        // is not a deterrent: with a throwaway in hand the pathfinder will cheerfully drop cobblestone INTO a cell the
        // schematic wants a stair in, just to have something to walk on, and then that cell has to be broken and
        // rebuilt. The `oriented` scenario regressed from 7/7 to 6/7 on exactly this, with the bot standing in the very
        // cell it was supposed to fill.
        //
        // It was 1_000_000, and that was not a deterrent either -- it was a prohibition, and it immobilised the bot.
        //
        // costOfPlacingAt asks getSchematic first, and that returns non-null for EVERY position inside the schematic's
        // bounding box, including the cells the schematic wants to be AIR. So the 1_000_000 did not merely price the
        // blueprint's own cells out of use as scaffolding; it priced out the entire empty volume of the farm, which is
        // exactly where a bot has to bridge. The escape the old comment relied on -- "bridging OUTSIDE the schematic
        // still costs a plain placeBlockCost" -- is true and useless, because on a 15004-cell farm the bot is inside
        // that box for the whole run.
        //
        // The signature, from the 5400s run at c0b7350: 193568 of 193571 path calculations ended "Open set size: 0"
        // with a PathNode map of exactly 15 -- median, 10th and 90th percentile all 15 -- while 330 movements were
        // considered each time and rejected. Every one of them a "No path to placement stance X; trying another",
        // including stances ONE block away and one down. "path calculation repeatedly failed" was the run's commonest
        // deferral reason at 4364 against 1778 for every stance failure combined.
        //
        // 10 is five times the library default and still finite. The instrument for "scaffold only as a last resort"
        // is blockPlacementPenalty below, which is a flat charge in ticks-of-walking and is exactly the right shape
        // for that; a multiplier that reaches infinity is not a preference, it is a wall.
        s.placeIncorrectBlockPenaltyMultiplier.value = 10.0D;
        // Scaffolding is a LAST RESORT, not a convenience.
        //
        // A throwaway block is what lets the bot reach cells that float with no neighbour, and etz-basalt has many of
        // those -- but the owner watching a run does not want to see cobblestone scattered wherever a route happened to
        // be one block shorter. Nothing cleans those up afterwards, and on a layer-by-layer build there is almost
        // always a way round.
        //
        // blockPlacementPenalty is what the pathfinder charges itself for placing a block to stand on. At its default
        // of 20 that is roughly twenty ticks of walking, so any detour longer than about twenty blocks loses to just
        // dropping a block. Raising it to 500 means a route that scaffolds is only chosen when walking round would
        // take hundreds of ticks -- i.e. when there genuinely is no way round, which is the only case it is wanted.
        s.blockPlacementPenalty.value = 500.0D;
        // The work-set cap, and it has been behind more findings than any other number in this project without ever
        // being tested. At 100, on a schematic whose single densest row holds 1821 cells:
        //   fullRecalc RETURNS as soon as the set passes it, scanning y upward, so cells high in the schematic are
        //     invisible while any 100 lower ones are outstanding;
        //   giveUpOnLayerIfItHasStalled can only retire what is visible, which is why it needed 25 firings at 900
        //     ticks each to release a fraction of one layer;
        //   and of the 5325 cells a run sets aside, only 1663 distinct ones ever appear in a deferral line -- at least
        //     69% are discarded having never been offered to the search at all, because they never fit in the window.
        // Raising it costs iteration per tick and nothing else: searchForPlacables is position-local and already sorts.
        //
        // TRIED AT 1000 AND REVERTED. Measured against the calibrated checkpoints: 2643 placed at t=50000 against a
        // baseline of 2607/2637/2689 -- dead level, inside a 3.0% noise floor -- but 3859 at t=143540 against
        // 4147/4230/4260 at t=150000, which is -7% where the measured spread is 2.7%, and the server audit came out at
        // 147/578 = 25.4% against a 27.2-31.0% baseline range. So a wider window does NOT free the untried cells; it
        // dilutes the search. searchForPlacables walks the whole set every tick to pick a target, so ten times the set
        // is ten times the walk for the same handful of reachable cells, and the cap is buying locality rather than
        // merely bounding memory. If the 69%-never-attempted problem is attacked again, it must be by changing WHICH
        // cells are in the window, not how many.
        //
        // s.incorrectSize.value = 1000;
        // Full per-tick tracing for every bench run. The ordinary log is a FAULT log -- a successful placement writes
        // nothing at all -- and that is how 175 missing cells came to appear nowhere in a 96000-tick run. The trace
        // costs tens of megabytes per run, which is a trade the owner has explicitly made in exchange for being able
        // to name a coordinate and read that block's entire life.
        System.setProperty("princeps.buildtrace", "true");
        s.buildIgnoreBlocks.value = new java.util.ArrayList<>();
        s.buildSkipBlocks.value = new java.util.ArrayList<>();
        s.buildValidSubstitutes.value = new java.util.HashMap<>();
        s.buildSubstitutes.value = new java.util.HashMap<>();
        s.buildIgnoreExisting.value = false;
        s.buildIgnoreDirection.value = false;
        s.buildIgnoreProperties.value = new java.util.ArrayList<>();
        s.buildOnlySelection.value = false;
        s.okIfWater.value = false;
        s.okIfAir.value = new java.util.ArrayList<>();
        s.mapArtMode.value = false;
        // The structural pass: one-block layers, strictly bottom-up, never advancing past an incomplete layer.
        //
        // -PnoLayers turns this off, which is not a convenience switch but the experiment the whole build order rests
        // on. Strict bottom-up layering is suspected of CREATING unbuildable cells rather than merely ordering them:
        // etz-basalt's 464 down-facing pistons can only be placed by clicking the course ABOVE them, which by
        // definition does not exist while their own layer is being built, and its y=-59 layer holds ~1700 cells over a
        // 936-cell one, so many float with no neighbour yet. With skipFailedLayers=false a single such cell holds
        // everything above it. Runs have stalled at layer 2 of 18 for hours, which is the shape of an ordering problem
        // rather than a tuning one -- and one flag settles it either way.
        s.buildInLayers.value = !Boolean.getBoolean("princeps.bench.nolayers");
        // Bottom-to-top by default. -PtopDown flips it, and it is a real experiment rather than a convenience: the
        // measured collapse of an etz-basalt run begins at layer 4, y=-57, which is the row holding 448 of the
        // schematic's 464 down-facing pistons. Those need a block ABOVE them to click, which bottom-up has not built
        // yet, so the layer never finishes and the give-up takes 1274 cells with it -- after which every layer above
        // is missing the floors it needed. Top-down would hand each of those pistons its ceiling before its turn, and
        // would instead strand the repeaters that need those pistons as their own floor; the difference is that a
        // stranded repeater supports nothing, so it cannot cascade, and the retry sweep meets a built piston.
        //
        // MEASURED, and the answer is no: 0 of 15004 cells placed by tick 34600, against 1467 by tick 16320 bottom-up,
        // with 38 layer give-ups and the build still on layer 4. Top-down starts with the roof, and a roof has neither
        // a face to click nor a floor to stand on -- the first rows of this schematic are its sparsest. The switch
        // stays, default off, because the question was worth asking once and is not worth asking twice.
        s.layerOrder.value = Boolean.getBoolean("princeps.bench.topdown");
        s.layerHeight.value = 1;
        s.startAtLayer.value = 0;
        // -PskipLayers lets the build climb past a layer it could not finish, instead of stopping at it forever.
        //
        // The middle option between the two the experiment above compared, and by far the most promising: turning
        // layers off entirely made basalt much WORSE (545 placed by tick 16780 against ~1400 for the layered run),
        // because without them the bot ranges over the whole 15,004-cell farm instead of finishing what is under its
        // feet. Layers are earning their keep as a locality device. It is their STRICTNESS that blocks -- basalt has
        // sat at layer 2 of 18 for hours behind a few hundred cells that cannot be placed until the course above them
        // exists, which is a course that a strict gate will never let it reach.
        //
        // The project has been here once before: the 0.3.55 "stuck at 23%" report was this exact shape, and setting
        // this true was the fix. It was pinned false here to mirror the OneBlock client -- but if the client is wrong,
        // mirroring it faithfully only reproduces the fault.
        s.skipFailedLayers.value = Boolean.getBoolean("princeps.bench.skiplayers");
        // Printed from the settings, not from a hardcoded description of them. The hardcoded version claimed
        // "layers=1 bottom-up" even in a run started with -PnoLayers, which is exactly the kind of log line that
        // makes an experiment unreadable: the only way to tell whether the flag had taken was to notice that no
        // "Starting layer" message ever appeared.
        // -PturnTicks pins the head-turn speed for this run, in ticks per 90 degrees. A property and not the setting
        // for the same reason the engine choice is one: a measurement must not decide what the owner's next real
        // launch looks like. Clamped to the dial's own 1..5 so a typo cannot produce a profile the client cannot.
        String turnTicks = System.getProperty("princeps.builder.turnticks");
        if (turnTicks != null && !turnTicks.isBlank()) {
            try {
                s.builderTurnTicks.value = Math.min(5.0, Math.max(1.0, Double.parseDouble(turnTicks.trim())));
            } catch (NumberFormatException malformed) {
                logMechanic(tag() + " ignoring -PturnTicks=" + turnTicks + ": not a number");
            }
        }
        logMechanic(tag() + " turnTicks=" + s.builderTurnTicks.value + " ticks/90deg");
        logMechanic(tag() + " build profile: buildInLayers=" + s.buildInLayers.value
                + (s.buildInLayers.value ? " (height=" + s.layerHeight.value
                        + ", " + (s.layerOrder.value ? "top-down" : "bottom-up") + ")" : " (build by reach)")
                + ", skipFailedLayers=" + s.skipFailedLayers.value
                + ", allowInventory=" + s.allowInventory.value
                + ", scaffold=" + !"false".equals(System.getProperty("princeps.bench.scaffold")));
    }

    /**
     * Give the player exactly the blocks this scenario needs, and nothing else. Repeated periodically while a run is
     * active, so running dry can never be mistaken for a placement failure.
     *
     * <p>The {@code clear} is not tidiness, it is the difference between a bench and a coincidence. The server keeps
     * per-player inventory in the world save, so leftovers from every earlier run persist: after four scenarios the
     * hotbar was full of stone and stained glass, {@code give} dropped this scenario's slab/stairs/door into main
     * inventory slots 31-35, and the builder -- which only ever reaches for the hotbar -- reported the material as
     * missing and stalled at 0/7. The scenario had not failed; its predecessors had filled its pockets.
     */
    private void refillMaterials(BenchSchematics.Scenario sc) {
        refillMaterials(sc, true);
    }

    /**
     * Stock the bot ONCE, with as many stacks of each material as the schematic actually consumes.
     *
     * <p>Handing materials out repeatedly does not work, in either direction, because the builder owns the inventory
     * too:
     * <ul>
     *   <li>{@code give} appends, so re-giving 21 materials every 10s overflowed the 36 slots after two passes and
     *       the surplus fell on the floor;</li>
     *   <li>{@code clear} first empties the inventory for the tick or two the client lags behind, and the builder
     *       samples exactly there and pauses for missing material;</li>
     *   <li>writing fixed slots repeatedly fights the builder's own hotbar management (allowInventory=true): it
     *       moves a door up to the hotbar, the next rewrite puts a second door in the door's home slot, and the
     *       material that lived there -- a bed, in the run that exposed this -- is simply gone. The build then
     *       pauses for a bed the bench had taken away from it.</li>
     * </ul>
     * Provisioning the exact demand once removes the need to ever touch the inventory again mid-run.
     */
    private void refillMaterials(BenchSchematics.Scenario sc, boolean clearFirst) {
        // Ask the block which ITEM places it, instead of assuming the item shares the block's id. They often do not:
        // wall_torch is placed by torch, wall signs by signs, wall banners by banners, wall heads by heads. Deriving
        // the id from the block made `give minecraft:wall_torch` fail with "Unknown item", the bot never received a
        // torch, and the builder correctly paused for missing material -- a bench failure that read exactly like a
        // builder failure. asItem() maps that whole family at once.
        // Count DEMAND per item, not just which items occur: one stack of sandstone does not build a sandstone wall,
        // and running dry mid-build is indistinguishable from a placement bug in the verdict.
        Map<net.minecraft.world.item.Item, Integer> demand = new java.util.LinkedHashMap<>();
        Set<String> unobtainable = new LinkedHashSet<>();
        for (BlockState st : sc.cells.values()) {
            net.minecraft.world.item.Item item = st.getBlock().asItem();
            if (item == net.minecraft.world.item.Items.AIR) {
                // Fire, piston heads, redstone wire, plant stems: no item form at all. Name them rather than let a
                // build quietly pause later on a material the bench was never able to hand over.
                unobtainable.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
                continue;
            }
            demand.merge(item, 1, Integer::sum);
        }
        // Write each material into a FIXED slot with `item replace`, rather than clear+give.
        //
        // `give` appends: topping up every 10s with one give per material added a fresh stack each time, so with 21
        // materials the 36-slot inventory overflowed after the second top-up and the surplus -- including the chest
        // this build needed -- fell on the floor. The builder then reported, correctly, that nothing in the
        // inventory could place a chest. And `clear` first is no better: it empties the inventory for the tick or
        // two the client needs to catch up, and the builder samples exactly there and pauses for missing material.
        // Replacing one slot at a time is atomic, idempotent and never leaves a gap in either direction.
        // First reserve one stack for each material, so a common material cannot crowd out the palette.
        // Then provision the remaining demand in the spare slots: Survival consumes every placement.
        //
        // Most-used types first, so if a schematic does exceed 36 types the ones that get dropped are the rarest --
        // the lower layers stay buildable, which is where the run has to get to anyway. What was dropped is named;
        // a bench that silently under-provisions reports builder failures that are its own.
        materialsProvisioned = true;
        List<Map.Entry<net.minecraft.world.item.Item, Integer>> byDemand = new java.util.ArrayList<>(demand.entrySet());
        byDemand.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int slot = 0;
        List<String> dropped = new java.util.ArrayList<>();
        for (Map.Entry<net.minecraft.world.item.Item, Integer> e : byDemand) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
            if (slot >= 36) {
                dropped.add(id + " (x" + e.getValue() + ")");
                continue;
            }
            int perStack = Math.max(1, new net.minecraft.world.item.ItemStack(e.getKey()).getMaxStackSize());
            sendCommand("item replace entity @s container." + slot + " with " + id + " " + perStack);
            slot++;
        }
        // Keep the existing scaffold reservation available when the palette leaves room for it.
        int materialSlots = "false".equals(System.getProperty("princeps.bench.scaffold")) ? 36 : 35;
        for (Map.Entry<net.minecraft.world.item.Item, Integer> e : byDemand) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
            int perStack = Math.max(1, new net.minecraft.world.item.ItemStack(e.getKey()).getMaxStackSize());
            int remaining = e.getValue() - perStack;
            while (remaining > 0 && slot < materialSlots) {
                int count = Math.min(perStack, remaining);
                sendCommand("item replace entity @s container." + slot + " with " + id + " " + count);
                slot++;
                remaining -= count;
            }
            if (remaining > 0) {
                logMechanic(tag() + " insufficient inventory capacity: " + id + " x" + remaining
                        + " still needed beyond the stocked stacks");
            }
        }
        if (!dropped.isEmpty()) {
            logMechanic(tag() + " " + demand.size() + " block types for 36 slots; NOT stocked: "
                    + String.join(", ", dropped) + " -- cells needing them cannot be built this run");
        }
        // A SCAFFOLD block, and it is not optional.
        //
        // Princeps gates every movement that has to place a block to stand on -- pillar up, bridge across, ascend --
        // behind CalculationContext.hasThrowaway, which asks the inventory for one of acceptableThrowawayItems
        // (dirt / cobblestone / netherrack / stone). This bench stocked the schematic's materials and cleared every
        // other slot, so that check was false in every run: the bot could not pillar, could not bridge, and therefore
        // could not reach the upper layers of anything hollow. A lighthouse is hollow, and the run showed exactly that
        // -- goals sitting three levels above a bot that had no way to climb, pathfinder calculating forever.
        //
        // A human handed this schematic would bring scaffolding. Withholding it does not test the builder, it tests
        // whether the builder can levitate. Cobblestone appears in neither test schematic, so it stays distinguishable
        // from real work, and creative placement never consumes it.
        // ON by default; -PnoScaffold withholds it, which is how its interaction with the placement goal was isolated.
        //
        // Princeps gates every movement that places a block to stand on behind CalculationContext.hasThrowaway, which
        // asks the inventory for one of acceptableThrowawayItems. Without one the bot cannot pillar or bridge, and a
        // schematic like etz-basalt is then not merely slow but partly impossible: its y=-59 layer holds ~1700 cells
        // above a 936-cell layer, so a large share of them float with nothing adjacent to click against at all.
        //
        // Handing one over regressed `oriented` and then `wall`, deterministically, and neither was the scaffold's
        // fault. Both were the placement GOAL accepting a spot the bot cannot build from: GoalPlace(cell) means "stand
        // on the cell you are filling", and GoalGetToBlock's |dx|+|dy|+|dz| <= 1 is true for the cell ITSELF. A bot
        // without a throwaway simply could not reach those positions, so the flaw stayed invisible; a bot with one
        // walks straight into them and stops -- at its goal, with its own body making vanilla refuse the placement.
        // With the goal fixed to GoalAdjacent, wall/oriented/ring7 all pass WITH a scaffold, and wall does it in 260
        // ticks against 940 without one.
        int scaffoldSlot = -1;
        if ("false".equals(System.getProperty("princeps.bench.scaffold"))) {
            // Say WHICH reason. Reporting "no slot left" for a scaffold that was simply switched off is the same kind
            // of wrong-cause message that has cost this project days already.
            logMechanic(tag() + " no scaffold block (withheld via -PnoScaffold)");
        } else if (slot >= 36) {
            logMechanic(tag() + " no slot left for a scaffold block; the bot cannot pillar or bridge this run");
        } else {
            scaffoldSlot = slot;
            sendCommand("item replace entity @s container." + slot + " with minecraft:cobblestone 64");
            slot++;
        }
        if (clearFirst) {
            // Wipe whatever sits beyond the materials, so a run never inherits the last one's leftovers.
            for (int rest = slot; rest < 36; rest++) {
                sendCommand("item replace entity @s container." + rest + " with air");
            }
        }
        if (scaffoldSlot >= 0) {
            logMechanic(tag() + " stocked " + (scaffoldSlot) + " material slot(s) + cobblestone scaffold in slot "
                    + scaffoldSlot);
        }
        if (!unobtainable.isEmpty() && !reportedUnobtainable) {
            reportedUnobtainable = true;
            logMechanic(tag() + " no obtainable item for: " + String.join(", ", unobtainable)
                    + " -- these cells cannot be graded fairly");
        }
    }

    // ── motion and aim ──────────────────────────────────────────────────────────────────────────────────
    //
    // Two questions a placement census cannot answer: does the body actually travel, and does the head hold still
    // while it works. A client trace taken on 07.08. showed a run that stood in one spot for 144 seconds and
    // reversed its turn direction on 60% of ticks -- it was never going to finish, and every verdict this bench
    // produces would have called it a healthy run in progress. These numbers close that gap.

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private float lastYaw = Float.NaN;
    private float lastTurn = Float.NaN;
    private long stillTicks;
    private long longestStill;
    private long currentStill;
    private double travelled;
    private double turnedDegrees;
    private long turnReversals;
    /** Ticks where the view reversed while the body stood still and no arm swung -- juddering, and nothing else. */
    private long shakeTicks;
    /** Reversal while standing, swinging or not -- what the operator sees. */
    private long rockTicks;
    /** Ticks still to wait for the setup fill to land before the build may start. 0 = not waiting. */
    private int pendingBuildTicks;
    /** A second of world time. The fill goes out as several commands and each is one server tick at best. */
    private static final int FILL_SETTLE_TICKS = 20;

    private void sampleMotion() {
        double x = ctx.player().position().x;
        double z = ctx.player().position().z;
        float yaw = ctx.playerRotations().getYaw();

        boolean stood = false;
        if (!Double.isNaN(lastX)) {
            double step = Math.hypot(x - lastX, z - lastZ);
            travelled += step;
            stood = step < 0.001D;
            if (stood) {
                stillTicks++;
                currentStill++;
                longestStill = Math.max(longestStill, currentStill);
            } else {
                currentStill = 0;
            }
        }

        boolean reversed = false;
        if (!Float.isNaN(lastYaw)) {
            float turn = net.minecraft.util.Mth.degreesDifference(lastYaw, yaw);
            turnedDegrees += Math.abs(turn);
            // A reversal is the signature of the shake: not a fast turn, but a turn that undoes itself. The
            // deadband keeps humanisation noise -- which is deliberately tiny -- from counting as a direction change.
            reversed = !Float.isNaN(lastTurn) && turn * lastTurn < 0
                    && Math.abs(turn) > 0.05f && Math.abs(lastTurn) > 0.05f;
            if (reversed) {
                turnReversals++;
            }
            lastTurn = turn;
        }

        // TWO measures, because the first one was too narrow and said 0.0% while the owner watched it rock.
        //
        // shake  = reversal, standing still, NOT swinging. The original reading: juddering instead of working.
        // rock   = reversal while standing still, swinging or not. This is what an operator actually sees --
        //          a head that oscillates DURING mining looks exactly as wrong as one that oscillates instead
        //          of mining, and the first measure excluded precisely that case by construction.
        //
        // Keeping both distinguishes "it is not working" from "it works but looks broken", which need
        // different fixes. A metric that can only report zero is not a measurement.
        if (reversed && stood) {
            rockTicks++;
            if (!ctx.player().swinging) {
                shakeTicks++;
            }
        }

        lastX = x;
        lastZ = z;
        lastYaw = yaw;
    }

    /** The two sentences an operator would otherwise have to obtain by watching the bot for two minutes. */
    private String motionNote() {
        if (ticks <= 1) {
            return "";
        }
        return String.format(java.util.Locale.ROOT,
                " travelled=%.1f still=%.0f%% longestStill=%d turnPerTick=%.2f reversals=%.0f%% shake=%.1f%% rock=%.1f%%",
                travelled,
                100.0D * stillTicks / ticks,
                longestStill,
                turnedDegrees / ticks,
                100.0D * turnReversals / ticks,
                shakePercent(),
                ticks > 0 ? 100.0D * rockTicks / ticks : 0.0D);
    }

    private double shakePercent() {
        return ticks > 0 ? 100.0D * shakeTicks / ticks : 0.0D;
    }

    /**
     * Above this share of juddering ticks a run is reported as SHAKY even if it emptied the box.
     *
     * <p>Five percent, because the honest reading of the client trace that started this was 60% and a healthy
     * run should be near zero -- there is no ambiguous middle to argue about. The point of failing a run that
     * FINISHED is that "it completed" was never the whole question: a bot that judders is a bot somebody is
     * watching, and it was invisible to every verdict this bench produced.
     */
    private static final double SHAKE_FAIL_PERCENT = 5.0D;

    /**
     * A registered gate the unattended exit waits for before halting the JVM.
     *
     * <p>A verdict is not the last thing a run has to say. A specialised driver may still owe an independent census
     * of the finished world, and that census used to depend on winning a fixed 1.5-second sleep -- a race whose
     * loser produced a run with a green verdict and no audit line at all, which the launcher then had to grade as a
     * failure it could not explain. Holding the exit on the auditor's own readiness takes the timing out of the
     * question; the cap only keeps a stuck auditor from turning into a hung run.
     */
    private static volatile java.util.function.BooleanSupplier exitGate;
    private static volatile String exitGateName = "";
    /** Hard cap for {@link #exitGate}. Long enough for any post-verdict census, short enough to never hang a run. */
    private static final long EXIT_GATE_CAP_MS = 30_000L;

    /** Holds the unattended halt until {@code ready} reports true, or until the cap elapses. */
    public static void holdExitUntil(String name, java.util.function.BooleanSupplier ready) {
        exitGateName = name == null ? "" : name;
        exitGate = ready;
    }

    /**
     * Ends the run WITHOUT judging it, so a specialised driver can take the world over from the generic bootstrap.
     *
     * <p>This exists because there was no way to say "this run is being replaced", and the driver had to settle for
     * revoking the builder instead. That left the bench running and still grading: one sample later it saw an
     * inactive builder over an unfinished scenario, printed {@code verdict=INACTIVE} and halted the JVM a second and
     * a half after that -- in the MIDDLE of the replacement fixture's setup commands. The bigger the fixture, the
     * more reliably it lost that race, which is exactly backwards. Handing over is a state, not a verdict, so give
     * it one.
     */
    public void suspendForHandover(String reason) {
        if (!running) {
            return;
        }
        running = false;
        pendingBuildTicks = 0;
        logMechanic(tag() + " handover -- this run is being replaced (" + reason + "); no verdict is due");
        princeps.getBuilderProcess().onLostControl();
    }

    public void stop(String verdict) {
        if (!running) {
            return;
        }
        running = false;
        int remaining = countRemaining();
        int placed = scenario.cellCount() - remaining;
        double blocksPerMin = ticks > 0 ? placed * 1200.0D / ticks : 0.0D;
        // Walk efficiency belongs on the verdict line, not in a log grep. It is the direct measure of the wasted travel
        // that is the most visible thing wrong with this builder, and the number whose absence let five plausible
        // "improvements" to cell selection each be shipped and each measured worse.
        long[] walks = princeps.getBuilderProcess().walkEfficiency();
        String walkNote = walks[0] == 0 ? " walks=0"
                : String.format(java.util.Locale.ROOT, " walks=%d paidOff=%d (%.0f%%)",
                        walks[0], walks[1], 100.0D * walks[1] / walks[0]);
        // stanceless= is the cost walk efficiency cannot see: a target routed to and then abandoned because nothing
        // could be clicked against it never reaches the walksStarted++ below the early return. recalled= is how many
        // retired cells a landing neighbour brought back.
        long[] stance = princeps.getBuilderProcess().stanceFailureCounts();
        // A finished run that juddered its way there is not a success; say so on the line that gets graded,
        // because a number in the middle of a long verdict is a number nobody reads.
        if ("SUCCESS".equals(verdict) && shakePercent() > SHAKE_FAIL_PERCENT) {
            verdict = "SHAKY";
        }
        logMechanic(tag() + " verdict=" + verdict
                + " scenario=" + scenario.name
                + " placed=" + placed + "/" + scenario.cellCount()
                + " remaining=" + remaining
                + " ticks=" + ticks
                + String.format(java.util.Locale.ROOT, " blocksPerMin=%.1f", blocksPerMin)
                + walkNote
                + " stanceless=" + stance[0]
                + " recalled=" + stance[1]
                + motionNote()
                + qualityNote()
                + checkpointNote());
        if (remaining > 0) {
            reportUnsatisfiedByFamily();
            // Name the cells that were never satisfied — this is the list a fix has to explain.
            StringBuilder sb = new StringBuilder(tag() + " unsatisfied:");
            int shown = 0;
            for (BlockPos p : remainingCells()) {
                if (shown++ >= 20) {
                    sb.append(" ...(+").append(remaining - 20).append(" more)");
                    break;
                }
                sb.append(' ').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
            }
            logMechanic(sb.toString());
        }
        princeps.getBuilderProcess().onLostControl();
        logMechanic(tag() + " END");
        if (AUTO_SCENARIO != null && !AUTO_SCENARIO.isEmpty() && !KEEP_ALIVE) {
            // Unattended run: terminate so the launcher returns immediately with the verdict in the log. Off the
            // game thread and after a short grace period so the log is flushed first.
            Thread exit = new Thread(() -> {
                try {
                    Thread.sleep(1500L);
                    // Whoever still owes a line after the verdict says when it is safe to go. Polling a supplier
                    // beats a longer sleep: a longer sleep is still a race, only a slower one.
                    java.util.function.BooleanSupplier gate = exitGate;
                    if (gate != null) {
                        long deadline = System.currentTimeMillis() + EXIT_GATE_CAP_MS;
                        while (!gate.getAsBoolean() && System.currentTimeMillis() < deadline) {
                            Thread.sleep(100L);
                        }
                        if (!gate.getAsBoolean()) {
                            logMechanic(tag() + " exit gate '" + exitGateName + "' never reported ready after "
                                    + (EXIT_GATE_CAP_MS / 1000L) + "s -- halting anyway");
                        }
                        Thread.sleep(500L);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                Runtime.getRuntime().halt(0);
            }, "bench-exit");
            exit.setDaemon(true);
            exit.start();
        }
    }

    /**
     * Break the failure down by block type, and by the lowest layer still incomplete.
     *
     * <p>Without this a verdict reads "STALLED placed=373/15012" and a reader has to go mine the log to learn
     * anything actionable. It matters most on a layered build: the builder refuses to advance past an incomplete
     * layer, so ONE unbuildable family in the lowest unfinished layer accounts for every other missing cell above it.
     * That is exactly what happened -- sixteen wall signs in layer 0 held back 14,639 cells of plain glass, and the
     * verdict's twenty sample coordinates all pointed at the glass, which was never the problem. The lowest
     * incomplete layer plus its block census names the real blocker in one line.
     */
    /**
     * The comparable half of a verdict: placed-so-far at fixed ticks.
     *
     * <p>A run ends when its wall clock does, and where that lands is not a property of the builder. Reading
     * "placed" at the end therefore compares two different questions. These two ticks are the least noisy points
     * measured on this bench -- 3.0% and 2.7% spread across three effectively identical runs, against 8.8% at the
     * end -- and the second is already near the plateau, so a 2500-second run says almost everything a 5400-second
     * one does.
     */
    /**
     * The first-try rate, and how much of the run was the builder correcting itself.
     *
     * <p>On the verdict line because it is the standard the owner states plainly: a block should be placed correctly
     * the first time. A traced run that sent 128 clicks, landed 86 and broke 188 blocks fails that standard by a wide
     * margin, and until this was printed no number here showed it at all.
     */
    private String qualityNote() {
        long[] q = princeps.getBuilderProcess().placementQuality();
        long[] execution = princeps.getBuilderProcess().executionQuality();
        String fidelity = execution.length < 2 ? ""
                : String.format(java.util.Locale.ROOT, " divergences=%d replans=%d", execution[0], execution[1]);
        if (q[0] == 0) {
            return fidelity;
        }
        return String.format(java.util.Locale.ROOT, " clicks=%d landed=%d wrong=%d broke=%d firsttry=%.0f%%",
                q[0], q[1], q[2], q[3], 100.0D * q[1] / q[0]) + fidelity;
    }

    private String checkpointNote() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CHECKPOINT_TICKS.length; i++) {
            if (checkpointPlaced[i] >= 0) {
                sb.append(" @t").append(CHECKPOINT_TICKS[i] / 1000).append("k=").append(checkpointPlaced[i]);
            }
        }
        return sb.toString();
    }

    private void reportUnsatisfiedByFamily() {
        Map<String, Integer> byBlock = new java.util.LinkedHashMap<>();
        Map<String, Integer> byBlockInLowestLayer = new java.util.LinkedHashMap<>();
        int lowestY = Integer.MAX_VALUE;
        Set<BlockPos> outstanding = remainingCells();
        for (BlockPos p : outstanding) {
            lowestY = Math.min(lowestY, p.getY());
        }
        for (BlockPos p : outstanding) {
            BlockState want = scenario.cells.get(BenchSchematics.Scenario.key(
                    p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ()));
            String id = want == null ? "?"
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(want.getBlock()).toString();
            byBlock.merge(id, 1, Integer::sum);
            if (p.getY() == lowestY) {
                byBlockInLowestLayer.merge(id, 1, Integer::sum);
            }
        }
        logMechanic(tag() + " unsatisfied by block: " + census(byBlock));
        if (lowestY != Integer.MAX_VALUE) {
            logMechanic(tag() + " lowest incomplete layer y=" + lowestY + " (" + byBlockInLowestLayer.values().stream()
                    .mapToInt(Integer::intValue).sum() + " cells): " + census(byBlockInLowestLayer)
                    + " -- a layered build cannot advance past these, so everything above is a consequence");
        }
    }

    /** "minecraft:repeater x81, minecraft:hopper x4", most-missing first. */
    private static String census(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(15)
                .map(e -> e.getKey() + " x" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Post-tick is where the bench reads the world: this tick's block updates have landed, so an independent
     * verification sees the same state a player would.
     *
     * <p>NOTE — this MUST be {@code onPostTick}, not {@code onTick}. MixinMinecraft dispatches the PRE event to
     * {@code onTick} and the POST event to {@code onPostTick} (see GameEventHandler), so an {@code onTick} override
     * that demands {@code EventState.POST} is dead code: it never runs, and it never says so. That silently disabled
     * this entire bench — no samples, no verdict, no complaint — until it was traced back here.
     */
    @Override
    public void onPostTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        if (!running) {
            tickAutoStart();
            return;
        }
        ticks++;
        if (pendingBuildTicks > 0) {
            if (--pendingBuildTicks == 0) {
                logMechanic(tag() + " fill settled, starting the clear");
                princeps.getBuilderProcess().build(scenario.name, scenario, origin);
            }
            return;
        }
        // EVERY tick, before the sampling interval below. Motion and aim are the two things a verdict could never
        // speak to, and they are exactly what an operator watches: a run can finish the box and still look wrong.
        // Sampling them every fiftieth tick would measure every fiftieth movement and call the gaps stillness.
        sampleMotion();
        // No mid-run material handouts. The inventory belongs to the builder once the build starts; every scheme
        // for topping it up behind its back broke a run (see refillMaterials). Demand is provisioned once, up front.
        if (ticks % SAMPLE_INTERVAL != 0) {
            return;
        }
        int remaining = countRemaining();
        // Retiring a cell counts as progress. It is not a placement, but it IS the work set shrinking and the build
        // moving on, and it is exactly what the builder does while grinding through cells it cannot currently reach.
        // Judging only by placements called that a stall and ended the run 30 cells into a convergence that was
        // heading for the retry sweep -- which is where those cells actually get built.
        // Fixed checkpoints, because "placed at the end" is one of the NOISIEST numbers this bench produces and it is
        // the one every past comparison used. Measured across three runs whose builder behaviour was effectively
        // identical, the spread of placed-so-far was: t=20000 11.9%, t=50000 3.0%, t=75000 13.0%, t=100000 3.1%,
        // t=150000 2.7%, end-of-run 8.8%. So t=50000 and t=150000 are the two places worth reading, the second is
        // already close to the plateau, and a difference of less than about 10% at the end is not a result.
        for (int i = 0; i < CHECKPOINT_TICKS.length; i++) {
            if (checkpointPlaced[i] < 0 && ticks >= CHECKPOINT_TICKS[i]) {
                checkpointPlaced[i] = scenario.cellCount() - remaining;
                // Logged HERE as well as on the verdict line, because a run killed by the wall clock never prints a
                // verdict -- which is exactly what happened to the first run these checkpoints were built for.
                logMechanic(tag() + " checkpoint t=" + CHECKPOINT_TICKS[i]
                        + " placed=" + checkpointPlaced[i] + "/" + scenario.cellCount());
            }
        }
        int retired = princeps.getBuilderProcess().retiredCellCount();
        if (remaining < lastRemaining || retired > lastRetired) {
            lastRemaining = Math.min(lastRemaining, remaining);
            lastRetired = retired;
            lastProgressTick = ticks;
        }
        boolean paused = princeps.getBuilderProcess().isPaused();
        boolean active = princeps.getBuilderProcess().isActive();

        // A stationary body and look are idle only when the controller is not mining a concrete live target.
        // Survival cobblestone without a pickaxe takes 200 ticks with a steady aim: run f7815303 removed both
        // helpers (full server-region audit passed), yet this detector cancelled it during the final break.
        // The fixed idle threshold, overall stall/timeout limits and material-aware engine deadline still apply.
        BetterBlockPos here = ctx.playerFeet();
        float yaw = ctx.player().getYRot();
        float pitch = ctx.player().getXRot();
        boolean moved = !here.equals(frozenAt);
        boolean looked = Math.abs(yaw - frozenYaw) > 0.5F || Math.abs(pitch - frozenPitch) > 0.5F;
        boolean mining = princeps.getInputOverrideHandler().getBlockBreakHelper().isBreakingBlock();
        if (moved || looked || mining) {
            frozenAt = here;
            frozenYaw = yaw;
            frozenPitch = pitch;
            frozenSinceTick = ticks;
        } else if (ticks - frozenSinceTick >= FROZEN_ABORT_TICKS) {
            logMechanic(tag() + " frozen for " + (ticks - frozenSinceTick) + " ticks at "
                    + here.x + "," + here.y + "," + here.z + " without moving or looking -- ending the run");
            stop("FROZEN");
            return;
        }

        logMechanic(tag() + " sample t=" + ticks
                + " remaining=" + remaining + "/" + scenario.cellCount()
                + " paused=" + paused
                + " active=" + active
                + " pos=" + ctx.playerFeet().x + "," + ctx.playerFeet().y + "," + ctx.playerFeet().z);

        // Matching the schematic is not the end of a frozen plan. A navigation or placement scaffold may still be
        // ledger-owned and its paired removal deliberately follows the final PLACE. Stopping at remaining==0 used to
        // revoke control one action early, leave that helper in the world, print broke=0 and call it SUCCESS. A clean
        // finish is: no schematic work remains AND the builder has run every cleanup action and gone inactive.
        if (paused) {
            stop("PAUSED");
            return;
        }
        if (!active) {
            long[] execution = princeps.getBuilderProcess().executionQuality();
            boolean faithful = execution.length >= 2 && execution[0] == 0L && execution[1] == 0L;
            String verdict = remaining != 0 ? "INACTIVE" : faithful ? "SUCCESS" : "DIVERGED";
            stop(verdict);
            return;
        }
        if (ticks - lastProgressTick >= STALL_TICKS && !NO_STALL) {
            stop("STALLED");
            return;
        }
        if (remaining == 0) {
            // The schematic is complete but the builder still owns a cleanup action. Keep sampling so the same stall
            // clock also catches a scaffold removal that never confirms.
            return;
        }
    }

    /**
     * Unattended run driver. Waits for the world, prepares a clean flat area with server commands, then starts the
     * scenario named by {@code -Dprinceps.bench}. Runs exactly once per launch.
     */
    private void tickAutoStart() {
        if (autoDone || AUTO_SCENARIO == null || AUTO_SCENARIO.isEmpty()) {
            return;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }
        autoTicks++;
        BenchSchematics.Scenario sc = BenchSchematics.byName(AUTO_SCENARIO);
        if (sc == null) {
            autoDone = true;
            logMechanic(tag() + " unknown scenario '" + AUTO_SCENARIO + "'; known: "
                    + BenchSchematics.scenarioNames());
            return;
        }
        // A FIXED origin, not one derived from wherever the bot happens to stand. An unattended run inherits the
        // previous run's leftovers: the bot ends a scenario standing on top of what it just built, so a
        // player-relative origin drifts upward run after run and two runs of the same scenario are not the same
        // test. The anchor plus the teleport below make every run start from identical ground.
        BlockPos origin = AUTO_ANCHOR.offset(3, 0, 3);
        if (autoTicks == AUTO_TELEPORT_TICK) {
            logMechanic(tag() + " auto-anchor: tp to " + AUTO_ANCHOR.getX() + "," + AUTO_ANCHOR.getY() + ","
                    + AUTO_ANCHOR.getZ());
            sendCommand("gamemode survival");
            sendCommand("tp @s " + AUTO_ANCHOR.getX() + " " + AUTO_ANCHOR.getY() + " " + AUTO_ANCHOR.getZ());
        } else if (autoTicks == AUTO_SETUP_TICK) {
            logMechanic(tag() + " auto-setup for scenario=" + sc.name);
            for (String cmd : setupCommands(sc, origin)) {
                sendCommand(cmd);
            }
            // One materials path, used everywhere: a second copy here drifted out of sync with the real one and was
            // missing the inventory reset that makes a run independent of its predecessors.
            refillMaterials(sc);
        } else if (autoTicks == AUTO_START_TICK) {
            autoDone = true;
            start(sc, origin);
        }
    }

    private void sendCommand(String command) {
        if (ctx.player() != null && ctx.player().connection != null) {
            ctx.player().connection.sendCommand(command);
        }
    }

    /**
     * Cells whose world state does not yet satisfy the schematic — read from the live level, not from the builder.
     *
     * <p>Deliberately NOT {@link BlockStateInterface}: that is the pathfinder's accessor, backed by a chunk snapshot
     * and an on-disk region cache. {@code ctx.world()} is the client's live level, the state the player sees.
     *
     * <p>This is still only the CLIENT's answer. The client applies placements optimistically before the server
     * confirms them, so its level is a prediction, and a prediction is a poor thing to grade a build with even when
     * it happens to be right. The client-side verdict is the fast signal, not the authority;
     * {@link #writeCellManifest} exports what was expected so the driver can settle it against the server, which is.
     * See docs/BENCH.md.
     */
    private Set<BlockPos> remainingCells() {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (Map.Entry<Long, BlockState> e : scenario.cells.entrySet()) {
            long k = e.getKey();
            int lx = (int) ((k >> 32) & 0xFFFF);
            int ly = (int) ((k >> 16) & 0xFFFF);
            int lz = (int) (k & 0xFFFF);
            BlockPos world = origin.offset(lx, ly, lz);
            BlockState now = ctx.world().getBlockState(world);
            // Block-level comparison: the bench asserts the right BLOCK landed. Exact property matching is the
            // builder's own stricter concern; counting a right-block-wrong-property cell as done here would hide
            // orientation regressions, so compare the block AND every property the scenario pinned.
            if (now.getBlock() != e.getValue().getBlock()) {
                out.add(world);
                continue;
            }
            boolean propsMatch = true;
            for (net.minecraft.world.level.block.state.properties.Property<?> p : e.getValue().getProperties()) {
                if (!now.hasProperty(p) || !now.getValue(p).equals(e.getValue().getValue(p))) {
                    // Connection/redstone properties are environment-resolved and legitimately differ; the builder's
                    // own AUTO_RESOLVED set governs correctness there. Only flag the geometry the scenario pinned.
                    if (PINNED_PROPS.contains(p.getName())) {
                        propsMatch = false;
                        break;
                    }
                }
            }
            if (!propsMatch) {
                out.add(world);
            }
        }
        return out;
    }

    /**
     * Write what this run expects, cell by cell, as {@code x y z block[props]} lines that a server-side probe can
     * test with {@code execute if block}.
     *
     * <p>This exists because the in-game verdict is the CLIENT's opinion, and the client's level is a prediction it
     * applies before the server confirms it. Exporting the expectation lets the driver put the same question to the
     * only authority there is, and a run that both agree on is one you can act on unattended. Only the properties
     * the scenario pins are written -- the same set {@link #remainingCells} compares -- so both answer the same
     * question rather than two similar ones.
     */
    private void writeCellManifest() {
        if (RUN_ID.isEmpty()) {
            return; // a manual #bench run has no driver waiting to cross-check it
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, BlockState> e : scenario.cells.entrySet()) {
            long k = e.getKey();
            BlockPos world = origin.offset((int) ((k >> 32) & 0xFFFF), (int) ((k >> 16) & 0xFFFF), (int) (k & 0xFFFF));
            sb.append(world.getX()).append(' ').append(world.getY()).append(' ').append(world.getZ())
                    .append(' ').append(blockPredicate(e.getValue())).append('\n');
        }
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("bench-out");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(RUN_ID + "-cells.txt"),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            logMechanic(tag() + " could not write the cell manifest: " + ex.getMessage());
        }
    }

    /** {@code minecraft:oak_stairs[facing=west,half=bottom]} -- only the properties the scenario pins. */
    private static String blockPredicate(BlockState state) {
        StringBuilder sb = new StringBuilder(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        StringBuilder props = new StringBuilder();
        for (net.minecraft.world.level.block.state.properties.Property<?> p : state.getProperties()) {
            if (!PINNED_PROPS.contains(p.getName())) {
                continue;
            }
            if (props.length() > 0) {
                props.append(',');
            }
            props.append(p.getName()).append('=').append(propertyValueName(p, state));
        }
        if (props.length() > 0) {
            sb.append('[').append(props).append(']');
        }
        return sb.toString();
    }

    private static <T extends Comparable<T>> String propertyValueName(
            net.minecraft.world.level.block.state.properties.Property<T> p, BlockState state) {
        return p.getName(state.getValue(p));
    }

    private int countRemaining() {
        return remainingCells().size();
    }

    /**
     * The commands a driver should run before {@link #start} so the scenario has a fair, repeatable environment.
     *
     * <p>The cleared box is the SAME for every scenario, not sized to the one about to run. Sized-to-fit left the
     * previous scenario's outlying blocks standing whenever a smaller one followed a larger one -- ringbig's 11x11
     * ring outlives a 7-long row -- so what a run met on the ground depended on what had run before it. The box is
     * kept under Minecraft's 32768-block fill limit: 37 x 15 x 37 = 20535.
     */
    /**
     * Solid rock packed around a dig scenario on every side, in blocks, from -Pencase.
     *
     * <p>Zero is the old shape: the cube stands in the open and the bot is teleported onto its bare top face, one
     * step from its first cell. That measures the clearing and nothing else. Real excavation is buried -- a 30-cube
     * sitting inside a 50-cube of rock -- and the bot starts somewhere else entirely, sealed in a two-block pocket
     * at the far edge, with no route to the work but the one it digs. Getting there is a job that fails on its own,
     * and with this at zero it was never once measured.
     */
    private static final int ENCASE = Integer.getInteger("princeps.bench.encase", 0);

    public static String[] setupCommands(BenchSchematics.Scenario scenario, BlockPos origin) {
        int back = 15 + ENCASE; // room behind the origin for the bot to approach and stand, plus the casing
        // Big enough for the synthetic scenarios, and grown to fit a real schematic file, which can be far larger.
        int front = Math.max(21, Math.max(scenario.widthX(), scenario.lengthZ()) + 8 + ENCASE);
        int height = Math.max(14, scenario.heightY() + 6 + ENCASE);
        BlockPos min = origin.offset(-back, 0, -back);
        BlockPos max = origin.offset(front, height, front);

        List<String> commands = new java.util.ArrayList<>();
        commands.add("gamemode survival");
        // A clean slate: clear the build volume, then lay a solid floor one below the origin. Minecraft refuses a
        // fill over 32768 blocks, so a large area goes out as horizontal slabs rather than one command that the
        // server would reject in silence -- leaving the previous build standing inside the "cleared" area.
        int width = max.getX() - min.getX() + 1;
        int depth = max.getZ() - min.getZ() + 1;
        int slab = Math.max(1, 32768 / Math.max(1, width * depth));
        for (int y = origin.getY(); y <= max.getY(); y += slab) {
            int top = Math.min(max.getY(), y + slab - 1);
            commands.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d air",
                    min.getX(), y, min.getZ(), max.getX(), top, max.getZ()));
        }
        commands.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d stone",
                min.getX(), origin.getY() - 1, min.getZ(), max.getX(), origin.getY() - 1, max.getZ()));
        // A dig scenario wants every cell to be AIR, so on a freshly cleared plot it would be finished before it
        // began. Fill its volume with the material it is meant to remove -- that IS the scenario.
        if (scenario.name.startsWith("dig")) {
            // Fill the pit, and note WHERE it is: digOrigin puts its TOP layer at the player's own level, so
            // the bot stands ON the volume and works downward. That is what excavating a box means, and it is
            // what the client's users do.
            //
            // Placed the naive way -- at the player's feet, extending fifteen blocks UP -- the bot faces a
            // solid wall taller than itself. It removes the handful of cells within reach and then has
            // nowhere to go, because a solid cube cannot be walked into. Measured: 9 of 3375 cells, then 97
            // seconds motionless with the view reversing on 98% of ticks. That is a scenario defect that
            // looks exactly like an engine defect, which is the most expensive kind.
            commands.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d stone",
                    origin.getX(), origin.getY(), origin.getZ(),
                    origin.getX() + scenario.widthX() - 1,
                    origin.getY() + scenario.heightY() - 1,
                    origin.getZ() + scenario.lengthZ() - 1));
            // Stand the bot ON TOP of the block it is meant to remove. An excavation is entered from above:
            // you stand on the volume and work down, and each cell you remove is a step further in.
            //
            // Left at its feet, beside a fifteen-block wall, it has nowhere to go the moment the cells within
            // arm's reach are gone -- solid rock cannot be walked into. Measured that way: 9 of 3375 cells,
            // then 97 seconds motionless with the view reversing on 98% of ticks, IDENTICALLY with and
            // without the aim hold. A scenario defect that reads exactly like an engine defect.
            //
            // Below is not an option here: the bench world is flat with its floor at y=-64 and the bot spawns
            // near y=-60, so a fifteen-deep pit would be dug out of the void.
            // AN OPEN PIT MEASURES HALF THE JOB. With no casing the bot is set down on the cube's bare top face,
            // already touching its first cell, and everything about GETTING to the work goes unmeasured -- which is
            // precisely the half a customer reported broken.
            if (ENCASE <= 0) {
                commands.add(String.format(java.util.Locale.ROOT, "tp @s %d %d %d",
                        origin.getX() + scenario.widthX() / 2,
                        origin.getY() + scenario.heightY(),
                        origin.getZ() + scenario.lengthZ() / 2));
            } else {
                // The cube sits at the centre of a far larger solid body -- a 30-cube inside a 50-cube of rock --
                // so it is buried on every side, the way real material is. Slabbed for the same reason as the clear
                // above: one fill over 32768 blocks is refused in silence, and a silently skipped fill leaves a
                // scenario that only LOOKS buried while the bot strolls in through a wall that was never placed.
                int bx0 = origin.getX() - ENCASE;
                int bx1 = origin.getX() + scenario.widthX() - 1 + ENCASE;
                int bz0 = origin.getZ() - ENCASE;
                int bz1 = origin.getZ() + scenario.lengthZ() - 1 + ENCASE;
                int by0 = origin.getY();
                int by1 = origin.getY() + scenario.heightY() - 1 + ENCASE;
                int bodyWidth = bx1 - bx0 + 1;
                int bodyDepth = bz1 - bz0 + 1;
                int bodySlab = Math.max(1, 32768 / Math.max(1, bodyWidth * bodyDepth));
                for (int y = by0; y <= by1; y += bodySlab) {
                    int top = Math.min(by1, y + bodySlab - 1);
                    commands.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d stone",
                            bx0, y, bz0, bx1, top, bz1));
                }
                // A sealed two-block pocket one block inside the body's edge: exactly enough room to stand, no
                // route out that was not mined. Deliberately NOT level with the first cell -- the start is up and
                // across from here, so the approach has to solve both directions instead of walking a straight line.
                int pocketX = bx0 + 1;
                int pocketZ = (bz0 + bz1) / 2;
                int pocketY = Math.max(origin.getY(), origin.getY() + scenario.heightY() - 7);
                commands.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d air",
                        pocketX, pocketY, pocketZ, pocketX, pocketY + 1, pocketZ));
                commands.add(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f",
                        pocketX + 0.5D, pocketY, pocketZ + 0.5D));
            }
        }
        if ("ringresume".equals(scenario.name)) {
            int x0 = origin.getX();
            int x1 = origin.getX() + scenario.widthX() - 1;
            int y0 = origin.getY();
            int y1 = origin.getY() + 1;
            int z0 = origin.getZ();
            int z1 = origin.getZ() + scenario.lengthZ() - 1;
            String glass = "minecraft:black_stained_glass";
            // Four idempotent edges, corners intentionally overlap. The first two layers are the saved build; the
            // third remains absent and is the work this resume run must finish.
            commands.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d %s",
                    x0, y0, z0, x1, y1, z0, glass));
            commands.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d %s",
                    x0, y0, z1, x1, y1, z1, glass));
            commands.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d %s",
                    x0, y0, z0, x0, y1, z1, glass));
            commands.add(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d %s",
                    x1, y0, z0, x1, y1, z1, glass));
            // Start on the floor inside the sealed ring, centred in the block. There is no mutation-free route to
            // the top work surface; the access scaffold is therefore required rather than merely preferred.
            commands.add(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f",
                    x0 + 9.5D, y0, z0 + 2.5D));
        }
        return commands.toArray(new String[0]);
    }
}
