/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** One extra cleanup block. Only an ordered server observation can discharge its debt. */
final class BuilderCleanupDebt {
    enum State { REQUESTED, OWNED, REMOVED, REPLACED }

    final Object world;
    final Object episode;
    final BlockPos pos;
    final Block block;
    private final long afterInteraction, afterServerUpdate, requestedAt;
    private long lastServerUpdate;
    private volatile State state = State.REQUESTED; // execution-cost workers may only lose this published permission
    private boolean interactionObserved;
    private boolean serverPresent;

    BuilderCleanupDebt(Object world, Object episode, BlockPos pos, BlockState wanted,
                       long afterInteraction, long afterServerUpdate, long requestedAt) {
        if (world == null || episode == null || wanted == null || wanted.isAir()) throw new IllegalArgumentException("solid helper required");
        this.world = world;
        this.episode = episode;
        this.pos = pos.immutable();
        this.block = wanted.getBlock();
        this.afterInteraction = afterInteraction;
        this.afterServerUpdate = afterServerUpdate;
        this.lastServerUpdate = afterServerUpdate;
        this.requestedAt = requestedAt;
    }

    void serverChanged(Object observedWorld, BlockPos at, BlockState observed, long sequence, long observedAt) {
        if (world != observedWorld || !pos.equals(at) || sequence <= afterServerUpdate || sequence <= lastServerUpdate
                || observedAt < requestedAt || state == State.REPLACED || state == State.REMOVED) return;
        lastServerUpdate = sequence;
        if (observed.isAir()) {
            serverPresent = false;
            // An unchanged AIR update before placement is not removal of a block we ever owned.
            if (state == State.OWNED) state = State.REMOVED;
        } else if (observed.getBlock() == block) {
            serverPresent = true;
            if (interactionObserved) state = State.OWNED;
        } else {
            serverPresent = false;
            state = State.REPLACED;
        }
    }

    /** Client-controller execution is separate from server confirmation and cannot grant ownership by itself. */
    void interactionObserved(Object observedWorld, Object observedEpisode, long sequence, long observedAt) {
        if (world != observedWorld || episode != observedEpisode || sequence <= afterInteraction || observedAt < requestedAt) return;
        interactionObserved = true;
        if (state == State.REQUESTED && serverPresent) state = State.OWNED;
    }

    boolean mayRequest() { return state == State.REQUESTED && !interactionObserved && !serverPresent; }

    State state() { return state; }
    boolean discharged() { return state == State.REMOVED; }
    boolean mayMine(Object actualWorld, BlockPos at, BlockState actual) {
        return state == State.OWNED && world == actualWorld && pos.equals(at) && actual.getBlock() == block;
    }
}
