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

import princeps.api.utils.Rotation;
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
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The first unit tests any of this geometry has ever had. V2's {@code mostAlignedPointOnFace},
 * {@code aimPointsOnFace} and {@code requiredLookDirections} have zero coverage, because reaching them means reaching
 * {@code BuilderProcess}, which means a running client. Lifted into a pure class they are testable headlessly, and
 * these tests exist to pin the two things a regression in them would break silently and totally: which way round
 * vanilla reads the look, and how much the winning axis wins by.
 */
public class PlacementGeometryTest {

    /** Real vanilla block states, headless. {@code getCollisionShape(null, null)} works after this and nothing else
     *  does. */
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

    /** The target cell T of every worked example below, placed at the origin so the numbers read as offsets. */
    private static final BlockPos T = new BlockPos(0, 0, 0);

    /** A full cube's AABB in block-local coordinates — what a scaffold block over T presents. */
    private static final AABB FULL_CUBE = new AABB(0, 0, 0, 1, 1, 1);

    private static final double EPS = 1.0E-9D;

    // ------------------------------------------------------------------ the two look conventions vanilla disagrees on

    /**
     * A piston {@code facing=down} is produced by looking UP. {@code PistonBaseBlock.getStateForPlacement} is
     * {@code getNearestLookingDirection().getOpposite()}, so the piston points AWAY from the look, and the look that
     * makes it is the opposite of its facing.
     */
    @Test
    public void pistonFacingDownIsMadeByLookingUp() {
        BlockState piston = Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN);

        Direction[] looks = PlacementGeometry.requiredLookDirections(piston);
        assertNotNull("a piston's facing IS decided by the look", looks);
        assertEquals("both signs, never one — vanilla's convention is not guessable per block", 2, looks.length);
        assertEquals("facing.getOpposite() first: the sign that actually produces facing=down", Direction.UP, looks[0]);
        assertEquals(Direction.DOWN, looks[1]);

        assertTrue("a down-facing piston is made by looking up", PlacementGeometry.needsUpwardLook(piston));
        assertEquals("the window has to reach dy=-3 to have any choice of upward stance",
                -3, PlacementGeometry.lowestStanceOffset(piston));

