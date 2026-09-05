package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class BuilderScaffoldLedgerTest {
    private static final BlockPos OUTSIDE = new BlockPos(-5, 64, 9);

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void predictionCannotAuthorizeCleanupBeforeServerConfirmation() {
        BuilderScaffoldLedger ledger = new BuilderScaffoldLedger();
        // BetterBlockPos and vanilla BlockPos have different hash functions for the same cell.
        BlockPos movementPos = new princeps.api.utils.BetterBlockPos(OUTSIDE);
        assertTrue(ledger.record(movementPos, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), false, true, 10));
        assertTrue(ledger.awaitingServer());
        assertFalse(ledger.owns(OUTSIDE, Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(ledger.serverChanged(OUTSIDE, Blocks.COBBLESTONE.defaultBlockState()));
        assertFalse(ledger.awaitingServer());
        assertTrue(ledger.owns(OUTSIDE, Blocks.COBBLESTONE.defaultBlockState()));
        // A client-predicted break cannot revoke ownership; a rejected break still owes cleanup.
        assertFalse(ledger.owns(OUTSIDE, Blocks.AIR.defaultBlockState()));
        assertTrue(ledger.contains(OUTSIDE));
        ledger.serverChanged(OUTSIDE, Blocks.COBBLESTONE.defaultBlockState());
        assertTrue(ledger.contains(OUTSIDE));
        ledger.serverChanged(OUTSIDE, Blocks.AIR.defaultBlockState());
        assertFalse(ledger.contains(OUTSIDE));
    }

    @Test
    public void existingBlocksAndRequestedTargetsNeverBecomeOwnedScaffolding() {
        BuilderScaffoldLedger ledger = new BuilderScaffoldLedger();
        assertFalse(ledger.record(OUTSIDE, Blocks.COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), false, true, 10));
        assertFalse(ledger.record(OUTSIDE, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), true, true, 10));
        assertFalse(ledger.record(OUTSIDE, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), false, false, 10));
        assertFalse(ledger.record(OUTSIDE, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false, true, 10));
        assertFalse(ledger.serverChanged(OUTSIDE, Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(ledger.positions().isEmpty());
    }

    @Test
    public void serverRejectionAndForeignReplacementRemoveCleanupAuthority() {
        BuilderScaffoldLedger ledger = new BuilderScaffoldLedger();
        ledger.record(OUTSIDE, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), false, true, 10);
        assertFalse(ledger.serverChanged(OUTSIDE, Blocks.STONE.defaultBlockState()));
        assertFalse(ledger.awaitingServer());
        assertTrue(ledger.positions().isEmpty());
        ledger.record(OUTSIDE, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), false, true, 10);
        ledger.serverChanged(OUTSIDE, Blocks.COBBLESTONE.defaultBlockState());
        ledger.serverChanged(OUTSIDE, Blocks.STONE.defaultBlockState());
        assertTrue(ledger.positions().isEmpty());
        assertFalse(ledger.owns(OUTSIDE, Blocks.STONE.defaultBlockState()));
    }

    @Test
    public void missingConfirmationRemainsNamedUntilItsTerminalDeadline() {
        BuilderScaffoldLedger ledger = new BuilderScaffoldLedger();
        ledger.record(OUTSIDE, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), false, true, 10);
        assertTrue(ledger.unconfirmedBefore(10).isEmpty());
        assertTrue(ledger.unconfirmedBefore(11).contains(OUTSIDE));
        assertTrue(ledger.awaitingServer());
        assertFalse(ledger.contains(OUTSIDE));
    }
}
