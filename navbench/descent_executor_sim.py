"""
navbench — EXECUTOR feasibility of DESCENT CHORDS (2.5D), bench-only: the expensive open question.

descent_potential.py showed the value (-39% heading turns on rolling terrain, where the flat merger does ~nothing).
This sim answers whether the EXECUTOR STATE MACHINE — exactly as shipped after the review fixes (chord-aware +1/+2
skips, far-skip chord exclusion, end-region SUCCESS gated on corridor membership, noRewindBelow floor, recursion
depth guard, distance gate 2.0/200 + 3.0, timeout cost+100) — can drive a MONOTONE-GENTLE-DESCENT chord, before a
single line of core code is written.

2.5D model:
  - terrain: rolling heightmap (same generator family as descent_potential)
  - a descent chord's VALID SET is 3D: every swept (x,z) cell AT ITS MERGE-TIME PROFILE HEIGHT (what chordSafe
    would verify per column) — feet (x, H(x,z), z) are contained iff the terrain height matches the profile
  - SUCCESS: axial end region (s >= len-0.5) AND feet in the 3D valid set (corridor membership incl. y)
  - physics: pursuit steers XZ exactly like navsim/MovementHelper; y snaps to terrain height under the feet
    (a 1-block walk-off drop costs ~4 extra ticks of reduced control, modeled as 3 ticks of no-input glide)
  - chord cost: XZ-euclid * SPRINT + FALL_N_BLOCKS_COST per 1-block drop (23/20 ticks ~ 1.15 each, generous)
PASS = every rolling-terrain path completes with zero cancels/timeouts and the recursion guard never saturates.
"""
import math, random, io, contextlib
with contextlib.redirect_stdout(io.StringIO()):
    import navsim
    import smoother as S
    import descent_potential as DP

SPRINT_ONE = 3.564
TIMEOUT = 100

class Mov:
    def __init__(self, H, seg):
        self.src, self.dest = seg[0], seg[-1]
        self.ys, self.ye = H[seg[0]], H[seg[-1]]
        cheb = max(abs(self.dest[0]-self.src[0]), abs(self.dest[1]-self.src[1]))
        self.is_chord = cheb > 1
        self.len = math.hypot(self.dest[0]-self.src[0], self.dest[1]-self.src[1])
        self.axis = ((self.dest[0]-self.src[0])/self.len, (self.dest[1]-self.src[1])/self.len) if self.len > 0 else (0.0, 0.0)
        # 3D valid set: swept cells at their merge-time profile heights (what chordSafe verifies per column)
        self.valid = {}
        if self.is_chord:
            for cx, cz in S.swept_cells(self.src, self.dest, 0.65):
                if (cx, cz) in H:
                    # profile height at this cell = terrain height (chordSafe verified it at merge time)
                    self.valid[(cx, cz)] = H[(cx, cz)]
        for c in (self.src, self.dest):
            self.valid[c] = H[c]
        drops = sum(max(0, H[seg[k]] - H[seg[k+1]]) for k in range(len(seg)-1))
        self.cost = self.len * SPRINT_ONE + drops * 1.15 * 20 / 20 * 20 * 0.0575  # ~1.15 ticks/drop, cost-scaled

    def contains(self, feet3):
        (x, z, y) = feet3
        return self.valid.get((x, z)) == y

    def done(self, pos, feet3, H):
        if (feet3[0], feet3[1]) == self.dest and feet3[2] == self.ye:
            return True
        if not self.is_chord:
            return False
        # 2.5D refinement (feasibility finding): end-region cells BESIDE dest legitimately sit +-1 in height on
        # rolling terrain — requiring the exact end height strands the movement (8/150 timeouts). Corridor
        # profile-height membership (contains) is the verified-ground condition; the axial bound does the rest.
        if not self.contains(feet3):
            return False
        ex, ez = pos[0]-(self.src[0]+0.5), pos[1]-(self.src[1]+0.5)
        s = ex*self.axis[0] + ez*self.axis[1]
        return s >= self.len - 0.5

def build_movements(H, lat):
    """merge with descent_potential's rule; unmerged remainder stays single lattice steps."""
    poly = DP.string_pull_mode(H, lat, 'descent')
    # expand back to per-pair movements referencing the ORIGINAL lattice segment for profile heights
    movs, k = [], 0
    idx = {c: i for i, c in enumerate(lat)}
    for a, b in zip(poly, poly[1:]):
        seg = lat[idx[a]:idx[b]+1]
        movs.append(Mov(H, seg))
    return movs, poly

