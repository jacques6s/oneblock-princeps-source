/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process.builder.bench;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import princeps.Princeps;
import princeps.api.Settings;
import princeps.api.event.events.TickEvent;
import princeps.api.utils.Helper;
import princeps.behavior.Behavior;

/**
 * Puts the bench into the state AutoDig's Shard-Pickaxe mode runs in: a three-high band, a three-wide swing, and a
 * pickaxe the server recognises as the area tool.
 *
 * <p>Separate from {@code BuilderBench} on purpose. That file is one of the owner's protected bench files, and the
 * settings this needs are three assignments -- not a reason to touch a file whose hash is pinned. Everything here
 * is a set-and-forget nudge applied a tick after the scenario has started, which works because the builder reads
 * {@code areaBreakSize} fresh on every tick rather than caching it at start.
 *
 * <p>Inert unless {@code -Dprinceps.bench.areatool=true} is passed. The pickaxe's name has to match what
 * {@code princeps.bench.areapick} tells the server-side mixin to look for, or the swing clears one block and the
 * whole measurement quietly becomes a test of an ordinary pickaxe.
 */
public final class AreaPickBenchDriver extends Behavior implements Helper {

    private static final boolean ENABLED = Boolean.getBoolean("princeps.bench.areatool");
    private static final String RUN_ID = System.getProperty("princeps.bench.run", "");

    /** Matches the default of {@code princeps.bench.areapick}; both sides must agree on the name. */
    private static final String TOOL_NAME = System.getProperty("princeps.bench.areapick", "Shard Pickaxe");
    private static final boolean DAMAGE_SLICES = Boolean.getBoolean("princeps.bench.areaholes");
    private static final boolean DAMAGE_FLOOR = Boolean.getBoolean("princeps.bench.areafloorholes");
    private static final boolean STOCK_CLEANUP_PICK = Boolean.getBoolean("princeps.bench.areacleanpick");
    /** Cavities, delayed gravel, fluid sources, and isolated holes in an encased excavation shell. */
    private static final boolean DIG_MESS = Boolean.getBoolean("princeps.bench.digmess");
    /** Owner-requested visible smoke world: 30 wide, 12 high, with only gravel, six sources, and one cavity. */
    private static final boolean SIMPLE_30X12 = Boolean.getBoolean("princeps.bench.simple30x12");
    /** Moderate follow-up: twice the isolated fluids plus deliberate side-wall and roof defects. */
    private static final boolean SIMPLE_INTEGRITY_STRESS =
            Boolean.getBoolean("princeps.bench.integritystress");
    /** Release-candidate stress: clustered sources and exactly thirty percent of every side wall missing. */
    private static final boolean CLUSTER_INTEGRITY_STRESS =
            Boolean.getBoolean("princeps.bench.clusterstress");
    /**
     * Start the way an owner starts: standing INSIDE the box, at its floor, not set down on its roof.
     *
     * <p>Every fixture until now teleported the bot onto the top face, which is the one starting position the
     * feature is never given in practice -- the box is dragged around the player and enter is pressed, so the bot
     * begins at the bottom of a volume that is worked from the top. That difference is not cosmetic: it is the
     * whole approach phase, and it had never once been executed on this bench.
     */
    private static final boolean OWNER_START = Boolean.getBoolean("princeps.bench.ownerstart");

    private static final int SIMPLE_WIDTH = 30;
    private static final int SIMPLE_HEIGHT = 12;
    private static final int SIMPLE_LENGTH = 30;
    /** 3x3x3 cube + 2x2 horizontal footprint + two 1x3 horizontal strips, expressed as x/y/z extents. */
    private static final int CLUSTER_SOURCES_PER_FLUID = (3 * 3 * 3) + (2 * 1 * 2) + (2 * 3 * 1 * 1);
    private static final int CLUSTER_SIDE_GAPS =
            (2 * (SIMPLE_WIDTH + SIMPLE_LENGTH) * SIMPLE_HEIGHT * 30) / 100;

    private static final int FIRST_APPLY_TICK = 40;
    /** BuilderBench fills at auto tick 160 and starts the clear twenty ticks later. */
    private static final int DAMAGE_TICK = 170;

    /**
     * How often the tool is checked again.
     *
     * <p>Arming once is not enough, and that cost a whole run: the scenario setup issues its own
     * {@code item replace ... diamond_pickaxe} with no custom name, and whichever of the two lands last wins.
     * Run snake0001 armed the tool at tick 40, was overwritten at scenario start, and then mined 147 cells one
     * block at a time -- a clean SUCCESS at 200 blocks/min that measured an ordinary pickaxe. A name that is
     * checked every second cannot be quietly taken away like that.
     */
    private static final int RECHECK_TICKS = 4;

    /**
     * How long a fixture census has to hold still before it is believed.
     *
     * <p>Setting a fixture up is a burst of several dozen server commands, and the client learns their results over
     * many ticks. Comparing the count against its target the moment it happens to match is a race against that burst,
     * won or lost by fixture size -- the bigger the world, the likelier a run starts half-built and the result gets
     * blamed on the bot. A count that has not moved for a second is a count the server has finished sending,
     * whatever the fixture's size and whatever the tick rate.
     */
    private static final int CENSUS_STABLE_TICKS = 20;

    /**
     * Ceiling on fixture readiness. A fixture that never converges has to FAIL, out loud, naming the mismatch.
     *
     * <p>Waiting on an exact count with no deadline turns any fixture defect -- one stray source, one wall cut the
     * server refused -- into a silent hang that reads like the bot doing nothing, which is the most expensive way
     * for a test to be wrong.
     */
    private static final int FIXTURE_READY_DEADLINE_TICKS = 2400;

