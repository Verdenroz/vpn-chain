plugins {
    id("vpnchain.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.vpnchain.core.update" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.koin.core)
        }
        // Both targets are JVM, so code that needs only java.* is compiled into each.
        androidMain { kotlin.srcDir("src/jvmShared/kotlin") }
        desktopMain { kotlin.srcDir("src/jvmShared/kotlin") }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(libs.okio.fakefilesystem)
        }
    }
}
