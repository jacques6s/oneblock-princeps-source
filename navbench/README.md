# navbench — Princeps navigation test bench

A high-fidelity, dependency-free (pure-stdlib Python) simulator + analyzer for the Princeps
ground navigation/look pipeline. It replays the exact chain the mod runs each tick and records
**every sent packet, body coordinate, and view direction**, so the navigation can be read,
tested for patterns, measured for smoothness/human-likeness, optimized, and regression-checked
without launching the game.

## Run

```
python navbench/bench.py            # full scenario x profile matrix + scores
python navbench/bench.py deployed   # one profile
```

## What it models (faithfully)

`navsim.py` replays, per tick, with `freeLook=false` (movement direction == applied yaw):

```
path -> pursuit steer (far-carrot gaze + near-carrot W/A/D octant, engage/release hysteresis
     +  boundary hysteresis on the octant)
     -> ballistic proportional turn toward the gaze  (LookBehavior.proportionalStep)
     -> + fixation/saccade wander + micro-tremor      (profile-gated; 0 in the "clean" profile)
     -> hard turn-envelope cap (70 deg/tick)
     -> integer mouse-count quantization
     -> MC ground physics:  v += accel*dir ; pos += v ; v *= friction   (real travel() order,
        so sprint steady state ~0.2863 b/t and the acceleration ramp are exact — this matters:
        a coarser physics model hides real velocity discontinuities)
```

Two profiles mirror the two live look tunings: `DEPLOYED` (turn 55/8/0.55 + wander/tremor) and
`INTENDED` (24/1.6/0.22, clean). The shipped octant boundary-hysteresis (20 deg) is on by default.

## What it measures (`analyze.py`)

* **SMOOTHNESS** — position jerk (3rd diff), **velocity-direction discontinuity** (the octant-switch
  jump, speed-gated so near-zero-velocity start/stop noise isn't miscounted), angular jerk, speed jerk.
* **PATTERNS** — yaw-rate autocorrelation, dominant spectral period (DFT), identical-value run length,
  octant-usage entropy, and the A/D **toggle rate (chatter)**.
* **PACKETS** — mouse-count quantization validity, superhuman-snap (>70 deg/tick), speed sanity.
* **STABILITY** — node coverage, cross-track bound, completion — aggregated across seeds.

## Scenarios (`scenarios.py`)

straight · sharp corner · S-curve · tight corridor · diagonal · 1-block zig-zag (worst case) ·
abrupt target flip (retarget) · long random winding path. Water-mining rotation is covered
separately in `../` `water_mining_sim.py` / elytra quantization in `elytra_sim.py`.

## Key findings (what the bench proved)

1. **With the honest speed-gated metric, the movement is already smooth**: straight/diagonal ~1 deg
   velocity-direction change, corners ~28 deg (inherent cornering geometry — a human does the same),
   low chatter on realistic paths. The scary raw "124 deg jerk" on the abrupt flip was a near-zero-speed
   direction artifact (1.6 deg at speed).
2. **The octant strafe is load-bearing for coverage.** Smoother alternatives (crosstrack-only,
   forward-diagonal cap, curvature slow-in) all collapse node coverage — the aggressive strafe is what
   keeps the walked track on every path node.
3. **The one clean, coverage-safe win: octant boundary hysteresis.** Keeping the held W/A/D combo unless
   another is clearly better cuts input chatter ~22-38% on realistic winding paths (corner 3.4->2.1%,
   s-curve 8.7->6.8%, long 19.1->13.9%) with node coverage unchanged and packets clean. Shipped as
   `humanizedSteeringHysteresis` (MovementHelper.strafeToward).
