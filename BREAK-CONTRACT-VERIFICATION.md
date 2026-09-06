# BREAK target and probe evidence, 2026-09-06

Worktree `C:/Users/jacqu/Desktop/Princeps-codex-break-contract`, branch `codex/builder-break-contract`.
Base `f5cb391f3c8e4cb3268237d2b081de11a167c03d`. Root and independent State review accepted the final production patch.
No server, client, active JAR, release, account or existing runner was changed.

Pure removal formerly returned an unbound composite. It now owns one `electedCell` and retained goal until
the actual classification revokes it. An explicit BREAK flag keeps AIR out of the sticky-placement and chosen
hotbar-material branches. Confirmed/observed AIR does not remain a no-op removal. The existing geometric
GoalBreak and user fall/place/break settings are unchanged.

Lane evidence belongs to the actual goal, target goal, start, model/origin, world, row/cleanup phase, target state,
placeable block identities and observed progress revision. Material facing computed from current aim is not a
change of available material. Pending stale answers are discarded; a consumed valid lane
is retained while walking, not revoked merely by advancing to the next feet cell. ERROR, cancellation, timeout,
and **any** unknown chunk encountered during the search are unknown. Frontier exhaustion or the explicit node
budget can provide bounded negative evidence; this is not a mathematical claim that no route exists anywhere.
An owned cleanup helper does not recursively acquire generic helper permission. Its missing descent behavior is
specified separately in `CLEANUP-DESCENT-DESIGN.md`, and is not activated here.

The existing layer-mask body is extracted without changing its predicates and reused while its real schematic,
minimum/maximum layer and top-down mode remain identical. This preserves the actual context identity across
equivalent ticks while still invalidating a new model or layer band. Row start/frontier and the active scaffold
overlay also belong to lane evidence. The unknown-chunk marker is independent of the existing fetch-budget
counter, which deliberately does not count dynamic-XZ destinations.

`PathProbe.complete` and `cancel` share an identity-checked boundary. A cancelled worker cannot publish into or
clear a replacement request. AStar's only change records search-end evidence; it changes no costs, movements,
timeouts, budgets or generic/AutoDig path results. PathProbe has only one production owner in this source tree,
the BuilderProcess lane driver. Public API source and the freshly built API JAR are unchanged.

## Reproducible tests

JDK `C:/Users/jacqu/tools-jdk25/jdk-25.0.3+9`, dedicated project cache `.gradle-break-contract` in this worktree:

```powershell
./gradlew.bat --project-cache-dir .gradle-break-contract test `
  --tests princeps.process.BuilderBreakContractTest `
  --tests princeps.process.BuilderLaneEvidenceTest `
  --tests princeps.pathing.calc.PathProbeEvidenceTest --console=plain
```

Final verified fixture result: **27 tests, 27 passed, zero failures/errors/skips**.

- Original f5cb BuilderProcess with the same final Assembler fixture: seven tests, five expected failures.
  The missing ownership, changing goal, subsequent target selection, completed AIR and placement-path entry are
  reproduced by calls to the actual assembler/sticky method. The last fails on its forbidden placement-lock
  access; no live player/inventory or global settings provider is created.
- Controlled production mutant restoring the two old `outcome != COMPLETE` lane decisions, unconditional worker
  publication and missing unknown-chunk qualification: 15 tests, six plain AssertionError failures (two each for
  ERROR decisions, cancellation publication, and unknown-chunk evidence). Production sources were restored before
  final GREEN. This is a production mutant experiment, not a claim that the unchanged old API compiled the new tests.
- Review-correction mutant disabled real layer-mask reuse, restored full BlockState material comparison and
  disabled the independent unknown-chunk marker: 27 tests, exactly three plain AssertionError failures. Restoring
  the candidate then passed all 27 tests. Equivalent masks are exercised through the actual mask producer,
  assembler and pending lane driver; changed model/band/top-down mode invalidates that same driver request.
- Tests use real completed-worker handling, real lane-driver decisions, real request/context matching, and the
  actual AStar search-end method with an actual BinaryHeapOpenSet. They do not execute a full A* expansion or prove
  a successful in-game descent.

The actual common assembler runs in existing row-goal mode to avoid the live Princeps singleton. It demonstrably
retains the exact goal object across body movement; the normal GoalBreak's above-target exclusion has a separate
test. No provider/permission/settings shadow was introduced. Normal two-mod/in-game behavior remains a required
integration check.

Earlier exploratory fixtures had bootstrap/assertion-format problems and a BetterBlockPos-versus-BlockPos HashMap
key mismatch. Those logs are retained for audit but **are not correctness evidence**. Final fixtures use position
longs, explicitly assert the two supplied Dirt states, and include the AIR transition that exposed the mismatch.
Only `verified-fixture-*` and `review-corrections-*` logs/XML are correctness evidence. The latter final GREEN
contains all review corrections. A dynamic unknown destination is tested at the actual marker/end-method boundary;
this is not a claim that a headless A* movement expansion or full client tick ran.

Evidence: `build/break-contract-evidence/verified-fixture-original-red.{log,xml}`,
`verified-fixture-mutant-red.log` and its XML directory, `verified-fixture-green.log` and its XML directory,
`review-corrections-mutant-red.log` and its XML directory, `review-corrections-final-green.log` and its XML directory,
`targeted-summary.json`, `production.patch`, `sha256-manifest.json`.

The approved Basalt fixture was copied unchanged for the future full suite (12077 bytes,
SHA `202bab5dbb24f99f180db44faaa6ee9daecb949647d946e7294e9ba25f7414e4`).
## Fresh full verification and artifact freeze

On 2026-09-06, the full suite and both JARs were rebuilt with a new dedicated GradleUserHome
`.gradle-user-break-full`. Existing dependency/Wrapper/Loom caches were copied as ordinary files; no task-output
build cache or test reports were copied. The command forced fresh compilation and execution:

```powershell
$env:GRADLE_USER_HOME = 'C:/Users/jacqu/Desktop/Princeps-codex-break-contract/.gradle-user-break-full'
./gradlew.bat --project-cache-dir .gradle-break-contract --no-build-cache --rerun-tasks test jar apiJar --console=plain
```

Result: **93 suites, 757 tests, 753 passed, zero failures/errors, four TraceReplay skips**. The six Basalt cases
were enabled by the pinned fixture. Gradle completed in 1m46s with all 12 tasks executed. Log:
`build/break-contract-evidence/full-test-jar-api-fresh.log`; all suite counts/timestamps are retained in
`full-test-summary.json`. This result does not establish a successful cleanup descent in game.

- Runtime: `princeps-1.17.0.jar`, 5,823,304 bytes,
  SHA-256 `88902271b48310bc19f2ada4b961beed43f5ef15ddd3c3bf54c68a3a85519ae3`.
- API: `princeps-1.17.0-api.jar`, 228,177 bytes,
  SHA-256 `9dff11d8dcdb540c156eb0d17abf6a5e9d574c115cd92c138eeda7123f82f0b1`, exactly matching the reviewed base API.

Read-only copies of both JARs, logs/XML, this report and the committed source archive are frozen under
`build/break-contract-evidence/frozen-break-20260906`. No live runtime was replaced and no remote action occurred.
