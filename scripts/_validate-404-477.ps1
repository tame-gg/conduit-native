# Provision mc114 + reuse mc1132, run DIRECT 477 and translated 404<->477 probes.
$ErrorActionPreference = "Continue"
$work = "C:\Users\Kyle\Documents\Codex\2026-09-13\files-pasted-by-the-user-conduit\work"
$repo = Join-Path $work "conduit-independent"
$logDir = Join-Path $work "real-client-validation\logs"
$java8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
$java21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$commit = (git -C $repo rev-parse --short HEAD).Trim()
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Kill-ConduitRelated {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
    if ($_.CommandLine -match "AllTests|ItemGameplayProbe|gg\.tame\.conduit\.launcher|minecraft-server-1\.(13|14)|server\.jar|net\.minecraft\.server") {
      Write-Host "kill $($_.ProcessId)"
      Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
  }
  Start-Sleep 2
}

function Wait-PortFree([int]$Port) {
  for ($i = 0; $i -lt 30; $i++) {
    $busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $busy) { return }
    Start-Sleep 1
  }
  throw "port $Port still busy"
}

function Wait-PortListen([int]$Port, [int]$Seconds = 90) {
  for ($i = 0; $i -lt $Seconds; $i++) {
    $busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($busy) { return }
    Start-Sleep 1
  }
  throw "port $Port never listened"
}

function Start-ConduitProxy([string]$Config, [string]$Label) {
  $portLine = Get-Content (Join-Path $repo $Config) | Where-Object { $_ -match '^\s*port\s*=' } | Select-Object -First 1
  $port = [int](($portLine -split '=')[1].Trim().Trim('"'))
  Wait-PortFree $port
  $clog = Join-Path $logDir "conduit-$Label-$stamp.log"
  $cerr = Join-Path $logDir "conduit-$Label-$stamp.err.log"
  $proc = Start-Process -FilePath $java21 -ArgumentList @("-cp", (Join-Path $repo "out"), "gg.tame.conduit.launcher.Main", $Config) `
    -WorkingDirectory $repo -PassThru -RedirectStandardOutput $clog -RedirectStandardError $cerr
  Start-Sleep 12
  Wait-PortListen $port 30
  # Translation paths need a backend protocol advertisement before handshake rewrite.
  for ($i = 0; $i -lt 60; $i++) {
    $text = Get-Content $clog -Raw -ErrorAction SilentlyContinue
    if ($text -and ($text -match "Backend lobby is healthy")) { break }
    Start-Sleep 1
  }
  return @{ Proc = $proc; Log = $clog; Err = $cerr; Port = $port }
}

function Start-GameplayProbe([string]$Class, [string]$Addr, [int]$Port, [string]$Name, [int]$Idle, [string]$Label) {
  $plog = Join-Path $logDir "probe-$Label-$stamp.log"
  $perr = Join-Path $logDir "probe-$Label-$stamp.err.log"
  $p = Start-Process -FilePath $java21 -ArgumentList @("-cp", (Join-Path $repo "out"), $Class, $Addr, "$Port", $Name, "$Idle") `
    -WorkingDirectory $repo -PassThru -RedirectStandardOutput $plog -RedirectStandardError $perr -NoNewWindow
  Wait-Process -Id $p.Id -Timeout 180 -ErrorAction SilentlyContinue
  if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep 1
  $text = Get-Content $plog -Raw -ErrorAction SilentlyContinue
  $ok = $false
  if ($text -and ($text -match "RESULT=ok")) { $ok = $true }
  Write-Host "PROBE $Label exit=$($p.ExitCode) ok=$ok"
  return @{ Ok = $ok; Log = $plog; Text = $text; Exit = $p.ExitCode }
}

function Stop-ProcSafe($p) {
  if ($null -eq $p) { return }
  if (-not $p.HasExited) {
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep 1
  }
}

Write-Host "=== commit=$commit kill stale ==="
Kill-ConduitRelated

Write-Host "=== rebuild ==="
& (Join-Path $repo "scripts\test.ps1")
if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }

