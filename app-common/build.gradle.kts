plugins {
    id("vpnchain.kmp.library")
    id("vpnchain.compose.multiplatform")
}

android { namespace = "com.verdenroz.vpnchain.app" }

compose.resources {
    packageOfResClass = "com.verdenroz.vpnchain.app.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.designsystem)
            api(projects.core.ui)
            api(projects.core.data)
            api(projects.core.domain)
            api(projects.feature.chain)
            api(projects.feature.settings)
            api(projects.feature.logs)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.compose.components.resources)
        }
    }
}
