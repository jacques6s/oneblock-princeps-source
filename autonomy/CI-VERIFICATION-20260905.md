# Portable Minecraft input verification

Base source: `5abc76881a28c1bab49941ca374d8f4f0fd5c165`.
Isolated worktree: `Princeps-codex-ci`, branch `codex/princeps-ci-verification`.
The implementation, API and runtime packaging inputs are unchanged. This change
corrects verification of Loom's generated Minecraft dependency, not Princeps
engine code. Nothing was pushed from this worktree; its inherited `origin` is
the private OneBlock repository and must not be used for public publication.

## Reproduction

Public CI run `33982077379` failed at `compileApiJava` because the generated
`net.minecraft:minecraft-merged-deobf:26.1.2` JAR did not match a whole-ZIP SHA-256
recorded from a Windows cache. A completely fresh separate Windows Gradle cache
reproduced the same failure, so this is not merely an Ubuntu-specific issue.

| Generated artifact | Whole-file SHA-256 |
| --- | --- |
| Original verified packaging cache | `039ebfdc071ce4071b9f0498377e3e76d0064bfaf275014c1732b257de1a1c6d` |
| Fresh cache at the same source/JDK | `5da52570eba1aa56bcad307b295aaa8b0fd03c53a38dd5de2bd3dbaf37bf8c7b` |

Independent Python ZIP inspection found exactly 29,433 non-directory entries
in each, with identical names, lengths and content SHA-256 values. Both produce
the canonical digest
`cf9fbb33eb6c0d7d99283a5a451ad506704d0c7ad7bbcf5dbdc5fad9fddcd980`.
All three upstream inputs also matched between the existing and fresh cache.

The [pinned Loom 1.15.5 sources](https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.15.5/fabric-loom-1.15.5-sources.jar)
explain the difference: `MinecraftJarMerger` writes through concurrent workers
to ZIP FS, with non-normalized directory times. In the unobfuscated branch,
`AbstractMappedMinecraftProvider.remapJar` copies that archive into its Maven
location. The pinned plugin's bytecode confirms that copy path.

Loom validates official client/server downloads against Mojang's SHA-1 metadata.
The reviewed pins add SHA-256 checks for the exact official
[version JSON](https://piston-meta.mojang.com/v1/packages/0936ffb5a6dd44735578d5785fa4b0022a2df64c/26.1.2.json),
[client](https://piston-data.mojang.com/v1/objects/4e618f09a0c649dde3fdf829df443ce0b8831e65/client.jar)
and [server](https://piston-data.mojang.com/v1/objects/97ccd4c0ed3f81bbb7bfacddd1090b0c56f9bc51/server.jar).
No checksum was accepted from the failing Ubuntu job or learned during a normal
build. The generated POM checksum and all external verification entries remain.

## Guard and measured controls

Only the exact generated JAR coordinate/version/filename is excepted from
Gradle's ZIP-byte check. `verifyMinecraftInputs` always runs before JavaCompile,
Test, JavaExec and Jar tasks. It checks pinned upstream bytes, the actual resolved
compile/runtime artifact and expected local Loom path, then every generated file
name, actual length and content hash. Duplicate/noncanonical names are rejected.
The digest algorithm and security boundary are specified in
`REPRODUCIBLE-BUILD.md`; it excludes ZIP representation, not any class or resource.

| Measurement | Result |
| --- | --- |
| Original source with completely fresh cache | FAIL at archive verification, before compilation |
| Patched online build after filling missing runtime-library cache | PASS, 52 seconds, 10 tasks executed |
| Append one byte to generated `version.json` | FAIL in content verification; no compiler task started |
| Change one byte in original client JAR | FAIL in upstream verification; no compiler task started |
| Restore original cache bytes, then `clean jar apiJar --offline` | PASS, 27 seconds, 11 tasks executed |

An earlier offline attempt could not resolve runtime libraries that the failing
baseline build had never downloaded; that failed log is retained as a cache
availability result. The subsequent normal online build downloaded them under
the unchanged dependency locks and verification metadata.

Negative controls used Gradle init scripts that mutate only this task's dedicated
cache after Loom configuration. This makes the guard see real wrong input bytes
without letting Loom repair the download first. Each control required nonzero
exit and the specific verification failure before any compiler task. Backups
were restored in `finally`, with full-file SHA-256 equality checked. No shared
Gradle cache, Minecraft source, running bench, or integration-client cache was
modified. The init scripts and original reports are archived with the evidence.

Both successful builds produced the exact already-reproduced and Survival-tested
artifact pair:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Runtime | 5,808,146 | `76b7eaa6a47c0b545c4f19e72cb32458738119b3274975c3257221e54fc9f711` |
| API | 228,177 | `9dff11d8dcdb540c156eb0d17abf6a5e9d574c115cd92c138eeda7123f82f0b1` |

Toolchain: Temurin 25.0.3+9, Gradle 9.4.0, Loom 1.15.5; dedicated cache
`C:/Users/jacqu/Desktop/Princeps-codex-ci-artifacts/gradle-user`; build and
configuration caches disabled. Verification was neither disabled nor regenerated.
All Java source differences from `5abc768` are empty.

Portable raw logs, comparison results, guard reports and control scripts are in
`autonomy/evidence/20260905-portable-inputs`. Local full Gradle reports/backups
remain under the isolated artifact directory. Public Build/Release workflows
upload both the mandatory guard report and ordinary Gradle verification failures.

Linux CI must still pass on the reviewed public PR revision before merge or
release. These local results preserve the exact tested Windows artifact pair;
they do not by themselves establish cross-JDK or cross-OS final-JAR identity.
