/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princeps is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princeps.  If not, see <https://www.gnu.org/licenses/>.
 */

package princeps.launch.mixins;

import princeps.api.PrincepsAPI;
import princeps.api.IPrinceps;
import princeps.api.event.events.PlayerUpdateEvent;
import princeps.api.event.events.SprintStateEvent;
import princeps.api.event.events.type.EventState;
import princeps.behavior.LookBehavior;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * @author Brady
 * @since 8/1/2018
 */
@Mixin(LocalPlayer.class)
public class MixinClientPlayerEntity {
    @Unique
    private static final MethodHandle MAY_FLY = princeps$resolveMayFly();

    @Unique
    private static MethodHandle princeps$resolveMayFly() {
        try {
            var lookup = MethodHandles.publicLookup();
            return lookup.findVirtual(LocalPlayer.class, "mayFly", MethodType.methodType(boolean.class));
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/player/AbstractClientPlayer.tick()V",
                    shift = At.Shift.AFTER
            )
    )
    private void onPreUpdate(CallbackInfo ci) {
        IPrinceps princeps = PrincepsAPI.getProvider().getPrincepsForPlayer((LocalPlayer) (Object) this);
        if (princeps != null) {
            princeps.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
        }
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "FIELD",
                    target = "net/minecraft/world/entity/player/Abilities.mayfly:Z"
            )
    )
    @Group(name = "mayFly", min = 1, max = 1)
    private boolean isAllowFlying(Abilities capabilities) {
        IPrinceps princeps = PrincepsAPI.getProvider().getPrincepsForPlayer((LocalPlayer) (Object) this);
        if (princeps == null) {
            return capabilities.mayfly;
        }
        return !princeps.getPathingBehavior().isPathing() && capabilities.mayfly;
    }

    @Redirect(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;mayFly()Z"
        )
    )
    @Group(name = "mayFly", min = 1, max = 1)
    private boolean onMayFlyNeoforge(LocalPlayer instance) throws Throwable {
        IPrinceps princeps = PrincepsAPI.getProvider().getPrincepsForPlayer((LocalPlayer) (Object) this);
        if (princeps == null) {
            return (boolean) MAY_FLY.invokeExact(instance);
        }
        return !princeps.getPathingBehavior().isPathing() && (boolean) MAY_FLY.invokeExact(instance);
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Input;sprint()Z"
            )
    )
    private boolean redirectSprintInput(final Input instance) {
        IPrinceps princeps = PrincepsAPI.getProvider().getPrincepsForPlayer((LocalPlayer) (Object) this);
        if (princeps == null) {
            return instance.sprint();
        }
        SprintStateEvent event = new SprintStateEvent();
        princeps.getGameEventHandler().onPlayerSprintState(event);
        if (event.getState() != null) {
            return event.getState();
        }
        if (princeps != PrincepsAPI.getProvider().getPrimaryPrinceps()) {
            // hitting control shouldn't make all bots sprint
            return false;
        }
        return instance.sprint();
    }

    @Inject(
            method = "rideTick",
            at = @At(
                    value = "HEAD"
            )
    )
    private void updateRidden(CallbackInfo cb) {
        IPrinceps princeps = PrincepsAPI.getProvider().getPrincepsForPlayer((LocalPlayer) (Object) this);
        if (princeps != null) {
            ((LookBehavior) princeps.getLookBehavior()).pig();
        }
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;tryToStartFallFlying()Z"
            )
    )
    private boolean tryToStartFallFlying(final LocalPlayer instance) {
        IPrinceps princeps = PrincepsAPI.getProvider().getPrincepsForPlayer(instance);
        if (princeps != null && princeps.getPathingBehavior().isPathing()) {
            return false;
        }
        return instance.tryToStartFallFlying();
    }

    // FlowNav frame-rate camera. ROOT CAUSE of the 20 Hz view stepping: vanilla interpolates every
    // entity's view rotation across frames EXCEPT the local player's — LocalPlayer overrides
    // getViewYRot/getViewXRot to return the RAW yRot/xRot (mouse input updates per frame, so vanilla
    // never needs to lerp it). A bot that turns once per game tick therefore renders as 20 steps/s.
    // While Princeps steers the view, return the previous->current tick rotation lerped by the frame's
    // partialTick — exactly the interpolation vanilla applies to every other entity. At partialTicks
    // == 1 this returns the exact applied rotation, so gameplay raycasts are byte-identical.
    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void flownavViewYRot(float partialTicks, CallbackInfoReturnable<Float> cir) {
        // Hot path (many calls per FRAME): the guard is a single reference compare, no provider lookup.
        if (princeps.flownav.FlowCam.ownsView(this)) {
            cir.setReturnValue(princeps.flownav.FlowCam.viewYaw(partialTicks));
        }
    }

    @Inject(method = "getViewXRot", at = @At("HEAD"), cancellable = true)
    private void flownavViewXRot(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (princeps.flownav.FlowCam.ownsView(this)) {
            cir.setReturnValue(princeps.flownav.FlowCam.viewPitch(partialTicks));
        }
    }
}
