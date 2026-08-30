package princeps.process;

import net.minecraft.core.BlockPos;
import org.junit.Test;
import org.junit.BeforeClass;
import princeps.api.pathing.goals.Goal;
import princeps.api.utils.BetterBlockPos;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure boundary checks for the caller-selected Map-Art offer band. */
public class BuilderRowBandTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    public void fiveRowBandIncludesBothEdgesOnly() {
        assertFalse(BuilderProcess.rowBandContains(39, 40, 5));
        assertTrue(BuilderProcess.rowBandContains(40, 40, 5));
        assertTrue(BuilderProcess.rowBandContains(44, 40, 5));
        assertFalse(BuilderProcess.rowBandContains(45, 40, 5));
    }

    @Test
    public void widthIsClampedAndMissingRearNeverMatches() {
        assertTrue(BuilderProcess.rowBandContains(-12, -12, 0));
        assertFalse(BuilderProcess.rowBandContains(-11, -12, 0));
        assertFalse(BuilderProcess.rowBandContains(0, Integer.MIN_VALUE, 5));
    }

    @Test
    public void consecutiveFiveRowBandsRunInOppositeDirections() {
        assertTrue(BuilderProcess.rowBandRunsForward(67, 67, 5));
        assertFalse(BuilderProcess.rowBandRunsForward(72, 67, 5));
        assertTrue(BuilderProcess.rowBandRunsForward(77, 67, 5));
    }

    @Test
    public void slidingRearCellsStayPinnedToTheirFixedFiveRowBand() {
        assertTrue(BuilderProcess.rowBandStart(72, 67, 5) == 72);
        assertTrue(BuilderProcess.rowBandStart(73, 67, 5) == 72);
        assertTrue(BuilderProcess.rowBandStart(76, 67, 5) == 72);
        assertTrue(BuilderProcess.rowBandStart(77, 67, 5) == 77);
        assertTrue(BuilderProcess.rowBandStart(Integer.MIN_VALUE, 67, 5) == Integer.MIN_VALUE);
    }

    @Test
    public void rowBreakUsesPictureTopBesideTargetInsteadOfTargetColumn() {
        Goal goal = BuilderProcess.rowBreakGoal(new BlockPos(10, 20, 30));

        assertTrue(goal.isInGoal(9, 21, 30));
        assertTrue(goal.isInGoal(11, 21, 30));
        assertTrue(goal.isInGoal(10, 21, 29));
        assertTrue(goal.isInGoal(10, 21, 31));
        assertFalse(goal.isInGoal(10, 21, 30));
        assertFalse(goal.isInGoal(9, 20, 30));
    }

    @Test
    public void rowBuildProtectsRealSupportButNotToeOverlapOnAdjacentPixel() {
        BetterBlockPos feet = new BetterBlockPos(67, -59, 68);
        BetterBlockPos pathStart = new BetterBlockPos(67, -59, 68);

        assertTrue(BuilderProcess.rowOwnSupportColumn(67, 68, feet, pathStart));
        assertFalse(BuilderProcess.rowOwnSupportColumn(67, 67, feet, pathStart));
    }

    @Test
    public void rowBuildAlwaysScansPicturePlaneBelowFeetForWrongStarter() {
        assertTrue(BuilderProcess.breakScanMinDy(true, false) == -1);
        assertTrue(BuilderProcess.breakScanMinDy(false, true) == -1);
        assertTrue(BuilderProcess.breakScanMinDy(false, false) == 0);
    }
}
