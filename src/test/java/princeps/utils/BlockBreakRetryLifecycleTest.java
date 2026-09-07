/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.utils;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.event.events.BlockChangeEvent;
import princeps.api.event.events.WorldEvent;
import princeps.api.event.events.type.EventState;
import princeps.api.utils.IPlayerContext;
import princeps.api.utils.Pair;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.*;

/** Calls the actual input listener and helper queries; only the already-rejected starting state is seeded. */
public class BlockBreakRetryLifecycleTest {
    private static final BlockPos TARGET = new BlockPos(7, -45, 1);

    @BeforeClass public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test public void aServerConfirmedBridgeReopensTheActualInputGuard() throws Exception {
        BlockBreakHelper helper = rejectedHelper();
        InputOverrideHandler input = listener(helper);
        assertTrue(helper.isAimingAtBlacklisted());
        input.onBlockChange(new BlockChangeEvent(ChunkPos.containing(TARGET),
                List.of(new Pair<>(TARGET, Blocks.STONE.defaultBlockState()))));
        assertFalse(helper.isAimingAtBlacklisted());
        assertFalse(helper.isBlacklisted(TARGET));
        assertEquals("none", helper.breakRetryDiagnosis());
    }

    @Test public void anUnchangedServerCorrectionKeepsTheActualInputGuardClosed() throws Exception {
        BlockBreakHelper helper = rejectedHelper();
        listener(helper).onBlockChange(new BlockChangeEvent(ChunkPos.containing(TARGET),
                List.of(new Pair<>(TARGET, Blocks.DEEPSLATE.defaultBlockState()))));
        assertTrue(helper.isAimingAtBlacklisted());
        assertTrue(helper.breakRetryDiagnosis().contains("retry 1/3"));
    }

    @Test public void realWorldListenerRetiresThePreviousConnectionsRejections() throws Exception {
        BlockBreakHelper helper = rejectedHelper();
        listener(helper).onWorldEvent(new WorldEvent(null, EventState.POST));
        assertFalse(helper.isAimingAtBlacklisted());
    }

    @Test public void onlyAutoDigInputRetriesWhileTerminalPlannerQueriesStayRejected() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean excavation = new java.util.concurrent.atomic.AtomicBoolean(true);
        BlockBreakHelper helper = rejectedHelper(excavation::get, 6000);
        assertFalse("the elapsed AutoDig cooldown permits a real new attempt", helper.isAimingAtBlacklisted());
        assertTrue("V3 terminal consumers retain their existing rejection contract", helper.isBlacklisted(TARGET));
        excavation.set(false);
        assertTrue("the next owner cannot inherit AutoDig retry permission", helper.isAimingAtBlacklisted());
        excavation.set(true);
        assertFalse(helper.isAimingAtBlacklisted());
    }

    private static BlockBreakHelper rejectedHelper() throws Exception {
        return rejectedHelper(() -> true, 0);
    }

    private static BlockBreakHelper rejectedHelper(java.util.function.BooleanSupplier retries, long elapsed) throws Exception {
        IPlayerContext context = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "world", "player" -> null;
                    case "objectMouseOver" -> new BlockHitResult(Vec3.atCenterOf(TARGET), Direction.WEST, TARGET, false);
                    default -> throw new AssertionError("Unexpected client access: " + method.getName());
                });
        BlockBreakHelper helper = new BlockBreakHelper(context, retries);
        Field field = BlockBreakHelper.class.getDeclaredField("breakRetry");
        field.setAccessible(true);
        BlockBreakRetry retry = (BlockBreakRetry) field.get(helper);
        long now = System.nanoTime() / 1_000_000L - elapsed;
        retry.completed(null, TARGET, Blocks.DEEPSLATE.defaultBlockState(), 0, now - 100);
        retry.completed(null, TARGET, Blocks.DEEPSLATE.defaultBlockState(), 0, now);
        return helper;
    }

    private static InputOverrideHandler listener(BlockBreakHelper helper) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        InputOverrideHandler listener = (InputOverrideHandler) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, InputOverrideHandler.class);
        Field field = InputOverrideHandler.class.getDeclaredField("blockBreakHelper");
        field.setAccessible(true);
        field.set(listener, helper);
        return listener;
    }
}
