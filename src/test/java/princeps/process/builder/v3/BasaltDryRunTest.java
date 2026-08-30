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

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.pathing.precompute.Ternary;
import princeps.process.builder.bench.BenchSchematics;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Question Q1 of plan section 16, measured against the owner's real basalt farm, in the build.
 *
 * <p>Q1 asks whether the diagonal stance survives the hard layer rule: the 448 {@code facing=down} pistons in y=-57
 * need feet one layer down, in y=-58, and under the bottom-up bound y=-58 is <b>already finished</b>. Whether the
 * schematic wants a block in the cell the feet would occupy decides whether the stance exists at all, and
 * {@code UPWARD_LOOK.md}'s 448-of-448 was counted without that constraint.
 *
 * <p>The whole measurement runs headless through {@link PredictedWorld#isStandable}. Its walkability rules receive the
 * same immutable {@link V3Settings} snapshot as the live planner; no global/client settings singleton is initialised.
 *
 * <p>Skipped rather than failed when {@code run/schematics/etz-basalt.litematic} is absent: it is the owner's file,
 * not a fixture, and a checkout without it should not go red.
 */
public class BasaltDryRunTest {

    /** The family as {@code UPWARD_LOOK.md} counts it: 464 pistons + 112 observers + 16 sticky pistons. */
    private static final int GENUINE_FAMILY = 592;

    @BeforeClass
    public static void bootstrapMinecraft() {
        BasaltDryRun.bootstrap();
    }

    /** The fixture, or a skipped test. Rebuilt per test on purpose — see {@link #theCensusIsDeterministic}. */
    private static Fixture fixture() {
        File file = BasaltDryRun.schematicFile();
        Assume.assumeTrue("run/schematics/" + BasaltDryRun.SCHEMATIC_NAME + " is the owner's file and is not in "
                + "this checkout; Q1 cannot be measured without it", file != null);
        BenchSchematics.Scenario parsed = BasaltDryRun.load(file);
        assertNotNull("the litematic is present but did not parse", parsed);
        PredictedWorld world = BasaltDryRun.world(parsed);
        SchematicView bare = BasaltDryRun.view(parsed, world, HotbarSchedule.InventorySnapshot.empty());
        Optional<Item> helper = BasaltDryRun.throwaway(bare);
        SchematicView view = BasaltDryRun.view(parsed, world,
                BasaltDryRun.inventory(BasaltDryRun.materialsOf(bare), helper.orElse(null)));
        return new Fixture(parsed, world, view);
    }

    private record Fixture(BenchSchematics.Scenario parsed, PredictedWorld world, SchematicView view) {
    }

    // ------------------------------------------------------------------------------------ the file, headlessly

    /**
     * The precondition for everything below: a litematic parses with registries and nothing else.
     *
     * <p>Worth its own test because the answer was not obvious — {@code LitematicaSchematic} reads NBT and a palette
     * and touches no client class, but {@code BenchSchematics.byName} resolves its directory through
     * {@code Minecraft.getInstance()} and {@code fromFile} does not, and only the second is used here.
     */
    @Test
    public void theSchematicParsesHeadlessly() {
        Fixture fixture = fixture();
        assertEquals(63, fixture.parsed().widthX());
        assertEquals(18, fixture.parsed().heightY());
        assertEquals(63, fixture.parsed().lengthZ());
        // 15004 and not 16342: BenchSchematics.fromFile drops the cells whose block has no item -- 602 water,
        // 720 lava, 8 bubble_column, 8 piston_head. Nothing can place those, so planning them would measure vanilla.
        assertEquals(15004, fixture.view().cellCount());
        assertEquals(18, fixture.view().layers().size());
    }

    /**
     * The origin is the one Q1's numbers are about, re-derived from the file rather than assumed.
     *
     * <p>A litematic carries no absolute anchor the loader keeps (trap 1.51), so {@link BasaltDryRun#ORIGIN} is a
     * choice, and a wrong choice would silently renumber every layer in the answer. This pins it two ways: the piston
     * row lands in y=-57 with exactly 448 members, and the family across the whole build comes to the 592 of plan 5.7.
     */
    @Test
    public void theAnchorIsThe448FacingDownPistonsInLayerMinus57() {
        Fixture fixture = fixture();
        assertEquals("layer " + BasaltDryRun.PISTON_LAYER + " does not hold the piston row; the origin is wrong and "
                        + "every layer number in the Q1 answer would be off by the same amount",
                BasaltDryRun.EXPECTED_PISTONS, BasaltDryRun.verifyAnchor(fixture.view()));

        int family = 0;
        for (int layer : fixture.view().layers()) {
            family += BasaltDryRun.countFamily(fixture.view(), layer, BasaltDryRun::needsGenuineUpwardLook);
        }
        assertEquals("the upward-look family is not the 592 cells plan 5.7 counted", GENUINE_FAMILY, family);
    }

    /** The planner's family predicate must name the genuine upward-look family exactly. */
    @Test
    public void thePlannersUpwardLookTestMatchesTheFamilyItself() {
        Fixture fixture = fixture();
        int planned = 0;
        for (int layer : fixture.view().layers()) {
            planned += BasaltDryRun.countFamily(fixture.view(), layer, UpwardLook::applies);
        }
        assertEquals(GENUINE_FAMILY, planned);

        // Up-facing pistons are made by looking down; down-facing pistons and up-facing observers look up.
        assertFalse(BasaltDryRun.needsGenuineUpwardLook(
                Blocks.STICKY_PISTON.defaultBlockState().setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        net.minecraft.core.Direction.UP)));
        assertFalse(UpwardLook.applies(
                Blocks.STICKY_PISTON.defaultBlockState().setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        net.minecraft.core.Direction.UP)));
        assertTrue(BasaltDryRun.needsGenuineUpwardLook(
                Blocks.PISTON.defaultBlockState().setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        net.minecraft.core.Direction.DOWN)));
        assertTrue(UpwardLook.applies(
                Blocks.PISTON.defaultBlockState().setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        net.minecraft.core.Direction.DOWN)));
        assertTrue(BasaltDryRun.needsGenuineUpwardLook(
                Blocks.OBSERVER.defaultBlockState().setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        net.minecraft.core.Direction.UP)));
    }

    // ----------------------------------------------------------------------------------------------------- Q1

    /**
     * <b>Q1, answered.</b> Every upward-look cell in etz-basalt keeps a foot column under the hard layer rule.
     *
     * <p>The third row of the 5.7.4 table — all eight columns occupied by the finished build, the case scaffold
     * cannot reach because what is missing is a hole — does not occur anywhere in this schematic. The failing table
     * is printed rather than merely asserted, because the interesting number if this ever goes red is WHICH cells and
     * how many, not that it went red.
     */
    @Test
    public void everyUpwardLookCellKeepsAFootColumnUnderTheHardLayerRule() {
        Fixture fixture = fixture();
        List<BlockPos> blocked = new ArrayList<>();
        int usable = 0;
        int steppingStone = 0;
        int total = 0;

        System.out.println("Q1  layer   family   >=1 usable   stepping-stone only   no column   all 24 schematic");
        for (int layer : fixture.view().layers()) {
            BasaltDryRun.Census census =
                    BasaltDryRun.census(fixture.world(), fixture.view(), layer, BasaltDryRun::needsGenuineUpwardLook);
            if (census.upwardCells() == 0) {
                continue;
            }
            System.out.printf(Locale.ROOT, "Q1  %5d %8d %12d %21d %11d %16d%n", layer, census.upwardCells(),
                    census.withUsableColumn(), census.needingSteppingStoneOnly(), census.blockedCells().size(),
                    census.allColumnsSchematic());
            total += census.upwardCells();
            usable += census.withUsableColumn();
            steppingStone += census.needingSteppingStoneOnly();
            blocked.addAll(census.blockedCells());
        }

        assertEquals(GENUINE_FAMILY, total);
        assertEquals("every column of these cells is inside the finished build; planned excavation (5.7.4 row 3) "
                + "would be needed for them: " + blocked, List.of(), blocked);
        assertEquals("the three rows of 5.7.4 must account for the whole family", GENUINE_FAMILY,
                usable + steppingStone);
    }

    /**
     * The Q1 numbers for the piston row itself, which is the cell count the question names.
     *
     * <p>Split out from the sum above because 448 of 592 is the row Q1 is about, and a regression that moved cells
     * between "usable now" and "usable after a stepping stone" would be invisible in a total that only checks they
     * add up.
     */
    @Test
    public void all448PistonsHaveAStandableColumnWithinTheThreeDepthWindow() {
        Fixture fixture = fixture();
        BasaltDryRun.Census census = BasaltDryRun.census(fixture.world(), fixture.view(), BasaltDryRun.PISTON_LAYER,
                BasaltDryRun::needsGenuineUpwardLook);

        assertEquals(BasaltDryRun.EXPECTED_PISTONS, census.upwardCells());
        // The old dy=-1-only census split 316/132. The oracle has always allowed dy down to -3; searching the complete
        // window finds a genuine finished-world stance for every piston without inventing a stepping stone.
        assertEquals(448, census.withUsableColumn());
        assertEquals(0, census.needingSteppingStoneOnly());
        assertEquals(0, census.blockedCells().size());
        // Every piston has a lateral column by dy=-3; none depends solely on the thinner corner-ray geometry.
        assertEquals(448, census.withUsableLateral());
    }

    /**
     * Same input, same census — the section 13 promise, on the half of the dry run that runs in the build.
     *
     * <p>Two fixtures rather than one reused: {@link OrderPlanner#plan} mutates the world it is given, and a census
     * taken twice off one world would compare a run against itself.
     */
    @Test
    public void theCensusIsDeterministic() {
        Fixture first = fixture();
        Fixture second = fixture();
        for (int layer : first.view().layers()) {
            assertEquals("layer " + layer,
                    BasaltDryRun.census(first.world(), first.view(), layer, BasaltDryRun::needsGenuineUpwardLook),
                    BasaltDryRun.census(second.world(), second.view(), layer, BasaltDryRun::needsGenuineUpwardLook));
        }
    }

    // ------------------------------------------------------------------------------ pure walkability seam

    /** The offline overload must classify ordinary blocks without touching the client-only settings singleton. */
    @Test
    public void walkabilitySnapshotClassifiesBasaltHeadlessly() {
        assertEquals(Ternary.NO, princeps.pathing.movement.MovementHelper.canWalkThroughBlockState(
                Blocks.BASALT.defaultBlockState(), V3Settings.defaults()));
    }
}
