plugins {
    id("vpnchain.kmp.feature")
}

android { namespace = "com.verdenroz.vpnchain.feature.sites" }

compose.resources {
    packageOfResClass = "com.verdenroz.vpnchain.feature.sites.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.data)
            implementation(libs.compose.components.resources)
        }
    }
}
