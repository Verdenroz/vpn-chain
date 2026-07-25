plugins {
    id("vpnchain.kmp.feature")
}

android { namespace = "com.verdenroz.vpnchain.feature.chain" }

compose.resources {
    packageOfResClass = "com.verdenroz.vpnchain.feature.chain.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // currentTimeMillis, for the ticking uptime readout.
            implementation(projects.core.common)
            implementation(libs.compose.components.resources)
        }
    }
}
