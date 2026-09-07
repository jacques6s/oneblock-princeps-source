# Cleanup escape: execute the proved placement pose

Isolated source base: `26d7b318784c3fb2d98df6e67ef5c4e708ec326c` on
`codex/builder-cleanup-stance-approach`, in `Princeps-codex-cleanup-approach`.
The original 6f918 runtime, its committed sources and all game evidence remain unchanged.

## Real stop and limits of the evidence

Normal Client220 run `towers68-client220-escape-20260906-01` stopped at 144/272 targets.
Its immutable `terminal-trace-prefix.log` is 8,986,796 bytes, SHA256
`bcf28f4496f5d9fb12c58b4ac7fd28a553ad7cf5494f2c3bca29aa682c892a17`.
T3075 starts the escape with 117 helper candidates and 31 permanent floors. T3081–3091
actually executes a three-position route to stance 77,-56,71. That route can be emitted only
after PREFIX, PRESENT and REMOVED proofs have completed; this was not a probe that kept running.
From T3092 the actor remains idle until the original 60-second episode deadline at T4275.
T4295 records the normal progress stop. No new helper was placed or removed.

The final pose is 77.759876854,-56,71.564538494, 0.267770791 blocks from the stance center;
ordinary placement actors require the existing 0.15-block tolerance plus one settling tick.
The new escape actor instead moved directly from cell-level arrival to PLACE. Its empty
`possibleToPlace` result merely returned HOLD, without centering, settling, rejecting that
concrete stance or recording which live predicate failed. The unchanged yaw/pitch confirms
that it did not reach `cleanupPlacementClick` to publish an aim.

This establishes a missing actor transition and a silent no-action fixed point. It does NOT
establish that distance or occlusion caused this particular empty placement result. Root's
offline full-cube check sees all nine unquantized top-face rays from both real pose and center
for the suspected helper 74,-57,71. The old trace does not record which helper was selected;
the exact vanilla/quantized-ray/item rejection remains unknown. The new saved region
`escape-world-snapshot-20260906/r.0.0.mca`, SHA256
`f26bf780a9c745cf90ce4ad7640e9acd61e92885a4a751baba87f2044ac51e02`,
has all 21,904 states identical to the prior lane baseline: 144 targets correct, 128 missing,
zero wrong targets, 1,369 floor cells intact, and the same three extra Dirt cells.

## Bounded correction

The actual WALK_PREFIX actor now uses the existing `centerInPlacementStance` and
`settleInPlacementStance` primitives. It cancels a remaining path before taking movement
input ownership, retains grounded-arrival checks, and derives a real click only after a
centered settling tick. Pose drift resets settling. Centering/cancel attempts use the
existing 80-tick bound, without changing the 60-second episode deadline or any fall setting.

A centered empty live derivation consumes just that concrete stance. The original finite
alternative queue and BREAK owner remain; a new PREFIX proof starts from the actual current
feet. The episode's original bounds/world/model/deadline remain unchanged. Cancelling the
old private probe prevents its answer from crossing into the replacement question. No
hypothetical path is executed. Once a helper request has been issued, stance rejection
cannot discard debt or create another helper. Missing hotbar material waits for ordinary
inventory handling instead of being counted as a geometric stance failure.

Once-per-transition trace events record the proved helper/stance/permanent floor, centering,
material wait, first derived aim and explicit stance rejection. An optional six-counter
argument to the existing `possibleToPlace` implementation records the actual predicates:
replaceable support, survival failure, entity obstruction, empty support shape, quantized
ray miss and rejected item-placement state. All existing callers retain the no-diagnostic
overload, unchanged checks and order. No parallel geometry implementation or per-tick dump
was added. An aim event explicitly is not a server ACK.

## Regression evidence

The RED setup first extracted the exact old WALK_PREFIX block into its actual actor method
without changing behavior. A fresh run of five tests then produced exactly two
`java.lang.AssertionError` failures: cell arrival skipped centering, and centered arrival
skipped settling. The other three controls passed. Its source, tests, XML and log are retained
under `build/cleanup-approach-evidence/targeted-red-03*` and
`extraction-only-red-BuilderProcess.java`. Earlier command-argument and missing vanilla item
component initialization failures are separately retained and are not counted as causal REDs.

