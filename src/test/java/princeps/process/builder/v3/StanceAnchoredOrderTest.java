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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.ISchematic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The travel property of plan 21.1, pinned where it is produced: <b>the planner empties a stance before it leaves
 * it.</b>
 *
 * <p>Measured on the generated basalt plan, the old distance-greedy policy paid a walk for 82.9 % of all placements —
 * 3 817 stance changes for 4 623 actions, over only 2 880 distinct stances, so it also went back to points it had
 * left too early. Neither number is visible in any assertion the suite had: every existing test asks what the plan
 * CONTAINS, and this is a property of the ORDER. A future change that reintroduces the hopping would pass all of them.
 *
 * <p>So three assertions over two tests, and they fail for different reasons on purpose.
 *
 * <ul>
 *   <li><b>Contiguity.</b> Every stance the plan uses occupies ONE unbroken run of actions. Distinct stances equal
 *       stance changes; nothing is walked to twice. This is the assertion that fails if the selection ever again
 *       interleaves two stances that both serve the same neighbourhood.</li>
 *   <li><b>The shared stance.</b> The busiest stance serves several cells rather than one or two. Contiguity alone is
 *       satisfied by a plan that visits every stance exactly once and places exactly one block from each, which is
 *       the defect wearing a different hat — and is precisely what the old policy does on this fixture while passing
 *       the contiguity check.</li>
 *   <li><b>Yield.</b> Actions per stance over the whole plan, pinned as a floor, so that one good stance among
 *       fifteen bad ones is not enough.</li>
 * </ul>
 *
 * <p>The fixture is a 5x5 slab and not a 3x3, because 3x3 is small enough to be served from two stances by accident.
 * Twenty-five cells over an open floor need several, and the interesting question — does the planner finish the one
 * it is standing on before choosing the next — only exists when there are several.
 *
 * <p>Both numbers are measured against both policies rather than chosen: 5.00 actions per stance and a busiest stance
 * of 10 with 21.1, 1.56 and 3 with the distance-greedy policy it replaced.
 *
 * <p>Same headless premise as {@link OrderPlannerTest}: azalea floor, shulker boxes, no {@code Princeps.settings()}
 * anywhere on the path. See that class's javadoc for why those two materials and no others.
 */
