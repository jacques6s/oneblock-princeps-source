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

package princeps.process.builder.v3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import princeps.api.utils.Rotation;

/**
 * The one place a builder click is allowed to be born: result equivalence against the LIVE ray, never angle equality.
 *
 * <h2>What it asks</h2>
 * <p>Not "is the head pointing where the plan said". That question is unanswerable under a humanized look — the aim
 * carries up to 0.42 degrees of yaw tremor, so {@code isReallyCloseTo}'s 0.01 degree tolerance never fires — and it is
 * the wrong question anyway. What decides whether a piston lands facing down is not the angle, it is what vanilla
 * would do with the ray that angle produces. So the gate takes the ray the client is ACTUALLY holding this tick,
 * simulates it through Vanilla's live item-placement path with the rotation the client is ACTUALLY at and the stack
 * it is ACTUALLY holding, and fires only when the state that would land is the state the plan proved. The planner's
 * pure approximation is never accepted as production authority.
 *
 * <h2>The five conditions, and why each one exists</h2>
 * <ol>
 *   <li>The live hit is on {@code plan.against}, on {@code plan.face}, and the cell that face opens onto is
 *       {@code plan.cell}. This is verbatim what {@code BlockPlaceHelper.ExpectedPlacement.matches} will check a
 *       moment later; a click that fails it is thrown away with no log line at all.</li>
 *   <li>The selected slot and the held item match the plan. {@code matches} compares item IDENTITY, and
 *       {@code InventoryBehavior} re-swaps slots 0 and 8 every single tick — a material that moved between the plan
 *       and the click voids the click silently.</li>
 *   <li>Simulating that live ray yields an accepted state, and that state is the one the plan predicted. This is the
 *       condition the 15 wrongly-landed blocks of the V2 trace failed: the gate there fired on a head that was still
 *       swinging, and the dominant look axis at click time was not the one the plan had scored.</li>
 *   <li>The place helper is not throttled. A click forced during the cooldown is discarded in
 *       {@code BlockPlaceHelper.tick} before anything happens — which is why the old builder re-pressed every tick and
 *       made its own trace unreadable: 793 of 1733 episodes read as "clicked more than once" when the bot was waiting
 *       on a door it could not see. V3 sends ONE click, at the tick it can arrive.</li>
 *   <li>No entity intersects the target cell. Vanilla's {@code isUnobstructed} tests entities, so a mob standing in
 *       the cell refuses the placement — and it refuses it after the cooldown has already been paid. This belongs
 *       here rather than in a wait state of its own: it is one more reason this tick's click cannot land, and a state
 *       machine that grows a state per reason stops being provable.</li>
 * </ol>
 *
 * <h2>Termination</h2>
 * <p>The aim curve converges monotonically and the hit result lags it by exactly one tick, so the gate fires on the
 * first tick after convergence plus one. Nothing here waits on an event that may not come.
 *
 * <p>The decision table has no clock or randomness. Client-shaped facts — throttle, consumption, hands, entities —
 * arrive as values, while the one deliberately impure operation is isolated behind {@link LiveSimulator}. Tests can
 * inject its result; production injects Vanilla's real live simulation.
 */
public final class PlaceGate {

    private PlaceGate() {
    }

    /**
     * The deliberately impure seam in the otherwise value-only gate.
     *
     * <p>The planner may use a pure geometric oracle while searching thousands of candidates. The final live gate
     * must instead ask Vanilla's real item-placement path with the live player, ray and rotation. Making that operation
     * explicit keeps the decision table headless-testable without ever making the planner's approximation the
     * production authority.
     */
    @FunctionalInterface
    public interface LiveSimulator {

        /** The state Vanilla would place for this exact live click, or {@code null} when Vanilla refuses it. */
        BlockState simulate(PlacementSolution plan, Live live);
    }

    /** What the caller should do about a verdict. The precedence the executor needs, attached to the verdict itself
     *  rather than re-derived at the call site, because "which of these means give up" is exactly the judgement two
     *  consumers would eventually disagree about. */
    public enum Response {

        /** Arm the expected placement and force {@code CLICK_RIGHT}, in this same tick. */
        FIRE,

        /** Nothing is wrong; the aim, the cooldown, the mob or the player's own hands need another tick. The caller
         *  spends patience and re-asks. */
        WAIT,

        /** The hotbar is not what the plan assumed. Back to EQUIP; the click would be voided silently otherwise. */
        RE_EQUIP,

        /** The live world contradicts the plan. Re-plan; do not click, do not wait. */
        DIVERGE
    }

