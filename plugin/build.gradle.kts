plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    // Netty is bundled in the Paper server JAR at runtime; add as compileOnly for compilation
    compileOnly("io.netty:netty-all:4.1.87.Final")
    // Agent module: compileOnly so the plugin can reference PacketAttachmentStore at compile time.
    // At runtime, the agent jar is loaded via -javaagent so its classes are in the app classloader.
    compileOnly(project(":agent"))
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Relocate bundled kotlin stdlib to avoid conflicts with server's kotlin
    relocate("kotlin", "com.foacraft.netman.shaded.kotlin")
    relocate("kotlinx", "com.foacraft.netman.shaded.kotlinx")
    exclude("META-INF/maven/**")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Ensure the plain jar task doesn't overwrite the shadow jar
tasks.jar {
    archiveClassifier.set("original")
}
