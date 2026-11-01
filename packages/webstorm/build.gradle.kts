import java.io.File

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform")
}

group = "com.fsodano"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

fun localWebStormPath(): String {
    val configured = providers.gradleProperty("webstormPath").orNull
        ?: providers.environmentVariable("WEBSTORM_HOME").orNull
    val candidates = listOfNotNull(
        configured,
        "${System.getProperty("user.home")}/Applications/WebStorm.app",
        "/Applications/WebStorm.app",
    )
    return candidates.firstOrNull { File(it).isDirectory }
        ?: error("WebStorm not found. Pass -PwebstormPath=/path/to/WebStorm.app")
}

dependencies {
    intellijPlatform {
        local(localWebStormPath())
        bundledPlugin("com.intellij.modules.jcef")
    }
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginVerification {
        ides {
            local(localWebStormPath())
        }
    }

    pluginConfiguration {
        id = "com.fsodano.openchamber"
        name = "OpenChamber"
        version = "0.1.0"
        description = """
            Runs OpenChamber inside WebStorm and connects OpenCode to WebStorm's
            built-in MCP server, so the agent can use the IDE's inspections,
            symbol search, refactorings and run configurations.
        """.trimIndent()

        vendor {
            name = "fsodano"
            url = "https://github.com/fsodano"
        }

        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
