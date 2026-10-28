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
            implementation(projects.core.update)
        }
        // Both targets are JVM, so the QR encoder is compiled into each.
        androidMain { kotlin.srcDir("src/jvmShared/kotlin") }
        desktopMain { kotlin.srcDir("src/jvmShared/kotlin") }
        desktopMain.dependencies {
            // QR generation for pairing devices; pure Java, no AWT/Android deps.
            implementation(libs.zxing.core)
        }
        androidMain.dependencies {
            implementation(libs.zxing.core)
            // CameraX + ML Kit for QR pairing
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.camera.mlkit.vision)
            implementation(libs.mlkit.barcode.scanning)
            implementation(libs.androidx.activity.compose)
        }
    }
}
