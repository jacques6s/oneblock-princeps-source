<#
.SYNOPSIS
    Die einzige erlaubte Art, in diesem Repo etwas zu messen.

.DESCRIPTION
    Eine Stufe der Beweisleiter ausfuehren, ihre Evidenz einfrieren und ein maschinenlesbares
    summary.json schreiben. Das Gate entscheidet ueber Bestehen -- nicht der Agent, der es aufruft.

    Stufen, aufsteigend nach Kosten:
      tests    gradlew test --offline            ~1-2 min   alle gruen, sonst nichts weiter
      dryrun   headless Planer                   ~3 min     Zellzahl je Ebene + Planzeit
      ringbig  Bench, Stein, 120 Zellen          ~5 min     SUCCESS, divergences=0, replans=0
      basalt   Basalt-Fenster, festes Budget     ~10-20 min Vergleich gegen Baseline
      full     Basalt bis zum Verdikt            bis 2 h    nur fuer den COMPLETE-Anspruch

    Jede Stufe schreibt autonomy\runs\<runid>\summary.json. Fehlt fuer eine Stufe frische Evidenz,
    gilt sie als NICHT bestanden -- ein Exit-Code allein zaehlt nie, weil in diesem Projekt schon
    mehrfach Exit 0 ohne Lauf vorkam.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('tests','dryrun','replay','ringbig','suite','basalt','full')]
    [string] $Stage,
    # Tick-Budget fuer die Basalt-Stufe. Vergleiche gelten nur bei gleichem Budget.
    [int]    $TickBudget = 30000,
    # 3x ist der Standard: 20 -> 60 Client-Ticks/s sind fuer `wall` gemessen, gleiches Verdikt, gleiche
    # Tickzahl. Der Bench misst die ERREICHTE Rate selbst und meldet sie; gate.ps1 haelt sie in
    # summary.json fest. Zwei Laeufe sind nur vergleichbar, wenn ihre erreichte Rate im selben Bereich lag.
    # Ausnahme: der Screen/Swap-Deadlock ist ein Rennen und wird bei -TimeScale 1 untersucht.
    [double] $TimeScale  = 3.0,
    # Wanduhr-Deckel fuer die Dry-Run-Stufe. Der Planer plant ALLE 18 Ebenen, nicht zwei: gemessen am
    # 31.07. brauchte Ebene -58 nach acht Minuten erst 440 ihrer 1521 Zellen, hochgerechnet Stunden.
    # Als Pflichtstufe vor jedem Bench ist das unbrauchbar, und HANDOVER-builder-v3.md beschreibt ohnehin
    # die Praxis, die hier festgeschrieben wird: -60 und -59 anschauen, dann abbrechen. 300 s decken
    # Gradle-Build (~30 s) + Ebene -60 (~6 s) + Ebene -59 (~140 s) mit Reserve.
    [int]    $DryRunSeconds = 300,
    # run_id einer frueheren Bench-Messung. Ist er gesetzt, schneidet das Gate BEIDE Laeufe rechnerisch auf
    # denselben Tick zu und schreibt diesen Tick in summary.json. Grund: -TickBudget bricht keinen Lauf bei
    # Tick N ab (siehe Kommentar an der basalt-Stufe), also enden zwei Fenster bei verschiedenen Tickstaenden,
    # und "1358 gegen 1290 Zellen" vergleicht dann zwei verschieden lange Laeufe.
    [string] $CompareTo  = '',
    # Welche Builder-Engine der Bench faehrt. 'v2' ist BuilderProcess.java -- die Engine im ausgelieferten
    # Client und laut MISSION-v1.md (02.08.2026) ab jetzt die Arbeitsbasis. 'v3' ist der eingefrorene
    # princeps.process.builder.v3-Baum. Der Vorgabewert bleibt 'v3', damit jeder frueher gefahrene Aufruf
    # weiterhin dasselbe misst wie damals; die neue Basis wird ausdruecklich mit -Engine v2 angefordert.
    # Der Wert steht in summary.json, weil zwei Laeufe verschiedener Engines nichts miteinander zu tun haben.
    [ValidateSet('v2','v3')]
    [string] $Engine     = 'v3',
    # Ebenen von oben nach unten bauen. Der Schalter liegt seit jeher in bench.ps1 und das Gate hat ihn nie
    # durchgereicht -- sein Kommentar dort beschreibt exakt die Wand, die am 02.08.2026 gemessen wurde:
    # "an etz-basalt run collapses at layer 4, the row holding 448 of its down-facing pistons, which cannot be
    # placed until something stands above them". Und der Nachbar-Zensus meldet bei JEDER blockierten Zelle
    # up=air(wants soul_soil): der Kolben braucht den Block ueber sich, und die Reihenfolge liefert ihn nie.
    [switch] $TopDown,
    # Ebenen ganz weglassen bzw. eine unfertige Ebene ueberspringen duerfen. Beide Schalter existieren seit jeher in
    # BuilderBench und build.gradle, aber scripts/bench.ps1 ist geschuetzt und reicht nur sieben Flags durch -- diese
    # zwei nicht. Der Weg fuehrt deshalb ueber eine Umgebungsvariable, die build.gradle zusaetzlich abfragt.
    # Warum es die Hauptfrage ist: BuilderBench:311-313 haelt selbst fest, dass mit skipFailedLayers=false eine
    # einzige unbaubare Zelle alles ueber sich haelt, dass Laeufe stundenlang auf layer 2 von 18 standen, und dass
    # das "the shape of an ordering problem rather than a tuning one" ist -- "and one flag settles it either way".
    [switch] $NoLayers,
    [switch] $SkipLayers,
    # buildNeverDiscard abschalten. Ohne das gibt es keinen Ausgang aus einer unfertigen Ebene -- siehe
    # Settings.buildNeverDiscard. Die Vorgabe des ausgelieferten Clients bleibt unveraendert.
    [switch] $DiscardCells,
    # Bench-only: vor jeder neu freigegebenen Ebene exakt deren Materialpalette atomar in die 36 Inventarplaetze
    # laden. Der normale Client bleibt unveraendert; das Gate haelt den Modus in summary.json fest.
    [switch] $LayerMaterials,
    [string] $Note       = '',
    # Wiederherstellung: run_id eines Laufs, dessen stage.log vollstaendig ist, dem aber das summary.json
    # fehlt, weil das Gate nach dem Bench haengengeblieben ist (siehe Invoke-Stage). Dann wird NICHTS
    # ausgefuehrt -- es wird nur die Evidenz eingesammelt, die schon auf der Platte liegt. Der Lauf wird in
    # summary.json als `revived` markiert, damit niemand ihn spaeter fuer eine frische Messung haelt.
    [string] $ReviveFrom = '',
    # Welche Szenarien die suite-Stufe faehrt. Vorgabe 'all' = genau das Verhalten von vorher.
    # Grund fuer den Schalter: 'all' faehrt sieben eingebaute Szenarien und laesst sich nicht auf die
    # vier eingrenzen, nach denen wirklich gefragt wurde -- wer dann trotzdem misst, misst etwas anderes
    # als die Frage. Die Namen stehen in BenchSchematics.byName (u.a. ring7, oriented, doors, doorpairs,
    # alldoors, doorway, facings, wall, floor, ringbig). Der Wert landet in summary.json, damit ein
    # Teil-Lauf nie mit einem vollen verwechselt werden kann.
    [string[]] $Scenarios = @('all')
)

$ErrorActionPreference = 'Stop'
# `powershell -File` reicht Argumente WOERTLICH durch: `-Scenarios a,b,c` kommt als EIN String mit Kommas an,
# nicht als Array. Der erste Aufruf so ist genau daran gescheitert ("unknown scenario 'ring7,oriented,doors,facings'").
# Hier einmal aufteilen, dann ist die Aufrufform egal.
$Scenarios = @($Scenarios | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if (-not $Scenarios) { $Scenarios = @('all') }
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Den eigenen Hash JETZT nehmen, nicht beim Schreiben des Summarys. Sonst stempelt ein Lauf, waehrend
# dessen Laufzeit gate.ps1 bearbeitet wurde, die NEUE Version in ein Ergebnis, das die ALTE erzeugt hat --
# eine falsche Herkunftsangabe in genau der Datei, die Herkunft beweisen soll. Gemessen am 02.08.2026:
# Lauf 20260802-015839-dryrun lief 25 min, waehrend der Zellverlust-Waechter eingebaut wurde.
$gateSha = (Get-FileHash $PSCommandPath -Algorithm SHA256).Hash
if ($ReviveFrom) {
    $runId  = $ReviveFrom
    $outDir = Join-Path $root "autonomy\runs\$runId"
    if (-not (Test-Path (Join-Path $outDir 'stage.log'))) {
        throw "ReviveFrom: kein stage.log in $outDir -- es gibt nichts einzusammeln."
    }
    if (-not $Stage) { throw 'ReviveFrom braucht die Stufe des urspruenglichen Laufs.' }
} else {
    $runId  = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Stage
    $outDir = Join-Path $root "autonomy\runs\$runId"
}
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$lock    = Join-Path $root 'autonomy\.lock'

function Write-Summary($obj) {
    $obj | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $outDir 'summary.json')
    Write-Host ""
    Write-Host "GATE $Stage -> $($obj.result)   ($runId)"
    if ($obj.reason) { Write-Host "  $($obj.reason)" }
}

