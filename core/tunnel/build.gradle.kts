plugins {
    id("vpnchain.kmp.library")
    id("vpnchain.compose.multiplatform")
}

android { namespace = "com.verdenroz.vpnchain.core.tunnel" }

compose.resources {
    packageOfResClass = "com.verdenroz.vpnchain.core.tunnel.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(projects.core.config)
            implementation(projects.core.common)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
        }
        androidMain.dependencies {
            implementation(libs.libbox)
            implementation(libs.koin.android)
        }
    }
}
