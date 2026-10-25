plugins {
    id("vpnchain.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.vpnchain.core.engine" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.kotlinx.serialization.json)
        }
        desktopMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.data)
            implementation(projects.core.domain)
            implementation(projects.core.logging)
            implementation(projects.core.tunnel)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.compose.components.resources)
        }
    }
}
