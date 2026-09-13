pluginManagement {
    // convention plugins (e.g. release-verification) live in an included build rather
    // than buildSrc: buildSrc's parent classloader would isolate AGP from the Kotlin
    // compiler plugins (Metro codegen silently stopped applying there)
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("self") {
            from(files("self.versions.toml"))
        }
    }
}

rootProject.name = "Meeting Minder"
include(":app")
