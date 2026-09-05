<#
.SYNOPSIS
    Runs builder-bench scenarios end to end, unattended, and reports a verdict per scenario.

.DESCRIPTION
    Boots the dev server (if it is not already up), then for each scenario launches the dev client so it joins that
    server, runs the scenario, and waits for the bench verdict in the client log.

    The point is that a builder change can be validated without a human at the keyboard: one command in, one verdict
    table out. Every run's full client log is archived under run\bench-out\, so a failure can be dissected after the
    fact instead of being re-run to be understood. A human can join the same server from a normal Minecraft client at
    any time to watch (see docs\BENCH.md).

    Exit code is the number of scenarios that did not end in SUCCESS, so 0 means everything passed.

.EXAMPLE
    scripts\bench.ps1 wall

.EXAMPLE
    scripts\bench.ps1 all

.EXAMPLE
    # Watch mode: leave the finished build and the bot in the world afterwards.
    scripts\bench.ps1 ring7 -Keep

.EXAMPLE
    # Three times faster. Verdicts and tick counts are unchanged; only the wall clock shrinks.
    scripts\bench.ps1 wall -TimeScale 3

.EXAMPLE
    # Prove that: run the scenario at 1x and at 3x and require identical verdicts and tick counts.
    scripts\bench.ps1 wall -EquivalenceAt 3
#>
[CmdletBinding()]
param(
    # One or more scenario names, or 'all'.
    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)][string[]] $Scenario,
    # Budget per scenario. A scenario that neither finishes nor judges itself inside this is itself a failure.
    [int]    $TimeoutSeconds = 420,
    # Leave the client in the world after the verdict, for watching. Only meaningful for a single scenario.
    [switch] $Keep,
    # Stop the dev server when done. Off by default: keeping it up makes the next run fast and lets a watcher stay
    # connected between scenarios.
    [switch] $StopServer,
    # Start even if another bench run looks live. Only when you KNOW the other one is finished -- the two would
    # otherwise share one world and one log, and neither set of results would belong to a single run.
    [switch] $Force,
    # Run with or without the client's aim hold. Empty = leave the client's own default alone.
    [string] $AimHold = '',
    # How much faster than real time to run. 1 = normal.
    #
    # BOTH halves are raised together, and that is the whole point: `tick rate` alone speeds the server while the
    # client -- and with it the bot's own logic -- keeps running at 20 Hz. Measured: server 60/s, client still 20
    # ticks per 1.00 second, i.e. the world sped up around a bot that did not. Worse than no speedup, because it
    # warps the environment relative to the thing under test. The client half is MixinDeltaTrackerTimer, passed
    # through as -PtimeScale.
    #
    # Note that the obvious check cannot detect a half-applied speedup: the bench counts CLIENT ticks, so "same
    # ticks, same blocks per minute" is what BOTH the working and the broken case produce. Only wall-clock deltas
    # between log lines separate them, which is what the run's own timestamps are checked against below.
    [double] $TimeScale = 1.0,
    # Run every scenario TWICE, at 1x and at this factor, and require the two to agree.
    #
    # The claim the accelerator makes is "same simulation, less waiting". That claim is only worth anything if it is
    # checked, because the failure mode is silent: an accelerated run that quietly drops ticks or races the network
    # still produces a verdict, just a different one -- and nothing in a single run reveals which of the two you got.
    # Same verdict and same tick count at both speeds is the evidence.
    [double] $EquivalenceAt = 0,
    # Build the layers downward instead of upward. An experiment, not a convenience: an etz-basalt run collapses at
    # layer 4, the row holding 448 of its down-facing pistons, which cannot be placed until something stands above
    # them. See BuilderBench.applyProductionBuildSettings.
    [switch] $TopDown,
    # Spend the whole tick budget instead of ending on the STALLED test. Use this for any comparison: the stall test
    # asks whether the work set shrank or the RETIRED set grew, and neither is monotone, so the same builder produced
    # 1641 placed by t=26240, 2629 by t=55380 and 5069 by t=321700 on three runs.
    [switch] $NoStall,
    # Which builder engine this run grades. Both are registered every session and the choice is resolved once and
    # frozen, so it has to be stated before the client starts. Passed as a system property rather than by writing the
    # `builderEngineV3` setting, because settings persist -- a bench run must not decide what the owner's next real
    # launch uses. Default v2 keeps every archived comparison meaningful.
    [ValidateSet('v2', 'v3')]
    [string] $Engine = 'v2',
    # Write the v3 per-tick trace to run\logs\build-trace-<runid>.log. Off by default: a line per tick is tens of
    # megabytes a run. On, it is the only instrument that can answer "the bot stood still and nothing was logged",
    # because that is precisely the question a fault-only log cannot answer.
    [switch] $BuildTrace,
    # Head-turn speed for this run, in ticks per 90 degrees (1..5). 0 leaves the client setting alone. This is the
    # owner-facing speed/realism dial and the single largest throughput term in the builder, so a comparison run
    # states it explicitly rather than inheriting whatever the last session left behind.
    [ValidateRange(0, 5)]
    [double] $TurnTicks = 0
)

