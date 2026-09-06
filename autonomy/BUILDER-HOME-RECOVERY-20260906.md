# Optional owned Home recovery

Base: `51f647dd7bd40d4f7cd33f85796c5edbef1a5a44`, the reviewed Schematic-only
Cleanup/FutureSupport integration with the unchanged 221 API. This branch is not
a replacement for the complete released AutoDig implementation.

The concrete V2 Builder exposes five optional methods for the normal client to
discover on its actual builder instance. No shared API source changes:

```java
void setHomeRecoveryEnabled(boolean enabled);
String homeRecoveryState();
long homeRecoveryRequestId();
boolean resumeAfterHomeRecovery(long id);
void failHomeRecovery(long id);
```

States are `DISABLED`, `IDLE`, `QUIESCING`, `READY`, `FAILED`. The request id is
monotonic for that Builder instance and is not reset by the next build. There is
no engine command sender, home name, teleport target or manual request method.

## Admission and retained state

The ordinary 60-active-second progress STOP can enter this opt-in only for a
STRUCTURE build with actual current route failure evidence, a current negative
lane question, or the specifically exhausted, unplaced terminal Cleanup episode.
Material preparation, unloaded chunks, mining, placement acknowledgement waits,
row builds and excavation cannot request it. An active/placed Cleanup episode or
undischarged debt refuses it. The terminal exception requires the precise
`finite helper candidates exhausted` result and no running probe; deactivating it
retains attempted owners and the ordinary scaffold ledger.

One attempt is consumed per confirmed build-action revision. The revision comes
from `observeScaffoldServerChange` consuming an armed expected state exactly once,
through `ConfirmedBuildActions`. It is not `ActionJournal.confirmedWorldChanges`
or damage ticks. The production packet producers are the existing RETURN hooks
for `handleBlockUpdate` and `handleChunkBlocksUpdate`. Unrelated/repeated state
events do not arm the next attempt. A successful Resume absorbs revisions earned
by an unsafe movement finishing during quiescence, so a new attempt requires a
subsequent confirmed action. Enable/disable toggles and elapsed time cannot reset
the budget.

The world, player, full model and absolute origin are identity-bound. The model,
work cells, owned scaffolds, debt and attempted-owner sets are retained. READY or
FAILED is an owned pause, not a terminal abort and not a restart. A stale token,
changed identity or lost selected owner cannot resume it. The normal user build
cancel/replacement still follows existing teardown.

## Quiescence and Resume

`cancelSegmentIfSafe` is used as-is. An unsafe current movement may finish under
its actual existing calculation context while the state remains QUIESCING;
there is no force-cancel or teleport permission during that movement. READY
requires current/next/inProgress all empty, no late acknowledgement debt, no
active Survival consumption, released use/attack keys, loaded collision-free
grounded body, negligible horizontal motion and two stable position samples.
The existing 10-second quiescence budget expires into a retained FAILED pause.
No movement reach, cost, cancellation safety or build-stall deadline is relaxed.

The actual selected Home owner keeps `PlayerMovementInput` with its cleared
forced inputs even when no route remains. Input and Survival inspect the actual
selected process before touching a Builder; they never select a lazy engine for
AutoDig or another process. Survival uses its existing eat/repair teardown to
release its own use hold. The input-owner callback also revokes a Home hold after
an intervening owner or OUT event.

Only the client establishes the server-applied return pose and safe landing.
Resume rechecks the token, owner, identity, empty routes, body and action gates.
It invalidates placement/repair/lane/executable route proofs and route-progress
samples. Only UNREACHABLE parks are released for the new departure position;
NO_FACE/NO_STANCE dependency facts remain. No model, ownership or cleanup attempt
is adopted or erased. The progress watch gets one explicit relocation grace
period without increasing the confirmed-action revision.

## Offline verification and limits

`BuilderHomeRecoveryTest` has 18 cases. They execute real Builder admission,
hold, Resume, PathingControlManager.preTick, InputOverrideHandler.onTick,
SurvivalBehavior.onTick, safe segment cancellation, scaffold-ledger ACK and the
confirmed-action callback. Stored movement cancellation safety, world loading,
body collision and groundedness are explicit fixture inputs. The actual Input
test uses the existing deterministic BBH rhythm seam, not a global API shim.
There is no full PathExecutor physics or real server Home command claim.

Initial 15 cases passed. The expanded first run had one isolated headless
humanization-settings initialization failure before its Input assertion; the
existing deterministic rhythm input corrected that fixture. The expanded 18
then passed. Four narrow safety mutations (route-empty, late ACK, owned eat
teardown, selective C-park release) produced five assertion failures in 18 cases,
with 13 controls passing and no runtime errors. The first result collector was
too strict about JUnit `ComparisonFailure`, which extends `AssertionError`; the
original XML/log and this distinction are preserved. Sources were restored
byte-for-byte in `finally` before the final related/full verification.

Evidence is under `build/home-recovery-evidence`. Final full-suite totals,
source/archive pins, runtime/API hashes and the 187-class API comparison are
recorded separately in `full/final-artifacts.json`. No live acceptance, release
or publication is asserted by these offline checks.