$mc114 = Join-Path $work "mc114"
New-Item -ItemType Directory -Force -Path $mc114 | Out-Null
Copy-Item (Join-Path $repo "artifacts\bin\minecraft-server-1.14.jar") (Join-Path $mc114 "server.jar") -Force
Set-Content (Join-Path $mc114 "eula.txt") "eula=true"
@(
  "online-mode=false"
  "server-port=25677"
  "gamemode=1"
  "force-gamemode=true"
  "difficulty=peaceful"
  "spawn-protection=0"
  "max-players=20"
  "level-name=world"
  "motd=Conduit 1.14 validation"
  "network-compression-threshold=-1"
) | Set-Content (Join-Path $mc114 "server.properties")

$mc1132 = Join-Path $work "mc1132"
New-Item -ItemType Directory -Force -Path $mc1132 | Out-Null
Copy-Item (Join-Path $repo "artifacts\bin\minecraft-server-1.13.2.jar") (Join-Path $mc1132 "server.jar") -Force
Set-Content (Join-Path $mc1132 "eula.txt") "eula=true"
@(
  "online-mode=false"
  "server-port=25614"
  "gamemode=1"
  "force-gamemode=true"
  "difficulty=peaceful"
  "spawn-protection=0"
  "max-players=20"
  "level-name=world"
  "motd=Conduit 1.13.2 validation"
  "network-compression-threshold=-1"
) | Set-Content (Join-Path $mc1132 "server.properties")

Write-Host "=== start 1.14 server :25677 ==="
Wait-PortFree 25677
$srv477 = Start-Process -FilePath $java8 -ArgumentList @("-Xms512M","-Xmx1G","-jar","server.jar","nogui") `
  -WorkingDirectory $mc114 -PassThru -RedirectStandardOutput (Join-Path $logDir "mc114-$stamp.log") `
  -RedirectStandardError (Join-Path $logDir "mc114-$stamp.err")
Wait-PortListen 25677 120
Write-Host "1.14 server PID=$($srv477.Id)"

Write-Host "=== start 1.13.2 server :25614 ==="
Wait-PortFree 25614
$srv404 = Start-Process -FilePath $java8 -ArgumentList @("-Xms512M","-Xmx1G","-jar","server.jar","nogui") `
  -WorkingDirectory $mc1132 -PassThru -RedirectStandardOutput (Join-Path $logDir "mc1132-477val-$stamp.log") `
  -RedirectStandardError (Join-Path $logDir "mc1132-477val-$stamp.err")
Wait-PortListen 25614 120
Write-Host "1.13.2 server PID=$($srv404.Id)"

$results = @{}

Write-Host "=== DIRECT 477 <-> 477 ==="
$c = Start-ConduitProxy "config\conduit-477-native.toml" "477-native"
$results["DIRECT_477"] = Start-GameplayProbe "gg.tame.conduit.tests.ItemGameplayProbe477" "127.0.0.1" $c.Port "Prober114D" 10000 "477-native"
Stop-ProcSafe $c.Proc

Write-Host "=== TRANSLATED 404 -> 477 ==="
$c = Start-ConduitProxy "config\conduit-404-to-477.toml" "404-to-477"
$results["404_TO_477"] = Start-GameplayProbe "gg.tame.conduit.tests.ItemGameplayProbe404" "127.0.0.1" $c.Port "Prober132F" 10000 "404-to-477"
Stop-ProcSafe $c.Proc

Write-Host "=== TRANSLATED 477 -> 404 ==="
$c = Start-ConduitProxy "config\conduit-477-to-404.toml" "477-to-404"
$results["477_TO_404"] = Start-GameplayProbe "gg.tame.conduit.tests.ItemGameplayProbe477" "127.0.0.1" $c.Port "Prober114R" 10000 "477-to-404"
Stop-ProcSafe $c.Proc

Write-Host "=== cleanup servers ==="
Stop-ProcSafe $srv477
Stop-ProcSafe $srv404
Kill-ConduitRelated

$summary = Join-Path $logDir "SUMMARY-404-477-$stamp.txt"
@(
  "commit=$commit"
  "stamp=$stamp"
  "DIRECT_477=$($results['DIRECT_477'].Ok) exit=$($results['DIRECT_477'].Exit) log=$($results['DIRECT_477'].Log)"
  "404_TO_477=$($results['404_TO_477'].Ok) exit=$($results['404_TO_477'].Exit) log=$($results['404_TO_477'].Log)"
  "477_TO_404=$($results['477_TO_404'].Ok) exit=$($results['477_TO_404'].Exit) log=$($results['477_TO_404'].Log)"
) | Set-Content $summary
Write-Host "=== SUMMARY ==="
Get-Content $summary
