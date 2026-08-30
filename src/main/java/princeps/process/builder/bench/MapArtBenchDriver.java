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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import princeps.Princeps;
import princeps.api.event.events.TickEvent;
import princeps.api.utils.Helper;
import princeps.behavior.Behavior;

/**
 * Supplies the map-art-only scenarios without modifying the protected generic bench.
 * Inert unless {@code -Dprinceps.bench.mapart} is present.
 */
public final class MapArtBenchDriver extends Behavior implements Helper {

    private static final String SCENARIO = System.getProperty("princeps.bench.mapart", "").trim();
    private static final BlockPos ANCHOR = new BlockPos(64, -60, 64);
    private static final BlockPos ORIGIN = ANCHOR.offset(3, 0, 3);

    /** How far the launch pad is shrunk; 1 leaves the single block the product actually preserves. */
    private static int padShrink() {
        return Integer.getInteger("princeps.bench.mapart.pad", 0);
    }

    /**
     * Where the bot is put down, which is not a free choice once the pad is a single block.
     *
     * <p>The generous pad spans the anchor AND the picture's first cells, so landing on the anchor works. The
     * single-block pad does not: it sits under ORIGIN, three blocks diagonally away, and a teleport to the anchor
     * therefore drops the bot into open air. That is not a hypothetical -- run 57814a36 fell from -60 to -333 at
     * terminal velocity before tick 80, took zero steps, and reported 0/16384. It measured gravity, not the
     * builder, while reading exactly like the product's failure.
     *
     * <p>Landing one ABOVE origin is the product's own geometry: the picture's first cell is the block under the
     * boots, not the block the boots are inside.
     */
    private static BlockPos stanceForPad() {
        return padShrink() == 1 ? ORIGIN.above() : ANCHOR;
    }
    /** Keep setup's wall-clock settling time at least as long as the proven 3x run when client ticks are accelerated. */
    private static final int SETUP_DELAY_FACTOR = Math.max(1, (int) Math.ceil(
            Double.parseDouble(System.getProperty("princeps.bench.timescale", "1.0")) / 3.0));
    private static final int TELEPORT_TICK = 40 * SETUP_DELAY_FACTOR;
    private static final int PREPARE_TICK = 80 * SETUP_DELAY_FACTOR;
    private static final int START_TICK = 160 * SETUP_DELAY_FACTOR;

    private int ticks;
    private boolean started;
    private BenchSchematics.Scenario scenario;
    private int benchScaffoldSlot = -1;
    private boolean stanceSettled;
    private java.util.Map<Item, Integer> demand;
    private int supplyTicks;
    private int deliveries;
    private boolean openingOrderDone;
    private int rescueTicks;
    private int rescues;
    // The stand-in has no auction-house round trip. Check every tick so the direct /give arrives before the builder's
    // ordinary missing-material pause; a one-second poll made a healthy product restock look like a failed benchmark.
    private static final int SUPPLY_INTERVAL_TICKS = 1;

    public MapArtBenchDriver(Princeps princeps) {
        super(princeps);
    }

