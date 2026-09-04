pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's Maven Central mirror is listed first: it is a different host from
        // repo.maven.apache.org and keeps builds working when the primary CDN throttles.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "unique"

include(":app")
include(":core:common")
include(":core:hook")
include(":core:compat")
include(":core:diagnostics")
include(":core:vprofile")
include(":core:vstorage")
include(":core:vpm")
include(":core:vam")
include(":core:vprocess")
include(":core:vpermission")
include(":core:google")
include(":core:native")
