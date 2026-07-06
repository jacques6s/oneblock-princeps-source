"""
navbench — any-angle path smoothing prototype ("why draw lines only through block centers at 45/90 deg?").

Baritone emits a lattice path: a sequence of block-center nodes connected by cardinal/diagonal unit steps, so an
off-axis or around-a-corner route zig-zags. A human walks the DIRECT line across open ground and only bends where
something is actually in the way. This module prototypes a collision-safe STRING-PULLING smoother (line-of-sight
greedy) that turns the lattice into a taut any-angle polyline, and measures:
  - efficiency   (path length: shorter = less distance walked)
  - smoothness   (number of turns / total heading change: fewer, gentler)
  - safety       (every smoothed segment stays on walkable cells — never cuts through a wall)
  - followability (feed the smoothed polyline to the real pursuit sim: coverage + cross-track hold)

Grid model: 2D walkable/blocked cells (flat). LOS is the SUPERCOVER of the segment inflated by the player half-width
(0.3 + margin), so a smoothed line only survives if a 0.6-wide body clears it — the real Princeps check would add the
solid-floor + head-room raytrace, but the geometry (which cells the body sweeps) is exactly this.
"""
import math, heapq, io, contextlib
with contextlib.redirect_stdout(io.StringIO()):
    import navsim
    import analyze

HALF = 0.35   # player half-width + small margin (cells the body sweeps must be clear)

def walkable(grid, cx, cz):
    # solid floor + clear body/head, and NOT a hazard. In Princeps this is the real
    # MovementHelper.canWalkOn(below) && canWalkThrough(feet) && canWalkThrough(head) && !avoidWalkingInto(hazard).
    if not (0 <= cx < grid['w'] and 0 <= cz < grid['h']): return False
    c = (cx, cz)
    return c not in grid['blocked'] and c not in grid.get('nofloor', set()) \
        and c not in grid.get('lowceil', set()) and c not in grid.get('hazard', set())

def crosses_hazard(grid, cx, cz):
    return (cx, cz) in grid.get('hazard', set())

def _seg_rect(ax, az, bx, bz, xmin, zmin, xmax, zmax):
    """Liang-Barsky: does segment (ax,az)->(bx,bz) intersect the axis-aligned rect [xmin,xmax]x[zmin,zmax]?"""
    dx, dz = bx - ax, bz - az
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, ax - xmin), (dx, xmax - ax), (-dz, az - zmin), (dz, zmax - az)):
        if abs(p) < 1e-12:
            if q < 0:
                return False
        else:
            r = q / p
            if p < 0:
                if r > t1: return False
                if r > t0: t0 = r
            else:
                if r < t0: return False
                if r < t1: t1 = r
    return t0 <= t1

def swept_cells(a, b, half):
    """
    EXACT (sampling-free) supercover: every cell an AABB body of half-width `half` overlaps while its centre travels
    the straight segment a->b. Body-square overlaps cell [cx,cx+1]x[cz,cz+1] iff the centre passes within `half` of
    the cell on both axes, i.e. the segment intersects that cell-rect expanded by `half` on all sides. Point-sampling
    the corners (the old way) MISSES cells the body grazes at a corner between samples (a real collision gap the random
    stress test caught); this enumerates the bounding box and does an exact segment-rect test, so nothing is missed.
    """
    ax, az, bx, bz = a[0] + 0.5, a[1] + 0.5, b[0] + 0.5, b[1] + 0.5
    xlo = math.floor(min(ax, bx) - half - 1); xhi = math.floor(max(ax, bx) + half + 1)
    zlo = math.floor(min(az, bz) - half - 1); zhi = math.floor(max(az, bz) + half + 1)
    out = []
    for cx in range(xlo, xhi + 1):
        for cz in range(zlo, zhi + 1):
            if _seg_rect(ax, az, bx, bz, cx - half, cz - half, cx + 1 + half, cz + 1 + half):
                out.append((cx, cz))
    return out

def los(grid, a, b):
    """3D-safe LOS: a 0.7-wide body can walk the straight segment a->b — every swept cell has floor + body + head
       clearance and is NOT a hazard. A single unsafe swept cell fails the merge (the lattice detour is kept).
       Uses the EXACT swept-cell set (no point-sampling graze gaps)."""
    for cx, cz in swept_cells(a, b, HALF):
        if not walkable(grid, cx, cz) or crosses_hazard(grid, cx, cz):
            return False
    return True

