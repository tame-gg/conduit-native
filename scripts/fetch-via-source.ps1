# Fetches the Corresponding Source for the GPL ViaVersion jars Conduit bundles.
#
# GPLv3 section 6: object code may be conveyed only with the source it was built
# from. Conduit bundles ViaVersion, ViaBackwards, ViaRewind and ViaLegacy object
# code inside conduit.jar, so anything that hands that jar to someone else has to
# hand these archives over too, and putting them in the same folder is what makes
# that automatic -- no written offer, no source server to keep online.
#
# The versions here are the ones fetch-via.ps1 downloads jars for. They are written
# once, in both places, and docs/LICENSING_VIA.md records the pairing; changing one
# without the other is what produces a distribution whose source does not match its
# binaries.
param([string]$OutDir = "")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $root "dist\source\third-party" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Downloads name Conduit's build scripts and nothing else: no user, machine or path.
$userAgent = "Conduit-Development/0.9.8-SNAPSHOT"

# Keep in step with scripts/fetch-via.ps1.
$viaVersion = "5.11.0"
$viaRewind = "4.1.3"
$viaLegacy = "3.0.16"

# The upstream release tags. Conduit assumes these tags built the published Maven
# jars and does not verify it by rebuilding them; docs/LICENSING_VIA.md says so
# under "Open points".
$archives = [ordered]@{
  "ViaVersion-$viaVersion"   = "https://github.com/ViaVersion/ViaVersion/archive/refs/tags/$viaVersion.zip"
  "ViaBackwards-$viaVersion" = "https://github.com/ViaVersion/ViaBackwards/archive/refs/tags/$viaVersion.zip"
  "ViaRewind-$viaRewind"     = "https://github.com/ViaVersion/ViaRewind/archive/refs/tags/$viaRewind.zip"
  "ViaLegacy-$viaLegacy"     = "https://github.com/ViaVersion/ViaLegacy/archive/refs/tags/v$viaLegacy.zip"
}

foreach ($name in $archives.Keys) {
  $target = Join-Path $OutDir "$name.zip"
  if (Test-Path $target) {
    Write-Host "exists $name.zip"
    continue
  }
  # Downloaded to .part and renamed, so an interrupted run cannot leave a truncated
  # archive that a later run then treats as already fetched.
  $part = "$target.part"
  Write-Host "GET $($archives[$name])"
  Invoke-WebRequest -Uri $archives[$name] -OutFile $part -UseBasicParsing -UserAgent $userAgent
  Move-Item $part $target -Force
  Write-Host "OK $name.zip $((Get-Item $target).Length)"
}

# A jar whose source is missing is the one case that must not pass quietly: it is
# the difference between a distribution that may be published and one that may not.
$missing = @($archives.Keys | Where-Object { -not (Test-Path (Join-Path $OutDir "$_.zip")) })
if ($missing.Count -gt 0) { throw "Corresponding Source missing for: $($missing -join ', ')" }

Write-Host "Corresponding Source for the GPL Via jars: $OutDir"
