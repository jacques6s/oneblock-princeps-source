"""
navbench — DESCENT-CHORD potential quantification (measurement only, no design commitment).

The shipped any-angle smoother merges FLAT obstacle-free runs only; every MovementDescend/Ascend terminates a
run. On rolling terrain (the user's live terrain: y87 -> y72 over 150 blocks) paths are Traverse/Descend chains,
so the flat merger barely engages. Before ANY strand-critical core work on 3D chords, this answers the value
question: how much of a rolling-terrain path is descent-mergeable, and what would merging buy (node/turn/length
reduction) compared to what the flat merger already gets?

Model: 2.5D heightmap (smooth rolling hills via summed sines + noise), MC-like A* moves (flat 4+4 dir with the
usual no-corner-squeeze rule, +-1 step up/down — up allowed since we measure generic travel, cost-weighted like
WALK/SPRINT). Merge rules compared:
  FLAT   : chebyshev>1 runs at constant y whose XZ-line stays on walkable equal-height cells (ships today)
  DESCENT: runs with MONOTONE non-increasing y, drop <= 1 per XZ-block, whose XZ-line cells match the run's
           height profile within +-0 (each swept column's ground == the profile height where the line crosses it)
This is geometry-only (no pursuit/executor sim): it measures POTENTIAL, honestly labeled as such.
"""
import math, random, io, contextlib
with contextlib.redirect_stdout(io.StringIO()):
    import smoother as S

def heightmap(rng, w, h):
    a1, a2 = rng.uniform(0.06, 0.12), rng.uniform(0.10, 0.22)
    p1, p2 = rng.uniform(0, 6), rng.uniform(0, 6)
    amp1, amp2 = rng.uniform(2, 5), rng.uniform(1, 3)
    H = {}
    for x in range(w):
        for z in range(h):
            v = amp1 * math.sin(a1 * x + p1) + amp2 * math.cos(a2 * z + p2) \
                + amp1 * 0.6 * math.sin(a2 * (x + z) * 0.7 + p2)
            H[(x, z)] = int(round(v))
    return H

def astar25(H, w, h, start, goal):
    import heapq
    def hh(p): return math.hypot(p[0]-goal[0], p[1]-goal[1])
    openq = [(hh(start), 0.0, start)]; came = {}; g = {start: 0.0}
    DIRS = [(1,0),(-1,0),(0,1),(0,-1),(1,1),(1,-1),(-1,1),(-1,-1)]
    while openq:
        _, gc, cur = heapq.heappop(openq)
        if cur == goal:
            path = [cur]
            while cur in came: cur = came[cur]; path.append(cur)
            return path[::-1]
        for dx, dz in DIRS:
            nb = (cur[0]+dx, cur[1]+dz)
            if not (0 <= nb[0] < w and 0 <= nb[1] < h): continue
            dy = H[nb] - H[cur]
            if abs(dy) > 1: continue                       # only step-1 transitions
            if dx and dz:
                m1, m2 = (cur[0]+dx, cur[1]), (cur[0], cur[1]+dz)
                if abs(H[m1]-H[cur]) > 1 or abs(H[m2]-H[cur]) > 1: continue
            step = math.hypot(dx, dz) * (1.0 if dy == 0 else 1.4)   # vertical transitions cost more (like MC)
            ng = gc + step
            if ng < g.get(nb, 1e18):
                g[nb] = ng; came[nb] = cur; heapq.heappush(openq, (ng + hh(nb), ng, nb))
    return [start]

