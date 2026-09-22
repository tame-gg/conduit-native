# Fetches the libraries a Velocity plugin links against at run time into lib/, for the compiler,
# the test class path and scripts/build-jar.ps1, which merges them into the release jar.
#
# Nothing here names a library, a version or a file name. The set comes from
# config/velocity-runtime.lock, which `./gradlew lockVelocityRuntime` writes from the velocityRuntime
# configuration in build.gradle.kts -- so it is velocity-api's own POM, resolved transitively, and
# not a hand-written list that goes stale the first time one of those POMs changes. Bumping a
# version means editing build.gradle.kts and running that task.
#
# Each line of the lock is a repository path and the SHA-256 of the file it names. A file already
# there is checked rather than trusted, a file that does not match is replaced, and a download is
# written beside its destination and moved onto it only once it hashes correctly -- so an
# interrupted run leaves no half-written jar for the next build to pick up. lib/ ends up holding
# exactly what the lock names: a jar from an older lock is removed, not left on the class path
# beside its replacement.
param([string]$LibDir = "")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $LibDir) { $LibDir = Join-Path $root "lib" }
New-Item -ItemType Directory -Path $LibDir -Force | Out-Null

# Downloads name Conduit's development scripts and nothing else: no user, machine or path.
$userAgent = "Conduit-Development/0.9.6-SNAPSHOT"
# Tried in order. Central serves everything but velocity-api and its Brigadier fork, which Velocity
# publishes to Paper's repository alone. The hash decides whether an answer is the right file, so a
# repository that serves something else at the same path falls through to the next one.
$repositories = @(
  "https://repo1.maven.org/maven2",
  "https://repo.papermc.io/repository/maven-public"
)

$lockFile = Join-Path $root "config/velocity-runtime.lock"
if (-not (Test-Path $lockFile)) {
  throw "$lockFile is missing. It is written by ``./gradlew lockVelocityRuntime`` and belongs in the checkout."
}
$wanted = [ordered]@{}
foreach ($line in Get-Content $lockFile) {
  if (-not $line.Trim() -or $line.StartsWith("#")) { continue }
  $fields = $line -split "`t"
  if ($fields.Count -ne 2) { throw "$lockFile has a line that is not <repository path><tab><sha256>: $line" }
  $wanted[[System.IO.Path]::GetFileName($fields[0])] = @{ Path = $fields[0]; Sha = $fields[1].Trim().ToLowerInvariant() }
}
if ($wanted.Count -eq 0) { throw "$lockFile names no artifacts" }

function Get-Sha256([string]$path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant() }

function Remove-Jar([string]$path, [string]$why) {
  try { Remove-Item -LiteralPath $path -Force }
  catch { throw "cannot remove $path ($why): a running JVM may still have it open. Stop it and rerun." }
}

foreach ($name in $wanted.Keys) {
  $entry = $wanted[$name]
  $dest = Join-Path $LibDir $name
  if (Test-Path $dest) {
    if ((Get-Sha256 $dest) -eq $entry.Sha) { Write-Host "present $name"; continue }
    Write-Host "replace $name (contents do not match the lock)"
    Remove-Jar $dest "it does not match the lock"
  }

  # Written beside the destination, not into the temp directory: a move within one directory is the
  # one the filesystem does without copying, so the destination never exists half-written.
  $part = "$dest.part"
  if (Test-Path $part) { Remove-Jar $part "a previous run left it behind" }
  $failures = @()
  $fetched = $false
  foreach ($repository in $repositories) {
    $url = "$repository/$($entry.Path)"
    try { Invoke-WebRequest -Uri $url -OutFile $part -UseBasicParsing -UserAgent $userAgent }
    catch { $failures += "$url : $($_.Exception.Message)"; continue }
    $got = Get-Sha256 $part
    if ($got -ne $entry.Sha) {
      $failures += "$url : served $got, the lock pins $($entry.Sha)"
      Remove-Jar $part "it is not the file the lock pins"
      continue
    }
    Write-Host "fetch $name"
    # The destination does not exist here: a matching one was kept above, a mismatching one removed.
    [System.IO.File]::Move($part, $dest)
    $fetched = $true
    break
  }
  if (-not $fetched) {
    if (Test-Path $part) { Remove-Jar $part "the fetch failed" }
    throw ("cannot fetch $name, which config/velocity-runtime.lock requires. Tried:`n  " +
      ($failures -join "`n  ") + "`nWithout it the release jar cannot load a Velocity plugin, so nothing was built.")
  }
}

# A jar from an older lock is not left behind: test.ps1 and _classpath.ps1 put every lib/*.jar on the
# class path, where an artifact that has been renamed or dropped would sit beside its replacement.
foreach ($stale in Get-ChildItem $LibDir -Filter *.jar) {
  if ($wanted.Contains($stale.Name)) { continue }
  Write-Host "remove $($stale.Name), which the lock no longer names"
  Remove-Jar $stale.FullName "the lock no longer names it"
}

Copy-Item (Join-Path $root "lib\velocity-compat\velocity-api-meta.xml") (Join-Path $LibDir "velocity-api-meta.xml") -ErrorAction SilentlyContinue
Write-Host "Velocity compat libraries ready in $LibDir ($($wanted.Count) jars)"
