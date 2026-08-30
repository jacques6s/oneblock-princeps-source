/*
 * This file is part of the Princeps project.
 */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class BuilderWaterloggingPolicyTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void v2StructuralTargetDriesWaterloggedBlocksWithoutChangingAnythingElse() {
        BlockState wet = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);

        BlockState structural = BuilderProcess.structuralDesiredState(wet);

        assertEquals(wet.setValue(BlockStateProperties.WATERLOGGED, false), structural);
        assertFalse(structural.getValue(BlockStateProperties.WATERLOGGED));
    }

    @Test
    public void dryAndNonWaterloggableTargetsRemainUntouched() {
        BlockState dry = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, false);
        BlockState stone = Blocks.STONE.defaultBlockState();

        assertSame(dry, BuilderProcess.structuralDesiredState(dry));
        assertSame(stone, BuilderProcess.structuralDesiredState(stone));
        assertNull(BuilderProcess.structuralDesiredState(null));
    }
}
