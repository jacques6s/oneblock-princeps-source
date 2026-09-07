# Direct support dependencies and scaffold material preparation

Isolated worktree `Princeps-codex-support-dependencies`, branch
`codex/builder-support-dependencies`, based on
`f5cb391f3c8e4cb3268237d2b081de11a167c03d`. No BREAK-contract change,
optional V3 activation, remote publication, live input or world mutation was
performed for this implementation.

## Observed failure and attribution limit

The frozen `basalt65-materials220-20260906-01` trace contains an initial
`NO_FACE` for a lower door, a scaffold probe explicitly unable to evaluate the
door without its hotbar item, one subsequently placed dirt helper, and no door
placement before the progress stop. The terminal world census independently
shows both door cells and the external foundation missing. The helper occupies
an original fluid target represented as AIR during the structural phase.

This is not evidence that the foundation was missing when construction began.
The historical region snapshots `f6fb5935…`, `e152857f…`, `04d8630e…`, and
`645adb55…` contain the stone foundation and both correct door halves. The later
`c6580edc…` and `647e1ece…` contain AIR at all three cells. The intermediate trace
shows the player entering the resulting hole but does not log the actual mining
controller call that removed its floor. The owner was also online. The change
interval is established; the responsible actor is not.

The relevant production gaps are independently visible in the current code:
outside-model terrain previously had ordinary finite mining cost without checking
whether it supported an existing model block, and an undecidable scaffold probe
could be promoted to an authorized helper. Neither gap requires the historical
actor to be inferred.

## Change

`BuilderSupportDependencies` evaluates a single AIR hypothesis using an immutable
`LevelReader` view over the current block reader. It does not implement a mutable
world, expose real chunks through the hypothesis, or call `ClientLevel.setBlock`.
It checks the six directly adjacent, in-model neighbours against the full model
before layer masking. A neighbour of the required block identity remains protected
even if its current toggle or orientation differs from the requested state.
Removal is refused when that current block survives before removal and fails its
actual Vanilla `canSurvive` predicate afterwards. Unknown loaded-world inputs fail
closed near model dependencies. Unrelated terrain and wrong neighbour identities
retain normal mining permission.

The calculation context holds one immutable policy for its existing model,
origin, stock and BSI snapshot. A concrete hypothesis is local to one removal.
The real crosshair is independently checked immediately before both mining
controller entry points in `BlockBreakHelper`. The input handler consults only
the process actually controlling that tick; it does not instantiate or select a
builder while another process is mining. Paused, inactive and excavation work
retain their previous mining behaviour. Cleanup clicks use the same real-hit
guard as navigation mining.

A selected direct repair can include the existing upper partner of a Vanilla
door or double plant. This is limited to the ordinary break-and-replace branch,
the same lower block state, target, world, player, model and tick, and an actual
and desired matching upper half. Navigation receives no such permission. A
multi-tick repair continues only with the real controller still mining that
same crosshair block and an uninterrupted builder tick sequence. State, target
or session changes, pause, abort and rejection withdraw the allowance. A merely
open wooden door still takes its ordinary interaction repair path; the repair
allowance never authorizes its external foundation.
The existing input handler also withdraws that allowance when the actual owner
changes, including a temporary foreign process that does not call the builder's
`onLostControl`. Returning to the builder cannot resurrect the previous action.

Scaffold `NOT_HOTBAR` now records one material demand and uses the existing normal
inventory method, including its locks and rate/stationary restrictions. A swap
return value is not treated as material arrival. The actual hotbar is checked
again before the existing geometry oracle runs. No unchecked fallback opens a
helper. Material preparation preserves cancellation through the existing route
continuation wrapper, requests at most one swap per builder tick and stops after
120 active build ticks. A manual pause subtracts only the elapsed paused build
ticks from that budget; repeated pause/resume does not renew it.

