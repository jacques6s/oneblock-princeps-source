/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.pathing.calc;

import org.junit.Test;
import org.junit.BeforeClass;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import princeps.api.pathing.goals.Goal;
import princeps.pathing.calc.openset.BinaryHeapOpenSet;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

/** The real completion/cancel boundary, including a worker finishing after cancellation and replacement. */
public class PathProbeEvidenceTest {
    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }
    @Test public void anEmptyRealFrontierStillCannotProveFailureAfterAnUnknownChunk() throws Exception {
        AStarPathFinder search = worker();
        BinaryHeapOpenSet frontier = new BinaryHeapOpenSet();
        assertTrue(frontier.isEmpty());
        search.recordSearchEnd(false, frontier, 1); assertFalse(search.exhaustedSearch());
        search.recordSearchEnd(true, frontier, 1); assertFalse(search.exhaustedSearch());
        search.recordSearchEnd(false, frontier, 0); assertTrue(search.exhaustedSearch());
    }

    @Test public void anUnknownDynamicDestinationCannotHideBehindAZeroFetchCounter() throws Exception {
        AStarPathFinder search = worker();
        search.recordUnknownChunk(); // the actual shared unknown-chunk branch, including dynamic-XZ moves
        search.recordSearchEnd(false, new BinaryHeapOpenSet(), 0);
        assertFalse(search.exhaustedSearch());
        search.recordSearchEnd(true, new BinaryHeapOpenSet(), 0);
        assertFalse(search.exhaustedSearch());
    }

    @Test public void timeBoundedOrCancelledSearchIsUnknownButAnExplicitLoadedNodeBudgetIsEvidence() throws Exception {
        AStarPathFinder search = worker();
        BinaryHeapOpenSet frontier = new BinaryHeapOpenSet();
        frontier.insert(new PathNode(0, 0, 0, new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) { return false; }
            @Override public double heuristic(int x, int y, int z) { return 1; }
        }));
        search.recordSearchEnd(false, frontier, 0); assertFalse(search.exhaustedSearch());
        search.recordSearchEnd(true, frontier, 0); assertTrue(search.exhaustedSearch());
        search.recordSearchEnd(true, frontier, 1); assertFalse(search.exhaustedSearch());
        search.cancel(); search.recordSearchEnd(true, frontier, 0); assertFalse(search.exhaustedSearch());
    }
    @Test public void partialAndMissingPathsNeedAnExplicitExhaustedSearch() {
        for (var outcome : PathProbe.Outcome.values()) {
            assertFalse(new PathProbe.Result(outcome, null, 1).failedToReachGoal());
            assertEquals(outcome == PathProbe.Outcome.NONE || outcome == PathProbe.Outcome.PARTIAL,
                    new PathProbe.Result(outcome, null, 1, true).failedToReachGoal());
        }
    }

    @Test public void cancelledWorkerCannotPublishANegativeAnswer() throws Exception {
        PathProbe probe = new PathProbe("test");
        AStarPathFinder worker = worker(); setRunning(probe, worker);
        probe.cancel();
        probe.complete(worker, negative());
        assertNull(probe.poll()); assertFalse(probe.isRunning());
    }

    @Test public void oldCompletionCannotEraseOrAnswerTheNewRequest() throws Exception {
        PathProbe probe = new PathProbe("test");
        AStarPathFinder old = worker(), current = worker();
        setRunning(probe, old); probe.cancel(); setRunning(probe, current);
        probe.complete(old, negative());
        assertTrue(probe.isRunning()); assertNull(probe.poll());
        var answer = new PathProbe.Result(PathProbe.Outcome.COMPLETE, null, 1);
        probe.complete(current, answer);
        assertFalse(probe.isRunning()); assertSame(answer, probe.poll()); assertNull(probe.poll());
    }

    @Test public void cancellingAnAlreadyFinishedRequestDiscardsItsAnswer() throws Exception {
        PathProbe probe = new PathProbe("test");
        AStarPathFinder worker = worker(); setRunning(probe, worker);
        probe.complete(worker, negative()); probe.cancel();
        assertNull(probe.poll());
    }

    private static PathProbe.Result negative() {
        return new PathProbe.Result(PathProbe.Outcome.NONE, null, 1, true);
    }
    private static AStarPathFinder worker() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return (AStarPathFinder) ((Unsafe) field.get(null)).allocateInstance(AStarPathFinder.class);
    }
    private static void setRunning(PathProbe probe, AStarPathFinder worker) throws Exception {
        Field field = PathProbe.class.getDeclaredField("running"); field.setAccessible(true); field.set(probe, worker);
    }
}