def astar(grid, start, goal):
    """8-directional lattice A* (diagonals allowed, no corner-cutting through blocks) — models Baritone's output."""
    def h(p): return math.hypot(p[0] - goal[0], p[1] - goal[1])
    openq = [(h(start), 0.0, start)]; came = {}; g = {start: 0.0}
    DIRS = [(1,0),(-1,0),(0,1),(0,-1),(1,1),(1,-1),(-1,1),(-1,-1)]
    while openq:
        _, gc, cur = heapq.heappop(openq)
        if cur == goal:
            path = [cur]
            while cur in came: cur = came[cur]; path.append(cur)
            return path[::-1]
        for dx, dz in DIRS:
            nb = (cur[0] + dx, cur[1] + dz)
            if not walkable(grid, *nb): continue
            if dx and dz and (not walkable(grid, cur[0]+dx, cur[1]) or not walkable(grid, cur[0], cur[1]+dz)):
                continue  # no diagonal squeeze through a wall corner
            ng = gc + math.hypot(dx, dz)
            if ng < g.get(nb, 1e18):
                g[nb] = ng; came[nb] = cur; heapq.heappush(openq, (ng + h(nb), ng, nb))
    return [start]

MAX_CHORD = 24   # cap a single smoothed chord's block-length (Chebyshev). Bounds the O(L^3) diagonal-open-field
                 # greedy-scan cost to O(L*cap^2); costs ZERO on real terrain (merges are far shorter) and only
                 # splits a huge straight run into COLLINEAR chords (same line, a few more nodes -> no smoothness loss).

def string_pull(grid, path, max_chord=None):
    """greedy line-of-sight smoothing: from each kept node, jump to the FURTHEST node still walkable in a straight line
       (bounded to max_chord blocks so the cost stays linear on large open fields)."""
    if len(path) < 3: return list(path)
    cap = MAX_CHORD if max_chord is None else max_chord
    out = [path[0]]; i = 0
    while i < len(path) - 1:
        j = len(path) - 1
        # cap the chord length first (never consider a node further than `cap` blocks from i), then pull in for safety
        while j > i + 1 and max(abs(path[j][0] - path[i][0]), abs(path[j][1] - path[i][1])) > cap:
            j -= 1
        while j > i + 1 and not los(grid, path[i], path[j]):
            j -= 1
        out.append(path[j]); i = j
    return out

def length(path):
    return sum(math.hypot(path[k+1][0]-path[k][0], path[k+1][1]-path[k][1]) for k in range(len(path)-1))

def turns(path):
    n, total = 0, 0.0
    for k in range(1, len(path)-1):
        h1 = math.degrees(math.atan2(path[k][1]-path[k-1][1], path[k][0]-path[k-1][0]))
        h2 = math.degrees(math.atan2(path[k+1][1]-path[k][1], path[k+1][0]-path[k][0]))
        d = abs((h2-h1+180) % 360 - 180)
        if d > 5: n += 1
        total += d
    return n, total

# ---- scenarios: (name, grid, start, goal) ; grid = open field minus 'blocked' cells (+ hazard / lowceil) ----
def field(w, h, blocked=(), hazard=(), lowceil=()):
    return {'w': w, 'h': h, 'blocked': set(blocked), 'hazard': set(hazard), 'lowceil': set(lowceil)}

def wall(x, z0, z1):  # vertical wall segment
    return [(x, z) for z in range(z0, z1)]

def scenarios():
    return [
        ("open_offaxis", field(16, 8), (0, 0), (15, 4)),        # shallow off-axis line across open ground
        ("open_steep",   field(10, 14), (0, 0), (3, 13)),       # steep off-axis
        ("corner_obst",  field(12, 12, wall(6, 0, 9)), (0, 4), (11, 4)),   # wall forces a detour; cut it where clear
        ("gap_slalom",   field(20, 9, wall(7, 0, 6) + wall(13, 3, 9)), (0, 4), (19, 4)),  # slalom around two walls
        ("diagonal45",   field(12, 12), (0, 0), (10, 10)),      # already 45 — smoother should barely change it
        # SAFETY: a lava pit sits ON the direct chord. The lattice detours around it; the smoother MUST NOT cut the
        # corner across the pit (that would walk the bot into lava). Expect the detour to be preserved.
        ("lava_pit",     field(14, 10, hazard=[(x, z) for x in range(5, 9) for z in range(3, 6)]),
                         (0, 4), (13, 4)),
        # SAFETY: a low-ceiling strip (head obstacle) on the direct line — the body can't fit; the smoother must not
        # chord across it (canWalkThrough head fails). Detour preserved.
        ("low_ceiling",  field(14, 10, lowceil=[(x, z) for x in range(5, 9) for z in range(3, 6)]),
                         (0, 4), (13, 4)),
        # a genuine 1-wide corridor (thick walls) — a diagonal body clips the walls, so almost nothing merges; the
        # smoother must keep it essentially lattice (coverage/safety hold, minimal shortening).
        ("tight_corridor", field(16, 11, wall(0, 0, 4) + wall(0, 6, 11) + [(x, z) for x in range(1, 16) for z in list(range(0, 4)) + list(range(6, 11))]),
                         (1, 5), (14, 5)),
    ]