$ErrorActionPreference = 'Stop'
$root      = Split-Path -Parent $PSScriptRoot
$clientLog = Join-Path $root 'run\logs\latest.log'
$serverLog = Join-Path $root 'run-server\logs\latest.log'
$outDir    = Join-Path $root 'run\bench-out'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$ALL_SCENARIOS = @('wall', 'floor', 'dig', 'ring7', 'ringbig', 'oriented', 'doorway', 'facings')
if ($Scenario.Count -eq 1 -and $Scenario[0] -eq 'all') { $Scenario = $ALL_SCENARIOS }

# Scenario names reach the filesystem (log archive, gradle output). A file scenario is named "file:foo.litematic",
# and a colon is not a legal Windows path character -- the first real-schematic run died on exactly that, taking the
# log archive, the server audit and the summary down with it. Names go through here before they become paths.
function Get-SafeName([string]$name) {
    return ($name -replace '[^A-Za-z0-9._-]', '-')
}

# NUR JVMs AUS DIESEM CHECKOUT. Das Muster allein trifft die ganze Maschine: `princeps\.bench` und `KnotServer`
# stehen wortgleich in der Kommandozeile jeder Princeps-Instanz, egal aus welchem Verzeichnis sie gestartet wurde.
# Der Eigentuemer hat am 08.08. einen zweiten Bench-Server auf 25566 aus C:\...\Princeps-shipped aufgesetzt, um
# sich aus dem Weg zu gehen -- getrennte Ports, getrenntes RCON, getrennter Lock. Das Aufraeumen haette ihn
# trotzdem erwischt: Stop-Jvm 'princeps\.bench' loescht JEDEN Treffer, und die Sicherung davor ("laeuft dort
# gerade jemand?") liest nur das Server-Log DIESES Checkouts und ist fuer den anderen blind.
#
# Jede JVM traegt ihren Repo-Pfad in der Kommandozeile (-Dfabric.dli.config=<root>\.gradle\loom-cache\launch.cfg),
# also ist die Zugehoerigkeit direkt ablesbar. Der Trennstrich am Ende ist dabei tragend und kein Schoenheitsfehler:
# "Princeps-shipped" ENTHAELT "Princeps", ein Vergleich ohne ihn traefe beide Verzeichnisse und die Abgrenzung
# waere genau dort wirkungslos, wo sie gebraucht wird.
function Get-Jvm([string]$marker) {
    return Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -match $marker -and ($marker -eq 'KnotServer' -or $_.CommandLine -notmatch 'KnotServer') -and $_.CommandLine -like "*$root\*" }
}

function Stop-Jvm([string]$marker) {
    foreach ($p in Get-Jvm $marker) {
        try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop } catch {}
    }
}

