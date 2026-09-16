# Returns the Conduit runtime classpath (lib + Via + compiled out/).
param([switch]$Build)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "out"
$lib = Join-Path $root "lib"
$viaLib = Join-Path $lib "via"

function Ensure-Built {
  if (-not (Test-Path (Join-Path $out "gg\tame\conduit\launcher\Main.class"))) {
    & (Join-Path $PSScriptRoot "test.ps1") | Out-Null
    return
  }
  $viaJars = @(Get-ChildItem $viaLib -Filter *.jar -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "*-sources.jar" })
  if ($viaJars.Count -eq 0) {
    & (Join-Path $PSScriptRoot "fetch-via.ps1") | Out-Null
  }
}

if ($Build) { & (Join-Path $PSScriptRoot "test.ps1") | Out-Null }
else { Ensure-Built }

& (Join-Path $PSScriptRoot "fetch-via.ps1") | Out-Null
$viaJars = @(Get-ChildItem $viaLib -Filter *.jar -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "*-sources.jar" } | ForEach-Object FullName)
if ($viaJars.Count -eq 0) { throw "no Via jars in lib/via" }

$libJars = @(Get-ChildItem $lib -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object FullName)
$mainResources = Join-Path $root "src/main/resources"
if (Test-Path $mainResources) { Copy-Item (Join-Path $mainResources "*") $out -Recurse -Force }

return (($libJars + $viaJars + $out) -join ";")
