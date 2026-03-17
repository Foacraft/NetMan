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

tasks.register<Copy>("copyArtifacts") {
    dependsOn(":plugin:shadowJar", ":agent:jar")

    from(project(":plugin").tasks.named("shadowJar")) {
        rename { "netman-${project.version}.jar" }
    }
    from(project(":agent").tasks.named("jar")) {
        rename { "netman-agent-${project.version}.jar" }
    }
    into(layout.buildDirectory)

    // Always copy, skip UP-TO-DATE check
    outputs.upToDateWhen { false }
}

tasks.register("build") {
    dependsOn("copyArtifacts")
}

// Also copy artifacts when submodules build
subprojects {
    tasks.configureEach {
        if (name == "build") {
            finalizedBy(rootProject.tasks.named("copyArtifacts"))
        }
    }
}
