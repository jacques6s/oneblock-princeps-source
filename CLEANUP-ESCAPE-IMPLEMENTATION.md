# Bounded cleanup escape candidate, 2026-09-06

This is a source candidate on reviewed lane-continuity commit
`8f5639e7bd5d9b313eb2003a011ed9fd38acb889`. No runtime was replaced and no game action was taken here.
The original, uncompiled escape WIP on `1581062` is preserved byte-for-byte in its original worktree and in
`Princeps-codex-cleanup-escape/build/cleanup-escape-wip-evidence/frozen-original-1581062-20260906/`;
that preservation manifest is `110e05e1ca70f73f23154796ed88961384dbf969d338a782e601280b9bad7498`.

## Real cause and bounded change

The corrected normal 220 lane run restored 144 reported correct cells, after the preceding BREAK candidate
regressed to 43. Its last completed cell was at trace tick 3071. At tick 3073 the engine accepted a fresh
negative Lane-A result for an owned cleanup block, retained that BREAK target, and refused recursive helpers.
It stopped normally at tick 4272 after 60 seconds without work. Stable binding fixed the earlier route-driver
regression but did not supply a legal descent from the completed high platform. This change addresses only
that subsequent cleanup class. Independent world counts and matching-tick curves are recorded by the parent
bench task; client intermediate counts are not a world oracle.

The normal `finishBuilderRoute` and concrete recovery producer from 8f563 remain unchanged. After their
current Lane-A evidence is consumed, an owned navigation-helper BREAK target may start one bounded episode.
The episode keeps that target, model, world and origin; row/AutoDig modes do not enter it. It enumerates a finite
local set of real standing positions and candidate AIR cells, requests no second helper, and expires after
60 seconds without renewing that deadline for aim, inventory changes or repeated paths.

The checked sequence is:

1. Complete strict navigation through the actual world to an existing, safe placement stance.
2. On a separate context whose private BSI assumes only helper H is present, prove a complete route to H.above.
3. Evaluate the real `MovementDownward.cost` for removing exactly H while standing above it, with an intact
   real block below H. Other mining, all route placement and fluid traversal are forbidden.
4. On another private context whose BSI assumes H is AIR, prove a complete route from H to permanent ground.
   That ground must support the body after cleanup, and all involved original helper-column blocks must be
   within real collision-ray reach from that same ground stance. No hypothetical client-world mutation is used.
5. Only after those proofs, walk a newly calculated real route to the stance, validate the actual click ray,
   request one normal full-cube throwaway and wait for both the matching local interaction and a newer matching
   server observation. Recalculate real strict routes after actual placement and removal; never execute an
   IPath returned by a hypothetical probe. On-ground feet and intact support are required before Downward.
6. After H is server-observed AIR and the player has arrived on verified permanent ground, release the episode
   and let the normal builder remove its retained original target. The new helper never becomes a new escape owner.

This includes the general equal-height helper → ordinary parkour → one-block Downward → permitted dry fall
chain. It does not hard-code bench coordinates or raise a three-block fall limit to four. Ordinary movement
costs, normal breaking restrictions, throwaway rules, protection and border checks continue to apply.

## Server evidence and retained debt

`SuccessfulBlockInteraction` records the local controller returning SUCCESS; it is not a server acknowledgement.
The existing launch mixin separately dispatches states from actual block-update/section-update packets after
their handlers run. The new debt record combines only the current request's world, episode, coordinate,
material, local serial fence and server-callback serial/time fence. An older observation cannot combine with a
later interaction, and the current server callback may arrive before the matching local receipt is polled.
These are ordered observations, not a newly added Minecraft protocol transaction id.

Independent review found that binding only the helper coordinate and material left a real execution context able
to mine identical foreign material after OWNED → server AIR → replacement. The pending correction binds real
mining to the current debt and active episode, with a published revocable state. The actual Downward phase also
rejects every solid no longer owned, while removed AIR may still be entered to land safely. Hypothetical PRESENT
mining remains a separate proof permission. Another reviewed callback boundary prevents the existing
BlockPlaceHelper local-success callback from adding this episode's request to the ordinary ledger prematurely.
Six additional production-path regression methods are prepared for these corrections but have not yet run.

Local SUCCESS alone grants no ownership. A matching server observation alone also grants no ownership.
Only after both does the existing navigation ledger receive this helper. AIR before ownership does not
discharge the debt; a foreign replacement revokes mining and cannot later become our successful cleanup.
The debt survives stop/new-job working-set reset. An unresolved or rejected request conservatively prevents
another escape helper; automatic recovery of such orphaned debt is outside this candidate. It is not silently
counted as removed. Normal progress actions are armed at the request and counted by the existing server callback.

The local world snapshot is checked fully at proof/action boundaries and invalidated by changes during ordinary
route ticks. Snapshot regions, search contexts and their individual H overlays are never reused or mutated by a
different worker. The initial position must stay unchanged during proof collection. Each real route is calculated
after the corresponding actual state change. A fall already in progress keeps its context until cancellation is safe.

## Shared movement boundary

The only shared movement addition is an explicit prohibition of bucket-based falls on this dry episode's context.
Normal `CalculationContext` constructors retain the exact previous inventory/settings expression for bucket
capability. `MovementFall.willPlaceBucket` still constructs its original fresh context for every ordinary route,
including builder and excavation routes, regardless of a stale route's cached `hasWaterBucket`. Only an explicit
dry episode skips that water decision. Nothing enables a disabled bucket or increases any fall height.

## Fresh targeted verification

The first compilation stopped before tests because the existing origin is a `Vec3i`, which has no `immutable()`
method. The immutable origin snapshot was corrected to `new BlockPos(origin)`; no behavior was changed by that
compiler correction. The second run started at 16:24:40.627Z and ended at 16:25:24.078Z: **57/57 PASS**, zero
failures, errors or skips. All seven requested Gradle tasks executed with `--rerun-tasks --no-build-cache`,
an isolated project cache and at most two workers. No jar or full-suite task was run.

The five suites were CleanupContract (15), CleanupFallPolicy (5), RouteContext (11, including one new driver
case), LaneEvidence (16) and BreakContract (10). The following checks passed in that run:

That 57-test result precedes the review corrections above; it is preserved as its own immutable evidence and
does not certify the subsequently modified sources. The next targeted run and counterfactual controls remain pending.

- Request/observation ordering, foreign-world/material/replacement and AIR-before-ownership regressions.
- Actual BSI `get0` reads of separate PRESENT and REMOVED overlays, including AIR (no overridden `get0`).
- Exact complete-path endpoint/start checks and the unchanged per-edge dry height bound.
- Strict context refusal of placement/unrelated mining and actual Downward refusal when disabled.
- Actual private `MovementFall.willPlaceBucket` execution: default routes reach their original fresh player
  dependency, while only explicit dry context may suppress it. No global provider or API shadow is installed.
- Actual cleanup-driver unsafe-cancellation return into real PathingBehavior, preserving its route context.
- Existing lane-evidence, break-contract and route-context controls passed; the full Basalt fixture suite remains pending.

The path-height tests are contract tests, not a positive Minecraft-physics proof. Headless positive full movement
costs require the real Minecraft/provider settings lifecycle; no fake bootstrap was added to claim that result.
The normal licensed 220 comparison on isolated 68, matching-tick progress and a complete independent world oracle
remain mandatory. The frozen 144-world geometry witness supports the candidate's possibility, not actual execution.
There is no current claim of successful cleanup, speed improvement, full schematic completion or 16k reliability.
