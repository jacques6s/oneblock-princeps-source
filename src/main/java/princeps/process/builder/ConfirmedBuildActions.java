/* This file is part of Princeps, licensed under LGPL-3.0-or-later. */
package princeps.process.builder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/** A request is only progress when a later server update satisfies that still-outstanding request once. */
public final class ConfirmedBuildActions<S> {
    private final Map<Long, S> pending = new HashMap<>();
    private final BiPredicate<S, S> matches;

    public ConfirmedBuildActions(BiPredicate<S, S> matches) { this.matches = matches; }

    public void arm(long cell, S before, S expected) {
        if (expected != null && !matches.test(before, expected)) pending.putIfAbsent(cell, expected);
    }

    public boolean serverChanged(long cell, S state) {
        S expected = pending.get(cell);
        if (expected == null || !matches.test(state, expected)) return false;
        pending.remove(cell);
        return true;
    }

    public void clear() { pending.clear(); }
}
