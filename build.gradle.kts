plugins {
    kotlin("jvm") version "1.9.23" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

allprojects {
    group = "com.foacraft.netman"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}
