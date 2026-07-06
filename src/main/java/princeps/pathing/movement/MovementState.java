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

package princeps.pathing.movement;

import princeps.api.pathing.movement.MovementStatus;
import princeps.api.utils.Rotation;
import princeps.api.utils.input.Input;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MovementState {

    private MovementStatus status;
    private MovementTarget target = new MovementTarget();
    private final Map<Input, Boolean> inputState = new HashMap<>();

    public MovementState setStatus(MovementStatus status) {
        this.status = status;
        return this;
    }

    public MovementStatus getStatus() {
        return status;
    }

    public MovementTarget getTarget() {
        return this.target;
    }

    public MovementState setTarget(MovementTarget target) {
        this.target = target;
        return this;
    }

    public MovementState setInput(Input input, boolean forced) {
        this.inputState.put(input, forced);
        return this;
    }

    public Map<Input, Boolean> getInputStates() {
        return this.inputState;
    }

    public static class MovementTarget {

        /**
         * Yaw and pitch angles that must be matched
         */
        public Rotation rotation;

        /**
         * Whether or not this target must force rotations.
         * <p>
         * {@code true} if we're trying to place or break blocks, {@code false} if we're trying to look at the movement location
         */
        private boolean forceRotations;

        /**
         * {@code true} when this forced rotation aims at a block we intend to BREAK (not place). Carried through to
         * LookBehavior so the bell-curve mining arc engages from the FIRST aim tick. The CLICK_LEFT input alone
         * cannot signal this: every break site correctly gates the press on the crosshair having ARRIVED, so
         * deriving the arc from the live input is circular (curve waits for the click, the click waits for the
         * arrival — the aim would snap exactly like the flick the curve exists to remove).
         */
        private boolean breakIntent;

        public MovementTarget() {
            this(null, false);
        }

        public MovementTarget(Rotation rotation, boolean forceRotations) {
            this(rotation, forceRotations, false);
        }

        public MovementTarget(Rotation rotation, boolean forceRotations, boolean breakIntent) {
            this.rotation = rotation;
            this.forceRotations = forceRotations;
            this.breakIntent = breakIntent;
        }

        public final Optional<Rotation> getRotation() {
            return Optional.ofNullable(this.rotation);
        }

        public boolean hasToForceRotations() {
            return this.forceRotations;
        }

        public boolean isBreakIntent() {
            return this.breakIntent;
        }
    }
}