# log4j holds these files open for writing the whole time, so read with full sharing rather than Get-Content.
function Read-SharedText([string]$path) {
    if (-not (Test-Path $path)) { return '' }
    try {
        $fs = [System.IO.File]::Open($path, 'Open', 'Read', 'ReadWrite')
        $sr = New-Object System.IO.StreamReader($fs)
        $text = $sr.ReadToEnd()
        $sr.Close(); $fs.Close()
        return $text
    } catch { return '' }
}

# Wait until $pattern shows up in $path, or $deadline passes. Returns the matching line, or $null on timeout.
# $watch is the launcher process: if it dies without a verdict the run is over, and waiting out the full timeout
# would just turn a crash into a slow timeout.
function Wait-ForLine([string]$path, [string]$pattern, [datetime]$deadline, [string[]]$abortPatterns = @(), $watch = $null) {
    $graceAfterExit = $null
    while ((Get-Date) -lt $deadline) {
        foreach ($line in (Read-SharedText $path) -split "`r?`n") {
            if ($line -match $pattern) { return $line }
            foreach ($abort in $abortPatterns) {
                if ($abort -and $line -match $abort) { return "ABORT: $line" }
            }
        }
        if ($watch -and $watch.HasExited) {
            # Give the log a moment to flush -- the verdict and the exit are seconds apart by design.
            if (-not $graceAfterExit) { $graceAfterExit = (Get-Date).AddSeconds(5) }
            elseif ((Get-Date) -gt $graceAfterExit) {
                return "ABORT: launcher exited (code $($watch.ExitCode)) without a verdict"
            }
        }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

# run-server\ is gitignored, so a fresh checkout has no server config at all and the first run would come up on a
# survival world with no cheats, no RCON and a random bot name -- and fail in ways that look like builder bugs.
# Write the bench's world contract explicitly instead of inheriting whatever the server generates.
function Initialize-BenchServer {
    $dir = Join-Path $root 'run-server'
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

    $props = Join-Path $dir 'server.properties'
    if (-not (Test-Path $props)) {
        $chars = (48..57) + (97..122)
        $password = -join ($chars | Get-Random -Count 20 | ForEach-Object { [char]$_ })
        Write-Host "[bench] writing a fresh run-server\server.properties"
        @(
            'level-name=benchworld'
            'level-type=minecraft\:flat'
            'gamemode=survival'
            'allow-cheats=true'
            'difficulty=peaceful'          # no mobs to blame a failed build on
            'online-mode=false'            # the dev client has no session, and a watcher can join under any name
            'motd=Princeps Builder Bench'
            'max-players=4'
            'view-distance=8'
            'simulation-distance=8'
            'spawn-protection=0'
            'enable-rcon=true'
            'rcon.port=25576'
            "rcon.password=$password"
            'server-port=25566'
        ) | Set-Content -Path $props -Encoding ascii
    }

    $ops = Join-Path $dir 'ops.json'
    if (-not (Test-Path $ops)) {
        # BenchBot must be opped or its own setup commands (gamemode/fill/give/clear) silently do nothing.
        $md5 = [System.Security.Cryptography.MD5]::Create()
        $b = $md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes('OfflinePlayer:BenchBot'))
        $b[6] = ($b[6] -band 0x0f) -bor 0x30
        $b[8] = ($b[8] -band 0x3f) -bor 0x80
        $h = ($b | ForEach-Object { $_.ToString('x2') }) -join ''
        $uuid = "$($h.Substring(0,8))-$($h.Substring(8,4))-$($h.Substring(12,4))-$($h.Substring(16,4))-$($h.Substring(20,12))"
        Write-Host "[bench] opping BenchBot ($uuid)"
        "[{`"uuid`":`"$uuid`",`"name`":`"BenchBot`",`"level`":4,`"bypassesPlayerLimit`":false}]" |
            Set-Content -Path $ops -Encoding ascii
    }

    # Mojang's EULA is the user's to accept, not this script's.
    $eula = Join-Path $dir 'eula.txt'
    if (-not (Test-Path $eula) -or -not (Select-String -Path $eula -Pattern 'eula\s*=\s*true' -Quiet)) {
        Write-Host "[bench] the Minecraft EULA has not been accepted for this server."
        Write-Host "[bench] Read https://aka.ms/MinecraftEULA and, if you agree, put 'eula=true' in $eula"
        return $false
    }
    return $true
}

function Start-BenchServer {
    if (Get-Jvm 'KnotServer') {
        Write-Host "[bench] dev server already up"
        Set-TickRate   # the rate is per server process, and this one may predate this invocation's -TickRate
        return $true
    }
    if (-not (Initialize-BenchServer)) { return $false }
    Write-Host "[bench] starting dev server ..."
    if (Test-Path $serverLog) { Remove-Item $serverLog -Force -ErrorAction SilentlyContinue }
    Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList 'runServer' -WorkingDirectory $root `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $outDir 'server-gradle.out') `
        -RedirectStandardError (Join-Path $outDir 'server-gradle.err') | Out-Null
    $ready = Wait-ForLine $serverLog 'Done \(' ((Get-Date).AddSeconds(180)) @('FAILURE: Build failed', 'Exception in thread "main"')
    if (-not $ready -or $ready.StartsWith('ABORT:')) {
        Write-Host "[bench] server did not come up: $ready"
        return $false
    }
    Write-Host "[bench] server up: $ready"
    # Keep the bench area loaded with no player in it, so the post-run server audit (and any later inspection) can
    # read the build after the client has exited. Without this the server answers "that position is not loaded".
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'rcon.ps1') `
        'forceload add 48 48 96 96' | Out-Null
    Set-TickRate
    return $true
}

# The server half of the speedup. The client half rides along on -PtimeScale; raising only this one is the mistake
# that warps the world around a bot still running at 20 Hz.
function Set-TickRate {
    $rate = [int](20 * $TimeScale)
    $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'rcon.ps1') `
        "tick rate $rate" 2>&1
    Write-Host "[bench] server tick rate -> $rate ($($TimeScale)x)"
}