    /**
     * Guarantees the bot is standing where the scenario wants it, from the very first tick.
     *
     * <p>PREVENTS the fall rather than repairing it, and the distinction is the whole point: a client that is
     * already falling cannot be brought back. It re-asserts its own position every tick, so the server's teleport
     * is overridden immediately -- measured, six runs in a row, every one of them frozen at y between -157 and
     * -417 with RCON teleports reporting success and changing nothing. Recovery is not available; only prevention
     * is.
     *
     * <p>Three things conspire to produce that fall, and hovering answers all three at once. The world is empty
     * until the scenario builds it, so joining anywhere means joining over the void. Chunks arrive later than the
     * teleport that assumes them, so even a prepared world can be invisible at the moment it matters. And the
     * bench runs in creative, where the pathfinder will happily step off a one-block platform because nothing
     * there can hurt it.
     *
     * <p>So: fly from tick one, teleport to the intended stance, and only touch down once there is genuinely
     * something under it. After that this method never runs again and the run is an ordinary, honest one.
     */
    private void holdStance() {
        if (stanceSettled) {
            return;
        }
        BlockPos want = stanceForPad();
        BlockPos ground = want.below();
        // PUT THE BLOCK WHERE WE TELEPORT. Obvious in hindsight and the source of six lost runs: setup clears the
        // world to the dimension floor during preparation, so any ground placed beforehand -- by the scenario, by
        // hand, by a previous run -- is gone at exactly the moment the bot needs it. Re-asserting it on every tick
        // until touchdown is the only version of this that cannot race. For a single-block pad this IS the
        // scenario's starter block, so nothing is being faked.
        if (ctx.world().getBlockState(ground).isAir()) {
            sendCommand("setblock " + ground.getX() + " " + ground.getY() + " " + ground.getZ()
                    + " minecraft:stone");
        }
        ctx.player().getAbilities().flying = true;
        if (!ctx.player().blockPosition().equals(want)) {
            sendCommand("tp @s " + want.getX() + " " + want.getY() + " " + want.getZ());
            return;
        }
        // HOLD UNTIL THE SETUP IS FINISHED, not until a block first appears. Setup deliberately clears every layer
        // to the dimension floor before it lays the starter block, so a bot that touched down on the earlier world
        // has the ground taken out from under it mid-preparation -- which is precisely what happened: it settled
        // correctly on 67,-59,67 and was at y=-285 moments later, in survival, without ever choosing to move.
        if (ticks < START_TICK) {
            return;
        }
        if (ctx.world().getBlockState(want.below()).isAir()) {
            return; // still no starter block -- keep hovering, do not drop
        }
        ctx.player().getAbilities().flying = false;
        stanceSettled = true;
        if (padShrink() == 1) {
            // SURVIVAL for the airborne pad, and this time the earlier objection no longer holds. In creative a
            // fall costs nothing, so the router rates the step off a one-block pad as perfectly ordinary -- run
            // band0005 settled correctly on 67,-59,67 and was one diagonal step away and falling by the next
            // sample. In survival that route is fatal and therefore never chosen, which is exactly the product's
            // situation. Survival was tried once before and appeared to break material handling; that was the old
            // path, which stocked the main inventory and left the hotbar empty. With the opening order and the two
            // swap fixes in place, the bot now fills its own bar.
            sendCommand("gamemode survival");
        }
        // NOT survival, despite the product running that way, and the reason is worth writing down. Survival was
        // tried to stop the pathfinder stepping off the one-block pad, which it does in creative because a fall
        // costs nothing there. It stopped the stepping and broke the measurement instead: with thirty materials
        // for nine hotbar slots the builder must restock constantly, and in survival that is a physical inventory
        // move, which turned every run into "Hotbar fetch needed for white_wool, blue_ice, dark_prismarine"
        // forever. The stepping-off is now handled where it belongs -- ground under the stance, held until the
        // build starts -- so creative can stay, and the run measures the builder rather than the inventory.
        logDirect("[BENCH] stance settled at " + want.getX() + "," + want.getY() + "," + want.getZ()
                + " on " + BuiltInRegistries.BLOCK.getKey(ctx.world().getBlockState(want.below()).getBlock())
                );
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (SCENARIO.isEmpty() || event.getType() != TickEvent.Type.IN
                || ctx.player() == null || ctx.world() == null) {
            return;
        }
        if (started) {
            keepOnPlane();
            supplyOnRequest();
            return;
        }
        ticks++;
        if (scenario == null) {
            scenario = createScenario();
        }
        holdStance();
        if (ticks == TELEPORT_TICK) {
            // Creative flight is server-persistent. Toggle it off so the later starter platform produces a real
            // on-ground stance instead of a player hovering at the same displayed block position.
            sendCommand("gamemode survival");
            sendCommand("gamemode creative");
            BlockPos stance = stanceForPad();
            sendCommand("tp @s " + stance.getX() + " " + stance.getY() + " " + stance.getZ());
            return;
        }
        if (ticks == PREPARE_TICK) {
            // Prepare the exact airborne world well before start(). BuilderBench intentionally starts the builder in
            // the same tick as its generic setup commands, so relying only on those commands lets the first client
            // scan race the server. start() still owns the canonical setup; this advance copy merely guarantees that
            // the initial placement scan sees the starter pad rather than an unprepared target.
            for (String command : BuilderBench.setupCommands(scenario, ORIGIN)) {
                sendCommand(command);
            }
            sendAirborneOverrides(scenario);
            openingOrder();
            // The safety net. Far enough below that nothing the builder does can ever reach or stand on it, close
            // enough that a fall ends in seconds instead of running off into the void where no teleport lands.
            int netY = ORIGIN.getY() - 80;
            for (int x = ORIGIN.getX() - 8; x <= ORIGIN.getX() + 136; x += 24) {
                sendCommand("fill " + x + " " + netY + " " + (ORIGIN.getZ() - 8) + " "
                        + Math.min(x + 23, ORIGIN.getX() + 136) + " " + netY + " " + (ORIGIN.getZ() + 136)
                        + " minecraft:stone");
            }
            return;
        }
        if (ticks < START_TICK) {
            return;
        }
        started = true;
        logMechanic("[MAPART-BENCH] airborne start scenario=" + scenario.name + " cells=" + scenario.cellCount());
        // The world and the auction stand-in were prepared eighty ticks ago and holdStance has confirmed the actual
        // client-side floor. Replaying the generic bench setup here clears that one block and races its replacement
        // against the first builder tick; run band0005 was grounded at start and falling by tick two for exactly that
        // reason. It also replaces the auction stacks with a synthetic pre-stocked inventory. Start only the shared
        // verifier and builder, keeping both the airborne geometry and the material path we came here to test.
        princeps.getBuilderBench().startPrepared(scenario, ORIGIN);
    }

