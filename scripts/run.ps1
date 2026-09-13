param([string]$ConfigPath = "config/conduit.toml")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "out"
if (-not (Test-Path $out)) { & (Join-Path $PSScriptRoot "test.ps1") }
java -cp $out gg.tame.conduit.launcher.Main $ConfigPath
