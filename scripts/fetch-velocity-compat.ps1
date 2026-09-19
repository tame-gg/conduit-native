param(
  [string]$LibDir = "",
  [string]$VelocityVersion = "3.4.0"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $LibDir) { $LibDir = Join-Path $root "lib" }
New-Item -ItemType Directory -Path $LibDir -Force | Out-Null

# Downloads name Conduit's development scripts and nothing else: no user, machine or path.
$userAgent = "Conduit-Development/0.9.0-SNAPSHOT"
$paper = "https://repo.papermc.io/repository/maven-public"
$central = "https://repo1.maven.org/maven2"
# The Adventure version velocity-api's own POM imports (adventure-bom). Its signatures, such as
# CommandSource.sendRichMessage(String, TagResolver...), need these classes at run time.
$adventure = "4.26.1"
$artifacts = @(
  # The 3.4.0 release, whose classes are the ones the last 3.4.0 snapshot had.
  @{ Url = "$paper/com/velocitypowered/velocity-api/$VelocityVersion/velocity-api-$VelocityVersion.jar"; Name = "velocity-api-$VelocityVersion.jar" },
  @{ Url = "$central/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar"; Name = "slf4j-api-2.0.17.jar" },
  # The SLF4J binding: plugin and library logging goes to java.util.logging, where Conduit's plugin loggers are.
  @{ Url = "$central/org/slf4j/slf4j-jdk14/2.0.17/slf4j-jdk14-2.0.17.jar"; Name = "slf4j-jdk14-2.0.17.jar" },
  @{ Url = "$central/javax/inject/javax.inject/1/javax.inject-1.jar"; Name = "javax.inject-1.jar" },
  # Guice: its annotations (@com.google.inject.Inject on real plugins), and the Injector a plugin can
  # ask for. aopalliance and jakarta.inject-api are the dependencies it needs at run time.
  @{ Url = "$central/com/google/inject/guice/6.0.0/guice-6.0.0.jar"; Name = "guice-6.0.0.jar" },
  @{ Url = "$central/aopalliance/aopalliance/1.0/aopalliance-1.0.jar"; Name = "aopalliance-1.0.jar" },
  @{ Url = "$central/jakarta/inject/jakarta.inject-api/2.0.1/jakarta.inject-api-2.0.1.jar"; Name = "jakarta.inject-api-2.0.1.jar" },
  @{ Url = "$central/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar"; Name = "examination-api-1.3.0.jar" },
  @{ Url = "$central/net/kyori/examination-string/1.3.0/examination-string-1.3.0.jar"; Name = "examination-string-1.3.0.jar" },
  @{ Url = "$central/net/kyori/option/1.1.0/option-1.1.0.jar"; Name = "option-1.1.0.jar" },
  @{ Url = "$central/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"; Name = "gson-2.11.0.jar" },
  # Velocity gives its plugins SnakeYAML, at the version velocity-api's POM names. Keep that
  # version so plugins get what they compiled against.
  @{ Url = "$central/org/yaml/snakeyaml/1.33/snakeyaml-1.33.jar"; Name = "snakeyaml-1.33.jar" },
  # Configurate 4, as velocity-api's POM names it (plugins such as TitleAnnouncer load their HOCON
  # config with it), with what its POMs need at run time: geantyref, and Typesafe Config for HOCON.
  @{ Url = "$central/org/spongepowered/configurate-core/4.1.2/configurate-core-4.1.2.jar"; Name = "configurate-core-4.1.2.jar" },
  @{ Url = "$central/org/spongepowered/configurate-hocon/4.1.2/configurate-hocon-4.1.2.jar"; Name = "configurate-hocon-4.1.2.jar" },
  @{ Url = "$central/org/spongepowered/configurate-yaml/4.1.2/configurate-yaml-4.1.2.jar"; Name = "configurate-yaml-4.1.2.jar" },
  @{ Url = "$central/org/spongepowered/configurate-gson/4.1.2/configurate-gson-4.1.2.jar"; Name = "configurate-gson-4.1.2.jar" },
  @{ Url = "$central/io/leangen/geantyref/geantyref/1.3.11/geantyref-1.3.11.jar"; Name = "geantyref-1.3.11.jar" },
  @{ Url = "$central/com/typesafe/config/1.4.1/config-1.4.1.jar"; Name = "typesafe-config-1.4.1.jar" },
  # Velocity's proxy also carries night-config's TOML reader, which velocity-api's POM does not name,
  # and plugins such as ForcePack compile against it without bundling it.
  @{ Url = "$central/com/electronwill/night-config/core/3.8.4/core-3.8.4.jar"; Name = "night-config-core-3.8.4.jar" },
  @{ Url = "$central/com/electronwill/night-config/toml/3.8.4/toml-3.8.4.jar"; Name = "night-config-toml-3.8.4.jar" },
  # The rest of velocity-api's POM at run time: its own fork of Brigadier, which has methods
  # Mojang's 1.0.18 lacks (requiresWithContext, removeChildByName, ...), Caffeine and toml4j.
  # Guava is not here: the one in lib/via (fetch-via.ps1) serves both.
  @{ Url = "$paper/com/velocitypowered/velocity-brigadier/1.0.0-SNAPSHOT/velocity-brigadier-1.0.0-20210613.082804-10.jar"; Name = "velocity-brigadier-1.0.0-20210613.082804-10.jar" },
  @{ Url = "$central/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar"; Name = "caffeine-3.1.8.jar" },
  @{ Url = "$central/com/moandjiezana/toml/toml4j/0.7.2/toml4j-0.7.2.jar"; Name = "toml4j-0.7.2.jar" },
  @{ Url = "$central/net/kyori/ansi/1.1.1/ansi-1.1.1.jar"; Name = "ansi-1.1.1.jar" }
)
foreach ($id in @("adventure-api", "adventure-key", "adventure-text-minimessage", "adventure-text-serializer-plain",
    "adventure-text-serializer-legacy", "adventure-text-serializer-gson", "adventure-text-serializer-json",
    "adventure-text-serializer-commons", "adventure-text-serializer-ansi", "adventure-text-logger-slf4j")) {
  $artifacts += @{ Url = "$central/net/kyori/$id/$adventure/$id-$adventure.jar"; Name = "$id-$adventure.jar" }
}

# Artifacts this script used to fetch and no longer does. Mojang's brigadier gave way to Velocity's
# fork, and Guava to the copy in lib/via. slf4j-nop was the old binding, and two
# bindings on one classpath is worse than either.
foreach ($retired in @("slf4j-nop", "brigadier", "guava", "failureaccess")) {
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
    Invoke-WebRequest -Uri $artifact.Url -OutFile $dest -UseBasicParsing -UserAgent $userAgent
  } catch {
    Remove-Item $dest -ErrorAction SilentlyContinue
    throw "failed to download $($artifact.Url): $_"
  }
}

Copy-Item (Join-Path $root "lib\velocity-compat\velocity-api-meta.xml") (Join-Path $LibDir "velocity-api-meta.xml") -ErrorAction SilentlyContinue
Write-Host "Velocity compat libraries ready in $LibDir"