When Vanilla survival can be restored only by a directly adjacent, currently
missing outside-model support, the failure names that terrain dependency. This
is a diagnostic, not permission to place an external temporary foundation and
later destroy it during cleanup. An unavailable view is reported as unknown,
not as proven missing permanent terrain.

## Controlled verification

JDK `25.0.3+9`, Gradle wrapper, offline dependencies, isolated cache
`.gradle-support-dependencies`. Results are retained under
`build/support-dependency-evidence`.

- Removing the new `REQUIRED_SUPPORT` decision while retaining the testable
  policy/input interfaces: 22 tests, 15 expected assertion failures, 7 controls
  passed, no errors/skips (`red/guard.xml`).
- Restoring the former unchecked scaffold fallback: 7 tests, 4 expected
  assertion failures, 3 controls passed, no errors/skips (`red/material.xml`).
- Restored implementation before the final pause/owner delta: 29 tests passed,
  no failures/errors/skips (`green/`).
- Final targeted run: 32 passed, no failures/errors/skips (`final-targeted/`).
- Fresh complete `test jar apiJar --rerun-tasks --no-build-cache --offline`:
  92 suites, 762 tests, 758 passed, no failures/errors and four existing
  `TraceReplayTest` skips for absent historical trace inputs (`full-xml/`).
  All seven `BasaltDryRunTest` cases ran and passed with the copied owner fixture
  pinned to `202bab5dbb24f99f180db44faaa6ee9daecb949647d946e7294e9ba25f7414e4`.
  The process ran from 15:46:40.881Z to 15:47:28.726Z on 2026-09-06; all twelve
  Gradle tasks executed. All seven reviewed source/document pins remained
  unchanged during that run. Only this evidence documentation was updated after it.

Frozen artifacts under `build/support-dependency-evidence/frozen/`:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Runtime | 5,833,276 | `ddfb12b4546749c1fa2b8b57056c8a747e8a1dbd023744be37c82d6e659a4985` |
| API | 228,177 | `9dff11d8dcdb540c156eb0d17abf6a5e9d574c115cd92c138eeda7123f82f0b1` |

All 187 API classes are byte-identical between that unchanged API artifact and
the frozen runtime; both ZIP CRC checks passed. Individual class hashes and the
comparison are recorded in `api-runtime-comparison.json`.

The tests call the real dependency predicates, actual BCC mining cost, actual
scaffold selection/preparation, a normal inventory lock refusal, and actual
`BlockBreakHelper.tick`/`isBreakingBlock` damage continuation. The two geometry
controls install existing positive/negative scaffold-cache entries to isolate
selection semantics. They do not prove a newly computed stance oracle. Only the
final chat/logger callback is replaced for the headless scaffold test. Tests do
not replace builder decisions or weaken world validation.

Earlier fixture-only failures are retained separately: a global settings access
after intentionally removing the guard, an attempted test subclass of the final
builder, and `BetterBlockPos.toString` invoked while formatting a failing JUnit
assertion. They are not counted as causal RED proof. The final counterexamples
use deterministic controller timing and fixed invariant messages.

## Remaining acceptance and scope

Root and an independent agent reviewed the source before the final tests and
artifact build. No live success claim accompanies this checkpoint. A later isolated survival
fixture must verify an existing outside-model foundation survives navigation and
cleanup, a wrongly oriented door can be repaired over multiple mining ticks, an
unknown hotbar item never creates a helper, and a genuinely missing external
foundation ends with the explicit dependency report. Independent world checks
must include both halves, the foundation and all temporary helpers.

This is direct single-block `canSurvive` dependency protection, not a general
physics simulation. Falling blocks, indirect update cascades, fluids, server
plugin area tools, and every `updateShape` partner effect are not thereby solved.
In particular, removing a door's upper half may affect its lower half through
Vanilla neighbour updates beyond the tested direct predicate. The existing
placement stance oracle is unchanged; its historical implementation is not
claimed to have become a read-only world simulator through this patch.
