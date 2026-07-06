"""navbench — scenario library. Node lists (block x,z) for the full test matrix the brief lists."""
import random

def straight(n=30): return [(i, 0) for i in range(n)]
def corner90():     return [(i, 0) for i in range(15)] + [(14, z) for z in range(1, 15)]
def s_curve():      return ([(i, 0) for i in range(8)] + [(7, z) for z in range(1, 7)]
                            + [(x, 6) for x in range(8, 16)] + [(15, z) for z in range(5, -1, -1)]
                            + [(x, 0) for x in range(16, 24)])
def corridor():     return corner90()   # analyzer treats walls separately; geometry is the tight corner
def diag():         # pure 45-degree run (MovementDiagonal territory)
    out = [(0, 0)]; x = z = 0
    for _ in range(20): x += 1; z += 1; out.append((x, z))
    return out
def zig_spike():    # hardest corner-cut worst case
    out = [(0, 0)]; x = z = 0
    for i in range(20):
        x += 1; out.append((x, z)); z += 1 if i % 2 == 0 else -1; out.append((x, z))
    return out
def long_random(seed=7):
    rng = random.Random(seed); x = z = 0; out = [(0, 0)]; seen = {(0, 0)}; d = (1, 0)
    while len(out) < 45:
        opts = [(1,0),(-1,0),(0,1),(0,-1)]; opts.remove((-d[0], -d[1]))
        (rng.shuffle(opts) if rng.random() < 0.35 else opts.sort(key=lambda o: o != d))
        for o in opts:
            nx, nz = x+o[0], z+o[1]
            if (nx, nz) not in seen: d = o; x, z = nx, nz; out.append((x, z)); seen.add((x, z)); break
        else: break
    return out

# name -> (nodes, flip_at). flip_at models an abrupt target change (retarget) mid-run.
def matrix():
    return [
        ("straight",   straight(),        None),
        ("corner90",   corner90(),        None),
        ("s_curve",    s_curve(),         None),
        ("corridor",   corridor(),        None),
        ("diagonal",   diag(),            None),
        ("zig_spike",  zig_spike(),       None),
        ("abrupt_flip", straight(),       40),      # abrupter Zielwechsel
        ("long_random", long_random(),    None),
    ]
