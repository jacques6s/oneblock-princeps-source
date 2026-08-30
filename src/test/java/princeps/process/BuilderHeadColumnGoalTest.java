package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderHeadColumnGoalTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void directlyBelowIsNeverAdjacentToAPlacementTarget() {
        BlockPos feet = new BlockPos(80, -60, 68);
        BlockPos target = new BlockPos(80, -59, 68);

        BuilderProcess.GoalAdjacent goal = new BuilderProcess.GoalAdjacent(target, target, true);

        assertFalse("the pose that puts the target in the player's head must not count as arrived",
                goal.isInGoal(feet.getX(), feet.getY(), feet.getZ()));
        assertTrue("a real side stance on the target level remains a valid approach",
                goal.isInGoal(target.getX() + 1, target.getY(), target.getZ()));
    }

    @Test
    public void aRealSideCellRemainsAdjacent() {
        BlockPos target = new BlockPos(80, -59, 68);
        BuilderProcess.GoalAdjacent goal = new BuilderProcess.GoalAdjacent(target, target, true);

        assertTrue(goal.isInGoal(81, -59, 68));
    }
}
