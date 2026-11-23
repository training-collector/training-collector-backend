plugins { kotlin("jvm") version "2.3.21" }

repositories { mavenCentral() }
kotlin { jvmToolchain(25) }
dependencies {
    testImplementation("com.tc:training-collector-backend:1.0.0")
    testImplementation("com.tc:training-collector-bot:1.0.0")
    testImplementation("com.tc:training-collector-auth-bot:1.0.0")
    testImplementation("dev.inmo:tgbotapi:24.0.2")
    testImplementation("io.ktor:ktor-server-netty:3.1.1")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.postgresql:postgresql:42.7.7")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    for (module in listOf("training-collector-bot", "training-collector-auth-bot")) {
        val bot = gradle.includedBuild(module)
        dependsOn(bot.task(":installDist"))
        systemProperty("$module.lib", bot.projectDir.resolve("build/install/$module/lib").absolutePath)
    }
}
