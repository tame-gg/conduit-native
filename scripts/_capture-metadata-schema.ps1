# Capture the real per-entity metadata layout from the 1.13.2 and 1.14 servers.
#
# Connects MetadataSchemaProbe straight to each server (no proxy), summons the
# same entities on both from the server console, and writes down every
# (metadataIndex, serializer) pair each entity type actually sends. Diffing the
# two outputs gives the 404 -> 477 schema delta as measured fact rather than as
# an assumed pair-wide index rule.
$ErrorActionPreference = "Continue"
$work = "C:\Users\Kyle\Documents\Codex\2026-09-13\files-pasted-by-the-user-conduit\work"
$repo = Join-Path $work "conduit-independent"
$outDir = Join-Path $work "real-client-validation\metadata-schema"
$java8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
$java21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

. (Join-Path $PSScriptRoot "_metadata-common.ps1")

function Capture([string]$Dir, [int]$Port, [int]$Protocol, [string]$Label) {
  Write-Host "=== capture $Label (protocol $Protocol) ==="
  Kill-Stale
  $backend = Start-Backend $Dir $Port $Label $outDir $stamp $java8
  Start-Sleep 5
  $probeLog = Join-Path $outDir "schema-$Protocol-$stamp.txt"
  $probe = Start-Process -FilePath $java21 `
    -ArgumentList @("-cp", (Join-Path $repo "out"), "gg.tame.conduit.tests.MetadataSchemaProbe",
                    "127.0.0.1", "$Port", "$Protocol", "SchemaProbe", "170") `
    -WorkingDirectory $repo -PassThru -NoNewWindow -RedirectStandardOutput $probeLog `
    -RedirectStandardError (Join-Path $outDir "schema-$Protocol-$stamp.err")
  Start-Sleep 12
  Summon-All $backend "SchemaProbe"
  Wait-Process -Id $probe.Id -Timeout 280 -ErrorAction SilentlyContinue
  if (-not $probe.HasExited) { Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue }
  Send-Console $backend "stop"
  Start-Sleep 6
  if (-not $backend.Proc.HasExited) { Stop-Process -Id $backend.Proc.Id -Force -ErrorAction SilentlyContinue }
  Write-Host "  -> $probeLog"
  return $probeLog
}

& (Join-Path $repo "scripts\test.ps1") | Out-Null
if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }

$a = Capture (Join-Path $work "mc1132") 25614 404 "mc1132"
$b = Capture (Join-Path $work "mc114") 25677 477 "mc114"
Kill-Stale
Write-Host "=== 404 schema ==="
Select-String -Path $a -Pattern "^SCHEMA" | ForEach-Object { $_.Line }
Write-Host "=== 477 schema ==="
Select-String -Path $b -Pattern "^SCHEMA" | ForEach-Object { $_.Line }
