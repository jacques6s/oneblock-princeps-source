package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-build ownership, separate from schematic bounds and from block material alone. */
final class BuilderScaffoldLedger {
    private final ConcurrentHashMap<Long, Block> placed = new ConcurrentHashMap<>();
    private record Request(Block block, long tick) {}
    private final ConcurrentHashMap<Long, Request> pending = new ConcurrentHashMap<>();

    boolean record(BlockPos pos, BlockState before, BlockState after,
                   boolean templateTarget, boolean throwaway, long tick) {
        if (!before.isAir() || after.isAir() || templateTarget || !throwaway) {
            return false;
        }
        pending.put(pos.asLong(), new Request(after.getBlock(), tick));
        return true;
    }

    boolean serverChanged(BlockPos pos, BlockState state) {
        Request request = pending.remove(pos.asLong());
        if (request != null && request.block() == state.getBlock()) {
            placed.put(pos.asLong(), request.block());
            return true;
        }
        observe(pos, state);
        return false;
    }

    boolean awaitingServer() {
        return !pending.isEmpty();
    }

    Set<BlockPos> unconfirmedBefore(long oldestAllowedTick) {
        return pending.entrySet().stream().filter(entry -> entry.getValue().tick() < oldestAllowedTick)
                .map(entry -> BlockPos.of(entry.getKey())).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    boolean contains(BlockPos pos) {
        return placed.containsKey(pos.asLong());
    }

    boolean owns(BlockPos pos, BlockState current) {
        return placed.get(pos.asLong()) == current.getBlock();
    }

    private void observe(BlockPos pos, BlockState current) {
        Block expected = placed.get(pos.asLong());
        if (expected != null && expected != current.getBlock()) {
            placed.remove(pos.asLong(), expected);
        }
    }

    Set<BlockPos> positions() {
        return placed.keySet().stream().map(BlockPos::of).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    void clear() {
        placed.clear();
        pending.clear();
    }
}
