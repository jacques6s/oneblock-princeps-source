# Owned helper cleanup before a structural layer advances

Base: `b14a115a971975407cf9934aeaefb51865433309`. This isolated change keeps
the complete published 222 excavation implementation, Builder/Home integration,
full-model support protection and shared API. It does not change the client
schematic adapter or classify original fluid targets.

At an otherwise completed STRUCTURE layer, `advanceLayerIfClean` performs S10
and asks `prepareLayerCleanup` for due owned helper debt before incrementing
`layer`. Bottom-up builds include owned helpers at or below the completed band;
top-down builds include those at or above it. Future-band helpers are retained.

One flag and the existing cleanup-target set keep this work separate from final
full-model cleanup. The current layer mask stays in place. Confirmed, currently
owned helper targets enter the ordinary BREAK work set and existing lane-A
pathing, mining, safe cancellation and bounded cleanup-escape behavior. The
existing model/support guards and platform no-mining contract still apply.

A local disappearance does not release the ledger or allow a layer advance.
The real ledger's server observation can discharge ownership directly into AIR,
WATER or another replacement state; no intermediate AIR frame is required.
Pending cleanup observations also refuse Home admission and final completion.
The next boundary attempt rereads the actual layer through S10.

Protected supports are never turned into mining permission. When no ordinary
cleanup work remains, a required support or an explicit anchor for an unfinished
later target produces a named LAYER_UNBUILDABLE report. An anchor whose exact
promised state is already present in the full model is released even if the
working layer masks its target. This is a bounded diagnostic limitation: there
is no new scheduler to reorder higher-layer work, and a reported dependency is
not a completed build.

Offline evidence is under `build/layer-cleanup-evidence`. The unchanged base
produced the causal `admission-red-confirmed` result: two tests, one work-admission
assertion failure and one passing control. Initial fixture typing and global
headless-settings failures remain separate. `boundary-green` passes all twelve
cases. They exercise the actual production layer-transition method, real BCC
and fullRecalc admission, BBH damage guards, ledger server observations, fresh S10
world reads, AIR/WATER/replacement results, both layer directions, protected
current/future support, platform retention and explicit anchor dependencies.
They do not claim complete A* physics or live-server execution. Server values
are supplied to the actual ledger; the existing packet callback is unchanged.

Final complete-suite counts and committed-source runtime/API pins are recorded
separately in `final-full/final-artifacts.json`. No live or release acceptance is
asserted by these offline checks.
