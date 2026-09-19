# Builds a single runnable conduit.jar, plus a .bat to start it.
#
# `gradle distZip` remains the real distribution: it carries the Corresponding
# Source that GPL object code has to travel with (build.gradle.kts,
# docs/LICENSING_VIA.md). This is a local build for running it on this machine.
# The jar contains GPL code from the ViaVersion projects, so it is not something
# to hand to anyone else on its own.
#
# What goes in: Conduit's own classes and generated tables, plus the runtime
# dependencies from lib/via -- the same set build.gradle.kts declares. The
# Velocity-compatibility jars in lib/ are deliberately left out and shipped
# beside the jar instead: lib/ carries guava 33.3.1 and lib/via carries 33.0.0,
# and merging both into one jar would leave whichever was unpacked last shadowing
# the other. run.bat puts that folder on the classpath when it is present, which
# is all the compatibility layer needs.
param([string]$Out = "dist", [switch]$SkipBuild)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$outDir = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $repo $Out }
$classes = Join-Path $repo "out"
$lib = Join-Path $repo "lib"
$version = "0.9.0"
$jarName = "conduit-$version.jar"

if (-not $SkipBuild) {
  Write-Host "building and testing..."
  try { & (Join-Path $PSScriptRoot "test.ps1") | Out-Null }
  catch { throw "build/tests failed, so nothing was packaged: $_" }
}
if (-not (Test-Path (Join-Path $classes "gg\tame\conduit\launcher\Main.class"))) {
  throw "no compiled classes in $classes; run scripts/test.ps1"
}

if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("conduit-jar-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $stage | Out-Null

$jdk = (Get-Command java).Source | Split-Path -Parent
$jarExe = Join-Path $jdk "jar.exe"

try {
  # Unpack the runtime jars into one tree. Signatures are dropped: a merged jar
  # no longer matches the signatures of the jars it came from, and leaving them
  # in makes the JVM refuse it with a SecurityException. Each jar's own manifest
  # and module descriptor go too, since the merged jar has one of its own and is
  # a plain classpath jar.
  $services = @{}
  foreach ($archive in Get-ChildItem (Join-Path $lib "via") -Filter *.jar | Where-Object { $_.Name -notlike "*-sources.jar" }) {
    $one = Join-Path $stage ".one"
    New-Item -ItemType Directory -Force -Path $one | Out-Null
    Push-Location $one
    & $jarExe --extract --file $archive.FullName
    Pop-Location
    Remove-Item (Join-Path $one "META-INF\MANIFEST.MF") -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $one "module-info.class") -Force -ErrorAction SilentlyContinue
    Get-ChildItem (Join-Path $one "META-INF") -Include *.SF,*.DSA,*.RSA,*.EC -Recurse -ErrorAction SilentlyContinue |
      Remove-Item -Force

    # Service files name implementations one per line, and two jars can both
    # contribute to the same service. Copying one over the other loses whichever
    # lost the race, so they are collected and concatenated at the end instead.
    $svcDir = Join-Path $one "META-INF\services"
    if (Test-Path $svcDir) {
      foreach ($svc in Get-ChildItem $svcDir -File) {
        if (-not $services.ContainsKey($svc.Name)) { $services[$svc.Name] = @() }
        $services[$svc.Name] += (Get-Content $svc.FullName)
      }
      Remove-Item $svcDir -Recurse -Force
    }
    Copy-Item (Join-Path $one "*") $stage -Recurse -Force
    Remove-Item $one -Recurse -Force
  }
  if ($services.Count -gt 0) {
    $svcOut = Join-Path $stage "META-INF\services"
    New-Item -ItemType Directory -Force -Path $svcOut | Out-Null
    foreach ($name in $services.Keys) {
      $services[$name] | Select-Object -Unique | Set-Content (Join-Path $svcOut $name) -Encoding ascii
    }
  }

  # Conduit last, so its own classes and generated tables win any collision.
  # The test tree is left out: this is a jar of Conduit, not of its harness.
  Copy-Item (Join-Path $classes "*") $stage -Recurse -Force
  Remove-Item (Join-Path $stage "gg\tame\conduit\tests") -Recurse -Force -ErrorAction SilentlyContinue

  $manifest = Join-Path $stage "conduit-manifest.txt"
  @(
    "Main-Class: gg.tame.conduit.launcher.Main",
    "Implementation-Title: Conduit",
    "Implementation-Version: $version",
    ""
  ) | Set-Content $manifest -Encoding ascii

  $jar = Join-Path $outDir $jarName
  Push-Location $stage
  & $jarExe --create --file $jar --manifest $manifest (Get-ChildItem $stage -Exclude "conduit-manifest.txt" | ForEach-Object { $_.Name })
  Pop-Location
  if (-not (Test-Path $jar)) { throw "jar was not created" }
} finally {
  Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}

# Optional: the Velocity compatibility jars, for loading Velocity plugins.
New-Item -ItemType Directory -Force -Path (Join-Path $outDir "lib") | Out-Null
Copy-Item (Join-Path $lib "*.jar") (Join-Path $outDir "lib") -Force
Get-ChildItem (Join-Path $outDir "lib") -Filter "*-sources.jar" | Remove-Item -Force

New-Item -ItemType Directory -Force -Path (Join-Path $outDir "config") | Out-Null
Copy-Item (Join-Path $repo "config\conduit.toml") (Join-Path $outDir "config") -Force
Copy-Item (Join-Path $repo "config\conduit-26.2.toml") (Join-Path $outDir "config") -Force
Copy-Item (Join-Path $repo "LICENSE") $outDir -Force
Copy-Item (Join-Path $repo "THIRD-PARTY-NOTICES") $outDir -Force

@'
@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM Which config to start with: the first argument, or config\conduit.toml.
set "CONFIG=%~1"
if "%CONFIG%"=="" set "CONFIG=config\conduit.toml"

where java >nul 2>&1
if errorlevel 1 (
  echo Java was not found on PATH. Conduit needs a Java 21 or newer runtime.
  echo Install one, or edit this file to give the full path to java.exe.
  pause
  exit /b 1
)

if not exist "%CONFIG%" (
  echo No configuration at "%CONFIG%".
  echo Pass one as an argument, for example:  run.bat config\conduit-26.2.toml
  pause
  exit /b 1
)

REM lib\ holds the Velocity-compatibility jars. It is optional: without it
REM Conduit runs normally and only Velocity plugins are unavailable.
set "CP=conduit-VERSION.jar"
if exist "lib\*.jar" set "CP=conduit-VERSION.jar;lib\*"

echo Starting Conduit with "%CONFIG%"
echo.
java -Xms512M -Xmx1G -cp "%CP%" gg.tame.conduit.launcher.Main "%CONFIG%"
set "CODE=%ERRORLEVEL%"

echo.
if not "%CODE%"=="0" (
  echo Conduit exited with code %CODE%.
  pause
)
endlocal
'@.Replace("VERSION", $version) | Set-Content (Join-Path $outDir "run.bat") -Encoding ascii

$jarSize = [math]::Round((Get-Item (Join-Path $outDir $jarName)).Length / 1MB, 1)
Write-Host ""
Write-Host "  $outDir\$jarName  ($jarSize MB, runnable on its own)"
Write-Host "  $outDir\run.bat"
Write-Host "  $outDir\config\  conduit.toml, conduit-26.2.toml"
Write-Host "  $outDir\lib\     $((Get-ChildItem (Join-Path $outDir 'lib') -Filter *.jar).Count) optional jars for Velocity plugins"
