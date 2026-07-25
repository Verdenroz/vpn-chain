pluginManagement {
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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Prebuilt sing-box libbox AAR (com.github.singbox-android:libbox).
        maven("https://jitpack.io") {
            content { includeGroup("com.github.singbox-android") }
        }
    }
}

rootProject.name = "vpn-chain"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":androidApp")
include(":desktopApp")
include(":app-common")
include(":core:model")
include(":core:common")
include(":core:logging")
include(":core:config")
include(":core:datastore")
include(":core:tunnel")
include(":core:data")
include(":core:domain")
include(":core:designsystem")
include(":core:ui")
include(":core:geoip")
include(":feature:chain")
include(":feature:settings")
include(":feature:logs")
