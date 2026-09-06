# Cleanup escape verification — 2026-09-06

Candidate based on `8f5639e7bd5d9b313eb2003a011ed9fd38acb889`, with exactly the reviewed Freeze03 production
and test sources. Root and the independent reviewer accepted Freeze03. The nine original source/test/document
pins remained unchanged through the targeted run, all counterfactual tests and the final full build.
No server, client, RCON, account, remote repository or published artifact was changed by this task.

## Fresh green results and artifact pins

The corrected targeted run started at **16:50:36.754Z**, ended at **16:51:39.978Z**, and passed **65/65** tests,
with zero failures, errors or skips. Those include all six additional ownership/execution cases and the two
existing BlockPlaceHelper tests for the exact support, face, target, slot, material and main-hand receipt.

After all five mutations were restored, the unmodified authoritative worktree ran the full suite, `jar` and
`apiJar` from **17:00:16.389Z to 17:01:13.410Z**. All **12 tasks executed freshly**. Result: **96 suites,
790 tests, 786 PASS, zero failures/errors, four pre-existing TraceReplay skips**. BasaltDryRun was **7/7 PASS**,
including the owner's fixture cases. The input `run/schematics/etz-basalt.litematic` was 12,077 bytes with SHA256
`202bab5dbb24f99f180db44faaa6ee9daecb949647d946e7294e9ba25f7414e4`.

| Artifact | Bytes | SHA256 |
|---|---:|---|
| Runtime `princeps-1.17.0.jar` | 5,844,141 | `6f918bcd000e31d210d814f6b0965b8d78a3c05baac55c4f5434332fe8fd97b9` |
| API `princeps-1.17.0-api.jar` | 228,177 | `9dff11d8dcdb540c156eb0d17abf6a5e9d574c115cd92c138eeda7123f82f0b1` |
| Full build log | — | `2dca58a5a3c0659865664c9a8438e3b32adcc2ef90c7cca680ef0d231def87da` |

The API jar is byte-identical to the previously accepted facade. Each of its **187 class entries** was also
compared individually against the same entry in the runtime; all matched. The class comparison record is
`build/cleanup-escape-evidence/api-classes-full-vs-facade.json`, SHA256
`1313e356fc0d8dcfb04cf075eb3507a637058decc81984833d9418f8a112dc49`.

Command settings for the full run: `test jar apiJar --rerun-tasks --no-build-cache --no-configuration-cache
--no-daemon --max-workers=2 --console=plain`, using JDK `C:/Users/jacqu/tools-jdk25/jdk-25.0.3+9`, dedicated
Gradle user home `C:/Users/jacqu/Desktop/Princeps-codex-break-contract/.gradle-user-break-full`, project cache
`.gradle-cleanup-escape`, and the fixed Java installation with autodetect/autodownload disabled. Each targeted
run used the same reproducibility controls with seven explicit test-class selections. No lock or dependency
verification settings were loosened.

## Counterfactual results

Five predeclared mutations ran sequentially in the separate checkout
`C:/Users/jacqu/Desktop/Princeps-codex-cleanup-escape-mutants-20260906` with its own output/project-cache directory.
The authoritative sources never received a mutant. Every run executed all 65 selected tests, then restored and
verified all nine source pins in the comparison checkout. All failures were exactly the predeclared
`java.lang.AssertionError` cases, with **zero bootstrap/runtime errors and zero skips**.

| Deliberate fault | UTC start → end | Expected and observed assertion failures |
|---|---|---:|
| Remove current debt/episode check from real mining cost | 16:53:48.880 → 16:54:37.292 | 2 |
| Remove the real DOWNWARD stop for a foreign solid | 16:54:52.002 → 16:55:37.277 | 1 |
| Admit a request to the ledger in the local-success callback | 16:56:54.511 → 16:57:31.460 | 1 |
| Let REMOVED debt reacquire a later identical block | 16:58:02.821 → 16:58:38.976 | 3 |
| Replace the ordinary fresh fall decision with stale route inventory | 16:59:04.477 → 16:59:43.052 | 3 |

The callback mutation is deliberately a semantic fault injection at the actual callback: it admits that
already-reviewed AIR/throwaway request too early. It is not presented as an exact old-source rollback.
Deleting the return alone would enter unrelated live global settings in the headless fixture and fail in
bootstrap, which would not establish a causal test failure. No global provider/API shadow was introduced.
The fall mutation likewise tests loss of the fresh inventory decision; it is not a Minecraft fall-physics test.

The predeclared mutation manifest has SHA256
`197fd2d191747b550e74e25e3f840d7babd06fb39174d0d1287b9bfd775813b8`.
Each result records exact failed test names, source hashes, timestamps and restore checks. Mutant classes stay
quarantined in the comparison checkout's build output; no mutant jar was packaged, installed or published.

## Scope: navigation helpers and direct build supports

The new escape continues the existing **navigation Lane-B** placement scope. This is distinct from the
direct, target-serving support rule in `scaffoldCellIsAllowed`. All the following behavior is present already
in base `8f5639e7bd5d9b313eb2003a011ed9fd38acb889`, not introduced by the escape candidate:

- `BuilderCalculationContext.placementLicence`, case `B_HELPERS_ALLOWED`, explicitly returns true for a
  coordinate outside its fixed schematic dimensions. Inside, it consults the requested state and rejects a real
  template block. The corresponding `costOfPlacingAt` branch returns `HELPER_BLOCK_COST` for an outside
  (`sch == null`) cell only when a throwaway is available.
- `scaffoldIsLicensedAt` accepts a matching constrained current-route licence. The real execution gates in
  `MovementHelper` and `MovementPillar` check both the builder policy and the route licence. Thus the outside
  navigation branch is executable existing behavior, not merely a stale comment or unconsumed cost.
- `scaffoldCellIsAllowed` is the separate direct build-support path and rejects coordinates outside the
  schematic. Its historical owner-comment wording does not supply new authorization for another mechanism.

`cleanupMayOccupy` retains the existing outside navigation scope, checks actual AIR, normal place/throwaway,
protection and border conditions, and requires explicit model AIR inside the full model. This is a new bounded
navigation action chain, not a change to the direct support rule. The old cleanup-design document only says
to obey the template AIR restriction; it is not cited as independent outside-volume authorization. Root
explicitly reviewed this distinction and requested preserving the existing executable navigation rule.
The unchanged base source and final source archive permit direct verification of these methods.

## What remains unproved

These checks establish the request/server ordering, revocable execution ownership, strict route contracts,
separate real BSI overlays and unchanged normal fall-decision boundary. Synthetic path-height checks and the
offline full-cube geometry witness are not positive Minecraft-physics evidence. Successful execution of the
entire helper → parkour → Downward → permanent-ground chain remains pending on the normal licensed 220 path.

The isolated game comparison must retain the prior 144-world evidence, compare reported correct cells at equal
client-tick windows, and perform a complete independent world oracle including AIR and removed helpers.
This task claims no successful live descent, full schematic completion, speed improvement or 16k reliability.
Unresolved request/debt survives stop conservatively; automatic reconciliation of orphaned requests remains an
explicit limitation. The original 158-based uncompiled WIP and all earlier test/compile evidence are preserved.