    private void sendAirborneOverrides(BenchSchematics.Scenario target) {
        // The real airborne build has no rescue floor. Clear every layer from the dimension floor (including the
        // vanilla flat-world bedrock) through the layer below the picture, across the picture plus a wide safety
        // margin. A bot that falls must therefore fail into the void instead of landing below the image and hiding a
        // row-traversal defect by pillaring back up.
        final int margin = 32;
        int minX = ORIGIN.getX() - margin;
        int maxX = ORIGIN.getX() + target.widthX() - 1;
        int minZ = ORIGIN.getZ() - margin;
        int maxZ = ORIGIN.getZ() + target.lengthZ() - 1;
        maxX += margin;
        maxZ += margin;

        // /fill accepts at most 32,768 blocks. Split wide images into safe x slabs for every cleared y layer.
        int depth = maxZ - minZ + 1;
        int xSpan = Math.max(1, 32768 / depth);
        for (int y = ctx.world().getMinY(); y < ORIGIN.getY(); y++) {
            for (int x = minX; x <= maxX; x += xSpan) {
                int slabMaxX = Math.min(maxX, x + xSpan - 1);
                sendCommand("fill " + x + " " + y + " " + minZ + " " + slabMaxX + " " + y + " "
                        + maxZ + " air");
            }
        }

        // The sole exception is this compact launch/start pad. It joins the teleport anchor to the first 2x2 image
        // corner so the very first blocks are physically placeable; there is no floor anywhere under later rows.
        //
        // ITS SIZE IS THE ONE PLACE THIS BENCH IS MORE GENEROUS THAN THE PRODUCT, so it is measurable rather than
        // hard-coded. As written it lays 36 blocks (x 63..68, z 63..68). The client module preserves exactly ONE --
        // the block under the operator's feet -- and a DonutSMP run on 2026-08-11 then reported 16511 of 16512 cells
        // with "no face to click against" and the single remaining one with "a face but no working stance". A bench
        // that can only produce the easy start cannot see that failure at all.
        //
        // -Dprinceps.bench.mapart.pad=1 reproduces the product's condition. Default 0 keeps every archived run
        // comparable.
        int padShrink = padShrink();
        if (padShrink == 1) {
            // Exactly the product's geometry: ONE block, and it is the picture's own first cell -- not a support
            // underneath it. The distinction is the whole feature. With the block one lower, the operator stands
            // level with the picture and the only placeable cell is the one their body occupies; a run reported
            // 16511 cells "no face to click against" and exactly one "a face but no working stance", which is
            // that geometry stated as a census. At picture level the bot stands ON the first cell and bridges
            // outward from its sides, which is how a person builds map art.
            sendCommand("setblock " + ORIGIN.getX() + " " + ORIGIN.getY() + " " + ORIGIN.getZ() + " stone");
        } else {
            sendCommand("fill " + (ANCHOR.getX() - 1) + " " + (ORIGIN.getY() - 1) + " " + (ANCHOR.getZ() - 1)
                    + " " + (ORIGIN.getX() + 1) + " " + (ORIGIN.getY() - 1) + " " + (ORIGIN.getZ() + 1)
                    + " stone");
        }
    }

