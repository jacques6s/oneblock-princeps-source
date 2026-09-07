# Preserve external supports before their model neighbours are built

Base `5e1d0b64d6a9e04fd9641cc45c4dda939882e553`; isolated branch
`codex/builder-future-support` in `Princeps-codex-future-support`.

The earlier full-model guard retained built material and the actual support of
matching model neighbours. It did not retain a boundary foundation when its
desired door was still AIR: the external cell had no primary model state, and
the neighbour predicate required current block identity to match the model.
Both navigation costs and the final actual-crosshair guard used that predicate.

The frozen Main trace (`basalt65-modelguard221-20260906-01`, SHA-256
`b152194a818f1fb5735993af266bd2f2c4d8b223121ac6e32c89bc52e65cef84`)
shows `MovementAscend` at ticks 218–228 followed by `NO_FACE` and a missing
external foundation at tick 229. It contains no per-hit foundation-removal
record. Root separately reported the saved before/after comparison: exactly one
of 163,840 cells changed, the external foundation STONE→AIR, with the 71,442
model cells unchanged. The source/trace establishes the permitting path; the
trace alone is not presented as a server acknowledgement of who removed it.

## Production change

Only `BuilderSupportDependencies.removal` changes. For a candidate removal
**outside the full model's geometric bounds**, each directly adjacent non-AIR
desired model state is checked with its real Vanilla `canSurvive` predicate in
the existing read-only views, before and after replacing the candidate with AIR.
If it can survive before but cannot afterwards, removal is `REQUIRED_SUPPORT`.
This applies while the target is absent, has the wrong identity, or awaits a
state repair. It preserves an existing prerequisite; it does not place outside
the model or invent a support for an already unsupported target.

The prior check of the actual matching neighbour is retained. Thus a wrongly
oriented attachment keeps its current anchor as well as a necessary future
anchor. Internal model-AIR helper cleanup and the explicitly selected wrong-state
paired repair retain their existing rules. No block list, item reservation,
drop assumption, cost weight, timer, route permission, or API change is added.
The existing actual executor binding supplies the same policy with fresh world
reads before owner/pause fallthrough. Ordinary/excavation routes are unchanged.

This is a direct `canSurvive` dependency check, not a general physics, falling
block, neighbour-update cascade, or arbitrary multi-cell dependency simulation.
No mutation is made to the real world or its shared planning cache.

## Controlled evidence

With unchanged base production, the new 14-test class produced **14 tests,
9 assertion failures, 5 passing controls, zero errors/skips**. Failed cases
covered absent door/floor torch/wall sign/ceiling attachment, desired versus
current orientation/identity, final controller damage and a retained unsafe
route after pause or owner handover. The original XML is retained in
`build/future-support-evidence/red/xml`.

The final targeted run passed **63/63, zero failures/errors/skips**, comprising
the new 14 cases and existing support, model-material and route lifecycle tests.
Three prior assumptions about allowing foundation removal after the actual door
disappeared were corrected to the new desired-support contract. One intermediate
63/1 run exposed the third such diagnostic-latch control and is preserved in
`green`; it is not counted as another causal product RED. The latch control now
uses absent support for its allowed interval, then restores the foundation and
door to verify a new refusal. Unrelated real stone mining is tested separately.

Tests execute actual BCC, policy, Input predicate, BBH/controller, retained
executor and cancellation boundaries. They reuse isolated constructor-bypassed
fixtures; movement lists, unsafe-cancel state and world states are controlled
inputs. They do not establish a complete A* route, Minecraft physics, a live
alternative route or successful Main completion. Existing cost/live checks for
explicit repair, internal AIR helpers, wrong primary identity, unrelated terrain
and unknown loaded world remain covered.

Targeted command (JDK 25.0.3, offline, separate cache, max two workers):

```
gradlew.bat --project-cache-dir .gradle-future-support --offline --max-workers=2 test --tests princeps.process.BuilderFutureSupportTest --tests princeps.process.BuilderSupportDependenciesTest --tests princeps.process.BuilderModelRestorationTest --tests princeps.process.BuilderModelRouteProtectionTest --console=plain
```

Final source/report pins are in `build/future-support-evidence/source-review.json`.
Full-suite/runtime results will be recorded separately after independent review.
No live, remote or publication actions were performed from this worktree.