def run():
    print(f"{'scenario':13s} {'latNodes':>8s} {'smNodes':>7s} {'latLen':>7s} {'smLen':>6s} {'len-':>5s} "
          f"{'latTurns':>8s} {'smTurns':>7s} {'latHead':>7s} {'smHead':>6s} {'safe':>4s} {'follow':>7s}")
    for name, grid, start, goal in scenarios():
        lat = astar(grid, start, goal)
        sm = string_pull(grid, lat)
        ll, sl = length(lat), length(sm)
        lt, lh = turns(lat); st, sh = turns(sm)
        safe = all(los(grid, sm[k], sm[k+1]) for k in range(len(sm)-1))
        # explicit hazard check (independent of los, via the exact swept-cell set): must touch NO hazard cell
        hazard_hit = any(crosses_hazard(grid, cx, cz)
                         for k in range(len(sm)-1) for cx, cz in swept_cells(sm[k], sm[k+1], HALF))
        # followability: feed the smoothed polyline to the real pursuit sim (deployed profile)
        tr = navsim.simulate(list(sm), navsim.DEPLOYED, seed=1000)
        follow = "cov%d%% xt%.2f" % (round(tr.coverage*100), max((abs(q.cross) for q in tr.records), default=0))
        haz = "HAZARD!" if hazard_hit else "clear"
        print(f"{name:13s} {len(lat):8d} {len(sm):7d} {ll:7.1f} {sl:6.1f} {100*(sl/ll-1):+4.0f}% "
              f"{lt:8d} {st:7d} {lh:7.0f} {sh:6.0f} {str(safe):>4s} {haz:>7s} {follow:>7s}")

def walked_length(tr):
    """actual distance the BODY travelled through the physics sim (not the polyline length) — the real efficiency."""
    r = tr.records
    return sum(math.hypot(r[i+1].x - r[i].x, r[i+1].z - r[i].z) for i in range(len(r)-1))

def head_rate(tr):
    """peak + rms of the per-tick SENT yaw delta (|dyaw|) — the packet-visible head-turn rate."""
    d = [abs(q.dyaw) for q in tr.records]
    peak = max(d, default=0.0)
    rms = math.sqrt(sum(x*x for x in d)/len(d)) if d else 0.0
    return peak, rms

def _mean(xs):
    return sum(xs)/len(xs) if xs else 0.0

