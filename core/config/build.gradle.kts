plugins {
    id("vpnchain.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.vpnchain.core.config" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(libs.kotlinx.serialization.json)
        }
        desktopMain.dependencies {
            implementation(projects.core.common)
        }
        // Both targets are JVM, so code that needs only java.* is compiled into each.
        androidMain { kotlin.srcDir("src/jvmShared/kotlin") }
        desktopMain { kotlin.srcDir("src/jvmShared/kotlin") }
    }
}
