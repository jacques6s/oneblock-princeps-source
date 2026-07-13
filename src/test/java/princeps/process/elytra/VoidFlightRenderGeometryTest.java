package princeps.process.elytra;

import princeps.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class VoidFlightRenderGeometryTest {

    @Test
    public void capsTheRealDirectPlanAtTheLocalRenderHorizon() {
        List<BetterBlockPos> result = VoidFlightRenderGeometry.directCorridor(
                new BlockPos(0, -76, 0), new BlockPos(10_000, -80, 10_000), -80, 100.0D);

        assertEquals(2, result.size());
        assertEquals(new BetterBlockPos(0, -80, 0), result.get(0));
        assertEquals(new BetterBlockPos(70, -80, 70), result.get(1));
    }

    @Test
    public void endsAtANearDestinationInsteadOfOvershootingIt() {
        List<BetterBlockPos> result = VoidFlightRenderGeometry.directCorridor(
                new BlockPos(4, -75, 7), new BlockPos(40, -90, 70), -80, 100.0D);

        assertEquals(List.of(
                new BetterBlockPos(4, -80, 7),
                new BetterBlockPos(40, -80, 70)), result);
    }

    @Test
    public void keepsTheCruiseAltitudeAndAvoidsADegenerateSegmentAtTheGoal() {
        List<BetterBlockPos> result = VoidFlightRenderGeometry.directCorridor(
                new BlockPos(8, -70, 9), new BlockPos(8, -120, 9), -80, 100.0D);

        assertEquals(List.of(
                new BetterBlockPos(8, -80, 9)), result);
    }
}
