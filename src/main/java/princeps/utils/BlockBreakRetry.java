/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Bounded retries for predicted breaks which the server restores, scoped to the observed block and world. */
final class BlockBreakRetry {
    private static final long REPEAT_WINDOW_MS = 4000;
    private static final int MAX_RETRIES = 3;
    private final Map<Long, Rejection> rejected = new HashMap<>();
    private Object world;
    private long lastPosition = Long.MIN_VALUE;
    private BlockState lastState;
    private long lastTime;
    private int lastColumn;
    private int repeats;

    private record Rejection(BlockState state, int retries, long retryAt) { }

    void completed(Object currentWorld, BlockPos pos, BlockState before, int columnAbove, long now) {
        enterWorld(currentWorld);
        if (before == null || before.isAir()) return;
        long key = pos.asLong();
        boolean repeated = key == lastPosition && before.equals(lastState)
                && now - lastTime >= 0 && now - lastTime < REPEAT_WINDOW_MS;
        boolean fallingRefill = repeated && columnAbove < lastColumn;
        repeats = repeated && !fallingRefill ? repeats + 1 : 1;
        lastPosition = key;
        lastState = before;
        lastTime = now;
        lastColumn = columnAbove;
        if (repeats > 1) {
            Rejection prior = rejected.get(key);
            int retries = prior == null ? 0 : prior.retries() + 1;
            long delay = 5000L << Math.min(retries, MAX_RETRIES);
            rejected.put(key, new Rejection(before, retries, now + delay));
        }
    }

    boolean blocked(Object currentWorld, BlockPos pos, long now) {
        enterWorld(currentWorld);
        if (pos == null) return false;
        Rejection rejection = rejected.get(pos.asLong());
        return rejection != null && (rejection.retries() >= MAX_RETRIES || now < rejection.retryAt());
    }

    boolean rejected(Object currentWorld, BlockPos pos) {
        enterWorld(currentWorld);
        return pos != null && rejected.containsKey(pos.asLong());
    }

    String diagnosis(Object currentWorld, BlockPos pos, long now) {
        if (!blocked(currentWorld, pos, now)) return "none";
        Rejection rejection = rejected.get(pos.asLong());
        return rejection.retries() >= MAX_RETRIES ? "server-restored-block: retries exhausted"
                : "server-restored-block: retry " + (rejection.retries() + 1) + "/" + MAX_RETRIES
                    + " in " + Math.max(0, rejection.retryAt() - now) + "ms";
    }

    /** Only authoritative server changes retire a rejection; predicted local AIR is not a confirmation. */
    void serverChanged(Object currentWorld, BlockPos pos, BlockState state) {
        enterWorld(currentWorld);
        Rejection rejection = rejected.get(pos.asLong());
        if (rejection != null && !rejection.state().equals(state)) rejected.remove(pos.asLong());
        if (pos.asLong() == lastPosition && !state.equals(lastState)) resetLast();
    }

    void clear() {
        rejected.clear();
        resetLast();
        world = null;
    }

    private void enterWorld(Object currentWorld) {
        if (world != currentWorld) {
            clear();
            world = currentWorld;
        }
    }

    private void resetLast() {
        lastPosition = Long.MIN_VALUE;
        lastState = null;
        repeats = 0;
        lastTime = 0;
        lastColumn = 0;
    }
}
