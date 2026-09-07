# Door interaction on a descending approach

Base: `b7a4cf34971a8585339502a37dd5e90c3ddac578` (the independently checked
support-dependency candidate). This change does not alter support-removal,
mining, placement, or builder-selection permissions.

## Observed boundary

The normal licensed Support C run `support68-client220-c-20260906-01` reached
`MovementDescend` while approaching the wrong-facing door. It remained at
`72.700,-59.000,67.501`; six targets were correct and both door halves were
wrong. Its trace reached the five-second diagnostic and the sixty-second
`PLACEMENT_FAILED` stop without a mining event or `SUPPORT-PROTECTED` refusal.
The old Descend cost treated a wooden door as passable, but its running actor
had no door interaction. The flat Traverse actor did have one.

The lower door was below the standing player's feet and the current builder
does not allow breaking from above by default. The approach therefore needs to
finish before the normal repair selection becomes available. This is not a
measured refusal by the new support guard. A separate path-standing exclusion
also exists in the repair selector; the trace alone does not prove it ran.

## Change

Traverse and Descend share one normal door-use actor. It projects the cardinal
approach onto each door half's height and uses the existing directional
passability rule. Only a blocking, hand-operated door with an allowing current
route context can request use. The grounded movement waits while aiming and
releasing sneak. The actual current ray must hit that door or its matching
opposite half, within ordinary reach. Both crouching and Vanilla secondary use
must have ended before CLICK_RIGHT is requested. A requested rotation alone
does not authorize a click with the held item.

Airborne control is retained. Once the door panel is passable the actor stops
requesting use, allowing the existing movement to continue. The actor writes
no world state and calls no inventory, mining or builder implementation. It
does not change costs, global settings, the gate actor or optional V3 policy.
The normal game interaction and server update still determine whether a door
actually changes state; the trace's `route-door` intention is not confirmation.

## Verification status

Fresh initial compile/test: 2026-09-06 17:06:47.239Z–17:07:28.277Z, JDK 25,
offline, maximum two workers, own `.gradle-door-approach` project cache.
The 54 tests passed with no failures, errors or skips: 14 new door cases,
3 diagonal-barrier, 2 traverse-aim, 3 barrier-policy, 23 support-dependency and
9 scaffold-material-preparation cases. XML is retained under
`build/door-approach-evidence/initial-green`; exact source hashes are in
`source-review.json` in the same evidence directory.

The new tests execute the actual Descend and Traverse RUNNING `updateState`
methods. They supply the preceding preparation stage as already completed;
Descend uses its existing safe-mode branch for nonblocking continuation. The
cost fixture uses the actual Descend cost and real state-only walk predicates
with explicit settings, without initialising the global Minecraft/provider.
A narrow corridor demonstrates three forbidden alternative descents and the
finite direct door step. This is not a full A* or Minecraft-physics traversal.
Ray miss/foreign hit, paired-half identity, reach, pose versus secondary use,
airborne control, passable/sideways panels, iron door, forbidden/missing route
context and unchanged barrier-free costs are covered.

The separately copied actor-off control was executed by the coordinating
agent at 17:17:22.710Z–17:18:08.060Z. All 525 source files matched except for
the single omitted Descend actor invocation; the authoritative worktree stayed
unchanged. Nine of the fourteen door cases failed with `AssertionError`,
including the finite-cost actor and the narrow-only-doorway cases. The other
45 cases passed, with no test errors or skips. The copied source manifest,
complete log and six XML reports are retained as `actor-off-copy.json`,
`actor-off-red.log`, `actor-off-run.json` and `actor-off-xml` in the evidence
directory. This control demonstrates the missing RUNNING actor; it does not
reproduce every behavior of the old Traverse implementation.

The unchanged authoritative source then passed a fresh full rebuild at
17:18:29.164Z–17:19:29.944Z:

```text
gradlew.bat --project-cache-dir .gradle-door-approach --offline --max-workers=2 test jar apiJar --rerun-tasks --no-build-cache --console=plain
```

All 93 suites completed: **776 tests, 772 passed, 4 skipped, 0 failures/errors**.
The four skips are the existing absent historical TraceReplay inputs; the
owner Basalt data-only fixture was pinned to
`202bab5dbb24f99f180db44faaa6ee9daecb949647d946e7294e9ba25f7414e4`
and all seven BasaltDryRun tests passed. Full XML, exact source pins, archive
checks and reports are frozen in `build/door-approach-evidence`.

- Runtime: 5,834,557 bytes,
  `c28b68f9f0d97d3fcf2437ecb2ea64b2a65bb1ea93178bb8af089ea6c0df4452`.
- API: 228,177 bytes,
  `9dff11d8dcdb540c156eb0d17abf6a5e9d574c115cd92c138eeda7123f82f0b1`.
- Both ZIP CRC checks passed; all 187 API class payloads are byte-identical
  between API and runtime, and the API archive retains the previously pinned
  hash.

The normal Support C repair rerun remains pending. It must demonstrate actual
normal door use, completed approach, subsequent legitimate repair, both exact
desired door states and the independent complete-volume/floor oracle.
Headless checks do not establish successful live repair. No release, server
action or world mutation was performed as part of this implementation.
