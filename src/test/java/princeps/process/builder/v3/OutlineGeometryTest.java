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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regressions for the selection-shape boundary between planning geometry and Vanilla's crosshair.
 *
 * <p>Every fixture here is a block for which collision/full-cube geometry gives the wrong answer. The assertion that
 * closes each case is the same one the planner now uses: an OUTLINE ray along the stored rotation must hit the exact
 * target cell and face.
 */
public class OutlineGeometryTest {

    private static final int FLOOR = 63;

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

    /**
     * Two blocks whose real geometry is nothing like the full cube a naive planner would aim at, and a proof that the
     * stored aim survives both.
     *
     * <p>The repeater's premise was wrong until it was probed against this build: this test used to assert that a
     * repeater has no collision shape at all. It has one. {@code DiodeBlock.SHAPE} is {@code Block.box(0,0,0,16,2,16)},
     * so a repeater is a plate two pixels tall, and its OUTLINE is that same plate — it is not an
     * outline-differs-from-collision example, it is a not-a-full-cube example. (Its INTERACTION shape is the empty
     * one, and nothing here asks for that.) The point the fixture carries is unchanged and is asserted directly
     * below: a planner aiming at the full-cube top plane {@code y = +1.0} would miss the block by fourteen pixels.
     *
     * <p>The torch carries the genuinely collisionless half on its own, and its premise stands exactly as written.
     */
    @Test
    public void aRepeatersThinPlateAndACollisionlessTorchBothHaveProvableRealOutlines() {
        BlockPos target = new BlockPos(0, FLOOR + 1, 0);
        PredictedWorld futureWorld = worldWith(Map.of());
        BlockState repeater = Blocks.REPEATER.defaultBlockState();

        List<AABB> repeaterCollision = repeater.getCollisionShape(
                OutlineGeometry.withState(futureWorld, target, repeater), target,
                CollisionContext.empty()).toAabbs();
        assertEquals("fixture: DiodeBlock.SHAPE is Block.box(0,0,0,16,2,16) — a repeater has a 2-pixel body, not none",
                1, repeaterCollision.size());
        assertEquals("and that body is nowhere near the full cube a naive aim would target",
                2.0D / 16.0D, repeaterCollision.get(0).maxY - repeaterCollision.get(0).minY, 0.0D);
        assertFalse("Vanilla gives it a clickable outline",
                OutlineGeometry.localParts(futureWorld, target, repeater).isEmpty());
        assertAimUsesOutline(futureWorld, target, repeater);

        BlockState torch = Blocks.TORCH.defaultBlockState();
        PredictedWorld torchWorld = worldWith(Map.of(target, torch));
        assertTrue("fixture: torches have no body collision",
                torch.getCollisionShape(torchWorld, target, CollisionContext.empty()).isEmpty());
        assertFalse("but Vanilla gives them a clickable outline", OutlineGeometry.localParts(torchWorld, target).isEmpty());
        assertAimUsesOutline(torchWorld, target, null);
    }

    @Test
    public void bottomSlabAimNeverUsesTheMissingUpperHalf() {
        BlockPos target = new BlockPos(0, FLOOR + 1, 0);
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        PredictedWorld world = worldWith(Map.of(target, slab));

        List<AABB> parts = OutlineGeometry.localParts(world, target);
        assertEquals(1, parts.size());
        assertEquals(0.5D, parts.get(0).maxY, 0.0D);

        BreakPlanner.Aim aim = assertAimUsesOutline(world, target, null);
        assertTrue("the stored point must lie on the slab, never in its absent upper half",
                aim.point().y <= target.getY() + 0.5D + 1.0E-9D);
    }

    @Test
    public void openAndClosedTrapdoorsKeepTheirDifferentOutlinePlanes() {
        BlockPos target = new BlockPos(0, FLOOR + 1, 0);
        BlockState closed = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(BlockStateProperties.OPEN, false);
        BlockState open = closed.setValue(BlockStateProperties.OPEN, true);

        PredictedWorld closedWorld = worldWith(Map.of(target, closed));
        AABB closedPart = OutlineGeometry.localParts(closedWorld, target).get(0);
        assertEquals("closed bottom trapdoor is a shallow horizontal plate", 3.0D / 16.0D,
                closedPart.maxY - closedPart.minY, 0.0D);
        assertEquals(1.0D, closedPart.maxZ - closedPart.minZ, 0.0D);
        assertAimUsesOutline(closedWorld, target, null);

        PredictedWorld openWorld = worldWith(Map.of(target, open));
        AABB openPart = OutlineGeometry.localParts(openWorld, target).get(0);
        assertEquals("open trapdoor is a thin vertical plate", 1.0D, openPart.maxY - openPart.minY, 0.0D);
        assertEquals(3.0D / 16.0D, openPart.maxZ - openPart.minZ, 0.0D);
        assertAimUsesOutline(openWorld, target, null);
    }