    private int ticks;
    private boolean settingsApplied;
    private int armings;
    private boolean groundSettled;
    private double startY = Double.NaN;
    private boolean damageApplied;
    private boolean delayedGravityApplied;
    private boolean integrityAuditPrinted;
    private boolean simpleFixturePrepared;
    private boolean simpleFixtureStarted;
    /** Read by the bench exit thread, so the halt can wait for this census instead of racing it. */
    private volatile boolean simpleAuditPrinted;
    private int simplePrepareTick = -1;
    private int expectedWaterSources;
    private int expectedLavaSources;
    private int expectedSideGaps;
    private boolean expectShell;
    private String lastCensusSignature = "";
    private int censusStableSince = -1;
    private boolean simpleProductionProfileLogged;
    private int clusterReadyWaterSources = -1;
    private int clusterReadyLavaSources = -1;
    private int clusterReadyOtherSources = -1;
    private int clusterReadySideGaps = -1;
    private BenchSchematics.Scenario pendingSimpleScenario;
    private BlockPos pendingSimpleOrigin;

    private static BlockPos scenarioOrigin() {
        String raw = System.getProperty("princeps.bench.anchor", "64,-60,64");
        String[] parts = raw.split(",");
        if (parts.length == 3) {
            try {
                return new BlockPos(Integer.parseInt(parts[0].trim()) + 3,
                        Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()) + 3);
            } catch (NumberFormatException ignored) {
                // Keep the bench deterministic even when an unrelated anchor override is malformed.
            }
        }
        return new BlockPos(67, -60, 67);
    }

    private static String benchTag() {
        return RUN_ID.isEmpty() ? "[BENCH]" : "[BENCH] run=" + RUN_ID;
    }

    /**
     * Replaces the tiny bootstrap scenario after its automatic start with the exact visible fixture requested by
     * the owner. Keeping this in the unprotected driver avoids adding a one-off scenario to the pinned bench core.
     */
    private void startSimple30x12Fixture() {
        if (!SIMPLE_30X12 || simpleFixturePrepared || ticks < DAMAGE_TICK + 10
                || !princeps.getBuilderBench().isRunning()) {
            return;
        }
        simpleFixturePrepared = true;
        simplePrepareTick = ticks;
        // HAND THE BENCH OVER, do not merely revoke the builder. Revoking left the generic bootstrap run alive and
        // still grading: one sample later it saw an inactive builder over its own unfinished scenario, called the run
        // INACTIVE and halted the JVM in the middle of these setup commands. The bigger the fixture, the more
        // reliably it lost that race -- so the stress profiles were the ones that could never finish setting up.
        princeps.getBuilderBench().suspendForHandover("simple30x12 fixture");
        // A verdict is not the last thing this run has to say; hold the unattended exit for the closing census.
        BuilderBench.holdExitUntil("simple30x12 integrity census", () -> simpleAuditPrinted);
        BlockPos o = scenarioOrigin();
        BenchSchematics.Scenario scenario = BenchSchematics.dig(SIMPLE_WIDTH, SIMPLE_HEIGHT, SIMPLE_LENGTH);

        for (String command : BuilderBench.setupCommands(scenario, o)) {
            ctx.player().connection.sendCommand(command);
        }

        int top = o.getY() + SIMPLE_HEIGHT - 1;

        // setupCommands creates a side pocket for buried generic dig benches. This test measures the normal top
        // entry instead, so restore that pocket and open only the two-block player space above one intact top cell.
        int encase = Integer.getInteger("princeps.bench.encase", 0);
        if (encase > 0) {
            int bx0 = o.getX() - encase;
            int bx1 = o.getX() + SIMPLE_WIDTH - 1 + encase;
            int bz0 = o.getZ() - encase;
            int bz1 = o.getZ() + SIMPLE_LENGTH - 1 + encase;
            int pocketX = bx0 + 1;
            int pocketZ = (bz0 + bz1) / 2;
            int pocketY = Math.max(o.getY(), o.getY() + SIMPLE_HEIGHT - 7);
            ctx.player().connection.sendCommand("fill " + pocketX + " " + pocketY + " " + pocketZ + " "
                    + pocketX + " " + (pocketY + 1) + " " + pocketZ + " stone");
        }
        // The snake starts one cell in from the nearer pair of outer edges. Put the opening on that exact route
        // cell; a centre opening would require walking across the sealed roof, which the corridor policy correctly
        // refuses to mine or detour around.
        int entryX = o.getX() + SIMPLE_WIDTH - 2;
        int entryZ = o.getZ() + SIMPLE_LENGTH - 2;
        if (!OWNER_START) {
            ctx.player().connection.sendCommand("setblock " + entryX + " " + (top + 1) + " " + entryZ + " air");
            ctx.player().connection.sendCommand("setblock " + entryX + " " + (top + 2) + " " + entryZ + " air");
        } else {
            // A two-block pocket on the box FLOOR with the bot standing in it, roof intact. This is the owner's
            // own start: inside his own tunnel, eleven blocks under the first band.
            ctx.player().connection.sendCommand("fill " + startX(o) + " " + o.getY() + " " + startZ(o) + " "
                    + startX(o) + " " + (o.getY() + 1) + " " + startZ(o) + " air");
        }

        // One fully internal 6x6x6 ravine. It intersects several lane passes but touches neither wall nor roof.
        ctx.player().connection.sendCommand("fill " + (o.getX() + 12) + " " + (o.getY() + 3) + " "
                + (o.getZ() + 12) + " " + (o.getX() + 17) + " " + (o.getY() + 8) + " "
                + (o.getZ() + 17) + " air");

        int waterSources;
        int lavaSources;
        if (CLUSTER_INTEGRITY_STRESS) {
            // One 3x3x3 cube (27 sources), one flat 2x2 patch, and two separate 1x3 strips PER fluid. All are
            // enclosed in ordinary stone and clear of the 6x6x6 cavity, so they begin flowing only when the normal
            // snake exposes them. That measures the production rule -- seal the newly exposed top surface now --
            // rather than how much water can spread during fixture setup.
            fillRelative(o, 3, 8, 4, 5, 10, 6, "water");
            fillRelative(o, 20, 2, 4, 21, 2, 5, "water");
            fillRelative(o, 8, 6, 25, 10, 6, 25, "water");
            fillRelative(o, 20, 9, 10, 22, 9, 10, "water");

            fillRelative(o, 23, 5, 22, 25, 7, 24, "lava");
            fillRelative(o, 5, 3, 22, 6, 3, 23, "lava");
            fillRelative(o, 4, 1, 12, 6, 1, 12, "lava");
            fillRelative(o, 24, 10, 15, 26, 10, 15, "lava");
            waterSources = CLUSTER_SOURCES_PER_FLUID;
            lavaSources = CLUSTER_SOURCES_PER_FLUID;
        } else {
            // Every source is isolated, separated from the cavity, and initially enclosed by ordinary stone. The
            // moderate stress profile doubles the count without turning a movement test into an artificial ocean.
            int[][] water = SIMPLE_INTEGRITY_STRESS
                    ? new int[][] {{4, 10, 5}, {23, 7, 7}, {8, 2, 24},
                                   {18, 10, 10}, {27, 7, 15}, {10, 4, 6}}
                    : new int[][] {{4, 10, 5}, {23, 7, 7}, {8, 2, 24}};
            int[][] lava = SIMPLE_INTEGRITY_STRESS
                    ? new int[][] {{25, 9, 22}, {5, 5, 20}, {21, 1, 4},
                                   {2, 8, 16}, {19, 5, 25}, {26, 2, 10}}
                    : new int[][] {{25, 9, 22}, {5, 5, 20}, {21, 1, 4}};
            for (int[] at : water) {
                ctx.player().connection.sendCommand("setblock " + (o.getX() + at[0]) + " "
                        + (o.getY() + at[1]) + " " + (o.getZ() + at[2]) + " water");
            }
            for (int[] at : lava) {
                ctx.player().connection.sendCommand("setblock " + (o.getX() + at[0]) + " "
                        + (o.getY() + at[1]) + " " + (o.getZ() + at[2]) + " lava");
            }
            waterSources = water.length;
            lavaSources = lava.length;
        }
        expectedWaterSources = waterSources;
        expectedLavaSources = lavaSources;

        if (CLUSTER_INTEGRITY_STRESS && encase > 0) {
            // Each face contains 30*12=360 cells. Three separated three-column, full-height cuts remove 108 cells
            // from each face: exactly 30%, or 432 of the 1,440 side-wall cells in total. Twelve fill commands keep
            // setup reliable under the server's packet/spam limits while still distributing the damage.
            int[][] zCuts = {{2, 4}, {12, 14}, {23, 25}};
            for (int[] cut : zCuts) {
                fillRelative(o, -1, 0, cut[0], -1, SIMPLE_HEIGHT - 1, cut[1], "air");
                fillRelative(o, SIMPLE_WIDTH, 0, cut[0], SIMPLE_WIDTH, SIMPLE_HEIGHT - 1, cut[1], "air");
            }
            int[][] xCuts = {{5, 7}, {16, 18}, {26, 28}};
            for (int[] cut : xCuts) {
                fillRelative(o, cut[0], 0, -1, cut[1], SIMPLE_HEIGHT - 1, -1, "air");
                fillRelative(o, cut[0], 0, SIMPLE_LENGTH, cut[1], SIMPLE_HEIGHT - 1,
                        SIMPLE_LENGTH, "air");
            }
        } else if (SIMPLE_INTEGRITY_STRESS && encase > 0) {
            // Twelve non-corner side-wall defects, spread over every 3-high band and all four faces. They are air,
            // not special marker blocks: the production rule must repair shell STATE, not recognise fixture material.
            int[][] sideGaps = {
                    {-1, 10, 4}, {-1, 7, 21}, {-1, 4, 8},
                    {SIMPLE_WIDTH, 9, 24}, {SIMPLE_WIDTH, 6, 5}, {SIMPLE_WIDTH, 2, 18},
                    {6, 10, -1}, {19, 5, -1}, {26, 1, -1},
                    {3, 8, SIMPLE_LENGTH}, {15, 4, SIMPLE_LENGTH}, {24, 2, SIMPLE_LENGTH}
            };
            for (int[] gap : sideGaps) {
                ctx.player().connection.sendCommand("setblock " + (o.getX() + gap[0]) + " "
                        + (o.getY() + gap[1]) + " " + (o.getZ() + gap[2]) + " air");
            }
        }
        if ((SIMPLE_INTEGRITY_STRESS || CLUSTER_INTEGRITY_STRESS) && encase > 0) {
            int[][] roofGaps = {{2, 4}, {8, 22}, {14, 7}, {20, 25}, {25, 12}, {23, 18}};
            for (int[] gap : roofGaps) {
                ctx.player().connection.sendCommand("setblock " + (o.getX() + gap[0]) + " " + (top + 1)
                        + " " + (o.getZ() + gap[1]) + " air");
            }
        }

        expectShell = encase > 0;
        // No casing means no shell at all: every side cell reads as a gap and the repair half of this fixture
        // measures nothing. Carry that in the expectation so the readiness line says it instead of grading a wall
        // that was never built.
        expectedSideGaps = !expectShell
                ? 2 * (SIMPLE_WIDTH + SIMPLE_LENGTH) * SIMPLE_HEIGHT
                : CLUSTER_INTEGRITY_STRESS ? CLUSTER_SIDE_GAPS
                : SIMPLE_INTEGRITY_STRESS ? 12
                : 0;

        // Gravel stands a few blocks above intact top cells. It cannot fall during setup; removing the support while
        // clearing the top band drops it back into the completed level and exercises the pre-descent re-walk.
        int[][] gravelColumns = {{3, 4, 3}, {10, 25, 2}, {24, 10, 4}, {27, 27, 2}};
        for (int[] column : gravelColumns) {
            ctx.player().connection.sendCommand("fill " + (o.getX() + column[0]) + " " + (top + 1) + " "
                    + (o.getZ() + column[1]) + " " + (o.getX() + column[0]) + " "
                    + (top + column[2]) + " " + (o.getZ() + column[1]) + " gravel");
        }

        // The feature assumes ample disposable full blocks. Stock several independent stacks so a hotbar swap or a
        // long ravine cannot turn route correctness into an inventory-capacity test.
        for (int slot = 2; slot < 9; slot++) {
            ctx.player().connection.sendCommand("item replace entity @s hotbar." + slot
                    + " with minecraft:cobblestone 64");
        }
        for (int slot = 0; slot < 27; slot++) {
            ctx.player().connection.sendCommand("item replace entity @s inventory." + slot
                    + " with minecraft:cobblestone 64");
        }
        ctx.player().connection.sendCommand("item replace entity @s hotbar.1 with "
                + "minecraft:diamond_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5},"
                + "minecraft:unbreakable={},minecraft:custom_name='{\"text\":\"Cleanup Pickaxe\","
                + "\"italic\":false}'] 1");
        if (OWNER_START) {
            ctx.player().connection.sendCommand("tp @s " + (startX(o) + 0.5D) + " " + o.getY() + " "
                    + (startZ(o) + 0.5D));
        } else {
            ctx.player().connection.sendCommand("tp @s " + (entryX + 0.5D) + " " + (top + 1) + " "
                    + (entryZ + 0.5D));
        }

        // Do not start from a fixed tick delay. At 3x client time, twenty client ticks can elapse before the server's
        // command chain and inventory packets have arrived. releaseSimple30x12Fixture observes the actual final
        // command state instead; this remains correct at every tick rate and under ordinary packet jitter.
        pendingSimpleScenario = scenario;
        pendingSimpleOrigin = o;
        logDirect(benchTag() + " SIMPLE-AUTODIG ownerStart=" + OWNER_START
                + " preparing fixture=30x12x30 stone=true cavity=6x6x6"
                + " sources=" + waterSources + "water+" + lavaSources + "lava gravelColumns=4 topEntry=true"
                + " sideGaps=" + (CLUSTER_INTEGRITY_STRESS ? CLUSTER_SIDE_GAPS
                        : SIMPLE_INTEGRITY_STRESS ? 12 : 0)
                + " roofGaps=" + ((SIMPLE_INTEGRITY_STRESS || CLUSTER_INTEGRITY_STRESS) ? 6 : 0)
                + " clusterStress=" + CLUSTER_INTEGRITY_STRESS);
    }

    /** Centre column of the box: where an owner stands when he drags the box around himself. */
    private static int startX(BlockPos origin) {
        return origin.getX() + SIMPLE_WIDTH / 2;
    }

    private static int startZ(BlockPos origin) {
        return origin.getZ() + SIMPLE_LENGTH / 2;
    }

    private void fillRelative(BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        ctx.player().connection.sendCommand("fill " + (origin.getX() + x0) + " " + (origin.getY() + y0) + " "
                + (origin.getZ() + z0) + " " + (origin.getX() + x1) + " " + (origin.getY() + y1) + " "
                + (origin.getZ() + z1) + " " + block);
    }

    /** Releases AutoDig only after the client has observed the last world/inventory commands of the fixture. */
    private void releaseSimple30x12Fixture() {
        if (!SIMPLE_30X12 || !simpleFixturePrepared || simpleFixtureStarted || simpleAuditPrinted
                || pendingSimpleScenario == null || pendingSimpleOrigin == null) {
            return;
        }
        BlockPos o = pendingSimpleOrigin;
        int top = o.getY() + SIMPLE_HEIGHT - 1;
        int entryX = o.getX() + SIMPLE_WIDTH - 2;
        int entryZ = o.getZ() + SIMPLE_LENGTH - 2;
        boolean worldReady = OWNER_START
                ? ctx.world().getBlockState(new BlockPos(startX(o), o.getY(), startZ(o))).isAir()
                        && ctx.world().getBlockState(new BlockPos(startX(o), o.getY() + 1, startZ(o))).isAir()
                        && !ctx.world().getBlockState(new BlockPos(entryX, top + 1, entryZ)).isAir()
                : !ctx.world().getBlockState(new BlockPos(entryX, top, entryZ)).isAir()
                        && ctx.world().getBlockState(new BlockPos(entryX, top + 1, entryZ)).isAir()
                        && ctx.world().getBlockState(new BlockPos(entryX, top + 2, entryZ)).isAir();
        boolean throwawayReady = ctx.player().getInventory().getNonEquipmentItems().stream()
                .anyMatch(stack -> !stack.isEmpty()
                        && stack.getItem() == net.minecraft.world.level.block.Blocks.COBBLESTONE.asItem());
        double wantX = OWNER_START ? startX(o) + 0.5D : entryX + 0.5D;
        double wantZ = OWNER_START ? startZ(o) + 0.5D : entryZ + 0.5D;
        boolean atEntry = Math.abs(ctx.player().getX() - wantX) < 0.75D
                && Math.abs(ctx.player().getZ() - wantZ) < 0.75D
                && (OWNER_START ? ctx.player().getY() <= o.getY() + 0.1D : ctx.player().getY() >= top + 0.9D);

        // Believe the census only once it stops moving. THAT, and not an exact match at an arbitrary instant, is
        // what separates "the server has finished building the fixture" from "the counts lined up mid-burst".
        // It also makes every profile equal: the small run is read exactly the way the stress run is.
        int[] census = fixtureCensus(o, top);
        String signature = census[0] + "/" + census[1] + "/" + census[2] + "/" + census[3];
        if (!signature.equals(lastCensusSignature)) {
            lastCensusSignature = signature;
            censusStableSince = ticks;
        }
        boolean settled = censusStableSince >= 0 && ticks - censusStableSince >= CENSUS_STABLE_TICKS;
        boolean asBuilt = census[0] == expectedWaterSources && census[1] == expectedLavaSources
                && census[2] == 0 && census[3] == expectedSideGaps;

        if (!(worldReady && throwawayReady && atEntry && settled && asBuilt)) {
            if (simplePrepareTick < 0 || ticks - simplePrepareTick < FIXTURE_READY_DEADLINE_TICKS) {
                return;
            }
            // The fixture never became what it was asked to be. That is a failed run with a named cause, not a
            // reason to keep waiting -- and it is said in the shapes the launcher already listens for, so a broken
            // fixture ends the run in seconds instead of burning the whole scenario timeout while looking exactly
            // like a bot that does nothing.
            simpleAuditPrinted = true;
            String expectation = expectedWaterSources + "/" + expectedLavaSources + "/0/" + expectedSideGaps;
            logDirect(benchTag() + " SIMPLE-AUTODIG-FIXTURE FAIL world=" + worldReady
                    + " throwaway=" + throwawayReady + " atEntry=" + atEntry + " settled=" + settled
                    + " census=" + signature + " expected=" + expectation + " shell=" + expectShell);
            logDirect(benchTag() + " verdict=FIXTUREFAIL scenario=simple30x12 placed=0/"
                    + pendingSimpleScenario.cellCount() + " remaining=" + pendingSimpleScenario.cellCount()
                    + " ticks=" + ticks + " blocksPerMin=0.0");
            logDirect(benchTag() + " SIMPLE-AUTODIG-INTEGRITY FAIL reason=fixture-never-converged"
                    + " census=" + signature + " expected=" + expectation);
            return;
        }

        clusterReadyWaterSources = census[0];
        clusterReadyLavaSources = census[1];
        clusterReadyOtherSources = census[2];
        clusterReadySideGaps = census[3];
        simpleFixtureStarted = true;
        princeps.getBuilderBench().startPrepared(pendingSimpleScenario, pendingSimpleOrigin);
        pendingSimpleScenario = null;
        pendingSimpleOrigin = null;
        logDirect(benchTag() + " SIMPLE-AUTODIG fixture settled after " + (ticks - simplePrepareTick)
                + " ticks; releasing AutoDig readinessSources="
                + (clusterReadyWaterSources + clusterReadyLavaSources + clusterReadyOtherSources)
                + " (water=" + clusterReadyWaterSources + ",lava=" + clusterReadyLavaSources
                + ",other=" + clusterReadyOtherSources + ") readinessSideGaps=" + clusterReadySideGaps
                + " expected=" + expectedWaterSources + "/" + expectedLavaSources + "/0/" + expectedSideGaps);
    }

    /**
     * What the client currently believes the fixture is: water sources, lava sources, any other source, shell gaps.
     *
     * <p>Taken every tick while a fixture is pending, so readiness can wait for the number to hold still instead of
     * racing the server's setup burst -- a race the client loses more often the larger the fixture is.
     */
    private int[] fixtureCensus(BlockPos origin, int top) {
        int waterSources = 0;
        int lavaSources = 0;
        int otherSources = 0;
        for (int y = origin.getY(); y <= top; y++) {
            for (int x = origin.getX(); x < origin.getX() + SIMPLE_WIDTH; x++) {
                for (int z = origin.getZ(); z < origin.getZ() + SIMPLE_LENGTH; z++) {
                    BlockState state = ctx.world().getBlockState(new BlockPos(x, y, z));
                    if (!state.getFluidState().isSource()) {
                        continue;
                    }
                    if (state.getFluidState().getType() == Fluids.WATER) {
                        waterSources++;
                    } else if (state.getFluidState().getType() == Fluids.LAVA) {
                        lavaSources++;
                    } else {
                        otherSources++;
                    }
                }
            }
        }
        return new int[] {waterSources, lavaSources, otherSources, countSimpleSideGaps(origin, top)};
    }

    private int countSimpleSideGaps(BlockPos origin, int top) {
        int gaps = 0;
        for (int y = origin.getY(); y <= top; y++) {
            for (int n = 0; n < SIMPLE_LENGTH; n++) {
                if (shellGap(origin.getX() - 1, y, origin.getZ() + n)) gaps++;
                if (shellGap(origin.getX() + SIMPLE_WIDTH, y, origin.getZ() + n)) gaps++;
            }
            for (int n = 0; n < SIMPLE_WIDTH; n++) {
                if (shellGap(origin.getX() + n, y, origin.getZ() - 1)) gaps++;
                if (shellGap(origin.getX() + n, y, origin.getZ() + SIMPLE_LENGTH)) gaps++;
            }
        }
        return gaps;
    }

    /** Creates damaged walls/floors after the cube exists and before BuilderBench releases the clear. */
    private void damageSlices() {
        // THE SECOND PICKAXE IS NOT PART OF THE DAMAGE TEST, and hiding it behind one made it unreachable.
        //
        // A bot digging with the area tool needs BOTH: the named Shard pickaxe for a full 3x3 slice, and an
        // ordinary one for anything that must take a single block -- the descent, and a leftover at the edge of a
        // damaged slice. Only the second of those has anything to do with damaged slices, and the stocking sat
        // behind the damage flags, so -areaCleanPick alone handed over nothing at all. Measured across five runs:
        // "tool changes: 0" every time, while snakeToolReady quietly fell back to the area tool because
        // snakeOrdinaryPickSlot found no other pickaxe on the bar.
        if (damageApplied || ticks < DAMAGE_TICK) {
            return;
        }
        if (!DAMAGE_SLICES && !DAMAGE_FLOOR && !STOCK_CLEANUP_PICK && !DIG_MESS) {
            return;
        }
        damageApplied = true;
        BlockPos o = scenarioOrigin();
        int top = o.getY() + 14;
        if (DAMAGE_SLICES) {
            int[][] holes = {
                    {1, top - 1, 5},   // first lane, first band
                    {4, top - 1, 9},   // reverse lane, first band
                    {7, top - 4, 6},   // middle lane, second band
                    {10, top - 7, 8},  // fourth lane, third band
                    {13, top - 10, 4}  // final lane, fourth band
            };
            for (int[] hole : holes) {
                ctx.player().connection.sendCommand("setblock " + (o.getX() + hole[0]) + " " + hole[1] + " "
                        + (o.getZ() + hole[2]) + " air");
            }
        }
        if (DAMAGE_FLOOR || DIG_MESS) {
            // Two centre-line support holes plus a full-width trench exercise both the shoulder and retreat paths.
            int supportY = top - 3;
            int[][] floorHoles = {
                    {1, supportY, 5},
                    {1, supportY, 8},
                    {0, supportY, 11},
                    {1, supportY, 11},
                    {2, supportY, 11}
            };
            for (int[] hole : floorHoles) {
                ctx.player().connection.sendCommand("setblock " + (o.getX() + hole[0]) + " " + hole[1] + " "
                        + (o.getZ() + hole[2]) + " air");
            }
        }
        if (DIG_MESS) {
            // Two exposed internal sources: the route must displace the source itself before it can spread across
            // the newly opened slice. Positions include a shoulder cell, not only the centre line.
            ctx.player().connection.sendCommand("setblock " + (o.getX() + 2) + " " + (top - 1) + " "
                    + (o.getZ() + 5) + " water");
            ctx.player().connection.sendCommand("setblock " + (o.getX() + 5) + " " + (top - 4) + " "
                    + (o.getZ() + 9) + " lava");

            // Isolated shell defects. -Dprinceps.bench.encase=1 supplies the surrounding rock; these commands punch
            // only the cells AutoDig has to restore, so the fixture stays realistic and finitely buildable.
            ctx.player().connection.sendCommand("setblock " + (o.getX() - 1) + " " + (top - 1) + " "
                    + (o.getZ() + 4) + " air");
            ctx.player().connection.sendCommand("setblock " + (o.getX() + 15) + " " + (top - 4) + " "
                    + (o.getZ() + 10) + " water");
            ctx.player().connection.sendCommand("setblock " + (o.getX() + 8) + " " + (top - 7) + " "
                    + (o.getZ() - 1) + " lava");
            ctx.player().connection.sendCommand("setblock " + (o.getX() + 3) + " " + (top + 1) + " "
                    + (o.getZ() + 3) + " air");
            ctx.player().connection.sendCommand("setblock " + (o.getX() + 11) + " " + (top + 1) + " "
                    + (o.getZ() + 9) + " water");
            ctx.player().connection.sendCommand("item replace entity @s hotbar.2 with minecraft:cobblestone 64");
        }
        if (STOCK_CLEANUP_PICK || DIG_MESS) {
            ctx.player().connection.sendCommand("item replace entity @s hotbar.1 with "
                    + "minecraft:diamond_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5},"
                    + "minecraft:unbreakable={},minecraft:custom_name='{\"text\":\"Cleanup Pickaxe\","
                    + "\"italic\":false}'] 1");
        }
        logDirect("[BENCH] damage applied: wall-centres=" + DAMAGE_SLICES + ", floor-holes=" + DAMAGE_FLOOR
                + ", ordinary cleanup pick=" + (STOCK_CLEANUP_PICK || DIG_MESS)
                + ", dig-mess=" + DIG_MESS);
    }

    /** Injects gravity debris only after the first three-high band has genuinely become clear. */
    private void injectDelayedGravity() {
        if (!DIG_MESS || !damageApplied || delayedGravityApplied || ticks < DAMAGE_TICK + 20) {
            return;
        }
        BlockPos o = scenarioOrigin();
        int top = o.getY() + 14;
        for (int y = top - 2; y <= top; y++) {
            for (int x = o.getX(); x < o.getX() + 15; x++) {
                for (int z = o.getZ(); z < o.getZ() + 15; z++) {
                    if (!ctx.world().getBlockState(new BlockPos(x, y, z)).isAir()) {
                        return;
                    }
                }
            }
        }
        delayedGravityApplied = true;
        // The resulting world state is exactly what two gravel columns settling late would produce. Injecting the
        // landed cells removes timing noise from the fixture while still requiring the post-band verification pass.
        ctx.player().connection.sendCommand("setblock " + (o.getX() + 1) + " " + top + " "
                + (o.getZ() + 2) + " gravel");
        ctx.player().connection.sendCommand("setblock " + (o.getX() + 10) + " " + (top - 1) + " "
                + (o.getZ() + 12) + " gravel");
        logDirect("[BENCH] delayed gravity debris injected after the top band became clean");
    }

    /** Prints an independent shell/source verdict as soon as the clear volume itself is empty. */
    private void auditIntegrity() {
        if (!DIG_MESS || integrityAuditPrinted || !delayedGravityApplied) {
            return;
        }
        BlockPos o = scenarioOrigin();
        int top = o.getY() + 14;
        int internalSources = 0;
        int remaining = 0;
        for (int y = o.getY(); y <= top; y++) {
            for (int x = o.getX(); x < o.getX() + 15; x++) {
                for (int z = o.getZ(); z < o.getZ() + 15; z++) {
                    BlockState state = ctx.world().getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir()) remaining++;
                    if (state.getFluidState().isSource()) internalSources++;
                }
            }
        }
        if (remaining != 0) {
            return;
        }
        int shellGaps = 0;
        for (int y = o.getY(); y <= top; y++) {
            for (int n = 0; n < 15; n++) {
                if (shellGap(o.getX() - 1, y, o.getZ() + n)) shellGaps++;
                if (shellGap(o.getX() + 15, y, o.getZ() + n)) shellGaps++;
                if (shellGap(o.getX() + n, y, o.getZ() - 1)) shellGaps++;
                if (shellGap(o.getX() + n, y, o.getZ() + 15)) shellGaps++;
            }
        }
        for (int x = o.getX(); x < o.getX() + 15; x++) {
            for (int z = o.getZ(); z < o.getZ() + 15; z++) {
                if (shellGap(x, top + 1, z)) shellGaps++;
            }
        }
        integrityAuditPrinted = true;
        logDirect("[BENCH] AUTODIG-INTEGRITY " + (internalSources == 0 && shellGaps == 0 ? "PASS" : "FAIL")
                + " internalSources=" + internalSources + " shellGaps=" + shellGaps
                + " diagnosis={" + princeps.getBuilderProcess().areaDigDiagnosis() + "}");
    }

    private boolean shellGap(int x, int y, int z) {
        BlockState state = ctx.world().getBlockState(new BlockPos(x, y, z));
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    /** Independent final census for the visible 30x12 run; delayed until BuilderBench has actually judged it. */
    private void auditSimple30x12Fixture() {
        if (!SIMPLE_30X12 || !simpleFixtureStarted || simpleAuditPrinted
                || princeps.getBuilderBench().isRunning()) {
            return;
        }
        BlockPos o = scenarioOrigin();
        int top = o.getY() + SIMPLE_HEIGHT - 1;
        int remaining = 0;
        int sources = 0;
        int fluids = 0;
        for (int y = o.getY(); y <= top; y++) {
            for (int x = o.getX(); x < o.getX() + SIMPLE_WIDTH; x++) {
                for (int z = o.getZ(); z < o.getZ() + SIMPLE_LENGTH; z++) {
                    BlockState state = ctx.world().getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir()) remaining++;
                    if (state.getFluidState().isSource()) sources++;
                    if (!state.getFluidState().isEmpty()) fluids++;
                }
            }
        }
        int sideGaps = countSimpleSideGaps(o, top);
        int roofGaps = 0;
        for (int x = o.getX(); x < o.getX() + SIMPLE_WIDTH; x++) {
            for (int z = o.getZ(); z < o.getZ() + SIMPLE_LENGTH; z++) {
                if (shellGap(x, top + 1, z)) roofGaps++;
            }
        }
        simpleAuditPrinted = true;
        String diagnosis = princeps.getBuilderProcess().areaDigDiagnosis();
        int expectedBands = (SIMPLE_HEIGHT + 2) / 3;
        boolean verifiedEveryBand = diagnosis.contains("verifiedBands=" + expectedBands + "}");
        // A run is only graded against a fixture that was verifiably built, and every profile carries its own
        // expectation, so the small run is held to the same standard as the stress run.
        boolean readinessCensusPassed = clusterReadyWaterSources == expectedWaterSources
                && clusterReadyLavaSources == expectedLavaSources
                && clusterReadyOtherSources == 0
                && clusterReadySideGaps == expectedSideGaps;
        // Repairing the shell can only be required where there is one.
        boolean shellRepaired = !expectShell || (sideGaps == 0 && roofGaps == 0);
        boolean pass = remaining == 0 && sources == 0 && fluids == 0 && shellRepaired
                && verifiedEveryBand && readinessCensusPassed;
        logDirect(benchTag() + " SIMPLE-AUTODIG-INTEGRITY " + (pass ? "PASS" : "FAIL")
                + " remaining=" + remaining + " sources=" + sources + " fluids=" + fluids
                + " sideGaps=" + sideGaps + " roofGaps=" + roofGaps + " shellExpected=" + expectShell
                + " initialSources=" + (clusterReadyWaterSources + clusterReadyLavaSources
                        + clusterReadyOtherSources)
                + " initialSideGaps=" + clusterReadySideGaps
                + " readinessCensus=" + readinessCensusPassed + " verifiedBands=" + verifiedEveryBand
                + " diagnosis={" + diagnosis + "}");
    }

    /**
     * Keeps the bot in the air until the ground it was placed on has actually arrived at the client.
     *
     * <p>The bench fills the scenario's cube and teleports onto its top surface in the SAME second. Server-side
     * that is consistent; client-side the 3375-block update has not been applied yet, so the bot lands on what it
     * still believes is open air and falls. In creative it does not die -- it drops out of the world and stops at
     * y=-157, and from then on every sample reads 3375/3375 with the bot hovering in the void. Four runs were lost
     * to this before the server log showed the fill and the teleport sharing a timestamp.
     *
     * <p>The old world hid it: a leftover cube from a previous run was already there, so the bot always landed on
     * something. Clearing the world for an honest measurement is what exposed the race.
     */
    private void holdAboveGround() {
        if (groundSettled || ctx.player() == null) {
            return;
        }
        if (Double.isNaN(startY)) {
            startY = ctx.player().getY();
        }
        boolean solidBelow = !ctx.world().getBlockState(ctx.player().blockPosition().below()).isAir();
        if (solidBelow) {
            groundSettled = true;
            ctx.player().getAbilities().flying = false;
            return;
        }
        // Falling far below where it started means the ground never arrived in time; hovering alone cannot undo
        // that, so put it back first and hold it there.
        if (ctx.player().getY() < startY - 6.0D) {
            ctx.player().connection.sendCommand("tp @s ~ " + (startY + 1.0D) + " ~");
        }
        ctx.player().getAbilities().flying = true;
    }

    public AreaPickBenchDriver(Princeps princeps) {
        super(princeps);
    }

    @Override
    public void onTick(TickEvent event) {
        if (!ENABLED || event.getType() != TickEvent.Type.IN || ctx.player() == null) {
            return;
        }
        holdAboveGround();
        startSimple30x12Fixture();
        releaseSimple30x12Fixture();
        damageSlices();
        injectDelayedGravity();
        auditIntegrity();
        auditSimple30x12Fixture();
        if (++ticks < FIRST_APPLY_TICK) {
            return;
        }
        // RE-APPLIED, not applied once. The bench writes its own clear profile after this driver's first tick and
        // resets the swing to a single block -- the log shows "clear profile applied (layers=off)" arriving after
        // "Shard-Pickaxe profile". The result reads like a working area-tool run and is not one: the SERVER still clears
        // nine per swing because the tool says so, while the CLIENT plans one block at a time, so neither the
        // three-layer band nor the snake ever engages. Two runs were graded on that illusion.
        Settings settings = Princeps.settings();
        if (SIMPLE_30X12) {
            // The visible fixture grades the production OneBlock profile, not BuilderBench's older shake experiment.
            // Keep enforcing this after startPrepared, whose generic clear profile writes reach=4.0 and may enable
            // aim-hold in the same tick.
            settings.blockReachDistance.value = 4.5F;
            settings.remainWithExistingLookDirection.value = false;
            if (!simpleProductionProfileLogged) {
                simpleProductionProfileLogged = true;
                logDirect(benchTag() + " SIMPLE-AUTODIG production look profile: reach=4.5 aimHold=false");
            }
        }
        princeps.getBuilderProcess().setAreaToolDisplayName(TOOL_NAME);
        if (settings.areaBreakSize.value != 3 || settings.layerHeight.value != 3) {
            settings.layerHeight.value = 3;
            settings.areaBreakSize.value = 3;
            if (!settingsApplied) {
                settingsApplied = true;
                logDirect("[BENCH] shard-pickaxe profile: band=3, swing=3x3");
            }
        }
        if (ticks % 4 != 0) {
            return;
        }
        // ASK ABOUT THE SLOT THIS DRIVER OWNS, NOT ABOUT THE HAND. The engine legitimately selects the ordinary
        // cleanup pickaxe for one-block work (the descent, single leftovers), and a hand-based check reads that as
        // "the tool was taken away" and re-issues an inventory command every four ticks for the rest of the run --
        // the driver fighting the engine over which slot is held, and hundreds of pointless server commands in a
        // measurement that is supposed to be quiet. Slot 0 is the driver's; which slot is HELD is the engine's.
        net.minecraft.world.item.ItemStack armed = ctx.player().getInventory().getItem(0);
        if (!armed.isEmpty() && armed.getHoverName().getString().contains(TOOL_NAME)) {
            return;
        }
        // The name is the entire contract with the server: the mixin swings three-by-three for this name and for
        // nothing else. Re-issued rather than repaired, because the scenario may hand out a fresh stack at any time.
        ctx.player().connection.sendCommand("item replace entity @s hotbar.0 with "
                + "minecraft:diamond_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5},"
                + "minecraft:unbreakable={},minecraft:custom_name='{\"text\":\"" + TOOL_NAME
                + "\",\"italic\":false}'] 1");
        if (++armings <= 3 || armings % 20 == 0) {
            logDirect("[BENCH] area tool armed (" + armings + "x): \"" + TOOL_NAME + "\"");
        }
    }
}