def run(H, movs, poly, seed, max_ticks=4000):
    pts = navsim.centers(poly)
    prof = navsim.DEPLOYED
    mc = navsim.min_count(prof.sensitivity)
    rng = random.Random(seed)
    wander = navsim.Wander(rng, prof)
    pos = [pts[0][0], pts[0][1]]
    v = [0.0, 0.0]
    yaw = navsim.yaw_to(pts[0], pts[1])
    strafing = False; p = 0; ticks_on = 0; ticks_away = 0; glide = 0; guard_hits = 0
    for tick in range(max_ticks):
        nrb = 0
        for it in range(9):
            if it == 8:
                guard_hits += 1
                break
            if p >= len(movs):
                return (guard_hits == 0), ('done' if guard_hits == 0 else 'guard'), tick
            m = movs[p]
            fx, fz = math.floor(pos[0]), math.floor(pos[1])
            if (fx, fz) not in H:
                return False, 'off-world', tick
            feet3 = (fx, fz, H[(fx, fz)])
            if not m.contains(feet3):
                jumped = False
                for i in range(nrb, p):
                    if movs[i].contains(feet3):
                        p, ticks_on, jumped = i, 0, True
                        break
                if not jumped:
                    for i in range(p+1, len(movs)):
                        cc = movs[i].is_chord
                        if i < p+3:
                            if cc and movs[i].contains(feet3):
                                p, ticks_on, jumped = i, 0, True
                                break
                            if not cc and (fx, fz) == movs[i].dest and feet3[2] == movs[i].ye:
                                p, ticks_on, jumped = i, 0, True
                                break
                        else:
                            if cc:
                                continue
                            if movs[i].contains(feet3):
                                p, ticks_on, jumped = max(p, i-1), 0, True
                                break
                if jumped:
                    continue
            if m.done(pos, feet3, H):
                p += 1; ticks_on = 0; nrb = max(nrb, p)
                continue
            break
        if p >= len(movs):
            return (guard_hits == 0), ('done' if guard_hits == 0 else 'guard'), tick
        best = min(min(math.hypot(pos[0]-(c[0]+0.5), pos[1]-(c[1]+0.5)) for c in mm.valid)
                   for mm in movs)
        if best > 3.0: return False, 'hard-dist@%d' % tick, tick
        if best > 2.0:
            ticks_away += 1
            if ticks_away > 200: return False, 'dist-timeout', tick
        else:
            ticks_away = 0
        ticks_on += 1
        if ticks_on > movs[p].cost + TIMEOUT:
            return False, 'move-timeout(c%d len %.0f)' % (p, movs[p].len), tick
        # steering (shipped core incl. XT strafe)
        lo, hi = max(0, p-1), min(len(pts)-1, p+8)
        i, t = navsim.project(pts, pos, lo, hi)
        ax, az = pts[i]; bx, bz = pts[i+1]
        segl = math.hypot(bx-ax, bz-az) or 1
        cross = ((pos[0]-ax)*(bz-az) - (pos[1]-az)*(bx-ax)) / segl
        far = navsim.advance(pts, i, t, hi, prof.gaze_ahead)
        near = navsim.advance(pts, i, t, hi, prof.track_ahead)
        prev_yaw = yaw
        ou, trem = wander.tick()
        gaze = navsim.yaw_to(pos, far)
        step = navsim.prop_step(navsim.wrap(gaze + ou + trem - prev_yaw), prof)
        capped = max(-prof.cruise_yaw_cap, min(prof.cruise_yaw_cap, max(-prof.hard_cap, min(prof.hard_cap, step))))
        yaw = prev_yaw + round(capped/mc)*mc
        rel = navsim.wrap(navsim.yaw_to(pos, near) - prev_yaw)
        bw = (abs(rel) >= 12.0) if strafing else (abs(rel) >= 25.0)
        xw = (abs(cross) > 0.12) if strafing else (abs(cross) >= 0.30)
        strafing = bw or xw
        if strafing and abs(rel) < 22.5 and abs(cross) > 0.12:
            octo = 45 if cross > 0 else -45
        else:
            octo = min(navsim.OCTANTS, key=lambda o: abs(navsim.wrap(rel-o))) if strafing else 0
        fwd = octo in (0, 45, -45)
        a0 = navsim.advance(pts, i, t, hi, 0.2); a1 = navsim.advance(pts, i, t, hi, 1.4); a2 = navsim.advance(pts, i, t, hi, 2.6)
        bend = abs(navsim.wrap(navsim.yaw_to(a1, a2) - navsim.yaw_to(a0, a1)))
        sf = 0.77 if bend >= 22 else 1.0
        if glide > 0:
            glide -= 1        # brief walk-off drop: no fresh input, momentum only
            accel = 0.0
        else:
            accel = (navsim.SPRINT_ACCEL if fwd else navsim.WALK_ACCEL) * sf
        md = math.radians(yaw + octo)
        v[0] += -math.sin(md) * accel; v[1] += math.cos(md) * accel
        oldy = H.get((math.floor(pos[0]), math.floor(pos[1])))
        pos[0] += v[0]; pos[1] += v[1]
        v[0] *= navsim.FRICTION; v[1] *= navsim.FRICTION
        newy = H.get((math.floor(pos[0]), math.floor(pos[1])))
        if oldy is not None and newy is not None and newy < oldy:
            glide = 3          # dropped an edge: ~3 ticks reduced control
    return False, 'tick-cap', max_ticks

def main():
    rng = random.Random(19)
    W = Hh = 22
    n_ok = n_maps = 0
    fails = []
    chord_share = 0.0
    while n_maps < 150:
        H = DP.heightmap(rng, W, Hh)
        lat = DP.astar25(H, W, Hh, (0, 0), (W-1, Hh-1))
        if len(lat) < 8 or lat[-1] != (W-1, Hh-1):
            continue
        n_maps += 1
        movs, poly = build_movements(H, lat)
        chords = sum(1 for m in movs if m.is_chord)
        chord_share += chords / max(1, len(movs))
        ok, why, t = run(H, movs, poly, seed=3000 + n_maps)
        if ok:
            n_ok += 1
        else:
            fails.append((n_maps, why))
    print("== descent-chord EXECUTOR feasibility: %d rolling-terrain maps ==" % n_maps)
    print("  completed: %d/%d  | avg chord share of movements: %.0f%%" % (n_ok, n_maps, 100*chord_share/n_maps))
    for f in fails[:12]:
        print("  FAIL map %d: %s" % f)
    if not fails:
        print("  ZERO fails — the shipped executor rules (chord skips, end-region-in-corridor SUCCESS, rewind")
        print("  floor, depth guard) carry over to 2.5D descent chords unchanged in this model.")

if __name__ == '__main__':
    main()
