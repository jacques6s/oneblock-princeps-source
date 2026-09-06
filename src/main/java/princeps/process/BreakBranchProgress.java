/* This file is part of Princeps, distributed under the GNU LGPL v3 or later. */
package princeps.process;

/** Break-branch accounting, separated from look requests and the placement-only trace census. */
final class BreakBranchProgress {
    private final int limit;
    private final int yieldTicks;
    private long activeTick;
    private long yieldUntil;
    private long lastEvaluatedTick = Long.MIN_VALUE;
    private int nonProgressTicks;
    private boolean startedYield;
    private boolean active;
    private Object damageTarget;
    private float lastDamage = Float.NaN;

    BreakBranchProgress(int limit, int yieldTicks) {
        if (limit < 1 || yieldTicks < 1) throw new IllegalArgumentException("positive break-branch limits required");
        this.limit = limit;
        this.yieldTicks = yieldTicks;
    }

    void beginTick(boolean paused, boolean inventoryBorrowed, boolean consuming) {
        startedYield = false;
        active = !paused && !inventoryBorrowed && !consuming;
        if (active) activeTick++;
    }

    boolean shouldYield(boolean claimsBranch, Object target, float controllerDamage) {
        if (!active || lastEvaluatedTick == activeTick) {
            return claimsBranch && activeTick < yieldUntil;
        }
        if (lastEvaluatedTick != activeTick - 1) {
            // Another branch received a working tick: mining was not starving it during that gap.
            nonProgressTicks = 0;
            forgetDamage();
        }
        lastEvaluatedTick = activeTick;
        if (!claimsBranch) {
            nonProgressTicks = 0;
            forgetDamage();
            return false;
        }
        if (activeTick < yieldUntil) {
            // This turn belongs to placement/recovery. It cannot count toward the next mining yield.
            forgetDamage();
            return true;
        }
        boolean validDamage = target != null && Float.isFinite(controllerDamage)
                && controllerDamage >= 0.0F && controllerDamage <= 1.0F;
        boolean advancing = validDamage && target.equals(damageTarget) && Float.isFinite(lastDamage)
                && controllerDamage > lastDamage;
        damageTarget = validDamage ? target : null;
        lastDamage = validDamage ? controllerDamage : Float.NaN;
        // Damage protects a genuinely active slow block, but does not forgive earlier failures. Target switches
        // and repeated damage restarts therefore still spend the budget unless a block actually changes.
        if (!advancing) nonProgressTicks++;
        if (nonProgressTicks > limit) {
            nonProgressTicks = 0;
            yieldUntil = activeTick + yieldTicks;
            startedYield = true;
            forgetDamage();
        }
        return claimsBranch && activeTick < yieldUntil;
    }

    void observedProgress() {
        nonProgressTicks = 0;
        yieldUntil = 0;
        startedYield = false;
        forgetDamage();
    }

    private void forgetDamage() {
        damageTarget = null;
        lastDamage = Float.NaN;
    }

    int nonProgressTicks() { return nonProgressTicks; }
    int yieldRemaining() { return (int) Math.max(0, yieldUntil - activeTick); }
    boolean startedYield() { return startedYield; }
    long activeTick() { return activeTick; }

    void clear() {
        activeTick = 0;
        yieldUntil = 0;
        nonProgressTicks = 0;
        startedYield = false;
        active = false;
        lastEvaluatedTick = Long.MIN_VALUE;
        forgetDamage();
    }
}
