param([string]$ConfigPath = "config/conduit.toml")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "out"
if (-not (Test-Path $out)) { & (Join-Path $PSScriptRoot "test.ps1") }
$mainResources = Join-Path $root "src/main/resources"
if (Test-Path $mainResources) { Copy-Item (Join-Path $mainResources "*") $out -Recurse -Force }
java -cp $out gg.tame.conduit.launcher.Main $ConfigPath
