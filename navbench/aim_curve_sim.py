"""
navbench — bell-curve mining-aim prototype ("beim block abbau flickt er — soll smooth sein").

The precise BREAK aim today either snaps instantly (capBreakTurn off: the flick) or jumps straight to a constant
9 deg/tick (capBreakTurn on: proportionalStep has no ease-IN — velocity goes 0 -> 9 in one tick, a visible kick).
The user wants a human acceleration BELL: ease-in (accelerate), peak, ease-out (decelerate into the target), with
3 selectable modes for the peak head speed — superSmooth 5, standard 9, fast 20 deg/tick — plus a tiny random
per-arc variance (0.01%..0.1%) so no two arcs are numerically identical.

This sim validates the EXACT math that ships in LookBehavior.aimCurveNextVel:
    rise = min(vPrev + peak/3, peak)          # ease-in: 0 -> peak in ~3 ticks (fast rise)
    tail = max(max(0.9, peak/8), err * 0.45)  # ease-out: proportional tail with a floor (no end-crawl)
    v    = min(rise, tail)
and measures, per mode and error size:
  - peak velocity  (must NEVER exceed the mode's ceiling)
  - peak ACCELERATION (the jerk the user sees as "flick" — the whole point: old prop jumps 9/tick^2, bell = peak/3)
  - bell shape     (velocity rises monotonically, then falls monotonically — no oscillation)
  - arrival        (ticks to reach the target, vs the old constant-rate arc = efficiency cost)
  - variance band  (with the 0.01..0.1% amplitude, arcs differ but never breach the ceiling)
"""
import random

def curve_next_vel(v_prev, err, peak):
    peak = max(0.5, peak)
    accel = max(0.5, peak / 3.0)
    gain = 0.45
    tail_min = max(0.9, peak / 8.0)
    rise = min(v_prev + accel, peak)
    tail = max(tail_min, err * gain)
    return min(rise, tail)

def run_arc(err0, peak, var_amp=0.0, rng=None, max_ticks=400):
    """simulate one aim arc; returns (steps, vels)"""
    err = err0; v = 0.0; steps = []; vels = []
    for _ in range(max_ticks):
        if err <= 1e-3:
            break
        v = curve_next_vel(v, err, peak)
        if var_amp and rng is not None:
            v *= 1.0 + (rng.random() * 2.0 - 1.0) * var_amp
            v = min(v, peak)                    # ceiling holds even with variance
        step = min(err, v)
        steps.append(step); vels.append(v)
        err -= step
    return steps, vels

def old_proportional(err0, max_step=9.0, gain=0.55, min_step=8.0, max_ticks=400):
    """the deployed capBreakTurn arc: proportionalStep clamped to the 9-deg cruise cap (NO ease-in)."""
    err = err0; steps = []
    for _ in range(max_ticks):
        if err <= 1e-3:
            break
        step = min(err, max(min_step, min(err * gain, max_step)))
        step = min(step, 9.0)
        steps.append(step)
        err -= step
    return steps

def bell_shape_ok(vels):
    """velocity must rise monotonically to its max, then never rise again (plateau allowed both sides)."""
    if not vels:
        return True
    peak_i = vels.index(max(vels))
    rising = all(vels[i+1] >= vels[i] - 1e-9 for i in range(peak_i))
    falling = all(vels[i+1] <= vels[i] + 1e-9 for i in range(peak_i, len(vels)-1))
    return rising and falling

def main():
    MODES = [("superSmooth", 5.0), ("standard", 9.0), ("fast", 20.0)]
    ERRS = [10.0, 25.0, 45.0, 90.0, 130.0, 179.0]
    rng = random.Random(11)

    print("== bell-curve mining aim: per-mode envelope (no variance) ==")
    print(f"{'mode':12s} {'err':>5s} {'ticks':>5s} {'vMax':>6s} {'aMax':>6s} {'bell':>5s}   (aMax = peak accel deg/tick^2; old prop kick = 9.0, old exact snap = err)")
    all_ok = True
    for name, peak in MODES:
        for err in ERRS:
            steps, vels = run_arc(err, peak)
            accs = [vels[0]] + [vels[i+1] - vels[i] for i in range(len(vels)-1)]
            v_max = max(vels); a_max = max(accs)
            ok = v_max <= peak + 1e-9 and bell_shape_ok(vels)
            all_ok &= ok
            print(f"{name:12s} {err:5.0f} {len(steps):5d} {v_max:6.2f} {a_max:6.2f} {str(ok):>5s}")
    print()

    print("== flick comparison, 90-deg re-aim (standard mode) ==")
    steps_new, vels_new = run_arc(90.0, 9.0)
    steps_old = old_proportional(90.0)
    a_new = max([vels_new[0]] + [vels_new[i+1]-vels_new[i] for i in range(len(vels_new)-1)])
    print(f"  old exact snap    : 1 tick,  step0=90.0  (accel 90.0)  <- the reported flick (capBreakTurn off)")
    print(f"  old proportional  : {len(steps_old)} ticks, step0={steps_old[0]:.1f}   (accel {steps_old[0]:.1f})   <- 0->9 kick (capBreakTurn on)")
    print(f"  new bell curve    : {len(steps_new)} ticks, step0={steps_new[0]:.1f}   (accel {a_new:.1f})   <- ease-in, peak accel {a_new:.1f}")
    print(f"  velocity profile  : {' '.join(f'{v:.1f}' for v in vels_new)}")
    print(f"  accel improvement : {steps_old[0]/a_new:.1f}x lower peak acceleration; arrival +{len(steps_new)-len(steps_old)} ticks")
    print()

    print("== variance band: 400 arcs @ 90 deg, standard, amp per-arc in [0.01%,0.1%] ==")
    v_ceil_breaches = 0; tick_counts = set(); vmaxes = []
    for _ in range(400):
        amp = 1.0e-4 + rng.random() * 9.0e-4
        steps, vels = run_arc(90.0, 9.0, var_amp=amp, rng=rng)
        if max(vels) > 9.0 + 1e-9:
            v_ceil_breaches += 1
        tick_counts.add(len(steps)); vmaxes.append(max(vels))
    print(f"  ceiling breaches: {v_ceil_breaches} (MUST be 0) | vMax range: {min(vmaxes):.4f}..{max(vmaxes):.4f} | arc lengths seen: {sorted(tick_counts)}")
    print()

    print("== hold-on-target: tremor-sized corrections (<=0.5 deg) while mining must stay sub-degree ==")
    worst = 0.0
    for _ in range(2000):
        err = rng.random() * 0.5
        steps, vels = run_arc(err, 9.0)
        if steps:
            worst = max(worst, max(steps))
    print(f"  max single correction step: {worst:.3f} deg (must be << 1 deg; the crosshair never leaves the block face)")
    print()
    print("ALL-OK:", all_ok and v_ceil_breaches == 0)

if __name__ == '__main__':
    main()
