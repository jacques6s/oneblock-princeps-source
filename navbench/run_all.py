"""
navbench — single-command suite runner: one PASS/FAIL line per suite, exit code 0 only if ALL pass.

The bench has grown to seven entry points; this is the cheap regression guard to run after ANY navigation
change (and before every deploy):  python run_all.py [--quick]

  bench                steering/look pipeline: coverage, packets, smoothness, patterns
  smoother             any-angle geometry: curated scenarios + A/B physics + margin sweep + 1200-map stress
  executor_sim         executor state machine on smoothed paths (300 maps, recursion-guard counted)
  aim_curve_sim        bell-curve mining aim envelope (ceiling, accel, bell shape, jitter breathing)
  mining_seq_sim       60-block mining session pattern audit (peak, accel, periodicity, const-run)
  descent_potential    (info only, no pass/fail semantics — prints potential numbers)
  descent_executor_sim descent-chord 2.5D executor feasibility (150 maps)

--quick skips the two slowest suites (smoother stress + descent feasibility) for tight inner loops.
"""
import io
import contextlib
import re
import sys
import time


def _drive(mod):
    """suite-specific full entry sequence (some suites keep their full run behind __main__ only)."""
    name = mod.__name__
    if name == 'bench':
        import navsim
        mod.report(navsim.DEPLOYED)
    elif name == 'smoother':
        mod.run()
        mod.ab_compare()
        mod.margin_sweep()
        mod.random_stress()
        mod.random_stress(seed=13, gen=mod._structured_grid, name="structured-terrain", note="walls+diagonals")
    elif hasattr(mod, 'main'):
        mod.main()
    elif hasattr(mod, 'run'):
        mod.run()


def run(name, checks, info_only=False):
    t0 = time.time()
    buf = io.StringIO()
    ok = True
    err = None
    try:
        with contextlib.redirect_stdout(buf):
            _drive(__import__(name))
    except SystemExit:
        pass
    except Exception as e:  # noqa: BLE001 — a crashing suite is a FAIL, whatever the exception
        ok, err = False, repr(e)
    out = buf.getvalue()
    if ok and not info_only:
        for pattern, must_match in checks:
            found = re.search(pattern, out) is not None
            if found != must_match:
                ok = False
                err = ('missing ' if must_match else 'present ') + repr(pattern)
                break
    status = 'INFO' if info_only and ok else ('PASS' if ok else 'FAIL')
    print(f'{status:4s}  {name:22s} {time.time()-t0:6.1f}s' + (f'   <- {err}' if err else ''))
    return ok or info_only


def main():
    quick = '--quick' in sys.argv
    suites = [
        ('bench', [(r'packets: nonQuant=0 snaps=0 overspeed=0', True),
                   (r'minCov=100%', True)], False),
        ('aim_curve_sim', [(r'ALL-OK: True', True)], False),
        ('mining_seq_sim', [(r'const-run <= 12\): True', True)], False),
        ('executor_sim', [(r'smoothed fails: 0', True),
                          (r'FAIL', False)], False),
    ]
    if not quick:
        suites += [
            ('smoother', [(r'SAFETY   unsafe/hazard-crossing smoothed paths : 0', True),
                          (r'HAZARD!', False)], False),
            ('descent_executor_sim', [(r'completed: 150/150', True)], False),
            ('descent_potential', [], True),
        ]
    print('navbench suite' + (' (--quick)' if quick else '') + ':')
    results = [run(*s) for s in suites]
    all_ok = all(results)
    print('ALL SUITES:', 'PASS' if all_ok else 'FAIL')
    sys.exit(0 if all_ok else 1)


if __name__ == '__main__':
    main()
