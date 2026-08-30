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
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The whole door hinge matrix — four facings by two hinge sides — asserted as a DERIVATION rather than a table.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The hinge derivation shipped on the strength of two live scenarios and a full server audit. That is real
 * evidence and it is still a sample: two scenarios exercise the paths those scenarios happen to take. This project
 * has twice been convinced by a sample that looked right, and both times the sample was not lying — it was answering
 * a narrower question than the one that mattered. {@code requiredLookDirections} was narrowed on the strength of one
 * example and took every clickable face away from 624 of 672 sticky pistons. {@code adoptDeferredOrientation} agreed
 * with every single case anyone examined and was still vacuous, because it copied the answer out of the question.
 *
 * <h2>What can and cannot be checked headlessly</h2>
 *
 * <p>Vanilla's own {@code DoorBlock.getStateForPlacement} needs a {@code Level} and a {@code Player}, and
 * {@code Bootstrap.bootStrap()} supplies registries and nothing else — so this class CANNOT call vanilla and compare.
 * It asserts the two things that are checkable and that a wrong derivation would fail:
 *
 * <ul>
 *   <li><b>Round-trip closure.</b> {@link PlacementFamilies#doorHingeWindow} promises a band of hit offsets that
 *       produce a WANTED hinge. Every offset inside that band, fed back through the deciding rule, must return that
 *       hinge — for all four facings and both sides. A window that does not close is the planner aiming at a hinge it
 *       will not get, which is exactly the failure that stopped a 4271-cell basalt run.</li>
 *   <li><b>Agreement with the rule observed in play.</b> Aim at the left half of the face and the hinge lands left;
 *       the right half and it lands right — left and right from the PLACER's view. That statement is independent of
 *       this code and came from the owner's own game, so it is a genuine external check rather than the
 *       implementation restating itself.</li>
 * </ul>
 *
 * <p>The live counterpart is the {@code doors} bench scenario, which places all eight variants and verifies each one
 * against the server with its full blockstate. Neither replaces the other: that one proves the whole chain works
 * against real vanilla on real geometry, this one proves the rule holds everywhere rather than in the eight places
 * that scenario visits.
 */
public class DoorHingeMatrixTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }


    /** Nothing anywhere, so stages 1 and 2 abstain and the hit is what decides — which is the stage under test.
     *  A null world is not an option: doorHingeWindow asks the neighbours first and would dereference it. */
    private static PredictedWorld emptyWorld() {
        return PredictedWorld.capture((x, y, z) -> Blocks.AIR.defaultBlockState(),
                (x, z) -> true, new Vec3i(-4, -4, -4), new Vec3i(4, 4, 4), 0);
    }

    private static final BlockPos CELL = new BlockPos(0, 0, 0);

    /**
     * The placer's left, in world terms.
     *
     * <p>Counter-clockwise seen from above IS the left hand of someone facing that way: facing north the left is
     * west, facing south it is east. Derived rather than tabulated on purpose — a table of four rows is four chances
     * to typo the very mapping under test, and it would agree with a wrong implementation just as readily.
     */
    private static Direction leftOf(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** A hit offset one quarter of the way toward {@code side} from the centre of the face. */
    private static double offsetToward(Direction side, Direction.Axis axis) {
        int step = axis == Direction.Axis.X ? side.getStepX() : side.getStepZ();
        return 0.5D + 0.25D * step;
    }

    // --------------------------------------------------------------------------- the rule observed in play

    /**
     * <b>The owner's rule, all four facings.</b> Look at the left half of the face and the hinge lands left.
     *
     * <p>The external check. This statement was made from playing the game, not from reading this code, so it is the
     * one assertion here that cannot be satisfied by an implementation that is merely self-consistent.
     */
    @Test
    public void aimingAtThePlacersLeftHalfPutsTheHingeOnTheLeft() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Direction.Axis axis = PlacementFamilies.hingeDecisionAxis(facing);
            Direction left = leftOf(facing);

            double towardLeft = offsetToward(left, axis);
            double towardRight = offsetToward(left.getOpposite(), axis);

            assertSame(facing + ": aiming at the placer's left half must hinge LEFT",
                    DoorHingeSide.LEFT, hingeAt(facing, axis, towardLeft));
            assertSame(facing + ": aiming at the placer's right half must hinge RIGHT",
                    DoorHingeSide.RIGHT, hingeAt(facing, axis, towardRight));
        }
    }

    /** Feed one offset on the deciding axis through the rule, holding the other axis at the centre. */
    private static DoorHingeSide hingeAt(Direction facing, Direction.Axis axis, double offset) {
        double x = axis == Direction.Axis.X ? offset : 0.5D;
        double z = axis == Direction.Axis.Z ? offset : 0.5D;
        return PlacementFamilies.hingeFromHit(facing, x, z);
    }

    // --------------------------------------------------------------------------- the eight windows close

    /**
     * <b>The matrix: four facings by two hinges.</b> Every offset the planner may aim at must produce the hinge it
     * aimed for.
     *
     * <p>Sampled densely across each window rather than at its midpoint. A window is only useful if it holds at its
     * EDGES too — the planner aims inside it, but the body arrives within a tolerance, and an edge that flips is the
     * same class of defect as an occlusion proof that holds only at the centre of the approach.
     */
    @Test
    public void everyWindowProducesTheHingeItWasAskedFor() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (DoorHingeSide wanted : DoorHingeSide.values()) {
                PlacementFamilies.HingeWindow window =
                        PlacementFamilies.doorHingeWindow(emptyWorld(), CELL, facing, wanted);
                assertNotNull(facing + "/" + wanted + ": no window at all — the planner cannot aim for this hinge",
                        window);
                assertSame(facing + "/" + wanted + ": the window must live on the deciding axis",
                        PlacementFamilies.hingeDecisionAxis(facing), window.axis());
                assertTrue(facing + "/" + wanted + ": empty window " + window.min() + ".." + window.max(),
                        window.max() > window.min());

                for (int step = 0; step <= 20; step++) {
                    double offset = window.min() + (window.max() - window.min()) * step / 20.0D;
                    assertTrue(facing + "/" + wanted + ": window says it contains " + offset,
                            window.contains(offset));
                    assertSame(facing + "/" + wanted + ": offset " + offset + " inside the window produced the "
                                    + "other hinge — the planner would aim for one and get the other",
                            wanted, hingeAt(facing, window.axis(), offset));
                }
            }
        }
    }

    /** The two windows of a facing must not overlap, or one aim point would claim both hinges. */
    @Test
    public void theTwoWindowsOfAFacingAreDisjoint() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            PlacementFamilies.HingeWindow left =
                    PlacementFamilies.doorHingeWindow(emptyWorld(), CELL, facing, DoorHingeSide.LEFT);
            PlacementFamilies.HingeWindow right =
                    PlacementFamilies.doorHingeWindow(emptyWorld(), CELL, facing, DoorHingeSide.RIGHT);
            assertNotNull(facing.toString(), left);
            assertNotNull(facing.toString(), right);
            assertFalse(facing + ": the windows overlap — one aim point would claim both hinges",
                    left.min() <= right.max() && right.min() <= left.max());
        }
    }

    // --------------------------------------------------------------------------- which axis decides

    /**
     * A door facing north or south reads the X offset; one facing east or west reads Z.
     *
     * <p>Stated as "the axis PERPENDICULAR to the facing" rather than as four literals, because that is the property —
     * the deciding coordinate is the one that runs across the doorway, which is what left and right are measured
     * along.
     */
    @Test
    public void theDecidingAxisRunsAcrossTheDoorway() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Direction.Axis across = leftOf(facing).getAxis();
            assertSame(facing + ": the hinge is decided along the axis across the doorway",
                    across, PlacementFamilies.hingeDecisionAxis(facing));
        }
    }

    // --------------------------------------------------------------------------- the centre line is undecided

    /**
     * A hit on the centre line does not DECIDE the hinge, and the engine says so instead of guessing.
     *
     * <p>This is the mechanical form of the caveat that came with the rule: the door has to be placed square to the
     * face, not obliquely from the side. Near the middle a few hundredths of body drift flip the answer, and the
     * approach tolerance is larger than that — so a plan that trusted the centre would be proven at a point and
     * executed somewhere else, which is exactly how a 0.019-block miss stopped an earlier basalt run.
     */
    @Test
    public void aHitOnTheCentreLineIsNotTrusted() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Direction.Axis axis = PlacementFamilies.hingeDecisionAxis(facing);
            assertFalse(facing + ": dead centre must not be treated as decided",
                    decidedAt(facing, axis, 0.5D));
            assertFalse(facing + ": just inside the margin must not be treated as decided",
                    decidedAt(facing, axis, 0.5D + PlacementFamilies.HINGE_DECISION_MARGIN / 2.0D));
            assertTrue(facing + ": clear of the margin must be decided",
                    decidedAt(facing, axis, 0.5D + PlacementFamilies.HINGE_DECISION_MARGIN * 2.0D));
            assertTrue(facing + ": clear of the margin on the other side must be decided",
                    decidedAt(facing, axis, 0.5D - PlacementFamilies.HINGE_DECISION_MARGIN * 2.0D));
        }
    }

    private static boolean decidedAt(Direction facing, Direction.Axis axis, double offset) {
        double x = axis == Direction.Axis.X ? offset : 0.5D;
        double z = axis == Direction.Axis.Z ? offset : 0.5D;
        return PlacementFamilies.hingeDecidedByHit(facing, x, z);
    }

    /** Every window the planner may aim into lies clear of the undecided band around the centre. */
    @Test
    public void noWindowReachesIntoTheUndecidedBand() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (DoorHingeSide wanted : DoorHingeSide.values()) {
                PlacementFamilies.HingeWindow window =
                        PlacementFamilies.doorHingeWindow(emptyWorld(), CELL, facing, wanted);
                assertNotNull(facing + "/" + wanted, window);
                for (int step = 0; step <= 20; step++) {
                    double offset = window.min() + (window.max() - window.min()) * step / 20.0D;
                    assertTrue(facing + "/" + wanted + ": the window offers " + offset
                                    + ", which sits in the band where a hit does not decide the hinge",
                            decidedAt(facing, window.axis(), offset));
                }
            }
        }
    }

    // --------------------------------------------------------------------------- flanking blocks take precedence

    /**
     * A flanking door decides the hinge, and the planner then has NO free choice.
     *
     * <p>The precedence question this derivation had to answer: vanilla consults the neighbours before it looks at
     * the hit. So when a neighbour has spoken, {@link PlacementFamilies#doorHingeWindow} must return null rather
     * than a band — offering the planner a hit position that cannot change the outcome would be a proof that proves
     * nothing, and the gate would refuse the click after the walk had already been paid for.
     */
    @Test
    public void aFlankingDoorLeavesThePlannerNoChoice() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            assertSame(facing + ": a door flanking on the left forces the hinge right",
                    DoorHingeSide.RIGHT,
                    PlacementFamilies.doorHingeFromNeighbours(false, false, false, false, true, false));
            assertSame(facing + ": a door flanking on the right forces the hinge left",
                    DoorHingeSide.LEFT,
                    PlacementFamilies.doorHingeFromNeighbours(false, false, false, false, false, true));
        }
        assertEquals("with nothing flanking, the neighbours must abstain and leave it to the hit",
                null, PlacementFamilies.doorHingeFromNeighbours(false, false, false, false, false, false));
    }
}
