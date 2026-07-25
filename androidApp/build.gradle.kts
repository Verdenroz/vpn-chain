plugins {
    id("vpnchain.android.application")
    id("vpnchain.compose.multiplatform")
}

android {
    namespace = "com.verdenroz.vpnchain"
    defaultConfig { applicationId = "com.verdenroz.vpnchain" }

    // CI provides a real keystore via env (see .github/workflows/release.yml);
    // without one, release falls back to debug signing so it stays installable.
    val releaseKeystore = System.getenv("VPNCHAIN_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("VPNCHAIN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VPNCHAIN_KEY_ALIAS")
                keyPassword = System.getenv("VPNCHAIN_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    // libbox.so is ~60 MB per ABI; split so each APK is installable instead of
    // one ~250 MB universal build. arm64 = phones, x86_64 = emulators.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(projects.appCommon)
    implementation(projects.core.tunnel)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.compose.material3)
}
