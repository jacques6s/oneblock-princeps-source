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

package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import princeps.api.pathing.path.IPathExecutor;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;

import java.util.function.Function;

/** Finish an already licensed water step before a distant mining target borrows the movement tick. */
final class ExcavationMiningApproach {
    private ExcavationMiningApproach() { }

    static PathingCommand finishWetStep(boolean excavating, IPathExecutor route, BlockPos feet,
                                        BlockPos target, Function<BlockPos, BlockState> read) {
        if (!excavating || route == null || target == null || route.getPath() == null) return null;
        var path = route.getPath();
        var positions = path.positions();
        // This is the immutable single-step excavation route, not an inferred route towards the mining target.
        if (positions.size() != 2 || route.getPosition() != 0 || path.getGoal() == null) return null;
        BlockPos start = positions.get(0);
        BlockPos step = positions.get(1);
        if (!start.equals(feet) || start.getY() != step.getY()
                || Math.abs(start.getX() - step.getX()) + Math.abs(start.getZ() - step.getZ()) != 1) return null;
        var licence = route.wadeLicence();
        for (BlockPos body : new BlockPos[] {start, start.above(), step, step.above()}) {
            // An immediate body obstruction must still preempt movement and retain its ordinary-tool guards.
            if (body.equals(target) || !licence.permitsWading(body)) return null;
        }
        if (!ExcavationRepairPolicy.clearWaterCorridor(start, step, read)) return null;
        // Merely entering block reach is not arrival: stopping here lets the current push the player back out
        // during the sight delay or multi-tick break. Keep the same executor until its destination cell is reached.
        // No exact centering, new path, progress credit, or mining/placement permission is introduced.
        return new PathingCommand(path.getGoal(), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
}
