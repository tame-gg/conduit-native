# Builds the native plugin API on its own, for plugin authors to compile against:
# build/conduit-api-<version>.jar (the gg.tame.conduit.api classes and nothing else) and its -sources.jar.
# The version is gg.tame.conduit.Conduit.VERSION, the one the proxy itself reports.
param([string]$Out = "build")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $root $Out }
$javaRoot = Join-Path $root "src\main\java"

$identity = Get-Content -Raw (Join-Path $javaRoot "gg\tame\conduit\Conduit.java")
if ($identity -notmatch 'String VERSION = "([^"]+)"') { throw "no VERSION in Conduit.java" }
$version = $Matches[1]

$classes = Join-Path $out "api-classes"
$empty = Join-Path $out "api-empty"
foreach ($dir in @($classes, $empty)) {
  if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
  New-Item -ItemType Directory -Path $dir | Out-Null
}
$list = Join-Path $out "api-sources.txt"
Get-ChildItem (Join-Path $javaRoot "gg\tame\conduit\api") -Recurse -Filter *.java | ForEach-Object FullName | Set-Content $list

# Nothing on the class or source path: the API has to compile alone, or it reaches into Conduit's internals.
cmd /c "javac --release 21 -proc:none -cp `"$empty`" -sourcepath `"$empty`" -d `"$classes`" `"@$list`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw "API compile failed" }

$jar = Join-Path $out "conduit-api-$version.jar"
$sourcesJar = Join-Path $out "conduit-api-$version-sources.jar"
jar --create --file $jar -C $classes gg -C $root LICENSE
if ($LASTEXITCODE -ne 0) { throw "API jar failed" }
jar --create --file $sourcesJar -C $javaRoot gg/tame/conduit/api -C $root LICENSE
if ($LASTEXITCODE -ne 0) { throw "API sources jar failed" }
Remove-Item -Recurse -Force $classes, $empty, $list
Write-Output $jar
Write-Output $sourcesJar
