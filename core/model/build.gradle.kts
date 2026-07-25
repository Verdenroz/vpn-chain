plugins {
    id("vpnchain.kmp.library")
    id("vpnchain.compose.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.vpnchain.core.model" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(compose.runtime)
            implementation(compose.components.resources)
        }
    }
}
