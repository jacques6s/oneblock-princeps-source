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
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The report said "ray budget spent after 11" for a cap that was never reached.
 *
 * <h2>The defect</h2>
 *
 * <p>One counter and one flag served two different caps:
 *
 * <pre>
 *   if (evaluated &gt;= budget.maxStances() || search.raysCast &gt;= budget.maxRays()) {
 *       search.reject(Rejection.RAY_BUDGET_EXHAUSTED);
 *       search.budgetExhausted = true;
 * </pre>
 *
 * <p>and {@code Solve.explain()} then appended {@code " -- ray budget spent after " + raysCast}. In all 56 lines of
 * the etz-basalt report that raised it, the LEFT disjunct is what fired: {@code stancesTried − notStandable −
 * exhausted} was exactly {@link SolveBudget#MAX_STANCES} every single time. The highest ray count on any cell in that
 * report was 298, against {@link SolveBudget#MAX_RAYS} of 512. The ray cap has never fired anywhere, on any cell.
 *
 * <p>That mattered because the sentence invited the obvious wrong fix. Raising {@code MAX_RAYS} would not have been a
 * mask over the real cause — it would have been a pure no-op, and the report was the only thing making it look like a
 * lead. A measuring instrument that names the wrong limit is worse than no instrument, so this is fixed before
 * anything is concluded about the family it was mis-measuring.
 *
 * <p>What this does NOT do is raise either cap. Both are unchanged.
 */
public class SolveBudgetInstrumentTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Neither cap reached: nothing about a budget appears in the line at all. */
    @Test
    public void anUncappedSearchSaysNothingAboutBudgets() {
        String line = solve(0, 0, false, false).explain();
        assertFalse(line, line.contains("cap hit"));
        assertFalse(line, line.contains("ray budget"));
    }

    /**
     * The stance cap names itself, reports how many candidates it left untried, and says the ray cap was NOT reached.
     *
     * <p>The count is the {@code STANCE_BUDGET_EXHAUSTED} tally, which is raised once per remaining candidate stance —
     * so it is exactly "how many were never evaluated", the number the old line never contained.
     */
    @Test
    public void theStanceCapNamesItselfAndCountsWhatItSkipped() {
        PlacementOracle.Solve solve = solve(0, 7, false, true);
        String line = solve.explain();

        assertTrue(line, line.contains("STANCE cap hit"));
        assertTrue("it must say how many candidates it never got to: " + line,
                line.contains("7 further candidate stance(s) never evaluated"));
        assertFalse("and it must NOT claim the ray cap was reached -- that is the whole bug: " + line,
                line.contains("RAY cap hit"));
        assertTrue("stating plainly that the ray cap was not the limit: " + line,
                line.contains("cap not reached"));
        assertFalse("the old sentence must not come back", line.contains("ray budget spent after"));
    }

    /** The ray cap, when it does fire, is a different sentence with a different number. */
    @Test
    public void theRayCapIsADistinctFindingWithItsOwnSentence() {
        String line = solve(4, 0, true, false).explain();
        assertTrue(line, line.contains("RAY cap hit"));
        assertFalse(line, line.contains("STANCE cap hit"));
    }

    /** Both are possible in one search and both are reported; neither is allowed to speak for the other. */
    @Test
    public void bothCapsCanFireAndBothAreNamed() {
        String line = solve(3, 5, true, true).explain();
        assertTrue(line, line.contains("RAY cap hit"));
        assertTrue(line, line.contains("STANCE cap hit"));
    }

    /**
     * The caps themselves are untouched, and this pins that.
     *
     * <p>Raising a limit to make a report look better is how a measuring instrument gets broken. Nothing in the
     * finding above justifies moving either number, so neither moves.
     */
    @Test
    public void neitherCapWasRaised() {
        assertEquals("V2's measured working figure", 24, SolveBudget.MAX_STANCES);
        assertEquals("never once reached in any recorded run; raising it would be a no-op, not a fix",
                512, SolveBudget.MAX_RAYS);
        assertEquals(24, SolveBudget.DEFAULT.maxStances());
        assertEquals(512, SolveBudget.DEFAULT.maxRays());
        assertEquals("exhaustive() disables the fast path and nothing else",
                512, SolveBudget.DEFAULT.exhaustive().maxRays());
        assertEquals(24, SolveBudget.DEFAULT.exhaustive().maxStances());
    }

    /** The two rejections are genuinely distinct enum constants, so the tally can tell them apart. */
    @Test
    public void theTwoCapsAreSeparateRejections() {
        assertFalse(PlacementOracle.Rejection.RAY_BUDGET_EXHAUSTED
                == PlacementOracle.Rejection.STANCE_BUDGET_EXHAUSTED);
    }

    // ---------------------------------------------------------------------------------------------- fixture

    /** A {@link PlacementOracle.Solve} built directly, which is the lowest level at which the reporting bug shows. */
    private static PlacementOracle.Solve solve(int rayRejections, int stanceRejections,
                                               boolean rayExhausted, boolean stanceExhausted) {
        int[] rejections = new int[PlacementOracle.Rejection.values().length];
        rejections[PlacementOracle.Rejection.RAY_BUDGET_EXHAUSTED.ordinal()] = rayRejections;
        rejections[PlacementOracle.Rejection.STANCE_BUDGET_EXHAUSTED.ordinal()] = stanceRejections;
        rejections[PlacementOracle.Rejection.STANCE_NOT_STANDABLE.ordinal()] = 191;
        return new PlacementOracle.Solve(List.of(), List.of(), rejections, 243, 11,
                rayExhausted, stanceExhausted);
    }
}
