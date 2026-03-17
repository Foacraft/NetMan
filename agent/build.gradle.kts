plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "com.foacraft.netman.agent.NetManAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Boot-Class-Path" to "${archiveFileName.get()} netman-agent-${project.version}.jar"
        )
    }
    // Bundle ASM into the agent jar (agent is loaded standalone)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
