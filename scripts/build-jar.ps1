# Builds a single runnable conduit.jar, plus a .bat to start it.
#
# The jar carries no ViaVersion. Via is GPL-3.0-or-later object code, and a jar
# holding it may only be handed to anyone alongside Via's Corresponding Source;
# it is also out of date the moment Via ships a new Minecraft release. So Conduit
# installs Via into lib/via on a start that can reach repo.viaversion.com, and
# this jar is Conduit's own code plus the Apache-2.0 libraries Via needs.
#
# Conduit itself is GPL-3.0-or-later, so the jar still travels with its own
# Corresponding Source, written into source/conduit/ (GPLv3 section 6).
#
# -SkipSource leaves that out. That is for running the build on this machine only;
# the result is not something to give to anyone else.
#
# What goes in: Conduit's own classes and generated tables, the runtime
# dependencies from lib/via -- the same set build.gradle.kts declares -- and the
# Velocity plugin runtime named by config/velocity-runtime.lock, which
# `./gradlew lockVelocityRuntime` resolves from velocity-api's own POM. The lock is
# read here rather than lib/ globbed, so what a release carries is decided by the
# build and not by whatever a developer's lib/ happens to hold. They are merged
# rather than shipped beside the jar because `java -jar` reads no class path but
# the jar's, and because a Velocity plugin must link against the very classes
# Conduit's adapter uses: its loader delegates to the class path the adapter came
# from. The two sets share no library: Guava is only in lib/via, at the version
# both ask for, and the build refuses two versions of anything.
param([string]$Out = "dist", [switch]$SkipBuild, [switch]$SkipSource)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$outDir = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $repo $Out }
$classes = Join-Path $repo "out"
$lib = Join-Path $repo "lib"
$version = "0.9.2"
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
  # The Velocity plugin runtime by name from the lock, not by globbing lib/: a release must carry
  # the set the build resolved, and a jar nobody put in lib/ is a broken release, not a smaller one.
  $velocityRuntime = foreach ($line in Get-Content (Join-Path $repo "config/velocity-runtime.lock")) {
    if (-not $line.Trim() -or $line.StartsWith("#")) { continue }
    $name = [System.IO.Path]::GetFileName(($line -split "`t")[0])
    $file = Join-Path $lib $name
    if (-not (Test-Path $file)) {
      throw "config/velocity-runtime.lock names $name, which is not in $lib. Run scripts/fetch-velocity-compat.ps1."
    }
    Get-Item $file
  }
  # ViaVersion itself is deliberately NOT merged. It is GPL object code, and a jar carrying it may
  # only be handed to anyone alongside Via's Corresponding Source; it also goes stale the moment Via
  # ships a new Minecraft release, which is every few weeks. Conduit installs it into lib/via on a
  # start that can reach repo.viaversion.com (Bootstrap, ViaUpdater#install), so what ships here is
  # Conduit's own code and the Apache-2.0 libraries Via needs -- fastutil, Netty, Guava -- which do
  # not go stale and are what would otherwise have to be downloaded with it.
  $viaOwn = @("viaversion-api", "viaversion-common", "viabackwards-common", "viarewind-common", "ViaLegacy")
  $runtime = @(Get-ChildItem (Join-Path $lib "via") -Filter *.jar |
    Where-Object { $_.Name -notlike "*-sources.jar" } |
    Where-Object { $name = $_.Name; -not ($viaOwn | Where-Object { $name -like "$_-*.jar" }) }) + @($velocityRuntime)
  # Guava is the one library both halves want, so build.gradle.kts excludes it from velocityRuntime
  # and the Via set is where it comes from -- at the newer version, which is the one both ask for.
  # That arrangement is invisible from either side, so it is stated here: if lib/via ever stops
  # carrying Guava, velocity-api and Velocity plugins lose it with no other warning.
  $guava = @($runtime | Where-Object { $_.Name -like "guava-*.jar" })
  if ($guava.Count -ne 1) {
    throw ("expected exactly one Guava in lib/via and found $($guava.Count). velocityRuntime in " +
      "build.gradle.kts excludes com.google.guava on the understanding that the Via set supplies it, " +
      "so without it velocity-api and every Velocity plugin lose Guava. Run scripts/fetch-via.ps1.")
  }
  # Two versions of one library would leave whichever was unpacked last shadowing the other.
  $seen = @{}
  foreach ($archive in $runtime) {
    $id = $archive.Name -replace '-\d[^-]*(-.*)?\.jar$', ''
    if ($seen.ContainsKey($id) -and $seen[$id] -ne $archive.Name) { throw "both $($seen[$id]) and $($archive.Name) would go into the jar" }
    $seen[$id] = $archive.Name
  }
  foreach ($archive in $runtime) {
    $one = Join-Path $stage ".one"
    New-Item -ItemType Directory -Force -Path $one | Out-Null
    Push-Location $one
    & $jarExe --extract --file $archive.FullName
    Pop-Location
    Remove-Item (Join-Path $one "META-INF\MANIFEST.MF") -Force -ErrorAction SilentlyContinue
    # Multi-release jars carry one under META-INF/versions/<n>/ as well.
    Get-ChildItem $one -Recurse -Filter "module-info.class" | Remove-Item -Force
    Get-ChildItem (Join-Path $one "META-INF") -Include *.SF,*.DSA,*.RSA,*.EC -Recurse -ErrorAction SilentlyContinue |
      Remove-Item -Force
    # Annotation-processor registrations are for a compiler, not a running proxy; velocity-api has one.
    Remove-Item (Join-Path $one "META-INF\services\javax.annotation.processing.Processor") -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $one "META-INF\gradle") -Recurse -Force -ErrorAction SilentlyContinue

    # License and notice files keep their text, in a folder named for their jar: Guava, Guice and
    # Caffeine all ship a META-INF/LICENSE, and merged they would overwrite one another -- and
    # Guice's NOTICE is one Apache-2.0 says must travel with it.
    $legal = @(Get-ChildItem $one, (Join-Path $one "META-INF") -File -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -match '^(LICENSE|NOTICE|COPYING)' })
    if ($legal.Count -gt 0) {
      $keep = Join-Path $one ("META-INF\licenses\" + $archive.BaseName)
      New-Item -ItemType Directory -Force -Path $keep | Out-Null
      $legal | Move-Item -Destination $keep -Force
    }

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
  # Things out/ and the unpacked jars leave behind that a release has no use for. The source lists
  # and the class-path argument file are how test.ps1 talks to javac on a shell with an 8191-character
  # limit, and they name this machine's paths; command-tree-776.bin is a test fixture out of
  # src/test/resources; and META-INF/INDEX.LIST is one input jar's index of its own contents, which
  # after a merge describes a jar that no longer exists and which the JVM would believe.
  foreach ($leftover in @(
      "classpath-args.txt",
      "*-sources.txt",
      "command-tree-776.bin",
      "META-INF\INDEX.LIST")) {
    Remove-Item (Join-Path $stage $leftover) -Force -ErrorAction SilentlyContinue
  }
  # The jar may travel on its own, so it carries Conduit's license and the third-party notices, and
  # a first start writes them beside itself (ConfigBootstrap). Under META-INF/conduit/ rather than
  # META-INF/ directly: a bare META-INF/LICENSE is a name half of Maven Central also uses, and a
  # development run with lib/ on the class path would find Guava's before Conduit's.
  $legalDir = Join-Path $stage "META-INF\conduit"
  New-Item -ItemType Directory -Force -Path $legalDir | Out-Null
  Copy-Item (Join-Path $repo "LICENSE"), (Join-Path $repo "THIRD-PARTY-NOTICES") $legalDir -Force

  $manifest = Join-Path $stage "conduit-manifest.txt"
  @(
    # The bootstrap, not the launcher: it decides whether a newer ViaVersion in
    # lib/via displaces the one merged into this jar, then calls the launcher.
    "Main-Class: gg.tame.conduit.boot.Bootstrap",
    # Configurate, night-config and adventure's SLF4J logger keep classes for newer JDKs under
    # META-INF/versions/, which a jar only uses when it says it is multi-release.
    "Multi-Release: true",
    "Implementation-Title: Conduit",
    "Implementation-Version: $version",
    ""
  ) | Set-Content $manifest -Encoding ascii

  $jar = Join-Path $outDir $jarName
  Push-Location $stage
  & $jarExe --create --file $jar --manifest $manifest (Get-ChildItem $stage -Exclude "conduit-manifest.txt" | ForEach-Object { $_.Name })
  Pop-Location
  if (-not (Test-Path $jar)) { throw "jar was not created" }

  # The jar a user downloads is the whole install, so a jar that cannot load a Velocity plugin is a
  # broken build, not an optional feature left out. One class from each library the adapter and
  # velocity-api need at run time, the adapter itself, and the notices that must travel with them.
  $entries = @(& $jarExe --list --file $jar)
  $required = @(
    "gg/tame/conduit/compat/velocity/VelocityBoot.class",
    "com/velocitypowered/api/plugin/Plugin.class",
    "net/kyori/adventure/text/Component.class",
    "net/kyori/adventure/text/minimessage/MiniMessage.class",
    "net/kyori/adventure/text/serializer/ansi/ANSIComponentSerializer.class",
    "com/mojang/brigadier/CommandDispatcher.class",
    "com/google/gson/Gson.class",
    "com/google/inject/Injector.class",
    "org/slf4j/Logger.class",
    "org/slf4j/jul/JDK14LoggerAdapter.class",
    "org/yaml/snakeyaml/Yaml.class",
    "org/spongepowered/configurate/hocon/HoconConfigurationLoader.class",
    "com/electronwill/nightconfig/toml/TomlParser.class",
    "com/github/benmanes/caffeine/cache/Caffeine.class",
    "com/moandjiezana/toml/Toml.class",
    "META-INF/conduit/LICENSE",
    "META-INF/conduit/THIRD-PARTY-NOTICES",
    "META-INF/licenses/guice-6.0.0/NOTICE"
  )
  $missing = @($required | Where-Object { $entries -notcontains $_ })
  if ($missing.Count -gt 0) { throw "the jar is missing what Velocity plugins need at run time: $($missing -join ', ')" }
  # Guava again, on the finished jar rather than on the list of inputs: velocity-api's own signatures
  # return Guava types, so a jar without it loads no Velocity plugin at all. It is checked separately
  # from the list above because nothing in the Velocity half of the build brings it.
  if ($entries -notcontains "com/google/common/collect/ImmutableList.class") {
    throw ("the jar has no Guava. $($guava.Name) went in, so something dropped it on the way -- " +
      "velocity-api returns Guava types and no Velocity plugin will load without it.")
  }
  # Velocity's Brigadier, not Mojang's: only the fork has what velocity-api's own classes call.
  $brigadier = & (Join-Path $jdk "javap.exe") -cp $jar com.mojang.brigadier.builder.ArgumentBuilder
  if (-not ($brigadier -match "requiresWithContext")) { throw "the jar's Brigadier is not Velocity's fork" }

  # And then the jar is actually started, in an empty folder, with a Velocity plugin in it. The
  # entries above say the classes are present; only a run says a user's download works. The suite
  # runs before the jar exists, so this is the pass that has one to give it.
  $cpArgs = Join-Path $classes "classpath-args.txt"
  if (Test-Path $cpArgs) {
    Write-Host "starting the jar in an empty folder..."
    cmd /c "java -ea `"-Dconduit.release.jar=$jar`" `"@$cpArgs`" gg.tame.conduit.tests.ReleaseJarTests"
    if ($LASTEXITCODE -ne 0) { throw "the packaged jar did not start a Velocity plugin from an empty folder" }
  } else {
    Write-Host "  no $cpArgs, so the empty-folder start was not run (-SkipBuild)" -ForegroundColor Yellow
  }
} finally {
  Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}

