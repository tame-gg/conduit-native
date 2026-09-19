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

# SHA-256 of every jar this script fetches, by file name: what Conduit was built and tested
# against, so a repository serving something else -- or a proxy or mirror in the way that rewrote
# it -- is caught here and not at the far end of a build. A version bump, -VelocityVersion
# included, means fetching the new jar, checking it, and putting its hash here beside the others.
$sha256 = @{
  "velocity-api-3.4.0.jar"                          = "58e9ceb57b2ace9070a34d65cd79d1fe066c2c247033f16e491f5439e612e58f"
  "velocity-brigadier-1.0.0-20210613.082804-10.jar" = "2d6646ed46d8052fc9f0d504309a0294d08a7c75d534fd84f11db0850cefdcb2"
  "slf4j-api-2.0.17.jar"                            = "7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832"
  "slf4j-jdk14-2.0.17.jar"                          = "ead25c1b15f59db1fb5552b76fe63de4164f0df40024d19287b75dece47ad3be"
  "javax.inject-1.jar"                              = "91c77044a50c481636c32d916fd89c9118a72195390452c81065080f957de7ff"
  "guice-6.0.0.jar"                                 = "b4d4f7ec5e8fc17b4f98dee9d3f6cf6ae3ae13e2e5ed4b2f7bbf09bc4bb675d5"
  "aopalliance-1.0.jar"                             = "0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08"
  "jakarta.inject-api-2.0.1.jar"                    = "f7dc98062fccf14126abb751b64fab12c312566e8cbdc8483598bffcea93af7c"
  "examination-api-1.3.0.jar"                       = "c9237ffecb05428f6eff86216246ac70ce0b47b04c08ea7ca35020fde57f8492"
  "examination-string-1.3.0.jar"                    = "7d01fc25a4bb3af0e1662685455f4541fbf4626216ea5846e455c1491e156b8c"
  "option-1.1.0.jar"                                = "97b69b4b17dfe02217c9131ad342564cbc9aebd04c75eb689639b5f78fd4b11c"
  "gson-2.11.0.jar"                                 = "57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b"
  "snakeyaml-1.33.jar"                              = "11ff459788f0a2d781f56a4a86d7e69202cebacd0273d5269c4ae9f02f3fd8f0"
  "configurate-core-4.1.2.jar"                      = "bd6a7bae8b01ccccc24119f6db32f5b1bf7ca33de6586f6d9b8ecd26afe146ac"
  "configurate-hocon-4.1.2.jar"                     = "c24410d1c4bf678a1e96c5e9b47cece307b37170d231a41e4a1787c42c93505f"
  "configurate-yaml-4.1.2.jar"                      = "01bc57ca7bf5995a6c085f71c6c3bb37809737f0bf423da99b4b8cf2721b732b"
  "configurate-gson-4.1.2.jar"                      = "ad2a0e0e01054cf516f915d2365cfe0b91b23d4f1b7e847f973d30c6b4a38397"
  "geantyref-1.3.11.jar"                            = "c26274474b3844bcdb7d83cf73a785366f84b453bc17a191cfa4f8fc3e022234"
  "typesafe-config-1.4.1.jar"                       = "4c0aa7e223c75c8840c41fc183d4cd3118140a1ee503e3e08ce66ed2794c948f"
  "night-config-core-3.8.4.jar"                     = "2d9532aa851742a6e2f3cb472a6a5b3c0e7e8b9da287c7d20a2963466f4aebb4"
  "night-config-toml-3.8.4.jar"                     = "127bf8b025c341139145c55bc897f3188057eec19940f99d3eae624399e88f2a"
  "caffeine-3.1.8.jar"                              = "7dd15f9df1be238ffaa367ce6f556737a88031de4294dad18eef57c474ddf1d3"
  "toml4j-0.7.2.jar"                                = "f5475e63e7e89e5db62223489aec7a56bd303543772077a17c2cb54c19ca3a20"
  "ansi-1.1.1.jar"                                  = "b6c569e1a0925b9eeb349e12db35e2237556107e3336657e0b2ebde261d8afd7"
  "adventure-api-4.26.1.jar"                        = "551e536b9ea868f30e72c7900a309b35124ee7d4889fa3b3aed0910299751a26"
  "adventure-key-4.26.1.jar"                        = "eec172d63db77b40eb7abeeb25f65eedea89bd30264d057b68b12fecb731be5e"
  "adventure-text-minimessage-4.26.1.jar"           = "1d43451e9af473252dc8af3e8084238d5ce68ad43af0e3b7383eb3d4b63fff9f"
  "adventure-text-serializer-plain-4.26.1.jar"      = "39b9bfe5790f645605ff5464382c0e062476f48f9bfb2548801f275f12faf405"
  "adventure-text-serializer-legacy-4.26.1.jar"     = "721107bc213572454df1bfbe438dba630e30457550c750b7a154b35dc3264aa8"
  "adventure-text-serializer-gson-4.26.1.jar"       = "e4a908dedc4acb4305083d916d362ddc2b21ecf452d577a0be665280bddae9fc"
  "adventure-text-serializer-json-4.26.1.jar"       = "55c64b4333d5d2968a0125b8f29dae7cfba15392d5f99f78dbaea711cb0d4dc2"
  "adventure-text-serializer-commons-4.26.1.jar"    = "66527fef559da4d91cda692491034f903d5b01e835de1b091edfbb6b51cee5a2"
  "adventure-text-serializer-ansi-4.26.1.jar"       = "79507c36685932166689423be279ea796db42b95d96da2a794cf9624280cb145"
  "adventure-text-logger-slf4j-4.26.1.jar"          = "009a99518d9b4f0ec54665f209724813bf98a2c1ff020aeabcf34a3699fd1dfa"
}

# A jar whose contents are not the ones pinned above is deleted rather than left for the build to
# pick up: a half-written download from an interrupted run looks exactly like this too.
function Confirm-Artifact([string]$path) {
  $name = Split-Path $path -Leaf
  $want = $sha256[$name]
  if (-not $want) { throw "no pinned SHA-256 for $name; add one to fetch-velocity-compat.ps1 before using it" }
  $got = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
  if ($got -eq $want) { return }
  Remove-Item $path -Force -ErrorAction SilentlyContinue
  throw "$name is not the jar Conduit pins: expected $want, got $got. The file has been removed."
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
    Confirm-Artifact $dest
    Write-Host "present $($artifact.Name)"
    continue
  }
  Write-Host "fetch $($artifact.Name)"
  try {
    Invoke-WebRequest -Uri $artifact.Url -OutFile $dest -UseBasicParsing -UserAgent $userAgent
  } catch {
    Remove-Item $dest -ErrorAction SilentlyContinue
    # velocity-brigadier is the one snapshot here: Velocity has never released its Brigadier fork,
    # and a snapshot can be swept from a repository one day.
    if ($artifact.Name -like "velocity-brigadier-*") {
      throw ("failed to download $($artifact.Url): $_`n" +
        "If it is gone from the repository, the same classes are inside any Velocity 3.4.0 proxy " +
        "jar: take com/mojang/brigadier/** out of one, zip it up as $dest, and rerun. The pinned " +
        "SHA-256 will say whether it is the jar Conduit was built against.")
    }
    throw "failed to download $($artifact.Url): $_"
  }
  Confirm-Artifact $dest
}

Copy-Item (Join-Path $root "lib\velocity-compat\velocity-api-meta.xml") (Join-Path $LibDir "velocity-api-meta.xml") -ErrorAction SilentlyContinue
Write-Host "Velocity compat libraries ready in $LibDir"
