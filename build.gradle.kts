import java.security.MessageDigest

plugins {
  application
}

group = "gg.tame.conduit"
version = "0.9.8-SNAPSHOT"

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

application {
  // The bootstrap, as in the release jar: the distribution carries no Via, and it is what installs
  // the pinned set into lib/via beside conduit.toml on the first start.
  mainClass.set("gg.tame.conduit.boot.Bootstrap")
}

repositories {
  mavenCentral()
  maven("https://repo.viaversion.com")
  // velocity-api and its own Brigadier fork, which is published nowhere else.
  maven("https://repo.papermc.io/repository/maven-public")
}

val viaVersion = "5.12.0"
val viaRewind = "4.2.0"
val viaLegacy = "3.1.0"
val velocityVersion = "3.4.0"

// The Velocity-compatibility adapter is built with the rest, not as a source set of its own: the
// distribution is the whole install, and one that cannot load a Velocity plugin is a broken build
// rather than a feature left out. scripts/build-jar.ps1 ships the same classes in its single jar,
// and scripts/test.ps1 still compiles src/main on its own to keep core free of velocity-api.
sourceSets.main { java.srcDir("src/compat-velocity/java") }

// What a Velocity plugin links against at run time, and the one statement of that set. velocity-api's
// POM brings most of it -- Adventure, Brigadier, Guice, Gson, SnakeYAML, Configurate, Caffeine,
// toml4j -- and resolving it here gets the transitives with it, rather than a hand-written list of
// file names that goes stale the first time one of those POMs changes. `./gradlew lockVelocityRuntime`
// writes the resolved files to config/velocity-runtime.lock, and that lock is what the release build
// fetches and merges into the jar. Nothing reads a developer's lib/ to decide what a release needs.
val velocityRuntime = configurations.create("velocityRuntime") {
  isCanBeConsumed = false
}
// Guava, and the failureaccess and listenablefuture stubs that come with it, are already in the
// Via set at the newer version both ask for. Two copies of a library in one jar is one silently
// shadowing the other, which the jar build refuses outright. The exclude goes on each dependency
// and not on velocityRuntime itself: a configuration's excludes apply to every configuration that
// extends it, so one there also took the Guava that implementation names directly, and the adapter,
// which uses it, stopped compiling under Gradle.
fun ExternalModuleDependency.withoutGuava() { exclude(group = "com.google.guava") }
// The adapter compiles, and a Gradle run runs, against the same set the release jar ships.
configurations.named("implementation") { extendsFrom(velocityRuntime) }

dependencies {
  velocityRuntime("com.velocitypowered:velocity-api:$velocityVersion") { withoutGuava() }
  // night-config's TOML reader, which Velocity's proxy carries and its POM does not name.
  velocityRuntime("com.electronwill.night-config:toml:3.8.4") { withoutGuava() }
  // An SLF4J binding, so plugin and library logging lands in java.util.logging with Conduit's own.
  velocityRuntime("org.slf4j:slf4j-jdk14:2.0.17") { withoutGuava() }
  // The Gson the Velocity plugins were tested against; velocity-api's POM asks for an older one,
  // and nothing else here raises it.
  velocityRuntime("com.google.code.gson:gson:2.11.0") { withoutGuava() }
}

// The files velocityRuntime resolves to, pinned by SHA-256: one `<repository path>\t<sha256>` a line.
// scripts/fetch-velocity-compat.ps1 fetches exactly this, and scripts/build-jar.ps1 merges exactly
// this, so changing a version means editing velocityRuntime above and running this task -- never
// editing a file name anywhere else.
val lockVelocityRuntime = tasks.register("lockVelocityRuntime") {
  val artifacts = velocityRuntime.incoming.artifacts
  val lock = layout.projectDirectory.file("config/velocity-runtime.lock")
  outputs.file(lock)
  doLast {
    val lines = artifacts.artifacts.map { artifact ->
      repositoryPath(artifact.variant.owner.toString(), artifact.file) + "\t" + sha256(artifact.file)
    }.sorted()
    lock.asFile.parentFile.mkdirs()
    lock.asFile.writeText(
      ("# Written by `./gradlew lockVelocityRuntime` from the velocityRuntime configuration in\n"
        + "# build.gradle.kts. Do not edit by hand. <repository path>\\t<sha256>.\n"
        + lines.joinToString("\n", postfix = "\n")).replace("\n", System.lineSeparator())
    )
    logger.lifecycle("wrote ${lines.size} artifacts to ${lock.asFile}")
  }
}

/**
 * Where a repository serves an artifact, as `group/name/version/file`.
 *
 * `coordinate` is `group:name:version`, and for a unique snapshot a fourth field holding the
 * timestamp Maven puts in the file name. Gradle caches such a jar under its plain `-SNAPSHOT` name,
 * which is not the name the repository serves, so that name is rebuilt from the timestamp.
 * velocity-brigadier is the one snapshot here: Velocity has never released its Brigadier fork.
 */
fun repositoryPath(coordinate: String, cached: File): String {
  val parts = coordinate.split(':')
  check(parts.size == 3 || parts.size == 4) { "cannot place $coordinate in a repository" }
  val (group, name, version) = parts
  val fileName = if (parts.size == 4) "$name-${version.removeSuffix("SNAPSHOT")}${parts[3]}.jar" else cached.name
  return group.replace('.', '/') + "/$name/$version/$fileName"
}

fun sha256(file: File): String =
  MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

dependencies {
  implementation("com.viaversion:viaversion-common:$viaVersion")
  implementation("com.viaversion:viabackwards-common:$viaVersion")
  implementation("com.viaversion:viarewind-common:$viaRewind")
  implementation("net.raphimc:ViaLegacy:$viaLegacy")
  implementation("io.netty:netty-all:4.1.118.Final")
  implementation("com.google.guava:guava:33.3.1-jre")
  implementation("it.unimi.dsi:fastutil:8.5.15")
  // The Velocity plugin runtime is declared in velocityRuntime above, which implementation extends.
}

// A distribution (distZip, distTar, installDist) conveys Conduit's GPL object code and carries its
// Corresponding Source in the same archive, which meets GPLv3 section 6 with no written offer to
// honour afterwards. Via is a compile dependency but is not shipped, the same as in the release jar:
// the bootstrap installs it on first start. See docs/LICENSING_VIA.md.
// File-name prefixes of what is left out: the five Via jars, and the com.seedfinding mc_* jars that
// only ViaLegacy pulls in, which neither the release jar nor the bootstrap's install carries either.
val viaJars = listOf("viaversion-api-", "viaversion-common-", "viabackwards-common-", "viarewind-common-", "ViaLegacy-", "mc_")
tasks.startScripts {
  val via = viaJars // a local copy: the configuration cache cannot serialize the script itself
  classpath = classpath!!.filter { jar -> via.none { jar.name.startsWith(it) } }
}

distributions {
  main {
    contents {
      exclude(viaJars.map { "**/$it*.jar" })
      from("LICENSE", "THIRD-PARTY-NOTICES")
      into("source/conduit") {
        from(rootDir) {
          include("src/**", "scripts/**", "tools/**", "config/**", "docs/**", "lib/README.md",
            "gradle/wrapper/**", "gradlew", "gradlew.bat",
            "build.gradle.kts", "settings.gradle.kts", "README.md", "LICENSE", "THIRD-PARTY-NOTICES")
          exclude("**/__pycache__/**")
        }
      }
    }
  }
}
