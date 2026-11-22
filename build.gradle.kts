plugins {
    application
    kotlin("jvm") version "2.3.21"
}

group = "com.tc"
version = "1.0.0"
repositories { mavenCentral() }
kotlin { jvmToolchain(25) }
application { mainClass.set("com.tc.gymtrainer.AppKt") }

tasks.test { useJUnitPlatform() }

tasks.named<JavaExec>("run") {
    val localConfig = layout.projectDirectory.file(".env").asFile
    doFirst {
        if (localConfig.isFile) localConfig.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val key = line.substringBefore('=')
            require('=' in line && key.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "Invalid .env entry" }
            if (key !in environment) environment(key, line.substringAfter('='))
        }
    }
}

val ktorVersion = "3.1.1"
val exposedVersion = "0.61.0"
val flywayVersion = "11.8.2"

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // HTTP API
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Database: Exposed + Postgres + pooling + migrations
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // Config + logging
    implementation("com.typesafe:config:1.4.3")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Tests
    testImplementation("org.testcontainers:postgresql:1.21.4")
}

tasks.test {
    dependsOn(tasks.installDist)
    systemProperty("training.dist.lib", layout.buildDirectory.dir("install/${project.name}/lib").get().asFile.absolutePath)
    systemProperty("training.dist.main", application.mainClass.get())
}
