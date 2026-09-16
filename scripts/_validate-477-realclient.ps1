# Real-client validation for protocol 477 (1.14).
#
# Runs the official Minecraft 1.13.2 and 1.14 clients and servers against
# Conduit for three paths:
#
#   1.14   client -> Conduit -> 1.14   server   (DIRECT, isolates the codec)
#   1.13.2 client -> Conduit -> 1.14   server   (TRANSLATED 404 -> 477)
#   1.14   client -> Conduit -> 1.13.2 server   (TRANSLATED 477 -> 404)
#
# Gameplay is driven from each backend's own console, because synthetic OS input
# does not reach a detached Minecraft window in this environment. The client is
# real and parses every translated packet, so a wire-format fault shows up as a
# decoder exception or a dropped session in its log; what the console cannot do
# is press a mouse button, so the serverbound half stays the scripted probes'
# job. See RESULTS-477-REALCLIENT.md for exactly which claims each run supports.
$ErrorActionPreference = "Continue"
$work = "C:\Users\Kyle\Documents\Codex\2026-09-13\files-pasted-by-the-user-conduit\work"
$repo = Join-Path $work "conduit-independent"
$rcv = Join-Path $work "real-client-validation"
$logDir = Join-Path $rcv "logs-477"
$java8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
$java21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$commit = (git -C $repo rev-parse --short HEAD).Trim()
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Write-Host "=== commit=$commit stamp=$stamp ==="

function Kill-Stale {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
    if ($_.CommandLine -match "AllTests|ItemGameplayProbe|gg\.tame\.conduit\.launcher|net\.minecraft|server\.jar") {
      Write-Host "  kill PID $($_.ProcessId)"
      Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
  }
  Start-Sleep 3
}

function Wait-PortFree([int]$Port) {
  for ($i = 0; $i -lt 40; $i++) {
    if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { return }
    Start-Sleep 1
  }
  throw "port $Port still busy"
}

function Wait-PortListen([int]$Port, [int]$Seconds = 120) {
  for ($i = 0; $i -lt $Seconds; $i++) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { return }
    Start-Sleep 1
  }
  throw "port $Port never listened"
}

# A server started this way keeps an open stdin, so gameplay can be driven from
# its own console exactly as an operator would.
function Start-Backend([string]$Dir, [int]$Port, [string]$Label) {
  Wait-PortFree $Port
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $java8
  $psi.Arguments = "-Xms512M -Xmx1G -jar server.jar nogui"
  $psi.WorkingDirectory = $Dir
  $psi.UseShellExecute = $false
  $psi.RedirectStandardInput = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $proc = [System.Diagnostics.Process]::Start($psi)
  $log = Join-Path $logDir "$Label-$stamp.log"
  Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
    if ($EventArgs.Data) { Add-Content -Path $Event.MessageData -Value $EventArgs.Data }
  } -MessageData $log | Out-Null
  Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action {
    if ($EventArgs.Data) { Add-Content -Path $Event.MessageData -Value $EventArgs.Data }
  } -MessageData $log | Out-Null
  $proc.BeginOutputReadLine()
  $proc.BeginErrorReadLine()
  Wait-PortListen $Port 180
  Write-Host "  backend $Label listening on $Port (PID $($proc.Id))"
  return @{ Proc = $proc; Log = $log }
}

function Send-Console($backend, [string]$Command) {
  $backend.Proc.StandardInput.WriteLine($Command)
  $backend.Proc.StandardInput.Flush()
  Start-Sleep -Milliseconds 700
}

function Start-Conduit([string]$Config, [string]$Label) {
  $portLine = Get-Content (Join-Path $repo $Config) | Where-Object { $_ -match '^\s*port\s*=' } | Select-Object -First 1
  $port = [int](($portLine -split '=')[1].Trim().Trim('"'))
  Wait-PortFree $port
  $log = Join-Path $logDir "conduit-$Label-$stamp.log"
  $err = Join-Path $logDir "conduit-$Label-$stamp.err.log"
  $proc = Start-Process -FilePath $java21 `
    -ArgumentList @("-Dconduit.trace=true", "-cp", (Join-Path $repo "out"), "gg.tame.conduit.launcher.Main", $Config) `
    -WorkingDirectory $repo -PassThru -RedirectStandardOutput $log -RedirectStandardError $err
  Wait-PortListen $port 60
  for ($i = 0; $i -lt 60; $i++) {
    $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
    if ($text -and ($text -match "Backend lobby is healthy")) { break }
    Start-Sleep 1
  }
  Write-Host "  conduit $Label on $port (PID $($proc.Id))"
  return @{ Proc = $proc; Log = $log; Err = $err; Port = $port }
}

