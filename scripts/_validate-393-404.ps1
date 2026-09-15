# Provision mc1132, run DIRECT 404 and translated 393<->404 probes against real jars.
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
    if ($_.CommandLine -match "AllTests|ItemGameplayProbe|gg\.tame\.conduit\.launcher|minecraft-server-1\.13|server\.jar|net\.minecraft\.server") {
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

function Wait-PortListen([int]$Port, [int]$Seconds = 60) {
  for ($i = 0; $i -lt $Seconds; $i++) {
    $busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($busy) { return }
    Start-Sleep 1
  }
  throw "port $Port never listened"
}

function Start-ConduitProxy([string]$Config, [string]$Label, [string]$OutDir, [string]$Java, [string]$RepoRoot, [string]$Logs, [string]$Stamp) {
  $portLine = Get-Content (Join-Path $RepoRoot $Config) | Where-Object { $_ -match '^\s*port\s*=' } | Select-Object -First 1
  $port = [int](($portLine -split '=')[1].Trim().Trim('"'))
  Wait-PortFree $port
  $clog = Join-Path $Logs "conduit-$Label-$Stamp.log"
  $cerr = Join-Path $Logs "conduit-$Label-$Stamp.err.log"
  $proc = Start-Process -FilePath $Java -ArgumentList @("-cp", $OutDir, "gg.tame.conduit.launcher.Main", $Config) `
    -WorkingDirectory $RepoRoot -PassThru -RedirectStandardOutput $clog -RedirectStandardError $cerr
  Start-Sleep 12
  Wait-PortListen $port 30
  return @{ Proc = $proc; Log = $clog; Err = $cerr; Port = $port }
}

function Start-GameplayProbe([string]$Class, [string]$Addr, [int]$Port, [string]$Name, [int]$Idle, [string]$Label, [string]$OutDir, [string]$Java, [string]$RepoRoot, [string]$Logs, [string]$Stamp) {
  $plog = Join-Path $Logs "probe-$Label-$Stamp.log"
  $perr = Join-Path $Logs "probe-$Label-$Stamp.err.log"
  $p = Start-Process -FilePath $Java -ArgumentList @("-cp", $OutDir, $Class, $Addr, "$Port", $Name, "$Idle") `
    -WorkingDirectory $RepoRoot -PassThru -RedirectStandardOutput $plog -RedirectStandardError $perr -NoNewWindow
  Wait-Process -Id $p.Id -Timeout 120 -ErrorAction SilentlyContinue
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

Write-Host "=== kill stale ==="
Kill-ConduitRelated

$out = Join-Path $repo "out"
if (-not (Test-Path (Join-Path $out "gg\tame\conduit\tests\ItemGameplayProbe404.class"))) {
  Write-Host "=== unit rebuild ==="
  & (Join-Path $repo "scripts\test.ps1")
  if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }
} else {
  Write-Host "=== reuse compiled out/ ==="
}

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

Write-Host "=== start 1.13.2 server :25614 ==="
Wait-PortFree 25614
$srv404 = Start-Process -FilePath $java8 -ArgumentList @("-Xms512M","-Xmx1G","-jar","server.jar","nogui") `
  -WorkingDirectory $mc1132 -PassThru -RedirectStandardOutput (Join-Path $logDir "mc1132-$stamp.log") `
  -RedirectStandardError (Join-Path $logDir "mc1132-$stamp.err")
Wait-PortListen 25614 90
Write-Host "1.13.2 server PID=$($srv404.Id)"

$mc113 = Join-Path $work "mc113"
$props393 = Join-Path $mc113 "server.properties"
if (Test-Path $props393) {
  (Get-Content $props393) -replace 'network-compression-threshold=\d+','network-compression-threshold=-1' | Set-Content $props393
}
Write-Host "=== (re)start 1.13 server :25613 ==="
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
  if ($_.CommandLine -match 'mc113') {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }
}
Start-Sleep 2
Wait-PortFree 25613
$srv393 = Start-Process -FilePath $java8 -ArgumentList @("-Xms512M","-Xmx1G","-jar","server.jar","nogui") `
  -WorkingDirectory $mc113 -PassThru -RedirectStandardOutput (Join-Path $logDir "mc113-$stamp.log") `
  -RedirectStandardError (Join-Path $logDir "mc113-$stamp.err")
Wait-PortListen 25613 90
Write-Host "1.13 server PID=$($srv393.Id)"

$results = @()

Write-Host "=== DIRECT 404 native ==="
$c = Start-ConduitProxy "config\conduit-404-native.toml" "404-native" $out $java21 $repo $logDir $stamp
$r = Start-GameplayProbe "gg.tame.conduit.tests.ItemGameplayProbe404" "127.0.0.1" 25564 "Direct404" 12000 "404-direct" $out $java21 $repo $logDir $stamp
$results += "DIRECT_404 ok=$($r.Ok) exit=$($r.Exit) log=$($r.Log)"
Stop-ProcSafe $c.Proc
Start-Sleep 2

Write-Host "=== TRANSLATED 393->404 ==="
$c = Start-ConduitProxy "config\conduit-393-to-404.toml" "393-to-404" $out $java21 $repo $logDir $stamp
$r = Start-GameplayProbe "gg.tame.conduit.tests.ItemGameplayProbe393" "127.0.0.1" 25563 "X393to404" 15000 "393-to-404" $out $java21 $repo $logDir $stamp
$results += "X_393_404 ok=$($r.Ok) exit=$($r.Exit) log=$($r.Log)"
$r2 = Start-GameplayProbe "gg.tame.conduit.tests.ItemGameplayProbe393" "127.0.0.1" 25563 "X393to404b" 60000 "393-to-404-sustained" $out $java21 $repo $logDir $stamp
$results += "X_393_404_sustained ok=$($r2.Ok) exit=$($r2.Exit) log=$($r2.Log)"
Stop-ProcSafe $c.Proc
Start-Sleep 2

Write-Host "=== TRANSLATED 404->393 ==="
$c = Start-ConduitProxy "config\conduit-404-to-393.toml" "404-to-393" $out $java21 $repo $logDir $stamp
$r = Start-GameplayProbe "gg.tame.conduit.tests.ItemGameplayProbe404" "127.0.0.1" 25562 "X404to393" 15000 "404-to-393" $out $java21 $repo $logDir $stamp
$results += "X_404_393 ok=$($r.Ok) exit=$($r.Exit) log=$($r.Log)"
$r2 = Start-GameplayProbe "gg.tame.conduit.tests.ItemGameplayProbe404" "127.0.0.1" 25562 "X404to393b" 60000 "404-to-393-sustained" $out $java21 $repo $logDir $stamp
$results += "X_404_393_sustained ok=$($r2.Ok) exit=$($r2.Exit) log=$($r2.Log)"
Stop-ProcSafe $c.Proc

Write-Host "=== cleanup servers ==="
Stop-ProcSafe $srv404
Stop-ProcSafe $srv393

$summary = Join-Path $logDir "RESULTS-393-404-$stamp.txt"
@("commit=$commit"; "stamp=$stamp"; $results) | Set-Content $summary
Write-Host "SUMMARY $summary"
$results | ForEach-Object { Write-Host $_ }
