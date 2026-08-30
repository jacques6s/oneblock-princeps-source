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
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The termination argument of decision E-D, checked.
 *
 * <p>E-D says a rejection re-runs the planner, and the planner is deterministic — so the only thing standing between
 * "re-plan on rejection" and an infinite loop at click cadence is that the journal makes the refusal an INPUT. Three
 * properties carry that argument and all three are asserted below: a refused triple can never be proposed again, three
 * structurally different refusals at one cell end the cell rather than the patience, and a real change in the world
 * re-opens what the evidence had closed. If any of them stops holding, the builder does not fail loudly — it clicks
 * forever, which is the failure mode this whole layer exists to make impossible.
 *
 * <p>The journal is the only piece of the acknowledgement story that can be tested at all: {@link ServerAck} needs
 * packets, a client thread and a live world, while this needs positions, faces and a tick number. That asymmetry is
 * why the two are separate classes.
 */
public class EvidenceJournalTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos CELL = new BlockPos(118, 71, -243);
    private static final BlockPos STANCE_A = new BlockPos(117, 71, -243);
    private static final BlockPos STANCE_B = new BlockPos(119, 71, -243);
    private static final BlockPos STANCE_C = new BlockPos(118, 71, -242);

    // ------------------------------------------------------------------------------------ a ban is permanent

    /**
     * The core of the termination argument: once refused, a triple is never offered to the planner again, so a
     * deterministic re-plan over an unchanged world cannot return the same solution.
     */
    @Test
    public void aBannedTripleIsNeverAllowedAgain() {
        EvidenceJournal journal = new EvidenceJournal();

        assertTrue("nothing is banned before any evidence exists",
                journal.allows(CELL, STANCE_A, Direction.NORTH));

        journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.REVERTED, 100L);

        assertFalse("the refused triple must never be proposed again",
                journal.allows(CELL, STANCE_A, Direction.NORTH));
        assertTrue("the ban is on the triple, not on the cell",
                journal.allows(CELL, STANCE_B, Direction.NORTH));
        assertTrue("the ban is on the triple, not on the stance",
                journal.allows(CELL, STANCE_A, Direction.SOUTH));
        assertTrue(journal.isBanned(CELL, STANCE_A, Direction.NORTH));
        assertEquals(EvidenceJournal.CellStatus.CONTESTED, journal.status(CELL));
    }

    /** A different cell that happens to share a stance and a face is a different solution and stays available. */
    @Test
    public void theBanIsScopedToItsCell() {
        EvidenceJournal journal = new EvidenceJournal();
        journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.REVERTED, 100L);

        BlockPos other = CELL.above();
        assertTrue(journal.allows(other, STANCE_A, Direction.NORTH));
        assertEquals(EvidenceJournal.CellStatus.CLEAR, journal.status(other));
    }

    /**
     * The same triple refused twice is one solution refused twice. If it counted twice, an executor that re-clicked
     * before consulting the planner could drive a cell terminal from a single stance — the escalation would be
     * measuring impatience instead of structure.
     */
    @Test
    public void repeatingARejectionDoesNotEscalate() {
        EvidenceJournal journal = new EvidenceJournal();
        journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.REVERTED, 100L);
        journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.REVERTED, 140L);
        journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.NO_CONFIRMATION, 180L);

        assertEquals("three refusals of ONE solution are one structural rejection",
                1, journal.structurallyDifferentRejections(CELL));
        assertEquals(EvidenceJournal.CellStatus.CONTESTED, journal.status(CELL));
        assertTrue(journal.externallyBlockedCells().isEmpty());
        assertEquals("every refusal is still kept as evidence", 3, journal.rejections(CELL).size());
    }

    // ------------------------------------------------------------------------------------------- escalation

    /** Three structurally different solutions, all refused: the obstacle is not geometry, and the cell is named. */
    @Test
    public void threeStructurallyDifferentRejectionsEscalate() {
        EvidenceJournal journal = new EvidenceJournal();

        assertEquals(EvidenceJournal.CellStatus.CONTESTED,
                journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.REVERTED, 100L));
        assertEquals(EvidenceJournal.CellStatus.CONTESTED,
                journal.reject(CELL, STANCE_B, Direction.SOUTH, EvidenceJournal.Reason.REVERTED, 160L));
        assertEquals(EvidenceJournal.CellStatus.EXTERNALLY_BLOCKED,
                journal.reject(CELL, STANCE_C, Direction.WEST, EvidenceJournal.Reason.NO_CONFIRMATION, 220L));

        assertTrue(journal.isExternallyBlocked(CELL));
        assertEquals(EvidenceJournal.REJECTIONS_BEFORE_BLOCKED, journal.structurallyDifferentRejections(CELL));
    }

    /** A blocked cell allows nothing at all, so no fourth plan can exist to spend a fourth click on. */
    @Test
    public void aBlockedCellAllowsNoFurtherSolution() {
        EvidenceJournal journal = new EvidenceJournal();
        journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.REVERTED, 100L);
        journal.reject(CELL, STANCE_B, Direction.SOUTH, EvidenceJournal.Reason.REVERTED, 160L);
        journal.reject(CELL, STANCE_C, Direction.WEST, EvidenceJournal.Reason.REVERTED, 220L);

        assertFalse("a never-tried fourth stance is refused too — the verdict is about the cell now",
                journal.allows(CELL, new BlockPos(118, 72, -244), Direction.UP));
    }

    /** The halt is loud: coordinates, not a count. */
    @Test
    public void theBlockedCellIsReportedByName() {
        EvidenceJournal journal = new EvidenceJournal();
        journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.REVERTED, 100L);
        journal.reject(CELL, STANCE_B, Direction.SOUTH, EvidenceJournal.Reason.REVERTED, 160L);
        journal.reject(CELL, STANCE_C, Direction.WEST, EvidenceJournal.Reason.REVERTED, 220L);

        List<BlockPos> blocked = journal.externallyBlockedCells();
        assertEquals(1, blocked.size());
        assertEquals(CELL, blocked.get(0));

        String report = journal.blockedReport();
        assertTrue("the report must carry the coordinates: " + report, report.contains("118,71,-243"));
        assertTrue("the report must carry the evidence: " + report, report.contains("REVERTED"));
    }

    /** Blocked cells report in the order they went terminal, so two runs over the same evidence read identically. */
    @Test
    public void blockedCellsReportInDiscoveryOrder() {
        EvidenceJournal journal = new EvidenceJournal();
        BlockPos second = new BlockPos(200, 71, -243);
        blockFully(journal, second, 100L);
        blockFully(journal, CELL, 400L);

        assertEquals(List.of(second, CELL), journal.externallyBlockedCells());
    }

    // ------------------------------------------------------------------------------- the marker is not permanent

    /**
     * The evidence was gathered against a world that no longer exists. Refusing to reconsider would turn one moment's
     * protection region into a permanent hole in the build.
     */
    @Test
    public void aNeighbourhoodChangeClearsTheMarker() {
        EvidenceJournal journal = new EvidenceJournal();
        blockFully(journal, CELL, 100L);
        assertTrue(journal.isExternallyBlocked(CELL));

        int cleared = journal.observeWorldChange(CELL.above());

        assertEquals(1, cleared);
        assertEquals(EvidenceJournal.CellStatus.CLEAR, journal.status(CELL));
        assertTrue("the bans go with the marker — the geometry may work now",
                journal.allows(CELL, STANCE_A, Direction.NORTH));
        assertEquals(0, journal.structurallyDifferentRejections(CELL));
        assertTrue(journal.externallyBlockedCells().isEmpty());
    }

    /** A change at the cell itself counts; so does one on the diagonal, because the radius is Chebyshev. */
    @Test
    public void theNeighbourhoodIsTheTwentySevenCellBox() {
        assertTrue(EvidenceJournal.withinNeighbourhood(CELL, CELL));
        assertTrue(EvidenceJournal.withinNeighbourhood(CELL, CELL.offset(1, 1, 1)));
        assertTrue(EvidenceJournal.withinNeighbourhood(CELL, CELL.offset(-1, -1, -1)));
        assertFalse(EvidenceJournal.withinNeighbourhood(CELL, CELL.offset(2, 0, 0)));
        assertFalse(EvidenceJournal.withinNeighbourhood(CELL, CELL.offset(0, 0, -2)));
    }

    /** Activity elsewhere on a busy server must not clear anything, or the loop the journal closes re-opens. */
    @Test
    public void aDistantChangeClearsNothing() {
        EvidenceJournal journal = new EvidenceJournal();
        blockFully(journal, CELL, 100L);

        assertEquals(0, journal.observeWorldChange(CELL.offset(4, 0, 0)));
        assertTrue(journal.isExternallyBlocked(CELL));
        assertFalse(journal.allows(CELL, STANCE_A, Direction.NORTH));
    }

    /** A contested cell is cleared by the same rule — its evidence is no less stale than a blocked one's. */
    @Test
    public void aNeighbourhoodChangeAlsoClearsAContestedCell() {
        EvidenceJournal journal = new EvidenceJournal();
        journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.WRONG_RESULT, 100L);

        assertEquals(1, journal.observeWorldChange(CELL.north()));
        assertTrue(journal.allows(CELL, STANCE_A, Direction.NORTH));
    }

    /** One change can clear several cells, and only the ones in range. */
    @Test
    public void aChangeClearsEveryCellInRange() {
        EvidenceJournal journal = new EvidenceJournal();
        blockFully(journal, CELL, 100L);
        blockFully(journal, CELL.above(), 200L);
        blockFully(journal, CELL.offset(10, 0, 0), 300L);

        assertEquals(2, journal.observeWorldChange(CELL));
        assertEquals(List.of(CELL.offset(10, 0, 0)), journal.externallyBlockedCells());
    }

    /** A late packet is one atomic journal event: stale geometry is cleared and completed progress is taken back. */
    @Test
    public void aServerInvalidationClearsEvidenceAndBooksOneRevert() {
        EvidenceJournal journal = new EvidenceJournal(0L);
        blockFully(journal, CELL, 100L);
        journal.recordPlaced(610L);

        assertEquals(1, journal.recordServerInvalidation(CELL, 620L));
        assertEquals(EvidenceJournal.CellStatus.CLEAR, journal.status(CELL));
        assertEquals(1L, journal.placedTotal());
        assertEquals(1L, journal.revertedTotal());
        assertEquals(0, journal.netProgress(620L));
    }

    // -------------------------------------------------------------------------------------- the net-progress window

    /** Placed and taken away at the same rate: every cell looks fine, and the build is achieving nothing. */
    @Test
    public void aNetZeroWindowIsAStall() {
        EvidenceJournal journal = new EvidenceJournal(0L);
        journal.recordPlaced(610L);
        journal.recordReverted(620L);
        journal.recordPlaced(630L);
        journal.recordReverted(640L);

        assertEquals(0, journal.netProgress(700L));
        assertTrue(journal.netProgressStalled(700L));
        assertTrue(journal.stallReport(700L).contains("the world is being changed against the build"));
    }

    /** Real progress with the occasional loss is not a stall. */
    @Test
    public void netPositiveProgressIsNotAStall() {
        EvidenceJournal journal = new EvidenceJournal(0L);
        journal.recordPlaced(610L);
        journal.recordPlaced(620L);
        journal.recordPlaced(630L);
        journal.recordReverted(640L);

        assertEquals(2, journal.netProgress(700L));
        assertFalse(journal.netProgressStalled(700L));
    }

    /**
     * A long walk between distant cells places nothing and loses nothing. Net zero, and not a stall — which is why
     * the verdict needs a revert inside the window and not merely the arithmetic.
     */
    @Test
    public void anIdleWindowIsNotAStall() {
        EvidenceJournal journal = new EvidenceJournal(0L);
        assertEquals(0, journal.netProgress(2000L));
        assertFalse(journal.netProgressStalled(2000L));
    }

    /** The first window of a build is spent walking to the origin and may not be judged. */
    @Test
    public void theFirstWindowIsNeverAStall() {
        EvidenceJournal journal = new EvidenceJournal(0L);
        journal.recordPlaced(10L);
        journal.recordReverted(20L);

        assertFalse(journal.netProgressStalled(100L));
        assertTrue("the same evidence, one window later", journal.netProgressStalled(
                EvidenceJournal.NET_PROGRESS_WINDOW_TICKS));
    }

    /** Events fall out of the window; a build that recovered is not judged on what it lost half a minute ago. */
    @Test
    public void eventsLeaveTheWindow() {
        EvidenceJournal journal = new EvidenceJournal(0L);
        journal.recordReverted(0L);
        journal.recordPlaced(10L);

        assertEquals(0, journal.netProgress(300L));
        assertEquals("the revert at tick 0 has aged out by tick 600",
                1, journal.netProgress(600L));
        assertFalse(journal.netProgressStalled(600L));
        assertEquals("the totals are lifetime, not windowed", 1L, journal.revertedTotal());
        assertEquals(1L, journal.placedTotal());
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    /** {@link ServerAck}'s vocabulary maps onto this one, and a confirmation is evidence against nothing. */
    @Test
    public void ackOutcomesMapOntoReasons() {
        assertEquals(EvidenceJournal.Reason.REVERTED,
                EvidenceJournal.reasonFor(ServerAck.Outcome.REVERTED));
        assertEquals(EvidenceJournal.Reason.WRONG_RESULT,
                EvidenceJournal.reasonFor(ServerAck.Outcome.WRONG_STATE));
        assertEquals(EvidenceJournal.Reason.NO_CONFIRMATION,
                EvidenceJournal.reasonFor(ServerAck.Outcome.TIMED_OUT));
        assertNull(EvidenceJournal.reasonFor(ServerAck.Outcome.CONFIRMED));
        assertNull(EvidenceJournal.reasonFor(ServerAck.Outcome.NOT_SENT));
        assertNull(EvidenceJournal.reasonFor(ServerAck.Outcome.UNLOADED));
        assertNull(EvidenceJournal.reasonFor(ServerAck.Outcome.PENDING));
        assertNull(EvidenceJournal.reasonFor(ServerAck.Outcome.IDLE));
    }

    /** The planner's within-plan vetoes and the executor's across-plan journal compose. */
    @Test
    public void filtersCompose() {
        EvidenceJournal journal = new EvidenceJournal();
        journal.reject(CELL, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.REVERTED, 100L);

        PlacementOracle.StanceFilter other =
                (cell, stance, face) -> !STANCE_B.equals(stance);
        PlacementOracle.StanceFilter both = journal.combinedWith(other);

        assertFalse("the journal's ban survives composition", both.allows(CELL, STANCE_A, Direction.NORTH));
        assertFalse("the other filter's ban survives composition", both.allows(CELL, STANCE_B, Direction.NORTH));
        assertTrue(both.allows(CELL, STANCE_C, Direction.NORTH));
        assertFalse(journal.combinedWith(null).allows(CELL, STANCE_A, Direction.NORTH));
    }

    /** A build start wipes the slate: evidence from the previous world proves nothing about this one. */
    @Test
    public void resetForgetsEverything() {
        EvidenceJournal journal = new EvidenceJournal(0L);
        blockFully(journal, CELL, 100L);
        journal.recordPlaced(110L);
        journal.recordReverted(120L);

        journal.reset(5000L);

        assertEquals(EvidenceJournal.CellStatus.CLEAR, journal.status(CELL));
        assertTrue(journal.allows(CELL, STANCE_A, Direction.NORTH));
        assertEquals(0L, journal.placedTotal());
        assertEquals(0L, journal.revertedTotal());
        assertFalse("the window restarts with the build", journal.netProgressStalled(5100L));
    }

    private static void blockFully(EvidenceJournal journal, BlockPos cell, long tick) {
        journal.reject(cell, STANCE_A, Direction.NORTH, EvidenceJournal.Reason.REVERTED, tick);
        journal.reject(cell, STANCE_B, Direction.SOUTH, EvidenceJournal.Reason.REVERTED, tick + 60L);
        journal.reject(cell, STANCE_C, Direction.WEST, EvidenceJournal.Reason.REVERTED, tick + 120L);
    }
}
