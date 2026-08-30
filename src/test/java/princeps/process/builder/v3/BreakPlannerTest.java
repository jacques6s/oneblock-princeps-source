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
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.schematic.ISchematic;
import princeps.api.utils.Rotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The {@code BREAK} action type of 5.9, at the three places it can be wrong without anyone noticing.
 *
 * <p>One: the aim. A break's geometry is the only one in the package whose target is SOLID, which inverts the meaning
 * of the occlusion test — the ray is supposed to stop on the target, and a caster asked "is the path clear" answers no
 * for exactly the ray that is correct. {@link #aimFindsAStanceForAnOrdinarySolidCube} is the regression test for that
 * inversion, and it fails against the geometry this class inherited.
 *
 * <p>Two: the cascade. Its whole purpose is to keep the predicted world honest after a break, and a cascade that is
 * merely incomplete produces a plan that is proven against a world the executor will never see.
 *
 * <p>Three: loss. It is one boolean per break and it decides a material ledger that is read hours later.
 */
public class BreakPlannerTest {

    /** Real vanilla block states, headless. No {@link net.minecraft.world.item.ItemStack} is built anywhere in this
     *  file, so the data-component binding {@code HotbarScheduleTest} needs is not needed here. */
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Floor level of every fixture below; the target sits at {@code FLOOR + 1}. */
    private static final int FLOOR = 63;

    // ------------------------------------------------------------------ the aim

    /**
     * A stone cube on a stone floor, in the open, with the bot free to stand anywhere around it. If a break cannot be
     * planned HERE it cannot be planned anywhere, and the failure is silent: {@code breakFor} returns empty, the cell
     * is left occupied, and the engine quietly keeps the behaviour V3 exists to replace.
     *
     * <p>This is the test that catches the aim point being pushed 0.02 blocks INSIDE the block it is aiming at. See
     * {@code BreakPlanner.faceAim} — the half-open ray convention makes a point exactly ON the face a clean miss and a
     * point just inside it a self-obstruction, so the inset turns every solid block in the world into
     * {@link BreakPlanner.Refusal#NO_PROVABLE_AIM}.
     */
    @Test
    public void aimFindsAStanceForAnOrdinarySolidCube() {
        BlockPos target = new BlockPos(0, FLOOR + 1, 0);
        PredictedWorld world = worldWith(floor(), Map.of(target, Blocks.STONE.defaultBlockState()));

        BreakPlanner.Aim aim = BreakPlanner.aim(world, PlayerPose.CROUCHED, SolveBudget.DEFAULT, target, null, null)
                .orElse(null);

        assertNotNull("a stone cube in the open must be breakable from somewhere", aim);
        assertTrue("the stance must be a cell the bot can actually stand in",
                world.isStandable(aim.stance().getX(), aim.stance().getY(), aim.stance().getZ()));
        assertTrue("and within reach of the click point",
                PlayerPose.CROUCHED.eyeAt(aim.approach()).distanceTo(aim.point()) <= SolveBudget.DEFAULT.maxReach());
    }

    /** The same question asked through {@link BreakPlanner#plan}: a schematic cell holding the wrong block compiles to
     *  a crouched left-click with the pickaxe, aimed at what the planner believes is there. */
    @Test
    public void aWrongBlockInASchematicCellIsPlannedAsABreak() {
        BlockPos cell = new BlockPos(0, FLOOR + 1, 0);
        PredictedWorld world = worldWith(floor(), Map.of(cell, Blocks.STONE.defaultBlockState()));
        SchematicView view = viewOf(world, Map.of(cell, Blocks.GLASS.defaultBlockState()));

        BreakPlanner.Attempt attempt = BreakPlanner.plan(world, view, V3Settings.defaults(), PlayerPose.CROUCHED,
                SolveBudget.DEFAULT, cell, null, BreakPlanner.Reason.SCHEMATIC_CELL, cell.getY());

        assertTrue(attempt.planned());
        assertEquals(BreakPlanner.Refusal.NONE, attempt.refusal());
        BuildAction.Break action = attempt.action();
        assertEquals(Blocks.STONE.defaultBlockState(), action.expected());
        assertEquals("the geometry was proven from the crouched eye, so the click goes out crouched",
                true, action.sneak());
        assertEquals("breaks hold the pickaxe, and the pickaxe slot is not ours to schedule",
                HotbarSchedule.PICKAXE_SLOT, action.handSlot());
        assertFalse("stone hands back cobblestone, which does not place stone", action.drops());
    }

    /** A cell that already holds what the schematic wants is not a break, and asking is how the {@code todo} filter
     *  and the {@code BREAK} trigger stay one question rather than two that can disagree. */
    @Test
    public void aSatisfiedCellIsNotBroken() {
        BlockPos cell = new BlockPos(0, FLOOR + 1, 0);
        PredictedWorld world = worldWith(floor(), Map.of(cell, Blocks.STONE.defaultBlockState()));
        SchematicView view = viewOf(world, Map.of(cell, Blocks.STONE.defaultBlockState()));

        BreakPlanner.Attempt attempt = BreakPlanner.plan(world, view, V3Settings.defaults(), PlayerPose.CROUCHED,
                SolveBudget.DEFAULT, cell, null, BreakPlanner.Reason.SCHEMATIC_CELL, cell.getY());

        assertFalse(attempt.planned());
        assertEquals(BreakPlanner.Refusal.CELL_ALREADY_CORRECT, attempt.refusal());
        assertNull("nothing worth a report line", attempt.note());
    }

    /** Tall grass, snow layers and water are replaced by the click itself. Breaking them buys nothing and pays the
     *  full break cooldown, which under humanized look is not even a fixed number of ticks. */
    @Test
    public void aReplaceableBlockIsNotBroken() {
        BlockPos cell = new BlockPos(0, FLOOR + 1, 0);
        PredictedWorld world = worldWith(floor(), Map.of(cell, Blocks.SHORT_GRASS.defaultBlockState()));
        SchematicView view = viewOf(world, Map.of(cell, Blocks.STONE.defaultBlockState()));

        BreakPlanner.Attempt attempt = BreakPlanner.plan(world, view, V3Settings.defaults(), PlayerPose.CROUCHED,
                SolveBudget.DEFAULT, cell, null, BreakPlanner.Reason.SCHEMATIC_CELL, cell.getY());

        assertEquals(BreakPlanner.Refusal.NOTHING_TO_REMOVE, attempt.refusal());
    }

    /**
     * Sand over the cell means the sand lands in the very hole the break just made, so the break achieves nothing and
     * would have to be repeated — and a repeat inside four seconds blacklists the position permanently. Refused, with
     * a sentence, because E3 forbids tearing down to correct and the report is where the human learns to go and move
     * the sand.
     */
    @Test
    public void aBreakUnderAFallingBlockIsRefusedWithASentence() {
        BlockPos cell = new BlockPos(0, FLOOR + 1, 0);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(cell, Blocks.STONE.defaultBlockState());
        blocks.put(cell.above(), Blocks.SAND.defaultBlockState());
        PredictedWorld world = worldWith(floor(), blocks);
        SchematicView view = viewOf(world, Map.of(cell, Blocks.GLASS.defaultBlockState()));

        BreakPlanner.Attempt attempt = BreakPlanner.plan(world, view, V3Settings.defaults(), PlayerPose.CROUCHED,
                SolveBudget.DEFAULT, cell, null, BreakPlanner.Reason.SCHEMATIC_CELL, cell.getY());

        assertFalse(attempt.planned());
        assertEquals(BreakPlanner.Refusal.FALLING_BLOCK_ABOVE, attempt.refusal());
        assertNotNull("a refusal a human can act on must carry its sentence", attempt.note());
        assertTrue(attempt.note().contains("falling block"));
    }

    /** The preferred stance goes first, so a break that follows its own placement is planned from the cell the bot is
     *  already standing in and pays no walk at all. */
    @Test
    public void thePreferredStanceIsTriedFirst() {
        BlockPos cell = new BlockPos(0, FLOOR + 1, 0);
        BlockPos preferred = new BlockPos(3, FLOOR + 1, 3);
        assertEquals(preferred, BreakPlanner.stances(cell, preferred).get(0));
        assertEquals("and it appears exactly once",
                1, BreakPlanner.stances(cell, preferred).stream().filter(preferred::equals).count());
        assertEquals("without a preference the window is ranked by distance from the target, deterministically",
                BreakPlanner.stances(cell, null), BreakPlanner.stances(cell, null));
    }

    // ------------------------------------------------------------------ the cascade

    /**
     * A wall torch hangs on the block that was just broken, so it pops off — and because the schematic wants it there,
     * the cascade owes its re-placement.
     *
     * <p>{@code canSurvive} is the mechanism, and the reason it has to run in the virtual world rather than be
     * reasoned about: the block that pops off can be the support of the next one along.
     */
    @Test
    public void aBlockThatLosesItsSupportPopsOffAndIsOwedBack() {
        BlockPos hole = new BlockPos(0, FLOOR + 1, 0);
        BlockPos torch = hole.east();
        BlockState wallTorch = Blocks.WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        assertSame("fixture check: the torch must hang on the cell to its west",
                Direction.WEST, PlacementGeometry.requiredSupportDirection(wallTorch));

        // The hole is already air: a cascade runs AFTER the break has been applied to the predicted world.
        PredictedWorld world = worldWith(floor(), Map.of(torch, wallTorch));
        SchematicView view = viewOf(world, Map.of(torch, wallTorch));

        Recorder edits = new Recorder(world);
        BreakPlanner.Cascade cascade = BreakPlanner.cascade(world, view, V3Settings.defaults(), hole,
                view.layerOf(torch), edits);

        assertTrue("the torch was emptied", cascade.emptied().contains(torch));
        assertEquals("and the schematic wants it back", List.of(torch), cascade.lost());
        assertTrue("the world moved with the decision", world.get(torch).isAir());
        assertEquals(List.of(torch), edits.vacated);
    }

    /**
     * Two sand blocks over a fresh hole drop by exactly one cell, and the cell the column vacated at the TOP becomes a
     * hole in its own right.
     *
     * <p>One cell and not more, because the hole is one cell. Modelling it matters because the alternative is a plan
     * that believes a schematic block is at a position the world has just moved it out of — every proof after that is
     * against a fiction, and the executor meets it as an unexplained divergence.
     */
    @Test
    public void aGravityColumnResettlesOneCellDown() {
        BlockPos hole = new BlockPos(0, FLOOR + 1, 0);
        BlockPos lower = hole.above();
        BlockPos upper = hole.above(2);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(lower, Blocks.SAND.defaultBlockState());
        blocks.put(upper, Blocks.SAND.defaultBlockState());
        PredictedWorld world = worldWith(floor(), blocks);
        // The schematic wants all three cells full of sand, so the two that end up wrong are owed back.
        Map<BlockPos, BlockState> wanted = new HashMap<>();
        wanted.put(hole, Blocks.SAND.defaultBlockState());
        wanted.put(lower, Blocks.SAND.defaultBlockState());
        wanted.put(upper, Blocks.SAND.defaultBlockState());
        SchematicView view = viewOf(world, wanted);

        BreakPlanner.Cascade cascade = BreakPlanner.cascade(world, view, V3Settings.defaults(), hole,
                upper.getY(), new Recorder(world));

        assertFalse("the hole was refilled by the column above it", world.get(hole).isAir());
        assertFalse(world.get(lower).isAir());
        assertTrue("and the top of the column is now empty", world.get(upper).isAir());
        assertTrue("which makes it a hole the cascade carries on from", cascade.emptied().contains(upper));
        assertEquals("only the cell the column left is wrong now", List.of(upper), cascade.lost());
        assertFalse("and the plan says so rather than modelling it silently", cascade.notes().isEmpty());
    }

    /** Nothing hangs on the broken cell and nothing stands over it: the cascade is the break and no more. A cascade
     *  that invented consequences would be worse than one that missed them. */
    @Test
    public void anIsolatedBreakCascadesToNothing() {
        BlockPos hole = new BlockPos(0, FLOOR + 1, 0);
        PredictedWorld world = worldWith(floor(), Map.of());
        SchematicView view = viewOf(world, Map.of());

        Recorder edits = new Recorder(world);
        BreakPlanner.Cascade cascade = BreakPlanner.cascade(world, view, V3Settings.defaults(), hole,
                hole.getY(), edits);

        assertEquals(List.of(hole), cascade.emptied());
        assertTrue(cascade.lost().isEmpty());
        assertTrue(edits.vacated.isEmpty());
        assertTrue(edits.filled.isEmpty());
    }

    // ------------------------------------------------------------------ loss, and the blacklist trap

    /** Glass is the case the plan names; the rest are the cases a schematic actually contains. The default is TRUE, so
     *  the ledger under-counts rather than refusing a build, and the report says which way it errs. */
    @Test
    public void lossIsDecidedPerBlockAndDefaultsToDropping() {
        assertFalse("glass drops nothing", BreakPlanner.drops(Blocks.GLASS.defaultBlockState()));
        assertFalse("nor does stained glass", BreakPlanner.drops(Blocks.RED_STAINED_GLASS.defaultBlockState()));
        assertFalse("nor a glass pane", BreakPlanner.drops(Blocks.GLASS_PANE.defaultBlockState()));
        assertFalse("nor a stained pane", BreakPlanner.drops(Blocks.LIME_STAINED_GLASS_PANE.defaultBlockState()));
        assertFalse("leaves hand back saplings at best", BreakPlanner.drops(Blocks.OAK_LEAVES.defaultBlockState()));
        assertFalse("ice melts", BreakPlanner.drops(Blocks.ICE.defaultBlockState()));
        assertFalse("packed ice is not an IceBlock and still drops nothing",
                BreakPlanner.drops(Blocks.PACKED_ICE.defaultBlockState()));
        assertFalse("an ore hands back a raw material", BreakPlanner.drops(Blocks.IRON_ORE.defaultBlockState()));
        assertFalse("stone hands back cobblestone", BreakPlanner.drops(Blocks.STONE.defaultBlockState()));

        assertTrue("cobblestone drops itself", BreakPlanner.drops(Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue("so does a piston", BreakPlanner.drops(Blocks.PISTON.defaultBlockState()));
        assertTrue("iron bars are not a glass pane, and the suffix rule must not reach them",
                BreakPlanner.drops(Blocks.IRON_BARS.defaultBlockState()));
        assertTrue("slime is a HalfTransparentBlock and drops itself — which is why that class is not the test",
                BreakPlanner.drops(Blocks.SLIME_BLOCK.defaultBlockState()));
        assertTrue("air is nothing to lose", BreakPlanner.drops(Blocks.AIR.defaultBlockState()));
    }

    /**
     * A helper block removed from a position the plan later breaks is two left-clicks at one position, even though
     * only one of them is called {@code BREAK} — and two inside four seconds blacklist it forever.
     */
    @Test
    public void aPositionTakenOutTwiceIsNamed() {
        BlockPos twice = new BlockPos(0, FLOOR + 1, 0);
        BlockPos once = new BlockPos(1, FLOOR + 1, 0);
        List<BuildAction> order = List.of(
                removeScaffoldOf(twice), breakOf(once), breakOf(twice), breakOf(once.above()));

        assertEquals(List.of(twice), BreakPlanner.repeatedBreaks(order));
        assertTrue("a plan that takes each position out once cannot hit the trap at all",
                BreakPlanner.repeatedBreaks(List.of(breakOf(once), breakOf(twice))).isEmpty());
    }

    /** Three breaks at one position is still one report entry: the list names positions, not events. */
    @Test
    public void aRepeatedPositionIsNamedOnce() {
        BlockPos position = new BlockPos(0, FLOOR + 1, 0);
        assertEquals(List.of(position),
                BreakPlanner.repeatedBreaks(List.of(breakOf(position), breakOf(position), breakOf(position))));
    }

    // ------------------------------------------------------------------ helpers

    /** An {@link BreakPlanner.Edits} sink that writes straight to the world and records what it was asked to do —
     *  what the planner's own sink does on top of that is bookkeeping this class knows nothing about. */
    private static final class Recorder implements BreakPlanner.Edits {

        private final PredictedWorld world;
        private final List<BlockPos> vacated = new ArrayList<>();
        private final List<BlockPos> filled = new ArrayList<>();

        private Recorder(PredictedWorld world) {
            this.world = world;
        }

        @Override
        public void vacate(BlockPos pos) {
            this.vacated.add(pos);
            this.world.apply(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public void fill(BlockPos pos, BlockState state) {
            this.filled.add(pos);
            this.world.apply(pos, state);
        }
    }

    /** A 9x9 stone floor at {@link #FLOOR}, which is what makes every stance in the window standable. */
    private static Map<BlockPos, BlockState> floor() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                blocks.put(new BlockPos(x, FLOOR, z), Blocks.STONE.defaultBlockState());
            }
        }
        return blocks;
    }

    private static PredictedWorld worldWith(Map<BlockPos, BlockState> floor, Map<BlockPos, BlockState> extra) {
        Map<Long, BlockState> cells = new HashMap<>();
        floor.forEach((pos, state) -> cells.put(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()), state));
        extra.forEach((pos, state) -> cells.put(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()), state));
        return PredictedWorld.capture(
                (x, y, z) -> cells.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState()),
                (x, z) -> true, new Vec3i(-6, FLOOR - 2, -6), new Vec3i(6, FLOOR + 6, 6), 0);
    }

    /** A schematic view over the same box as {@link #worldWith}, wanting exactly the cells handed in. */
    private static SchematicView viewOf(PredictedWorld world, Map<BlockPos, BlockState> wanted) {
        BlockPos min = new BlockPos(-6, FLOOR - 2, -6);
        BlockPos max = new BlockPos(6, FLOOR + 6, 6);
        Map<Long, BlockState> byLocal = new HashMap<>();
        wanted.forEach((pos, state) -> {
            BlockPos local = pos.subtract(min);
            byLocal.put(BlockPos.asLong(local.getX(), local.getY(), local.getZ()), state);
        });
        ISchematic schematic = new ISchematic() {

            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> placeable) {
                BlockState desired = byLocal.get(BlockPos.asLong(x, y, z));
                return desired == null ? Blocks.AIR.defaultBlockState() : desired;
            }

            @Override
            public int widthX() {
                return max.getX() - min.getX() + 1;
            }

            @Override
            public int heightY() {
                return max.getY() - min.getY() + 1;
            }

            @Override
            public int lengthZ() {
                return max.getZ() - min.getZ() + 1;
            }
        };
        return SchematicView.capture("break-test", schematic, new Vec3i(min.getX(), min.getY(), min.getZ()), world,
                List.of());
    }

    private static BuildAction.Break breakOf(BlockPos cell) {
        return new BuildAction.Break(cell, cell.above(), Vec3.ZERO, Vec3.ZERO, new Rotation(0.0F, 0.0F),
                Direction.UP, Blocks.STONE.defaultBlockState(), false, true, HotbarSchedule.PICKAXE_SLOT, 0);
    }

    private static BuildAction.RemoveScaffold removeScaffoldOf(BlockPos cell) {
        return new BuildAction.RemoveScaffold(cell, cell.above(), Vec3.ZERO, Vec3.ZERO, new Rotation(0.0F, 0.0F),
                Blocks.COBBLESTONE.defaultBlockState(), true, HotbarSchedule.PICKAXE_SLOT, 0);
    }
}
