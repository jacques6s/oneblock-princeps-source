# Keep the current permanent platform for one build step

Base: `f0a371ab4ac4a6f2eed99b1b9d86afd8692fbb4e`.
Branch: `codex/builder-platform-traverse`. No live, remote or publication actions.

The existing standing-cell search can route down from an upper platform even
when the next permanent full cube is directly beside its floor. This change
offers the existing `MovementTraverse` bridge/backplace action for that one
adjacent target. It does not choose a generally higher route or reorder the
composite standing goals.

`platformTraverseGoal` is shared by election and placement recovery. It checks
the actual Traverse cost, a loaded, grounded full-cube source, clear body/head
cells, exact existing source state, an available normal hotbar item and a
non-falling, non-fluid, non-oriented full-cube target. Both source and target
must be permanent states of the full unmasked model. A temporary overlay over
model AIR cannot qualify. The original source is retained while the sneaking
body crosses into the destination cell; the still-empty target never becomes
its own assumed support.

The immutable builder calculation context admits just source and destination
at their shared height, permits placement only at the permanent target, and
forbids mining. The ordinary input guard also forbids mining for this action.
A private goal marker travels with the actual `IPath`, so an invalidated route
being cancelled cannot use an obstructed backplace ray to mine a neighbour.
The shared InputOverride/BlockBreakHelper predicate checks that route before
granting permission from a missing or changed process owner. The Builder's
public predicate checks it before paused/inactive returns. An unsafe-to-cancel
Traverse can remain current after pause or owner cancellation; the route's
restriction lasts until the actual route disappears, independently of the
shorter election proof. An absent or ordinary route retains ordinary mining.
A new platform offer also waits for any ordinary current executor to end.
Only the exact previously marked action may continue with an existing executor.
This prevents admission as a NEXT segment whose splice could retain an older
ordinary goal marker; no general splice behaviour was changed.
No API, movement type, pathfinder, material supplier or licence changes were
introduced. New routing commands receive a fresh context when the proof is
created or withdrawn. The chosen cell survives a material wait, but its stale
edge action is not returned as a usable goal.

The normal RUNNING backplace block was extracted unchanged into
`MovementTraverse.updateBackplace`, with narrow safety checks added: real
ground/crouch/secondary-use input before edge movement or placement, actual
reach to the intended support face, and fresh left/right/back inputs each
call. The secondary-use check matters on interactive supports: Vanilla chooses
support use versus held-item use from the actual Shift input, not the crouch
pose. Existing MapArt and excavation retain this same actor and normal
material/route gates; no separate free-movement controller was added.

## Reproduction and verification

JDK 25.0.3+9; Gradle 9.4 offline, maximum two workers, separate project cache
`.gradle-platform-traverse`. Commands and XML are retained under
`build/platform-traverse-evidence`.

- Controlled offer-off RED: only `platformTraverseGoal` returned null. The
  actual verdict/cost/lifecycle tests ran **13 tests, 9 AssertionErrors, 4
  passing controls, no errors or skips**. Authoritative source bytes were
  restored in `finally`; hashes match. Evidence: `offer-off-red-3`.
- Actual secondary-use RED: crouching pose true but Shift false. The actual
  backplace actor incorrectly requested use before the final guard.
  **7 tests, 1 AssertionError, 6 passing controls**, no errors or skips.
  Evidence: `secondary-use-red` and its log.
- Review-driven lifecycle RED: **4 tests, 3 AssertionErrors, 1 passing control**.
  With only the two actual-route refusals disabled, pause returned permission;
  owner-null and foreign-owner cases each reached a real controller damage
  call. The actual `cancelSegmentIfSafe` kept the same unsafe current executor,
  and the actual backplace actor generated the obstruction click. The final
  fixture places a solid obstruction after the movement proof. Evidence:
  `lifecycle-delta/route-guard-off` (original first RED also retained).
- Entry-boundary RED: **14 tests, 1 AssertionError, 13 passing controls**.
  An ordinary current executor must finish before a new edge offer; the same
  marked action continues. The first diagnostic attempt invoked the settings
  singleton while JUnit formatted `GoalBlock.toString`; it is retained as an
  excluded diagnostic-format failure. A boolean assertion checks the same
  predicate without formatting the live goal. Evidence: `entry-red.xml`.
- Final restored targeted GREEN: **97 tests in 10 suites, zero failures,
  errors or skips**. This includes 14 builder/platform cases, 7 actual
  backplace cases, 4 actual route/input/mining lifecycle cases and existing
  Door, Aim, AIR, Commitment, Row, Support and Scaffold-material regression
  suites. Evidence:
  `lifecycle-delta/final-entry-green` and `final-entry-green.log`.
  The previous 92/96-test freezes are retained and superseded by
  `source-review-entry.json`.

The counterexamples include target AIR/temporary overlays, falling blocks,
slabs/stairs/soul sand, orientation-sensitive blocks, unloaded chunks, missing
material, obstructed body/head, removed support, world/target changes, pause,
target completion, old executing route cancellation, wrong live face/reach,
airborne/crouch/Shift waits, stale inputs and two successive target/support
pairs. Current world-state changes are explicit fixture inputs; these tests
do not invent a server acknowledgement.

Initial fixture failures are retained separately and are **not** RED evidence:
an import ambiguity, a missing fixture BSI world border, a WIP nonexistent
accessor in `offer-off-red`, and the uninitialized ordinary-verdict cache in
`offer-off-red-2`. The last attempt contained eight expected assertions plus
one fixture NPE. Its replacement initializes the existing verdict cache and
an explicitly exhausted standing-search budget; production null guards were
not added for tests.

## Evidence limits

The tests execute the real election method, Traverse cost, extracted RUNNING
backplace actor, pause/completion and actual mining entry. Constructor-bypassed
fixtures supply world, inventory, route and immutable-context inputs; they do
not replace the tested methods. The backplace tests start after the unchanged
outer actor's inventory/licence/preparation gates. They do not execute an
entire A* search, whole `Movement.update`, client/server physics, normal
inventory swap or live placement. The lifecycle fixture supplies the executor's
stored `safeToCancel=false` result for its still-missing floor; the real cancel
method, actor, input predicate and BBH/controller boundary run without replacing
their methods. This does not claim a full `PathExecutor.onTick` physics run.
No full suite or runtime JAR was requested
for this review stage. Those and the isolated upper-platform game comparison
remain before any release or claim of measured throughput improvement.

The stored Towers example in the approved design is motivation, not a special
coordinate case in production. General upper/lower route scoring, edge stances
for partial blocks and multi-step forecasts remain outside this change.
