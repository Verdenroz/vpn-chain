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
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
    }
}
