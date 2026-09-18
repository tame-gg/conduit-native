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
# The Adventure version velocity-api's own POM imports (adventure-bom). Its signatures, such as
# CommandSource.sendRichMessage(String, TagResolver...), need these classes at run time.
$adventure = "4.26.1"
$artifacts = @(
  @{ Url = "$paper/com/velocitypowered/velocity-api/3.4.0-SNAPSHOT/velocity-api-$VelocityVersion.jar"; Name = "velocity-api-$VelocityVersion.jar" },
  @{ Url = "$central/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"; Name = "slf4j-api-2.0.16.jar" },
  # The SLF4J binding: plugin and library logging goes to java.util.logging, where Conduit's plugin loggers are.
  @{ Url = "$central/org/slf4j/slf4j-jdk14/2.0.16/slf4j-jdk14-2.0.16.jar"; Name = "slf4j-jdk14-2.0.16.jar" },
  @{ Url = "$central/javax/inject/javax.inject/1/javax.inject-1.jar"; Name = "javax.inject-1.jar" },
  # Guice for its annotations (@com.google.inject.Inject on real plugins); aopalliance is its dependency.
  @{ Url = "$central/com/google/inject/guice/6.0.0/guice-6.0.0.jar"; Name = "guice-6.0.0.jar" },
  @{ Url = "$central/aopalliance/aopalliance/1.0/aopalliance-1.0.jar"; Name = "aopalliance-1.0.jar" },
  @{ Url = "$central/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar"; Name = "examination-api-1.3.0.jar" },
  @{ Url = "$central/net/kyori/examination-string/1.3.0/examination-string-1.3.0.jar"; Name = "examination-string-1.3.0.jar" },
  @{ Url = "$central/net/kyori/option/1.1.0/option-1.1.0.jar"; Name = "option-1.1.0.jar" },
  @{ Url = "$central/com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar"; Name = "guava-33.3.1-jre.jar" },
  @{ Url = "$central/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar"; Name = "failureaccess-1.0.2.jar" },
  @{ Url = "$central/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"; Name = "gson-2.11.0.jar" },
  # Velocity gives its plugins SnakeYAML, at the version velocity-api's POM names. Keep that
  # version so plugins get what they compiled against.
  @{ Url = "$central/org/yaml/snakeyaml/1.33/snakeyaml-1.33.jar"; Name = "snakeyaml-1.33.jar" },
  @{ Url = "$paper/com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar"; Name = "brigadier-1.0.18.jar" }
)
foreach ($id in @("adventure-api", "adventure-key", "adventure-text-minimessage", "adventure-text-serializer-plain",
    "adventure-text-serializer-legacy", "adventure-text-serializer-gson", "adventure-text-serializer-json",
    "adventure-text-serializer-commons", "adventure-text-logger-slf4j")) {
  $artifacts += @{ Url = "$central/net/kyori/$id/$adventure/$id-$adventure.jar"; Name = "$id-$adventure.jar" }
}

# Artifacts this script used to fetch and no longer does. slf4j-nop was the old binding, and two
# bindings on one classpath is worse than either.
foreach ($retired in @("slf4j-nop")) {
  Get-ChildItem $LibDir -Filter "$retired-*.jar" | ForEach-Object {
    Write-Host "remove retired $($_.Name)"
    try { Remove-Item $_.FullName }
    catch { throw "cannot remove retired $($_.TargetObject): a running JVM still has it open. Stop it and rerun." }
  }
}

foreach ($artifact in $artifacts) {
  # test.ps1 and _classpath.ps1 put every lib/*.jar on the classpath, so an older version of the
  # same artifact left behind would sit next to this one. Remove it.
  $id = $artifact.Name -replace '-\d[^-]*(-.*)?\.jar$', ''
  Get-ChildItem $LibDir -Filter "$id-*.jar" |
    Where-Object { $_.Name -ne $artifact.Name -and $_.Name -match ('^' + [regex]::Escape($id) + '-\d') } |
    ForEach-Object {
      Write-Host "remove superseded $($_.Name)"
      try { Remove-Item $_.FullName }
      catch { throw "cannot remove superseded $($_.TargetObject): a running JVM still has it open. Stop it and rerun." }
    }
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
