# Real-server check of the 393 <-> 765 sound and particle tables.
#
# The unit tests check the tables against the dumps they were generated from,
# which cannot catch a table that is wrong in the same way the dump was: an
# earlier sound table passed its tests with 448 of 662 ids wrong. This closes the
# loop from outside. A real vanilla backend is told on its own console to play a
# named sound or particle at the prober, and the prober reports what arrives.
#
# What is asserted is the RAW REGISTRY ID off the wire, against the id in
# Mojang's own registries.json for the receiving version -- the numbers below.
# Asserting the decoded NAME instead proves nothing, because the prober would
# decode it with the same table Conduit encoded it with: a table wrong in both
# directions round-trips and looks right. That was measured, not guessed. With
# the broken sound table in place this script asserted names and passed.
#
# The 1.20.4 numbers are therefore the real check, since 1.20.4's registry comes
# straight from an official report that no Conduit table took part in. The 1.13
# numbers come from the calibrated jar dump, so that direction leans on the
# calibration rather than on a second independent source.
#
#   scripts/_validate-sound-particle.ps1
param([int]$ListenSeconds = 30)

$ErrorActionPreference = "Continue"
$work = "C:\Users\Kyle\Documents\Codex\2026-09-13\files-pasted-by-the-user-conduit\work"
$repo = Join-Path $work "conduit-independent"
$logDir = Join-Path $work "real-client-validation\logs"
$java8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
$java21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe"
$out = Join-Path $repo "out"
# Via is a compile-time and runtime dependency whether or not translation is on:
# ConduitRuntime always calls ConduitViaBootstrap, so `-cp out` alone will not
# start the launcher. _classpath.ps1 assembles lib + Via + out.
$classpath = & (Join-Path $repo "scripts\_classpath.ps1")
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$commit = (git -C $repo rev-parse --short HEAD).Trim()
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

& (Join-Path $repo "scripts\_kill-java.ps1")

function Wait-PortListen([int]$Port, [int]$Seconds = 120) {
  for ($i = 0; $i -lt $Seconds; $i++) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { return $true }
    Start-Sleep 1
  }
  return $false
}
function Wait-PortFree([int]$Port) {
  for ($i = 0; $i -lt 30; $i++) {
    if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { return }
    Start-Sleep 1
  }
}

# A backend started with its console attached, so commands can be typed at it
# while the prober is connected. Start-Process cannot do that: its stdin
# redirection wants a file that is complete before the process starts.
function Start-Backend([string]$Dir, [string]$Java, [string]$Label) {
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $Java
  $psi.Arguments = "-Xms512M -Xmx1G -jar server.jar nogui"
  $psi.WorkingDirectory = $Dir
  $psi.UseShellExecute = $false
  $psi.RedirectStandardInput = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $p = [System.Diagnostics.Process]::Start($psi)
  $log = Join-Path $logDir "sp-$Label-$stamp.log"
  Register-ObjectEvent -InputObject $p -EventName OutputDataReceived -Action {
    if ($EventArgs.Data) { Add-Content -Path $Event.MessageData -Value $EventArgs.Data }
  } -MessageData $log | Out-Null
  Register-ObjectEvent -InputObject $p -EventName ErrorDataReceived -Action {
    if ($EventArgs.Data) { Add-Content -Path $Event.MessageData -Value $EventArgs.Data }
  } -MessageData $log | Out-Null
  $p.BeginOutputReadLine(); $p.BeginErrorReadLine()
  return @{ Process = $p; Log = $log }
}

$results = @()

