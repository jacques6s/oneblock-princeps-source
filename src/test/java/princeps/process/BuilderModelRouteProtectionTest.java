/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.Princeps;
import princeps.api.pathing.PlacementLicence;
import princeps.api.pathing.WadeLicence;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.movement.IMovement;
import princeps.api.process.IPrincepsProcess;
import princeps.api.utils.BetterBlockPos;
import princeps.behavior.PathingBehavior;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.path.PathExecutor;
import princeps.process.builder.BuilderProgressWatch;
import princeps.utils.BlockBreakHelper;
import princeps.utils.InputOverrideHandler;
import princeps.utils.PathingControlManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.*;
import static princeps.process.BuilderModelRestorationTest.*;

/** Real executor factory/cut/splice, unsafe-cancel, Input predicate and BBH controller boundary.
 * Movement lists and stored safeToCancel=false are fixture inputs; this is not a full physics tick. */
public class BuilderModelRouteProtectionTest {
    @BeforeClass public static void bootstrap() throws Exception { BuilderModelRestorationTest.bootstrap(); }

    @Test public void searchFactoryUsesItsCompletedSearchEvenAfterBehaviorContextChanges() throws Exception {
        Route f = route();
        set(f.pathing, "context", allocate(CalculationContext.class));
        Method factory = method(PathingBehavior.class, "executorForSearch", IPath.class, CalculationContext.class);
        PathExecutor created = (PathExecutor) factory.invoke(f.pathing, path(0, 2), f.base.cost);
        assertSame(f.protection, get(created, "modelProtection"));
        assertFalse(created.allowsModelRemoval(TARGET, null, () -> false));
    }

    @Test public void unsafeCancelAfterRealPauseCannotReachControllerDamage() throws Exception {
        Route f = route();
        set(f.base.owner, "progressWatch", new BuilderProgressWatch(5, 60, 2));
        f.base.owner.pause();
        assertTrue(f.base.owner.isPaused());
        assertFalse(f.pathing.cancelSegmentIfSafe());
        assertSame(f.executor, f.pathing.getCurrent());
        f.base.tickMining();
        assertEquals(0, f.base.damage.get());
    }

    @Test public void unsafeCurrentRouteSurvivesOwnerNullWithoutGrantingMining() throws Exception {
        Route f = route();
        set(f.control, "inControlThisTick", null);
        assertFalse(f.pathing.cancelSegmentIfSafe());
        f.base.tickMining();
        assertEquals(0, f.base.damage.get());
    }

    @Test public void foreignOwnerCannotMineThroughTheStillCurrentBuilderRoute() throws Exception {
        Route f = route();
        set(f.control, "inControlThisTick", foreign());
        assertFalse(f.pathing.cancelSegmentIfSafe());
        f.base.tickMining();
        assertEquals(0, f.base.damage.get());
        assertNull(get(f.input, "supportMiningOwner"));
    }

    @Test public void absentAndOrdinaryRoutesReleaseTheirOldProtection() throws Exception {
        Route f = route();
        set(f.control, "inControlThisTick", null);
        set(f.pathing, "current", null);
        f.base.tickMining();
        assertEquals(1, f.base.damage.get());
        set(f.pathing, "current", new PathExecutor(f.pathing, path(0, 2)));
        f.base.tickMining();
        assertEquals(2, f.base.damage.get());
    }

    @Test public void fullModelWorldChangeAfterTheSearchIsReadAtActualMining() throws Exception {
        Route f = route();
        set(f.control, "inControlThisTick", null);
        f.base.world.states.put(TARGET.asLong(), Blocks.COBBLESTONE.defaultBlockState());
        f.base.tickMining();
        assertEquals(1, f.base.damage.get());
        f.base.world.states.put(TARGET.asLong(), Blocks.BLACK_STAINED_GLASS.defaultBlockState());
        f.base.tickMining();
        assertEquals(1, f.base.damage.get());
        assertEquals(1, f.base.resets.get());
    }

