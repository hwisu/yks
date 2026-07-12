plugins {
    kotlin("jvm") version "2.2.20"
    `maven-publish`
}

group = "dev.yks"
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")

val semanticVersionPattern = Regex(
    """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
)

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
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

tasks.named("check") {
    dependsOn("interopTest")
}

tasks.register<GradleBuild>("consumerSmokeTest") {
    description = "Publishes YKS to Maven Local and runs the standalone consumer smoke app."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn("publishToMavenLocal")
    dir = file("consumer-smoke")
    tasks = listOf("clean", "run")
    startParameter.projectProperties = mapOf("yksVersion" to project.version.toString())
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "yks"
            version = project.version.toString()

            pom {
                name.set("YKS")
                description.set("Kotlin/JVM implementation of the Yjs document model and update protocol.")
                url.set("https://github.com/hwisu/yks")
                developers {
                    developer {
                        id.set("hwisu")
                        name.set("hwisu")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/hwisu/yks.git")
                    developerConnection.set("scm:git:ssh://git@github.com/hwisu/yks.git")
                    url.set("https://github.com/hwisu/yks")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/hwisu/yks")
            credentials {
                username = project.providers.environmentVariable("GITHUB_ACTOR").orNull
                password = project.providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}

tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
    doFirst {
        val releaseVersion = project.providers.gradleProperty("releaseVersion").orNull
        check(releaseVersion != null && semanticVersionPattern.matches(releaseVersion)) {
            "Remote publication requires -PreleaseVersion=<SemVer>; got ${releaseVersion ?: "<missing>"}."
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}