# conduit.toml sits beside the jar: plugins/, via/ and forwarding.secret are all
# resolved against the folder it is in. Taken from src/main/resources, which is the
# one copy of it -- the same file the jar writes on a first start, so what ships and
# what Conduit would have written cannot disagree.
Copy-Item (Join-Path $repo "src/main/resources/gg/tame/conduit/config/conduit.toml") $outDir -Force
Copy-Item (Join-Path $repo "config\conduit-26.2.toml") $outDir -Force
Copy-Item (Join-Path $repo "LICENSE") $outDir -Force
Copy-Item (Join-Path $repo "THIRD-PARTY-NOTICES") $outDir -Force

if ($SkipSource) {
  Write-Host ""
  Write-Host "  -SkipSource: no Corresponding Source. Do not publish this build." -ForegroundColor Yellow
} else {
  # GPLv3 section 6: the object code in the jar travels with the source it was built
  # from, in the same archive, so no written offer and no source server is needed.
  $sourceDir = Join-Path $outDir "source\conduit"
  New-Item -ItemType Directory -Force -Path $sourceDir | Out-Null
  # The Gradle wrapper travels with the rest: it is one of the scripts used to control compilation,
  # and config/velocity-runtime.lock cannot be regenerated without it.
  foreach ($item in @("src", "scripts", "tools", "config", "docs", "gradle", "gradlew", "gradlew.bat",
                      "build.gradle.kts", "settings.gradle.kts", "README.md", "LICENSE",
                      "THIRD-PARTY-NOTICES")) {
    $from = Join-Path $repo $item
    if (Test-Path $from) { Copy-Item $from $sourceDir -Recurse -Force }
  }
  Get-ChildItem $sourceDir -Recurse -Directory -Filter "__pycache__" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force
}

@'
@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM Which config to start with: the first argument, or conduit.toml beside this file.
set "CONFIG=%~1"
if "%CONFIG%"=="" set "CONFIG=conduit.toml"

where java >nul 2>&1
if errorlevel 1 (
  echo Java was not found on PATH. Conduit needs a Java 21 or newer runtime.
  echo Install one, or edit this file to give the full path to java.exe.
  pause
  exit /b 1
)

REM No check that the configuration exists: Conduit ships the file and writes it
REM on a first start, along with plugins\ and forwarding.secret. Refusing here was
REM the proxy declining to do something it is perfectly able to do.

echo Starting Conduit with "%CONFIG%"
echo.
java -Xms512M -Xmx1G -jar conduit-VERSION.jar "%CONFIG%"
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
Write-Host "  $outDir\conduit.toml  (and conduit-26.2.toml)"
if (-not $SkipSource) {
  Write-Host "  $outDir\source\  Corresponding Source for Conduit"
}