# ---- Eine Stufe ausfuehren, ohne sich an ihrer eigenen Ausgabe aufzuhaengen -------------------
#
# ZWOELFTE MESSFALLE, und sie hat eine ganze Nacht gekostet. Bis zum 02.08.2026 liefen alle Bench-Stufen
# als `& powershell -File bench.ps1 ... 2>&1 | Tee-Object stage.log`. Der Lauf 20260802-062732-full war
# um 08:20 fertig, hat seine Zusammenfassung gedruckt -- und das Gate stand danach 100 Minuten still, bis
# es von Hand erschossen wurde: kein summary.json, kein Urteil, der Lock die ganze Zeit gehalten.
#
# Die Ursache ist keine Eigenart von Tee-Object, sondern von CreateProcess. Eine Pipeline in PowerShell
# endet erst, wenn das SCHREIBENDE ENDE des Rohrs geschlossen ist -- nicht, wenn der Prozess endet, dem
# man es gegeben hat. `Start-Process -RedirectStandardOutput` setzt intern bInheritHandles=TRUE, und
# damit erbt das Kind JEDEN vererbbaren Handle des Elternprozesses, nicht nur die drei umgeleiteten.
# bench.ps1:217 startet auf diesem Weg `gradlew runServer`, und der Dev-Server bleibt nach dem Lauf
# ABSICHTLICH stehen (AUTONOMY.md, "Prozesse"). Also lebte eine Kopie des Rohrendes weiter, obwohl
# bench.ps1 laengst beendet war, und Tee-Object wartete auf ein Dateiende, das erst mit dem Server kommt.
#
# Genau deshalb faellt es nur MANCHMAL auf: stand der Dev-Server beim Start schon, nimmt bench.ps1 den
# Zweig "dev server already up" und startet gar kein gradlew -- dann endet der Lauf normal. Die drei
# frueheren full-Laeufe hatten Glueck. Dieser hier hat den Server selbst gestartet.
#
# Die Reparatur: nicht in eine Pipeline, sondern in eine DATEI umleiten und auf den direkten Kindprozess
# warten. Ein geerbter Dateihandle haelt niemanden auf -- WaitForExit fragt nach dem Prozess, nicht nach
# dem Rohr. Fortschritt wird gezeigt, indem die Datei nachgelesen wird; das ist ohnehin die Regel dieses
# Projekts ("Nicht durch tail pipen"), und die dryrun-Stufe macht es seit jeher so.
#
# Dazu eine harte Wanduhr-Schranke. Ein Bench kennt seine eigene (-TimeoutSeconds); haengt er trotzdem,
# soll das Gate ihn abraeumen und das Ergebnis MELDEN, statt bis zum Morgen zu warten.
function Invoke-Stage {
    param(
        [string]   $FilePath,
        [string[]] $Arguments,
        [string]   $LogPath,
        [int]      $HardDeadlineSeconds,
        [string]   $Follow,          # Regex, case-sensitive, fuer die Zeilen die live gezeigt werden
        [string]   $WorkDir
    )
    $errPath = [System.IO.Path]::ChangeExtension($LogPath, '.err')
    $proc = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkDir `
                -PassThru -WindowStyle Hidden `
                -RedirectStandardOutput $LogPath -RedirectStandardError $errPath
    # Den Handle EINMAL anfassen, sonst ist $proc.ExitCode spaeter leer -- und ein leerer Exit-Code ist
    # in PowerShell nicht 0, sondern $null, womit jede Pruefung "$code -ne 0" wahr wird. Gemessen im
    # ersten Lauf mit dieser Funktion (20260802-101341-tests): BUILD SUCCESSFUL, 528 Tests, 0 rot --
    # und das Gate meldete FAIL. .NET fuellt ExitCode nur, wenn der Handle des Prozesses gecacht wurde,
    # bevor er endet; Start-Process -PassThru allein tut das nicht.
    # DERSELBE Fehler steckt seit jeher in der dryrun-Stufe -- dort nur folgenlos, weil ihr Urteil den
    # Exit-Code nicht benutzt. Er ist unten mitrepariert.
    $null = $proc.Handle
    $deadline = (Get-Date).AddSeconds($HardDeadlineSeconds)
    $shown = 0
    $tick  = 0
    while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $tick++
        $seen = @(Select-String -Path $LogPath -Pattern $Follow -CaseSensitive -ErrorAction SilentlyContinue)
        for ($i = $shown; $i -lt $seen.Count; $i++) { Write-Host "  $($seen[$i].Line.Trim())" }
        $shown = $seen.Count
        if ($tick % 36 -eq 0) {   # alle drei Minuten ein Lebenszeichen mit der letzten Fortschrittszeile
            $s = @(Select-String -Path $LogPath -Pattern 'sample t=\d+\s+remaining=' -ErrorAction SilentlyContinue)
            $lastSample = if ($s.Count) { ($s[$s.Count-1].Line -replace '^.*\[BENCH\]\s*', '').Trim() } else { '(noch keine Sample-Zeile)' }
            Write-Host "  [gate] $([int]((Get-Date) - $proc.StartTime).TotalMinutes) min  $lastSample"
        }
    }
    $killed = $false
    if (-not $proc.HasExited) {
        $killed = $true
        Write-Host "[gate] HARTE SCHRANKE von $HardDeadlineSeconds s erreicht -- Prozessbaum $($proc.Id) wird abgeraeumt."
        & taskkill.exe /PID $proc.Id /T /F 2>&1 | Out-Null
        $proc.WaitForExit(60000) | Out-Null
    }
    # Die Reste anhaengen, die zwischen der letzten Runde und dem Ende geschrieben wurden.
    $seen = @(Select-String -Path $LogPath -Pattern $Follow -CaseSensitive -ErrorAction SilentlyContinue)
    for ($i = $shown; $i -lt $seen.Count; $i++) { Write-Host "  $($seen[$i].Line.Trim())" }
    $code = if ($proc.HasExited) { $proc.ExitCode } else { 1 }
    return @{ code = $code; killed = $killed }
}

# ---- Exklusiv-Lock -------------------------------------------------------------------------
# Zwei Gradle- oder Bench-Prozesse gleichzeitig tauschen einer laufenden JVM die Klassen weg.
# Gemessen: eine 6-Sekunden-Ebene war nach 20 Minuten nicht fertig, ohne Absturz.
if ($ReviveFrom) {
    # Eine Wiederherstellung startet keine JVM, tauscht keiner laufenden Klassen weg und darf deshalb
    # auch keinen Lock nehmen -- sonst kann man einen abgestuerzten Lauf nicht nachtragen, waehrend der
    # naechste schon misst.
    Write-Host "WIEDERHERSTELLUNG aus $outDir -- es wird nichts ausgefuehrt, nur eingesammelt."
} elseif (Test-Path $lock) {
    $age = (Get-Date) - (Get-Item $lock).LastWriteTime
    # ZUERST DIE PID, DANN DIE UHR. Ein abgebrochener Lauf laesst seinen Lock stehen, und die
    # Vier-Stunden-Frist darunter macht daraus vier Stunden, in denen keine Messung mehr laeuft --
    # 07.08. genau so passiert, der Halter war seit Minuten tot und der naechste Lauf wurde abgewiesen.
    # Ob der Prozess noch lebt, ist die genauere Frage und sie ist auch die billigere.
    $holder = $null
    if ((Get-Content $lock -Raw) -match 'pid=(\d+)') { $holder = [int]$Matches[1] }
    if ($holder -and -not (Get-Process -Id $holder -ErrorAction SilentlyContinue)) {
        Write-Host "Lock-Halter pid=$holder lebt nicht mehr, Lock wird entfernt."
        Remove-Item $lock -Force
    } elseif ($age.TotalMinutes -lt 240) {
        Write-Summary @{ run_id=$runId; stage=$Stage; result='REFUSED'
                         reason="Ein anderer Gate-Lauf haelt den Lock seit $([int]$age.TotalMinutes) min ($(Get-Content $lock -Raw))." }
        exit 3
    } else {
        Write-Host "Lock ist $([int]$age.TotalHours) h alt, wird als verwaist entfernt."
        Remove-Item $lock -Force
    }
}
if (-not $ReviveFrom) { Set-Content $lock "$runId  pid=$PID  $(Get-Date -Format o)" }

