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

HALF = 0.35   # player half-width + small margin (cells the body sweeps must be clear)

def walkable(grid, cx, cz):
    return 0 <= cx < grid['w'] and 0 <= cz < grid['h'] and (cx, cz) not in grid['blocked']

def los(grid, a, b):
    """can a 0.7-wide body walk the straight segment a->b entirely on walkable cells?"""
    (ax, az), (bx, bz) = (a[0] + 0.5, a[1] + 0.5), (b[0] + 0.5, b[1] + 0.5)
    dist = math.hypot(bx - ax, bz - az)
    steps = max(1, int(dist / 0.1))
    for s in range(steps + 1):
        t = s / steps
        x, z = ax + (bx - ax) * t, az + (bz - az) * t
        for ox in (-HALF, HALF):
            for oz in (-HALF, HALF):
                if not walkable(grid, math.floor(x + ox), math.floor(z + oz)):
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

# ---- scenarios: (name, grid, start, goal) ; grid = open field minus 'blocked' cells ----
def field(w, h, blocked=()):
    return {'w': w, 'h': h, 'blocked': set(blocked)}

def wall(x, z0, z1):  # vertical wall segment
    return [(x, z) for z in range(z0, z1)]

def scenarios():
    return [
        ("open_offaxis", field(16, 8), (0, 0), (15, 4)),        # shallow off-axis line across open ground
        ("open_steep",   field(10, 14), (0, 0), (3, 13)),       # steep off-axis
        ("corner_obst",  field(12, 12, wall(6, 0, 9)), (0, 4), (11, 4)),   # wall forces a detour; cut it where clear
        ("gap_slalom",   field(20, 9, wall(7, 0, 6) + wall(13, 3, 9)), (0, 4), (19, 4)),  # slalom around two walls
        ("diagonal45",   field(12, 12), (0, 0), (10, 10)),      # already 45 — smoother should barely change it
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
        # followability: feed the smoothed polyline to the real pursuit sim (deployed profile)
        tr = navsim.simulate(list(sm), navsim.DEPLOYED, seed=1000)
        follow = "cov%d%% xt%.2f" % (round(tr.coverage*100), max((abs(q.cross) for q in tr.records), default=0))
        print(f"{name:13s} {len(lat):8d} {len(sm):7d} {ll:7.1f} {sl:6.1f} {100*(sl/ll-1):+4.0f}% "
              f"{lt:8d} {st:7d} {lh:7.0f} {sh:6.0f} {str(safe):>4s} {follow:>7s}")

if __name__ == '__main__':
    run()
