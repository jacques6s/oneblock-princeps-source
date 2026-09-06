/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

/** Counts only ticks on which excavation can act; interruptions neither age nor renew its deadlines. */
final class ExcavationActiveClock {
    private long activeTicks;

    long now() {
        return activeTicks;
    }

    void tick(boolean paused, boolean inventoryBorrowed, boolean consuming) {
        if (!paused && !inventoryBorrowed && !consuming) {
            activeTicks++;
        }
    }

    void clear() {
        activeTicks = 0;
    }
}
