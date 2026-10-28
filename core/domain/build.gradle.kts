plugins {
    id("vpnchain.kmp.library")
}

android { namespace = "com.verdenroz.vpnchain.core.domain" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.data)
            api(projects.core.geoip)
            implementation(projects.core.common)
            api(projects.core.relay)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(projects.core.config)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
