plugins {
    kotlin("jvm") version "2.3.20" apply false
    id("com.gradleup.shadow") version "9.4.0" apply false
    id("org.bxteam.runserver") version "1.2.2" apply false
    id("net.minecrell.plugin-yml.bukkit") version "0.6.0" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}
