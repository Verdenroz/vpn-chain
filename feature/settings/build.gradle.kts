plugins {
    id("vpnchain.kmp.feature")
}

android { namespace = "com.verdenroz.vpnchain.feature.settings" }

compose.resources {
    packageOfResClass = "com.verdenroz.vpnchain.feature.settings.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.components.resources)
            implementation(projects.core.common)
            implementation(projects.core.config)
        }
        desktopMain.dependencies {
            // QR generation for pairing with the Android app — pure-Java, no AWT/Android deps.
            implementation(libs.zxing.core)
        }
        androidMain.dependencies {
            // Camera scanning Activity for QR pairing.
            implementation(libs.zxing.android.embedded)
            implementation(libs.androidx.activity.compose)
        }
    }
}