public class StanceAnchoredOrderTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
    }

    private static final Vec3i ORIGIN = new Vec3i(0, 65, 0);

    /** One below the schematic — the surface the bot stands on to build the first layer. */
    private static final int FLOOR_Y = 64;

    /** Off the build and on the floor, so the first stance is a real choice rather than where the bot happens to be. */
    private static final BlockPos BOT_START = new BlockPos(-2, 65, -2);

    /** The slab's edge. Five, for the reason in the class javadoc. */
    private static final int WIDTH = 5;

    /**
     * The floor under the yield assertion, in actions per stance.
     *
     * <p>Measured at 5.00 on this fixture (25 placements over 5 stances) with the stance-anchored policy of 21.1, and
     * at 1.56 (25 over 16) with the distance-greedy one it replaced. Pinned below what it measures and well above
     * what the old policy could reach: this is a tripwire for the defect coming back, not a target to tune against,
     * and a plan that merely got a little worse should not fail a test meant to catch a policy change.
     */
    private static final double MIN_ACTIONS_PER_STANCE = 3.0D;

    /**
     * How many cells the busiest stance has to serve.
     *
     * <p>Measured at 10 of the 25 under 21.1 and at 3 under the policy it replaced, so the assertion separates two
     * values that are far apart rather than sitting on top of either. The geometry says it must be several: the
     * crouched eye sits 1.27 above the feet, the aim points are the top faces of the floor cells, and a cell three
     * out is 3.26 from the eye against a planning reach of 4.2 — half of this slab is inside one stance's reach.
     */
    private static final int SHARED_CELLS = 6;

    // ------------------------------------------------------------------------------------------------- the fixture

    private static ISchematic slab(int width) {
        return new ISchematic() {

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                return Blocks.SHULKER_BOX.defaultBlockState();
            }

            @Override
            public int widthX() {
                return width;
            }

            @Override
            public int heightY() {
                return 1;
            }

            @Override
            public int lengthZ() {
                return width;
            }
        };
    }

    private static PlanReport planSlab(int width) {
        ISchematic schematic = slab(width);
        Vec3i max = new Vec3i(ORIGIN.getX() + schematic.widthX() - 1, ORIGIN.getY() + schematic.heightY() - 1,
                ORIGIN.getZ() + schematic.lengthZ() - 1);
        BlockState floor = Blocks.AZALEA.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        PredictedWorld world = PredictedWorld.capture((x, y, z) -> y == FLOOR_Y ? floor : air,
                (x, z) -> true, ORIGIN, max, PredictedWorld.DEFAULT_MARGIN);
        SchematicView view = SchematicView.capture("stance-anchored", schematic, ORIGIN, world, List.of());

        List<ItemStack> slots = new ArrayList<>(HotbarSchedule.INVENTORY_SLOTS);
        for (int index = 0; index < HotbarSchedule.INVENTORY_SLOTS; index++) {
            slots.add(ItemStack.EMPTY);
        }
        slots.set(HotbarSchedule.PICKAXE_SLOT, new ItemStack(Items.DIAMOND_PICKAXE));
        slots.set(1, new ItemStack(Items.SHULKER_BOX, 64));

        V3Settings settings = V3Settings.defaults();
        PlacementOracle oracle = new PlacementOracle(settings, PlayerPose.CROUCHED);
        // Netherrack as the helper block: it appears in no schematic here, and a slab on open floor needs none — a
        // scaffold block turning up in this plan would itself be the news.
        ScaffoldPlanner scaffold = new ScaffoldPlanner(oracle, settings, SolveBudget.DEFAULT,
                Items.NETHERRACK, HotbarSchedule.THROWAWAY_SLOT);
        return new OrderPlanner(oracle, scaffold).plan(view, world, settings, SolveBudget.DEFAULT,
                new HotbarSchedule.InventorySnapshot(slots), BOT_START);
    }

    /** The stance of every world-clicking action, in plan order. A hotbar swap and a sign's text click nothing at the
     *  world and carry no stance, so neither is a walk and neither is counted. */
    private static List<BlockPos> stanceSequence(BuildPlan plan) {
        List<BlockPos> stances = new ArrayList<>();
        for (int index = 0; index < plan.size(); index++) {
            BlockPos stance = stanceOf(plan.action(index));
            if (stance != null) {
                stances.add(stance);
            }
        }
        return stances;
    }

    /** Where one action is performed from. Exhaustive over the sealed interface on purpose: a ninth action type added
     *  without a branch here would silently drop out of the census this test is built on. */
    private static BlockPos stanceOf(BuildAction action) {
        return switch (action) {
            case BuildAction.Place place -> place.solution().stance();
            case BuildAction.JumpPlace jump -> jump.solution().stance();
            case BuildAction.PlaceScaffold placed -> placed.solution().stance();
            case BuildAction.Break broken -> broken.stance();
            case BuildAction.RemoveScaffold removed -> removed.stance();
            case BuildAction.Interact interact -> interact.stance();
            case BuildAction.FillFluid fluid -> fluid.stance();
            case BuildAction.WriteSign ignored -> null;
            case BuildAction.SwapHotbar ignored -> null;
        };
    }

    /** The stance sequence collapsed to its runs — one entry per WALK, in order. */
    private static List<BlockPos> runs(List<BlockPos> stances) {
        List<BlockPos> collapsed = new ArrayList<>();
        for (BlockPos stance : stances) {
            if (collapsed.isEmpty() || !collapsed.get(collapsed.size() - 1).equals(stance)) {
                collapsed.add(stance);
            }
        }
        return collapsed;
    }

    // ------------------------------------------------------------------------------------------- what 21.1 promises

    /**
     * The cells one stance serves are placed consecutively FROM it, and no stance is left and returned to.
     *
     * <p>Two facts, both about the order and neither visible in any other test. The second one — every stance owns
     * one contiguous run — is a property the old distance-greedy policy also happened to satisfy on this fixture, and
     * it is asserted anyway because it is the half that fails when a future selection starts interleaving two stances
     * that serve the same neighbourhood. The first is the one that fails today without 21.1: the old policy's busiest
     * stance on this fixture places THREE cells of the twenty-five, because "nearest to the previous stance" never has to
     * empty anything.
     *
     * <p>The message names the offender rather than a number that moved: which stance, how many cells it served, and
     * where the two visits were.
     */
    @Test
    public void theCellsOneStanceServesArePlacedConsecutivelyFromIt() {
        PlanReport report = planSlab(WIDTH);
        assertEquals("a slab on open floor has no reason to be blocked: " + report.headline(),
                PlanReport.Status.READY, report.status());

        List<BlockPos> stances = stanceSequence(report.plan());
        List<BlockPos> walks = runs(stances);

        Map<BlockPos, Integer> firstVisit = new LinkedHashMap<>();
        for (int walk = 0; walk < walks.size(); walk++) {
            BlockPos stance = walks.get(walk);
            Integer earlier = firstVisit.putIfAbsent(stance, walk);
            assertTrue("the plan walks back to " + BuildAction.describePos(stance) + ": visit " + earlier
                            + " and visit " + walk + " of " + walks.size()
                            + " are the same stance with other stances in between, so it was left before it was empty",
                    earlier == null);
        }
        assertEquals("one run per stance", new LinkedHashSet<>(walks).size(), walks.size());

        int busiest = 0;
        BlockPos where = null;
        for (BlockPos stance : new LinkedHashSet<>(stances)) {
            int served = 0;
            for (BlockPos each : stances) {
                if (each.equals(stance)) {
                    served++;
                }
            }
            if (served > busiest) {
                busiest = served;
                where = stance;
            }
        }
        assertTrue("the busiest stance of this plan is " + BuildAction.describePos(where) + " and it places only "
                        + busiest + " cell(s); a 5x5 slab on open floor puts at least " + SHARED_CELLS
                        + " within reach of one point, so the planner is leaving a stance before it is empty",
                busiest >= SHARED_CELLS);
    }

    /**
     * A stance that is walked to is worked.
     *
     * <p>The companion to the contiguity assertion above and the reason both are needed: a plan that visits every
     * stance once and places one block from each satisfies contiguity perfectly and is exactly the behaviour 21.5
     * measured. The floor is the actions-per-stance ratio the basalt plan is judged by, on a fixture small enough to
     * read.
     */
    @Test
    public void aStanceThatIsWalkedToIsEmptied() {
        PlanReport report = planSlab(WIDTH);
        assertEquals(PlanReport.Status.READY, report.status());

        List<BlockPos> stances = stanceSequence(report.plan());
        List<BlockPos> walks = runs(stances);
        double perStance = (double) stances.size() / walks.size();

        assertEquals("every cell of the slab is placed exactly once", WIDTH * WIDTH, stances.size());
        assertTrue(String.format(Locale.ROOT,
                        "%d actions over %d stance(s) is %.2f per stance, under the floor of %.2f — the planner is "
                                + "walking again instead of emptying the stance it is standing on",
                        stances.size(), walks.size(), perStance, MIN_ACTIONS_PER_STANCE),
                perStance >= MIN_ACTIONS_PER_STANCE);
    }
}
