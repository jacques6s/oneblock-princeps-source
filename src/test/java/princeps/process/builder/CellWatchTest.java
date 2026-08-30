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

/**
 * The watch list is a diagnostic, so its failure mode matters more than its happy path: a malformed entry must leave
 * the builder alone rather than take a two-hour bench run down with it, and an empty list must cost nothing.
 *
 * <p>{@link CellWatch#WATCHED} is a static final read at class-init, so this cannot exercise a populated list without
 * a fixture file next to the test's working directory — which would then be read by every OTHER test's JVM too. What
 * IS pinned here is the part that has to hold in the default configuration, which is the configuration every run of
 * {@code gate.ps1 -Stage tests} uses: watching nothing must be inert.
 */
public class CellWatchTest {

    @Test
    public void theGuardAndTheWatchListAgree() {
        // Written as a CONTRACT, not as an environment assumption: autonomy/watch-cells.txt is a working file that
        // comes and goes with whatever cell is under investigation, and the test's working directory is the repo
        // root, so it sees it. A test that asserted "nothing is watched" would go red the moment the instrument was
        // actually used -- which is precisely when nobody wants to be debugging a test.
        if (CellWatch.active()) {
            // NO HARD-CODED COORDINATES. This branch used to require 116,-59,73 or 116,-59,72 to be watched -- the two
            // cells that happened to be under investigation the day it was written. The comment above called that a
            // contract; it was an environment assumption, and it went red the first time a DIFFERENT cell was
            // investigated (85,-59,67 on 04.08.2026), i.e. exactly when the instrument was being used and nobody
            // wanted to be debugging a test. What is actually invariant is the guard's consistency, so that is what is
            // asserted: a coordinate that no investigation would ever name must still answer false while the list is
            // active, which is the property that catches a guard short-circuiting to "everything is watched".
            assertFalse("an unnamed cell must answer false even while the watch list is active",
                    CellWatch.isWatched(1_234_567, 4_242, -7_654_321));
        } else {
            assertFalse("with no watch list, the hot-path guard must answer on the length check alone",
                    CellWatch.isWatched(116, -59, 73));
        }
    }

    @Test
    public void aNoteNeverThrowsAndNeverNeedsATrace() {
        // The hot path calls this with no BuildTrace running during every unit test and every non-traced bench run.
        CellWatch.note(1234L, 116, -59, 73, "scan", "must not throw with no trace open");
        CellWatch.note(1234L, -1, -1, -1, "scan", "an unwatched cell must be a no-op");
        CellWatch.reset();
    }

    @Test
    public void theSourceListCoversBothWorkingDirectories() {
        // The bench client runs with its working directory at run/, a unit test at the repo root. Getting this wrong
        // is silent -- the instrument simply never fires -- so the two cases are named rather than assumed.
        assertTrue("the bench client's own directory must be a source",
                java.util.Arrays.asList(CellWatch.SOURCES).contains("watch-cells.txt"));
        assertTrue("the shared list one level up must be a source",
                java.util.Arrays.asList(CellWatch.SOURCES).contains("../autonomy/watch-cells.txt"));
        assertTrue("the repo root must be a source",
                java.util.Arrays.asList(CellWatch.SOURCES).contains("autonomy/watch-cells.txt"));
    }
}