# Ask the SERVER whether the build really matches, using the cell manifest the run exported. This is the audit that
# makes a verdict trustworthy: the bench itself can only see the client's world, which is a prediction.
function Test-AgainstServer([string]$runId) {
    $result = [pscustomobject]@{ Checked = 0; Mismatched = @(); Misoriented = @() }
    $manifest = Join-Path $root "run\bench-out\$runId-cells.txt"
    if (-not (Test-Path $manifest)) { return $result }
    $lines = Get-Content $manifest | Where-Object { $_.Trim() }
    if (-not $lines) { return $result }

    # A real schematic is tens of thousands of cells and one RCON command each would take longer than the build.
    # Audit an evenly spread sample instead -- and say so, because an audit that quietly checked 3% of the build
    # while printing "confirmed" would be worse than no audit at all.
    $MAX_AUDIT = 600
    if ($lines.Count -gt $MAX_AUDIT) {
        $step = [Math]::Ceiling($lines.Count / $MAX_AUDIT)
        $sampled = @()
        for ($i = 0; $i -lt $lines.Count; $i += $step) { $sampled += $lines[$i] }
        Write-Host ("[bench] server audit: sampling {0} of {1} cells (every {2}nd)" -f $sampled.Count, $lines.Count, $step)
        $lines = $sampled
    }

    # TWO probes per cell, because "wrong" is not one thing. The first asks for the exact state the schematic wants;
    # the second asks only for the right BLOCK, ignoring its properties. A cell that fails the first and passes the
    # second holds the right block turned the wrong way -- which is a completely different bug from a cell that is empty,
    # and the two were indistinguishable in this audit until now. The owner had to spot wrongly-oriented pistons by eye,
    # and the rate (~1.4% of 624) had to be measured by hand afterwards. That is the bench's job.
    $commands = @()
    foreach ($l in $lines) {
        $p = $l -split ' ', 4
        $commands += "execute if block $($p[0]) $($p[1]) $($p[2]) $($p[3])"
        $commands += "execute if block $($p[0]) $($p[1]) $($p[2]) $(($p[3] -split '\[')[0])"
    }
    # Send in BATCHES. Every command used to go out as one argument list, and at 578 cells Windows refused the whole
    # invocation with "the filename or extension is too long" -- the command line has a hard length limit. That did not
    # merely skip the audit: it threw, so the summary table never printed and the script exited 0 while the run it had
    # just judged was STALLED. An exit code that reports success for a failed run is worse than no exit code.
    $rcon = Join-Path $PSScriptRoot 'rcon.ps1'
    $BATCH = 50
    $i = 0
    for ($start = 0; $start -lt $commands.Count; $start += $BATCH) {
        $end = [Math]::Min($start + $BATCH, $commands.Count) - 1
        $chunk = @($commands[$start..$end])
        try {
            $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $rcon @chunk 2>&1
        } catch {
            Write-Host "[bench] server audit batch failed: $($_.Exception.Message)"
            continue
        }
        foreach ($line in $out) {
            $text = "$line"
            if ($text -notmatch 'Test (passed|failed)') { continue }
            # Results arrive in the order sent: exact state, then block-only, per cell. $i counts probes, so the cell
            # index is $i/2 and the parity says which of the two questions this answer belongs to.
            $cell = [Math]::Floor($i / 2)
            $isBlockOnlyProbe = ($i % 2) -eq 1
            if (-not $isBlockOnlyProbe) {
                $result.Checked++
                $exactOk = $text -match 'Test passed'
            }
            if ($isBlockOnlyProbe -and $cell -lt $lines.Count) {
                # The exact-state probe for this cell failed if it was not counted as a pass above; combined with a
                # block-only pass that means the right block is there, facing the wrong way.
                if (-not $exactOk) {
                    if ($text -match 'Test passed') { $result.Misoriented += $lines[$cell] }
                    else { $result.Mismatched += $lines[$cell] }
                }
            }
            $i++
        }
    }
    return $result
}

