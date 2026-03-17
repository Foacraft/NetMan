import org.bxteam.runserver.ServerType
import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
    id("net.minecrell.plugin-yml.bukkit")
    id("org.bxteam.runserver")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val minecraftVersion: String by project

dependencies {
    compileOnly("io.papermc.paper:paper-api:$minecraftVersion-R0.1-SNAPSHOT")
    compileOnly("io.netty:netty-all:4.1.87.Final")
    compileOnly(project(":agent"))
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(17)
}

bukkit {
    name = "NetMan"
    main = "com.foacraft.netman.NetManPlugin"
    apiVersion = "1.20"
    description = "Network traffic analysis and packet attribution for Paper servers"
    authors = listOf("FoaCraft")

    commands {
        register("networkanalyze") {
            description = "Network traffic analyzer"
            aliases = listOf("netanalyze", "na")
            permission = "netman.admin"
            usage = "/na <start|stop|status|confirm|cancel>"
        }
    }

    permissions {
        register("netman.admin") {
            description = "Full access to NetMan commands"
            default = BukkitPluginDescription.Permission.Default.OP
        }
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("kotlin", "com.foacraft.netman.shaded.kotlin")
    relocate("kotlinx", "com.foacraft.netman.shaded.kotlinx")
    exclude("META-INF/maven/**")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    archiveClassifier.set("original")
}

tasks.runServer {
    serverType(ServerType.PAPER)
    serverVersion(minecraftVersion)
    acceptMojangEula()
    noGui(true)

    inputTask(tasks.shadowJar)

    downloadPlugins {
        modrinth("tab-was-taken", "5.2.1")
        modrinth("essentialsx", "2.20.1")
        modrinth("decentholograms", "2.8.8")
    }

    val agentJar = project(":agent").tasks.jar.flatMap { it.archiveFile }
    dependsOn(project(":agent").tasks.jar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agentJar.get().asFile.absolutePath}")
    })
}
