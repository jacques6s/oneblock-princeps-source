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

package princeps.process.builder;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The self-clearance bootstrap plans the cell the bot is STANDING IN, and that is the cell most likely to have just
 * been rejected for this target. {@link PlacementTargetLock#planStance} throws on a rejected stance, so the bootstrap
 * has to ask {@link PlacementTargetLock#mayTryStance} first.
 *
 * <p>Run e7dd25d0 died at tick 7220 for want of that question:
 *
 * <pre>
 * IllegalArgumentException: cannot reuse a rejected placement stance
 *   at PlacementTargetLock.planStance(:232) &lt;- BuilderProcess.placementRecoveryCommand(:5657)
 * </pre>
 *
 * <p>one tick after "Recovering placement at 78,-59,122 from a different stance". The whole client went down and the
 * run produced no verdict. 555 green tests did not catch it, because the throw needs a runtime LOCK STATE and every
 * existing test around this area exercises pure policy inputs. This pins the state transition itself.
 */
public class PlacementTargetLockBootstrapTest {

    private static final long TARGET = 1L;
    private static final long STANCE = 2L;

    /** Timeouts are irrelevant here -- nothing in these cases advances a tick -- but they must be positive. */
    private static PlacementTargetLock<Object> recovering() {
        PlacementTargetLock<Object> lock = new PlacementTargetLock<>(40, 40, 200);
        assertTrue("a fresh lock must accept the target", lock.acquire(TARGET, null));
        lock.startRecovery();
        return lock;
    }

    /**
     * THE ONE THAT MATTERS: the two ways into recovery differ in whether they accuse the current cell, and the choice
     * decides whether the self-clearance bootstrap can work at all.
     *
     * <p>{@code acquireForRecovery(target, feetKey)} records that cell as rejected. The bootstrap then plans the cell
     * the bot is standing in -- the same one -- and {@code planStance} throws. That is not a rare interleaving: the
     * only caller passed the CURRENT feet cell, so the accusation preceded the bootstrap on every single pass. Run
     * e7dd25d0 died on it at tick 7220.
     *
     * <p>Both directions are pinned, because a future reader has to be able to see which one is intended here.
     */
    @Test
    public void blamingRecoveryRejectsTheCellWhileNonBlamingDoesNot() {
        PlacementTargetLock<Object> blaming = new PlacementTargetLock<>(40, 40, 200);
        assertTrue(blaming.acquireForRecovery(TARGET, STANCE));
        assertFalse("acquireForRecovery accuses the stance it is handed", blaming.mayTryStance(STANCE));

        PlacementTargetLock<Object> plain = recovering();
        assertTrue("acquire + startRecovery() accuses nothing -- the pose case", plain.mayTryStance(STANCE));
        plain.planStance(STANCE, 0L);
        assertTrue("so the bootstrap can plan the cell the bot stands in", plain.hasPlannedStance());
    }

    @Test
    public void aRejectedStanceIsRefusedByMayTryStance() {
        PlacementTargetLock<Object> lock = recovering();
        lock.planStance(STANCE, 0L);
        lock.rejectPlannedStance();

        assertFalse("the guard the bootstrap must consult", lock.mayTryStance(STANCE));
    }

    @Test
    public void planningARejectedStanceStillThrows() {
        // Pinned deliberately: the throw is the invariant, and the fix is the CALLER asking first -- not softening
        // this. If a later change makes planStance tolerant, that is a decision to take on purpose, and this test is
        // where it has to be argued.
        PlacementTargetLock<Object> lock = recovering();
        lock.planStance(STANCE, 0L);
        lock.rejectPlannedStance();

        try {
            lock.planStance(STANCE, 0L);
            fail("planning a rejected stance must keep throwing");
        } catch (IllegalArgumentException expected) {
            assertTrue("the message names the reason",
                    expected.getMessage().contains("rejected placement stance"));
        }
    }

    @Test
    public void anUnrejectedStanceRemainsPlannable() {
        // The other half: the guard must not turn into a blanket refusal, or the bootstrap never fires at all and the
        // 0.03-block body overlap it exists for goes unfixed.
        PlacementTargetLock<Object> lock = recovering();
        long other = 3L;

        lock.planStance(STANCE, 0L);
        lock.rejectPlannedStance();

        assertTrue("a stance that was never rejected stays available", lock.mayTryStance(other));
        lock.planStance(other, 0L);
        assertTrue("and is then the planned one", lock.hasPlannedStance());
    }
}