# "minecraft:sticky_piston x12, minecraft:observer x3", most-affected first -- the same census shape the in-game
# verdict uses, so a reader compares like with like.
function Format-CellCensus($cellLines) {
    $counts = @{}
    foreach ($l in $cellLines) {
        $block = ((($l -split ' ', 4)[3]) -split '\[')[0]
        if ($counts.ContainsKey($block)) { $counts[$block]++ } else { $counts[$block] = 1 }
    }
    return (($counts.GetEnumerator() | Sort-Object -Property Value -Descending |
        ForEach-Object { "$($_.Key) x$($_.Value)" }) -join ', ')
}

# Measured client ticks per REAL second, from the bench's own periodic sample lines and log4j's timestamps.
#
# This is the only honest check that a speedup happened. The bench counts client ticks, so tick counts and
# blocks-per-minute read identically whether the acceleration worked or silently did nothing -- which is how a
# server-only speedup passed review once already. Wall-clock is the one axis that cannot be faked by the thing
# being measured.
function Measure-TickRateAchieved([string]$logText, [string]$runId) {
    $firstT = $null; $firstS = 0.0; $lastT = $null; $lastS = 0.0
    foreach ($line in ($logText -split "`r?`n")) {
        if ($line -match "^\[(\d\d):(\d\d):(\d\d)\].*run=$runId sample t=(\d+)") {
            $secs = [double]$matches[1] * 3600 + [double]$matches[2] * 60 + [double]$matches[3]
            $tick = [int]$matches[4]
            if ($null -eq $firstT) { $firstT = $tick; $firstS = $secs }
            $lastT = $tick; $lastS = $secs
        }
    }
    if ($null -eq $firstT -or $lastT -eq $firstT) { return $null }
    $elapsed = $lastS - $firstS
    if ($elapsed -lt 0) { $elapsed += 86400 }   # run crossed midnight
    if ($elapsed -le 0) { return $null }
    return [math]::Round(($lastT - $firstT) / $elapsed, 2)
}

