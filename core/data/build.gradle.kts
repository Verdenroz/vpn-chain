plugins {
    id("vpnchain.kmp.library")
}

android { namespace = "com.verdenroz.vpnchain.core.data" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(projects.core.common)
            implementation(projects.core.config)
            implementation(projects.core.datastore)
            implementation(projects.core.tunnel)
            implementation(projects.core.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
        }
    }
}
