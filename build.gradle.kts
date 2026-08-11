import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.2.20"
    `maven-publish`
}

group = "dev.yks"
version = providers.gradleProperty("releaseVersion").getOrElse("0.2.8-SNAPSHOT")

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}

val semanticVersionPattern = Regex(
    """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
)

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }
}

java {
    withSourcesJar()
}

val buildRevision = providers.gradleProperty("buildRevision").getOrElse("uncommitted")

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val legalNoticeFiles = listOf("LICENSE", "THIRD_PARTY_NOTICES")

tasks.withType<Jar>().configureEach {
    manifest.attributes(
        "Implementation-Version" to project.version.toString(),
        "YKS-Revision" to buildRevision,
    )
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

val consumerSmokeTest = tasks.register<GradleBuild>("consumerSmokeTest") {
    description = "Publishes YKS to Maven Local and runs the baseline standalone consumer."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn("publishToMavenLocal")
    dir = file("consumer-smoke")
    tasks = listOf("clean", "run")
    startParameter.projectProperties = mapOf(
        "consumerKotlinVersion" to "2.2.20",
        "yksVersion" to project.version.toString(),
    )
}

val consumerKotlinCompatibilityTest = tasks.register<Exec>("consumerKotlinCompatibilityTest") {
    description = "Consumes the published YKS artifact with Norric's Kotlin compiler baseline."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn("publishToMavenLocal")
    mustRunAfter(consumerSmokeTest)
    workingDir = file("consumer-smoke")
    commandLine(
        rootProject.file("gradlew").absolutePath,
        "clean",
        "run",
        "--no-daemon",
        "-PconsumerKotlinVersion=2.3.21",
        "-PyksVersion=${project.version}",
    )
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
                    tag.set(buildRevision)
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
        check(buildRevision.matches(Regex("^[0-9a-f]{40}$"))) {
            "Remote publication requires -PbuildRevision=<40-character Git SHA>; got '$buildRevision'."
        }
    }
}

val performanceSourceSet = sourceSets.create("performance")
performanceSourceSet.compileClasspath += sourceSets["main"].output
performanceSourceSet.runtimeClasspath += sourceSets["main"].output
configurations[performanceSourceSet.implementationConfigurationName]
    .extendsFrom(configurations["implementation"])
configurations[performanceSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations["runtimeOnly"])

tasks.register<JavaExec>("performanceBenchmark") {
    description = "Runs warmed YKS scenarios used by the Yjs parity benchmark."
    group = "benchmark"
    dependsOn(performanceSourceSet.classesTaskName)
    classpath = performanceSourceSet.runtimeClasspath
    mainClass.set("dev.yks.benchmark.PerformanceBenchmarkKt")
    jvmArgs("-Xms512m", "-Xmx512m", "-XX:+UseG1GC")
    doFirst {
        args(
            providers.gradleProperty("performanceFixture").get(),
            providers.gradleProperty("performanceWarmup").getOrElse("8"),
            providers.gradleProperty("performanceSamples").getOrElse("15"),
            providers.gradleProperty("performanceScenarios").getOrElse("all"),
            providers.gradleProperty("performanceRepeatCounts").getOrElse(""),
        )
        providers.gradleProperty("performanceJfr").orNull?.let { recording ->
            jvmArgs("-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true")
        }
    }
}

val jmhSourceSet = sourceSets.create("jmh")
jmhSourceSet.compileClasspath += sourceSets["main"].output
jmhSourceSet.runtimeClasspath += sourceSets["main"].output
configurations[jmhSourceSet.implementationConfigurationName]
    .extendsFrom(configurations["implementation"])
configurations[jmhSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations["runtimeOnly"])

tasks.register<JavaExec>("jmh") {
    description = "Runs reproducible JMH latency and allocation benchmarks for YKS core and adversarial paths."
    group = "benchmark"
    dependsOn(jmhSourceSet.classesTaskName)
    classpath = jmhSourceSet.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    jvmArgs("-Xms1g", "-Xmx1g", "-XX:+UseG1GC")
    doFirst {
        val report = layout.buildDirectory.file("reports/jmh/results.json").get().asFile
        report.parentFile.mkdirs()
        args(
            providers.gradleProperty("jmhInclude").getOrElse("dev.yks.benchmark.*"),
            "-wi", providers.gradleProperty("jmhWarmupIterations").getOrElse("3"),
            "-w", providers.gradleProperty("jmhWarmupTime").getOrElse("500ms"),
            "-i", providers.gradleProperty("jmhMeasurementIterations").getOrElse("5"),
            "-r", providers.gradleProperty("jmhMeasurementTime").getOrElse("500ms"),
            "-f", providers.gradleProperty("jmhForks").getOrElse("1"),
            "-foe", "true",
            "-prof", providers.gradleProperty("jmhProfiler").getOrElse("gc"),
            "-rf", "json",
            "-rff", report.absolutePath,
        )
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    add(jmhSourceSet.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(jmhSourceSet.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
}
