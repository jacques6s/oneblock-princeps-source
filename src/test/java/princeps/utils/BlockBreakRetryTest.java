/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.utils;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class BlockBreakRetryTest {
    private final Object world = new Object();
    private final BlockPos target = new BlockPos(7, -45, 1);
    private final BlockBreakRetry retry = new BlockBreakRetry();

    @BeforeClass public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test public void aBridgeReplacingTheApproachBlockDoesNotInheritItsRejection() {
        reject(Blocks.DEEPSLATE.defaultBlockState(), 0);
        assertTrue(retry.blocked(world, target, 200));
        retry.serverChanged(world, target, Blocks.STONE.defaultBlockState());
        assertFalse("an authoritative replacement retires the old block's denial at the same coordinate",
                retry.blocked(world, target, 200));
    }

    @Test public void confirmedAirAllowsEvenTheSameMaterialToBePlacedAndMinedAgain() {
        reject(Blocks.STONE.defaultBlockState(), 0);
        retry.serverChanged(world, target, Blocks.AIR.defaultBlockState());
        retry.serverChanged(world, target, Blocks.STONE.defaultBlockState());
        retry.completed(world, target, Blocks.STONE.defaultBlockState(), 0, 200);
        assertFalse(retry.blocked(world, target, 200));
    }

    @Test public void aServerCorrectionToTheSameBlockDoesNotPretendTheBreakSucceeded() {
        reject(Blocks.DEEPSLATE.defaultBlockState(), 0);
        retry.serverChanged(world, target, Blocks.DEEPSLATE.defaultBlockState());
        assertTrue(retry.blocked(world, target, 200));
        assertTrue(retry.diagnosis(world, target, 200).contains("retry 1/3"));
    }

    @Test public void aMissingConfirmationGetsARetryWithoutRequiringAnotherServerPacket() {
        reject(Blocks.STONE.defaultBlockState(), 0);
        assertTrue(retry.blocked(world, target, 5099));
        assertFalse(retry.blocked(world, target, 5100));
        assertFalse(retry.blocked(world, target, 5200));
    }

    @Test public void persistentRejectionHasThreeBackedOffRetriesThenAnExplicitBlockedReason() {
        long time = 0;
        long[] delay = {5000, 10000, 20000};
        for (int attempt = 0; attempt < 3; attempt++) {
            reject(Blocks.STONE.defaultBlockState(), time);
            assertTrue(retry.blocked(world, target, time + 100 + delay[attempt] - 1));
            time += 100 + delay[attempt];
            assertFalse(retry.blocked(world, target, time));
        }
        reject(Blocks.STONE.defaultBlockState(), time);
        assertTrue(retry.blocked(world, target, time + 1_000_000));
        assertEquals("server-restored-block: retries exhausted", retry.diagnosis(world, target, time + 1_000_000));
        retry.serverChanged(world, target, Blocks.AIR.defaultBlockState());
        assertFalse("a later real change still releases exhausted evidence", retry.blocked(world, target, time + 1_000_001));
    }

    @Test public void fallingColumnsAndDistantIndependentBreaksKeepMining() {
        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        for (int height = 8; height >= 0; height--) {
            long time = (8 - height) * 100;
            retry.completed(world, target, gravel, height, time);
            assertFalse(retry.blocked(world, target, time));
        }
        retry.completed(world, target, gravel, 8, 5000);
        assertFalse(retry.blocked(world, target, 5000));
    }

    @Test public void unrelatedChangesAndPredictedAirDoNotEraseTheRejectedTarget() {
        reject(Blocks.STONE.defaultBlockState(), 0);
        retry.serverChanged(world, target.east(), Blocks.AIR.defaultBlockState());
        retry.completed(world, target, Blocks.AIR.defaultBlockState(), 0, 200);
        assertTrue(retry.blocked(world, target, 200));
        assertFalse(retry.blocked(world, target.east(), 200));
    }

    @Test public void worldChangesAndExplicitResetDiscardOldEvidence() {
        reject(Blocks.STONE.defaultBlockState(), 0);
        assertFalse(retry.blocked(new Object(), target, 200));
        reject(Blocks.STONE.defaultBlockState(), 1000);
        assertTrue(retry.blocked(world, target, 1200));
        retry.clear();
        assertFalse(retry.blocked(world, target, 1200));
    }

    private void reject(BlockState state, long time) {
        retry.completed(world, target, state, 0, time);
        retry.completed(world, target, state, 0, time + 100);
    }
}
