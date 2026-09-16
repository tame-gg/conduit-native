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
}

val viaVersion = "5.11.0"
val viaRewind = "4.1.3"
val viaLegacy = "3.0.16"

dependencies {
  implementation("com.viaversion:viaversion-common:$viaVersion")
  implementation("com.viaversion:viabackwards-common:$viaVersion")
  implementation("com.viaversion:viarewind-common:$viaRewind")
  implementation("net.raphimc:ViaLegacy:$viaLegacy")
  implementation("io.netty:netty-all:4.1.118.Final")
  implementation("com.google.guava:guava:33.0.0-jre")
  implementation("it.unimi.dsi:fastutil:8.5.15")
}