function Start-Client([string]$Version, [string]$AssetIndex, [string]$User, [int]$Port, [string]$Label) {
  $client = Join-Path $rcv "client-$Version"
  $cp = (Get-Content (Join-Path $client "classpath.txt") -Raw).Trim()
  $gameDir = Join-Path $client "game"
  New-Item -ItemType Directory -Force -Path $gameDir | Out-Null
  $log = Join-Path $logDir "client-$Label-$stamp.log"
  $err = Join-Path $logDir "client-$Label-$stamp.err.log"
  $args = @(
    "-Djava.library.path=$(Join-Path $client 'natives')",
    "-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true",
    "-Xms512M", "-Xmx1G",
    "-cp", $cp,
    "net.minecraft.client.main.Main",
    "--username", $User,
    "--version", $Version,
    "--gameDir", $gameDir,
    "--assetsDir", (Join-Path $client "assets"),
    "--assetIndex", $AssetIndex,
    "--uuid", "00000000-0000-0000-0000-0000000004$($Port % 100)",
    "--accessToken", "0",
    "--userType", "legacy",
    "--versionType", "release",
    "--server", "127.0.0.1",
    "--port", "$Port"
  )
  $proc = Start-Process -FilePath $java8 -ArgumentList $args -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError $err
  Write-Host "  client $Version PID $($proc.Id) -> 127.0.0.1:$Port"
  return @{ Proc = $proc; Log = $log; Err = $err }
}

function Stop-Safe($p) {
  if ($null -eq $p) { return }
  try { if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } } catch {}
}

# The gameplay burst. Every command here exercises a specific part of the
# semantic layer: item identity and NBT, block states with properties, entity
# types whose registry index moved in 1.14, chat, and a container block.
function Drive-Gameplay($backend, [string]$User, [bool]$Modern) {
  $sign = if ($Modern) { "minecraft:oak_sign" } else { "minecraft:sign" }
  Send-Console $backend "gamemode creative $User"
  Send-Console $backend "time set day"
  Send-Console $backend "weather clear"
  Send-Console $backend "difficulty peaceful"
  Send-Console $backend "give $User minecraft:diamond_sword{display:{Name:'{`"text`":`"Widowmaker`"}'},Enchantments:[{id:`"minecraft:sharpness`",lvl:5s}],Damage:42} 1"
  Send-Console $backend "give $User minecraft:stone 64"
  Send-Console $backend "give $User $sign 3"
  Send-Console $backend "give $User minecraft:diamond_helmet 1"
  Send-Console $backend "give $User minecraft:oak_stairs 32"
  Send-Console $backend "give $User minecraft:chest 4"
  # Block states with real properties, well above the id divergence point.
  Send-Console $backend "fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:oak_stairs[facing=east,half=top,shape=straight]"
  Send-Console $backend "setblock ~2 ~ ~2 minecraft:chest"
  Send-Console $backend "setblock ~-2 ~ ~2 minecraft:oak_trapdoor[facing=north,half=top,open=true]"
  Send-Console $backend "setblock ~-2 ~ ~-2 minecraft:iron_door[facing=south,half=lower,open=true]"
  Send-Console $backend "setblock ~3 ~ ~0 minecraft:repeater[delay=3,facing=west]"
  Send-Console $backend "setblock ~0 ~ ~3 minecraft:stone_slab[type=top]"
  Send-Console $backend "setblock ~0 ~ ~-3 minecraft:glass"
  Send-Console $backend "setblock ~4 ~ ~0 minecraft:furnace[facing=north]"
  Send-Console $backend "setblock ~-4 ~ ~0 minecraft:rail[shape=north_south]"
  # Entity types whose registry index shifted in 1.14.
  Send-Console $backend "summon minecraft:cow ~2 ~ ~-2"
  Send-Console $backend "summon minecraft:creeper ~-3 ~ ~1"
  Send-Console $backend "summon minecraft:zombie ~3 ~ ~3"
  Send-Console $backend "summon minecraft:armor_stand ~1 ~ ~1"
  Send-Console $backend "summon minecraft:item ~1 ~ ~-1 {Item:{id:`"minecraft:diamond`",Count:1b}}"
  Send-Console $backend "effect give $User minecraft:speed 60 1"
  Send-Console $backend "say CONDUIT_477_GAMEPLAY_BURST"
  Start-Sleep 5
}

function Summarise([string]$Label, $client, $conduit, $backend) {
  $clientText = (Get-Content $client.Log -Raw -ErrorAction SilentlyContinue) + "`n" +
                (Get-Content $client.Err -Raw -ErrorAction SilentlyContinue)
  $conduitText = (Get-Content $conduit.Log -Raw -ErrorAction SilentlyContinue) + "`n" +
                 (Get-Content $conduit.Err -Raw -ErrorAction SilentlyContinue)
  $backendText = Get-Content $backend.Log -Raw -ErrorAction SilentlyContinue

  $joined = $backendText -match "joined the game"
  $burst = $backendText -match "CONDUIT_477_GAMEPLAY_BURST"
  $decoderFault = $clientText -match "DecoderException|Loading NBT|Internal Exception|ReadTimeout|Badly compressed"
  $translationFault = $conduitText -match "TranslationException|no semantic mapping|translation failed"
  $drops = ([regex]::Matches($conduitText, "DROP ")).Count
  $translations = ([regex]::Matches($conduitText, "TRANSLATE ")).Count
  $disconnect = $backendText -match "lost connection"

  Write-Host "--- $Label ---"
  Write-Host "  joined=$joined burst=$burst clientDecoderFault=$decoderFault translationFault=$translationFault"
  Write-Host "  translations=$translations drops=$drops lostConnection=$disconnect"
  return [ordered]@{
    label = $Label; joined = $joined; burst = $burst
    clientDecoderFault = $decoderFault; translationFault = $translationFault
    translations = $translations; drops = $drops; lostConnection = $disconnect
    clientLog = $client.Log; conduitLog = $conduit.Log; backendLog = $backend.Log
  }
}