        BlockState upFacing = piston.setValue(BlockStateProperties.FACING, Direction.UP);
        assertFalse("an up-facing piston is made by looking down", PlacementGeometry.needsUpwardLook(upFacing));
        assertEquals(-1, PlacementGeometry.lowestStanceOffset(upFacing));
    }

    /**
     * An observer {@code facing=up} is ALSO produced by looking UP — {@code getNearestLookingDirection().getOpposite()
     * .getOpposite()}, a double opposite that cancels, so an observer faces exactly where you look.
     *
     * <p>This is the asymmetry the whole two-element return exists for, and a regression in it is silent and total:
     * narrowing the array to the "obvious" single sign would keep working for one of these two families and produce
     * the reversed block for the other, every time, with no error anywhere. The assertions below pin that the look
     * which produces the state sits at index 0 for the piston and at index 1 for the observer.
     */
    @Test
    public void observerFacingUpIsMadeByLookingUpToo_andTheConventionsAreOpposite() {
        BlockState observer = Blocks.OBSERVER.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP);
        BlockState piston = Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN);

        Direction[] observerLooks = PlacementGeometry.requiredLookDirections(observer);
        assertNotNull(observerLooks);
        assertEquals(2, observerLooks.length);
        assertEquals(Direction.DOWN, observerLooks[0]);
        assertEquals("the observer's own facing IS the look that makes it", Direction.UP, observerLooks[1]);

        Direction[] pistonLooks = PlacementGeometry.requiredLookDirections(piston);
        // Both blocks are made by looking UP, and they carry OPPOSITE facings while doing it. Same aim, mirrored
        // result: that is the whole reason neither sign may be dropped.
        assertEquals(Direction.UP, pistonLooks[0]);
        assertEquals(Direction.UP, observerLooks[1]);
        assertEquals(Direction.DOWN, piston.getValue(BlockStateProperties.FACING));
        assertEquals(Direction.UP, observer.getValue(BlockStateProperties.FACING));

        assertTrue(PlacementGeometry.needsUpwardLook(observer));
    }

    /** Nothing outside the three look-derived families claims a look direction — a shulker box and an end rod both
     *  carry the six-way FACING and neither takes it from {@code getNearestLookingDirection}. */
    @Test
    public void onlyTheLookDerivedFamilyClaimsALookDirection() {
        assertNull(PlacementGeometry.requiredLookDirections(Blocks.STONE.defaultBlockState()));
        assertNull(PlacementGeometry.requiredLookDirections(Blocks.OAK_STAIRS.defaultBlockState()));
        assertNull(PlacementGeometry.requiredLookDirections(Blocks.SHULKER_BOX.defaultBlockState()));
        assertNull(PlacementGeometry.requiredLookDirections(Blocks.END_ROD.defaultBlockState()));
        assertNotNull(PlacementGeometry.requiredLookDirections(Blocks.DISPENSER.defaultBlockState()));
        assertNotNull(PlacementGeometry.requiredLookDirections(Blocks.STICKY_PISTON.defaultBlockState()));

        assertFalse("a horizontal facing needs a level look, which a level stance already has",
                PlacementGeometry.needsUpwardLook(Blocks.PISTON.defaultBlockState()
                        .setValue(BlockStateProperties.FACING, Direction.NORTH)));
        assertEquals(-1, PlacementGeometry.lowestStanceOffset(Blocks.STONE.defaultBlockState()));
    }

    // -------------------------------------------------------------------------------------- the worked aim examples

    /**
     * Plan 5.7.2, the scaffold-underside stance: feet at T+EAST+DOWN, approach optimised to 0.3 from the cell wall,
     * clicking the BOTTOM face of the scaffold block at T+UP.
     *
     * <pre>
     * eye      (T.x+1.30 ; T.y+0.27 ; T.z+0.50)
     * aim      (T.x+0.98 ; T.y+1.00 ; T.z+0.50)
     * d      = (-0.32    ; +0.73    ;  0.00)      UP dominant, margin 0.41, reach 0.80
     * </pre>
     *
     * <p>The underside beats every lateral face for this family and it is not close: a side face offers as its highest
     * aimable point only its top edge at {@code T.y+0.98}, and one of the two horizontal axes is nailed to the face
     * plane. The underside sits higher, at {@code T.y+1.00} across its whole area, and BOTH horizontal axes are free
     * to clamp to the eye — which is exactly what produces the 0.00 in Z above.
     */
    @Test
    public void undersideOfTheScaffoldAboveGivesTheWorkedMargin() {
        Vec3 eye = new Vec3(T.getX() + 1.30D, T.getY() + 0.27D, T.getZ() + 0.50D);
        BlockPos scaffold = T.above();

        PlacementGeometry.AlignedPoint aligned = PlacementGeometry.mostAlignedPointOnFaceWithMargin(
                eye, scaffold, FULL_CUBE, Direction.DOWN, Direction.UP);

        assertNotNull("this is the stance the upward family is planned around", aligned);
        assertEquals(T.getX() + 0.98D, aligned.point().x, EPS);
        assertEquals(T.getY() + 1.00D, aligned.point().y, EPS);
        assertEquals("free axis clamps to the eye, so d.z is exactly zero", T.getZ() + 0.50D, aligned.point().z, EPS);

        Vec3 d = aligned.point().subtract(eye);
        assertEquals(-0.32D, d.x, EPS);
        assertEquals(+0.73D, d.y, EPS);
        assertEquals(0.00D, d.z, EPS);

        assertEquals(0.41D, aligned.margin(), EPS);
        assertEquals(0.80D, d.length(), 0.005D);
        assertTrue("0.41 is four times the threshold — this is the safe end of the scale",
                aligned.margin() > PlacementGeometry.MIN_MARGIN);
    }

    /**
     * Plan 5.7.3: the CORNER stance, offset in both horizontal axes, gives the IDENTICAL margin — because both
     * horizontal axes clamp to the eye symmetrically. That is what doubles the upward family's candidates from four
     * foot columns to eight, and in a farm layout the corners are more often free than the sides.
     */
    @Test
    public void cornerStanceHasTheIdenticalMargin() {
        Vec3 eye = new Vec3(T.getX() + 1.30D, T.getY() + 0.27D, T.getZ() + 1.30D);

        PlacementGeometry.AlignedPoint aligned = PlacementGeometry.mostAlignedPointOnFaceWithMargin(
                eye, T.above(), FULL_CUBE, Direction.DOWN, Direction.UP);

        assertNotNull(aligned);
        assertEquals(T.getX() + 0.98D, aligned.point().x, EPS);
        assertEquals(T.getY() + 1.00D, aligned.point().y, EPS);
        assertEquals(T.getZ() + 0.98D, aligned.point().z, EPS);

        Vec3 d = aligned.point().subtract(eye);
        assertEquals(-0.32D, d.x, EPS);
        assertEquals(+0.73D, d.y, EPS);
        assertEquals(-0.32D, d.z, EPS);

        assertEquals("identical to the side stance, not merely close", 0.41D, aligned.margin(), EPS);
        assertEquals(0.86D, d.length(), 0.005D);
    }

    /**
     * The margin form and the boolean form must be the same decision, not two decisions that usually agree — the
     * planner selects on one and V2's proven geometry is the other, and a point one accepts and the other refuses is
     * a placement nobody ever proved.
     *
     * <p>Swept over every face normal, every look and a grid of eyes covering both sides of the block, so the
     * backwards-aim case (where {@code along} is negative and an absolute-value margin would wrongly report a win) is
     * densely represented.
     */
    @Test
    public void marginFormAgreesWithTheBooleanFormOnEveryInput() {
        BlockPos against = T.above();
        int compared = 0;
        int accepted = 0;
        for (double ex = -2.0D; ex <= 3.0D; ex += 0.35D) {
            for (double ey = -2.0D; ey <= 3.0D; ey += 0.35D) {
                for (double ez = -2.0D; ez <= 3.0D; ez += 0.35D) {
                    Vec3 eye = new Vec3(ex, ey, ez);
                    for (Direction faceNormal : Direction.values()) {
                        for (Direction look : Direction.values()) {
                            Vec3 plain = PlacementGeometry.mostAlignedPointOnFace(
                                    eye, against, FULL_CUBE, faceNormal, look);
                            PlacementGeometry.AlignedPoint withMargin =
                                    PlacementGeometry.mostAlignedPointOnFaceWithMargin(
                                            eye, against, FULL_CUBE, faceNormal, look);
                            compared++;
                            assertEquals("accept/refuse must agree exactly at eye=" + eye + " face=" + faceNormal
                                    + " look=" + look, plain == null, withMargin == null);
                            if (plain != null) {
                                accepted++;
                                assertEquals("same closed form, so the same point", plain, withMargin.point());
                                assertTrue("a listed AlignedPoint is strictly dominant by construction",
                                        withMargin.margin() > 0.0D);
                            }
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must actually exercise both outcomes", accepted > 0 && accepted < compared);
    }

    /**
     * {@code aimPointsOnFace} derives at most one point per candidate look and usually one in total, because the
     * opposite sign aims backwards and is dropped without ever casting a ray. Six faces times two looks replaces six
     * times five sampled points.
     */
    @Test
    public void derivedAimPointsDropTheBackwardsSignWithoutARay() {
        BlockState piston = Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN);
        Vec3 eye = new Vec3(T.getX() + 1.30D, T.getY() + 0.27D, T.getZ() + 0.50D);

        // against = UP: the block to click is the one above T, and its face normal is therefore DOWN.
        List<Vec3> points = PlacementGeometry.aimPointsOnFace(eye, T.above(), FULL_CUBE, Direction.UP, piston);
        assertEquals("look=UP survives, look=DOWN aims backwards from an eye below the face", 1, points.size());
        assertEquals(T.getY() + 1.00D, points.get(0).y, EPS);

        List<PlacementGeometry.AlignedPoint> withMargin =
                PlacementGeometry.aimPointsOnFaceWithMargin(eye, T.above(), FULL_CUBE, Direction.UP, piston);
        assertEquals("same points, same order", points.size(), withMargin.size());
        assertEquals(points.get(0), withMargin.get(0).point());
        assertEquals(0.41D, withMargin.get(0).margin(), EPS);
    }

    /** A state whose facing the look does not decide falls back to the five sampled points, and they carry an
     *  infinite margin: there is no dominance contest, so the aim cannot lose one. */
    @Test
    public void unconstrainedAimPointsAreSampledAndCarryNoDominanceContest() {
        Vec3 eye = new Vec3(T.getX() + 2.5D, T.getY() + 1.27D, T.getZ() + 0.5D);
        BlockState stone = Blocks.STONE.defaultBlockState();

        List<Vec3> points = PlacementGeometry.aimPointsOnFace(eye, T.east(), FULL_CUBE, Direction.EAST, stone);
        assertEquals("centre plus four offsets — two points down a side face is a thin target", 5, points.size());

        List<PlacementGeometry.AlignedPoint> withMargin =
                PlacementGeometry.aimPointsOnFaceWithMargin(eye, T.east(), FULL_CUBE, Direction.EAST, stone);
        assertEquals(5, withMargin.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(points.get(i), withMargin.get(i).point());
            assertEquals(Double.POSITIVE_INFINITY, withMargin.get(i).margin(), 0.0D);
            assertFalse("a full cube has no facing to get wrong, so it is never TIGHT",
                    withMargin.get(i).margin() < PlacementGeometry.MIN_MARGIN);
        }
    }

    /** The threshold is one number, not two: {@link PlacementGeometry#MIN_MARGIN} and {@link SolveBudget#MIN_MARGIN}
     *  must never drift apart, because one gates the geometry and the other gates the search that uses it. */
    @Test
    public void minMarginIsOneNumber() {
        assertEquals(0.15D, PlacementGeometry.MIN_MARGIN, 0.0D);
        assertEquals(SolveBudget.MIN_MARGIN, PlacementGeometry.MIN_MARGIN, 0.0D);
        assertEquals(0.02D, PlacementGeometry.FACE_INSET, 0.0D);
    }

    // ------------------------------------------------------------------------------------- the support-face rules

    @Test
    public void wallMountedBlocksNameTheirOwnSupportFace() {
        BlockState wallTorch = Blocks.WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        assertTrue(PlacementGeometry.isWallMounted(wallTorch));
        assertEquals("facing points AWAY from the wall it hangs on",
                Direction.WEST, PlacementGeometry.requiredSupportDirection(wallTorch));
        assertEquals("one face, not six — the other five were rays whose outcome was known before casting",
                1, PlacementGeometry.supportDirectionsFor(wallTorch).length);

        BlockState wallLever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        assertEquals(Direction.SOUTH, PlacementGeometry.requiredSupportDirection(wallLever));
        assertEquals(Direction.DOWN, PlacementGeometry.requiredSupportDirection(
                wallLever.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)));
        assertEquals(Direction.UP, PlacementGeometry.requiredSupportDirection(
                wallLever.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING)));

        assertNull("hangs BETWEEN its neighbours, so its support is perpendicular to facing",
                PlacementGeometry.requiredSupportDirection(Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState()));
        assertNull(PlacementGeometry.requiredSupportDirection(Blocks.STONE.defaultBlockState()));
    }

    /** FaceAttachedHorizontalDirectionalBlock derives a wall lever's facing from the clicked face, not the look. */
    @Test
    public void wallLeverSimulationDerivesEveryHorizontalFacingFromTheClickedFace() {
        PredictedWorld world = PredictedWorld.capture(
                (x, y, z) -> Blocks.AIR.defaultBlockState(),
                (x, z) -> true,
                new Vec3i(-4, -4, -4), new Vec3i(4, 4, 4), 0);
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);

        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos against = T.relative(facing.getOpposite());
            Vec3 aim = Vec3.atCenterOf(against.relative(facing));
            BlockState landed = oracle.simulate(world, new ItemStack(Items.LEVER), against, facing, aim,
                    new Rotation(37.0F, -11.0F), true);

            assertNotNull(facing.toString(), landed);
            assertEquals(AttachFace.WALL, landed.getValue(BlockStateProperties.ATTACH_FACE));
            assertEquals("clicked face is the wall lever's outward facing",
                    facing, landed.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
    }

    /**
     * The trap this file's hopper branch fell into for its whole existence, pinned so it cannot be re-introduced.
     *
     * <p>{@code HopperBlock.FACING} is {@code BlockStateProperties.FACING_HOPPER}, a DIFFERENT object from
     * {@code BlockStateProperties.FACING} (five values, no UP), and {@code StateHolder.valueIndex} scans its key array
     * with {@code if_acmpne} — reference identity. {@code Property.equals} compares class and name and WOULD match,
     * but nothing on this path calls it. So {@code hasProperty(BlockStateProperties.FACING)} is false for every hopper
     * in the game, and every branch keyed on that object silently skipped them.
     *
     * <p>The first two assertions are the trap itself and must keep holding — they are what makes the third one's
     * property name load-bearing rather than decorative.
     */
    @Test
    public void hopperFacingIsFacingHopperAndTheLookupIsByIdentity() {
        BlockState hopper = Blocks.HOPPER.defaultBlockState();
        assertFalse("FACING_HOPPER is not FACING, and the lookup is ==",
                hopper.hasProperty(BlockStateProperties.FACING));
        assertTrue("but it does carry HopperBlock.FACING, which is what every branch must key on",
                hopper.hasProperty(HopperBlock.FACING));
        assertNotSame("the two Property objects are distinct instances despite sharing the name \"facing\"",
                BlockStateProperties.FACING, HopperBlock.FACING);
    }

    /**
     * A horizontal hopper has exactly ONE neighbour that can produce it, and a {@code facing=down} hopper has two.
     *
     * <p>{@code HopperBlock.getStateForPlacement} is {@code getClickedFace().getOpposite()} with a vertical result
     * collapsed to {@code DOWN} (26.1.2 bytecode, verbatim). So for a horizontal facing the support IS the facing —
     * click the face of {@code T+facing} that points back at {@code T}. That is not a narrowing heuristic, it is the
     * whole truth about the block, and it is why 44 of etz-basalt's hoppers can only be reached from one specific
     * neighbour.
     *
     * <p>{@code facing=down} returns null rather than a direction: it is produced by ANY vertical click, the top of
     * the block below or the underside of the block above, and one {@code Direction} cannot say "either". Null keeps
     * both and lets {@code simulate} refuse the four lateral faces by name. The old code returned {@code UP} here,
     * which kept only the rarer of the two.
     */
    @Test
    public void hopperSupportIsTheClickedFaceAndAHorizontalHopperHasExactlyOne() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState hopper = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing);
            assertEquals("the support direction IS the facing", facing,
                    PlacementGeometry.requiredSupportDirection(hopper));
            assertEquals("one neighbour, not six — the other five were rays whose outcome was known in advance",
                    1, PlacementGeometry.supportDirectionsFor(hopper).length);
            assertEquals(facing, PlacementGeometry.supportDirectionsFor(hopper)[0]);
        }
        BlockState down = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN);
        assertNull("either vertical click produces facing=down, and one Direction cannot say \"either\"",
                PlacementGeometry.requiredSupportDirection(down));
        assertEquals(6, PlacementGeometry.supportDirectionsFor(down).length);
    }

    /**
     * The round trip: the clicked face alone decides a hopper's landed facing, and a wrong face lands a wrong facing
     * that the acceptance test then refuses BY NAME rather than clicking.
     *
     * <p>This is the assertion that would have caught the original defect. Before the fix {@code simulate} left the
     * item default {@code facing=down} on every hopper for every face, so {@code predict} refused all 140 of
     * etz-basalt's as {@code WRONG_STATE_WOULD_LAND} and not one appeared in 4250 plan lines.
     */
    @Test
    public void hopperSimulationDerivesFacingFromTheClickedFaceAlone() {
        PredictedWorld world = PredictedWorld.capture(
                (x, y, z) -> Blocks.AIR.defaultBlockState(),
                (x, z) -> true,
                new Vec3i(-4, -4, -4), new Vec3i(4, 4, 4), 0);
        PlacementOracle oracle = new PlacementOracle(V3Settings.defaults(), PlayerPose.CROUCHED);

        for (Direction face : Direction.values()) {
            BlockPos against = T.relative(face.getOpposite());
            Vec3 aim = Vec3.atCenterOf(against.relative(face));
            BlockState landed = oracle.simulate(world, new ItemStack(Items.HOPPER), against, face, aim,
                    new Rotation(37.0F, -11.0F), false);

            assertNotNull(face.toString(), landed);
            Direction expected = face.getOpposite().getAxis() == Direction.Axis.Y
                    ? Direction.DOWN : face.getOpposite();
            assertEquals("landed facing is the clicked face's opposite, vertical collapsing to DOWN",
                    expected, landed.getValue(HopperBlock.FACING));
        }

        // And the gate still refuses the wrong face rather than accepting it: clicking NORTH lands facing=south, and
        // a schematic wanting facing=east is told so instead of being placed backwards.
        BlockPos against = T.relative(Direction.SOUTH);
        BlockState wrong = oracle.simulate(world, new ItemStack(Items.HOPPER), against, Direction.NORTH,
                Vec3.atCenterOf(T), new Rotation(37.0F, -11.0F), false);
        assertEquals(Direction.SOUTH, wrong.getValue(HopperBlock.FACING));
        assertFalse("a wrong face lands a wrong facing and is refused, not waved through",
                V3Settings.defaults().valid(wrong,
                        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST), true));
    }

    /**
     * Look-derived facings are ORDERED, never narrowed. Narrowing is the most expensive mistake in this file's
     * history: constraining the neighbour to the facing axis starved 624 of etz-basalt's 672 sticky pistons of every
     * clickable face at once, and the stance report read 49 standable, 0 workable, 49 "nothing solid to click".
     */
    @Test
    public void lookDerivedFacingsAreOrderedNotNarrowed() {
        BlockState piston = Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN);

        Direction[] ordered = PlacementGeometry.supportDirectionsFor(piston);
        assertEquals("all six survive — the lateral neighbours are what made those pistons placeable at all",
                Direction.values().length, ordered.length);
        assertEquals("the sure thing first: clicking T+L sends the ray straight down L", Direction.UP, ordered[0]);
        assertEquals(Direction.DOWN, ordered[1]);
        for (Direction d : Direction.values()) {
            int seen = 0;
            for (Direction o : ordered) {
                if (o == d) {
                    seen++;
                }
            }
            assertEquals("every face exactly once: " + d, 1, seen);
        }

        assertEquals("a state with no look preference keeps vanilla's own order",
                Direction.values().length,
                PlacementGeometry.supportDirectionsFor(Blocks.STONE.defaultBlockState()).length);
    }

    // ---------------------------------------------------------------------------------------- check A1, the body

    /**
     * Check A1 measured against the real collision shape rather than a full cube. A bottom slab occupies only
     * {@code T.y..T.y+0.5}, so a body standing in the upper half of T is legal — with a full-cube test that stance is
     * thrown away for nothing, and stances are the scarce resource in a sparse layer.
     */
    @Test
    public void bodyInTheUpperHalfOfABottomSlabIsLegal() {
        VoxelShape slab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                .getCollisionShape(null, null);
        Vec3 upperHalf = new Vec3(T.getX() + 0.5D, T.getY() + 0.5D, T.getZ() + 0.5D);

        assertFalse("the body rests ON the slab: they share the plane y+0.5 and sharing is not intersecting",
                PlacementGeometry.playerBodyIntersects(upperHalf, 1.5D, T, slab));
        assertTrue("standing on the floor of T, the feet are inside the slab",
                PlacementGeometry.playerBodyIntersects(
                        new Vec3(T.getX() + 0.5D, T.getY(), T.getZ() + 0.5D), 1.5D, T, slab));
    }

    /** The same stance against a full cube is refused, because there vanilla's {@code isUnobstructed} really would
     *  see the bot inside the block it is placing and refuse the bot's own placement. */
    @Test
    public void bodyInsideAFullCubeIsRefused() {
        VoxelShape cube = Blocks.STONE.defaultBlockState().getCollisionShape(null, null);

        assertTrue(PlacementGeometry.playerBodyIntersects(
                new Vec3(T.getX() + 0.5D, T.getY() + 0.5D, T.getZ() + 0.5D), 1.5D, T, cube));
        assertTrue(PlacementGeometry.playerBodyIntersects(
                new Vec3(T.getX() + 0.5D, T.getY(), T.getZ() + 0.5D), 1.5D, T, cube));
        assertFalse("standing on top of it is not standing in it",
                PlacementGeometry.playerBodyIntersects(
                        new Vec3(T.getX() + 0.5D, T.getY() + 1.0D, T.getZ() + 0.5D), 1.5D, T, cube));
        // Brackets the body WIDTH, which nothing else here does. T's east wall is x=1.0, so with the vanilla 0.6 width
        // the box reaches 0.3 either side of the centre: a centre at 1.2 still has 0.1 of itself inside the cell, a
        // centre at 1.35 clears it by 0.05. The pair is deliberately tight on both sides -- a lone far-away assertFalse
        // passes for any half-width under 0.8 and pins nothing, so a body silently built at half or double width would
        // sail through it and only show up as stances rejected in the field that the proof said were fine.
        assertTrue("0.2 outside the wall, but the body's own 0.3 reaches back in",
                PlacementGeometry.playerBodyIntersects(
                        new Vec3(T.getX() + 1.2D, T.getY(), T.getZ() + 0.5D), 1.5D, T, cube));
        assertFalse("0.35 outside, and 0.3 of body does not span the gap",
                PlacementGeometry.playerBodyIntersects(
                        new Vec3(T.getX() + 1.35D, T.getY(), T.getZ() + 0.5D), 1.5D, T, cube));
    }

    /** A shape that collides with nothing can obstruct nothing — a torch, a sign, redstone. These fall out for free
     *  rather than needing a special case at every call site. */
    @Test
    public void emptyCollisionShapesNeverObstruct() {
        VoxelShape torch = Blocks.TORCH.defaultBlockState().getCollisionShape(null, null);
        assertTrue(torch.isEmpty());
        assertFalse(PlacementGeometry.playerBodyIntersects(
                new Vec3(T.getX() + 0.5D, T.getY(), T.getZ() + 0.5D), 1.5D, T, torch));
    }

    // ------------------------------------------------------------------------------------- the classification sets

    /**
     * Every name in this set was confirmed against 26.1.2's own bytecode, and getting the set wrong is silent and
     * total: the builder compares what it wanted against what landed, they differ in a property no click can
     * influence, the cell is "incorrect" forever, and with {@code buildInLayers} and {@code skipFailedLayers=false} a
     * single such cell stops the whole build at its layer.
     */
    @Test
    public void autoResolvedPropertiesKeepTheBytecodeVerifiedList() {
        for (String environmentResolved : new String[]{
                "north", "east", "south", "west", "up", "down",
                "powered", "power", "in_wall", "distance", "persistent", "shape",
                "locked", "instrument", "enabled", "extended", "bottom", "triggered", "lit", "short",
                "snowy", "attached", "disarmed", "signal_fire", "occupied",
                "slot_0_occupied", "slot_5_occupied", "has_book", "has_record", "has_bottle_0", "level",
                "age", "stage", "honey_level", "bites", "eggs", "hatch",
                "moisture", "hydration", "dusted", "berries", "cracked"}) {
            assertTrue("must stay environment-resolved: " + environmentResolved,
                    PlacementGeometry.AUTO_RESOLVED_PROP_NAMES.contains(environmentResolved));
        }
        // Kept OFF on purpose: the things a human actually aims, plus waterlogged, which the bucket pass owns.
        for (String aimed : new String[]{"facing", "half", "hinge", "axis", "type", "waterlogged", "open"}) {
            assertFalse("must stay enforced: " + aimed,
                    PlacementGeometry.AUTO_RESOLVED_PROP_NAMES.contains(aimed));
        }
        // Not placeable, but a right-click fixes them — enforced for correctness, never broken and re-placed.
        for (String clickable : new String[]{"open", "delay", "mode", "note", "inverted"}) {
            assertTrue("must stay interaction-fixable: " + clickable,
                    PlacementGeometry.INTERACTION_PROP_NAMES.contains(clickable));
        }
    }

    /** {@code shape} reads like geometry and is not placeable: vanilla derives it in {@code getStateForPlacement} and
     *  recomputes it in {@code updateShape}. Enforcing it made the builder reject its own correct placement forever,
     *  which is why a stair is geometry-sensitive but a plain cube is not. */
    @Test
    public void geometrySensitivityMatchesTheV2Classification() {
        assertFalse(PlacementGeometry.placementStateIsGeometrySensitive(Blocks.STONE.defaultBlockState()));
        assertFalse(PlacementGeometry.placementStateIsGeometrySensitive(
                Blocks.BLACK_STAINED_GLASS.defaultBlockState()));
        assertTrue(PlacementGeometry.placementStateIsGeometrySensitive(Blocks.OAK_LOG.defaultBlockState()));
        assertTrue(PlacementGeometry.placementStateIsGeometrySensitive(Blocks.STONE_SLAB.defaultBlockState()));
        assertTrue("null is unknown, and unknown is not safe",
                PlacementGeometry.placementStateIsGeometrySensitive(null));

        assertFalse(PlacementGeometry.shouldUseStanceRecovery(Blocks.STONE.defaultBlockState(), true));
        assertTrue(PlacementGeometry.shouldUseStanceRecovery(Blocks.STONE.defaultBlockState(), false));
        assertTrue(PlacementGeometry.shouldUseStanceRecovery(Blocks.OBSERVER.defaultBlockState(), true));
    }

    /** Pistons and dispensers are excluded on purpose — adding them cost etz-basalt 400 blocks, twice. This list is
     *  about which blocks the DEFAULT adjacency goal gets wrong, not which blocks have a facing. */
    @Test
    public void orientationSensitiveListStaysTight() {
        assertTrue(PlacementGeometry.isOrientationSensitive(Blocks.OAK_DOOR.defaultBlockState()));
        assertTrue(PlacementGeometry.isOrientationSensitive(Blocks.RED_BED.defaultBlockState()));
        assertTrue(PlacementGeometry.isOrientationSensitive(Blocks.REPEATER.defaultBlockState()));
        assertTrue(PlacementGeometry.isOrientationSensitive(Blocks.COMPARATOR.defaultBlockState()));
        assertTrue(PlacementGeometry.isOrientationSensitive(Blocks.OBSERVER.defaultBlockState()));
        assertTrue(PlacementGeometry.isOrientationSensitive(Blocks.OAK_TRAPDOOR.defaultBlockState()));

        assertFalse("400 blocks, twice", PlacementGeometry.isOrientationSensitive(Blocks.PISTON.defaultBlockState()));
        assertFalse(PlacementGeometry.isOrientationSensitive(Blocks.DISPENSER.defaultBlockState()));
        assertFalse(PlacementGeometry.isOrientationSensitive(Blocks.OAK_STAIRS.defaultBlockState()));
        assertFalse(PlacementGeometry.isOrientationSensitive(Blocks.CHEST.defaultBlockState()));
        assertFalse(PlacementGeometry.isOrientationSensitive(null));
    }

    /** The second cell of a two-block placement is filled in by vanilla when the base goes down, so it can never be
     *  planned as its own action — and, on a layered build, must not be counted as unresolved either. */
    @Test
    public void secondaryHalvesAreNeverTheirOwnTarget() {
        assertTrue(PlacementGeometry.isSecondaryHalf(Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)));
        assertFalse(PlacementGeometry.isSecondaryHalf(Blocks.OAK_DOOR.defaultBlockState()));
        assertFalse(PlacementGeometry.isSecondaryHalf(Blocks.RED_BED.defaultBlockState()));
        assertFalse(PlacementGeometry.isSecondaryHalf(null));

        assertTrue(PlacementGeometry.isTraversalInteraction(Blocks.OAK_DOOR.defaultBlockState()));
        assertTrue(PlacementGeometry.isTraversalInteraction(Blocks.OAK_FENCE_GATE.defaultBlockState()));
        assertFalse("a repeater's delay never affects walking",
                PlacementGeometry.isTraversalInteraction(Blocks.REPEATER.defaultBlockState()));
    }

    /** An iron door or trapdoor placed closed IS built correctly; whether it is open afterwards is the redstone's
     *  business. Without this the builder broke and re-placed the same 40 iron trapdoors for the whole run. */
    @Test
    public void ironDoorsOpenStateIsBeyondOurControl() {
        assertTrue(PlacementGeometry.beyondOurControl(Blocks.IRON_TRAPDOOR, "open"));
        assertTrue(PlacementGeometry.beyondOurControl(Blocks.IRON_DOOR, "open"));
        assertFalse("a wooden one opens under a right-click, so it belongs to the interaction pass",
                PlacementGeometry.beyondOurControl(Blocks.OAK_TRAPDOOR, "open"));
        assertTrue(PlacementGeometry.beyondOurControl(Blocks.OAK_FENCE, "north"));
        assertFalse(PlacementGeometry.beyondOurControl(Blocks.OAK_STAIRS, "facing"));
    }

    /** Repeater delay wraps 4 to 1, so the click count is modular and never negative. */
    @Test
    public void interactionClicksCountTheRightClicks() {
        BlockState delayOne = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 1);
        BlockState delayThree = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 3);
        BlockState delayFour = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 4);

        assertEquals(2, PlacementGeometry.interactionClicks(delayOne, delayThree));
        assertEquals("wraps, never goes backwards", 1, PlacementGeometry.interactionClicks(delayFour, delayOne));
        assertEquals(0, PlacementGeometry.interactionClicks(delayThree, delayThree));

        assertTrue(PlacementGeometry.matchesExceptInteraction(delayOne, delayThree));
        assertFalse(PlacementGeometry.matchesExceptInteraction(delayOne, Blocks.COMPARATOR.defaultBlockState()));

        BlockState closedIron = Blocks.IRON_TRAPDOOR.defaultBlockState();
        BlockState openIron = closedIron.setValue(BlockStateProperties.OPEN, true);
        assertEquals("only redstone toggles these, so it is not interaction-fixable",
                -1, PlacementGeometry.interactionClicks(closedIron, openIron));
    }

    /** One item routinely places TWO blocks: a torch becomes {@code torch} on the ground and {@code wall_torch} on a
     *  side. Comparing the placing ITEM covers that whole family; without it the builder reported the torches in its
     *  own inventory as a missing material and paused the build. */
    @Test
    public void itemIdentityCoversTheStandingAndWallForms() {
        BlockState standing = Blocks.TORCH.defaultBlockState();
        BlockState onAWall = Blocks.WALL_TORCH.defaultBlockState();

        assertTrue(PlacementGeometry.itemCanPlaceBlock(standing, onAWall));
        assertTrue(PlacementGeometry.itemCanPlaceBlock(onAWall, standing));
        assertFalse(PlacementGeometry.itemCanPlaceBlock(Blocks.STONE.defaultBlockState(), standing));
        assertTrue(PlacementGeometry.containsBlockState(List.of(Blocks.STONE.defaultBlockState(), standing), onAWall));
        assertFalse(PlacementGeometry.containsBlockState(List.of(Blocks.STONE.defaultBlockState()), onAWall));
    }

    // ------------------------------------------------------------------- acceptance, against an explicit snapshot

    /** {@link V3Settings#defaults()} exists so these two can be exercised at all — reading the live settings from a
     *  test throws {@code ExceptionInInitializerError} before the first assertion runs. */
    @Test
    public void acceptancePredicatesReadTheSnapshotAndNothingElse() {
        V3Settings stock = V3Settings.defaults();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        assertTrue(PlacementGeometry.valid(stock, stone, stone, false));
        assertFalse(PlacementGeometry.valid(stock, air, stone, false));
        assertTrue("null desired means outside the schematic", PlacementGeometry.valid(stock, stone, null, false));
        assertTrue(PlacementGeometry.valid(stock, air, air, false));

        // A fence set between two posts stands unconnected until its neighbours exist, and is CORRECT the instant it
        // is placed — the connection properties are the game's to write, not the builder's.
        BlockState bareFence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState connectedFence = bareFence.setValue(BlockStateProperties.NORTH, true);
        assertTrue(PlacementGeometry.sameBlockstate(stock, connectedFence, bareFence));
        assertFalse(PlacementGeometry.sameBlockstate(stock, stone, Blocks.DIRT.defaultBlockState()));
    }

    /** {@code buildIgnoreDirection} bypasses {@link PlacementGeometry#ORIENTATION_PROPS}, which are matched by
     *  Property object rather than by name — so the set has to hold the very instances the blocks use. */
    @Test
    public void buildIgnoreDirectionBypassesTheOrientationProperties() {
        BlockState facingNorth = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        BlockState facingSouth = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);

        assertFalse("stock config enforces the facing a human aimed",
                PlacementGeometry.sameBlockstate(V3Settings.defaults(), facingNorth, facingSouth));

        V3Settings ignoreDirection = new V3Settings(true, List.of(), false, List.of(), List.of(), false, Map.of());
        assertTrue(PlacementGeometry.sameBlockstate(ignoreDirection, facingNorth, facingSouth));
        assertTrue(PlacementGeometry.ORIENTATION_PROPS.contains(StairBlock.FACING));
    }

    // ------------------------------------------------------------------------------------------------- identity

    @Test
    public void positionKeysAgreeWithVanillaAndWithEachOther() {
        BlockPos pos = new BlockPos(-1234, 71, 5678);
        assertEquals(pos.asLong(), PlacementGeometry.positionKey(pos));
        assertEquals(PlacementGeometry.positionKey(pos),
                PlacementGeometry.positionKey(pos.getX(), pos.getY(), pos.getZ()));
        assertEquals("stone", PlacementGeometry.blockName(Blocks.STONE.defaultBlockState()));
        assertEquals("oak_stairs", PlacementGeometry.blockName(Blocks.OAK_STAIRS.defaultBlockState()));
    }
}