    @Test
    public void aPartialOccluderBlocksOnlyTheSpaceItsOutlineOccupies() {
        BlockPos slabPos = new BlockPos(0, FLOOR + 1, -1);
        BlockPos target = new BlockPos(0, FLOOR + 1, 0);
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        PredictedWorld world = worldWith(Map.of(slabPos, slab, target, Blocks.STONE.defaultBlockState()));

        OutlineGeometry.Ray low = OutlineGeometry.clip(world,
                new Vec3(0.5D, target.getY() + 0.25D, -2.0D),
                new Vec3(0.5D, target.getY() + 0.25D, 0.5D));
        assertTrue(low.evaluable());
        assertNotNull(low.hit());
        assertEquals("the occupied lower half occludes", slabPos, low.hit().getBlockPos());

        OutlineGeometry.Ray high = OutlineGeometry.clip(world,
                new Vec3(0.5D, target.getY() + 0.75D, -2.0D),
                new Vec3(0.5D, target.getY() + 0.75D, 0.5D));
        assertTrue(high.evaluable());
        assertNotNull(high.hit());
        assertEquals("the empty upper half remains visible", target, high.hit().getBlockPos());
        assertEquals(Direction.NORTH, high.hit().getDirection());
    }

    @Test
    public void placementOracleCanClickACollisionlessTorchSupport() {
        BlockPos support = new BlockPos(0, FLOOR + 1, 0);
        BlockPos cell = support.above();
        PredictedWorld world = worldWith(Map.of(support, Blocks.TORCH.defaultBlockState()));
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);

        PlacementOracle.Solve solve = oracle.solve(world, cell, Blocks.STONE.defaultBlockState(),
                List.of(new ItemStack(Items.STONE)), SolveBudget.DEFAULT);
        PlacementSolution solution = solve.best().orElse(null);

        assertNotNull("a torch has no collision, but its real outline is a legal click target: " + solve.explain(),
                solution);
        assertEquals(support, solution.against());
        assertEquals(Direction.UP, solution.face());
        assertTrue("the accepted quantised look must hit that exact outline face",
                OutlineGeometry.hits(world, PlayerPose.CROUCHED.eyeAt(solution.approach()), solution.rotation(),
                        SolveBudget.DEFAULT.maxReach(), support, Direction.UP));
    }

    /** BreakPlanner's interaction form also covers a state not yet committed to the predicted world. */
    private static BreakPlanner.Aim assertAimUsesOutline(PredictedWorld world, BlockPos target, BlockState future) {
        BreakPlanner.Aim aim = BreakPlanner.aim(world, PlayerPose.CROUCHED, SolveBudget.DEFAULT, target, null, future)
                .orElse(null);
        assertNotNull("a visible real outline must yield an aim", aim);

        BlockState state = future == null ? world.get(target) : future;
        List<AABB> parts = OutlineGeometry.localParts(world, target, state);
        assertTrue("stored point must lie on a face of one actual outline component",
                parts.stream().anyMatch(part -> pointOnFace(target, part, aim.point(), aim.face())));
        Vec3 eye = PlayerPose.CROUCHED.eyeAt(aim.approach());
        assertTrue("stored rotation must finish on the exact target and face",
                OutlineGeometry.hits(world, target, state, eye, aim.rotation(),
                        SolveBudget.DEFAULT.maxReach(), aim.face()));
        return aim;
    }

    private static boolean pointOnFace(BlockPos pos, AABB local, Vec3 point, Direction face) {
        double x = point.x - pos.getX();
        double y = point.y - pos.getY();
        double z = point.z - pos.getZ();
        double epsilon = 1.0E-9D;
        boolean inside = x >= local.minX - epsilon && x <= local.maxX + epsilon
                && y >= local.minY - epsilon && y <= local.maxY + epsilon
                && z >= local.minZ - epsilon && z <= local.maxZ + epsilon;
        if (!inside) {
            return false;
        }
        return switch (face) {
            case EAST -> Math.abs(x - local.maxX) <= epsilon;
            case WEST -> Math.abs(x - local.minX) <= epsilon;
            case UP -> Math.abs(y - local.maxY) <= epsilon;
            case DOWN -> Math.abs(y - local.minY) <= epsilon;
            case SOUTH -> Math.abs(z - local.maxZ) <= epsilon;
            case NORTH -> Math.abs(z - local.minZ) <= epsilon;
        };
    }

    private static PredictedWorld worldWith(Map<BlockPos, BlockState> extra) {
        Map<Long, BlockState> cells = new HashMap<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                cells.put(BlockPos.asLong(x, FLOOR, z), Blocks.STONE.defaultBlockState());
            }
        }
        extra.forEach((pos, state) ->
                cells.put(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()), state));
        return PredictedWorld.capture(
                (x, y, z) -> cells.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState()),
                (x, z) -> true, new Vec3i(-6, FLOOR - 2, -6), new Vec3i(6, FLOOR + 6, 6), 0);
    }
}