# Each case: which client protocol joins, which Conduit config bridges it, and
# the console commands the backend is given. The sounds are chosen to exercise
# the three paths: one both registries have, one only the backend has (which has
# to arrive named rather than be dropped), and a name no registry has at all.
function Run-Case([string]$Label, [string]$Config, [int]$ListenPort, [int]$ClientProtocol,
                  [hashtable]$Backend, [string]$Player, [string[]]$Commands, [hashtable]$Expect) {
  Wait-PortFree $ListenPort
  $conduitLog = Join-Path $logDir "sp-conduit-$Label-$stamp.log"
  $c = Start-Process -FilePath $java21 -ArgumentList @("-cp",$classpath,"gg.tame.conduit.launcher.Main",$Config) `
    -WorkingDirectory $repo -PassThru -RedirectStandardOutput $conduitLog `
    -RedirectStandardError "$conduitLog.err"
  if (-not (Wait-PortListen $ListenPort 60)) { Write-Host "SP $Label conduit never listened"; return }

  $probeLog = Join-Path $logDir "sp-probe-$Label-$stamp.log"
  $p = Start-Process -FilePath $java21 -NoNewWindow -PassThru `
    -ArgumentList @("-cp",$classpath,"gg.tame.conduit.tests.SoundParticleProbe","127.0.0.1",$ListenPort,$ClientProtocol,$Player,($ListenSeconds*1000)) `
    -WorkingDirectory $repo -RedirectStandardOutput $probeLog -RedirectStandardError "$probeLog.err"

  # Wait for the prober to be in PLAY before the backend is told to do anything.
  $ready = $false
  for ($i = 0; $i -lt 60; $i++) {
    if ((Test-Path $probeLog) -and (Select-String -Path $probeLog -Pattern "^READY" -Quiet)) { $ready = $true; break }
    if ($p.HasExited) { break }
    Start-Sleep 1
  }
  Write-Host "SP $Label ready=$ready"

  if ($ready) {
    Start-Sleep 2
    foreach ($cmd in $Commands) {
      $Backend.Process.StandardInput.WriteLine($cmd)
      $Backend.Process.StandardInput.Flush()
      Start-Sleep 2
    }
    Start-Sleep 3
  }

  Wait-Process -Id $p.Id -Timeout ($ListenSeconds + 30) -ErrorAction SilentlyContinue
  if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
  Stop-Process -Id $c.Id -Force -ErrorAction SilentlyContinue

  $text = Get-Content $probeLog -Raw -ErrorAction SilentlyContinue
  $heardLine = ($text -split "`n" | Where-Object { $_ -match '^SOUNDS=' }) -join ""
  $seenLine  = ($text -split "`n" | Where-Object { $_ -match '^PARTICLES=' }) -join ""
  Write-Host "SP $Label $heardLine"
  Write-Host "SP $Label $seenLine"
  foreach ($key in $Expect.Keys) {
    $want = $Expect[$key]
    $ok = $text -match [regex]::Escape($want)
    Write-Host ("SP {0} expect {1} = {2} -> {3}" -f $Label, $key, $want, $(if ($ok) { "OK" } else { "MISSING" }))
    $script:results += "SP_$Label`_$key want=$want got=$(if ($ok) { 'OK' } else { 'MISSING' })"
  }
  $script:results += "SP_$Label heard=$heardLine seen=$seenLine log=$probeLog"
}

# --- backends -------------------------------------------------------------
$mc113 = Join-Path $work "mc113"
$mc1204 = Join-Path $work "mc1204"
(Get-Content (Join-Path $mc113 "server.properties")) -replace 'network-compression-threshold=\d+','network-compression-threshold=-1' |
  Set-Content (Join-Path $mc113 "server.properties")

Wait-PortFree 25613
$b113 = Start-Backend $mc113 $java8 "mc113"
if (-not (Wait-PortListen 25613 120)) { throw "1.13 backend never listened" }

$backend765Port = 25604
Wait-PortFree $backend765Port
$b765 = Start-Backend $mc1204 $java21 "mc1204"
if (-not (Wait-PortListen $backend765Port 180)) { throw "1.20.4 backend never listened" }

# The prober is not a real player to the server's permission system; op it so
# playsound and particle run. Vanilla takes the name on the console.
$b113.Process.StandardInput.WriteLine("op Prober13"); $b113.Process.StandardInput.Flush()
$b765.Process.StandardInput.WriteLine("op Prober20"); $b765.Process.StandardInput.Flush()
Start-Sleep 2