# Runs one scenario to its verdict. Returns a result object; never throws on a failing scenario, because a failing
# scenario is a result, not an error.
function Invoke-Scenario([string]$name) {
    # Each launch gets an id that every one of its bench lines carries. Deleting latest.log is NOT enough on its own:
    # log4j only rolls the file seconds into the next launch, so for that window the previous run's verdict is still
    # readable and a bare "verdict=" grep scores the wrong run -- which is exactly what happened before this existed.
    $runId = [guid]::NewGuid().ToString('N').Substring(0, 8)
    if (Test-Path $clientLog) { Remove-Item $clientLog -Force -ErrorAction SilentlyContinue }

    # ONE client, always. A second one joins the same world and the same server, so two bots build over each
    # other, the audit reads a world neither of them alone produced, and latest.log gets two writers -- the
    # results are not merely noisy, they belong to no single run. There are two ways they pile up and both
    # happened: -Keep deliberately leaves a client alive after the verdict so the finished build can be walked,
    # and nothing closes it before the next run; and two drivers invoking this script at once each start their
    # own. Neither is worth a diagnosis at the far end, so the previous client is closed here rather than
    # detected later.
    # A leftover client from an EARLIER run has to go -- two bots in one world produce results that belong to
    # neither. A client from a run that is still going is a different thing entirely, and killing it is how one
    # driver destroys another driver's evening. The two are told apart by whether the world is still being
    # written to: a live run touches the server log constantly, a corpse has not for minutes.
    $stale = @(Get-Jvm 'princeps\.bench')
    if ($stale.Count -gt 0) {
        $busy = $false
        if (Test-Path $serverLog) {
            $idleSeconds = ((Get-Date) - (Get-Item $serverLog).LastWriteTime).TotalSeconds
            $busy = $idleSeconds -lt 30
        }
        if ($busy -and -not $Force) {
            Write-Host "[bench] ANOTHER BENCH RUN IS LIVE (server log written $([int]$idleSeconds)s ago)."
            Write-Host "[bench] refusing to start -- it would share the world and kill the other client."
            Write-Host "[bench] wait for it, or pass -Force if you are certain it is finished."
            exit 99
        }
        Write-Host "[bench] closing $($stale.Count) client(s) left over from an earlier run"
        Stop-Jvm 'princeps\.bench'
        Start-Sleep -Seconds 2
    }

    $clientArgs = @('runClient', "-Pbench=$name", "-PbenchRun=$runId")
    if ($AimHold -ne '') { $clientArgs += "-PaimHold=$AimHold" }
    if ($TimeScale -ne 1.0) { $clientArgs += "-PtimeScale=$TimeScale" }
    if ($TopDown) { $clientArgs += "-PtopDown" }
    if ($NoStall) { $clientArgs += "-PnoStall" }
    if ($Keep) { $clientArgs += '-PbenchKeep' }
    if ($Engine -ne 'v2') { $clientArgs += "-PbuilderEngine=$Engine" }
    if ($BuildTrace) { $clientArgs += '-PbuildTrace' }
    if ($TurnTicks -gt 0) { $clientArgs += "-PturnTicks=$TurnTicks" }
    Write-Host ""
    # The engine goes in the header because the archived log is the only thing a comparison has months later, and two
    # engines producing the same verdict format is exactly how a run gets attributed to the wrong one.
    Write-Host "[bench] === $name (run $runId, engine $Engine) ==="
    $started = Get-Date
    $safe = Get-SafeName $name
    $proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList $clientArgs -WorkingDirectory $root `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $outDir "client-gradle-$safe.out") `
        -RedirectStandardError (Join-Path $outDir "client-gradle-$safe.err") -PassThru

    $verdict = Wait-ForLine $clientLog "\[BENCH\] run=$runId verdict=" ((Get-Date).AddSeconds($TimeoutSeconds)) @(
        "\[BENCH\] run=$runId unknown scenario",
        'Exception in thread "main"',
        'Failed to connect to the server'
    ) $proc

    $log = Read-SharedText $clientLog
    foreach ($line in ($log -split "`r?`n")) {
        if ($line -match "\[BENCH\] run=$runId") { Write-Host $line }
    }

    # Archive the whole client log: a verdict says WHAT failed, the log is the only thing that says why.
    # Never let archiving take the run down with it -- the verdict and the server audit matter more than the copy.
    $stamp   = $started.ToString('yyyyMMdd-HHmmss')
    $archive = Join-Path $outDir "$safe-$stamp-$runId.log"
    if ($log) {
        try { [System.IO.File]::WriteAllText($archive, $log) }
        catch { Write-Host "[bench] could not archive the log to ${archive}: $($_.Exception.Message)" }
    }

    if (-not $Keep) { Stop-Jvm 'KnotClient' }

    $state = 'NO_VERDICT'
    if ($verdict -and $verdict.StartsWith('ABORT:')) { $state = 'ABORTED' }
    elseif ($verdict -match 'verdict=([A-Z]+)')      { $state = $matches[1] }

    # The in-game verdict is the CLIENT's opinion, and the client's world is a prediction it applies before the
    # server confirms it. Settle it against the server, which is the only authority. A SUCCESS the server does not
    # confirm is downgraded, loudly: builder and server disagreeing about what got built is worse than a stall.
    # The audit is a cross-check, never the thing that decides whether the script survives. It used to be able to
    # throw and take the verdict, the summary and the exit code down with it.
    $audit = [pscustomobject]@{ Checked = 0; Mismatched = @() }
    try { $audit = Test-AgainstServer $runId }
    catch { Write-Host "[bench] server audit could not run: $($_.Exception.Message)" }
    if ($audit.Checked -gt 0) {
        $wrong = $audit.Mismatched.Count + $audit.Misoriented.Count
        Write-Host ("[bench] server audit: {0}/{1} cells confirmed" -f ($audit.Checked - $wrong), $audit.Checked)
        # MISORIENTED is called out separately and loudly. A cell holding the right block the wrong way round looks
        # built to a glance and is silently wrong forever -- the failure mode the owner had to catch by eye. A count of
        # "cells not confirmed" hides it among the merely-unbuilt.
        if ($audit.Misoriented.Count -gt 0) {
            Write-Host ("[bench] server audit: {0} cell(s) hold the RIGHT BLOCK FACING THE WRONG WAY: {1}" -f `
                $audit.Misoriented.Count, (Format-CellCensus $audit.Misoriented))
        }
        if ($audit.Mismatched.Count -gt 0) {
            Write-Host ("[bench] server audit: {0} cell(s) not built or wrong block: {1}" -f `
                $audit.Mismatched.Count, (Format-CellCensus $audit.Mismatched))
        }
        # A misorientation is never acceptable in a SUCCESS: the build is finished and quietly wrong.
        if ($state -eq 'SUCCESS' -and $wrong -gt 0) { $state = 'CLIENT_ONLY' }
    }

    $detail = ''
    if ($verdict -match 'placed=(\S+).*ticks=(\S+).*blocksPerMin=(\S+)') {
        $detail = "placed=$($matches[1]) ticks=$($matches[2]) bpm=$($matches[3])"
    } elseif ($verdict) {
        $detail = $verdict
    }

    # Time fidelity. A run that did not reach the rate it asked for is still a valid verdict -- the simulation is
    # tick-quantised, so slow is just slow -- but every WALL-CLOCK number from it (idle seconds, "stood still for
    # 1-2 seconds") is measured against a clock that did not run at the assumed speed. Say so on the run rather
    # than let a later reader assume.
    $achieved = Measure-TickRateAchieved $log $runId
    $want = 20.0 * $TimeScale
    if ($achieved) {
        $note = if ($achieved -lt $want * 0.85) { "  <-- SHORT of ${want}/s, wall-clock figures are not time-faithful" } else { '' }
        Write-Host ("[bench] measured {0} client ticks/real second (asked {1}){2}" -f $achieved, $want, $note)
    }

    return [pscustomobject]@{
        Scenario = $name
        Verdict  = $state
        Detail   = $detail
        Seconds  = [int]((Get-Date) - $started).TotalSeconds
        TicksPerSec = $achieved
        Log      = $archive
    }
}