    @Test public void unloadedActualCellAndChangedWorldCannotBorrowOldWorldPermission() throws Exception {
        Route f = route();
        f.base.world.unloaded = true;
        assertFalse(f.executor.allowsModelRemoval(TARGET, null, () -> false));
        Fixture other = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true);
        f.base.world = other.world;
        assertFalse(f.executor.allowsModelRemoval(TARGET, null, () -> false));
    }

    @Test public void historyCutRetainsTheExactProtectionAndNormalPositionAccounting() throws Exception {
        Route f = route();
        PathExecutor longPath = new QuietExecutor(f.pathing, path(0, 4), f.protection);
        set(longPath, "pathPosition", 3);
        Method cut = method(PathExecutor.class, "cutIfTooLong", int.class, int.class);
        PathExecutor shortened = (PathExecutor) cut.invoke(longPath, 2, 1);
        assertEquals(2, shortened.getPosition());
        assertEquals(4, shortened.getPath().length());
        assertSame(f.protection, get(shortened, "modelProtection"));
        assertFalse(shortened.allowsModelRemoval(TARGET, null, () -> false));
    }

    @Test public void sameBindingSpliceRetainsProtectionAcrossBothSegments() throws Exception {
        Route f = route();
        BuilderProcess.ModelProtection same = protection(f.base, f.base.full, ORIGIN, f.base.world, f.base.player);
        PathExecutor next = new PathExecutor(f.pathing, path(2, 4), PlacementLicence.NONE, WadeLicence.NONE, same);
        PathExecutor joined = f.executor.trySplice(next);
        assertNotSame(f.executor, joined);
        assertEquals(5, joined.getPath().length());
        assertSame(f.protection, get(joined, "modelProtection"));
        assertFalse(joined.allowsModelRemoval(TARGET, null, () -> false));
    }

    @Test public void differentModelOriginWorldPlayerOrOrdinaryNextCannotDiluteTheRoute() throws Exception {
        Route f = route(); Fixture other = fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true);
        for (BuilderProcess.ModelProtection changed : new BuilderProcess.ModelProtection[]{
                protection(f.base, other.full, ORIGIN, f.base.world, f.base.player),
                protection(f.base, f.base.full, ORIGIN.above(), f.base.world, f.base.player),
                protection(f.base, f.base.full, ORIGIN, other.world, f.base.player),
                protection(f.base, f.base.full, ORIGIN, f.base.world, other.player), null}) {
            PathExecutor next = new PathExecutor(f.pathing, path(2, 4), PlacementLicence.NONE, WadeLicence.NONE, changed);
            assertSame(f.executor, f.executor.trySplice(next));
        }
    }

    @Test public void explicitSameSessionRepairMayContinueButNewModelCannotLicenseOldRoute() throws Exception {
        Fixture base = fixture(Blocks.HOPPER.defaultBlockState(), true);
        Route f = route(base);
        var wrong = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST);
        base.world.states.put(TARGET.asLong(), wrong);
        assertFalse(f.executor.allowsModelRemoval(TARGET, base.owner, () -> false));
        base.owner.selectSupportRepair(TARGET, wrong, Blocks.HOPPER.defaultBlockState());
        assertTrue(f.executor.allowsModelRemoval(TARGET, base.owner, () -> false));
        set(base.owner, "buildTick", 1L);
        assertTrue(f.executor.allowsModelRemoval(TARGET, base.owner, () -> true));
        assertFalse(f.executor.allowsModelRemoval(TARGET, base.owner, () -> false));
        Fixture newBuild = fixture(Blocks.HOPPER.defaultBlockState(), true);
        set(base.owner, "realSchematic", newBuild.full);
        base.owner.selectSupportRepair(TARGET, wrong, Blocks.HOPPER.defaultBlockState());
        assertFalse(f.executor.allowsModelRemoval(TARGET, base.owner, () -> true));
    }

    @Test public void correctCellAndForeignOwnerCannotUseAStoredRepairException() throws Exception {
        Fixture base = fixture(Blocks.HOPPER.defaultBlockState(), true); Route f = route(base);
        var wrong = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST);
        base.world.states.put(TARGET.asLong(), wrong);
        base.owner.selectSupportRepair(TARGET, wrong, Blocks.HOPPER.defaultBlockState());
        assertFalse(f.executor.allowsModelRemoval(TARGET, foreign(), () -> true));
        base.world.states.put(TARGET.asLong(), Blocks.HOPPER.defaultBlockState());
        assertFalse(f.executor.allowsModelRemoval(TARGET, base.owner, () -> true));
    }

    @Test public void realThreeTickRepairCrossesBothGuardsAndForeignHandoverRevokesIt() throws Exception {
        Fixture base = fixture(Blocks.HOPPER.defaultBlockState(), true); Route f = route(base);
        var wrong = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST);
        base.world.states.put(TARGET.asLong(), wrong);
        base.owner.selectSupportRepair(TARGET, wrong, Blocks.HOPPER.defaultBlockState());
        for (long tick = 0; tick < 3; tick++) {
            set(base.owner, "buildTick", tick);
            base.tickMining();
            assertTrue(base.mining.isBreakingBlock());
        }
        assertEquals(3, base.damage.get());
        set(f.control, "inControlThisTick", foreign());
        base.tickMining();
        assertEquals(3, base.damage.get());
        assertEquals(1, base.resets.get());
        set(f.control, "inControlThisTick", base.owner);
        set(base.owner, "buildTick", 3L);
        base.tickMining();
        assertEquals("an old held click cannot renew the revoked repair", 3, base.damage.get());
    }

    private static Route route() throws Exception { return route(fixture(Blocks.BLACK_STAINED_GLASS.defaultBlockState(), true)); }
    static Route route(Fixture base) throws Exception {
        PathingBehavior pathing = allocate(PathingBehavior.class); set(pathing, "ctx", base.ctx);
        PathingControlManager control = allocate(PathingControlManager.class);
        set(control, "inControlThisTick", base.owner);
        Princeps bot = allocate(Princeps.class);
        set(bot, "pathingBehavior", pathing); set(bot, "pathingControlManager", control);
        set(base.owner, "princeps", bot);
        InputOverrideHandler input = allocate(InputOverrideHandler.class);
        set(input, "princeps", bot); set(input, "ctx", base.ctx); set(bot, "inputOverrideHandler", input);
        BuilderProcess.ModelProtection protection = protection(base, base.full, ORIGIN, base.world, base.player);
        set(base.cost, "modelProtection", protection);
        PathExecutor executor = new PathExecutor(pathing, path(0, 2), PlacementLicence.NONE, WadeLicence.NONE, protection);
        set(pathing, "current", executor); set(pathing, "safeToCancel", false);
        Method actualPredicate = method(InputOverrideHandler.class, "allowsMining", BlockPos.class);
        Constructor<BlockBreakHelper> ctor = BlockBreakHelper.class.getDeclaredConstructor(princeps.api.utils.IPlayerContext.class, Predicate.class);
        ctor.setAccessible(true);
        base.mining = ctor.newInstance(base.ctx, (Predicate<BlockPos>) target -> {
            try { return (boolean) actualPredicate.invoke(input, target); }
            catch (ReflectiveOperationException ex) { throw new AssertionError(ex instanceof InvocationTargetException ? ex.getCause() : ex); }
        });
        set(input, "blockBreakHelper", base.mining);
        return new Route(base, pathing, control, input, executor, protection);
    }

    private static BuilderProcess.ModelProtection protection(Fixture f, princeps.api.schematic.ISchematic model,
                                                              Vec3i origin, Object world, Object player) {
        return new BuilderProcess.ModelProtection(f.owner, model, origin, world, player, List.of());
    }
    record Route(Fixture base, PathingBehavior pathing, PathingControlManager control, InputOverrideHandler input,
                         PathExecutor executor, BuilderProcess.ModelProtection protection) { }
    static IPrincepsProcess foreign() {
        return (IPrincepsProcess) Proxy.newProxyInstance(IPrincepsProcess.class.getClassLoader(), new Class<?>[]{IPrincepsProcess.class},
                (proxy, method, args) -> { throw new AssertionError("no foreign process actions"); });
    }
    private static IPath path(int first, int last) {
        List<BetterBlockPos> positions = new ArrayList<>(); List<IMovement> movements = new ArrayList<>();
        for (int i = first; i <= last; i++) positions.add(new BetterBlockPos(30 + i, 20, 30));
        for (int i = 0; i < positions.size() - 1; i++) {
            BetterBlockPos src = positions.get(i), dest = positions.get(i + 1);
            movements.add((IMovement) Proxy.newProxyInstance(IMovement.class.getClassLoader(), new Class<?>[]{IMovement.class},
                    (proxy, method, args) -> switch(method.getName()) {
                        case "getSrc" -> src; case "getDest" -> dest;
                        default -> throw new AssertionError("no simulated movement update: " + method.getName());
                    }));
        }
        return new IPath() {
            public List<IMovement> movements() { return List.copyOf(movements); }
            public List<BetterBlockPos> positions() { return List.copyOf(positions); }
            public Goal getGoal() { return new GoalBlock(40,20,30); }
            public int getNumNodesConsidered() { return positions.size(); }
        };
    }
    private static class QuietExecutor extends PathExecutor {
        QuietExecutor(PathingBehavior behavior, IPath path, BuilderProcess.ModelProtection protection) {
            super(behavior, path, PlacementLicence.NONE, WadeLicence.NONE, protection);
        }
        @Override public void logDebug(String message) { /* Local cut test does not initialise a game chat sink. */ }
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }
}
