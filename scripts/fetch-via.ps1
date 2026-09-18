# Fetch ViaVersion ecosystem + runtime libraries into lib/via
# Third-party artifacts, downloaded from upstream and never committed (lib/via is gitignored).
# Licenses: THIRD-PARTY-NOTICES and docs/LICENSING_VIA.md.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$lib = Join-Path $root "lib\via"
New-Item -ItemType Directory -Force -Path $lib | Out-Null

# Downloads name Conduit's development scripts and nothing else: no user, machine or path.
$userAgent = "Conduit-Development/0.9.0-SNAPSHOT"
$viaRepo = "https://repo.viaversion.com/everything"
$maven = "https://repo1.maven.org/maven2"

function Get-Artifact([string]$url, [string]$out) {
  if (Test-Path $out) {
    Write-Host "exists $(Split-Path $out -Leaf)"
    return
  }
  Write-Host "GET $url"
  Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing -UserAgent $userAgent
  Write-Host "OK $(Split-Path $out -Leaf) $((Get-Item $out).Length)"
}

$viaVersion = "5.11.0"
$viaRewind = "4.1.3"
$viaLegacy = "3.0.16"
$netty = "4.1.118.Final"
$guava = "33.0.0-jre"
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

Write-Host "Via dependency fetch complete: $lib"
