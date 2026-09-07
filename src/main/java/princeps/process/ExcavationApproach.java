/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import princeps.api.pathing.calc.IPath;
import princeps.api.pathing.goals.Goal;
import princeps.pathing.movement.Movement;

/** The first journey to a dig entry, never a permission for subsequent excavation detours. */
final class ExcavationApproach {
    private boolean pending;
    private Goal entry;
    private Object token;

    void start() {
        clear();
        pending = true;
    }

    void clear() {
        pending = false;
        entry = null;
        token = null;
    }

    void suspend() {
        token = null;
    }

    void observeCommand(Object commandToken) {
        if (token != commandToken) suspend();
    }

    boolean pending() {
        return pending;
    }

    Object token() {
        return token;
    }

    Goal entry() {
        return entry;
    }

    void observe(BlockPos feet) {
        if (pending && entry != null && entry.isInGoal(feet)) clear();
    }

    Object commit(Goal requested, BlockPos feet) {
        if (!pending || requested == null) return null;
        if (entry == null) entry = requested;
        observe(feet);
        if (!pending) return null;
        if (token == null) token = new Object();
        return token;
    }

    boolean owns(Object commandToken, Object routeToken) {
        return pending && token != null && token == commandToken && token == routeToken;
    }

    /** The live movement's declared cut, excluding later steps and foreground blocks from blind aim fallbacks. */
    static boolean requiresBreak(IPath path, int position, BlockPos target) {
        if (path == null || target == null || position < 0 || position >= path.movements().size()) return false;
        if (!(path.movements().get(position) instanceof Movement movement)) return false;
        for (BlockPos required : movement.toBreakAll()) {
            if (target.equals(required)) return true;
        }
        return false;
    }
}
