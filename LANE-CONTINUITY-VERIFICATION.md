# Builder lane continuity correction

Base: `1581062e4c18904d61c74eeec6ef92df017e31e1`; branch `codex/builder-lane-probe-continuity`.
This is an isolated correction of the real BREAK runtime regression. No runtime has been published or tested in a
game from this worktree. The unfinished cleanup escape implementation remains in a different, frozen worktree.

## Real failure

The stopped normal Client220 BREAK run `towers68-client220-break-20260906-01` ended at 43/272 correct,
229 missing, zero wrong. At relative tick2000, the earlier b12/context/BREAK runs had 99/101/43 reported correct cells
(the last two samples were 13 ticks old). An end-only comparison also showed the regression: 124/144/43.

At T698, the selected placement target's A probe returned bounded NONE; B returned COMPLETE at T699. A dirt
helper landed at T711. Its new opportunistic cleanup fallback changed the whole outgoing JankyGoalComposite,
invalidating the accepted placement proof. The following A completions could reach that cleanup fallback rather
than the placement stance. From T725, placement recovery returned early on every tick without servicing the lane
driver. There were no further LANE events after T722 through the terminal T2137. The world census confirmed that
both repeated recovery stances had AIR where their supporting floor would need to be.

## Correction and boundary

All new ordinary and recovery routes use `finishBuilderRoute`. The shadow question is the elected work goal for
the ordinary route, or the exact selected stance for recovery; the router retains its full outgoing navigation goal.
Opportunistic cleanup does not change that work question. A genuinely different work/stance/model still invalidates
it. The shared finalizer services the existing driver, then selects the current lane context in the same tick.
Unsafe airborne recovery continues the existing route context through the already-tested continuation method.

The existing planned-stance routing section was extracted without changing its lock, distance, centering or
rejection rules. `build/lane-continuity-evidence/extraction-audit.json` verifies against the base that the material,
ownership and body preflight, planned-stance body and alternative search are identical apart from the specified
route returns. The trace formatting now checks existing `BuildTrace.isActive()` before formatting the goal; an
inactive trace no longer invokes the goal's global-settings formatter. Active trace content is unchanged.

No AStar, negative-evidence, permissions, search budgets, material handling, goal election, movement costs or
scaffold ownership rules changed. No extra target-selection scan or framework was introduced.

## Verification on 2026-09-06

JDK `C:/Users/jacqu/tools-jdk25/jdk-25.0.3+9`, isolated GradleUserHome
`C:/Users/jacqu/Desktop/Princeps-codex-break-contract/.gradle-user-break-full`, new project cache:

```text
gradlew.bat --project-cache-dir .gradle-lane-continuity --no-build-cache test --tests princeps.process.BuilderLaneEvidenceTest --tests princeps.process.BuilderRouteContextTest --tests princeps.process.BuilderBreakContractTest --console=plain
```

Final targeted GREEN: **36 tests, 36 passed, zero failures/errors/skips**, 14 seconds. `test` executed; this is not a
cached test report. Six new lane-continuity tests use the actual lane driver, real GoalBlock equality, actual
PlacementTargetLock.routeTick, concrete recovery return and scaffold ledger server confirmation. They cover the
full A=NONE → B=COMPLETE → own helper landing → expanded cleanup → recovery chain and changed stance/work/model,
stale world revision and ERROR/UNKNOWN countercases. Existing break and context tests also pass. The context-call
coverage now includes the fourth, unsafe recovery continuation.

Controlled RED runs, with the exact final source restored afterward:

* Restore only the old direct planned-recovery PCC return: 16 tests, **5 assertion failures**, zero errors;
  `recovery-actual-goals-red.xml`, SHA256 `d1b89002a63ba81ca8c4ea9481e143e185446de333a407e157a14e2249a498f2`.
* Wire the shadow question back to the full outgoing route: 16 tests, **1 assertion failure**, zero errors;
  `fallback-mutant-red.xml`, SHA256 `ca95f0d2b25dffaca8b48ed1c8b61f17819de9c8c2a3c3baeaecaac303080ddc`.

An earlier exploratory fixture used a QuietGoal subclass, which does not equal a real GoalBlock. That fixture was
replaced for these new tests; it is not the final correctness evidence. The initial RED error-formatting run also
triggered the unavailable global API through JUnit's object formatting; the clean RED evidence above avoids that.

## Explicit integration limits

These are production decision-path regressions, not headless Minecraft physics tests. Worker results are delivered
at the real PathProbe request boundary; the tests do not launch actual AStar workers. Real builder-context snapshots
are allocated and populated without a Minecraft singleton; matching contexts are reused by the finalizer. A normal
client must still verify actual A-to-B context construction, path calculation, full material/body preflight and
movement. The fallback mutant intentionally asserts the lost work proof before the unavailable fresh live-context
constructor can obscure that causal failure. No API provider or global settings shadow is installed.

Before acceptance: independent source review, fresh full suite with the same Basalt fixture, jar/API comparison,
then the normal licensed Client220 isolated68 comparison with the same setup. Compare correct cells at matching
tick windows, terminal counts and a complete world oracle. Passing these unit tests alone does not establish a
recovered game build or resolve the separate bounded cleanup descent problem.
