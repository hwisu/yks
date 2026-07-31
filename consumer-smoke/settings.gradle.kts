pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("consumerKotlinVersion")
            .getOrElse("2.2.20")
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

val yksRepositoryUrl = providers.gradleProperty("yksRepositoryUrl").orNull?.takeIf { it.isNotBlank() }
val githubActor = providers.environmentVariable("GITHUB_ACTOR").orNull
val githubToken = providers.environmentVariable("GITHUB_TOKEN").orNull

if (yksRepositoryUrl != null) {
    require(!githubActor.isNullOrBlank()) {
        "GITHUB_ACTOR is required when yksRepositoryUrl selects GitHub Packages."
    }
    require(!githubToken.isNullOrBlank()) {
        "GITHUB_TOKEN is required when yksRepositoryUrl selects GitHub Packages."
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (yksRepositoryUrl == null) {
            mavenLocal {
                content {
                    includeGroup("dev.yks")
                }
            }
        } else {
            maven {
                name = "YksPublishedRepository"
                url = uri(yksRepositoryUrl)
                credentials {
                    username = githubActor
                    password = githubToken
                }
                content {
                    includeGroup("dev.yks")
                }
            }
        }
        mavenCentral {
            content {
                excludeGroup("dev.yks")
            }
        }
    }
}

rootProject.name = "yks-consumer-smoke"
