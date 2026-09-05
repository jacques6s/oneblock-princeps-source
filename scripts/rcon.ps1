<#
.SYNOPSIS
    Sends console commands to the bench server over RCON and prints the reply.

.DESCRIPTION
    Server-side control of the bench world, independent of the client under test. That independence is the point:
    the bot's own view of the world is exactly what a builder bug corrupts, so setup and verification must be able
    to come from the server instead.

    Typical uses: wipe the build area before a run, teleport the bot, freeze time and weather so runs are
    comparable, or read back a block the builder claims to have placed.

.EXAMPLE
    scripts\rcon.ps1 "time set day"

.EXAMPLE
    scripts\rcon.ps1 "fill 5 -60 -10 40 -40 25 air", "fill 5 -61 -10 40 -61 25 stone"

.EXAMPLE
    # Independent read-back: does the world really have that block?
    scripts\rcon.ps1 "execute if block 11 -60 -2 minecraft:stone run say YES"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)][string[]] $Command,
    [string] $ServerHost = '127.0.0.1',
    [int]    $Port = 25576,
    # Defaults to the password in run-server\server.properties, so callers never have to repeat it.
    [string] $Password
)

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$props = Join-Path $root 'run-server\server.properties'

if (-not $Password) {
    if (-not (Test-Path $props)) { throw "no server.properties at $props and no -Password given" }
    $line = Select-String -Path $props -Pattern '^rcon\.password=(.*)$' | Select-Object -First 1
    if (-not $line) { throw "rcon.password is not set in $props" }
    $Password = $line.Matches[0].Groups[1].Value
}
if (-not $Password) { throw "rcon.password is empty -- RCON refuses to start without one" }

$RCON_LOGIN    = 3
$RCON_COMMAND  = 2
$AUTH_FAILED   = -1

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($ServerHost, $Port)
$stream = $client.GetStream()
$stream.ReadTimeout = 5000

function Send-Packet([int]$id, [int]$type, [string]$body) {
    $bodyBytes = [System.Text.Encoding]::ASCII.GetBytes($body)
    $len = 4 + 4 + $bodyBytes.Length + 2
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([int]$len)
    $bw.Write([int]$id)
    $bw.Write([int]$type)
    $bw.Write($bodyBytes)
    $bw.Write([byte]0)
    $bw.Write([byte]0)
    $bw.Flush()
    $bytes = $ms.ToArray()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()
    $bw.Dispose(); $ms.Dispose()
}

function Read-Exactly([int]$count) {
    $buf = New-Object byte[] $count
    $read = 0
    while ($read -lt $count) {
        $n = $stream.Read($buf, $read, $count - $read)
        if ($n -le 0) { throw "connection closed while reading" }
        $read += $n
    }
    return $buf
}

function Receive-Packet {
    $lenBytes = Read-Exactly 4
    $len = [BitConverter]::ToInt32($lenBytes, 0)
    $payload = Read-Exactly $len
    $id   = [BitConverter]::ToInt32($payload, 0)
    $type = [BitConverter]::ToInt32($payload, 4)
    $body = [System.Text.Encoding]::UTF8.GetString($payload, 8, [Math]::Max(0, $len - 10))
    return [pscustomobject]@{ Id = $id; Type = $type; Body = $body }
}

try {
    Send-Packet 1 $RCON_LOGIN $Password
    $auth = Receive-Packet
    if ($auth.Id -eq $AUTH_FAILED) { throw "RCON auth failed -- wrong password" }

    $exit = 0
    $reqId = 10
    foreach ($cmd in $Command) {
        if (-not $cmd) { continue }
        Send-Packet $reqId $RCON_COMMAND $cmd
        $reply = Receive-Packet
        $text = $reply.Body.Trim()
        if ($text) { Write-Output "$cmd -> $text" } else { Write-Output "$cmd -> (no output)" }
        # The server answers an unknown command with a parse error rather than an error code, so surface it as one.
        if ($text -match 'Unknown or incomplete command|Incorrect argument|Expected ') { $exit = 1 }
        $reqId++
    }
    exit $exit
} finally {
    $stream.Close()
    $client.Close()
}
