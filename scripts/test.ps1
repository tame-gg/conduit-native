$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null
$sources = Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object FullName
javac --release 21 -d $out @sources
$resources = Join-Path $root "src/test/resources"
if (Test-Path $resources) { Copy-Item (Join-Path $resources "*") $out -Recurse -Force }
java -ea -cp $out gg.tame.conduit.tests.AllTests
