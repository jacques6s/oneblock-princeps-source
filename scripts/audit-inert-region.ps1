[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_-]+$')][string]$RunId,
    [int]$MinX=52, [int]$MinY=-60, [int]$MinZ=52,
    [int]$MaxX=88, [int]$MaxY=-46, [int]$MaxZ=88
)

$ErrorActionPreference='Stop'
if($MinX -gt $MaxX -or $MinY -gt $MaxY -or $MinZ -gt $MaxZ){throw 'Inverted audit bounds'}
$offsetY=250
$destinationMinY=[long]$MinY+$offsetY
$destinationMaxY=[long]$MaxY+$offsetY
if($MinY -lt -64 -or $MaxY -gt 319 -or $destinationMinY -lt -64 -or $destinationMaxY -gt 319){
    throw 'Source and expected volumes must fit the Overworld build height [-64,319]'
}
if($destinationMinY -le $MaxY -and $destinationMaxY -ge $MinY){throw 'Source and expected volumes overlap'}
$volume=([long]$MaxX-$MinX+1)*([long]$MaxY-$MinY+1)*([long]$MaxZ-$MinZ+1)
if($volume -le 0 -or $volume -gt 32768){throw 'Audit volume must contain 1..32768 cells'}
$taskRoot=Split-Path -Parent $PSScriptRoot
$manifest=Join-Path $taskRoot "run/bench-out/$RunId-cells.txt"
$rcon=Join-Path $PSScriptRoot 'rcon.ps1'
$expected=@(Get-Content -LiteralPath $manifest | Where-Object { $_.Trim() })
if(-not $expected.Count){throw 'Empty expected-cell manifest'}
$coordinates=[System.Collections.Generic.HashSet[string]]::new()
# This audit is deliberately limited to the inert stone/glass fixtures. Door secondary
# halves and neighbor-derived properties require another expected-world adapter.
foreach($line in $expected){
    if($line -notmatch '^(-?\d+) (-?\d+) (-?\d+) minecraft:(?:stone|black_stained_glass)$'){
        throw "This full-volume audit accepts only inert stone/glass fixtures: $line"
    }
    $cellX=[int]$Matches[1]; $cellY=[int]$Matches[2]; $cellZ=[int]$Matches[3]
    if(-not $coordinates.Add("$cellX $cellY $cellZ")){throw 'Duplicate target coordinates'}
    if($cellX -lt $MinX -or $cellX -gt $MaxX -or
       $cellY -lt $MinY -or $cellY -gt $MaxY -or
       $cellZ -lt $MinZ -or $cellZ -gt $MaxZ){throw 'Target outside audited volume'}
}

# Construct an independent expected volume above the fixture, outside any route
# the bot could use. No command alters the original build. /execute if blocks
# compares every cell, including the air where temporary support must be absent.
$commands=@("fill $MinX $($MinY+$offsetY) $MinZ $MaxX $($MaxY+$offsetY) $MaxZ air")
foreach($line in $expected){
    $parts=$line -split ' ',4
    $commands += "setblock $($parts[0]) $([int]$parts[1]+$offsetY) $($parts[2]) $($parts[3])"
}
$commands += "execute if blocks $MinX $MinY $MinZ $MaxX $MaxY $MaxZ $MinX $($MinY+$offsetY) $MinZ all"
$out=@(& powershell -NoProfile -ExecutionPolicy Bypass -File $rcon @commands 2>&1)
$archive=Join-Path $taskRoot "run/bench-out/$RunId-full-region-audit.log"
$out | Set-Content -LiteralPath $archive
$verdict=$out[-1].ToString()
$passed=$LASTEXITCODE -eq 0 -and $verdict -match 'Test passed'
@{
    run_id=$RunId; target_cells=$expected.Count; volume_cells=$volume
    bounds=@($MinX,$MinY,$MinZ,$MaxX,$MaxY,$MaxZ)
    expected_bounds=@($MinX,$destinationMinY,$MinZ,$MaxX,$destinationMaxY,$MaxZ)
    expected_y_offset=$offsetY
    expected_air_cells=$volume-$expected.Count
    all_target_and_air_cells_match=$passed
    server_response=$verdict
    manifest_sha256=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash
} | ConvertTo-Json | Set-Content (Join-Path $taskRoot "run/bench-out/$RunId-full-region-audit.json")
Write-Output "Full server-region audit: match=$passed, volume=$volume, targets=$($expected.Count), air=$($volume-$expected.Count)"
if(-not $passed){exit 1}
