# Packages a runnable Conduit into dist/, with no source tree and no network.
#
# `gradle distZip` is the real distribution and carries its Corresponding Source
# (see build.gradle.kts and docs/LICENSING_VIA.md). This is not that: it is a
# local test build, assembled from what scripts/test.ps1 already compiled plus
# the jars in lib/, so it needs neither Gradle nor a download. Use distZip for
# anything that leaves this machine -- handing someone this folder would convey
# GPL object code without the source that has to travel with it.
param([string]$Out = "dist", [switch]$SkipBuild)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$outDir = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $repo $Out }
$classes = Join-Path $repo "out"
$lib = Join-Path $repo "lib"
$version = "0.9.2"

if (-not $SkipBuild) {
  Write-Host "building and testing..."
  # test.ps1 throws on a failure, and its $LASTEXITCODE is whatever native
  # command it ran last, so the throw is what is caught rather than the code.
  try { & (Join-Path $PSScriptRoot "test.ps1") | Out-Null }
  catch { throw "build/tests failed, so nothing was packaged: $_" }
}
if (-not (Test-Path (Join-Path $classes "gg\tame\conduit\launcher\Main.class"))) {
  throw "no compiled classes in $classes; run scripts/test.ps1"
}

if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path (Join-Path $outDir "lib") | Out-Null

# Conduit's own classes and the generated tables. The compiled tree also holds
# the test probes, which are wanted here: they are how a join is driven without
# a GUI.
$jar = Join-Path $outDir "conduit-$version.jar"
$jdk = (Get-Command java).Source | Split-Path -Parent
& (Join-Path $jdk "jar.exe") --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

# conduit.toml sits beside the jar, because every path in it -- plugins/, via/,
# forwarding.secret -- is resolved against the folder it is in. The 26.2 test
# config goes along so the folder is usable for that without the source tree.
Copy-Item (Join-Path $repo "conduit.toml") $outDir -Force
Copy-Item (Join-Path $repo "config\conduit-26.2.toml") $outDir -Force

Copy-Item (Join-Path $lib "*.jar") (Join-Path $outDir "lib") -Force
Copy-Item (Join-Path $lib "via\*.jar") (Join-Path $outDir "lib") -Force
Get-ChildItem (Join-Path $outDir "lib") -Filter "*-sources.jar" | Remove-Item -Force
Copy-Item (Join-Path $repo "LICENSE") $outDir -Force
Copy-Item (Join-Path $repo "THIRD-PARTY-NOTICES") $outDir -Force

# Via is a runtime dependency whether or not translation is on: ConduitRuntime
# always calls ConduitViaBootstrap, so the jar alone will not start.
@'
@echo off
setlocal
set HERE=%~dp0
set CONFIG=%1
if "%CONFIG%"=="" set CONFIG=%HERE%conduit.toml
java -Xms512M -Xmx1G -cp "%HERE%conduit-VERSION.jar;%HERE%lib\*" gg.tame.conduit.launcher.Main "%CONFIG%"
'@.Replace("VERSION", $version) | Set-Content (Join-Path $outDir "run.cmd") -Encoding ascii

@'
param([string]$ConfigPath)
$here = $PSScriptRoot
if (-not $ConfigPath) { $ConfigPath = Join-Path $here "config\conduit-26.2.toml" }
if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
  [Console]::Error.WriteLine("No configuration at $ConfigPath")
  exit 1
}
java -Xms512M -Xmx1G -cp "$here\conduit-VERSION.jar;$here\lib\*" gg.tame.conduit.launcher.Main $ConfigPath
'@.Replace("VERSION", $version) | Set-Content (Join-Path $outDir "run.ps1") -Encoding utf8

$size = [math]::Round(((Get-ChildItem $outDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "dist at $outDir ($size MB)"
Write-Host "  jar    $jar"
Write-Host "  libs   $((Get-ChildItem (Join-Path $outDir 'lib') -Filter *.jar).Count) jars"
