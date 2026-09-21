# Fetch ViaVersion ecosystem + runtime libraries into lib/via
# Third-party artifacts, downloaded from upstream and never committed (lib/via is gitignored).
# Licenses: THIRD-PARTY-NOTICES and docs/LICENSING_VIA.md.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$lib = Join-Path $root "lib\via"
New-Item -ItemType Directory -Force -Path $lib | Out-Null

# Downloads name Conduit's development scripts and nothing else: no user, machine or path.
$userAgent = "Conduit-Development/0.9.1-SNAPSHOT"
$viaRepo = "https://repo.viaversion.com/everything"
$maven = "https://repo1.maven.org/maven2"

# SHA-256 of every jar this script fetches, by file name. These are what Conduit was built and
# tested against: a repository that served something else, or a proxy or mirror in the way that
# rewrote it, is caught here rather than at the far end of a build. Bumping a version means
# fetching the new jar, checking it, and putting its hash here -- the hash follows the version.
$sha256 = @{
  "viaversion-api-5.11.0.jar"      = "5f98cff9ffe6519e0371f59ff34633996a3246b3f584bb6b4339eb9427d4e848"
  "viaversion-common-5.11.0.jar"   = "a4dd9f63257ed923f73a64ecece31010acd04247db12855383172e1226912b3e"
  "viabackwards-common-5.11.0.jar" = "f17e79dc3622a361f9bab9dcf2d11d03ed3461897b1aeaefe13ede0047b15ec5"
  "viarewind-common-4.1.3.jar"     = "f5fe56b4691f045d04662659d45bc182b610cb4b693501f72272c8877a021c8f"
  "ViaLegacy-3.0.16.jar"           = "e17735824328f92349bcbb5e4b00ea2791affaeea2f3b2933827ccca9527bf0e"
  "netty-common-4.1.118.Final.jar"    = "65cce901ecf0f9d6591cc7750772614ab401a84415dc9aec9da4d046f0f9a77c"
  "netty-buffer-4.1.118.Final.jar"    = "0eea4e8666a9636a28722661d8ba5fa8564477e75fec6dd2ff3e324e361f8b3c"
  "netty-transport-4.1.118.Final.jar" = "ab3751e717daef9c8d91e4d74728a48730bd8530b72e2466b222b2ea3fb07db9"
  "netty-codec-4.1.118.Final.jar"     = "4abd215fd1ed7ce86509d169cc9cbede5042176c265a79b3b70602b017226c3f"
  "netty-handler-4.1.118.Final.jar"   = "26e3f8a5e859fd62cf3c13dc6d75e4e18879f000a5d0ad7f58f8679675d23dae"
  "netty-resolver-4.1.118.Final.jar"  = "3170c225972c18b6850d28add60db15bb28d83c4e3d5b686ca220e0bd7273c8a"
  "guava-33.3.1-jre.jar"           = "4bf0e2c5af8e4525c96e8fde17a4f7307f97f8478f11c4c8e35a0e3298ae4e90"
  "failureaccess-1.0.2.jar"        = "8a8f81cf9b359e3f6dfa691a1e776985c061ef2f223c9b2c80753e1b458e8064"
  "fastutil-8.5.15.jar"            = "cffeb6673bdf1e6e4377d684df2f55ac359cf5cf6df613e05e62deeea500936a"
}

# A jar whose contents are not the ones pinned above is deleted rather than left for the build to
# pick up: a half-written download from an interrupted run looks exactly like this too.
function Confirm-Artifact([string]$path) {
  $name = Split-Path $path -Leaf
  $want = $sha256[$name]
  if (-not $want) { throw "no pinned SHA-256 for $name; add one to fetch-via.ps1 before using it" }
  $got = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
  if ($got -eq $want) { return }
  Remove-Item $path -Force -ErrorAction SilentlyContinue
  throw "$name is not the jar Conduit pins: expected $want, got $got. The file has been removed."
}

function Get-Artifact([string]$url, [string]$out) {
  if (Test-Path $out) {
    Confirm-Artifact $out
    Write-Host "exists $(Split-Path $out -Leaf)"
    return
  }
  Write-Host "GET $url"
  Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing -UserAgent $userAgent
  Confirm-Artifact $out
  Write-Host "OK $(Split-Path $out -Leaf) $((Get-Item $out).Length)"
}

$viaVersion = "5.11.0"
$viaRewind = "4.1.3"
$viaLegacy = "3.0.16"
$netty = "4.1.118.Final"
# One Guava for the whole jar: the Velocity-compatibility layer (fetch-velocity-compat.ps1) and its
# plugins use this copy too, so it is the newer of the two those sets asked for. Via runs on it.
$guava = "33.3.1-jre"
$fastutil = "8.5.15"

Get-Artifact "$viaRepo/com/viaversion/viaversion-api/$viaVersion/viaversion-api-$viaVersion.jar" (Join-Path $lib "viaversion-api-$viaVersion.jar")
Get-Artifact "$viaRepo/com/viaversion/viaversion-common/$viaVersion/viaversion-common-$viaVersion.jar" (Join-Path $lib "viaversion-common-$viaVersion.jar")
Get-Artifact "$viaRepo/com/viaversion/viabackwards-common/$viaVersion/viabackwards-common-$viaVersion.jar" (Join-Path $lib "viabackwards-common-$viaVersion.jar")
Get-Artifact "$viaRepo/com/viaversion/viarewind-common/$viaRewind/viarewind-common-$viaRewind.jar" (Join-Path $lib "viarewind-common-$viaRewind.jar")
Get-Artifact "$viaRepo/net/raphimc/ViaLegacy/$viaLegacy/ViaLegacy-$viaLegacy.jar" (Join-Path $lib "ViaLegacy-$viaLegacy.jar")

foreach ($artifact in @(
  "io/netty/netty-common/$netty/netty-common-$netty.jar",
  "io/netty/netty-buffer/$netty/netty-buffer-$netty.jar",
  "io/netty/netty-transport/$netty/netty-transport-$netty.jar",
  "io/netty/netty-codec/$netty/netty-codec-$netty.jar",
  "io/netty/netty-handler/$netty/netty-handler-$netty.jar",
  "io/netty/netty-resolver/$netty/netty-resolver-$netty.jar",
  "com/google/guava/guava/$guava/guava-$guava.jar",
  "com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar",
  "it/unimi/dsi/fastutil/$fastutil/fastutil-$fastutil.jar"
)) {
  $name = Split-Path $artifact -Leaf
  Get-Artifact "$maven/$artifact" (Join-Path $lib $name)
}

# A Guava this script used to pin, left behind, would sit on the class path beside the new one.
Get-ChildItem $lib -Filter "guava-*.jar" | Where-Object { $_.Name -ne "guava-$guava.jar" } | ForEach-Object {
  Write-Host "remove superseded $($_.Name)"
  try { Remove-Item $_.FullName }
  catch { throw "cannot remove superseded $($_.Name): a running JVM still has it open. Stop it and rerun." }
}

Write-Host "Via dependency fetch complete: $lib"
