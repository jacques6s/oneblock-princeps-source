package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import princeps.api.utils.BetterBlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Excavation integrity does not depend on the width of the mining tool or on a snake head. */
final class ExcavationRepairPolicy {
    enum Kind { INTERNAL_SOURCE, SHELL_SOURCE, SHELL_GAP, BRIDGE }

    record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                  int bandFloor, int bandTop, boolean ordinary) {
        boolean inside(int x, int y, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ
                    && y >= minY && y <= maxY;
        }

        boolean shell(int x, int y, int z) {
            return BuilderProcess.snakeShellCoordinate(x, y, z, minX, maxX, minZ, maxZ,
                    bandFloor, bandTop, maxY) || floor(x, y, z);
        }

        boolean floor(int x, int y, int z) {
            return ordinary && bandFloor == minY && y == minY - 1
                    && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        BetterBlockPos approach(BetterBlockPos feet, BlockPos repair) {
            // Repair navigation is a level, in-volume walk. Vertical entry stays with ordinary clearing.
            if (feet.x < minX || feet.x > maxX || feet.z < minZ || feet.z > maxZ
                    || feet.y < bandFloor || feet.y > bandTop + 1) {
                return null;
            }
            return new BetterBlockPos(Math.clamp(repair.getX(), minX, maxX), feet.y,
                    Math.clamp(repair.getZ(), minZ, maxZ));
        }
    }

    record Target(BetterBlockPos pos, Kind kind) { }
    record Census(List<Target> repairs, int flowing, int solid) {
        boolean clean() { return repairs.isEmpty() && flowing == 0 && solid == 0; }
    }

    static boolean navigationMayMine(boolean ordinaryExcavation, BlockState desired) {
        return !ordinaryExcavation || (desired != null && desired.isAir());
    }

    static boolean ordinaryBreakAllowed(boolean ordinaryExcavation, boolean activeAirCell, boolean areaToolHeld) {
        return !ordinaryExcavation || (activeAirCell && !areaToolHeld);
    }

    /** One already-clear cardinal step through water. Mining, vertical entry and dry travel keep their own paths. */
    static BetterBlockPos ordinaryWetStep(Bounds bounds, BetterBlockPos feet, BlockPos work,
                                          Function<BlockPos, BlockState> read) {
        if (!bounds.ordinary() || !bounds.inside(feet.x, feet.y, feet.z)
                || feet.y < bounds.bandFloor() || feet.y > bounds.bandTop()
                || !bounds.inside(work.getX(), work.getY(), work.getZ())
                || work.getY() < bounds.bandFloor() || work.getY() > bounds.bandTop()) return null;
        BetterBlockPos approach = bounds.approach(feet, work);
        if (approach == null || approach.equals(feet)) return null;
        BetterBlockPos step = BuilderProcess.nextSnakeWaypoint(feet, approach);
        boolean water = false;
        for (BlockPos body : new BlockPos[] {feet, feet.above(), step, step.above()}) {
            BlockState state = read.apply(body);
            if (state.isAir()) continue;
            if (!(state.getBlock() instanceof LiquidBlock) || !state.getFluidState().is(FluidTags.WATER)) return null;
            water = true;
        }
        return water ? step : null;
    }

    static Kind repair(Bounds bounds, int x, int y, int z, BlockState state, boolean replaceable,
                       boolean excavation) {
        boolean sourceBand = !bounds.ordinary() || (y >= bounds.bandFloor() && y <= bounds.bandTop());
        if (sourceBand && bounds.inside(x, y, z) && BuilderProcess.snakeSourceReadyForPlug(state, excavation)) {
            return Kind.INTERNAL_SOURCE;
        }
        // Filling AIR directly above a one-high surface would remove the only standing headroom. This is based on
        // the complete selection height, never on the final one-high band of a taller excavation. Fluids still seal.
        if (excavation && ShallowExcavationPolicy.keepRoofAir(bounds, x, y, z, state)) return null;
        if (bounds.shell(x, y, z) && BuilderProcess.snakeShellRequiresSeal(state, replaceable)) {
            return bounds.floor(x, y, z) ? Kind.BRIDGE
                    : state.getFluidState().isSource() ? Kind.SHELL_SOURCE : Kind.SHELL_GAP;
        }
        // Interior flowing water/lava is not plugged repeatedly. Seal its boundary input, then let it drain.
        return null;
    }

    static Census inspect(Bounds bounds, Function<BlockPos, BlockState> read, Predicate<BlockPos> replaceable) {
        List<Target> repairs = new ArrayList<>();
        int flowing = 0, solid = 0;
        for (int x = bounds.minX() - 1; x <= bounds.maxX() + 1; x++) {
            for (int y = bounds.bandFloor() - 1; y <= bounds.bandTop() + 1; y++) {
                for (int z = bounds.minZ() - 1; z <= bounds.maxZ() + 1; z++) {
                    boolean inside = bounds.inside(x, y, z)
                            && y >= bounds.bandFloor() && y <= bounds.bandTop();
                    if (!inside && !bounds.shell(x, y, z)) continue;
                    BetterBlockPos pos = new BetterBlockPos(x, y, z);
                    BlockState state = read.apply(pos);
                    Kind kind = repair(bounds, x, y, z, state, replaceable.test(pos), true);
                    if (kind != null) repairs.add(new Target(pos, kind));
                    if (inside && !state.isAir()) {
                        if (BuilderProcess.snakeTreatAsFluid(state, true)) flowing++;
                        else solid++;
                    }
                }
            }
        }
        return new Census(List.copyOf(repairs), flowing, solid);
    }
}
