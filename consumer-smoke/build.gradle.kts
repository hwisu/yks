plugins {
    kotlin("jvm") version "2.2.20"
    application
}

val yksVersion = providers.gradleProperty("yksVersion").getOrElse("0.1.0-SNAPSHOT")

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("dev.yks:yks:$yksVersion")
}

application {
    mainClass.set("ConsumerSmokeKt")
}
