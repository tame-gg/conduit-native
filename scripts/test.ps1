$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "out"
$lib = Join-Path $root "lib"
$fetch = Join-Path $PSScriptRoot "fetch-velocity-compat.ps1"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null

# Core + tests compile without Velocity jars.
$mainSources = Get-ChildItem (Join-Path $root "src\main") -Recurse -Filter *.java | ForEach-Object FullName
$testSources = Get-ChildItem (Join-Path $root "src\test") -Recurse -Filter *.java | ForEach-Object FullName
$mainList = Join-Path $out "main-sources.txt"
$testList = Join-Path $out "test-sources.txt"
$mainSources | Set-Content $mainList
$testSources | Set-Content $testList

javac --release 21 -d $out "@$mainList"
if ($LASTEXITCODE -ne 0) { throw "main compile failed" }

# Velocity compatibility layer (optional but expected for Phase9).
& $fetch -LibDir $lib
$libJars = @(Get-ChildItem $lib -Filter *.jar | ForEach-Object FullName)
if ($libJars.Count -eq 0) { throw "no jars in lib/ after fetch" }
$cp = ($libJars + $out) -join ";"
$compatSources = Get-ChildItem (Join-Path $root "src\compat-velocity") -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName
if ($compatSources) {
  $compatList = Join-Path $out "compat-sources.txt"
  $compatSources | Set-Content $compatList
  javac --release 21 -cp $cp -d $out "@$compatList"
  if ($LASTEXITCODE -ne 0) { throw "compat-velocity compile failed" }
}

javac --release 21 -cp $cp -d $out "@$testList"
if ($LASTEXITCODE -ne 0) { throw "test compile failed" }

$resources = Join-Path $root "src/test/resources"
if (Test-Path $resources) { Copy-Item (Join-Path $resources "*") $out -Recurse -Force }

$runCp = ($libJars + $out) -join ";"
java -ea -cp $runCp gg.tame.conduit.tests.AllTests
if ($LASTEXITCODE -ne 0) { throw "tests failed" }
