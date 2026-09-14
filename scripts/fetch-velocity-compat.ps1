param(
  [string]$LibDir = "",
  [string]$VelocityVersion = "3.4.0-20260121.190037-118"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $LibDir) { $LibDir = Join-Path $root "lib" }
New-Item -ItemType Directory -Path $LibDir -Force | Out-Null

$paper = "https://repo.papermc.io/repository/maven-public"
$central = "https://repo1.maven.org/maven2"
$artifacts = @(
  @{ Url = "$paper/com/velocitypowered/velocity-api/3.4.0-SNAPSHOT/velocity-api-$VelocityVersion.jar"; Name = "velocity-api-$VelocityVersion.jar" },
  @{ Url = "$central/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"; Name = "slf4j-api-2.0.16.jar" },
  @{ Url = "$central/org/slf4j/slf4j-nop/2.0.16/slf4j-nop-2.0.16.jar"; Name = "slf4j-nop-2.0.16.jar" },
  @{ Url = "$central/javax/inject/javax.inject/1/javax.inject-1.jar"; Name = "javax.inject-1.jar" },
  @{ Url = "$central/net/kyori/adventure-api/4.17.0/adventure-api-4.17.0.jar"; Name = "adventure-api-4.17.0.jar" },
  @{ Url = "$central/net/kyori/adventure-key/4.17.0/adventure-key-4.17.0.jar"; Name = "adventure-key-4.17.0.jar" },
  @{ Url = "$central/net/kyori/adventure-text-serializer-plain/4.17.0/adventure-text-serializer-plain-4.17.0.jar"; Name = "adventure-text-serializer-plain-4.17.0.jar" },
  @{ Url = "$central/net/kyori/adventure-text-serializer-gson/4.17.0/adventure-text-serializer-gson-4.17.0.jar"; Name = "adventure-text-serializer-gson-4.17.0.jar" },
  @{ Url = "$central/net/kyori/adventure-text-serializer-legacy/4.17.0/adventure-text-serializer-legacy-4.17.0.jar"; Name = "adventure-text-serializer-legacy-4.17.0.jar" },
  @{ Url = "$central/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar"; Name = "examination-api-1.3.0.jar" },
  @{ Url = "$central/net/kyori/examination-string/1.3.0/examination-string-1.3.0.jar"; Name = "examination-string-1.3.0.jar" },
  @{ Url = "$central/com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar"; Name = "guava-33.3.1-jre.jar" },
  @{ Url = "$central/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar"; Name = "failureaccess-1.0.2.jar" },
  @{ Url = "$central/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"; Name = "gson-2.11.0.jar" },
  @{ Url = "$paper/com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar"; Name = "brigadier-1.0.18.jar" }
)

foreach ($artifact in $artifacts) {
  $dest = Join-Path $LibDir $artifact.Name
  if (Test-Path $dest) {
    Write-Host "present $($artifact.Name)"
    continue
  }
  Write-Host "fetch $($artifact.Name)"
  try {
    Invoke-WebRequest -Uri $artifact.Url -OutFile $dest -UseBasicParsing
  } catch {
    Remove-Item $dest -ErrorAction SilentlyContinue
    throw "failed to download $($artifact.Url): $_"
  }
}

Copy-Item (Join-Path $root "lib\velocity-compat\velocity-api-meta.xml") (Join-Path $LibDir "velocity-api-meta.xml") -ErrorAction SilentlyContinue
Write-Host "Velocity compat libraries ready in $LibDir"