    /** Why the click did or did not go out, in the exact vocabulary the trace and the log use. */
    public enum Verdict {

        /** Everything holds. */
        FIRE(Response.FIRE),

        /** The crosshair is on nothing — mid-turn, or looking past the world. */
        NO_HIT(Response.WAIT),

        /** The crosshair is on a different block than the plan's click neighbour. */
        WRONG_BLOCK(Response.WAIT),

        /** Right block, wrong face. */
        WRONG_FACE(Response.WAIT),

        /** Right block and face, but the cell that opens onto is not the plan's cell — a plan whose
         *  {@code against}/{@code face}/{@code cell} disagree, which would be accepted by the world and rejected by
         *  {@code ExpectedPlacement} for reasons nobody could read off the trace. */
        WRONG_CELL(Response.DIVERGE),

        /** A different hotbar slot is selected than the plan's. */
        WRONG_SLOT(Response.RE_EQUIP),

        /** The right slot holds a different item than the plan's. */
        WRONG_ITEM(Response.RE_EQUIP),

        /** The player is mid-swing or otherwise hands-busy; {@code BlockPlaceHelper} would drop the click. */
        HANDS_BUSY(Response.WAIT),

        /** {@code SurvivalBehavior.isConsuming()} — eating or mending. Both helpers are ticked with {@code false}
         *  while it is true, which does not merely skip the click, it destroys the commit. */
        CONSUMING(Response.WAIT),

        /** An entity stands in the target cell. Vanilla refuses the placement; a mob may also walk away. */
        ENTITY_IN_CELL(Response.WAIT),

        /** The target cell is no longer replaceable — something got there first. */
        CELL_OCCUPIED(Response.DIVERGE),

        /** The live ray would land a state the plan did not prove, or none at all. */
        WRONG_RESULT(Response.DIVERGE),

        /** The place cooldown is still running; this click would be discarded in silence. */
        THROTTLED(Response.WAIT);

        private final Response response;

        Verdict(Response response) {
            this.response = response;
        }

        public Response response() {
            return this.response;
        }

        public boolean fires() {
            return this.response == Response.FIRE;
        }
    }

    /**
     * Everything the gate needs about this tick that it may not read for itself.
     *
     * <p>The client-shaped facts are booleans rather than a {@code ctx} handle on purpose: it is what keeps this
     * class pure, and purity here is not tidiness — the gate is the single decision that turns a proven plan into an
     * irreversible click, and it is the only part of the executor that can be exercised without a running game.
     *
     * @param hitPos       block the live crosshair rests on, or null for no block hit
     * @param hitFace      face of that block the ray entered through; null with {@code hitPos}
     * @param hitLocation  exact world point of the hit — what vanilla derives slab halves and stair halves from
     * @param rotation     the LIVE rotation, i.e. the one the ray was cast from and the one the click will carry
     * @param held         the LIVE main-hand stack
     * @param selectedSlot the LIVE selected hotbar slot
     * @param sneaking     the LIVE crouch state; chests branch on it and so does the family resolution
     * @param throttled    {@code BlockPlaceHelper.isThrottled()}
     * @param consuming    {@code SurvivalBehavior.isConsuming()}
     * @param handsBusy    {@code player.isHandsBusy()}
     * @param entityClear  no entity intersects the target cell's collision shape
     */
    public record Live(
            BlockPos hitPos,
            Direction hitFace,
            Vec3 hitLocation,
            Rotation rotation,
            ItemStack held,
            int selectedSlot,
            boolean sneaking,
            boolean throttled,
            boolean consuming,
            boolean handsBusy,
            boolean entityClear,
            BlockHitResult rawHit
    ) {

        /**
         * Compatibility constructor for pure callers. Production supplies the original {@link BlockHitResult}; tests
         * that only own values get an equivalent ordinary block hit.
         */
        public Live(BlockPos hitPos, Direction hitFace, Vec3 hitLocation, Rotation rotation, ItemStack held,
                    int selectedSlot, boolean sneaking, boolean throttled, boolean consuming, boolean handsBusy,
                    boolean entityClear) {
            this(hitPos, hitFace, hitLocation, rotation, held, selectedSlot, sneaking, throttled, consuming, handsBusy,
                    entityClear, hitPos == null || hitFace == null || hitLocation == null
                            ? null : new BlockHitResult(hitLocation, hitFace, hitPos, false));
        }
    }

