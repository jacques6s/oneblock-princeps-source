/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process;

import net.minecraft.core.BlockPos;
import org.junit.Test;
import princeps.api.pathing.PlacementLicence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The placement permission a route carries, from the owner's build algorithm v0.4.
 *
 * <p>The two lanes of S4/S5 are exactly two of the three forms of {@link PlacementLicence}: lane A searches with
 * placing forbidden ({@link PlacementLicence#NONE}), lane B searches with helper blocks allowed, but only where the
 * template wants air ({@link PlacementLicence#where}). {@link PlacementLicence#UNRESTRICTED} is ordinary navigation
 * outside a build.
 *
 * <p>The class is tested directly — it is public API in {@code princeps.api.pathing} and needs no mirror. What the
 * tests pin down is the property the type exists for: the answer is fixed for the lifetime of the route. A licence
 * built once must give the same answer for the same position no matter how many blocks have been placed since,
 * because planner and executor read it at different times and a disagreement between them is what produced the
 * measured deadlock (921 two-node paths against 922 "no path" ticks, nothing placed).
 */
public class PlacementLicenceTest {

    /** A modest build volume, in the style of a real one: origin at 100,-60,100, eight blocks on a side. */
    private static final int OX = 100;
    private static final int OY = -60;
    private static final int OZ = 100;
    private static final int SIZE = 8;

    // ----------------------------------------------------------------------------------------------------------
    // NONE — lane A
    // ----------------------------------------------------------------------------------------------------------

    @Test
    public void noneAllowsNothingAnywhere() {
        PlacementLicence lane = PlacementLicence.NONE;
        assertFalse(lane.permitsPlacement(0, 0, 0));
        assertFalse(lane.permitsPlacement(OX, OY, OZ));
        assertFalse(lane.permitsPlacement(new BlockPos(OX + 3, OY + 1, OZ + 3)));
        assertFalse(lane.permitsPlacement(-30_000_000, -64, 30_000_000));
        assertFalse(lane.permitsPlacement(Integer.MAX_VALUE, 0, Integer.MIN_VALUE));
    }

    @Test
    public void noneAnnouncesItselfAsForbiddingEverythingAndNotAsUnrestricted() {
        assertTrue(PlacementLicence.NONE.forbidsEverything());
        assertFalse(PlacementLicence.NONE.isUnrestricted());
    }

    // ----------------------------------------------------------------------------------------------------------
    // UNRESTRICTED — ordinary navigation
    // ----------------------------------------------------------------------------------------------------------

    @Test
    public void unrestrictedAllowsEverythingEverywhere() {
        PlacementLicence any = PlacementLicence.UNRESTRICTED;
        assertTrue(any.permitsPlacement(0, 0, 0));
        assertTrue(any.permitsPlacement(OX, OY, OZ));
        assertTrue(any.permitsPlacement(new BlockPos(OX + 3, OY + 1, OZ + 3)));
        assertTrue(any.permitsPlacement(-30_000_000, -64, 30_000_000));
    }

    @Test
    public void unrestrictedAnnouncesItselfAsUnrestrictedAndNotAsForbidding() {
        assertTrue(PlacementLicence.UNRESTRICTED.isUnrestricted());
        assertFalse(PlacementLicence.UNRESTRICTED.forbidsEverything());
    }

    @Test
    public void theTwoBlanketConstantsAreDistinct() {
        assertFalse(PlacementLicence.NONE == PlacementLicence.UNRESTRICTED);
    }

    // ----------------------------------------------------------------------------------------------------------
    // where(...) — lane B
    // ----------------------------------------------------------------------------------------------------------

    @Test
    public void whereAllowsExactlyWhatThePredicateSays() {
        PlacementLicence laneB = PlacementLicence.where(insideVolumeWhereTemplateWantsAir(), "air-cells");

        // inside the volume, on an even layer: the template wants air, a helper is allowed
        assertTrue(laneB.permitsPlacement(OX + 1, OY, OZ + 1));
        assertTrue(laneB.permitsPlacement(OX + 7, OY + 2, OZ + 7));
        // inside the volume, on an odd layer: the template wants a block there, no helper
        assertFalse(laneB.permitsPlacement(OX + 1, OY + 1, OZ + 1));
        assertFalse(laneB.permitsPlacement(OX + 7, OY + 3, OZ + 7));
    }

    /** Outside the build volume the template asks for nothing at all, so lane B may not put a helper there. */
    @Test
    public void whereRefusesPositionsOutsideTheBuildVolume() {
        PlacementLicence laneB = PlacementLicence.where(insideVolumeWhereTemplateWantsAir(), "air-cells");

        assertFalse(laneB.permitsPlacement(OX - 1, OY, OZ));          // one west of the volume
        assertFalse(laneB.permitsPlacement(OX + SIZE, OY, OZ));       // one east
        assertFalse(laneB.permitsPlacement(OX, OY, OZ - 1));          // one north
        assertFalse(laneB.permitsPlacement(OX, OY, OZ + SIZE));       // one south
        assertFalse(laneB.permitsPlacement(OX, OY - 1, OZ));          // one below
        assertFalse(laneB.permitsPlacement(OX, OY + SIZE, OZ));       // one above
        assertFalse(laneB.permitsPlacement(0, 0, 0));                 // nowhere near it
    }

    /**
     * The two blanket flags are fast paths: a caller that sees {@code isUnrestricted()} skips the check entirely,
     * and one that sees {@code forbidsEverything()} refuses without asking. A lane B licence must claim neither,
     * or its predicate is never consulted at all.
     */
    @Test
    public void aLaneBLicenceIsNeitherBlanketFormEvenWhenItsPredicateIsTotal() {
        PlacementLicence alwaysYes = PlacementLicence.where(pos -> true, "always");
        assertFalse(alwaysYes.isUnrestricted());
        assertFalse(alwaysYes.forbidsEverything());
        assertTrue(alwaysYes.permitsPlacement(1, 2, 3));

        PlacementLicence alwaysNo = PlacementLicence.where(pos -> false, "never");
        assertFalse(alwaysNo.isUnrestricted());
        assertFalse(alwaysNo.forbidsEverything());
        assertFalse(alwaysNo.permitsPlacement(1, 2, 3));
    }

    /** The predicate is handed BlockPos-packed coordinates, negatives included — that is what it must decode. */
    @Test
    public void thePredicateSeesBlockPosPackedCoordinates() {
        List<Long> seen = new ArrayList<>();
        PlacementLicence laneB = PlacementLicence.where(pos -> {
            seen.add(pos);
            return true;
        }, "recording");

        laneB.permitsPlacement(-5, -60, -1000);
        laneB.permitsPlacement(new BlockPos(17, 200, -3));

        assertEquals(2, seen.size());
        assertEquals(Long.valueOf(BlockPos.asLong(-5, -60, -1000)), seen.get(0));
        assertEquals(Long.valueOf(BlockPos.asLong(17, 200, -3)), seen.get(1));
    }

    @Test
    public void theLabelSurvivesIntoToStringSoALogSaysWhichLicenceRefused() {
        assertTrue(PlacementLicence.where(pos -> true, "air-cells").toString().contains("air-cells"));
        assertTrue(PlacementLicence.NONE.toString().contains("none"));
        assertTrue(PlacementLicence.UNRESTRICTED.toString().contains("any"));
    }

    // ----------------------------------------------------------------------------------------------------------
    // edge cases
    // ----------------------------------------------------------------------------------------------------------

    /**
     * A null rule is the caller having no rule to give, and the safe reading of that is lane A. Note that the label
     * is dropped with it: what comes back is the shared {@link PlacementLicence#NONE} constant.
     */
    @Test
    public void whereWithANullPredicateCollapsesToNone() {
        assertSame(PlacementLicence.NONE, PlacementLicence.where(null, "lane B"));
        assertSame(PlacementLicence.NONE, PlacementLicence.where(null, null));
        assertTrue(PlacementLicence.where(null, "lane B").forbidsEverything());
        assertFalse(PlacementLicence.where(null, "lane B").permitsPlacement(OX, OY, OZ));
    }

    /** A null position is not a place, so nothing may be placed at it — for every form, blanket ones included. */
    @Test
    public void aNullPositionIsRefusedByEveryForm() {
        assertFalse(PlacementLicence.NONE.permitsPlacement((BlockPos) null));
        assertFalse(PlacementLicence.UNRESTRICTED.permitsPlacement((BlockPos) null));
        assertFalse(PlacementLicence.where(pos -> true, "always").permitsPlacement((BlockPos) null));
    }

    /** A null position must not even reach the rule — a rule that unpacks it would see a fabricated 0,0,0. */
    @Test
    public void aNullPositionNeverReachesTheRule() {
        List<Long> seen = new ArrayList<>();
        PlacementLicence laneB = PlacementLicence.where(pos -> {
            seen.add(pos);
            return true;
        }, "recording");

        assertFalse(laneB.permitsPlacement((BlockPos) null));
        assertTrue(seen.isEmpty());
    }

    /**
     * The whole reason this is a value: once the route holds it, nothing that happens in the world can change the
     * answer. Here the "world" is a counter the rule is deliberately not allowed to consult.
     */
    @Test
    public void theAnswerDoesNotDriftWhileTheRouteIsDriven() {
        PlacementLicence laneB = PlacementLicence.where(insideVolumeWhereTemplateWantsAir(), "air-cells");

        boolean beforeAnyPlacement = laneB.permitsPlacement(OX + 1, OY, OZ + 1);
        for (int i = 0; i < 100; i++) {
            laneB.permitsPlacement(OX + (i % SIZE), OY + (i % SIZE), OZ + (i % SIZE));
        }
        assertEquals(beforeAnyPlacement, laneB.permitsPlacement(OX + 1, OY, OZ + 1));
        assertTrue(beforeAnyPlacement);
    }

    // ----------------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------------

    /**
     * The rule lane B is actually given: inside the build volume, and only in cells the template wants empty.
     * Modelled here as "every second layer is solid", which is enough to tell "the predicate decided" apart from
     * "the licence decided".
     */
    private static LongPredicate insideVolumeWhereTemplateWantsAir() {
        return packed -> {
            BlockPos at = BlockPos.of(packed);
            int lx = at.getX() - OX;
            int ly = at.getY() - OY;
            int lz = at.getZ() - OZ;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= SIZE || ly >= SIZE || lz >= SIZE) {
                return false;   // outside the build volume the template wants nothing, so no helper may go here
            }
            return ly % 2 == 0;
        };
    }
}