def ab_compare(seeds=(1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007)):
    """
    OBJECTIVE any-angle measurement: walk the SAME lattice route once as the raw Baritone lattice and once as the
    string-pulled smoothed polyline, both through the FULL physics + pursuit-steering pipeline (navsim, deployed
    profile), and report the per-dimension % improvement. This is the honest end-to-end number: it isn't "the geometry
    is shorter", it's "a body actually walking the smoothed line is measurably smoother AND keeps full coverage".
    Averaged over seeds because the human-wander layer is stochastic.
    Dimensions: efficiency (walked length), smoothness/jerk (velDir jerk RMS, sent-yaw angular jerk RMS, head-turn
    peak), collision-safety (max cross-track), coverage (node hit-rate — must not regress).
    """
    print("\n== A/B: lattice vs smoothed, walked through the full physics+steering pipeline (avg over %d seeds) ==" % len(seeds))
    print(f"{'scenario':13s} {'len_lat':>7s} {'len_sm':>6s} {'len%':>5s} {'vdj_lat':>7s} {'vdj_sm':>6s} {'vdj%':>5s} "
          f"{'aj_lat':>6s} {'aj_sm':>6s} {'aj%':>5s} {'hd_lat':>6s} {'hd_sm':>6s} {'cov_lat':>7s} {'cov_sm':>6s} {'xt_sm':>5s}")
    agg = {k: [] for k in ('len', 'vdj', 'aj', 'hd', 'covL', 'covS', 'xt')}
    for name, grid, start, goal in scenarios():
        lat = astar(grid, start, goal)
        sm = string_pull(grid, lat)
        L = {k: [] for k in ('lenL','lenS','vdjL','vdjS','ajL','ajS','hdL','hdS','covL','covS','xtS')}
        for sd in seeds:
            tl = navsim.simulate(centers_to_nodes(lat), navsim.DEPLOYED, seed=sd)
            ts = navsim.simulate(centers_to_nodes(sm),  navsim.DEPLOYED, seed=sd)
            sl_, ss_ = analyze.smoothness(tl), analyze.smoothness(ts)
            L['lenL'].append(walked_length(tl)); L['lenS'].append(walked_length(ts))
            L['vdjL'].append(sl_['veldir_jump_rms']); L['vdjS'].append(ss_['veldir_jump_rms'])
            L['ajL'].append(sl_['ang_jerk_rms']); L['ajS'].append(ss_['ang_jerk_rms'])
            L['hdL'].append(head_rate(tl)[0]); L['hdS'].append(head_rate(ts)[0])
            L['covL'].append(tl.coverage); L['covS'].append(ts.coverage)
            L['xtS'].append(max((abs(q.cross) for q in ts.records), default=0.0))
        lenL, lenS = _mean(L['lenL']), _mean(L['lenS'])
        vdjL, vdjS = _mean(L['vdjL']), _mean(L['vdjS'])
        ajL, ajS = _mean(L['ajL']), _mean(L['ajS'])
        hdL, hdS = _mean(L['hdL']), _mean(L['hdS'])
        covL, covS = _mean(L['covL']), _mean(L['covS'])
        xtS = _mean(L['xtS'])
        pct = lambda a, b: (100*(1 - b/a)) if a > 1e-9 else 0.0   # % reduction (lower is better)
        agg['len'].append((lenL, lenS)); agg['vdj'].append((vdjL, vdjS)); agg['aj'].append((ajL, ajS))
        agg['hd'].append((hdL, hdS)); agg['covL'].append(covL); agg['covS'].append(covS); agg['xt'].append(xtS)
        print(f"{name:13s} {lenL:7.1f} {lenS:6.1f} {pct(lenL,lenS):+4.0f}% {vdjL:7.2f} {vdjS:6.2f} {pct(vdjL,vdjS):+4.0f}% "
              f"{ajL:6.2f} {ajS:6.2f} {pct(ajL,ajS):+4.0f}% {hdL:6.1f} {hdS:6.1f} {covL*100:6.0f}% {covS*100:5.0f}% {xtS:5.2f}")
    # aggregate improvement across all scenarios (totals for length, means for jerk)
    tLenL = sum(a for a, _ in agg['len']); tLenS = sum(b for _, b in agg['len'])
    mVdjL, mVdjS = _mean([a for a, _ in agg['vdj']]), _mean([b for _, b in agg['vdj']])
    mAjL, mAjS = _mean([a for a, _ in agg['aj']]), _mean([b for _, b in agg['aj']])
    mHdL, mHdS = _mean([a for a, _ in agg['hd']]), _mean([b for _, b in agg['hd']])
    red = lambda a, b: (100*(1 - b/a)) if a > 1e-9 else 0.0
    print("-" * 118)
    print("AGGREGATE  walked-length %+.0f%%  | velDir-jerk %+.0f%%  | angular-jerk %+.0f%%  | head-peak %+.0f%%  "
          "| coverage lat %.0f%% -> sm %.0f%%  | maxXT %.2f"
          % (red(tLenL, tLenS), red(mVdjL, mVdjS), red(mAjL, mAjS), red(mHdL, mHdS),
             _mean(agg['covL'])*100, _mean(agg['covS'])*100, _mean(agg['xt'])))
    print("(positive %% = smoothed is that much better; coverage must stay ~equal = no strand introduced)")

def centers_to_nodes(cells):
    """navsim.simulate expects integer lattice nodes; smoothed endpoints are already integer cells, pass through."""
    return [(int(x), int(z)) for x, z in cells]

