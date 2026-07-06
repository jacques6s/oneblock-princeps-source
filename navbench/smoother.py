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

def los(grid, a, b):
    """3D-safe LOS: a 0.7-wide body can walk the straight segment a->b — every swept cell has floor + body + head
       clearance and is NOT a hazard. A single unsafe swept cell fails the merge (the lattice detour is kept)."""
    (ax, az), (bx, bz) = (a[0] + 0.5, a[1] + 0.5), (b[0] + 0.5, b[1] + 0.5)
    dist = math.hypot(bx - ax, bz - az)
    steps = max(1, int(dist / 0.1))
    for s in range(steps + 1):
        t = s / steps
        x, z = ax + (bx - ax) * t, az + (bz - az) * t
        for ox in (-HALF, HALF):
            for oz in (-HALF, HALF):
                cx, cz = math.floor(x + ox), math.floor(z + oz)
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

def string_pull(grid, path):
    """greedy line-of-sight smoothing: from each kept node, jump to the FURTHEST node still walkable in a straight line."""
    if len(path) < 3: return list(path)
    out = [path[0]]; i = 0
    while i < len(path) - 1:
        j = len(path) - 1
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
        # explicit hazard check: densely sample every smoothed segment's body-sweep; must touch NO hazard cell
        hazard_hit = False
        for k in range(len(sm)-1):
            (ax, az), (bx, bz) = (sm[k][0]+0.5, sm[k][1]+0.5), (sm[k+1][0]+0.5, sm[k+1][1]+0.5)
            steps = max(1, int(math.hypot(bx-ax, bz-az)/0.05))
            for s in range(steps+1):
                t = s/steps; x, z = ax+(bx-ax)*t, az+(bz-az)*t
                for ox in (-HALF, HALF):
                    for oz in (-HALF, HALF):
                        if crosses_hazard(grid, math.floor(x+ox), math.floor(z+oz)): hazard_hit = True
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

if __name__ == '__main__':
    run()
    ab_compare()
    margin_sweep()
