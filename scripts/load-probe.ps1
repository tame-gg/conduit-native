# Measures what a population of connected players costs the proxy: platform threads, memory, and
# whether the connections stay healthy. Not part of scripts/test.ps1 -- it takes minutes and it
# reports numbers rather than asserting them.
#
#   ./scripts/load-probe.ps1                            # 100, 500, 1000, held 20s each
#   ./scripts/load-probe.ps1 -Clients 100,500 -Hold 30
#   ./scripts/load-probe.ps1 -Label before -Out out-probe
#
# -Out reuses an existing compile when it is there, so a before/after pair measures two builds and
# not two compilers.
param([string]$Clients = "100,500,1000", [int]$Hold = 20, [string]$Label = "current", [string]$Out = "out-probe")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $root $Out }

if (-not (Test-Path (Join-Path $out "gg/tame/conduit/tests/LoadProbe.class"))) {
  Write-Host "compiling into $out ..."
  & (Join-Path $PSScriptRoot "test.ps1") -Out $Out -Only "gg.tame.conduit.tests.LoadProbe --compile-only"
}

$cpArgs = Join-Path $out "classpath-args.txt"
if (-not (Test-Path $cpArgs)) { throw "no classpath argument file in $out; run scripts/test.ps1 -Out $Out first" }

# -Xss is the one knob that decides what a platform thread costs, so it is pinned here rather than
# left to whatever the machine defaults to: a before/after pair has to price a thread the same way.
cmd /c "java -Xss512k -Xmx2g `"@$cpArgs`" gg.tame.conduit.tests.LoadProbe --clients $Clients --hold-seconds $Hold --label $Label"
if ($LASTEXITCODE -ne 0) { throw "load probe failed" }
