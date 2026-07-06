"""
navbench — EXECUTOR-faithful simulation of smoothPath: the last "only live-testable" integration gap.

navsim.simulate proves the pursuit FOLLOWS a smoothed polyline; smoother.py proves the geometry is safe. What
neither models is PathExecutor's STATE MACHINE, which is exactly where a live integration bug would bite:

  - pathPosition advances ONLY when a movement reports SUCCESS, and SmoothTraverse.SUCCESS requires the feet
    block to EQUAL the chord dest cell — if the pursuit cut the corner of that 1x1 cell, the movement would
    never succeed, the steering window (anchored to pathPosition) would stall, and the movement would time out.
  - the positional forward-skip starts at pathPosition+3 ("don't check +1/+2"), useless with long chords.
  - the distance gate cancels when the player is >2 blocks from EVERY valid cell for >200 ticks (>3 = instant).
  - the timeout cancels a movement after cost + movementTimeoutTicks(100) ticks.

This sim replays navsim's exact steering/physics core per tick, but drives it through the executor semantics
above (same-tick onTick recursion included) over the random+structured terrain corpus. PASS = the bot reaches
the goal with zero cancels and zero timeouts on every map. Constants mirror PathExecutor.java:50-60 and
Settings.movementTimeoutTicks; costs use SPRINT_ONE_BLOCK_COST=3.564 like Path.smoothFlatRuns' euclid cost.
"""
import math, random, io, contextlib
with contextlib.redirect_stdout(io.StringIO()):
    import navsim
    import smoother as S

SPRINT_ONE_BLOCK_COST = 3.564
MOVEMENT_TIMEOUT = 100
MAX_DIST, MAX_TICKS_AWAY, MAX_MAX_DIST = 2.0, 200, 3.0

class Chord:
    def __init__(self, src, dest):
        self.src, self.dest = src, dest
        cheb = max(abs(dest[0]-src[0]), abs(dest[1]-src[1]))
        if cheb > 1:   # merged any-angle chord: valid = exact swept supercover (matches SmoothTraverse)
            self.valid = set(S.swept_cells(src, dest, 0.35)) | {src, dest}
        else:          # plain lattice step (matches MovementTraverse/Diagonal validPositions)
            self.valid = {src, dest}
        self.cost = math.hypot(dest[0]-src[0], dest[1]-src[1]) * SPRINT_ONE_BLOCK_COST

