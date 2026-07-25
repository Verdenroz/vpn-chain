plugins {
    id("vpnchain.kmp.library")
    id("vpnchain.compose.multiplatform")
}

android { namespace = "com.verdenroz.vpnchain.core.ui" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.designsystem)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
        }
    }
}
