plugins {
    id("vpnchain.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.verdenroz.vpnchain.core.warp"
    // The Public Suffix List, read as a Java resource on both JVM targets.
    sourceSets["main"].resources.srcDir("src/jvmShared/resources")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(projects.core.config)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
        }
        // Both targets are JVM, so code that needs only java.* is compiled into each.
        androidMain { kotlin.srcDir("src/jvmShared/kotlin") }
        desktopMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            resources.srcDir("src/jvmShared/resources")
        }
        // Tink supplies X25519 on both targets: the JDK's own XDH provider only
        // exists on Android 13+, and this app supports 8.0 upward.
        androidMain.dependencies {
            implementation(libs.tink.android)
        }
        desktopMain.dependencies {
            implementation(libs.tink)
        }
    }
}