def margin_sweep():
    """
    Justify the collision-safety half-width (deployed 0.35). Re-smooth every scenario at half in {0.30..0.45} and
    report the total smoothed length + merge count. 0.30 = the exact 0.6-wide body (ZERO margin); larger = more
    conservative (rejects grazes, keeps the lattice detour). The finding: 0.35 buys a real corner-clearance margin
    (it rejects knife-edge grazes a 0.30 body would accept — see the navbench graze probe / SmoothTraverseTest) while
    losing ~nothing in length on real paths, because lattice routes keep >=1 block clearance so genuine merges aren't
    grazes. So 0.35 sits at the safe knee: full efficiency, real margin.
    """
    print("\n== collision-margin sweep (half-width): total smoothed length + merges across all scenarios ==")
    print(f"{'half':>5s} {'totLatLen':>9s} {'totSmLen':>8s} {'len-':>5s} {'merges':>6s} {'allSafe':>7s}")
    scen = scenarios()
    for half in (0.30, 0.35, 0.40, 0.45):
        old = globals()['HALF']; globals()['HALF'] = half
        try:
            totLat = totSm = 0.0; merges = 0; allSafe = True
            for name, grid, start, goal in scen:
                lat = astar(grid, start, goal); sm = string_pull(grid, lat)
                totLat += length(lat); totSm += length(sm)
                merges += len(lat) - len(sm)
                if not all(los(grid, sm[k], sm[k+1]) for k in range(len(sm)-1)):
                    allSafe = False
            print(f"{half:5.2f} {totLat:9.1f} {totSm:8.1f} {100*(totSm/totLat-1):+4.0f}% {merges:6d} {str(allSafe):>7s}"
                  + ("   <- deployed" if abs(half-0.35) < 1e-9 else ""))
        finally:
            globals()['HALF'] = old
    print("(0.30 = exact body / zero margin; deployed 0.35 keeps the length gains AND rejects knife-edge grazes)")

def _random_grid(rng, w, h, density, hazard_frac):
    """random obstacle+hazard field; the two goal corners + their 2-neighbourhood are kept clear so a route usually
       exists. A fraction of obstacles are hazards (lava) to also stress the never-chord-across-hazard rule."""
    blocked, hazard = [], []
    for x in range(w):
        for z in range(h):
            if (x <= 1 and z <= 1) or (x >= w - 2 and z >= h - 2):
                continue
            if rng.random() < density:
                (hazard if rng.random() < hazard_frac else blocked).append((x, z))
    return field(w, h, blocked=blocked, hazard=hazard)

def _structured_grid(rng, w, h):
    """
    STRUCTURED obstacles: axis-aligned walls (each with a doorway) + diagonal barrier segments. Unlike the blobby
    independent-cell field, this produces long straight/diagonal faces so a smoothed chord can run tangent ALONG a
    wall or squeeze a corridor — exactly the tangent / parallel-edge geometries where a floating-point bug in the
    Liang-Barsky segment/AABB clip would hide. Some walls are hazard (lava). Goal corners kept clear.
    """
    blocked, hazard = set(), set()
    def put(x, z, hz):
        if 0 <= x < w and 0 <= z < h and not ((x <= 1 and z <= 1) or (x >= w - 2 and z >= h - 2)):
            (hazard if hz else blocked).add((x, z))
    for _ in range(rng.randint(3, 6)):                    # walls with a doorway
        hz = rng.random() < 0.25
        if rng.random() < 0.5:
            cx = rng.randint(3, w - 4); z0 = rng.randint(0, h - 6); z1 = min(h - 1, z0 + rng.randint(4, 8))
            door = rng.randint(z0, z1)
            for z in range(z0, z1 + 1):
                if z != door: put(cx, z, hz)
        else:
            cz = rng.randint(3, h - 4); x0 = rng.randint(0, w - 6); x1 = min(w - 1, x0 + rng.randint(4, 8))
            door = rng.randint(x0, x1)
            for x in range(x0, x1 + 1):
                if x != door: put(x, cz, hz)
    for _ in range(rng.randint(1, 3)):                    # diagonal barriers (the hardest graze geometry)
        hz = rng.random() < 0.25
        sx = rng.randint(2, w - 6); sz = rng.randint(2, h - 6); ln = rng.randint(3, 7); dz = rng.choice((1, -1))
        for i in range(ln):
            put(sx + i, sz + i * dz, hz)
    return field(w, h, blocked=sorted(blocked), hazard=sorted(hazard))

