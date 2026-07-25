plugins {
    id("vpnchain.kmp.library")
}

android { namespace = "com.verdenroz.vpnchain.core.logging" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core)
        }
    }
}
