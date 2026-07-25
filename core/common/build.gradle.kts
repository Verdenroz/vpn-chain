plugins {
    id("vpnchain.kmp.library")
}

android { namespace = "com.verdenroz.vpnchain.core.common" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
        }
    }
}