try {
    # ---- Benchmark-Integritaet ---------------------------------------------------------------
    $protected = Get-Content (Join-Path $root 'autonomy\protected.txt') |
                 Where-Object { $_ -and -not $_.StartsWith('#') }
    $hashes = @{}
    $missing = @()
    foreach ($rel in $protected) {
        $p = Join-Path $root ($rel -replace '/', '\')
        if (Test-Path $p) { $hashes[$rel] = (Get-FileHash $p -Algorithm SHA256).Hash }
        else { $missing += $rel.ToString() }
    }
    # In der Wiederherstellung NICHT ueberschreiben: manifest.json, working.diff und status.txt sind die
    # eingefrorene Herkunft des DAMALIGEN Laufs. Wer sie mit den Hashes von heute ueberschreibt, faelscht
    # genau die Angabe, wegen der sie existieren.
    if (-not $ReviveFrom) { $hashes | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outDir 'manifest.json') }

    $manifestPath = Join-Path $root 'autonomy\protected.sha256.json'
    $tampered = @()
    if (Test-Path $manifestPath) {
        $ref = Get-Content $manifestPath -Raw | ConvertFrom-Json
        foreach ($rel in $hashes.Keys) {
            if ($ref.$rel -and $ref.$rel -ne $hashes[$rel]) { $tampered += $rel }
        }
    } else {
        $hashes | ConvertTo-Json -Depth 5 | Set-Content $manifestPath
        Write-Host "Referenz-Hashes angelegt: autonomy\protected.sha256.json"
    }
    if ($tampered.Count -gt 0) {
        Write-Summary @{ run_id=$runId; stage=$Stage; result='TAMPERED'
                         reason="Geschuetzte Datei geaendert: $($tampered -join ', ')"
                         tampered=$tampered }
        exit 4
    }

    # ---- Arbeitszustand einfrieren -----------------------------------------------------------
    $commit = (git rev-parse HEAD).Trim()
    $branch = (git rev-parse --abbrev-ref HEAD).Trim()
    if (-not $ReviveFrom) {
        git diff            | Set-Content (Join-Path $outDir 'working.diff')
        git status --short  | Set-Content (Join-Path $outDir 'status.txt')
    }

    # ---- latest.log retten, bevor ein Clientstart es rotiert ---------------------------------
    $latest = Join-Path $root 'run\logs\latest.log'
    if ((-not $ReviveFrom) -and (Test-Path $latest) -and $Stage -in @('ringbig','suite','basalt','full')) {
        Copy-Item $latest (Join-Path $outDir 'previous-latest.log') -ErrorAction SilentlyContinue
    }
    $benchOut  = Join-Path $root 'run\bench-out'
    $before    = @()
    if (Test-Path $benchOut) { $before = Get-ChildItem $benchOut -Filter *.log | ForEach-Object { $_.Name } }

    # ---- Stufe ausfuehren ---------------------------------------------------------------------
    # $ErrorActionPreference bleibt fuer die Dauer des Laufs 'Continue', und das ist keine Bequemlichkeit.
    # `& gradlew.bat ... 2>&1` macht aus jeder stderr-Zeile ein PowerShell-Fehlerobjekt; unter 'Stop' beendet
    # schon die erste davon das Gate. Gemessen im ersten Lauf ueberhaupt (20260731-170307-tests): Gradle schrieb
    # "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called", das Gate brach mitten in
    # `test` ab, schrieb kein summary.json und meldete nichts -- also genau der Zustand, den diese Datei
    # verhindern soll: ein Lauf ohne Evidenz. Die Warnung ist harmlos, das Urteil faellt weiterhin $LASTEXITCODE.
    $ErrorActionPreference = 'Continue'
    $started  = Get-Date
    if ($NoLayers)   { $env:PRINCEPS_NO_LAYERS = '1' }   else { Remove-Item Env:\PRINCEPS_NO_LAYERS -ErrorAction SilentlyContinue }
    if ($SkipLayers) { $env:PRINCEPS_SKIP_LAYERS = '1' } else { Remove-Item Env:\PRINCEPS_SKIP_LAYERS -ErrorAction SilentlyContinue }
    if ($DiscardCells) { $env:PRINCEPS_DISCARD = '1' } else { Remove-Item Env:\PRINCEPS_DISCARD -ErrorAction SilentlyContinue }
    if ($LayerMaterials) { $env:PRINCEPS_LAYER_MATERIALS = '1' } else { Remove-Item Env:\PRINCEPS_LAYER_MATERIALS -ErrorAction SilentlyContinue }
    $stageLogPath = Join-Path $outDir 'stage.log'
    # In Anfuehrungszeichen, weil Start-Process die ArgumentList in PowerShell 5.1 ungeschuetzt mit
    # Leerzeichen zusammensetzt -- ein Repo-Pfad mit Leerzeichen wuerde sonst zu zwei Argumenten.
    $benchPs1 = '"' + (Join-Path $root 'scripts\bench.ps1') + '"'
    # Und die Zahl invariant formatieren: unter de-DE macht [double]2.5 sonst "2,5", und bench.ps1
    # bekaeme einen Wert, den es nicht parsen kann. Frueher ging das durch, weil `&` den Typ direkt
    # weiterreichte; ueber eine Kommandozeile geht nur noch Text.
    $timeScaleArg = $TimeScale.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $killed   = $false
    # Was live gezeigt wird. -CaseSensitive ist wesentlich: `[bench]` sind die Zeilen des WRAPPERS,
    # `[BENCH]` die des Clients -- darunter die Sample-Zeile, die jede Sekunde kommt. Ohne Gross-/
    # Kleinschreibung waeren es in einem Vollauf viertausend Zeilen Fortschrittsanzeige.
    $benchFollow = '^\[bench\]|verdict=|checkpoint t=|FAILURE:|Exception in thread'
    if ($ReviveFrom) {
        Write-Host "  (Stufe wird nicht ausgefuehrt -- Evidenz stammt aus dem vorhandenen stage.log.)"
        $code = 0
    } else {
    switch ($Stage) {
        'tests' {
            $r = Invoke-Stage -FilePath (Join-Path $root 'gradlew.bat') `
                    -Arguments @('test','--offline','--console=plain') -LogPath $stageLogPath `
                    -HardDeadlineSeconds 1800 -WorkDir $root `
                    -Follow 'FAILED|BUILD SUCCESSFUL|BUILD FAILED|^> Task :(test|compileJava)'
            $code = $r.code; $killed = $r.killed
        }
        'dryrun' {
            # Nicht ueber die Pipeline, sondern als eigener Prozess mit Umleitung: nur so laesst sich der Lauf
            # nach $DryRunSeconds beenden, ohne dass das Gate mitstirbt. Beendet wird die PLANER-JVM, nicht der
            # Wrapper -- dann laeuft run-dryrun.ps1 von selbst aus und das Gate sammelt seine Evidenz normal ein.
            $dryLog = Join-Path $outDir 'stage.log'
            $proc = Start-Process powershell -PassThru -WindowStyle Hidden -WorkingDirectory $root `
                -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',
                                (Join-Path $root 'tools\dryrun\run-dryrun.ps1')) `
                -RedirectStandardOutput $dryLog -RedirectStandardError (Join-Path $outDir 'stage.err')
            $null = $proc.Handle   # siehe Invoke-Stage: ohne das ist $proc.ExitCode spaeter leer
            $deadline = (Get-Date).AddSeconds($DryRunSeconds)
            $shown = 0
            while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
                Start-Sleep -Seconds 3
                # Fortschritt sichtbar halten. Nicht durch `tail` pipen -- das puffert bis zum Pipe-Ende und
                # genau die Zeilen, wegen derer der Planer sie schreibt, kommen dann nie an.
                $seen = @(Select-String -Path $dryLog -Pattern 'cells in|planned in|NO SCHEMATIC|PARSE FAILED' -ErrorAction SilentlyContinue)
                for ($i = $shown; $i -lt $seen.Count; $i++) { Write-Host "  $($seen[$i].Line.Trim())" }
                $shown = $seen.Count
            }
            $truncated = -not $proc.HasExited
            if ($truncated) {
                Write-Host "Dry-Run-Deckel von $DryRunSeconds s erreicht -- Planer-JVM wird beendet."
                Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
                    Where-Object { $_.CommandLine -like '*BasaltDryRun*' } |
                    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
                $proc.WaitForExit(60000) | Out-Null
                if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
            }
            $code = if ($proc.HasExited) { $proc.ExitCode } else { 0 }
        }
        'replay' {
            # Der Wiederholungs-Pruefstand: eine gescheiterte Zelle aus einem vorhandenen Trace nachstellen, ohne
            # Client und ohne Server. ARBEITSWEISE.md nennt ihn den groessten Hebel im Projekt, und die Zahl gibt ihr
            # recht: rund 12 ms je Zelle gegen einen Vier-Minuten-Bench-Lauf (gemessen 11-14 ueber drei Laeufe).
            #
            # --rerun-tasks, weil die Stufe von einer Datei ausserhalb des Gradle-Eingabegraphen abhaengt: die Traces
            # unter run/logs/. Ohne den Schalter meldet Gradle UP-TO-DATE, sobald der Quellbaum unveraendert ist, und
            # die Stufe wuerde die Antwort des VORIGEN Traces als die des neuen ausgeben -- Messfalle 4 in neuer
            # Verkleidung. Kostet hier nichts, weil nur diese eine Testklasse laeuft.
            $r = Invoke-Stage -FilePath (Join-Path $root 'gradlew.bat') `
                    -Arguments @('test','--offline','--console=plain','--rerun-tasks',
                                 '--tests','princeps.process.builder.replay.TraceReplayTest') `
                    -LogPath $stageLogPath -HardDeadlineSeconds 900 -WorkDir $root `
                    -Follow 'FAILED|BUILD SUCCESSFUL|BUILD FAILED|\[replay'
            $code = $r.code; $killed = $r.killed
        }
        'ringbig' {
            $r = Invoke-Stage -FilePath 'powershell' -LogPath $stageLogPath -WorkDir $root `
                    -Arguments @('-NoProfile','-ExecutionPolicy','Bypass','-File',$benchPs1,
                                 'ringbig','-Engine',$Engine,'-TimeoutSeconds','420') `
                    -HardDeadlineSeconds 1200 -Follow $benchFollow
            $code = $r.code; $killed = $r.killed
        }
        'suite' {
            # Alle eingebauten Szenarien: wall, floor, ring7, ringbig, ringresume, oriented, doorway,
            # facings. Schutz gegen Overfitting auf genau eine Schematic. Exit-Code = Anzahl der
            # Szenarien, die nicht SUCCESS wurden. TimeScale wird auf beiden Seiten synchron angewendet; ihn nur im
            # summary.json zu nennen, ohne ihn an bench.ps1 zu reichen, waere eine falsche Messungsherkunft.
            $r = Invoke-Stage -FilePath 'powershell' -LogPath $stageLogPath -WorkDir $root `
                    -Arguments (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$benchPs1) `
                                 + $Scenarios + @('-Engine',$Engine,'-TimeScale',$timeScaleArg,
                                                  '-TimeoutSeconds','600')) `
                    -HardDeadlineSeconds 6000 -Follow $benchFollow
            $code = $r.code; $killed = $r.killed
        }
        'basalt' {
            # -NoStall: der Stall-Test ist nicht monoton; derselbe Builder lieferte 1641 / 2629 / 5069
            # gesetzte Zellen in drei Laeufen. Ein Vergleich braucht ein festes Tick-Budget.
            #
            # ABER: -TickBudget ist hier eine Umrechnung in Wanduhr, kein Tick-Budget. Nichts bricht den Lauf
            # bei Tick N ab -- weder BuilderBench noch bench.ps1 kennen so eine Grenze, und beide sind
            # geschuetzt (autonomy\protected.txt), also wird hier auch keine eingebaut. Mit -NoStall endet ein
            # Basalt-Fenster ausschliesslich an -TimeoutSeconds. Der Knopf, der beide Laeufe vergleichbar
            # macht, ist deshalb $seconds; die erreichte Tickzahl ist das ERGEBNIS, nicht die Vorgabe.
            # Zwei Fenster sind vergleichbar, wenn $seconds gleich war UND ticks_per_sec_achieved im selben
            # Bereich lag -- beides steht in summary.json.
            $seconds = [int]([math]::Ceiling($TickBudget / (20.0 * $TimeScale))) + 180
            $r = Invoke-Stage -FilePath 'powershell' -LogPath $stageLogPath -WorkDir $root `
                    -Arguments @('-NoProfile','-ExecutionPolicy','Bypass','-File',$benchPs1,
                                 'file:etz-basalt.litematic','-Engine',$Engine,'-BuildTrace','-NoStall',
                                 '-TimeScale',$timeScaleArg,'-TimeoutSeconds',$seconds) `
                    -HardDeadlineSeconds ($seconds + 900) -Follow $benchFollow
            $code = $r.code; $killed = $r.killed
        }
        'full' {
            $r = Invoke-Stage -FilePath 'powershell' -LogPath $stageLogPath -WorkDir $root `
                    -Arguments (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$benchPs1,
                                 'file:etz-basalt.litematic','-Engine',$Engine,'-BuildTrace',
                                 '-TimeScale',$timeScaleArg,'-TimeoutSeconds','7200') +
                                 $(if ($TopDown) { @('-TopDown') } else { @() })) `
                    -HardDeadlineSeconds 8400 -Follow $benchFollow
            $code = $r.code; $killed = $r.killed
        }
    }
    }
    $elapsed = [int]((Get-Date) - $started).TotalSeconds
    $ErrorActionPreference = 'Stop'

    # ---- Evidenz einsammeln -------------------------------------------------------------------
    $stageLog = Get-Content (Join-Path $outDir 'stage.log') -Raw -ErrorAction SilentlyContinue
    if (-not $stageLog) { $stageLog = '' }

    if ($ReviveFrom) {
        # Wanduhr und Startzeit stammen aus dem NACHGETRAGENEN Lauf, nicht aus dem Moment des Nachtragens.
        # Der Bench druckt seine eigene Laufzeit in die Zusammenfassungszeile ("... NO_VERDICT  7216s").
        $started = (Get-Item $stageLogPath).CreationTime
        $em = [regex]::Match($stageLog, '\[bench\]\s+\S+\s+[A-Z_]+\s+(\d+)s')
        if ($em.Success) { $elapsed = [int]$em.Groups[1].Value }
        else { $elapsed = [int]((Get-Item $stageLogPath).LastWriteTime - $started).TotalSeconds }
    }

    $summary = @{
        run_id = $runId; stage = $Stage; commit = $commit; branch = $branch
        scenarios = ($Scenarios -join ',')
        started = $started.ToString('o'); elapsed_seconds = $elapsed
        exit_code = $code; tick_budget = $TickBudget; time_scale = $TimeScale
        engine = $Engine
        note = $Note; protected_ok = $true; missing_protected = $missing
        top_down = [bool]$TopDown; no_layers = [bool]$NoLayers; skip_layers = [bool]$SkipLayers; discard_cells = [bool]$DiscardCells
        layer_materials = [bool]$LayerMaterials
        gate_sha256 = $gateSha
        result = 'UNKNOWN'; reason = ''
    }
    if ($killed) {
        $summary.gate_hard_deadline_hit = $true
    }
    if ($ReviveFrom) {
        # Alles, was an dieser Auswertung NICHT aus dem Lauf stammt, steht hier -- damit niemand ein
        # nachgetragenes summary.json spaeter fuer eine frische Messung haelt.
        $summary.revived = $true
        $summary.revived_at = (Get-Date).ToString('o')
        $summary.revived_note = 'Nachgetragen aus dem vorhandenen stage.log. gate_sha256, commit, branch, ' +
            'engine, time_scale und tick_budget beschreiben den Stand beim NACHTRAGEN, nicht beim Lauf -- ' +
            'die Herkunft des Laufs selbst steht unveraendert in manifest.json, working.diff und status.txt ' +
            'desselben Ordners. exit_code ist unbekannt und steht deshalb auf null -- NICHT auf 0, weil 0 ' +
            'in diesem Projekt Erfolg bedeutet und ein Nachtrag darueber nichts weiss.'
        $summary.exit_code = $null
    }

    if ($Stage -eq 'tests') {
        # Nicht aus dem Konsolenlog zaehlen, sondern aus build\test-results\test\TEST-*.xml.
        # Grund, gemessen in 20260731-181526-tests: die Stufe meldete PASS nach 2 Sekunden mit
        # tests_failed=null. Gradle hatte `test` als UP-TO-DATE uebersprungen -- es lief kein einziger
        # Test, und die alte Regex '(\d+) tests? ... (\d+) fail' fand im gruenen Fall ohnehin nie etwas,
        # weil --console=plain bei Erfolg gar keine Zaehlzeile schreibt. Ein PASS ohne eine einzige
        # ausgefuehrte Assertion ist Messfalle 4 (Exit 0 ohne Lauf) in genau der Stufe, auf der P0.1
        # ("530 Tests gruen") ruht. Die XML-Dateien tragen die Zahlen unabhaengig vom Konsolenformat.
        $resDir = Join-Path $root 'build\test-results\test'
        $xml = @()
        if (Test-Path $resDir) { $xml = @(Get-ChildItem $resDir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) }
        $total = 0; $failed = 0; $skipped = 0
        $failing = @()
        foreach ($f in $xml) {
            try { $doc = [xml](Get-Content $f.FullName -Raw -Encoding UTF8) } catch { continue }
            $ts = $doc.testsuite
            if (-not $ts) { continue }
            $total   += [int]$ts.tests
            $failed  += [int]$ts.failures + [int]$ts.errors
            $skipped += [int]$ts.skipped
            foreach ($tc in @($ts.testcase)) {
                if ($tc.failure -or $tc.error) { $failing += "$($tc.classname).$($tc.name)" }
            }
        }
        # Frische: sind die XML-Dateien aelter als der Start dieses Laufs, hat Gradle nichts ausgefuehrt
        # und die Zahlen beschreiben einen frueheren Zustand des Codes.
        $newestXml = if ($xml.Count) { ($xml | Sort-Object LastWriteTime | Select-Object -Last 1).LastWriteTime } else { $null }
        $summary.tests_total    = $total
        $summary.tests_failed   = $failed
        $summary.tests_skipped  = $skipped
        $summary.tests_suites   = $xml.Count
        $summary.tests_failing  = $failing
        $summary.tests_fresh    = if ($newestXml) { $newestXml -ge $started.AddSeconds(-5) } else { $false }

        if ($total -eq 0) {
            $summary.result = 'INVALID'
            $summary.reason = "Kein Testergebnis in $resDir. Exit-Code $code sagt hier nichts -- die Stufe hat nicht gemessen."
        } elseif ($code -ne 0 -or $failed -gt 0) {
            $summary.result = 'FAIL'
            $summary.reason = "gradlew test ist nicht gruen: $failed von $total Tests rot" +
                              $(if ($failing.Count) { " ($($failing -join ', '))" } else { '' }) + '.'
        } else {
            $summary.result = 'PASS'
            if (-not $summary.tests_fresh) {
                $summary.reason = "$total Tests gruen, aber Gradle hat die test-Task als UP-TO-DATE uebersprungen: " +
                                  "die Zahlen stammen vom Lauf um $($newestXml.ToString('HH:mm:ss')), nicht von jetzt. " +
                                  'Gueltig, solange seither keine Quelldatei angefasst wurde.'
            }
        }
    }
    elseif ($Stage -eq 'replay') {
        # Die Stufe misst zwei Dinge, und das ZWEITE entscheidet ueber das erste.
        #
        # Der Pruefstand baut die Welt eines Laufs aus dessen Trace NACH: Stein unter dem Boden, Luft darueber, dazu
        # jede Zelle, die der Trace als gelandet meldet. Sagt er "kein Standplatz", kann das ebenso gut heissen, dass
        # die Rekonstruktion eine Stuetze verloren hat, die die echte Welt hatte. Deshalb prueft dieselbe Klasse eine
        # POSITIVKONTROLLE: Zellen, die der Builder tatsaechlich gesetzt hat, muessen als loesbar zurueckkommen.
        # Faellt die Kontrolle, ist die Stufe INVALID -- nicht FAIL, denn dann hat sie ueber den Builder nichts
        # gesagt, sondern nur ueber sich selbst.
        $replayFile = Join-Path $root 'run\replay\last-replay.txt'
        $calibFile  = Join-Path $root 'run\replay\last-calibration.txt'
        $replayTxt = if (Test-Path $replayFile) { Get-Content $replayFile -Raw } else { '' }
        $calibTxt  = if (Test-Path $calibFile)  { Get-Content $calibFile  -Raw } else { '' }

        # Frische: die Berichte liegen ausserhalb des Gradle-Eingabegraphen. Sind sie aelter als der Laufbeginn, hat
        # die Stufe die Antwort des VORIGEN Laufs eingesammelt.
        # BEIDE Dateien, nicht nur die erste: wird die Kalibrierung kuenftig uebersprungen, waehrend der Replay-Test
        # laeuft, laese das Gate sonst eine ALTE Positivkontrolle und meldete MEASURED mit der Kontrolle eines anderen
        # Laufs -- dieselbe Messfalle 4, die der Kommentar zur --rerun-tasks-Zeile fuer die andere Datei schliesst.
        # Vom Kritiker im Abschluss-Review der Iteration 14 gefunden.
        $replayFresh = (Test-Path $replayFile) -and ((Get-Item $replayFile).LastWriteTime -ge $started.AddSeconds(-5))
        $calibFresh  = (Test-Path $calibFile)  -and ((Get-Item $calibFile).LastWriteTime  -ge $started.AddSeconds(-5))
        $summary.replay_fresh             = $replayFresh
        $summary.replay_calibration_fresh = $calibFresh

        $h = [regex]::Match($replayTxt, '\[replay\]\s+trace=(\S+)\s+deferred cells=(\d+)\s+setup=(\d+) ms')
        if ($h.Success) {
            $summary.replay_trace          = $h.Groups[1].Value
            $summary.replay_deferred_cells = [int]$h.Groups[2].Value
            $summary.replay_setup_ms       = [int]$h.Groups[3].Value
        }
        $t = [regex]::Match($replayTxt, '\[replay\]\s+(\d+) of (\d+) solvable; per cell max (\d+) ms, mean (\d+) ms')
        if ($t.Success) {
            $summary.replay_solvable    = [int]$t.Groups[1].Value
            $summary.replay_examined    = [int]$t.Groups[2].Value
            $summary.replay_ms_max      = [int]$t.Groups[3].Value
            $summary.replay_ms_mean     = [int]$t.Groups[4].Value
        }
        # Die Geruestsonde. `placeable` ist zugleich ihre eigene Kontrolle: sondiert sie mit einem Block, den das
        # Inventar nicht haelt, lehnt PlacementOracle jeden Kandidaten bei NO_ITEM ab, BEVOR eine Geometriefrage
        # gestellt wird -- und "jeder Kandidat schwebt" liest sich dann wie ein Befund. Genau so ist es am
        # 02.08.2026 passiert, mit Blocks.STONE gegen ein Inventar ohne Stein. Null setzbare Kandidaten bei
        # vorhandenen Kandidaten ist deshalb ein Werkzeugfehler, kein Ergebnis.
        $sc = [regex]::Match($replayTxt, 'scaffold candidates (\d+), of them placeable (\d+)')
        if ($sc.Success) {
            $summary.replay_scaffold_candidates = [int]$sc.Groups[1].Value
            $summary.replay_scaffold_placeable  = [int]$sc.Groups[2].Value
        }
        $c = [regex]::Match($calibTxt, '(\d+) of (\d+) cells the builder ACTUALLY PLACED come back solvable')
        if ($c.Success) {
            $summary.replay_calibration_ok    = [int]$c.Groups[1].Value
            $summary.replay_calibration_total = [int]$c.Groups[2].Value
        }
        # Die NEGATIVKONTROLLE. Die Positivkontrolle allein belegt nur, dass die Basisannahme (Stein unter dem
        # Bauboden) stimmt -- eine Kontrollzelle auf der Bodenebene kommt loesbar zurueck, ob das Overlay der
        # gelandeten Zellen etwas beitraegt oder nicht. Getragen wird jedes Verdikt aber von genau diesem Overlay,
        # eine Ebene hoeher. Also muss gezeigt werden, dass es Arbeit leistet: Kontrollzellen OBERHALB des Bodens,
        # die ohne Overlay unloesbar werden. Kippt keine, sagt die Stufe nichts ueber die Rekonstruktion.
        $n = [regex]::Match($calibTxt, 'negative control: (\d+) of (\d+) control cells above the floor flip')
        if ($n.Success) {
            $summary.replay_overlay_load_bearing = [int]$n.Groups[1].Value
            $summary.replay_control_above_floor  = [int]$n.Groups[2].Value
        }
        # Die Verteilung der Ablehnungsgruende, so wie das Orakel sie selbst formuliert -- das ist die eigentliche
        # Antwort der Stufe und der Grund, warum sie existiert.
        $reasons = @{}
        foreach ($rm in [regex]::Matches($replayTxt, 'oracle: of \d+ candidate stances: (.+?), \d+ would work')) {
            $key = $rm.Groups[1].Value -replace '\d+', '<n>'
            if ($reasons.ContainsKey($key)) { $reasons[$key]++ } else { $reasons[$key] = 1 }
        }
        $summary.replay_rejections = $reasons

        if ($code -ne 0) {
            $summary.result = 'FAIL'
            $summary.reason = "Die Testklasse ist nicht gruen (Exit $code) -- der Pruefstand selbst ist kaputt."
        } elseif (-not $replayFresh -or -not $calibFresh -or -not $t.Success) {
            $summary.result = 'INVALID'
            $summary.reason = 'Kein frischer Bericht unter run\replay\ (replay_fresh=' + $replayFresh +
                              ', replay_calibration_fresh=' + $calibFresh +
                              '). Die Stufe hat nicht gemessen; Exit-Code ignorieren.'
        } elseif (-not $c.Success -or $summary.replay_calibration_ok -lt $summary.replay_calibration_total) {
            $summary.result = 'INVALID'
            $summary.reason = "Positivkontrolle nicht vollstaendig: $($summary.replay_calibration_ok) von " +
                              "$($summary.replay_calibration_total) tatsaechlich gebauten Zellen kommen als loesbar " +
                              'zurueck. Solange die Rekonstruktion die gebauten Zellen nicht erklaert, sagt jedes ' +
                              '"kein Standplatz" nichts ueber den Builder.'
        } elseif ($sc.Success -and $summary.replay_examined -gt $summary.replay_solvable -and
                  $summary.replay_scaffold_candidates -lt 1) {
            # NULL Kandidaten ist die Schwesterfehlerform von "alle abgelehnt", und sie ist am 02.08.2026 bereits
            # eingetreten: ein Ausschluss ueber `view.desired(side) == null` warf jede Stelle INNERHALB des Volumens
            # weg, an der die Schematic nichts will -- genau die Geruestplaetze -- und alle zwanzig Zellen kippten
            # in einem Zug auf NOWHERE. Eine stille Null liest sich wie "nirgends moeglich".
            $summary.result = 'INVALID'
            $summary.reason = 'Die Geruestsonde hat fuer keine der ungeloesten Zellen auch nur EINEN Kandidaten ' +
                              'erzeugt. Vor jeder Deutung ist der Kandidatenfilter zu pruefen.'
        } elseif ($sc.Success -and $summary.replay_scaffold_candidates -gt 0 -and
                  $summary.replay_scaffold_placeable -lt 1) {
            $summary.result = 'INVALID'
            $summary.reason = "Die Geruestsonde hat alle $($summary.replay_scaffold_candidates) Kandidaten " +
                              'abgelehnt. Das ist ein Werkzeugfehler, kein Befund -- zu pruefen ist zuerst, ob der ' +
                              'Sondierblock einer ist, den das Inventar ueberhaupt haelt.'
        } elseif (-not $n.Success -or $summary.replay_overlay_load_bearing -lt 1) {
            $summary.result = 'INVALID'
            $summary.reason = 'Negativkontrolle nicht bestanden: keine einzige Kontrollzelle oberhalb des Baubodens ' +
                              'wird ohne das Overlay der gelandeten Zellen unloesbar. Dann belegt die ' +
                              'Positivkontrolle nur die Basisannahme, nicht die Rekonstruktion -- und die traegt ' +
                              'jedes Verdikt.'
        } else {
            $summary.result = 'MEASURED'
            $summary.reason = "$($summary.replay_solvable) von $($summary.replay_examined) blockierten Zellen sind " +
                              "loesbar. Positivkontrolle $($summary.replay_calibration_ok)/" +
                              "$($summary.replay_calibration_total), Negativkontrolle " +
                              "$($summary.replay_overlay_load_bearing)/$($summary.replay_control_above_floor) " +
                              "oberhalb des Bodens. $($summary.replay_ms_mean) ms je Zelle " +
                              "(max $($summary.replay_ms_max)), Aufbau $($summary.replay_setup_ms) ms."
        }
    }
    elseif ($Stage -eq 'dryrun') {
        # Zellzahl und Zeit je Ebene sagen, WIE WEIT und WIE SCHNELL geplant wurde -- NICHT, wie gut.
        # (Der frueher hier stehende Satz "Regressionsschutz gegen Zellverlust" war falsch; der Waechter
        #  dafuer steht weiter unten und liest proven/blocked.)
        # Die Quelle ist OrderPlanner.reportLayer:
        #   "v3 plan: layer -60 - 936 cells in 7.0s (7.5 ms/cell), total 7.0s; access ..."
        # Genau diese Zeile, und keine andere. Drei Zeilen im selben Log sehen ihr aehnlich genug, dass eine
        # lockere Regex sie mitzaehlt und die Ebene doppelt und mit falschen Zahlen in die Baseline schreibt:
        #   OrderPlanner.reportSlice  "layer -60 - 300/936 cells, last 100 in 3.2s ..."   (Fortschritt, alle 10 s)
        #   PlanReport                "layer -60:  936 cells,  0 scaffold blocks"        (kein Zeitwert)
        #   BasaltDryRun-Zensus       "  layer   -60  minecraft:stone[...]  12"          (kein "cells")
        # Deshalb: Praefix "v3 plan:" verlangen und "cells in" statt "cells". Der Trenner zwischen Ebenennummer
        # und Zellzahl ist ein Em-Dash; er wird als \D{1,10} gelesen, damit die Kodierung des Logs egal ist.
        $layers = [regex]::Matches($stageLog, 'v3 plan:\s*layer\s*(-?\d+)\D{1,10}?(\d+)\s*cells\s+in\s+([\d.,]+)\s*s') |
                  ForEach-Object { @{ layer=[int]$_.Groups[1].Value; cells=[int]$_.Groups[2].Value
                                      seconds=[double]($_.Groups[3].Value -replace ',', '.') } }
        $summary.layers            = $layers
        $summary.dryrun_truncated  = [bool]$truncated
        $summary.dryrun_cap_seconds = $DryRunSeconds

        # ---- Zellverlust ---------------------------------------------------------------------
        # ACHTUNG: ueber $layers[].cells ist Zellverlust NICHT messbar. reportLayer bekommt todo.size(),
        # also die Zahl der bei EBENENEINTRITT unerfuellten Zellen; sie steht fest, BEVOR planLayer die
        # erste Entscheidung trifft, und meldet dasselbe, ob die Ebene alles bewiesen oder alles blockiert
        # haette (zwoelfte Messfalle, failure-signatures.json). Der Kommentar bei :276 hat das jahrelang
        # falsch behauptet und ist deshalb dort korrigiert.
        # Entscheidungsabhaengig sind erst diese Zahlen, aus DEMSELBEN Log, an zwei unabhaengigen Druckstellen:
        #   PlanReport  "PLAN INCOMPLETE   4243 / 15004 cells proven   35 cells blocked"
        #   Q-Block     "cells 15004   proven 4243   provisional 0"  +  "blockers: 35"
        # Beide werden gelesen und gegeneinander geprueft: stimmen sie nicht ueberein, steht mindestens
        # eine der Regex am falschen Ort, und dann darf die Zahl nichts beweisen.
        # Die Kopfzeile hat DREI Gestalten, und eine Regex, die nur die erste kennt, ist genau bei den
        # VERBESSERUNGEN blind (PlanReport.java:186-203):
        #   "PLAN INCOMPLETE   4243 / 15004 cells proven   35 cells blocked"   blockers > 1
        #   "PLAN INCOMPLETE   4243 / 15004 cells proven"                      blockers 0 oder 1 -- Suffix faellt weg
        #   "PLAN READY   15004 / 15004 cells proven   137 scaffold blocks ..." Erfolg -- "COMPLETE" gibt es NICHT
        # Deshalb wird der Suffix NICHT verlangt, und die Blockerzahl kommt primaer aus "blockers: N"
        # (BasaltDryRun.java:728, unbedingt gedruckt, auch bei 0).
        $planLine = [regex]::Match($stageLog, 'PLAN\s+(READY|INCOMPLETE)\s+(\d+)\s*/\s*(\d+)\s*cells proven')
        $summary.plan_status  = if ($planLine.Success) { $planLine.Groups[1].Value } else { $null }
        $summary.plan_proven  = if ($planLine.Success) { [int]$planLine.Groups[2].Value } else { $null }
        $summary.plan_cells   = if ($planLine.Success) { [int]$planLine.Groups[3].Value } else { $null }
        $bLine = [regex]::Match($stageLog, 'blockers:\s*(\d+)')
        $summary.plan_blocked = if ($bLine.Success) { [int]$bLine.Groups[1].Value } else { $null }
        # Gegenproben aus unabhaengigen Druckstellen. Der Suffix der Kopfzeile ist nur noch Gegenprobe und
        # darf fehlen; fehlt er, gilt er als bestaetigt (er traegt dann keine Information).
        $qLine = [regex]::Match($stageLog, 'cells\s+(\d+)\s+proven\s+(\d+)')
        $sLine = [regex]::Match($stageLog, 'cells proven\s+(\d+)\s*cells blocked')
        $summary.plan_proven_crosscheck  = if ($qLine.Success) { [int]$qLine.Groups[2].Value } else { $null }
        $summary.plan_blocked_crosscheck = if ($sLine.Success) { [int]$sLine.Groups[1].Value } else { $null }
        $summary.plan_counts_agree =
            (($null -ne $summary.plan_proven) -and ($null -ne $summary.plan_blocked) -and
             ($summary.plan_proven -eq $summary.plan_proven_crosscheck) -and
             (($null -eq $summary.plan_blocked_crosscheck) -or
              ($summary.plan_blocked -eq $summary.plan_blocked_crosscheck)))
        # Der Vergleichsschluessel. proven ist NUR bei gleichem Ebenensatz entscheidungsabhaengig: wer eine
        # Ebene weniger schafft, verliert Zellen an die Wanduhr und nicht an eine Entscheidung. Deshalb wird
        # der Satz mitgeschrieben und der Vergleich verweigert sich, wenn er abweicht.
        # Der Satz wird NUR aus dem Logpraefix VOR der Planmeldung gebildet. Dahinter liegt der
        # DETERMINISM-Abschnitt, der Ebene -60 ein zweites Mal plant -- der eine Ort im Log, an dem
        # Nichtdeterminismus per Konstruktion erwartet wird. Wer ihn mitzaehlt, mischt die Ebenen zweier
        # Planlaeufe mit einer Kennzahl aus nur einem davon. Gemessen an 20260731-220919-dryrun: Ebenen
        # bei Zeile 71/87/219 vor der Planmeldung (220), eine weitere bei 588 danach.
        $prefix = if ($planLine.Success) { $stageLog.Substring(0, $planLine.Index) } else { $stageLog }
        $summary.layer_set = (([regex]::Matches($prefix, 'v3 plan:\s*layer\s*(-?\d+)\D{1,10}?(\d+)\s*cells\s+in\s+([\d.,]+)\s*s') |
                               ForEach-Object { [int]$_.Groups[1].Value }) -join ',')
        if ($layers.Count -eq 0) {
            $summary.result = 'INVALID'
            $summary.reason = 'Kein Ebenen-Ergebnis im Log gefunden. Regex in gate.ps1 an die reale Ausgabe von run-dryrun.ps1 anpassen, bevor diese Stufe irgendetwas beweist.'
        } elseif ($truncated) {
            # Der Exit-Code sagt hier nichts: run-dryrun.ps1 meldete auch mit erschossener Planer-JVM eine 0
            # (gemessen in 20260731-170559-dryrun). Genau deshalb urteilt diese Stufe ueber die Ebenen-Evidenz.
            $summary.result = 'PASS'
            # Der Ebenensatz, NICHT die rohe Liste der Abschlusszeilen: die enthaelt den DETERMINISM-Abschnitt
            # und las sich als "-60, -59, -58, -60, -59" -- fuenf Eintraege fuer drei Ebenen, praesentiert als
            # "vollstaendig geplant und vergleichbar". Genau diese Sorte Satz hat Iteration 11 ausgeloest.
            $summary.reason = "Gedeckelt nach $DryRunSeconds s. Abgeschlossen geplant: Ebenen $($summary.layer_set)" +
                              $(if ($summary.plan_proven -ne $null) { " ($($summary.plan_proven) Zellen bewiesen, $($summary.plan_blocked) blockiert)" } else { " -- KEINE Planmeldung, also kein Zellverlust-Beleg" }) +
                              ". Hoehere Ebenen sind NICHT gemessen -- diese Stufe belegt Zellverlust nur dort, wo sie hinsah."
        } else {
            $summary.result = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
        }
    }
    else {
        # ---- Zeittreue ----------------------------------------------------------------------
        # Der Bench zaehlt CLIENT-Ticks. Tickzahl und blocks/min lesen sich identisch, egal ob die
        # Beschleunigung gewirkt hat oder still nichts tat -- nur die Wanduhr trennt die beiden Faelle.
        # bench.ps1 misst das selbst; hier wird es in summary.json festgehalten, damit ein Vergleich
        # zwischen zwei Laeufen die ERREICHTE Rate vergleichen kann und nicht die angeforderte.
        # bench.ps1 :459 formatiert diese Zeile mit -f, also kulturabhaengig: auf einem deutschen Windows steht
        # dort "23,4" und nicht "23.4". Beide Schreibweisen lesen, dann auf den Punkt normalisieren.
        $tr = [regex]::Match($stageLog, 'measured\s+([\d.,]+)\s+client ticks/real second\s+\(asked\s+([\d.,]+)\)')
        if ($tr.Success) {
            $summary.ticks_per_sec_achieved = [double]($tr.Groups[1].Value -replace ',', '.')
            $summary.ticks_per_sec_asked    = [double]($tr.Groups[2].Value -replace ',', '.')
            $summary.time_faithful = ($summary.ticks_per_sec_achieved -ge $summary.ticks_per_sec_asked * 0.85)
        } else {
            $summary.ticks_per_sec_achieved = $null
            $summary.ticks_per_sec_asked    = 20.0 * $TimeScale
            $summary.time_faithful          = $null
        }

        # Bench-Stufen: der Exit-Code allein zaehlt nicht. Ohne frisches Archivlog gibt es keinen Lauf.
        $after = @()
        if (Test-Path $benchOut) { $after = Get-ChildItem $benchOut -Filter *.log | ForEach-Object { $_.Name } }
        $new = @($after | Where-Object { $before -notcontains $_ })
        if ($ReviveFrom) {
            # Ein nachgetragener Lauf kann sein Archivlog nicht ueber "neu seit dem Start" finden -- es liegt
            # laengst da. bench.ps1 druckt den vollen Pfad in die Zusammenfassung; genau der wird genommen,
            # damit nicht das juengste Log irgendeines spaeteren Laufs eingesammelt wird.
            $lm = [regex]::Matches($stageLog, 'bench-out[\\/]([^\s\\/]+\.log)')
            $names = @($lm | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique |
                       Where-Object { $after -contains $_ })
            $new = $names
            $summary.revived_log_from_stage_log = $names
        }
        $summary.archived_logs = $new

        $verdictSrc = $stageLog
        if ($new.Count -gt 0) {
            $newest = if ($ReviveFrom) { Get-Item (Join-Path $benchOut $new[$new.Count - 1]) }
                      else { Get-ChildItem $benchOut -Filter *.log | Sort-Object LastWriteTime | Select-Object -Last 1 }
            Copy-Item $newest.FullName (Join-Path $outDir 'client.log')
            # -Encoding UTF8 ist nicht kosmetisch. Java schreibt das Clientlog als UTF-8 ohne BOM; PowerShell 5.1
            # nimmt ohne diesen Schalter die ANSI-Codepage an, und der Em-Dash in jeder DIVERGENCE-Zeile kommt als
            # drei Ersatzzeichen an. Der Familienschluessel traegt den Muell dann bis in summary.json.
            # stage.log wird bewusst OHNE -Encoding gelesen: Tee-Object schreibt in 5.1 UTF-16 mit BOM, und den
            # erkennt Get-Content nur, wenn man ihm keine Kodierung vorschreibt.
            $verdictSrc = Get-Content $newest.FullName -Raw -Encoding UTF8
        }

        # Alle folgenden Groessen sind Felder EINER Zeile -- der Verdiktzeile von BuilderBench:
        #   [BENCH] run=9ee44eec verdict=INACTIVE scenario=etz-basalt.litematic placed=2624/15004
        #   remaining=12380 ticks=66440 blocksPerMin=47.4 ... divergences=94 replans=93 @t50k=2124
        # Sie muessen deshalb aus dieser Zeile gelesen werden und nicht aus dem ganzen Log.
        #
        # SIEBTE MESSFALLE, gefunden am 01.08.2026, und sie hat JEDEN der drei full-Laeufe falsch abgelesen:
        # `[regex]::Match` liefert den ERSTEN Treffer im ganzen Log. Vor der Verdiktzeile steht aber die
        # Checkpointzeile
        #   [BENCH] run=9ee44eec checkpoint t=50000 placed=2124/15004
        # und die traegt dasselbe Muster. Gemessen in den Archiven:
        #   20260731-231939-full   summary.placed = 2124   richtig waeren 2624   (Verdiktzeile, Zeile 5942)
        #   20260801-015252-full   summary.placed = 2145   richtig waeren 3272   (letzte Sample-Zeile)
        #   20260801-040936-full   summary.placed = 2108   richtig waeren 3235   (letzte Sample-Zeile)
        # Der Zahlenwert war also nie der Endstand, sondern immer der Zwischenstand bei t=50000 -- eine Zahl,
        # die sich wie ein Ergebnis liest und keines ist. Besonders bissig beim Vergleich zweier Fenster: sie
        # ist bei JEDEM Lauf an derselben Stelle gemessen und sieht deshalb sogar plausibel aus.
        #
        # Der Rueckfall auf die Sample-Reihe weiter unten hat NICHT gegriffen, weil er nur bei $null feuert --
        # und $null war es nie. Ein Rueckfall, der von einem falschen Wert verdeckt wird, ist schlimmer als
        # keiner: er sieht im Code so aus, als sei der Fall behandelt.
        #
        # Deshalb: die LETZTE Zeile mit verdict= ist die Quelle. Letzte und nicht erste, weil `suite` mehrere
        # Szenarien in ein Log schreibt -- der Kommentar bei der Szenarientabelle behauptete schon immer, das
        # geparste verdict= beschreibe das letzte Szenario, und ab hier stimmt das auch.
        # Gibt es keine Verdiktzeile (Fenster mit -NoStall, das an -TimeoutSeconds endet), bleibt alles $null,
        # und genau dann -- und nur dann -- fuellt die Sample-Reihe weiter unten die Werte.
        $verdictLine = ''
        foreach ($line in ($verdictSrc -split "`n")) {
            if ($line -match 'verdict=[A-Z_]+') { $verdictLine = $line }
        }
        $summary.verdict_line = if ($verdictLine) { $verdictLine.Trim() } else { $null }

        $v = [regex]::Match($verdictLine, 'verdict=([A-Z_]+)')
        $summary.verdict     = if ($v.Success) { $v.Groups[1].Value } else { $null }
        $p = [regex]::Match($verdictLine, 'placed=(\d+)/(\d+)')
        $summary.placed      = if ($p.Success) { [int]$p.Groups[1].Value } else { $null }
        $summary.total_cells = if ($p.Success) { [int]$p.Groups[2].Value } else { $null }
        $t = [regex]::Match($verdictLine, 'ticks=(\d+)')
        $summary.ticks       = if ($t.Success) { [int]$t.Groups[1].Value } else { $null }
        $d = [regex]::Match($verdictLine, 'divergences=(\d+)')
        $summary.divergences = if ($d.Success) { [int]$d.Groups[1].Value } else { $null }
        $r = [regex]::Match($verdictLine, 'replans=(\d+)')
        $summary.replans     = if ($r.Success) { [int]$r.Groups[1].Value } else { $null }

        # Der Zwischenstand bei t=50000 wird nicht weggeworfen, sondern bekommt einen eigenen, ehrlichen Namen.
        # Er ist als Vergleichsgroesse sogar brauchbar -- er steht in jedem Lauf am selben Tick. Nur eben nicht
        # als `placed`.
        $cp = [regex]::Match($verdictSrc, 'checkpoint t=(\d+) placed=(\d+)/(\d+)')
        $summary.checkpoint_tick   = if ($cp.Success) { [int]$cp.Groups[1].Value } else { $null }
        $summary.checkpoint_placed = if ($cp.Success) { [int]$cp.Groups[2].Value } else { $null }
        # Das Server-Audit steht NICHT im archivierten Clientlog. Es ist eine Write-Host-Zeile von bench.ps1 :428,
        # also im Ausgabestrom des Wrappers -- das archivierte Log enthaelt nur, was der Client selbst geschrieben
        # hat. Gegen $verdictSrc (= Clientlog) gesucht war die Trefferzahl in allen 70 vorhandenen Archivlogs
        # exakt null, womit `full` unabhaengig vom Ergebnis immer FAIL gemeldet haette. Deshalb hier stage.log.
        $a = [regex]::Match($stageLog, 'server audit:\s*(\d+)/(\d+)\s*cells confirmed')
        $summary.server_audit = if ($a.Success) { "$($a.Groups[1].Value)/$($a.Groups[2].Value)" } else { $null }

        # DREIZEHNTE MESSFALLE, vor dem ersten V1-Lauf notiert statt nach dem ersten Fehlschluss.
        # `divergences=0 replans=0` steht auch dann auf der Verdiktzeile, wenn die Engine gar nichts zaehlt:
        # IBuilderProcess:194 gibt {0,0} als DEFAULT zurueck, BuilderProcess.java ueberschreibt die Methode
        # nicht (nur PlannedBuilderProcess:938 tut es), und BuilderBench:600 druckt das Paar, sobald das
        # Array zwei Elemente hat -- was der Default erfuellt. Fuer -Engine v2 ist die Null also eine
        # KONSTANTE, keine Messung. Schlimmer: BuilderBench:720-721 leitet daraus `faithful` und damit
        # SUCCESS statt DIVERGED ab, V1 kann diese Pruefung also gar nicht verfehlen.
        # Wer die beiden Zahlen zwischen v2 und v3 vergleicht, vergleicht eine Konstante mit einer Messung.
        $summary.fidelity_counters_meaningful = ($Engine -eq 'v3')

        # Ein Basalt-FENSTER endet planmaessig ohne Verdikt: mit -NoStall laeuft der Client, bis bench.ps1 ihn an
        # -TimeoutSeconds abraeumt, und dann steht kein `verdict=` im Log. Ohne diesen Rueckfall haette das Gate
        # genau die Messung als 'INVALID' weggeworfen, fuer die die Stufe existiert. Die Sample-Zeile
        # (BuilderBench :704) traegt dieselben Zahlen und wird jede Sekunde geschrieben:
        #   [BENCH] run=<id> sample t=27860 remaining=13687/15004 paused=false active=true pos=...
        $samples = [regex]::Matches($verdictSrc, 'sample t=(\d+)\s+remaining=(\d+)/(\d+)')
        if ($samples.Count -gt 0) {
            $last = $samples[$samples.Count - 1]
            $summary.last_sample_ticks     = [int]$last.Groups[1].Value
            $summary.last_sample_remaining = [int]$last.Groups[2].Value
            $summary.samples               = $samples.Count
            # Die volle Reihe, weil ein spaeterer Vergleich sie braucht: zwei Fenster enden bei verschiedenen
            # Tickstaenden, und nur mit der Reihe laesst sich beides auf denselben Tick zurueckrechnen.
            $summary.sample_series = @($samples | ForEach-Object {
                @{ t = [int]$_.Groups[1].Value; placed = [int]$_.Groups[3].Value - [int]$_.Groups[2].Value } })
            $summary.max_tick = [int]$last.Groups[1].Value
            if ($null -eq $summary.ticks)       { $summary.ticks       = [int]$last.Groups[1].Value }
            if ($null -eq $summary.total_cells) { $summary.total_cells = [int]$last.Groups[3].Value }
            if ($null -eq $summary.placed)      { $summary.placed      = [int]$last.Groups[3].Value - [int]$last.Groups[2].Value }
        }

        # Divergenzfamilien zaehlen -- die Liste, die ein Fix erklaeren muss.
        # Echte Zeilen (aus run\bench-out\, nicht geraten):
        #   v3: DIVERGENCE at action 163 - APPROACH waited 61 ticks (patience 60) on PLACE at 86,-60,83: waiting
        #       for path-travel momentum to stop before the proved fine approach
        #   v3: DIVERGENCE at action 29 - the gate said WRONG_BLOCK for 60 ticks
        # Die alte Regex 'DIVERGENCE[^:]*:\s*(.+)$' hat beide falsch behandelt: bei der ersten schnitt sie den
        # Familiennamen ab und behielt nur den Teil hinter dem Doppelpunkt ("waiting for ..."), bei der zweiten
        # fand sie gar nichts, weil hinter DIVERGENCE kein Doppelpunkt mehr kommt -- diese Familie waere still
        # als 0 gezaehlt worden. Der Trenner ist ein Em-Dash und wird als \D gelesen, damit die Kodierung des
        # Logs keine Rolle spielt.
        $fam = @{}
        foreach ($line in ($verdictSrc -split "`n")) {
            $fm = [regex]::Match($line, 'DIVERGENCE\s+at\s+action\s+-?\d+\s*\D{1,10}?\s*([A-Za-z].*)$')
            if ($fm.Success) {
                $key = ($fm.Groups[1].Value -replace '-?\d+[,.]?\d*', '<n>') -replace '(<n>[,\s]*)+', '<n>'
                $key = $key.Trim()
                if ($fam.ContainsKey($key)) { $fam[$key]++ } else { $fam[$key] = 1 }
            }
        }
        $summary.divergence_families = $fam

        if ($new.Count -eq 0 -and -not $v.Success -and $samples.Count -eq 0) {
            $summary.result = 'INVALID'
            $summary.reason = 'Kein neues Archivlog, kein verdict= und keine Sample-Zeile. Der Lauf hat nicht stattgefunden -- Exit-Code ignorieren.'
        }
        elseif ($Stage -eq 'suite') {
            # Bei mehreren Szenarien beschreibt das oben geparste verdict= nur das LETZTE. Die Tabelle, die
            # bench.ps1 :529 am Ende druckt, traegt alle -- ohne sie sagt ein FAIL nicht, welches Szenario fiel.
            #   [bench] wall       SUCCESS        42s  placed=120/120 ticks=1880 bpm=76.6
            $summary.scenarios = @{}
            foreach ($sm in [regex]::Matches($stageLog, '\[bench\]\s+(\S+)\s+([A-Z_]+)\s+\d+s')) {
                $summary.scenarios[$sm.Groups[1].Value] = $sm.Groups[2].Value
            }
            $summary.result = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
            if ($code -ne 0) { $summary.reason = "$code Szenarien nicht SUCCESS: " +
                ((@($summary.scenarios.GetEnumerator() | Where-Object { $_.Value -ne 'SUCCESS' } |
                    ForEach-Object { "$($_.Key)=$($_.Value)" })) -join ', ') }
        }
        elseif ($Stage -eq 'ringbig') {
            $ok = ($summary.verdict -eq 'SUCCESS') -and ($summary.divergences -eq 0) -and ($summary.replans -eq 0)
            $summary.result = if ($ok) { 'PASS' } else { 'FAIL' }
            if (-not $ok) { $summary.reason = "ringbig: verdict=$($summary.verdict) divergences=$($summary.divergences) replans=$($summary.replans) -- Regressionsschutz nicht erfuellt." }
            elseif (-not $summary.fidelity_counters_meaningful) {
                $summary.reason = "verdict=SUCCESS. ABER: divergences/replans sind bei -Engine $Engine keine Messung, " +
                                  'sondern die Vorgabe {0,0} aus IBuilderProcess:194 -- diese Stufe hat auf dieser ' +
                                  'Engine nur das Verdikt geprueft.'
            }
        }
        elseif ($Stage -eq 'full') {
            $ok = ($summary.verdict -eq 'SUCCESS') -and $summary.server_audit -and
                  ($summary.server_audit -split '/')[0] -eq ($summary.server_audit -split '/')[1]
            $summary.result = if ($ok) { 'PASS' } else { 'FAIL' }
            if (-not $ok) { $summary.reason = "Vollauf: verdict=$($summary.verdict) audit=$($summary.server_audit)." }
        }
        else {
            # basalt: das Gate faellt hier bewusst KEIN Urteil ueber Fortschritt.
            # Der Vergleich gegen accepted-baseline.json ist Sache des Kritikers, und er braucht
            # zwei Laeufe mit gleichen Flags und gleichem Tick-Budget.
            # Ein Fenster ohne Verdikt ist die Regel, nicht der Fehler -- siehe die Sample-Rueckfall oben.
            # INVALID bleibt fuer den Fall, dass es ueberhaupt keine Zahlen gibt.
            $summary.result = if ($summary.verdict -or ($null -ne $summary.ticks)) { 'MEASURED' } else { 'INVALID' }
            if (-not $summary.verdict -and ($null -ne $summary.ticks)) {
                $summary.reason = "Fenster ohne Verdikt (planmaessig bei -NoStall): $($summary.placed)/$($summary.total_cells) Zellen " +
                                  "in $($summary.ticks) Ticks, aus der letzten Sample-Zeile."
            }
            elseif (-not $summary.verdict) { $summary.reason = 'Kein Verdikt und keine Sample-Zeile im Log.' }
            elseif ($summary.time_faithful -eq $false) {
                $summary.reason = "Nicht zeittreu: $($summary.ticks_per_sec_achieved) statt $($summary.ticks_per_sec_asked) Ticks/s. " +
                                  "Tickbasierte Zahlen bleiben gueltig, jede Wanduhr-Aussage nicht. Ein Vergleich gegen die Baseline " +
                                  "ist nur zulaessig, wenn deren erreichte Rate im selben Bereich lag."
            }
        }
    }

    # ---- Vergleich auf gemeinsamem Tickstand ---------------------------------------------------
    # "1358 gegen 1290 Zellen" ist keine Aussage, solange die beiden Fenster verschieden lang waren, und das
    # sind sie fast immer: -TickBudget ist eine Wanduhr-Umrechnung, kein Abbruch bei Tick N, und die erreichte
    # Tickrate schwankt mit der Last. Deshalb wird auf den kleinsten gemeinsamen Tickstand zugeschnitten und
    # dieser Tick festgehalten -- eine spaetere Lektuere soll nicht raten muessen, worauf sich das Delta bezieht.
    if ($CompareTo) {
        $otherPath = Join-Path $root "autonomy\runs\$CompareTo\summary.json"
        if (-not (Test-Path $otherPath)) {
            $summary.comparison = @{ against = $CompareTo; error = "summary.json nicht gefunden: $otherPath" }
        } else {
            $other = Get-Content $otherPath -Raw | ConvertFrom-Json
            if ($Stage -eq 'dryrun') {
                # Zellverlust-Waechter. Er urteilt NUR bei identischem Ebenensatz: proven mischt sonst
                # Entscheidungsqualitaet mit Planungstempo, und ein Patch, der nur langsamer ist, saehe aus
                # wie einer, der Zellen verliert. Bei abweichendem Satz heisst das Ergebnis INCONCLUSIVE --
                # ausdruecklich NICHT "bestanden".
                # Baselines von vor dieser Parseraenderung haben die Felder nicht. Statt gespeicherte Evidenz
                # zu ueberschreiben wird ihr stage.log NACHGELESEN -- die Datei bleibt byteweise unangetastet,
                # und die Zahlen entstehen aus derselben Quelle wie die des aktuellen Laufs.
                $otherProven = $other.plan_proven; $otherBlocked = $other.plan_blocked; $otherSet = $other.layer_set
                if ($null -eq $otherProven) {
                    $otherLogPath = Join-Path $root "autonomy\runs\$CompareTo\stage.log"
                    if (Test-Path $otherLogPath) {
                        $otherLog = Get-Content $otherLogPath -Raw
                        # DIESELBE Regex wie fuer den eigenen Lauf (:318). Vorher stand hier noch die alte mit
                        # Pflicht-Suffix und "COMPLETE" -- die Baseline-Seite waere also weiter blind gewesen
                        # fuer PLAN READY und fuer Blocker <= 1, waehrend die eigene Seite es nicht mehr ist.
                        $om = [regex]::Match($otherLog, 'PLAN\s+(READY|INCOMPLETE)\s+(\d+)\s*/\s*(\d+)\s*cells proven')
                        $ob0 = [regex]::Match($otherLog, 'blockers:\s*(\d+)')
                        if ($om.Success -and $ob0.Success) {
                            $otherProven  = [int]$om.Groups[2].Value
                            $otherBlocked = [int]$ob0.Groups[1].Value
                            $otherSet = (([regex]::Matches($otherLog, 'v3 plan:\s*layer\s*(-?\d+)\D{1,10}?(\d+)\s*cells\s+in\s+([\d.,]+)\s*s') |
                                          ForEach-Object { [int]$_.Groups[1].Value } | Sort-Object -Unique) -join ',')
                            $summary.comparison_backfilled_from_log = $true
                        }
                    }
                }
                # ZWEI BEKANNTE SCHWAECHEN, hier benannt statt stillschweigend in Kauf genommen. Beide sind
                # heute folgenlos, beide sind es aus GRUENDEN, die niemand garantiert hat:
                #
                # (1) Die beiden Seiten normalisieren VERSCHIEDEN. Links steht die Praefixreihenfolge des
                #     Logs (Planungsreihenfolge, von unten), rechts sortiert der Rueckfall numerisch mit
                #     Sort-Object -Unique. Dass beides "-60,-59,-58" ergibt, ist ein ZUFALL dieses
                #     Schematics: aufsteigende [int]-Sortierung faellt hier mit der Planungsreihenfolge
                #     zusammen. Ein Schematic, das von oben plant, brauchte das nicht zu tun -- Folge waere
                #     ein unnoetiges INCONCLUSIVE, kein falsches PASS.
                # (2) Der Teilmengentest unten vergleicht ELEMENTZAHLEN. Rechts ist dedupliziert, links seit
                #     der Praefix-Begrenzung nicht mehr. Ein Duplikat LINKS maskierte einen echten Verlust
                #     als INCONCLUSIVE_LAYERS -- also genau das, was der Teilmengentest abschaffen soll.
                #     Heute unmoeglich, weil view.layers() duplikatfrei ist und je Iteration genau ein
                #     reportLayer faellt. Das ist ein anderer Grund als der, aus dem -Unique links entfiel
                #     (dort ging es um den DETERMINISM-Abschnitt) -- zwei Aussagen, nicht eine.
                #
                # Beides waere durch einen MENGENvergleich statt Zeichenkettenvergleich behoben. Nicht hier
                # gemacht -- und die erste Begruendung dafuer ("Rechenlogik, der Lauf ist gefahren") war
                # INKONSEQUENT: sie gilt woertlich auch fuer die Regexaenderung im Rueckfallpfad, die sehr
                # wohl gemacht wurde. Was den Unterschied traegt, ist nicht die Kategorie, sondern die
                # MESSUNG: fuer die Regexaenderung liegt ein Beleg vor, dass sie auf der Vergleichsbasis
                # ergebnisgleich ist (evidence/20260802-p06-cellloss-guard/regex-change-is-outcome-neutral).
                # Fuer den Mengenvergleich liegt keiner vor. Er gehoert in den naechsten Diff, MIT Beleg --
                # nicht, weil er gefaehrlicher waere, sondern weil er ungemessen ist.
                # Fehlrichtung beider aufgeschobenen Schwaechen ist geprueft: ein Duplikat links macht
                # $setHere.Count -ge $setThere.Count, also kein isProperSubset, also -not $sameSet, also
                # INCONCLUSIVE_LAYERS und damit result = 'INCONCLUSIVE'. Nie PASS.
                $sameSet = [bool]$summary.layer_set -and ($summary.layer_set -eq $otherSet)
                # Gegenprobe auch auf den nachgelesenen Baselinewert -- sonst ist die Strenge asymmetrisch.
                # Traegt die Baseline die Felder schon selbst, gilt IHRE Gegenprobe -- nicht blind $true.
                $otherAgrees = if ($null -ne $other.plan_counts_agree) { [bool]$other.plan_counts_agree } else { $true }
                if ($summary.comparison_backfilled_from_log) {
                    $ob = [regex]::Match($otherLog, 'blockers:\s*(\d+)')
                    $oq = [regex]::Match($otherLog, 'cells\s+(\d+)\s+proven\s+(\d+)')
                    $otherAgrees = $ob.Success -and $oq.Success -and
                                   ([int]$oq.Groups[2].Value -eq $otherProven) -and
                                   ([int]$ob.Groups[1].Value -eq $otherBlocked)
                    $summary.comparison_other_log_sha256 = (Get-FileHash $otherLogPath -Algorithm SHA256).Hash
                }
                $bothParsed = $summary.plan_counts_agree -and $otherAgrees -and ($null -ne $otherProven)
                $dProven  = if ($bothParsed) { $summary.plan_proven  - [int]$otherProven  } else { $null }
                $dBlocked = if ($bothParsed) { $summary.plan_blocked - [int]$otherBlocked } else { $null }
                # Ein SCHRUMPFENDER Ebenensatz ist kein fehlendes Urteil, sondern das Urteil selbst --
                # ABER die Begruendung dafuer ist heikler, als sie aussieht, und eine falsche stand hier schon.
                #
                # FALSCH WAERE: "eine am Deckel erschossene JVM druckt keine Planmeldung". Widerlegt an genau
                # dem Lauf, der die Baseline dieses Waechters traegt: 20260802-045927-dryrun hat
                # dryrun_truncated=true, elapsed 2702 s gegen cap 2700 -- UND plan_proven=4243. Der Kill kam
                # 21 Minuten NACH der Planmeldung.
                #
                # RICHTIG IST: die Planmeldung wird gedruckt, NACHDEM die Ebenenschleife zurueckkehrt
                # (OrderPlanner:924-963, danach return report(...)). Ein Kill kann sie also nicht ABSCHNEIDEN,
                # wohl aber danach zuschlagen. Ihr Vorhandensein beweist deshalb: die Schleife ist regulaer
                # zurueckgekehrt. Sie hat GENAU DREI Ausgaenge:
                #   1. view.layers() erschoepft            -> voller Satz, kein Schrumpfen
                #   2. layersPlanned >= layerBudget        -> OrderPlanner:924-929, "Deferred, NOT blocked"
                #   3. state.blockers > blockersBefore     -> OrderPlanner:958-963, der Blockerabbruch
                # Nur 3 bedeutet Zellverlust. 2 bedeutet ihn NICHT -- und erzeugt trotzdem Planmeldung plus
                # echt kleineren Ebenensatz.
                #
                # DIESE REGEL GILT ALSO NUR, SOLANGE AUSGANG 2 NICHT FEUERT. Er feuert heute nicht, weil
                # layerBudget auf Integer.MAX_VALUE steht (OrderPlanner:810) und BasaltDryRun
                # planAtMostLayers NICHT aufruft -- geprueft, kein Treffer. Der einzige Aufrufer ist
                # PlannedBuilderProcess:525 mit LAYERS_PER_DRY_RUN = 1, und der laeuft nicht in dieser Stufe.
                #
                # WER DAS AENDERT, MUSS HIERHER: setzt BasaltDryRun je ein Ebenenbudget -- etwa um die
                # 45-Minuten-Laufzeit zu druecken --, wird dieser Waechter zum Fehlalarmgenerator und meldet
                # FAIL gegen jede gespeicherte Baseline. Und er koennte es nicht einmal bemerken: PlanReport
                # traegt KEIN Trunkierungsmerkmal, OrderPlanner.truncated() ist ein separater Accessor, den
                # nur PlannedBuilderProcess:528 liest. Aus dem Log ist Ausgang 2 von Ausgang 3 NICHT
                # unterscheidbar.
                #
                # ZWEITE ungarantierte Bedingung, ebenso wenig eine Garantie: Ausgang 1 ("voller Satz")
                # setzt voraus, dass view.layers() zwischen den Laeufen GLEICH ist. SchematicView:293-294 --
                # "ONLY the layers that hold at least one cell the plan has something to do in" -- also eine
                # Funktion von Schematic und Inventar, nicht von Planerentscheidungen. Ein Patch am View-Bau
                # koennte den Satz legitim schrumpfen und ein falsches CELL_LOSS ausloesen. Fehlrichtung ist
                # FAIL, nicht PASS -- also die sichere, aber es kostet einen Lauf, bis es jemand merkt.
                $setHere  = @($summary.layer_set -split ',' | Where-Object { $_ })
                $setThere = @($otherSet -split ',' | Where-Object { $_ })
                $isProperSubset = ($setHere.Count -lt $setThere.Count) -and
                                  (@($setHere | Where-Object { $setThere -notcontains $_ }).Count -eq 0)
                $verdictCells =
                    if (-not $bothParsed)                        { 'INCONCLUSIVE_PARSE' }
                    elseif ($isProperSubset)                     { 'CELL_LOSS' }
                    elseif (-not $sameSet)                       { 'INCONCLUSIVE_LAYERS' }
                    elseif (($dProven -lt 0) -or ($dBlocked -gt 0)) { 'CELL_LOSS' }
                    else                                         { 'NO_CELL_LOSS' }
                $summary.comparison = @{
                    against            = $CompareTo
                    kind               = 'cell-loss'
                    layer_set_here     = $summary.layer_set
                    layer_set_there    = $otherSet
                    layer_sets_equal   = $sameSet
                    layer_set_shrank   = $isProperSubset
                    plan_status_here   = $summary.plan_status
                    proven_here        = $summary.plan_proven
                    proven_there       = $otherProven
                    blocked_here       = $summary.plan_blocked
                    blocked_there      = $otherBlocked
                    delta_proven       = $dProven
                    delta_blocked      = $dBlocked
                    counts_agree_here  = $summary.plan_counts_agree
                    counts_agree_there = $otherAgrees
                    cells_in_scope     = $summary.plan_cells
                    verdict            = $verdictCells
                    note               = 'Reichweite: gemessen werden nur die Ebenen in layer_set, nicht plan_cells. delta_proven und delta_blocked sind NICHT unabhaengig (proven ~ Summe todo - blocked); der Waechter sieht praktisch die Blockerzahl, nicht schlechtere aber noch loesbare Geometrie. INCONCLUSIVE ist kein Bestehen.'
                }
                Write-Host ""
                Write-Host "ZELLVERLUST gegen ${CompareTo}: $verdictCells  (proven $($summary.plan_proven) vs $otherProven, blockiert $($summary.plan_blocked) vs $otherBlocked)"
                if ($verdictCells -eq 'CELL_LOSS') {
                    if ($isProperSubset) {
                        Write-Host "  Der Planer endete REGULAER (Planmeldung vorhanden) bei Ebenen $($summary.layer_set) statt $otherSet."
                        Write-Host "  Ein frueherer Blockerabbruch (OrderPlanner:958) -- nicht die Wanduhr. Alle Ebenen darueber entfallen."
                        $summary.result = 'FAIL'
                        $summary.reason = "Zellverlust: Ebenensatz schrumpfte von '$otherSet' auf '$($summary.layer_set)' bei regulaerem Planende. Der Planer brach frueher ab."
                    } else {
                        Write-Host "  Ebenensatz $($summary.layer_set) identisch -- der Verlust ist eine Entscheidung, keine Wanduhr."
                        $summary.result = 'FAIL'
                        $summary.reason = "Zellverlust bei gleichem Ebenensatz: proven $dProven, blockiert +$dBlocked gegen $CompareTo."
                    }
                } elseif ($verdictCells -like 'INCONCLUSIVE*') {
                    # Ein nicht gefaelltes Urteil darf NIEMALS als PASS durchgehen. Vorher blieb hier das
                    # 'PASS' der Deckel-Logik stehen -- mitsamt der Begruendung "vollstaendig geplant und
                    # vergleichbar", die dann das Gegenteil dessen behauptete, was gemessen wurde.
                    Write-Host "  WARNUNG: kein Urteil moeglich ($verdictCells). Diese Stufe belegt hier NICHTS."
                    $summary.result = 'INCONCLUSIVE'
                    $summary.reason = "Zellverlust-Vergleich gegen $CompareTo nicht moeglich ($verdictCells): " +
                                      "Ebenensatz hier '$($summary.layer_set)' gegen dort '$otherSet', " +
                                      "Gegenproben hier=$($summary.plan_counts_agree) dort=$otherAgrees. " +
                                      "Diese Stufe hat NICHTS belegt."
                }
            }
            elseif ((-not $other.sample_series) -or (-not $summary.sample_series)) {
                $summary.comparison = @{ against = $CompareTo
                                         error = 'Mindestens einem der beiden Laeufe fehlt sample_series; nicht zuschneidbar.' }
            } else {
                $tick = [math]::Min([int]$summary.max_tick, [int]$other.max_tick)
                # Zellstand bei Tick T = letzte Sample-Zeile mit t <= T. Nicht interpolieren: zwischen zwei
                # Samples ist unbekannt, wann die Zelle fiel, und eine erfundene Zwischenzahl liest sich wie
                # eine Messung.
                $here  = @($summary.sample_series | Where-Object { $_.t -le $tick } | Select-Object -Last 1)
                $there = @($other.sample_series   | Where-Object { $_.t -le $tick } | Select-Object -Last 1)
                $ph = if ($here.Count)  { [int]$here[0].placed }  else { $null }
                $pt = if ($there.Count) { [int]$there[0].placed } else { $null }
                # Zeittreue: tickbasierte Zahlen bleiben gueltig, aber zwei Laeufe mit weit auseinander
                # liegender erreichter Rate haben nicht dieselbe Welt gesehen (Server/Client-Verhaeltnis).
                $rh = $summary.ticks_per_sec_achieved
                $rt = $other.ticks_per_sec_achieved
                $ratesOk = $null
                if ($rh -and $rt) { $ratesOk = ([math]::Abs($rh - $rt) / [math]::Max($rh, $rt)) -le 0.15 }
                $summary.comparison = @{
                    against          = $CompareTo
                    at_tick          = $tick
                    placed_here      = $ph
                    placed_there     = $pt
                    delta            = if (($null -ne $ph) -and ($null -ne $pt)) { $ph - $pt } else { $null }
                    max_tick_here    = [int]$summary.max_tick
                    max_tick_there   = [int]$other.max_tick
                    rate_here        = $rh
                    rate_there       = $rt
                    rates_comparable = $ratesOk
                    note             = 'Zugeschnitten auf den kleinsten gemeinsamen Tickstand. Ein Delta bei rates_comparable=false ist kein Beleg.'
                }
                Write-Host ""
                Write-Host "VERGLEICH gegen $CompareTo bei Tick ${tick}:  $ph vs $pt Zellen (Delta $($summary.comparison.delta))"
                if ($ratesOk -eq $false) { Write-Host "  WARNUNG: erreichte Raten $rh vs $rt -- nicht vergleichbar." }
            }
        }
    }
    Write-Summary $summary
    switch ($summary.result) {
        'PASS'     { exit 0 }
        'MEASURED' { exit 0 }
        default    { exit 1 }
    }
}
finally {
    # Nur den EIGENEN Lock loesen. Eine Wiederherstellung hat keinen genommen und darf deshalb auch
    # keinen fremden entfernen -- sonst raeumt ein Nachtrag den Schutz eines gerade laufenden Benchs ab.
    if (-not $ReviveFrom) { Remove-Item $lock -Force -ErrorAction SilentlyContinue }
}
