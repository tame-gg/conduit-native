import java.net.URI

plugins {
  application
}

group = "gg.tame.conduit"
version = "0.9.0-SNAPSHOT"

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

application {
  mainClass.set("gg.tame.conduit.launcher.Main")
}

repositories {
  mavenCentral()
  maven("https://repo.viaversion.com")
  // velocity-api and its own Brigadier fork, which is published nowhere else.
  maven("https://repo.papermc.io/repository/maven-public")
}

val viaVersion = "5.11.0"
val viaRewind = "4.1.3"
val viaLegacy = "3.0.16"
val velocityVersion = "3.4.0"

// The Velocity-compatibility adapter is built with the rest, not as a source set of its own: the
// distribution is the whole install, and one that cannot load a Velocity plugin is a broken build
// rather than a feature left out. scripts/build-jar.ps1 ships the same classes in its single jar,
// and scripts/test.ps1 still compiles src/main on its own to keep core free of velocity-api.
sourceSets.main { java.srcDir("src/compat-velocity/java") }

dependencies {
  implementation("com.viaversion:viaversion-common:$viaVersion")
  implementation("com.viaversion:viabackwards-common:$viaVersion")
  implementation("com.viaversion:viarewind-common:$viaRewind")
  implementation("net.raphimc:ViaLegacy:$viaLegacy")
  implementation("io.netty:netty-all:4.1.118.Final")
  implementation("com.google.guava:guava:33.3.1-jre")
  implementation("it.unimi.dsi:fastutil:8.5.15")
  // velocity-api's POM brings what a Velocity plugin links against: Adventure, Brigadier, Guice,
  // Gson, SnakeYAML, Configurate, Caffeine, toml4j. What it leaves out, a plugin still expects:
  implementation("com.velocitypowered:velocity-api:$velocityVersion")
  // night-config's TOML reader, which Velocity's proxy carries and its POM does not name.
  runtimeOnly("com.electronwill.night-config:toml:3.8.4")
  // An SLF4J binding, so plugin and library logging lands in java.util.logging with Conduit's own.
  runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
  // The Gson the jar build ships, which is the one the Velocity plugins were tested against;
  // velocity-api's POM asks for an older one, and nothing else here raises it.
  implementation("com.google.code.gson:gson:2.11.0")
}

// A distribution (distZip, distTar, installDist) conveys GPL object code: Conduit itself and the Via
// jars. It carries its own Corresponding Source inside the same archive, which meets GPLv3 section 6
// with no written offer to honour afterwards. See docs/LICENSING_VIA.md.
// ponytail: GitHub tag archives are trusted as-is, with no pinned hash; pin SHA-256s if a release
// pipeline needs reproducible archives.
val gplSources = mapOf(
  "ViaVersion-$viaVersion" to "https://github.com/ViaVersion/ViaVersion/archive/refs/tags/$viaVersion.zip",
  "ViaBackwards-$viaVersion" to "https://github.com/ViaVersion/ViaBackwards/archive/refs/tags/$viaVersion.zip",
  "ViaRewind-$viaRewind" to "https://github.com/ViaVersion/ViaRewind/archive/refs/tags/$viaRewind.zip",
  "ViaLegacy-$viaLegacy" to "https://github.com/ViaVersion/ViaLegacy/archive/refs/tags/v$viaLegacy.zip",
)
val fetchViaSource = tasks.register("fetchViaSource") {
  val target = layout.buildDirectory.dir("corresponding-source")
  val sources = gplSources // a local copy: the configuration cache cannot serialize the script itself
  // Downloads name Conduit's build and nothing else: no user, machine or path.
  val userAgent = "Conduit-Development/$version"
  inputs.property("sources", sources)
  outputs.dir(target)
  doLast {
    sources.forEach { (name, url) ->
      val file = target.get().file("$name.zip").asFile
      val part = File(file.path + ".part")
      file.parentFile.mkdirs()
      val connection = URI(url).toURL().openConnection()
      connection.setRequestProperty("User-Agent", userAgent)
      connection.getInputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
      if (!part.renameTo(file) && !(file.delete() && part.renameTo(file))) throw GradleException("cannot write $file")
    }
  }
}

distributions {
  main {
    contents {
      from("LICENSE", "THIRD-PARTY-NOTICES")
      into("source/conduit") {
        from(rootDir) {
          include("src/**", "scripts/**", "tools/**", "config/**", "docs/**", "lib/README.md",
            "build.gradle.kts", "settings.gradle.kts", "README.md", "LICENSE", "THIRD-PARTY-NOTICES")
          exclude("**/__pycache__/**")
        }
      }
      into("source/third-party") { from(fetchViaSource) }
    }
  }
}
