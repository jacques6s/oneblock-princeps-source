/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.Princeps;
import princeps.api.utils.IPlayerContext;
import princeps.behavior.PathingBehavior;
import princeps.pathing.movement.CalculationContext;
import princeps.pathing.movement.movements.MovementFall;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

/** Invokes the actual fall-executor decision. Ordinary inventory reads stop at a deliberate player sentinel. */
public class BuilderCleanupFallPolicyTest {
    private static Unsafe allocator;
    private static Method willPlaceBucket;

    @BeforeClass public static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (Item item : new Item[]{Items.WATER_BUCKET, Items.BUCKET}) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY);
        }
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        allocator = (Unsafe) field.get(null);
        willPlaceBucket = MovementFall.class.getDeclaredMethod("willPlaceBucket");
        willPlaceBucket.setAccessible(true);
    }

    @Test public void noCommittedContextStillReadsTheCurrentPlayerInventory() throws Exception {
        requireFreshDecision(null);
    }

    @Test public void ordinaryRouteCannotTrustAPreviouslyAvailableBucket() throws Exception {
        requireFreshDecision(context(false, true));
    }

    @Test public void ordinaryRouteCannotSuppressANewlyAvailableBucket() throws Exception {
        requireFreshDecision(context(false, false));
    }

    @Test public void builderAndExcavationContextsKeepTheSameFreshDefault() throws Exception {
        for (BuilderProcess.Lane lane : new BuilderProcess.Lane[]{
                BuilderProcess.Lane.A_NO_PLACING, BuilderProcess.Lane.B_HELPERS_ALLOWED, BuilderProcess.Lane.EXCAVATION_PATH}) {
            var route = allocate(BuilderProcess.BuilderCalculationContext.class);
            set(route, "lane", lane); set(route, "hasWaterBucket", true);
            requireFreshDecision(route);
        }
    }

    @Test public void onlyExplicitDryEscapeMaySuppressTheFreshBucketDecision() throws Exception {
        for (boolean previouslyHadBucket : new boolean[]{false, true}) {
            assertEquals(Boolean.FALSE, willPlaceBucket.invoke(fall(context(true, previouslyHadBucket))));
        }
    }

    private static void requireFreshDecision(CalculationContext route) throws Exception {
        try {
            willPlaceBucket.invoke(fall(route));
            fail("ordinary fall must construct its original fresh CalculationContext");
        } catch (InvocationTargetException expected) {
            assertTrue("unexpected live dependency: " + expected.getCause(), expected.getCause() instanceof FreshInventoryRead);
        }
    }

    private static CalculationContext context(boolean dry, boolean oldBucket) throws Exception {
        CalculationContext route = allocate(CalculationContext.class);
        set(route, "fallWaterForbidden", dry); set(route, "hasWaterBucket", oldBucket);
        return route;
    }

    private static MovementFall fall(CalculationContext route) throws Exception {
        Princeps owner = allocate(Princeps.class); PathingBehavior pathing = allocate(PathingBehavior.class);
        IPlayerContext player = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("player")) throw new FreshInventoryRead();
                    throw new AssertionError("unexpected live dependency: " + method.getName());
                });
        set(owner, "pathingBehavior", pathing); set(owner, "playerContext", player);
        set(pathing, "context", route);
        MovementFall movement = allocate(MovementFall.class); set(movement, "princeps", owner);
        return movement;
    }

    private static final class FreshInventoryRead extends RuntimeException { }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(allocator.allocateInstance(type)); }
    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
