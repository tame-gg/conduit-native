# Builds a release the way a stranger would: a fresh clone of this repository, in a directory of its
# own, with nothing carried over from the working copy.
#
# The bug this exists for was invisible here. lib/ holds the libraries a Velocity plugin needs at run
# time, and a build that fetched them once leaves them there -- so a release that had stopped carrying
# them, or a fetch script that had stopped fetching one, built and ran perfectly on the machine it was
# written on and shipped a jar that could not load a plugin. Nothing in lib/, config/ or dist/ here is
# read by this script: HEAD is cloned, the fetch scripts fill the clone's own lib/ from
# config/velocity-runtime.lock, and build-jar.ps1 packages and tests it there.
#
# It builds what HEAD says, so uncommitted work is not in it and is reported rather than assumed.
# -SkipSource skips the Corresponding Source archives, which is quicker and not a release.
param([switch]$SkipSource, [switch]$Keep)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

$dirty = @(git -C $repo status --porcelain)
if ($dirty.Count -gt 0) {
  Write-Host ""
  Write-Host "  $($dirty.Count) uncommitted change(s) here. This checks HEAD, so they are not in it." -ForegroundColor Yellow
  Write-Host ""
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("conduit-release-check-" + [guid]::NewGuid().ToString("N"))
$clone = Join-Path $work "conduit"

# Deleting a tree a JVM has just had open loses a race with Windows often enough to matter: the test
# suite's last class loader may still hold a jar for a moment after the process that made it exits.
function Remove-Tree([string]$path) {
  for ($attempt = 1; $attempt -le 5; $attempt++) {
    try { Remove-Item -Recurse -Force -LiteralPath $path -ErrorAction Stop; return }
    catch { if ($attempt -eq 5) { throw } ; Start-Sleep -Seconds 2 }
  }
}

try {
  Write-Host "cloning HEAD into $clone"
  # safe.directory for this one command and no other: a repository owned by another account on the
  # same machine is refused otherwise, and this is the user's own checkout.
  # Both spellings: the check is on the .git directory, and git compares the path it built itself.
  git -c "safe.directory=$repo" -c "safe.directory=$repo/.git" clone --quiet $repo $clone
  if ($LASTEXITCODE -ne 0) { throw "could not clone $repo" }
  if (Get-ChildItem (Join-Path $clone "lib") -Filter *.jar -ErrorAction SilentlyContinue) {
    throw "the clone already has jars in lib/; they are supposed to be untracked"
  }

  Write-Host "fetching ViaVersion..."
  & (Join-Path $clone "scripts\fetch-via.ps1")
  Write-Host "fetching the Velocity plugin runtime..."
  & (Join-Path $clone "scripts\fetch-velocity-compat.ps1")

  Write-Host "building..."
  $arguments = @()
  if ($SkipSource) { $arguments += "-SkipSource" }
  & (Join-Path $clone "scripts\build-jar.ps1") @arguments

  $jar = Get-ChildItem (Join-Path $clone "dist") -Filter "conduit-*.jar"
  if (-not $jar) { throw "the build produced no jar" }
  Write-Host ""
  Write-Host "  release check passed: $($jar.Name), $([math]::Round($jar.Length / 1MB, 1)) MB, built from a clone with no lib/ of its own" -ForegroundColor Green
} finally {
  if ($Keep) {
    Write-Host "  -Keep: left $work in place"
  } elseif (Test-Path $work) {
    Remove-Tree $work
  }
}
