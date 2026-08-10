plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val yksVersion = providers.gradleProperty("yksVersion").getOrElse("0.2.7-SNAPSHOT")

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("dev.yks:yks:$yksVersion")
}

application {
    mainClass.set("ConsumerSmokeKt")
}