def run_executor(poly, profile, seed=0, max_ticks=6000):
    """walk the polyline through pursuit physics + the executor state machine; returns (ok, reason, stats)."""
    movements = [Chord(poly[k], poly[k+1]) for k in range(len(poly)-1)]
    pts = navsim.centers(poly)
    mc = navsim.min_count(profile.sensitivity)
    rng = random.Random(seed)
    wander = navsim.Wander(rng, profile)
    pos = [pts[0][0], pts[0][1]]
    v = [0.0, 0.0]
    yaw = navsim.yaw_to(pts[0], pts[1])
    strafing = False; held = [0]
    p = 0                      # executor pathPosition
    ticks_on, ticks_away = 0, 0
    max_timeout_frac = 0.0     # worst ticksOnCurrent/(cost+100) seen — headroom metric

    for tick in range(max_ticks):
        # ---------------- executor onTick (with same-tick recursion, bounded) ----------------
        for _ in range(8):     # onTick recursion guard
            if p >= len(movements):
                return True, "done", max_timeout_frac
            m = movements[p]
            feet = (math.floor(pos[0]), math.floor(pos[1]))
            if feet not in m.valid:
                jumped = False
                for i in range(p):                       # back-skip (lag/teleport)
                    if feet in movements[i].valid:
                        p, ticks_on, jumped = i, 0, True
                        break
                if not jumped:
                    for i in range(p+3, len(movements)-1):   # forward skip, starts at +3 like the real one
                        if feet in movements[i].valid:
                            p, ticks_on, jumped = i, 0, True
                            break
                if jumped:
                    continue
            if feet == m.dest:                            # SmoothTraverse SUCCESS
                p += 1; ticks_on = 0
                continue
            break
        if p >= len(movements):
            return True, "done", max_timeout_frac

        # distance gate: nearest valid CELL CENTER across all movements (closestPathPos)
        best = min(min(math.hypot(pos[0]-(c[0]+0.5), pos[1]-(c[1]+0.5)) for c in mm.valid)
                   for mm in movements)
        if best > MAX_MAX_DIST:
            return False, "hard-dist@%d" % tick, max_timeout_frac
        if best > MAX_DIST:
            ticks_away += 1
            if ticks_away > MAX_TICKS_AWAY:
                return False, "dist-timeout@%d" % tick, max_timeout_frac
        else:
            ticks_away = 0

        # movement timeout (cost + movementTimeoutTicks)
        ticks_on += 1
        budget = movements[p].cost + MOVEMENT_TIMEOUT
        max_timeout_frac = max(max_timeout_frac, ticks_on / budget)
        if ticks_on > budget:
            return False, "move-timeout@%d(chord %d len %.0f)" % (tick, p, movements[p].cost/SPRINT_ONE_BLOCK_COST), max_timeout_frac

        # ---------------- steering + physics (navsim core), window anchored to pathPosition ----------------
        lo, hi = max(0, p-1), min(len(pts)-1, p+8)
        i, t = navsim.project(pts, pos, lo, hi)
        ax, az = pts[i]; bx, bz = pts[i+1]
        segl = math.hypot(bx-ax, bz-az) or 1
        cross = ((pos[0]-ax)*(bz-az) - (pos[1]-az)*(bx-ax)) / segl
        far = navsim.advance(pts, i, t, hi, profile.gaze_ahead)
        near = navsim.advance(pts, i, t, hi, profile.track_ahead)
        prev_yaw = yaw
        ou, trem = wander.tick()
        gaze = navsim.yaw_to(pos, far)
        raw_err = abs(navsim.wrap(gaze - prev_yaw))
        wscale = 1.0 if raw_err <= profile.wander_fade_lo else max(0.0, 1-(raw_err-profile.wander_fade_lo)/(profile.wander_fade_hi-profile.wander_fade_lo))
        desired = gaze + ou*wscale + trem
        step = navsim.prop_step(navsim.wrap(desired - prev_yaw), profile)
        capped = max(-profile.hard_cap, min(profile.hard_cap, step))
        capped = max(-profile.cruise_yaw_cap, min(profile.cruise_yaw_cap, capped))
        yaw = prev_yaw + round(capped/mc)*mc
        rel = navsim.wrap(navsim.yaw_to(pos, near) - prev_yaw)
        if strafing and abs(rel) < profile.release_deg: strafing = False
        elif not strafing and abs(rel) >= profile.engage_deg: strafing = True
        octo = min(navsim.OCTANTS, key=lambda o: abs(navsim.wrap(rel-o))) if strafing else 0
        if profile.boundary_hyst > 0 and strafing and held[0] != 0 and octo != held[0]:
            if abs(navsim.wrap(rel-octo)) > abs(navsim.wrap(rel-held[0])) - profile.boundary_hyst:
                octo = held[0]
        held[0] = octo if strafing else 0
        fwd = octo in (0, 45, -45)
        speed_factor = 1.0
        if profile.curve_slow:
            a0 = navsim.advance(pts, i, t, hi, 0.2)
            a1 = navsim.advance(pts, i, t, hi, 0.2 + profile.curve_lookahead*0.5)
            a2 = navsim.advance(pts, i, t, hi, 0.2 + profile.curve_lookahead)
            bend = abs(navsim.wrap(navsim.yaw_to(a1, a2) - navsim.yaw_to(a0, a1)))
            speed_factor = 0.77 if bend >= 22 else 1.0
        move_dir = math.radians(yaw + octo)
        accel = (navsim.SPRINT_ACCEL if fwd else navsim.WALK_ACCEL) * speed_factor
        v[0] += -math.sin(move_dir)*accel; v[1] += math.cos(move_dir)*accel
        pos[0] += v[0]; pos[1] += v[1]
        v[0] *= navsim.FRICTION; v[1] *= navsim.FRICTION
    return False, "sim-tick-cap", max_timeout_frac

def corpus(n_maps, seed, gen=None):
    rng = random.Random(seed); W = H = 22; out = []
    while len(out) < n_maps:
        grid = gen(rng, W, H) if gen else S._random_grid(rng, W, H, rng.uniform(0.06, 0.14), 0.25)
        lat = S.astar(grid, (0, 0), (W-1, H-1))
        if len(lat) < 4 or lat[-1] != (W-1, H-1):
            continue
        out.append((grid, lat))
    return out

def run():
    prof = navsim.DEPLOYED
    for name, gen, seed in (("random", None, 7), ("structured", S._structured_grid, 13)):
        maps = corpus(150, seed, gen)
        fails_sm, fails_lat, worst_frac, worst_case = 0, 0, 0.0, ""
        for k, (grid, lat) in enumerate(maps):
            sm = S.string_pull(grid, lat)
            ok, why, frac = run_executor(sm, prof, seed=2000+k)
            if frac > worst_frac:
                worst_frac, worst_case = frac, f"map{k} {why if not ok else 'ok'}"
            if not ok:
                fails_sm += 1
                print(f"  SMOOTHED FAIL map {k}: {why}")
            ok2, why2, _ = run_executor(lat, prof, seed=2000+k)
            if not ok2:
                fails_lat += 1
                print(f"  (lattice baseline fail map {k}: {why2} — sim-fidelity reference)")
        print(f"== executor-sim [{name}]: {len(maps)} maps | smoothed fails: {fails_sm} (MUST be 0) | "
              f"lattice-baseline fails: {fails_lat} | worst timeout headroom used: {worst_frac*100:.0f}% ({worst_case})")

if __name__ == '__main__':
    run()