def merge_run(H, path, i, mode):
    """longest j>i such that path[i..j] merges under `mode`; returns best j (or i)."""
    best = i
    yi = H[path[i]]
    for j in range(i+2, len(path)):
        seg = path[i:j+1]
        ys = [H[c] for c in seg]
        if mode == 'flat' and any(y != yi for y in ys): break
        if mode == 'descent':
            if any(ys[k+1] > ys[k] for k in range(len(ys)-1)): break     # monotone non-increasing
            if any(ys[k] - ys[k+1] > 1 for k in range(len(ys)-1)): break # gentle
        # XZ line-of-sight: each swept cell's ground must match the linear height profile position
        (ax, az), (bx, bz) = seg[0], seg[-1]
        L = math.hypot(bx-ax, bz-az)
        ok = True
        for cx, cz in S.swept_cells(seg[0], seg[-1], 0.65):
            if not (0 <= cx < 22 and 0 <= cz < 22): ok = False; break
            t = 0.0 if L < 1e-9 else max(0.0, min(1.0, ((cx+0.5-(ax+0.5))*(bx-ax) + (cz+0.5-(az+0.5))*(bz-az)) / (L*L)))
            # expected height at that point along the run's profile (nearest original node)
            k = min(range(len(seg)), key=lambda q: abs(q/(len(seg)-1) - t) if len(seg) > 1 else 0)
            if abs(H[(cx, cz)] - H[seg[k]]) > (0 if mode == 'flat' else 1):
                ok = False; break
        if not ok: break
        best = j
    return best

def string_pull_mode(H, path, mode):
    out = [path[0]]; i = 0
    while i < len(path)-1:
        j = merge_run(H, path, i, mode)
        if j <= i: j = i+1
        out.append(path[j]); i = j
    return out

def turns(poly):
    n = 0
    for k in range(1, len(poly)-1):
        h1 = math.atan2(poly[k][1]-poly[k-1][1], poly[k][0]-poly[k-1][0])
        h2 = math.atan2(poly[k+1][1]-poly[k][1], poly[k+1][0]-poly[k][0])
        if abs((math.degrees(h2-h1)+180) % 360 - 180) > 5: n += 1
    return n

def main():
    rng = random.Random(19)
    W = Hh = 22
    stats = {m: dict(nodes=0, turns=0, length=0.0) for m in ('lattice', 'flat', 'descent')}
    n_maps = 250
    for _ in range(n_maps):
        H = heightmap(rng, W, Hh)
        lat = astar25(H, W, Hh, (0, 0), (W-1, Hh-1))
        if len(lat) < 6 or lat[-1] != (W-1, Hh-1): continue
        polys = {'lattice': lat,
                 'flat': string_pull_mode(H, lat, 'flat'),
                 'descent': string_pull_mode(H, lat, 'descent')}
        for m2, poly in polys.items():
            stats[m2]['nodes'] += len(poly)
            stats[m2]['turns'] += turns(poly)
            stats[m2]['length'] += sum(math.hypot(poly[k+1][0]-poly[k][0], poly[k+1][1]-poly[k][1])
                                       for k in range(len(poly)-1))
    print("== descent-chord POTENTIAL on rolling terrain (%d maps, geometry only) ==" % n_maps)
    base = stats['lattice']
    print(f"{'mode':9s} {'nodes':>7s} {'turns':>7s} {'length':>9s} {'nodes-':>7s} {'turns-':>7s} {'len-':>6s}")
    for m2 in ('lattice', 'flat', 'descent'):
        s2 = stats[m2]
        print(f"{m2:9s} {s2['nodes']:7d} {s2['turns']:7d} {s2['length']:9.0f} "
              f"{100*(s2['nodes']/base['nodes']-1):+6.0f}% {100*(s2['turns']/base['turns']-1):+6.0f}% "
              f"{100*(s2['length']/base['length']-1):+5.1f}%")
    fl, de = stats['flat'], stats['descent']
    print()
    print("DELTA descent vs flat merger: nodes %+.0f%%, turns %+.0f%%, length %+.1f%%"
          % (100*(de['nodes']/fl['nodes']-1), 100*(de['turns']/fl['turns']-1), 100*(de['length']/fl['length']-1)))
    print("(geometry-only potential; executor/pursuit feasibility NOT included — that is the expensive part)")

if __name__ == '__main__':
    main()
