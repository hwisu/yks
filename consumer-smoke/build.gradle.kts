plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val yksVersion = providers.gradleProperty("yksVersion").getOrElse("0.2.15-SNAPSHOT")
val consumerKotlinVersion = providers.gradleProperty("consumerKotlinVersion").getOrElse("2.4.21")

kotlin {
    jvmToolchain(21)
    // Keep the legacy compiler check without loading its vulnerable Gradle plugin.
    @OptIn(
        org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class,
        org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class,
    )
    compilerVersion.set(consumerKotlinVersion)
}

// Compiler plugins must match the selected compiler, not the Gradle plugin.
configurations.matching { it.name.startsWith("kotlinCompilerPluginClasspath") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-scripting-")) {
            useVersion(consumerKotlinVersion)
        }
    }
}

dependencies {
    implementation("dev.yks:yks:$yksVersion")
}

application {
    mainClass.set("ConsumerSmokeKt")
}
