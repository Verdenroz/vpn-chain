plugins {
    id("vpnchain.kmp.library")
    id("vpnchain.compose.multiplatform")
}

android { namespace = "com.verdenroz.vpnchain.core.geoip" }

compose.resources {
    packageOfResClass = "com.verdenroz.vpnchain.core.geoip.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
