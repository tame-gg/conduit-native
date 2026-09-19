param([string]$ConfigPath = "conduit.toml")
$ErrorActionPreference = "Stop"
# Checked before the classpath, which can mean a full build: a missing file ended that wait in a stack trace.
if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
  [Console]::Error.WriteLine("No configuration at $ConfigPath. Copy the sample conduit.toml there and edit it, or pass -ConfigPath <file>.")
  exit 1
}
# Conduit does not start without the Via jars, even with translation disabled: out/ alone is not a classpath.
$cp = & (Join-Path $PSScriptRoot "_classpath.ps1")
java -cp $cp gg.tame.conduit.launcher.Main $ConfigPath
