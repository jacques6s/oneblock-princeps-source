# Builder integration and real comparison, 2026-09-06

The Basalt main bench on localhost:25565 remains the owner acceptance target.
Its 63×18×63 schematic at (80,-60,180) contains 16,342 non-air target cells.
Run05 stopped normally after remaining at 959 client-correct cells for almost
24 minutes. That counter is not a full independent world acceptance result.
The owner requested stable target commitment and permanent progress checks,
with general fixes to observed error classes rather than fixture-specific rules.

## Integrated changes

- AIR does not qualify as an ordinary template block that exempts a route from
  its placement licence. Lane A must not plan a helper its executor prohibits.
- Unsupported cells receive a persistent NO_FACE verdict. Releasing a parked
  cell invalidates its verdict and stance memo together; actual support changes
  invalidate stale support judgments even if the neighbour mask is unchanged.
- A missing hotbar item is an unknown material observation, not a geometric
  NO_STANCE proof. Existing geometry proofs and material-independent break
  routes keep their meaning.
- Target commitment survives temporary hotbar/search-budget gaps and unrelated
  completions. Progress uses server-confirmed requested changes, actual route
  advancement and mining progress. Rotation, newly allocated goals and blind
  nudges do not count. A five-second diagnostic precedes a bounded 60-second
  no-progress stop; deliberate pauses suspend that clock.
- Both GitHub build and release workflows now run `test jar apiJar` and preserve
  test reports. Previously these workflows built only the runtime JAR.

The first three fixes were committed as f3154a8; progress integration is 2ff305c.
The merged full suite freshly ran 706 tests: 702 passed, zero failures/errors,
four external trace-replay cases skipped. Its reports and command log are retained
under `build/integration-inputs/progress-combined-full-*`.
The runtime is 5,819,046 bytes, SHA-256
`781b9a534d9710c90e1abbf0f2b8313d6ac9c24fdb3496aef2b056bbf83746af`.
The 228,177-byte public API JAR remains
`9dff11d8dcdb540c156eb0d17abf6a5e9d574c115cd92c138eeda7123f82f0b1`.

## Isolated comparison and remaining cause

The separate vanilla Survival bench on localhost:25568 uses the same 272-target
multi-region fixture, initial inventory, profile and 20 TPS for each comparison.
No supply, teleport or terrain repair occurs after Build. The independent oracle
comes from geometric predicates, not the engine's schematic parser.

The published baseline reached 40 targets. AIR-lane correction reached 59.
The three-fix integration also reached 59: traces show that parked cells are
now genuinely reconsidered, but stance searches still reject inner template AIR
as temporary floor space. The existing predicate permits only an absent template
cell (`null`), while Lane B permits temporary helpers at explicit AIR too.
Outer pillars progressed because helper positions existed outside the bounds.

After the three-fix run's normal deadline stop, independent server reads confirmed
59/272 target states and all 1,369 floor cells. The full 21,904-cell comparison
failed; at least one AIR region also differed. This is a partial result, not a
completed structure or cleanup pass. At the blocked inner pillar, Root verified
the candidate helper cell (76,-60,70), feet/head cells above it and target
(77,-58,70) were AIR, with stone below the helper and stone bricks below the target.

The exact progress runtime then reached the same 59 independently confirmed
targets. It emitted PROGRESS-DIAGNOSE at five quiet seconds and PROGRESS-STOP at
60, recorded PLACEMENT_FAILED, and stopped normally after 112 seconds, before
the 240-second external deadline. The transformed mining accessor was verified
in the actual client. See the separate bench's verification/PROGRESS-COMPARISON-20260906.md.

The same runtime was then loaded through the existing, normally licensed
Bootstrap override path for a bounded owner run on 25565, preserving clear=false.
The Basalt counter advanced from 959 to 1,332 before another explicit 60-second
stall at (90,-59,223). Two previously blocked wall signs and their required glass
supports passed independent server probes. The terminal counter also contained
one wrong cell; no whole-farm acceptance is claimed. Normal stop completed at
10:46:52.664 UTC. Administrative supply made 19 inventory reads and three refill
batches; a missed empty redstone stack was restored separately only after stop.

## Further inner-AIR stance correction

The current source also allows explicit AIR as temporary floor space in ordinary
3D builds, retaining row-mode restrictions, body clearance, material and real
template reservations. It reuses the current calculation block view for that
scaffold search. The existing non-scaffold stance method preserves its original
fluid short-circuit: the first merged full run caught premature block-view
construction in that method. That failed report is retained, the original order
was restored, and two regressions cover fluid in feet/head before live access.

Controlled inner-AIR RED: three expected AIR/CAVE_AIR/VOID_AIR failures, nine
passing counterexamples. Controlled fluid-order RED: two expected failures,
12 passing controls. Final merged full suite: 720 tests, 716 passed, zero
failures/errors, four external trace skips. JAR/API both built successfully.
The resulting 5,819,233-byte runtime has SHA-256
`b12d8292cbcf09f16e495eb6c0a9f40e1bdc7136ea780cd22f6127c6958491a6`.
Its real component comparison remains pending at this revision. No customer
release or complete Basalt build has been accepted.
