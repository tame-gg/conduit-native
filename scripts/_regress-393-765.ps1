# Focused 393<->765 regression after adding 404 support.
$ErrorActionPreference = "Continue"
$work = "C:\Users\Kyle\Documents\Codex\2026-09-13\files-pasted-by-the-user-conduit\work"
$repo = Join-Path $work "conduit-independent"
$logDir = Join-Path $work "real-client-validation\logs"
$java8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
$java21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$commit = (git -C $repo rev-parse --short HEAD).Trim()
$out = Join-Path $repo "out"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

& (Join-Path $repo "scripts\_kill-java.ps1")

function Wait-PortListen([int]$Port, [int]$Seconds = 90) {
  for ($i = 0; $i -lt $Seconds; $i++) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { return }
    Start-Sleep 1
  }
  throw "port $Port never listened"
}
function Wait-PortFree([int]$Port) {
  for ($i = 0; $i -lt 30; $i++) {
    if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { return }
    Start-Sleep 1
  }
}

# Ensure 1.13 and 1.20.4 backends
$mc113 = Join-Path $work "mc113"
$mc1204 = Join-Path $work "mc1204"
Write-Host "using 1204 dir $mc1204"

(Get-Content (Join-Path $mc113 "server.properties") -ErrorAction SilentlyContinue) -replace 'network-compression-threshold=\d+','network-compression-threshold=-1' | Set-Content (Join-Path $mc113 "server.properties")

Wait-PortFree 25613
$s113 = Start-Process -FilePath $java8 -ArgumentList @("-Xms512M","-Xmx1G","-jar","server.jar","nogui") `
  -WorkingDirectory $mc113 -PassThru -RedirectStandardOutput (Join-Path $logDir "reg-mc113-$stamp.log") `
  -RedirectStandardError (Join-Path $logDir "reg-mc113-$stamp.err")
Wait-PortListen 25613

# Find 1.20.4 port from config
$cfg393765 = Join-Path $repo "config\conduit-393-to-765.toml"
$cfg765393 = Join-Path $repo "config\conduit-765-to-393.toml"
Write-Host (Get-Content $cfg393765 | Select-String 'port')

# Start 1.20.4 if not listening on common port from config
$backend765Port = 25604
if (Test-Path (Join-Path $mc1204 "server.properties")) {
  $line = Get-Content (Join-Path $mc1204 "server.properties") | Where-Object { $_ -match '^server-port=' } | Select-Object -First 1
  if ($line) { $backend765Port = [int]($line -split '=')[1] }
}
Write-Host "765 backend port=$backend765Port"
if (-not (Get-NetTCPConnection -LocalPort $backend765Port -State Listen -ErrorAction SilentlyContinue)) {
  $java765 = $java21
  $s765 = Start-Process -FilePath $java765 -ArgumentList @("-Xms512M","-Xmx1G","-jar","server.jar","nogui") `
    -WorkingDirectory $mc1204 -PassThru -RedirectStandardOutput (Join-Path $logDir "reg-mc765-$stamp.log") `
    -RedirectStandardError (Join-Path $logDir "reg-mc765-$stamp.err")
  Wait-PortListen $backend765Port 120
} else {
  $s765 = $null
  Write-Host "765 server already up"
}

$results = @()

function RunOne([string]$Config, [string]$ListenPort, [string]$ProbeClass, [string]$Label, [string]$Name) {
  Wait-PortFree ([int]$ListenPort)
  $c = Start-Process -FilePath $java21 -ArgumentList @("-cp",$out,"gg.tame.conduit.launcher.Main",$Config) `
    -WorkingDirectory $repo -PassThru -RedirectStandardOutput (Join-Path $logDir "reg-conduit-$Label-$stamp.log") `
    -RedirectStandardError (Join-Path $logDir "reg-conduit-$Label-$stamp.err")
  Start-Sleep 14
  Wait-PortListen ([int]$ListenPort) 40
  $plog = Join-Path $logDir "reg-probe-$Label-$stamp.log"
  $p = Start-Process -FilePath $java21 -ArgumentList @("-cp",$out,$ProbeClass,"127.0.0.1",$ListenPort,$Name,"20000") `
    -WorkingDirectory $repo -PassThru -RedirectStandardOutput $plog -RedirectStandardError "$plog.err" -NoNewWindow
  Wait-Process -Id $p.Id -Timeout 120 -ErrorAction SilentlyContinue
  if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force }
  $text = Get-Content $plog -Raw -ErrorAction SilentlyContinue
  $ok = $text -match "RESULT=ok"
  $fails = Select-String -Path (Join-Path $logDir "reg-conduit-$Label-$stamp.err") -Pattern "Translation failed" -SimpleMatch -ErrorAction SilentlyContinue
  Write-Host "REG $Label ok=$ok translationFails=$($fails.Count)"
  $script:results += "REG_$Label ok=$ok fails=$($fails.Count) log=$plog"
  Stop-Process -Id $c.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep 2
}

RunOne "config\conduit-393-to-765.toml" "25561" "gg.tame.conduit.tests.ItemGameplayProbe393" "393-765" "Reg393"
# 765 probe uses modern client protocol
RunOne "config\conduit-765-to-393.toml" "25562" "gg.tame.conduit.tests.ItemGameplayProbe" "765-393" "Reg765"

if ($s113) { Stop-Process -Id $s113.Id -Force -ErrorAction SilentlyContinue }
if ($s765) { Stop-Process -Id $s765.Id -Force -ErrorAction SilentlyContinue }

$summary = Join-Path $logDir "RESULTS-393-765-REGRESSION-$stamp.txt"
@("commit=$commit"; $results) | Set-Content $summary
Write-Host "SUMMARY $summary"
$results | ForEach-Object { Write-Host $_ }
