import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.2.20"
    `maven-publish`
}

group = "dev.yks"
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.1-SNAPSHOT")

val semanticVersionPattern = Regex(
    """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
)

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

val legalNoticeFiles = listOf("LICENSE", "THIRD_PARTY_NOTICES")

tasks.withType<Jar>().configureEach {
    from(legalNoticeFiles.map { rootProject.file(it) }) {
        into("META-INF")
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("yjs-interop", "yrs-interop")
    }
}

tasks.register<Test>("interopTest") {
    description = "Runs cross-language compatibility tests against upstream Yjs and Yrs fixtures."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("yks.projectDirectory", rootProject.projectDir.absolutePath)
    useJUnitPlatform {
        includeTags("yjs-interop", "yrs-interop")
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
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/hwisu/yks/blob/main/LICENSE")
                        distribution.set("repo")
                    }
                }
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

val publicationMetadataTest = tasks.register("publicationMetadataTest") {
    description = "Verifies license metadata in the Maven POM and published JARs."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn("generatePomFileForMavenJavaPublication", "jar", "sourcesJar")

    doLast {
        val expectedNotices = legalNoticeFiles.associateWith { rootProject.file(it).readBytes() }
        val pomFile = layout.buildDirectory.file("publications/mavenJava/pom-default.xml").get().asFile
        val pomText = pomFile.readText()
        listOf(
            "<name>MIT License</name>",
            "<url>https://github.com/hwisu/yks/blob/main/LICENSE</url>",
            "<distribution>repo</distribution>",
        ).forEach { fragment ->
            check(fragment in pomText) {
                "Generated Maven POM is missing license metadata: $fragment"
            }
        }

        listOf(
            tasks.named<Jar>("jar").get().archiveFile.get().asFile,
            tasks.named<Jar>("sourcesJar").get().archiveFile.get().asFile,
        ).forEach { artifact ->
            ZipFile(artifact).use { archive ->
                expectedNotices.forEach { (name, expectedBytes) ->
                    val path = "META-INF/$name"
                    val entry = checkNotNull(archive.getEntry(path)) {
                        "${artifact.name} is missing $path"
                    }
                    val actualBytes = archive.getInputStream(entry).use { it.readBytes() }
                    check(actualBytes.contentEquals(expectedBytes)) {
                        "${artifact.name} contains stale $path"
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(publicationMetadataTest)
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