def random_stress(n_maps=600, n_sim=100, seed=7, gen=None, name="random-terrain",
                  note="random 22x22 fields, 6-14% obstacles, 25% of them hazard; goal corners kept clear"):
    """
    ROBUSTNESS / anti-cherry-pick: instead of 8 curated scenarios, throw hundreds of random obstacle+hazard fields at
    the smoother. For every map with a valid A* route we assert (load-bearing, pure geometry, no sim needed) that every
    string-pulled segment is los-safe and crosses NO hazard — a single violation would mean the swept-cell supercover
    has a hole a body could clip a wall / lava through. We also assert the smoothed node set never GROWS and the
    smoothed polyline is never LONGER than the lattice (string-pull must only ever shorten). A subset is walked through
    the full physics+steering A/B to confirm the jerk/length gains generalise beyond the hand-built cases.
    `gen` selects the obstacle topology (default = blobby random cells; pass _structured_grid for walls+diagonals).
    """
    import random
    rng = random.Random(seed)
    W, H = 22, 22
    valid = safety_violations = length_regressions = node_regressions = cov_regressions = 0
    attempts = 0
    ab_len, ab_vdj, ab_covL, ab_covS = [], [], [], []
    while valid < n_maps and attempts < n_maps * 8:
        attempts += 1
        grid = gen(rng, W, H) if gen else _random_grid(rng, W, H, density=rng.uniform(0.06, 0.14), hazard_frac=0.25)
        start, goal = (0, 0), (W - 1, H - 1)
        lat = astar(grid, start, goal)
        if len(lat) < 4 or lat[-1] != goal:      # no route on this map
            continue
        valid += 1
        sm = string_pull(grid, lat)
        # SAFETY (the assertion that matters): every smoothed segment body-safe + hazard-free
        seg_safe = all(los(grid, sm[k], sm[k + 1]) for k in range(len(sm) - 1))
        # independent hazard cross-check via the exact swept-cell set (a finer step than the old los would have missed)
        haz = any(crosses_hazard(grid, cx, cz)
                  for k in range(len(sm) - 1) for cx, cz in swept_cells(sm[k], sm[k + 1], HALF))
        if not seg_safe or haz:
            safety_violations += 1
        if length(sm) > length(lat) + 1e-6:
            length_regressions += 1
        if len(sm) > len(lat):
            node_regressions += 1
        if valid <= n_sim:
            tl = navsim.simulate(centers_to_nodes(lat), navsim.DEPLOYED, seed=1000 + valid)
            ts = navsim.simulate(centers_to_nodes(sm), navsim.DEPLOYED, seed=1000 + valid)
            ab_len.append((walked_length(tl), walked_length(ts)))
            ab_vdj.append((analyze.smoothness(tl)['veldir_jump_rms'], analyze.smoothness(ts)['veldir_jump_rms']))
            ab_covL.append(tl.coverage); ab_covS.append(ts.coverage)
            if ts.coverage < tl.coverage - 0.05:   # smoothing must not strand the pursuit vs the lattice baseline
                cov_regressions += 1
    tLatL = sum(a for a, _ in ab_len); tLatS = sum(b for _, b in ab_len)
    mVdjL, mVdjS = _mean([a for a, _ in ab_vdj]), _mean([b for _, b in ab_vdj])
    red = lambda a, b: (100 * (1 - b / a)) if a > 1e-9 else 0.0
    print("\n== %s stress: %d valid maps (of %d attempts), %d walked through A/B ==" % (name, valid, attempts, min(valid, n_sim)))
    print("  SAFETY   unsafe/hazard-crossing smoothed paths : %d   (MUST be 0)" % safety_violations)
    print("  MONOTONE smoothed longer than lattice          : %d   (MUST be 0)" % length_regressions)
    print("  MONOTONE smoothed has more nodes than lattice  : %d   (MUST be 0)" % node_regressions)
    print("  COVERAGE smoothed stranded vs lattice (>5%% drop): %d   (MUST be 0)" % cov_regressions)
    print("  A/B      walked-length %+.0f%% | velDir-jerk %+.0f%% | coverage lat %.0f%% -> sm %.0f%%"
          % (red(tLatL, tLatS), red(mVdjL, mVdjS), _mean(ab_covL) * 100, _mean(ab_covS) * 100))
    print("  (%s)" % note)
    return safety_violations + length_regressions + node_regressions + cov_regressions

if __name__ == '__main__':
    run()
    ab_compare()
    margin_sweep()
    random_stress()
    random_stress(seed=13, gen=_structured_grid, name="structured-terrain",
                  note="walls with doorways + diagonal barriers, 25% hazard; the tangent/parallel-edge stress")
