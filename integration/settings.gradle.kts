rootProject.name = "training-dialog-integration"
includeBuild("..")
includeBuild(providers.gradleProperty("trainingBotDir").get())
includeBuild(providers.gradleProperty("authBotDir").get())
