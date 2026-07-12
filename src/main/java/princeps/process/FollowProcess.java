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

import princeps.Princeps;
import princeps.api.pathing.goals.Goal;
import princeps.api.pathing.goals.GoalBlock;
import princeps.api.pathing.goals.GoalComposite;
import princeps.api.pathing.goals.GoalNear;
import princeps.api.pathing.goals.GoalXZ;
import princeps.api.process.IFollowProcess;
import princeps.api.process.PathingCommand;
import princeps.api.process.PathingCommandType;
import princeps.api.utils.BetterBlockPos;
import princeps.utils.PrincepsProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Follow an entity
 *
 * @author leijurv
 */
public final class FollowProcess extends PrincepsProcessHelper implements IFollowProcess {

    private Predicate<Entity> filter;
    private List<Entity> cache;
    private boolean into; // walk straight into the target, regardless of settings

    public FollowProcess(Princeps princeps) {
        super(princeps);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        scanWorld();
        Goal goal = new GoalComposite(cache.stream().map(this::towards).toArray(Goal[]::new));
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private Goal towards(Entity following) {
        BlockPos pos;
        if (Princeps.settings().followOffsetDistance.value == 0 || into) {
            pos = following.blockPosition();
        } else {
            GoalXZ g = GoalXZ.fromDirection(following.position(), Princeps.settings().followOffsetDirection.value, Princeps.settings().followOffsetDistance.value);
            pos = new BetterBlockPos(g.getX(), following.position().y, g.getZ());
        }
        if (into) {
            return new GoalBlock(pos);
        }
        return new GoalNear(pos, Princeps.settings().followRadius.value);
    }


    private boolean followable(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!entity.isAlive()) {
            return false;
        }
        if (entity.equals(ctx.player())) {
            return false;
        }
        int maxDist = Princeps.settings().followTargetMaxDistance.value;
        if (maxDist != 0 && entity.distanceToSqr(ctx.player()) > maxDist * maxDist) {
            return false;
        }
        // scanWorld already obtained this entity from ctx.entitiesStream(); searching the same stream
        // again here made dense worlds O(entityCount^2) without changing the answer.
        return true;
    }

    private void scanWorld() {
        cache = ctx.entitiesStream()
                .filter(this::followable)
                .filter(this.filter)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isActive() {
        if (filter == null) {
            return false;
        }
        scanWorld();
        return !cache.isEmpty();
    }

    @Override
    public void onLostControl() {
        filter = null;
        cache = null;
    }

    @Override
    public String displayName0() {
        return "Following " + cache;
    }

    @Override
    public void follow(Predicate<Entity> filter) {
        this.filter = filter;
        this.into = false;
    }

    @Override
    public void pickup(Predicate<ItemStack> filter) {
        this.filter = e -> e instanceof ItemEntity && filter.test(((ItemEntity) e).getItem());
        this.into = true;
    }

    @Override
    public List<Entity> following() {
        return cache;
    }

    @Override
    public Predicate<Entity> currentFilter() {
        return filter;
    }
}
