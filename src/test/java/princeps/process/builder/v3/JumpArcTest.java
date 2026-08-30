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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The jump the upward-facing placement rides on, checked against numbers rather than against intuition.
 *
 * <p>The whole reason a body in flight may be planned for at all is that a vanilla jump has no randomness in it. If
 * that stops being true these tests are where it shows, and the constants they rest on were read out of the game's
 * own bytecode: {@code LivingEntity.BASE_JUMP_POWER = 0.42}, the {@code gravity} attribute's default {@code 0.08},
 * and the {@code 0.98} vertical drag.
 */
public class JumpArcTest {

    /** The apex, to four places. 1.2522 is the number every Minecraft player knows as "you can jump onto a block but
     *  not onto two", and it is what the recurrence has to reproduce for the rest of this to mean anything. */
    @Test
    public void theApexIsTheOneEveryPlayerKnows() {
        assertEquals("a vanilla jump peaks a quarter of a block above the block it can climb",
                1.2522D, JumpArc.apex(), 5.0E-4D);
    }

    /** A full block of clearance is reached, which is the entire premise: the body has to leave the cell it is about
     *  to fill before the click that fills it. */
    @Test
    public void aJumpClearsAFullBlock() {
        assertTrue("without this the upward-facing placement has no mechanism at all", JumpArc.clearsAFullBlock());
    }

    /**
     * The window is several ticks wide, not one.
     *
     * <p>This is the property that makes the placement robust rather than lucky. A one-tick window would have to be
     * hit exactly, and a client that drops a tick — which the bench does at a raised tick rate — would miss and have
     * to jump again. Anything inside the window works, so a dropped tick costs nothing.
     */
    @Test
    public void theWindowIsWideEnoughToSurviveADroppedTick() {
        int[] window = JumpArc.clearanceWindow(1.0D);

        assertNotNull("a jump that cannot clear a block would make the whole action impossible", window);
        assertTrue("window " + window[0] + ".." + window[1] + " must be wide enough that one missed tick is harmless",
                window[1] - window[0] >= 2);
    }

    /** The body is NOT clear on the first tick of the jump, which is exactly why the click has to wait for the
     *  window instead of going out with the jump input. Clicking early would place the block into the body. */
    @Test
    public void theBodyIsNotClearOnTheJumpTickItself() {
        double[] rise = JumpArc.rise();

        assertTrue("the first tick of a jump lifts the feet 0.42, well short of the block being vacated",
                rise[0] < 1.0D);
        assertEquals(JumpArc.BASE_JUMP_POWER, rise[0], 1.0E-9D);
    }

    /** Two blocks of clearance is not reachable, so nothing may ever plan for it. Stated because the failure would
     *  otherwise be a silent "no window" at runtime rather than a refusal at plan time. */
    @Test
    public void twoBlocksOfClearanceIsNotAThingAJumpCanDo() {
        assertNull("a vanilla jump peaks at 1.2522 and no proof may assume otherwise",
                JumpArc.clearanceWindow(2.0D));
    }

    /** The arc is a pure function: two calls agree exactly. Determinism is a promise this engine makes about whole
     *  plans, and a plan that contains a jump inherits it from here. */
    @Test
    public void theArcIsDeterministic() {
        double[] first = JumpArc.rise();
        double[] second = JumpArc.rise();

        for (int tick = 0; tick < first.length; tick++) {
            assertEquals("tick " + tick, first[tick], second[tick], 0.0D);
        }
    }
}
