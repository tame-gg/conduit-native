# Control: real 1.14 client straight to the real 1.14 server, no Conduit at all.
#
# The 1.14 client has been crashing with "Tesselating block model" on paths that
# include Conduit. That crash also appears on the DIRECT byte-passthrough path,
# which cannot be a translation fault, so this removes Conduit entirely to settle
# whether the client is stable in this environment on its own. Without this
# control, a client-side rendering crash and a proxy bug look identical.
$ErrorActionPreference = "Continue"
$work = "C:\Users\Kyle\Documents\Codex\2026-09-13\files-pasted-by-the-user-conduit\work"
$rcv = Join-Path $work "real-client-validation"
$logDir = Join-Path $rcv "logs-477"
$java8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
  if ($_.CommandLine -match "AllTests|GameplayProbe|SchemaProbe|conduit\.launcher|net\.minecraft|server\.jar") {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }
}
Start-Sleep 3

$mc114 = Join-Path $work "mc114"
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $java8
$psi.Arguments = "-Xms512M -Xmx1G -jar server.jar nogui"
$psi.WorkingDirectory = $mc114
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$srv = [System.Diagnostics.Process]::Start($psi)
$srvLog = Join-Path $logDir "control-mc114-$stamp.log"
Register-ObjectEvent -InputObject $srv -EventName OutputDataReceived -Action {
  if ($EventArgs.Data) { Add-Content -Path $Event.MessageData -Value $EventArgs.Data }
} -MessageData $srvLog | Out-Null
$srv.BeginOutputReadLine()
for ($i = 0; $i -lt 180; $i++) {
  if (Get-NetTCPConnection -LocalPort 25677 -State Listen -ErrorAction SilentlyContinue) { break }
  Start-Sleep 1
}
Write-Host "server listening"

$client = Join-Path $rcv "client-1.14"
$cp = (Get-Content (Join-Path $client "classpath.txt") -Raw).Trim()
$gameDir = Join-Path $client "game"
$log = Join-Path $logDir "control-client-$stamp.log"
$args = @(
  "-Djava.library.path=$(Join-Path $client 'natives')",
  "-Xms1G", "-Xmx3G", "-cp", $cp,
  "net.minecraft.client.main.Main",
  "--username", "Control114", "--version", "1.14",
  "--gameDir", $gameDir, "--assetsDir", (Join-Path $client "assets"),
  "--assetIndex", "1.14", "--uuid", "00000000-0000-0000-0000-000000000114",
  "--accessToken", "0", "--userType", "legacy", "--versionType", "release",
  "--server", "127.0.0.1", "--port", "25677"
)
$proc = Start-Process -FilePath $java8 -ArgumentList $args -PassThru `
  -RedirectStandardOutput $log -RedirectStandardError (Join-Path $logDir "control-client-$stamp.err")
Write-Host "client PID $($proc.Id) -> 127.0.0.1:25677 (NO PROXY)"

Start-Sleep 120
$alive = -not $proc.HasExited
$text = Get-Content $srvLog -Raw -ErrorAction SilentlyContinue
$joined = ([regex]::Matches($text, "Control114 joined the game")).Count
$lost = ([regex]::Matches($text, "Control114 lost connection")).Count
Write-Host "CONTROL clientAlive=$alive joined=$joined lost=$lost"
if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
$srv.StandardInput.WriteLine("stop"); $srv.StandardInput.Flush()
Start-Sleep 8
if (-not $srv.HasExited) { Stop-Process -Id $srv.Id -Force -ErrorAction SilentlyContinue }
Write-Host "client log: $log"