if (-not (Start-BenchServer)) { exit 2 }

$results = @()

if ($EquivalenceAt -gt 1) {
    # Equivalence mode: each scenario twice, slow then fast, and the pair has to agree.
    $mismatches = 0
    foreach ($name in $Scenario) {
        $TimeScale = 1.0
        Set-TickRate
        $slow = Invoke-Scenario $name

        $TimeScale = $EquivalenceAt
        Set-TickRate
        $fast = Invoke-Scenario $name

        $slowTicks = if ($slow.Detail -match 'ticks=(\d+)') { [int]$matches[1] } else { -1 }
        $fastTicks = if ($fast.Detail -match 'ticks=(\d+)') { [int]$matches[1] } else { -2 }

        $agree = ($slow.Verdict -eq $fast.Verdict) -and ($slowTicks -eq $fastTicks)
        # Verdict and tick count must match EXACTLY -- both are pure functions of the simulation, which the clock
        # speed must not touch. Wall-clock seconds are the one thing that is allowed, and expected, to differ.
        $speedup = if ($slow.Seconds -gt 0) { [math]::Round($slow.Seconds / [math]::Max(1, $fast.Seconds), 2) } else { 0 }
        Write-Host ""
        Write-Host ("[bench] equivalence {0}: 1x -> {1} ticks={2} {3}s @ {4}/s | {5}x -> {6} ticks={7} {8}s @ {9}/s" -f `
            $name, $slow.Verdict, $slowTicks, $slow.Seconds, $slow.TicksPerSec, `
            $EquivalenceAt, $fast.Verdict, $fastTicks, $fast.Seconds, $fast.TicksPerSec)
        if ($agree) {
            Write-Host ("[bench] equivalence {0}: AGREE -- same simulation, {1}x less waiting" -f $name, $speedup)
        } elseif ($slowTicks -lt 0 -and $fastTicks -lt 0) {
            # Neither run reached a verdict, so the pair says nothing about the speedup. Blaming it here would be a
            # false accusation, and the honest report of an inconclusive test is "inconclusive".
            Write-Host ("[bench] equivalence {0}: INCONCLUSIVE -- neither speed produced a verdict, fix that first" -f $name)
            $mismatches++
        } else {
            Write-Host ("[bench] equivalence {0}: DISAGREE -- the speedup changed the outcome, do not trust it" -f $name)
            $mismatches++
        }
        $results += $slow
        $results += $fast
    }
    if ($StopServer) { Stop-Jvm 'KnotServer' }
    Write-Host ""
    Write-Host ("[bench] equivalence: {0} scenario(s) disagreed" -f $mismatches)
    exit $mismatches
}

Set-TickRate
foreach ($name in $Scenario) {
    $results += Invoke-Scenario $name
}

if ($StopServer) { Stop-Jvm 'KnotServer' }

Write-Host ""
Write-Host "[bench] ---------------- summary ----------------"
$results | ForEach-Object {
    Write-Host ("[bench] {0,-10} {1,-11} {2,4}s  {3}" -f $_.Scenario, $_.Verdict, $_.Seconds, $_.Detail)
}
$failed = @($results | Where-Object { $_.Verdict -ne 'SUCCESS' })
Write-Host ("[bench] {0}/{1} passed" -f ($results.Count - $failed.Count), $results.Count)
if ($failed.Count) {
    Write-Host "[bench] logs of failing runs:"
    $failed | ForEach-Object { Write-Host "[bench]   $($_.Scenario): $($_.Log)" }
}
exit $failed.Count
