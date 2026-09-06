# Preserve model material while navigating between build actions

Base `e8dc4dafd0b6eea75e1d6c8a93bf82a912c5d116`, isolated branch
`codex/builder-model-restoration`. No live, remote or publication actions.

The completed Door221 trace
`door68-client221-fixed-20260906-01/terminal-trace-prefix.log` has SHA256
`1ac58c388bfdf5b607d261f7dd4872f7b70e8ccc4bc5e90588d32840b4159342`.
It reaches the intended door placement at T192, cleans the temporary dirt,
then stops at T211 because required black stained glass is missing. The
independent saved-world review reports three lost existing targets: that glass
and two hoppers above the active door layer. The trace's successful door
placement does not constitute a successful complete fixture.

The causal code boundary is the layer wrapper: BCC.getSchematic returns null
for a full-model cell outside the active band. breakCostMultiplierAt then
returns 1, so the cell is priced like ordinary terrain. The existing support
policy knew the full model but examined only neighbouring survival dependencies.
It did not protect the material at the removed position. A finite correctness
penalty, preferred Silk Touch tool or a possible drop is no restoration proof.

The shared policy now checks the primary position against the full model before
the neighbour checks. A non-AIR desired block with the same current block
identity is retained, including a state needing correction. The existing
explicitly selected wrong-state repair may remove it, provided its exact
state/session/target proof still holds. Correct states never receive that
exception. Wrong block identities, model-AIR cleanup, unrelated outside terrain
and the existing excavation scope keep their established behaviour. There is
no block list, inventory reservation, drop prediction or new cost weighting.

The executing route also retains a ModelProtection snapshot from the exact
completed search's local context. It captures the full model, copied origin and
stock, owner, world and player. Its final crosshair query reads loaded live
world cells through the existing read-only support View, without a path-worker
cache or global settings lookup. The public active Builder check uses that
same live view. Unknown loaded state cannot grant mining.

InputOverrideHandler checks the actual current executor before owner-null or
paused Builder permissions. Thus an unsafe-to-cancel route cannot lose the
restriction while continuing to run. Cuts retain the same protection; splicing
requires equal model/origin/world/player/owner/stock bindings. Ordinary routes
carry no such protection. Repair exceptions require the same active owning
Builder and matching full-model/session inputs, including actual same-block
controller continuation on later ticks. A new model or foreign owner cannot
reuse a prior route's repair. No public API source was changed.

## Reproduction and verification

JDK 25.0.3+9, Gradle 9.4 offline, maximum two workers, separate project cache
`.gradle-model-restoration`. Evidence is in `build/model-restoration-evidence`.

- Original e8dc source: **13 tests, 9 AssertionErrors, 4 passing controls,
  zero errors/skips**. `red.xml` and `red-result.json` retain the source and test
  pins. The actual BCC returns cost factor 1; actual BBH reaches controller
  damage for the missing preservation cases. Glass, bookshelf and hopper
  inputs exercise the shared class of problem.
- Minimal primary-policy GREEN: **45 tests, zero failures/errors/skips**,
  including existing support/paired-repair and material-preparation tests.
- Controlled lifecycle RED: only the actual Input route guard was omitted.
  **13 tests, 5 AssertionErrors, 8 passing controls, zero errors/skips**.
  Pause, owner-null, foreign owner, newly corrected world state and the
  revoked multi-tick repair each reached forbidden controller damage. The
  original source bytes were restored in finally and independently hashed.
  Evidence: `route-guard-off-red.xml`, `route-guard-off-result.json`.
- Final restored targeted run: **58 tests in four suites, zero failures,
  errors or skips**, with all seven Gradle tasks executed freshly using
  `--rerun-tasks --no-build-cache`. All XML is copied to
  `final-targeted-green`; log and totals remain beside it.

The first test compile used a lambda for a non-functional Mask interface; it
is retained as `red.log` and excluded from RED evidence. The first primary
compile encountered a local variable-name collision, retained in
`primary-green.log`; its corrected run is `primary-green-2.log`. Neither
compile failure is claimed as a behavioral counterexample.

## Limits

Tests execute actual BCC cost, executor factory/cut/splice, cancelSegmentIfSafe,
Input predicate and BBH/controller calls. Constructor-bypassed fixtures supply
world/inventory/context and path data. The active layer mask is an explicit
snapshot input; the tests do not run the entire Builder layer-selection tick.
The lifecycle tests supply the stored unsafe-to-cancel result and path movement
endpoints; they do not simulate a full PathExecutor/Movement physics tick.
The cut fixture silences only its debug chat sink. The real three-damage-tick
repair crosses both guards and uses actual BBH.isBreakingBlock.

This prevents unlicensed removal; it does not prove an alternative route or
helper sequence is available. A full suite/runtime build requires the agreed
independent delta review, and a later isolated game comparison must verify
progress plus preservation of the complete original model. Indirect physics,
loot recovery and full AutoDig221 integration remain outside this change.
