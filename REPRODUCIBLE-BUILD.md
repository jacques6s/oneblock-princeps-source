# Rebuilding the Princeps runtime and API facade

This packaging preparation starts from
`0855a98d6c55f508a51c883df23e779739497a5b` (the Survival scaffold cleanup fix).
It changes build configuration and verification tooling, not the implementation.
It is a candidate for a later corresponding-source update; it does not replace
the existing OneBlock artifact or its published source offer by itself.

## Fixed inputs

- Gradle wrapper: 9.4.0, binary distribution SHA-256
  `60ea723356d81263e8002fec0fcf9e2b0eee0c0850c7a3d7ab0a63f2ccc601f3`.
  This matches the official
  [Gradle distribution checksum](https://services.gradle.org/distributions/gradle-9.4.0-bin.zip.sha256).
- Fabric Loom: released version 1.15.5, replacing the moving 1.15-SNAPSHOT marker.
- Build JDK used for the recorded comparison: Eclipse Temurin 25.0.3+9 LTS.
  Compilation uses UTF-8 and `--release 25`.
- Minecraft 26.1.2, Fabric Loader 0.18.4, Nether Pathfinder 1.6, jsr305 3.0.2.
  Resolved versions are in `gradle.lockfile`; downloaded plugin/dependency bytes
  are pinned by `gradle/verification-metadata.xml`.
- Version comes from `mod_version=1.17.0` in `gradle.properties`. Local Git tags,
  dirty status, absence of Git, and the exporting computer do not change it.
- Text inputs use LF. Archives have stable entry order and normalized timestamps.
  Both produced jars include the existing LGPL-3.0 `LICENSE` under `META-INF`.
  Source copyright headers and other notices are retained.
  Loom 1.15.5 nests dependencies after Gradle's archive writer; a final action
  waits for its workers and uses Loom's archive normalizer to remove the new
  nested entry's wall-clock timestamp as well.

## Build from an exported source tree

Use a dedicated Gradle user home. A separate project directory alone does not
isolate Loom's Minecraft cache from another running build or client on Windows.

PowerShell, with paths chosen for your machine:

```powershell
$jdk = 'C:/path/to/temurin-25.0.3+9'
$dependencies = 'C:/path/to/isolated-princeps-gradle'
$env:JAVA_HOME = $jdk
.\gradlew.bat --gradle-user-home $dependencies clean jar apiJar `
  --no-daemon --no-build-cache --no-configuration-cache `
  "-Dorg.gradle.java.home=$jdk" `
  "-Porg.gradle.java.installations.paths=$jdk" `
  '-Porg.gradle.java.installations.auto-detect=false' `
  '-Porg.gradle.java.installations.auto-download=false'
```

Outputs are `build/libs/princeps-1.17.0.jar` and
`build/libs/princeps-1.17.0-api.jar`. The first is the separately loaded Fabric
runtime mod. The second contains the API source set for consumer compilation.
The same API class bytes must occur in the full runtime jar.

Do not use `--write-locks`, `--update-locks`, `--write-verification-metadata`, or
disabled dependency verification when reproducing a release. Updating those
files is a reviewed source change, not part of the rebuild procedure.

## Independent comparison

Export one committed revision with `git archive`, extract it twice into new
directories, and run the build command above sequentially in both directories.
Each extraction starts with no `build/` or project `.gradle/` directory. Reusing
the isolated downloaded-dependency cache is permitted; build and configuration
caches are disabled, and `clean` is requested for both runs.

With Python 3, compare the two outputs and optionally the currently distributed
API and runtime jars:

```text
python scripts/compare-release-jars.py A/build/libs B/build/libs
  --baseline-api /path/to/current/princeps-api-1.17.0.jar
  --baseline-runtime /path/to/current/princeps-1.17.0.jar
  --output archive-comparison.json
```

The report records whole-file SHA-256, entry inventories, entry-content and ZIP
metadata differences, nested-jar checksums, notices, and runtime/API coherence.
It also conservatively compares declared public/protected JVM descriptors,
linkage flags, parents, constants, and newly abstract methods with the old API.
This is a binary-interface inventory, not proof of behavioral compatibility or
successful integration in Minecraft. No code from a compared jar is executed.

Keep the source archive, its commit ID and SHA-256, both build logs, both artifact
pairs, and the comparison report together. Publish the exact corresponding
source before replacing a distributed binary; preserve older source offers for
older binaries. This preparation performs no publication or OneBlock pin change.