# --- 1.13 client, 1.20.4 backend -----------------------------------------
# entity.pig.ambient is in both registries. entity.warden.roar is 1.20.4-only,
# so it can only reach a 1.13 client as Named Sound Effect. conduit:probe.tone
# is in no registry at all and must also arrive named.
Run-Case "393client" "config\conduit-393-to-765.toml" 25561 393 $b765 "Prober13" @(
  "playsound minecraft:entity.pig.ambient master Prober13",
  "playsound minecraft:entity.warden.roar master Prober13",
  "execute at Prober13 run particle minecraft:flame ~ ~1 ~ 0 0 0 0 10 force Prober13",
  "execute at Prober13 run particle minecraft:block minecraft:stone ~ ~1 ~ 0 0 0 0 10 force Prober13",
  "execute at Prober13 run particle minecraft:dust 1 0 0 1 ~ ~1 ~ 0 0 0 0 10 force Prober13"
) @{
  # Toward 1.13 there is no inline form, so even a /playsound has to be resolved
  # to a 1.13 registry id: this direction exercises the table whatever the
  # backend sends. 416 is the id in the calibrated dump of the 1.13 jar.
  "pig"    = "SOUND id=416 name=minecraft:entity.pig.ambient"
  # No 1.13 id exists for the warden, so it must arrive named or not at all.
  "warden" = "SOUND id=none name=minecraft:entity.warden.roar (named)"
  "flame"  = "PARTICLE id=23 name=minecraft:flame"
  "block"  = "PARTICLE id=3 name=minecraft:block"
  "dust"   = "PARTICLE id=11 name=minecraft:dust"
}

# --- 1.20.4 client, 1.13 backend -----------------------------------------
Run-Case "765client" "config\conduit-765-to-393.toml" 25562 765 $b113 "Prober20" @(
  "playsound minecraft:entity.pig.ambient master Prober20",
  "playsound minecraft:block.beacon.activate master Prober20",
  "execute at Prober20 run summon minecraft:lightning_bolt ~ ~ ~",
  "execute at Prober20 run particle minecraft:flame ~ ~1 ~ 0 0 0 0 10 force Prober20",
  "execute at Prober20 run particle minecraft:block minecraft:stone ~ ~1 ~ 0 0 0 0 10 force Prober20",
  "execute at Prober20 run particle minecraft:dust 1 0 0 1 ~ ~1 ~ 0 0 0 0 10 force Prober20"
) @{
  # Vanilla's /playsound takes an arbitrary resource location, so it emits Named
  # Sound Effect rather than a registry id. 1.20.4 has no such packet, so these
  # two arrive as inline Sound Effects: no id to check, but they do prove the
  # named path carries a sound this way round too.
  "pig"    = "SOUND id=none name=minecraft:entity.pig.ambient (inline)"
  "beacon" = "SOUND id=none name=minecraft:block.beacon.activate (inline)"
  # Lightning is the registry-id path, and this is the assertion with teeth.
  # 742 is thunder in 1.20.4's own registries.json; the 1.13 server sends its own
  # id 351, so a table that maps 351 to anything else cannot produce this number.
  "thunder" = "SOUND id=742 name=minecraft:entity.lightning_bolt.thunder"
  "flame"  = "PARTICLE id=30 name=minecraft:flame"
  "block"  = "PARTICLE id=2 name=minecraft:block"
  "dust"   = "PARTICLE id=14 name=minecraft:dust"
}

foreach ($b in @($b113, $b765)) {
  try { $b.Process.StandardInput.WriteLine("stop"); $b.Process.StandardInput.Flush() } catch { }
}
Start-Sleep 8
& (Join-Path $repo "scripts\_kill-java.ps1")

$summary = Join-Path $logDir "RESULTS-SOUND-PARTICLE-$stamp.txt"
@("commit=$commit"; $results) | Set-Content $summary
Write-Host "SUMMARY $summary"
$results | ForEach-Object { Write-Host $_ }
