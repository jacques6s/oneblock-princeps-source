# Platform traverse on the current Home builder

This isolated integration applies reviewed platform source
`e2ebd27143f764b0102cef5147ffad443ff02ba0` to Home/cleanup/future-support base
`10874d33ca6d82b331ad898c51068c695c144dfb`. The API compatibility commit
`521e66d` is deliberately not applied: this base already has the current API.
No client, server, world, release or live-run configuration is changed.

## Conflict resolution

The permanent, adjacent full-cube offer and normal sneak-backplace actor retain
the platform source's scope. The merge preserves both required movement imports,
the Home pause/input hold, and the complete cleanup action chain. Platform pause,
target completion and normal build reset withdraw the current offer; an unsafe
old marked executor retains its no-mining rule until it actually ends.

The newer current-world future-support policy and selectedSupportRepairState
remain unchanged. The shared removal policy additionally refuses mining during
a platform offer. At the actual input/controller boundary, both the immutable
model-protection rule and immutable platform-route refusal remain in effect.
The old platform lifecycle fixture now wires its real BlockBreakHelper into the
input object, matching the production constructor rather than dropping the newer
model guard for that fixture.

Platform's former inline routing tail is ported into the newer shared
finishBuilderRoute, retaining target-bound Lane servicing for ordinary recovery
returns. A platform offer routes only to its own marked goal with its exact
Lane-A approach snapshot. Its early recovery return also uses this shared
finisher. Cleanup keeps its separate contexts and existing dispatch priority.

Two integration boundaries were found during independent source review:

- A completed negative Lane-B answer can withdraw an offer during route
  finishing. The finisher now cancels that withdrawn marked route instead of
  emitting its old goal with an ordinary replacement context.
- A Lane question binds the exact platform approach identity. Clearing or
  replacing the offer invalidates old answers even when coordinates and goal
  values are equal.

These are compatibility fixes between the reviewed mechanisms; no helper,
mining, fall, reach, world or model permission is widened.

## Verification

Independent focused source review accepted the merged guards. The first fresh
targeted run passed **191 tests**, zero failures, errors or skips. It covers
Home, platform election/actor/mining lifecycle, model restoration/protection,
future support, support dependencies, route context, Lane evidence and all
cleanup suites. Three additional tests execute the real Lane result consumer
and route finisher for withdrawn and retained offers and check stale snapshot
identity. Their supplied approach/context inputs are explicit; actual platform
election remains covered by BuilderPlatformTraverseTest.

The owner's Basalt fixture is copied only into this worktree's ignored run
directory, with SHA-256
`202bab5dbb24f99f180db44faaa6ee9daecb949647d946e7294e9ba25f7414e4`.
Builds use a dedicated project cache, at most two workers, offline dependencies,
`--rerun-tasks` and `--no-build-cache`. Evidence, invocation records, XML and final
runtime/API comparisons are under `build/home-platform-evidence`.

This source integration and its tests do not establish live placement,
throughput or automatic Home recovery. The engine-only candidate still requires
Root's controlled game comparison before making those claims.
