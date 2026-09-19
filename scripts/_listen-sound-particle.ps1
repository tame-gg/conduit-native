# Plays sounds and particles to a REAL 1.13 client joined through Conduit.
#
# The scripted probe checks the ids against Mojang's registry, which says the
# right sound was chosen. It cannot say the packet is one a real client accepts:
# the probe is Conduit's own code decoding Conduit's own output. The official
# client is an independent decoder. If a Sound Effect or Particle packet were
# malformed, or carried an id outside the client's registry, the client would
# log it or drop the connection.
#
# What this can and cannot show:
#   CAN  - the official client accepts every packet and stays connected
#   CAN  - the client resolves each sound (it logs the ones it cannot)
#   CAN  - the particles are on screen, in a screenshot
#   CANNOT - that the sound is the right one to a listener. Nobody here has ears.
#            The id check in _validate-sound-particle.ps1 is what establishes that.
#
#   scripts/_listen-sound-particle.ps1
param([int]$JoinTimeout = 180)

$ErrorActionPreference = "Continue"
$work = "C:\Users\Kyle\Documents\Codex\2026-09-13\files-pasted-by-the-user-conduit\work"
$repo = Join-Path $work "conduit-independent"
$rcv = Join-Path $work "real-client-validation"
$logDir = Join-Path $rcv "logs"
$java8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
$java21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe"
$classpath = & (Join-Path $repo "scripts\_classpath.ps1")
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$player = "ConduitTest"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

& (Join-Path $repo "scripts\_kill-java.ps1")

function Wait-PortListen([int]$Port, [int]$Seconds = 180) {
  for ($i = 0; $i -lt $Seconds; $i++) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { return $true }
    Start-Sleep 1
  }
  return $false
}

# The backend with its console attached, so it can be told to play things while
# the client is standing in the world.
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $java21
$psi.Arguments = "-Xms512M -Xmx1G -jar server.jar nogui"
$psi.WorkingDirectory = Join-Path $work "mc1204"
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$backend = [System.Diagnostics.Process]::Start($psi)
$backendLog = Join-Path $logDir "listen-mc1204-$stamp.log"
Register-ObjectEvent -InputObject $backend -EventName OutputDataReceived -Action {
  if ($EventArgs.Data) { Add-Content -Path $Event.MessageData -Value $EventArgs.Data }
} -MessageData $backendLog | Out-Null
$backend.BeginOutputReadLine()
if (-not (Wait-PortListen 25604 240)) { throw "1.20.4 backend never listened" }
Write-Host "backend up"

$conduitLog = Join-Path $logDir "listen-conduit-$stamp.log"
$conduit = Start-Process -FilePath $java21 -PassThru `
  -ArgumentList @("-cp",$classpath,"gg.tame.conduit.launcher.Main","config\conduit-393-to-765.toml") `
  -WorkingDirectory $repo -RedirectStandardOutput $conduitLog -RedirectStandardError "$conduitLog.err"
if (-not (Wait-PortListen 25561 60)) { throw "conduit never listened" }
Write-Host "conduit up on 25561"