    private BenchSchematics.Scenario createScenario() {
        int defaultSize = SCENARIO.equalsIgnoreCase("mapartsmall") ? 32 : 128;
        int size = Integer.getInteger("princeps.bench.mapart.size", defaultSize);
        int materials = Integer.getInteger("princeps.bench.mapart.materials", 32);
        if (SCENARIO.equalsIgnoreCase("mapartimage")) {
            String path = System.getProperty("princeps.bench.mapart.image", "mapart-test.png");
            boolean dither = !"false".equalsIgnoreCase(
                    System.getProperty("princeps.bench.mapart.dither", "true"));
            return BenchMapArt.fromImage(path, size, materials, dither);
        }
        if (SCENARIO.equalsIgnoreCase("mapart") || SCENARIO.equalsIgnoreCase("mapartsmall")) {
            return BenchMapArt.synthetic(size, materials);
        }
        throw new IllegalArgumentException("unknown map-art scenario: " + SCENARIO);
    }

    /**
     * Pre-stock before BuilderBench.start: that method also stocks, but starts the builder in the same tick. The
     * advance copy gives the client inventory 80 ticks to observe the server commands, avoiding a false missing-item
     * pause without changing the protected bench.
     */
    /**
     * The bench's stand-in for the auction house: hand over a stack the moment the bot has none left of it.
     *
     * <p>NOTHING is pre-stocked any more, and that is the point. Pre-stocking made the bench answer a question the
     * product never asks -- it started every run with a full inventory laid out by hand, in slots chosen by the
     * bench, which is nothing like a player who buys what a picture needs and then manages the bar himself. Worse,
     * it hid the part that actually matters: whether the bot can get a material from the backpack into the hand at
     * the moment it needs it. That is the same machinery a real run leans on constantly, and it was never once
     * exercised here.
     *
     * <p>So the bot keeps the whole chain: it decides what the picture needs, it moves blocks from the inventory to
     * the hotbar, it places them. The ONLY thing shortened is the auction house itself, which does not exist on an
     * isolated test server -- where the product would send {@code /ah <block> stack} and receive a stack, the bench
     * simply hands the stack over. Everything before and after that seam stays the bot's own work.
     *
     * <p>Rate-limited on purpose. A stack arriving every tick would paper over exactly the restocking stalls this
     * is meant to expose; one delivery every second is closer to a purchase and still never the bottleneck.
     */
    /** The shopping list, bought once, BEFORE the build starts -- the order the product itself uses. */
    private void openingOrder() {
        buildDemand();
        if (openingOrderDone) {
            return;
        }
        openingOrderDone = true;
        int bought = 0;
        for (Item item : demand.keySet()) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            sendCommand("give @s " + id + " " + Math.max(1, new ItemStack(item).getMaxStackSize()));
            bought++;
        }
        logDirect("[BENCH] auction stand-in: opening order, one stack each of " + bought + " material(s)");
    }

    private void buildDemand() {
        if (demand != null || scenario == null) {
            return;
        }
        demand = new java.util.LinkedHashMap<>();
        for (BlockState state : scenario.cells.values()) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR) {
                demand.merge(item, 1, Integer::sum);
            }
        }
    }

    /**
     * Brings the bot back onto the picture if something drops it off.
     *
     * <p>A single misstep used to end an entire measurement: the bot left the pad, fell past the world floor, and
     * every later sample read the same frozen coordinate while the run counted ticks against a bot that was no
     * longer anywhere near the build. Six runs died that way, and none of them said anything about the builder.
     *
     * <p>Two pieces, and both are needed. The catch floor far below stops the fall -- a client in free fall
     * ignores teleports outright, so there is nothing to rescue until it has landed somewhere. The retry then
     * brings it home. The floor sits eighty blocks under the picture: much too far to support anything the builder
     * does, close enough that a fall ends in seconds.
     */
    private void keepOnPlane() {
        if (ctx.player() == null) {
            return;
        }
        if (ctx.player().getY() >= ORIGIN.getY() - 6.0D) {
            return;
        }
        if (++rescueTicks % 20 != 0) {
            return;
        }
        BlockPos back = stanceForPad();
        if (ctx.world().getBlockState(back.below()).isAir()) {
            sendCommand("setblock " + back.below().getX() + " " + back.below().getY() + " "
                    + back.below().getZ() + " minecraft:stone");
        }
        sendCommand("tp @s " + back.getX() + " " + back.getY() + " " + back.getZ());
        if (++rescues <= 20) {
            logDirect("[BENCH] rescued from y=" + (int) ctx.player().getY() + " back onto the picture ("
                    + rescues + ")");
        }
    }

    private void supplyOnRequest() {
        if (scenario == null || ++supplyTicks % SUPPLY_INTERVAL_TICKS != 0) {
            return;
        }
        buildDemand();
        java.util.List<java.util.Map.Entry<Item, Integer>> ordered = new java.util.ArrayList<>(demand.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (java.util.Map.Entry<Item, Integer> entry : ordered) {
            if (carries(entry.getKey())) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(entry.getKey()).toString();
            int count = Math.max(1, new ItemStack(entry.getKey()).getMaxStackSize());
            // `give` lands in the first free slot, exactly as a purchase would -- never in a slot of our choosing.
            sendCommand("give @s " + id + " " + count);
            if (++deliveries <= 40) {
                logDirect("[BENCH] auction stand-in: delivered " + count + "x " + id);
            }
            return;
        }
    }

    /** Whether the bot has any of this item anywhere on him -- hotbar or backpack, it makes no difference. */
    private boolean carries(Item item) {
        for (ItemStack stack : ctx.player().getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    private void stockMaterials(BenchSchematics.Scenario target) {
        java.util.Map<Item, Integer> demand = new java.util.LinkedHashMap<>();
        for (BlockState state : target.cells.values()) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR) {
                demand.merge(item, 1, Integer::sum);
            }
        }
        java.util.List<java.util.Map.Entry<Item, Integer>> ordered = new java.util.ArrayList<>(demand.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        // HOTBAR FIRST, and this is not cosmetic. `container.N` on a PLAYER addresses the main inventory, not the
        // nine quick-slots -- so every material the bench handed out landed somewhere the builder cannot place
        // from. The log said so plainly and I read past it for several runs: "Hotbar fetch needed for white_wool,
        // blue_ice, dark_prismarine; hotbar [0=- 1=- ... 8=-]". Nine empty slots, a full inventory, and a builder
        // waiting for a swap that never completed. The overflow still goes to the inventory on purpose: that is
        // what a real auction-house purchase produces, so the restocking path stays exercised rather than skipped.
        int hotbar = 0;
        int slot = 0;
        for (java.util.Map.Entry<Item, Integer> entry : ordered) {
            String id = BuiltInRegistries.ITEM.getKey(entry.getKey()).toString();
            int count = Math.max(1, new ItemStack(entry.getKey()).getMaxStackSize());
            if (hotbar < 9) {
                sendCommand("item replace entity @s hotbar." + hotbar + " with " + id + " " + count);
                hotbar++;
                continue;
            }
            if (slot >= 26) {
                break;
            }
            sendCommand("item replace entity @s container." + slot + " with " + id + " " + count);
            slot++;
        }
        // Remember the next slot because protected BuilderBench.start() puts its generic helper cobblestone there.
        // Map Art must not receive artificial pillar/bridge material: clear that exact extra slot both now and again
        // immediately after start() has queued its own stock commands.
        // NOT tracked any more, and that is a correction. This counter assumed the driver and the protected bench
        // lay their materials out the same way; since the driver fills the HOTBAR first, its slot counter no longer
        // points at the bench's scaffold at all. It pointed at container.0 instead -- the bench's FIRST material --
        // and the run then reported "supply the material and start it again" with a full inventory. A cobblestone
        // scaffold left in place costs nothing here; deleting a material the build needs costs the whole run.
        benchScaffoldSlot = -1;
        while (slot < 26) {
            sendCommand("item replace entity @s container." + slot + " with air");
            slot++;
        }
        while (hotbar < 9) {
            sendCommand("item replace entity @s hotbar." + hotbar + " with air");
            hotbar++;
        }
        logMechanic("[MAPART-BENCH] pre-stocked " + demand.size()
                + " material type(s); generic scaffold withheld");
    }

    private void removeBenchScaffold() {
        if (benchScaffoldSlot >= 0) {
            sendCommand("item replace entity @s container." + benchScaffoldSlot + " with air");
            logMechanic("[MAPART-BENCH] cleared generic scaffold slot " + benchScaffoldSlot);
        }
    }

    private void sendCommand(String command) {
        if (ctx.player() != null && ctx.player().connection != null) {
            ctx.player().connection.sendCommand(command);
        }
    }
}