Write-Host "=== kill stale ==="
Kill-Stale

Write-Host "=== rebuild + unit tests ==="
& (Join-Path $repo "scripts\test.ps1")
if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }

Write-Host "=== start backends ==="
$mc1132 = Join-Path $work "mc1132"
$mc114 = Join-Path $work "mc114"
$b404 = Start-Backend $mc1132 25614 "mc1132"
$b477 = Start-Backend $mc114 25677 "mc114"
Start-Sleep 5

$results = @()

# ---------------------------------------------------------------- DIRECT 477
Write-Host "=== DIRECT: 1.14 client -> Conduit -> 1.14 server ==="
$c = Start-Conduit "config\conduit-477-native.toml" "477-native"
$cl = Start-Client "1.14" "1.14" "Direct114" $c.Port "477-direct"
Start-Sleep 75
Drive-Gameplay $b477 "Direct114" $true
Start-Sleep 5
$results += Summarise "DIRECT_477_477" $cl $c $b477
Stop-Safe $cl.Proc; Stop-Safe $c.Proc
Start-Sleep 5

# ------------------------------------------------------------ TRANSLATED 404->477
Write-Host "=== TRANSLATED: 1.13.2 client -> Conduit -> 1.14 server ==="
$c = Start-Conduit "config\conduit-404-to-477.toml" "404-to-477"
$cl = Start-Client "1.13.2" "1.13.1" "Trans404" $c.Port "404-to-477"
Start-Sleep 75
Drive-Gameplay $b477 "Trans404" $true
Start-Sleep 5
$results += Summarise "TRANSLATED_404_477" $cl $c $b477
Stop-Safe $cl.Proc; Stop-Safe $c.Proc
Start-Sleep 5

# ------------------------------------------------------------ TRANSLATED 477->404
Write-Host "=== TRANSLATED: 1.14 client -> Conduit -> 1.13.2 server ==="
$c = Start-Conduit "config\conduit-477-to-404.toml" "477-to-404"
$cl = Start-Client "1.14" "1.14" "Trans477" $c.Port "477-to-404"
Start-Sleep 75
Drive-Gameplay $b404 "Trans477" $false
Start-Sleep 5
$results += Summarise "TRANSLATED_477_404" $cl $c $b404
Stop-Safe $cl.Proc; Stop-Safe $c.Proc

Write-Host "=== cleanup ==="
try { Send-Console $b404 "stop" } catch {}
try { Send-Console $b477 "stop" } catch {}
Start-Sleep 8
Stop-Safe $b404.Proc; Stop-Safe $b477.Proc
Kill-Stale

$summary = Join-Path $logDir "SUMMARY-477-REALCLIENT-$stamp.json"
@{ commit = $commit; stamp = $stamp; results = $results } | ConvertTo-Json -Depth 5 | Set-Content $summary
Write-Host "=== SUMMARY $summary ==="
Get-Content $summary