# The official 1.13 client, joining Conduit rather than the server. --proxyHost
# points its own online lookups at a dead SOCKS port so nothing leaves the box.
$client = Join-Path $rcv "client-1.13"
$gameDir = Join-Path $client "game"
New-Item -ItemType Directory -Force -Path $gameDir | Out-Null
$clientLog = Join-Path $logDir "listen-client-$stamp.log"
$clientArgs = @(
  "-Djava.library.path=$(Join-Path $client 'natives')",
  "-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true",
  "-Xms512M", "-Xmx1G",
  "-cp", (Get-Content (Join-Path $client "classpath.txt") -Raw).Trim(),
  "net.minecraft.client.main.Main",
  "--username", $player,
  "--version", "1.13",
  "--gameDir", $gameDir,
  "--assetsDir", (Join-Path $client "assets"),
  "--assetIndex", "1.13",
  "--uuid", "00000000-0000-0000-0000-000000000013",
  "--accessToken", "0",
  "--userType", "legacy",
  "--versionType", "release",
  "--proxyHost", "127.0.0.1", "--proxyPort", "9",
  "--server", "127.0.0.1", "--port", "25561"
)
$clientProc = Start-Process -FilePath $java8 -ArgumentList $clientArgs -PassThru -NoNewWindow `
  -RedirectStandardOutput $clientLog -RedirectStandardError "$clientLog.err"
Write-Host "client PID=$($clientProc.Id) log=$clientLog"

# The backend says when the player is actually in the world.
$joined = $false
for ($i = 0; $i -lt $JoinTimeout; $i++) {
  if (Select-String -Path $backendLog -Pattern "$player joined the game" -Quiet -ErrorAction SilentlyContinue) {
    $joined = $true; break
  }
  if ($clientProc.HasExited) { Write-Host "client exited early"; break }
  Start-Sleep 1
}
Write-Host "joined=$joined"

if ($joined) {
  Start-Sleep 5
  $backend.StandardInput.WriteLine("op $player"); $backend.StandardInput.Flush()
  Start-Sleep 2
  # Sounds first: they leave a line in the client log, so they need no picture.
  foreach ($cmd in @(
    "playsound minecraft:entity.pig.ambient master $player",
    "playsound minecraft:block.beacon.activate master $player",
    "playsound minecraft:entity.lightning_bolt.thunder master $player",
    "playsound minecraft:entity.warden.roar master $player",
    "execute at $player run summon minecraft:lightning_bolt ~ ~ ~"
  )) {
    $backend.StandardInput.WriteLine($cmd); $backend.StandardInput.Flush()
    Start-Sleep 1
  }

  # Particles are only on screen for a second or two, so each is photographed
  # immediately rather than after the rest of the run. They are spawned right on
  # the camera and in quantity so that whichever way the player happens to be
  # facing, the shot shows them.
  $particles = [ordered]@{
    "flame" = "execute at $player run particle minecraft:flame ~ ~1 ~ 0.6 0.6 0.6 0.01 600 force $player"
    "block" = "execute at $player run particle minecraft:block minecraft:redstone_block ~ ~1 ~ 0.6 0.6 0.6 0.01 600 force $player"
    "dust"  = "execute at $player run particle minecraft:dust 1 0 0 4 ~ ~1 ~ 0.6 0.6 0.6 0.01 600 force $player"
  }
  foreach ($name in $particles.Keys) {
    $backend.StandardInput.WriteLine($particles[$name]); $backend.StandardInput.Flush()
    Start-Sleep -Milliseconds 400
    $shot = Join-Path $logDir "listen-shot-$name-$stamp.png"
    & (Join-Path $rcv "shot-window.ps1") -Out $shot -ProcessId $clientProc.Id
    Write-Host "SHOT_$name=$shot"
    Start-Sleep 2
  }
  Start-Sleep 2
}

# What the client itself said. A sound it cannot resolve is logged by name; a
# malformed packet ends the connection.
$clientText = (Get-Content $clientLog -Raw -ErrorAction SilentlyContinue) + `
              (Get-Content "$clientLog.err" -Raw -ErrorAction SilentlyContinue)
$unknownSounds = ([regex]::Matches($clientText, "Unable to play unknown soundEvent: ?(\S+)") |
                  ForEach-Object { $_.Groups[1].Value }) -join ","
$disconnects = ([regex]::Matches($clientText, "(Internal Exception|Disconnected|Connection reset|Timed out)") |
                ForEach-Object { $_.Value } | Select-Object -Unique) -join ","
Write-Host "CLIENT_UNKNOWN_SOUNDS=$unknownSounds"
Write-Host "CLIENT_DISCONNECTS=$disconnects"
Write-Host "CLIENT_STILL_RUNNING=$(-not $clientProc.HasExited)"

$translationFails = (Select-String -Path "$conduitLog.err" -Pattern "Translation failed" -SimpleMatch -ErrorAction SilentlyContinue).Count
Write-Host "CONDUIT_TRANSLATION_FAILS=$translationFails"

$summary = Join-Path $logDir "RESULTS-LISTEN-$stamp.txt"
@(
  "joined=$joined",
  "client_unknown_sounds=$unknownSounds",
  "client_disconnects=$disconnects",
  "client_still_running=$(-not $clientProc.HasExited)",
  "conduit_translation_fails=$translationFails",
  "client_log=$clientLog",
  "backend_log=$backendLog"
) | Set-Content $summary
Write-Host "SUMMARY $summary"

if (-not $clientProc.HasExited) { Stop-Process -Id $clientProc.Id -Force -ErrorAction SilentlyContinue }
Stop-Process -Id $conduit.Id -Force -ErrorAction SilentlyContinue
try { $backend.StandardInput.WriteLine("stop"); $backend.StandardInput.Flush() } catch { }
Start-Sleep 8
& (Join-Path $repo "scripts\_kill-java.ps1")
