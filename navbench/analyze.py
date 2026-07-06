"""
navbench — analyzer suite. Reads a Trace (every sent packet, coordinate, view direction) and
measures the four things the bench exists to guarantee:

  SMOOTHNESS   position jerk, velocity-direction discontinuity (octant switches!), angular jerk, speed jerk
  PATTERNS     yaw-rate autocorrelation, dominant spectral period, identical-value run length, octant entropy
  PACKETS      mouse-count quantization validity, superhuman-snap check, speed sanity
  STABILITY    coverage, cross-track bound, divergence — aggregated across seeds

All pure-stdlib (no numpy) so it runs anywhere. Returns dicts; bench.py turns them into a report + scores.
"""
import math

def _diff(xs):
    return [xs[i+1] - xs[i] for i in range(len(xs)-1)]

def smoothness(tr):
    r = tr.records
    if len(r) < 5:
        return dict(pos_jerk_rms=0, pos_jerk_max=0, veldir_jump_max=0, veldir_jump_rms=0,
                    ang_jerk_rms=0, ang_jerk_max=0, speed_jerk_rms=0)
    xs = [q.x for q in r]; zs = [q.z for q in r]
    # position jerk = 3rd difference of position (per tick^3)
    jx = _diff(_diff(_diff(xs))); jz = _diff(_diff(_diff(zs)))
    pj = [math.hypot(a, b) for a, b in zip(jx, jz)]
    # velocity DIRECTION change per tick (deg) — the octant-switch discontinuity. Only counted while genuinely
    # moving (both endpoints above a speed floor): the direction of a near-zero velocity vector is ill-defined, so
    # counting it would report meaningless "jerk" at start/stop/reversal that no observer could see.
    SPEED_FLOOR = 0.12
    dirs = [math.degrees(math.atan2(-q.vx, q.vz)) for q in r]
    vdj = [abs((dirs[i+1]-dirs[i]+180) % 360 - 180)
           for i in range(len(r)-1) if r[i].speed > SPEED_FLOOR and r[i+1].speed > SPEED_FLOOR]
    # angular jerk of the SENT yaw (3rd diff)
    ys = [q.yaw for q in r]
    ay = _diff(_diff(_diff([_unwrap(ys)][0])))
    # speed jerk (2nd diff of speed)
    sp = [q.speed for q in r]; sj = _diff(_diff(sp))
    return dict(
        pos_jerk_rms=_rms(pj), pos_jerk_max=max(pj, default=0),
        veldir_jump_max=max(vdj, default=0), veldir_jump_rms=_rms(vdj),
        ang_jerk_rms=_rms(ay), ang_jerk_max=max((abs(x) for x in ay), default=0),
        speed_jerk_rms=_rms(sj))

def _unwrap(ys):
    out = [ys[0]]; off = 0.0
    for i in range(1, len(ys)):
        d = ys[i] - ys[i-1]
        if d > 180: off -= 360
        elif d < -180: off += 360
        out.append(ys[i] + off)
    return out

def _rms(xs):
    return math.sqrt(sum(x*x for x in xs)/len(xs)) if xs else 0.0

def patterns(tr):
    r = tr.records
    dyaw = [q.dyaw for q in r]
    # identical quantized-value run length (robotic constant-rate tell)
    best = run = 0; last = None
    for d in dyaw:
        if abs(d) > 1e-9 and last is not None and abs(d-last) < 1e-6: run += 1; best = max(best, run)
        else: run = 0
        last = d
    # autocorrelation of yaw-rate: peak (excluding lag0) => periodicity
    ac_peak, ac_lag = _autocorr_peak(dyaw, maxlag=min(60, len(dyaw)//2))
    # dominant spectral period of the cross-track (weaving periodicity)
    per, power = _dominant_period([q.cross for q in r])
    # octant usage entropy + toggle rate (A/D chatter)
    octs = [q.octant for q in r]
    ent = _entropy(octs)
    toggles = sum(1 for a, b in zip(octs, octs[1:]) if a != b)
    return dict(max_const_run=best+1, yawrate_autocorr_peak=ac_peak, autocorr_lag=ac_lag,
                crosstrack_period=per, crosstrack_power=power, octant_entropy=ent,
                octant_toggle_pct=100.0*toggles/max(1, len(octs)-1))

def _autocorr_peak(xs, maxlag):
    n = len(xs)
    if n < 8: return 0.0, 0
    m = sum(xs)/n; var = sum((x-m)**2 for x in xs) or 1e-9
    best, blag = 0.0, 0
    for lag in range(3, maxlag):
        c = sum((xs[i]-m)*(xs[i+lag]-m) for i in range(n-lag)) / var / (n-lag)
        if c > best: best, blag = c, lag
    return best, blag

def _dominant_period(xs):
    n = len(xs)
    if n < 16: return 0, 0.0
    m = sum(xs)/n; xs = [x-m for x in xs]
    best_p, best_pow = 0, 0.0
    for period in range(4, n//2):
        w = 2*math.pi/period
        re = sum(x*math.cos(w*i) for i, x in enumerate(xs))
        im = sum(x*math.sin(w*i) for i, x in enumerate(xs))
        p = (re*re+im*im)/n
        if p > best_pow: best_pow, best_p = p, period
    return best_p, best_pow

def _entropy(vals):
    from collections import Counter
    c = Counter(vals); n = len(vals)
    return -sum((k/n)*math.log2(k/n) for k in c.values()) if n else 0.0

def packets(tr, mc):
    r = tr.records
    nonquant = sum(1 for q in r if abs(q.dyaw) > 1e-9 and abs(round(q.dyaw/mc)*mc - q.dyaw) > 1e-4)
    snaps = sum(1 for q in r if abs(q.dyaw) > 70.0 + 1e-6)
    overspeed = sum(1 for q in r if q.speed > 0.36)   # sprint steady ~0.286; >0.36 impossible on flat ground
    return dict(nonquant_ticks=nonquant, snap_ticks=snaps, overspeed_ticks=overspeed, sent_ticks=len(r))

def stability(traces):
    covs = [t.coverage for t in traces]
    maxxt = max((abs(q.cross) for t in traces for q in t.records), default=0)
    done = sum(1 for t in traces if t.done)
    return dict(min_coverage=min(covs, default=0), mean_coverage=sum(covs)/len(covs) if covs else 0,
                max_crosstrack=maxxt, completed=done, runs=len(traces))
