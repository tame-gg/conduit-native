param([string]$ConfigPath = "config/conduit.toml")
$ErrorActionPreference = "Stop"
# Conduit does not start without the Via jars, even with translation disabled: out/ alone is not a classpath.
$cp = & (Join-Path $PSScriptRoot "_classpath.ps1")
java -cp $cp gg.tame.conduit.launcher.Main $ConfigPath
