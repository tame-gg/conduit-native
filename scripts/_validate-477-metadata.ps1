# Verify 477 -> 404 entity metadata against what a real 1.14 client expects.
#
# The real 1.14.0 client cannot render in this environment - it crashes in its
# own block tesselator even with no proxy in the path (see _control-114-noproxy)
# - so it cannot be used to sign off the metadata work. This gets the same
# assurance a different way.
#
# A protocol-477 client is run THROUGH Conduit against the real 1.13.2 server,
# and every entity is summoned. Whatever Conduit hands that client is then
# compared field by field against the layout the real 1.14 server sends for the
# same entity. If Conduit puts a field at an index or serializer 1.14 does not
# use there, the comparison fails - which is precisely the fault that made the
# client throw ClassCastException on an arrow.
$ErrorActionPreference = "Continue"
$work = "C:\Users\Kyle\Documents\Codex\2026-09-13\files-pasted-by-the-user-conduit\work"
$repo = Join-Path $work "conduit-independent"
$outDir = Join-Path $work "real-client-validation\metadata-schema"
$java8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
$java21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$commit = (git -C $repo rev-parse --short HEAD).Trim()
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Write-Host "=== commit=$commit stamp=$stamp ==="

. (Join-Path $PSScriptRoot "_metadata-common.ps1")

Kill-Stale
& (Join-Path $repo "scripts\test.ps1") | Out-Null
if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }

$backend = Start-Backend (Join-Path $work "mc1132") 25614 "mc1132-thru" $outDir $stamp $java8
Start-Sleep 5

# Conduit: protocol-477 clients in front of the 1.13.2 backend.
$conduitLog = Join-Path $outDir "conduit-477to404-$stamp.log"
$conduit = Start-Process -FilePath $java21 `
  -ArgumentList @("-Dconduit.trace=true", "-cp", (Join-Path $repo "out"), "gg.tame.conduit.launcher.Main", "config\conduit-477-to-404.toml") `
  -WorkingDirectory $repo -PassThru -RedirectStandardOutput $conduitLog `
  -RedirectStandardError (Join-Path $outDir "conduit-477to404-$stamp.err")
Wait-PortListen 25547 90
for ($i = 0; $i -lt 60; $i++) {
  $t = Get-Content $conduitLog -Raw -ErrorAction SilentlyContinue
  if ($t -and ($t -match "Backend lobby is healthy")) { break }
  Start-Sleep 1
}
Write-Host "conduit ready on 25547"

$probeLog = Join-Path $outDir "schema-477-through-conduit-$stamp.txt"
$probe = Start-Process -FilePath $java21 `
  -ArgumentList @("-cp", (Join-Path $repo "out"), "gg.tame.conduit.tests.MetadataSchemaProbe",
                  "127.0.0.1", "25547", "477", "SchemaProbe", "170") `
  -WorkingDirectory $repo -PassThru -NoNewWindow -RedirectStandardOutput $probeLog `
  -RedirectStandardError (Join-Path $outDir "schema-477-through-conduit-$stamp.err")
Start-Sleep 12
Summon-All $backend "SchemaProbe"
Wait-Process -Id $probe.Id -Timeout 280 -ErrorAction SilentlyContinue
if (-not $probe.HasExited) { Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue }

Send-Console $backend "stop"
Start-Sleep 6
if (-not $backend.Proc.HasExited) { Stop-Process -Id $backend.Proc.Id -Force -ErrorAction SilentlyContinue }
if (-not $conduit.HasExited) { Stop-Process -Id $conduit.Id -Force -ErrorAction SilentlyContinue }
Kill-Stale

Write-Host "=== translation faults ==="
$ct = Get-Content $conduitLog -Raw -ErrorAction SilentlyContinue
$faults = ([regex]::Matches($ct, "TranslationException|no semantic mapping|translation failed")).Count
Write-Host "conduit translation faults: $faults"
Write-Host "=== drop reasons ==="
[regex]::Matches($ct, "DROP [^
]*") | ForEach-Object { $_.Value } |
  ForEach-Object { ($_ -split ":")[0] } | Group-Object | Sort-Object Count -Descending |
  Select-Object -First 12 | ForEach-Object { "{0,6}  {1}" -f $_.Count, $_.Name }
Write-Host "probe output: $probeLog"
Write-Host "compare with: python tools\compare_metadata_schema.py <native-477> $probeLog"