    /**
     * The gate.
     *
     * <p>Order matters and is not an optimisation. The identity checks come first because they are the ones that
     * distinguish "still turning" from "the world moved", and reporting a stale ray as WRONG_RESULT would turn every
     * mid-turn tick into a re-plan. The throttle comes LAST although it is the cheapest, because a verdict of
     * THROTTLED then also carries the information that everything else was ready — which is the difference between a
     * trace that says "waiting on the cooldown" and one that says "waiting, cause unknown".
     *
     * @param handSlot the slot the plan says this click comes out of, from {@link BuildAction#handSlot} and NOT from
     *                 the solution: a {@link PlacementSolution} names the ITEM, because the oracle proves a cell
     *                 placeable whenever the material exists anywhere in the 36-slot inventory, and the hotbar slot
     *                 is {@link HotbarSchedule#belady}'s answer written in by {@link HotbarSchedule#weave}
     * @param world a capture of the LIVE world around the cell, at least one cell of context in every direction so
     *              the neighbour-sensitive families (chest pairing, wall-versus-standing, door hinge) resolve the way
     *              they will actually resolve
     */
    public static Verdict evaluate(PlacementSolution plan, int handSlot, Live live, PlacementOracle oracle,
                                   PredictedWorld world) {
        return evaluate(plan, handSlot, live, oracle.settings(), world,
                (solution, actual) -> oracle.predict(world, solution.cell(), solution.desired(), actual.held(),
                        actual.hitPos(), actual.hitFace(), actual.hitLocation(), actual.rotation()));
    }

    /**
     * Production overload. Its caller must inject Vanilla's live item-placement simulation; no oracle fallback exists
     * on this path.
     */
    public static Verdict evaluate(PlacementSolution plan, int handSlot, Live live, V3Settings settings,
                                   PredictedWorld world, LiveSimulator simulator) {
        return evaluate(plan, handSlot, live, settings, world.get(plan.cell()), simulator);
    }

    /**
     * Allocation-free production gate. Only the live target state is needed here; Vanilla's injected simulator reads
     * whatever neighbour context the item actually needs from the real level.
     */
    public static Verdict evaluate(PlacementSolution plan, int handSlot, Live live, V3Settings settings,
                                   BlockState liveTarget, LiveSimulator simulator) {
        if (live.hitPos() == null || live.hitFace() == null || live.hitLocation() == null) {
            return Verdict.NO_HIT;
        }
        if (!live.hitPos().equals(plan.against())) {
            return Verdict.WRONG_BLOCK;
        }
        if (live.hitFace() != plan.face()) {
            return Verdict.WRONG_FACE;
        }
        // Redundant given the two checks above for any self-consistent plan, and kept anyway: it is the third clause
        // of ExpectedPlacement.matches, and a plan that violates it produces a click the helper discards without a
        // word. Catching it here names the planner bug instead of hiding it as an unexplained stall.
        if (!live.hitPos().relative(live.hitFace()).equals(plan.cell())) {
            return Verdict.WRONG_CELL;
        }
        if (live.selectedSlot() != handSlot) {
            return Verdict.WRONG_SLOT;
        }
        if (live.held() == null || live.held().isEmpty() || live.held().getItem() != plan.item()) {
            return Verdict.WRONG_ITEM;
        }
        if (live.handsBusy()) {
            return Verdict.HANDS_BUSY;
        }
        if (live.consuming()) {
            return Verdict.CONSUMING;
        }
        if (!live.entityClear()) {
            return Verdict.ENTITY_IN_CELL;
        }
        // Asked separately from the simulation below although the simulation would also refuse it, because the two
        // mean entirely different things: an occupied cell is the world having moved on, and a refused simulation is
        // this ray being wrong. Merging them would report "wrong result" for a cell somebody else already filled.
        if (!liveTarget.canBeReplaced()) {
            return Verdict.CELL_OCCUPIED;
        }
        BlockState landed = simulator.simulate(plan, live);
        if (landed == null) {
            return Verdict.WRONG_RESULT;
        }
        // Accepted is not enough. The plan chose this stance, this approach and this aim point because they produced
        // THIS state with the largest dominance margin available; a click that would land some other accepted state
        // is a click the plan never proved, and letting it through is how a piston family ends up half reversed.
        if (!settings.sameBlockstate(landed, plan.predicted())
                || !(settings.valid(landed, plan.desired(), true)
                        || PlacementGeometry.interactionClicks(landed, plan.desired()) >= 0)) {
            return Verdict.WRONG_RESULT;
        }
        if (live.throttled()) {
            return Verdict.THROTTLED;
        }
        return Verdict.FIRE;
    }
}
