# Builder target commitment and measured progress

Base: public Princeps source `6efb77388e12fe28c2d0afdeb4818652c773e64e`.
This isolated change does not include the separately reviewed AIR, park/verdict, or material/verdict fixes.

## Problem and behavior

An unrelated completed cell cleared `electedCell`; a hotbar change could also discard a still-valid target. A new
Goal object counted as navigation progress. Separately, the early five-second nudge could keep returning before
later recovery, and the fixed 400-tick placement deadline could release a target during a legitimate long walk.

The selected cell now owns preparation, material demand, navigation and the eventual placement attempt. An
exhausted search budget or a missing hotbar item is Unknown and retains that cell, including when no route has
yet been obtained. Current positive placement evidence can replace its stance goal. Verified completion or a
real negative geometry verdict releases it. The current map-art placement conditions are rechecked; the old goal
is not blindly reused for row builds. The old generic route timer cannot rotate an already selected cell.

The active builder samples a monotonic clock at its outer tick. Five seconds without two blocks of net approach
or route/mining advancement produces a diagnostic and revalidates the same goal. It does not select another cell.
Sixty active seconds without confirmed world action, net approach, valid route-node advancement or increasing
vanilla mining fraction requests a normal bounded abort with the phase, target and progress observations in the
report. All unresolved cells remain unresolved. The old nudge and unconditional 400-tick lock deadline are removed.
Recovery's existing stance timeout accepts measured progress, including a long detour.

Equivalent reconstructed paths share a fingerprint and high-water mark. A shortened route can credit feet actually
reaching a later node of its predecessor; constructing a new executor is not itself progress. Failed, finished and
out-of-range executor positions, including `cancel()`'s length-plus-three sentinel, are excluded. Target distance is
compared with its best observation; repeated one-block pacing does not keep earning distance progress. Route and
target histories are not silently capped at 128 identities. They live until a confirmed action or build reset.

World progress is separate from controller click success. An expected outstanding placement, interaction or break
must receive a matching server block-change observation and earns credit once. Later power/neighbor cycles at an
already completed action do not refresh the timer. Mining reads vanilla's `destroyProgress` through a read-only
mixin accessor; repeating the same unconfirmed animation cannot repeatedly earn the same fraction of progress.

Material, chunk and confirmation waits have distinct trace phases and do not count as progress. Material/chunk
waits cancel motion and clear input; the existing idle-route wrapper cannot revive those cancellations or a stop.
Explicit pause suspends the clock, including a pause/resume interval with no builder ticks. The existing internal
material-pause branch also delegates to that same callback. Normal supply/owner pause behavior remains separate.

## Verification

Windows, JDK 25.0.3+9, Gradle 9.4.0, offline pinned dependencies and the existing Minecraft input verification.
Commands use `--no-daemon --no-build-cache --no-configuration-cache --max-workers=2`; Java installation and
`org.gradle.java.home` are explicitly pinned. Exact local argument arrays, logs and JUnit XML are retained in
ignored `build/progress-evidence/`.

- Controlled RED against the original completion callback: 2 tests, 1 assertion failure, 0 errors/skips. Completing
  an unrelated cell incorrectly released the selected target; completion of the selected cell was the passing control.
- Full `test jar apiJar`: 85 suites, 682 tests, **672 passed, 0 failures/errors, 10 skipped**. This run precedes only
  the final one-line delegation of the existing internal material pause to the already tested `pause()` callback.
- Final source after that delegation: `test` restricted to `BuilderCommitmentTest`, `BuilderProgressWatchTest`,
  `PlacementTargetLockTest`, and `BuilderRecoveryPathPolicyTest`, plus `jar apiJar`: **45 passed, 0 failures/errors/skips**.
- The 10 full-suite skips are six external-fixture cases in `BasaltDryRunTest` and four external-trace cases in
  `TraceReplayTest`. They are not claimed as passing coverage here.
- Initial test-fixture construction/import/assertion-format failures are retained separately; they are not counted
  as controlled RED evidence. No retry, sleep extension or test disabling was used to pass an assertion.

The tests call the actual completion, pause/resume, server-change, verdict and idle-route wrapper methods, and
inspect actual PathExecutor status boundaries. A small isolated vanilla world proves that support removal produces
a negative verdict while an exhausted stance budget is Unknown and retains the goal. Lock/watch time, route and
mining observations are controlled synthetic inputs: long detours, more than 128 route identities, shortened routes,
one-block pacing, target/goal reconstruction, slow mining, repeated mining animation, phase changes, timeout,
old power cycles, and legitimate one-shot server confirmations.

These results do **not** establish an end-to-end `onTick` survival run, live mixin transformation, full Farm/Basalt
completion, material logistics, or sustained in-game speed. The next required acceptance is the separately isolated
combined-engine bench with full-volume oracle, actual hook/origin checks and normal terminal cleanup. The watchdog
is a bounded failure report; it does not repair the independently identified park/material/AIR causes itself.

## Local candidate identity

Final `BuilderProcess.java` SHA-256:
`ba34906283bbfa7a11ce06b1980fdb7eb54e97b3fe2955cd31bbfb5eff74117d`.

Runtime `princeps-1.17.0.jar`: 5,818,762 bytes,
`212bc7a1d75cb01744961d260af15133480a80d0e6df9046e8fb51a5ff1763ef`.

API `princeps-1.17.0-api.jar`: 228,177 bytes,
`9dff11d8dcdb540c156eb0d17abf6a5e9d574c115cd92c138eeda7123f82f0b1` (unchanged).
All 187 API classes are present and byte-identical in the runtime JAR.

These are private local build artifacts, not a published release or a completed client/source offer update.
