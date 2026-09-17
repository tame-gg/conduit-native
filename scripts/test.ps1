param([string]$Out = "out", [string]$Only = "")

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$out = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $root $Out }
$lib = Join-Path $root "lib"
$viaLib = Join-Path $lib "via"
$fetch = Join-Path $PSScriptRoot "fetch-velocity-compat.ps1"
$fetchVia = Join-Path $PSScriptRoot "fetch-via.ps1"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null

& $fetchVia

# Core + tests compile without Velocity jars; Via jars are required for translation.
$mainSources = Get-ChildItem (Join-Path $root "src\main") -Recurse -Filter *.java | ForEach-Object FullName
$testSources = Get-ChildItem (Join-Path $root "src\test") -Recurse -Filter *.java | ForEach-Object FullName
$mainList = Join-Path $out "main-sources.txt"
$testList = Join-Path $out "test-sources.txt"
$mainSources | Set-Content $mainList
$testSources | Set-Content $testList

$viaJars = @(Get-ChildItem $viaLib -Filter *.jar -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "*-sources.jar" } | ForEach-Object FullName)
if ($viaJars.Count -eq 0) { throw "no Via jars in lib/via after fetch-via.ps1" }
$viaCp = $viaJars -join ";"

cmd /c "javac --release 21 -cp `"$viaCp`" -d `"$out`" `"@$mainList`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw "main compile failed" }

# Velocity compatibility layer (optional but expected for Phase9).
& $fetch -LibDir $lib
$libJars = @(Get-ChildItem $lib -Filter *.jar | ForEach-Object FullName)
if ($libJars.Count -eq 0) { throw "no jars in lib/ after fetch" }
$cp = ($libJars + $viaJars + $out) -join ";"
$compatSources = Get-ChildItem (Join-Path $root "src\compat-velocity") -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName
if ($compatSources) {
  $compatList = Join-Path $out "compat-sources.txt"
  $compatSources | Set-Content $compatList
  cmd /c "javac --release 21 -cp `"$cp`" -d `"$out`" `"@$compatList`" 2>&1"
  if ($LASTEXITCODE -ne 0) { throw "compat-velocity compile failed" }
}

cmd /c "javac --release 21 -cp `"$cp`" -d `"$out`" `"@$testList`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw "test compile failed" }

$mainResources = Join-Path $root "src/main/resources"
if (Test-Path $mainResources) { Copy-Item (Join-Path $mainResources "*") $out -Recurse -Force }

$resources = Join-Path $root "src/test/resources"
if (Test-Path $resources) { Copy-Item (Join-Path $resources "*") $out -Recurse -Force }

$runCp = ($libJars + $viaJars + $out) -join ";"
$runner = if ($Only) { $Only } else { "gg.tame.conduit.tests.AllTests" }
cmd /c "java -ea -cp `"$runCp`" $runner"
if ($LASTEXITCODE -ne 0) { throw "tests failed" }
