plugins {
  application
}

group = "gg.tame.conduit"
version = "0.1.0-SNAPSHOT"

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

application {
  mainClass.set("gg.tame.conduit.launcher.Main")
}
