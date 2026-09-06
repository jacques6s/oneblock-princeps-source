/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Per-excavation ownership of temporary source plugs, never a rule about every cobblestone block. */
final class ExcavationFluidPlugs {
    private record Plug(BlockState before, BlockState placed, Fluid fluid, boolean confirmed, long requestedTick) { }
    record Hazard(BlockPos plug, List<BlockPos> sources) { }
    private final ConcurrentHashMap<Long, Plug> plugs = new ConcurrentHashMap<>();
    private Hazard waitingFor;
    private long waitingSince;
    private long improvement;
    private long waitingImprovement;
    private Object route;
    private int routePosition;
    private final Set<Long> reachedRouteCells = new HashSet<>();

    /** Called only for the current excavation's explicitly scoped, in-selection integrity placement. */
    void record(BlockPos pos, BlockState before, BlockState after) {
        record(pos, before, after, 0);
    }

    void record(BlockPos pos, BlockState before, BlockState after, long tick) {
        if (!BuilderProcess.snakeSourceReadyForPlug(before, true) || after.isAir()
                || !after.getFluidState().isEmpty()) return;
        // A retry must not turn a confirmed plug back into a fresh request or reset its progress clock.
        plugs.compute(pos.asLong(), (key, previous) -> previous != null && previous.placed().equals(after)
                ? previous : new Plug(before, after, before.getFluidState().getType(), false, tick));
    }

    void observe(BlockPos pos, BlockState current) {
        Plug expected = plugs.get(pos.asLong());
        if (expected == null) return;
        if (expected.placed().equals(current)) {
            if (!expected.confirmed() && plugs.replace(pos.asLong(), expected,
                    new Plug(expected.before(), expected.placed(), expected.fluid(), true, expected.requestedTick()))) progress();
        } else if (expected.confirmed() || !expected.before().equals(current)) {
            plugs.remove(pos.asLong(), expected);
        }
    }

    void clear() { plugs.clear(); waitingFor = null; resetRoute(); }
    int size() { return plugs.size(); }

    Optional<BlockPos> unconfirmedBefore(long oldestTick) {
        return plugs.entrySet().stream().filter(entry -> !entry.getValue().confirmed()
                && entry.getValue().requestedTick() < oldestTick).map(entry -> BlockPos.of(entry.getKey())).findFirst();
    }

    /** A* consumes its own ownership snapshot together with its copied chunk provider. */
    ExcavationFluidPlugs snapshot() {
        ExcavationFluidPlugs snapshot = new ExcavationFluidPlugs();
        snapshot.plugs.putAll(plugs);
        return snapshot;
    }

    /** A confirmed new seal or a newly reached route node, never a click or repeated acknowledgement. */
    void progress() { improvement++; }

    /** A new path alone is not progress; an advanced node must also reach a cell not already credited. */
    void routeProgress(Object currentRoute, int position, BlockPos feet) {
        if (currentRoute == null) return;
        if (route != currentRoute) {
            route = currentRoute;
            routePosition = position;
            reachedRouteCells.add(feet.asLong());
        } else if (position > routePosition) {
            routePosition = position;
            if (reachedRouteCells.add(feet.asLong())) progress();
        }
    }

    private void resetRoute() { route = null; reachedRouteCells.clear(); }

    boolean waitExpired(Hazard hazard, long tick) {
        if (hazard == null) { waitingFor = null; resetRoute(); return false; }
        // Merely switching between blocked plugs, or drifting to a different first scan candidate, is not
        // improvement. Preserve both the clock and already-credited route cells throughout the blocked episode.
        if (waitingFor == null || waitingImprovement != improvement) {
            waitingSince = tick;
            waitingImprovement = improvement;
        }
        waitingFor = hazard;
        return tick - waitingSince >= 200;
    }

    /** Remote clients do not receive these gamerules. This names the vanilla defaults, not server knowledge. */
    static boolean vanillaSourceConversion(Fluid fluid) {
        return fluid.isSame(Fluids.WATER) ? GameRules.WATER_SOURCE_CONVERSION.defaultValue()
                : fluid.isSame(Fluids.LAVA) && GameRules.LAVA_SOURCE_CONVERSION.defaultValue();
    }

    static List<BlockPos> footprint(BlockPos target, Direction face, boolean areaTool) {
        if (!areaTool) return List.of(target.immutable());
        if (face == null) return List.of();
        List<BlockPos> cells = new ArrayList<>(9);
        for (int first = -1; first <= 1; first++) {
            for (int second = -1; second <= 1; second++) {
                cells.add(switch (face.getAxis()) {
                    case X -> target.offset(0, first, second).immutable();
                    case Y -> target.offset(first, 0, second).immutable();
                    case Z -> target.offset(first, second, 0).immutable();
                });
            }
        }
        return List.copyOf(cells);
    }

    Optional<Hazard> firstHazard(Collection<BlockPos> cut, BlockGetter world, Predicate<Fluid> converts) {
        if (plugs.isEmpty()) return Optional.empty();
        Set<Long> removed = new HashSet<>();
        cut.forEach(pos -> removed.add(pos.asLong()));
        for (BlockPos pos : cut) {
            Plug plug = plugs.get(pos.asLong());
            if (plug == null || !plug.placed().equals(world.getBlockState(pos)) || !converts.test(plug.fluid())) continue;
            BlockState below = afterCut(pos.below(), removed, world);
            // A deeper source is only support for horizontal regeneration. It is not an obligation to drain a
            // future band first, and a Shard cut that removes the solid support can make this cut safe itself.
            if (!below.isSolid() && !(below.getFluidState().isSource()
                    && plug.fluid().isSame(below.getFluidState().getType()))) continue;
            List<BlockPos> sources = new ArrayList<>(4);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = pos.relative(direction);
                BlockState state = afterCut(neighbour, removed, world);
                if (state.getFluidState().isSource() && plug.fluid().isSame(state.getFluidState().getType())
                        && !Shapes.mergedFaceOccludes(Shapes.empty(), state.getCollisionShape(world, neighbour), direction)) {
                    sources.add(neighbour.immutable());
                }
            }
            if (sources.size() >= 2) return Optional.of(new Hazard(pos.immutable(), List.copyOf(sources)));
        }
        return Optional.empty();
    }

    private static BlockState afterCut(BlockPos pos, Set<Long> removed, BlockGetter world) {
        BlockState current = world.getBlockState(pos);
        // Fluids are not mined. A mined waterlogged block exposes its source; it does not become dry AIR.
        return removed.contains(pos.asLong()) && !BuilderProcess.snakeTreatAsFluid(current, true)
                ? current.getFluidState().createLegacyBlock() : current;
    }
}
