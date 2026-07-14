package princeps.process.elytra;

import princeps.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VoidFlightRenderGeometryTest {

    private static final BlockPos ANCHOR = new BlockPos(0, -76, 0);
    private static final BlockPos DEST = new BlockPos(10_000, -80, 0);

    @Test
    public void theLineIsStationaryWhileThePlayerDriftsSideways() {
        // Same along-line progress, wildly different lateral drift: the red line must not move a block.
        List<BetterBlockPos> onLine = VoidFlightRenderGeometry.plannedWindow(
                ANCHOR, DEST, -80, new BlockPos(50, -80, 0), 30, 100);
        List<BetterBlockPos> drifted = VoidFlightRenderGeometry.plannedWindow(
                ANCHOR, DEST, -80, new BlockPos(50, -80, 14), 30, 100);

        assertEquals(onLine, drifted);
    }

    @Test
    public void theWindowSlidesAlongTheFixedLineWithoutMovingItsNodes() {
        List<BetterBlockPos> early = VoidFlightRenderGeometry.plannedWindow(
                ANCHOR, DEST, -80, new BlockPos(50, -80, 0), 30, 100);
        List<BetterBlockPos> later = VoidFlightRenderGeometry.plannedWindow(
                ANCHOR, DEST, -80, new BlockPos(90, -80, 0), 30, 100);

        // Advancing 40 blocks slides the window by 40 nodes; the overlap is node-identical (fixed geometry).
        assertEquals(new BetterBlockPos(20, -80, 0), early.get(0));
        assertEquals(new BetterBlockPos(60, -80, 0), later.get(0));
        assertTrue(new HashSet<>(later).containsAll(
                early.subList(40, early.size()))); // early[40..] == later window start region
    }

    @Test
    public void nodesFollowADiagonalPlanLineOnePerBlockOfTravel() {
        List<BetterBlockPos> window = VoidFlightRenderGeometry.plannedWindow(
                new BlockPos(0, -76, 0), new BlockPos(70, -90, 70), -80, new BlockPos(0, -80, 0), 30, 100);

        assertEquals(new BetterBlockPos(0, -80, 0), window.get(0));
        assertEquals(new BetterBlockPos(70, -80, 70), window.get(window.size() - 1));
        // Diagonal of ~99 blocks length: every step lands on the fixed line, deduped where rounding collides.
        for (BetterBlockPos p : window) {
            assertEquals("stays on the diagonal", p.getX(), p.getZ());
            assertEquals(-80, p.getY());
        }
    }

    @Test
    public void capsTheWindowAtTheLocalHorizonLikeTheStandardPath() {
        List<BetterBlockPos> window = VoidFlightRenderGeometry.plannedWindow(
                ANCHOR, DEST, -80, new BlockPos(0, -80, 0), 30, 100);

        assertEquals(new BetterBlockPos(0, -80, 0), window.get(0));
        assertEquals(new BetterBlockPos(100, -80, 0), window.get(window.size() - 1));
    }

    @Test
    public void endsExactlyOnTheDestinationWhenItIsInsideTheWindow() {
        List<BetterBlockPos> window = VoidFlightRenderGeometry.plannedWindow(
                new BlockPos(4, -75, 7), new BlockPos(40, -90, 70), -80, new BlockPos(38, -80, 66), 30, 100);

        assertEquals(new BetterBlockPos(40, -80, 70), window.get(window.size() - 1));
    }

    @Test
    public void aDegenerateZeroLengthPlanRendersJustTheDestinationColumn() {
        List<BetterBlockPos> window = VoidFlightRenderGeometry.plannedWindow(
                new BlockPos(8, -70, 9), new BlockPos(8, -120, 9), -80, new BlockPos(8, -80, 9), 30, 100);

        assertEquals(List.of(new BetterBlockPos(8, -80, 9)), window);
    }

    @Test
    public void lateralOffsetMeasuresDistanceFromThePlanLine() {
        assertEquals(0.0D, VoidFlightRenderGeometry.lateralOffset(ANCHOR, DEST, new BlockPos(500, -80, 0)), 1e-9);
        assertEquals(17.0D, VoidFlightRenderGeometry.lateralOffset(ANCHOR, DEST, new BlockPos(500, -80, 17)), 1e-9);
        // Zero-length plan: plain distance to the anchor column.
        assertEquals(5.0D, VoidFlightRenderGeometry.lateralOffset(
                new BlockPos(0, 0, 0), new BlockPos(0, -50, 0), new BlockPos(3, 0, 4)), 1e-9);
    }
}