The final tests call the actual actor phase and full escape driver, including remaining-path
cancellation, grounded arrival, drift, world invalidation, finite rejection, retained
owner/deadline, issued-helper guard, rebased proof consumption, changed-pose rejection and
noncomplete answers. Additional tests invoke the real `possibleToPlace` survival/collision/
support predicates with controlled world observations, including adjacent self-overlap and
an independent obstruction that centering must not hide. The adjacent counterexample is
deliberately distinct from the remote helper in the real run.

These are production input/state and predicate-boundary tests. They do not establish a
positive complete vanilla placement, quantized ray or Minecraft physics trajectory. World
and block-view doubles in the predicate tests provide the stated observations; they are not
presented as independent path/BSI-overlay physics proof. There is no replacement API provider
or licence bypass. Exact fresh test totals, source hashes and timestamps are recorded in
`build/cleanup-approach-evidence/targeted-green-02-run.json` and its XML copies.

Two complete PLACE-driver branches remain game-verification requirements: a centered empty
live derivation must advance to CANDIDATE with a rebased proof start, while missing hotbar
material must remain PLACE without rejecting the stance. Their constituent predicate and
rejection contracts are tested, but the complete driver reaches `cleanupMayOccupy` and the
ordinary inventory fetch, both of which read the real global settings. The existing headless
test setup cannot initialize those settings without constructing the actual provider and
Minecraft client services. No fake provider/client framework or production permission seam
was introduced to label these branches green. Root explicitly deferred their integration
check to the new, phase-specific traces in the normal game comparison.

The final fresh targeted run completed at 2026-09-06 18:19:32 UTC: 11 suites, 96 tests,
96 passed, zero failures/errors/skips; all seven Gradle tasks executed with rerun-tasks and
no build cache. It includes 17 new tests and 79 directly relevant existing controls.

The fresh full suite plus `jar apiJar` ran from 18:28:26.771 to 18:29:50.363 UTC using the
same JDK 25, isolated Gradle home/project outputs, max two workers, `--rerun-tasks` and
`--no-build-cache`. All 12 tasks executed. The 98 suites contain 807 tests: 803 passed,
zero failures/errors, and only the four known `TraceReplayTest` skips. The six Basalt
tests ran with the unchanged 12,077-byte owner fixture, SHA256
`202bab5dbb24f99f180db44faaa6ee9daecb949647d946e7294e9ba25f7414e4`.
The exact command and timestamps are in `full-green-01-run.json`; XML totals and artifact
checks are in `full-green-01-verification.json` in the evidence directory.

The built runtime is 5,845,553 bytes, SHA256
`649b93a7aafdb9e8670a103293b700330a719b8dedcc2cb67417c2cbd63ea579`.
Its API facade remains 228,177 bytes, SHA256
`9dff11d8dcdb540c156eb0d17abf6a5e9d574c115cd92c138eeda7123f82f0b1`;
all 187 API class entries in the runtime also match the facade byte for byte.
The tested/built BuilderProcess source SHA256 remains
`b1e73bb7444a3248bd1702f7c3a48a0eeaea26d891a61a4d229e7297b8f6aaa6`,
unchanged since the accepted production review. The later documentation update and local
commit do not rebuild or alter these artifacts; the final immutable evidence records the
commit, source bytes, XML, runtime/API and prior causal RED/review freeze together.

No corrected game run or throughput improvement is claimed. Root is preparing the next
normal comparison on the planned Client221 bootstrap; the old Client220 result
remains a historical baseline, not a same-client-version comparison. The new comparison
must inspect the newly explicit helper/phase/rejection evidence, exceed the
144-target endpoint without damage, compare equal client-tick progress and preserve the
complete independent AIR/floor/target oracle. Full 272 completion and 16k reliability remain
unproved. Shared `CalculationContext`, `MovementFall`, ownership/debt and public API sources
are unchanged by this correction.
