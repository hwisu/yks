plugins {
    kotlin("jvm") version "2.2.20"
}

group = "dev.yks"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("yjs-interop")
    }
}

tasks.register<Test>("interopTest") {
    description = "Runs cross-language compatibility tests against upstream Yjs fixtures."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("yjs-interop")
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}
