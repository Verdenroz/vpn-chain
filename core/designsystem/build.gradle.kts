plugins {
    id("vpnchain.kmp.library")
    id("vpnchain.compose.multiplatform")
}

android { namespace = "com.verdenroz.vpnchain.core.designsystem" }

compose.resources {
    packageOfResClass = "com.verdenroz.vpnchain.core.designsystem.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
        }
    }
}
