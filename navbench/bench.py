"""
navbench — runner. Runs the full scenario x profile x seed matrix, aggregates every metric,
prints a report, and computes composite scores so smoothness / human-likeness / stability can be
optimized and regression-checked. Run:  python navbench/bench.py  [deployed|intended|both]

Composite scores (0..100, higher = better):
  SMOOTHNESS  penalizes velocity-direction jumps (octant discontinuity), position/angular/speed jerk
  PATTERN     penalizes long constant-rate runs, strong yaw-rate autocorrelation, strong periodicity, A/D chatter
  PACKET      hard gate: any non-quantized or superhuman-snap or overspeed tick = fail
  STABILITY   coverage + cross-track bound across seeds
"""
import sys, statistics
import navsim, analyze, scenarios

SEEDS = list(range(12))

def run_profile(profile):
    mc = navsim.min_count(profile.sensitivity)
    rows = []
    for name, nodes, flip in scenarios.matrix():
        traces = [navsim.simulate(list(nodes), profile, seed=1000+s, flip_at=flip) for s in SEEDS]
        sm = _avg([analyze.smoothness(t) for t in traces])
        pt = _avg([analyze.patterns(t) for t in traces])
        pk = _sum([analyze.packets(t, mc) for t in traces])
        st = analyze.stability(traces)
        rows.append((name, sm, pt, pk, st))
    return rows, mc

def _avg(ds):
    keys = ds[0].keys(); return {k: statistics.mean(d[k] for d in ds) for k in keys}
def _sum(ds):
    keys = ds[0].keys(); return {k: sum(d[k] for d in ds) for k in keys}

def scores(rows):
    # aggregate worst/representative values across scenarios
    veldir = max(r[1]['veldir_jump_max'] for r in rows)
    posjerk = max(r[1]['pos_jerk_rms'] for r in rows)
    angjerk = max(r[1]['ang_jerk_rms'] for r in rows)
    construn = max(r[2]['max_const_run'] for r in rows)
    autoc = max(r[2]['yawrate_autocorr_peak'] for r in rows)
    chatter = max(r[2]['octant_toggle_pct'] for r in rows)
    nonq = sum(r[3]['nonquant_ticks'] for r in rows)
    snaps = sum(r[3]['snap_ticks'] for r in rows)
    over = sum(r[3]['overspeed_ticks'] for r in rows)
    mincov = min(r[4]['min_coverage'] for r in rows)
    maxxt = max(r[4]['max_crosstrack'] for r in rows)
    # scoring (tuned so the current system lands mid-range, leaving room to show improvement)
    smooth = max(0, 100 - veldir*1.1 - posjerk*220 - angjerk*3.0)
    pattern = max(0, 100 - max(0, construn-6)*4 - autoc*60 - chatter*1.2)
    packet = 100 if (nonq == 0 and snaps == 0 and over == 0) else 0
    stab = max(0, 100 - (1-mincov)*400 - max(0, maxxt-0.4)*120)
    return dict(smoothness=smooth, pattern=pattern, packet=packet, stability=stab,
                _veldir_max=veldir, _pos_jerk=posjerk, _ang_jerk=angjerk, _const_run=construn,
                _autocorr=autoc, _chatter=chatter, _nonq=nonq, _snaps=snaps, _over=over,
                _min_cov=mincov, _max_xt=maxxt)

def report(profile):
    rows, mc = run_profile(profile)
    print(f"\n================ PROFILE: {profile.name}  (mouse count = {mc:.4f} deg) ================")
    print(f"{'scenario':11s} {'velDirJump':>10s} {'posJerkRMS':>10s} {'angJerkRMS':>10s} "
          f"{'constRun':>8s} {'autoCorr':>8s} {'chatter%':>8s} {'cov':>5s} {'maxXT':>6s}")
    for name, sm, pt, pk, st in rows:
        print(f"{name:11s} {sm['veldir_jump_max']:10.2f} {sm['pos_jerk_rms']:10.4f} {sm['ang_jerk_rms']:10.2f} "
              f"{pt['max_const_run']:8.0f} {pt['yawrate_autocorr_peak']:8.2f} {pt['octant_toggle_pct']:8.1f} "
              f"{st['min_coverage']:5.0%} {st['max_crosstrack']:6.2f}")
    sc = scores(rows)
    print(f"\n  SCORES  smoothness={sc['smoothness']:5.1f}  pattern={sc['pattern']:5.1f}  "
          f"packet={sc['packet']:3.0f}  stability={sc['stability']:5.1f}")
    print(f"  drivers: velDirJumpMax={sc['_veldir_max']:.1f}deg  posJerkRMS={sc['_pos_jerk']:.4f}  "
          f"angJerkRMS={sc['_ang_jerk']:.2f}  constRun={sc['_const_run']:.0f}  autoCorr={sc['_autocorr']:.2f}  "
          f"chatter={sc['_chatter']:.1f}%")
    print(f"  packets: nonQuant={sc['_nonq']} snaps={sc['_snaps']} overspeed={sc['_over']}  "
          f"| stability: minCov={sc['_min_cov']:.0%} maxXT={sc['_max_xt']:.2f}")
    return sc

if __name__ == '__main__':
    which = sys.argv[1] if len(sys.argv) > 1 else 'both'
    profs = {'deployed': [navsim.DEPLOYED], 'intended': [navsim.INTENDED],
             'both': [navsim.DEPLOYED, navsim.INTENDED]}[which]
    for p in profs:
        report(p)
