# Bounded cleanup descent — design only, 2026-09-06

No escape-helper behavior is enabled by this change. The target/probe correction is separate.

The actual 220 comparison separates two faults: b12 stopped at 124 correct cells with an unbound pure BREAK
composite and repeated transient MovementFall; the context-preserving 582 runtime reached 144 correct cells
but still had an unbound pure BREAK composite. The standing platform is four blocks above the permanent ground;
the configured safe fall is three. MovementDescend needs an existing lower landing support, MovementTraverse
can bridge only at its current foot height, and MovementDownward needs a standable block two levels below.
Allowing generic Lane B alone neither proves a descent nor bounds the extra cleanup debt.

## Proposed single-helper contract

One finite cleanup episode owns **one original BREAK target and at most one extra helper**, and has no recursive
entry from that helper. It starts only after a current, loaded, unsuccessful Lane-A search. Model, world, target,
normal break/place permissions, material and loaded-area checks remain applicable. Row/AutoDig modes do not enter it.

1. Enumerate the finite set of nearby AIR cells within the current player's ordinary placement reach and below
   the current platform. Each must have a real clickable face, obey the template AIR restriction, and accept an
   ordinary standable, non-gravity throwaway. Call the existing real placement/raycast oracle from the actual
   current stance. A hypothetical face or a stance reached by a future helper is insufficient.
2. On a **new private BuilderCalculationContext**, use its existing `bsi.nimmBlockAn` for that one candidate.
   Ask an A-only path to a loaded, lower, existing permanent floor with clear feet/head and no fluids. Require a
   complete path using normal movements and unchanged fall limits. The route may not mine permanent/template
   blocks or rely on a second helper. Prefer a descent-only route which needs no mining at all.
3. Independently prove normal A-only access from the safe end area to the original cleanup target and the new
   helper, including an actual safe break stance/ray and return to that permanent area. These paths must not use
   any block scheduled for cleanup as their footing. A pair of unrelated reachability booleans is insufficient:
   check the sequential post-removal geometry. Existing `nimmBlockAn` represents only one assumed block; if a
   proof needs multiple simultaneous assumptions, report that limitation instead of mutating the client world
   or silently using the existing `mitAngenommenemBlock` helper (which changes the client world temporarily).
4. Only after all proofs, execute the ordinary checked placement and wait for server confirmation. Revalidate
   the route in the real world, follow normal MovementTraverse/Descend/Fall as proved, then remove the original
   helper and this extra helper from the safe area with ordinary mining checks. Preserve the original work owner
   through the intermediate placement/route; the extra helper must not become a fresh top-level Lane-B request.
5. Before placement, each candidate may be rejected once per unchanged world episode; the candidate set is
   finite. After placement, no second helper may be created until this helper is server-confirmed AIR. A failed
   route/confirmation/cleanup retains the explicit debt and ends normally under the existing bounded watchdog;
   a target change or user cancellation must not pretend that the remaining helper was removed.

This needs a small explicit episode record (owner, candidate, proved end area, stage, rejected candidates,
server-confirmed debt), not a new planner framework. The old `openScaffoldPhase` is not directly interchangeable:
it has a placement-oriented served-cell lifecycle, can accept an unproved candidate, and drops its override when
that cell is served. Its per-layer count also does not bound navigation-owned helper debt.

## Required countercases and acceptance

- One legal intermediate step converts a forbidden four-block direct drop into permitted individual drops;
  unchanged user maximum, no forced fall and no change to any movement cost.
- No real face, out-of-reach/occluded placement, fluids, protected/non-AIR template cell, missing material,
  gravity/non-standable material, unloaded cells and incomplete/timeout/cancelled probes produce no helper.
- Reject a reachable intermediate platform with no onward permanent floor, and a helper which cannot be mined
  from the proved end area or whose removal destroys its own cleanup stance.
- After one helper is confirmed, repeated failed paths cannot create another. Completion of the original target
  does not discard the helper debt; confirmed removal is the only completion of that debt.
- First use the preserved 144 world as a read-only geometric fixture, then a fresh isolated arena and independent
  full world oracle. Existing target/probe unit tests do not establish successful descent in game.
