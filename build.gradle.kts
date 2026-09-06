plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.isFork = true
}
tasks.test {
    useJUnitPlatform()
    // Test isolation: use build-specific directories for test home and config
    systemProperty("aiagent.test.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    systemProperty("aiagent.test.config", layout.buildDirectory.dir("test-config").get().asFile.absolutePath)
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        showExceptions = true
        showStackTraces = true
    }
}
group = "com.aiagent.chat"
version = "0.50.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        clion("2024.2")
        bundledPlugin("com.intellij.clion")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.intellij.plugins.markdown")
        instrumentationTools()
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.0")
}

kotlin {
    jvmToolchain(21)
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
intellijPlatform {
    pluginConfiguration {
        id = "com.aiagent.chat"
        name = "AI Agent Chat"
        vendor {
            name = "Huawei"
            email = "support@huawei.com"
            url = "https://huawei.com"
        }
        description = "Agentic AI chat for OpenAI-compatible APIs with file and shell tools."
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242.0"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            ide("CL-2024.2")
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.named("runIde") {
    doFirst {
        println("in run Ide, clearing logFile")
        val sandboxBase = layout.buildDirectory.dir("idea-sandbox").get().asFile
        if (sandboxBase.exists()) {
            sandboxBase.walkTopDown()
                .filter { it.name == "idea.log" }
                .forEach { logFile ->
                    println("Deleting old log: ${logFile.absolutePath}")
                    logFile.delete()
                }
        }
    }
}
