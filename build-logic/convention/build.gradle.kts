import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.verdenroz.vpnchain.buildlogic"

// Match the JDK used to build the project (not the device runtime).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.android.tools.common)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.composeCompiler.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "vpnchain.kmp.library"
            implementationClass = "KotlinMultiplatformLibraryConventionPlugin"
        }
        register("kmpFeature") {
            id = "vpnchain.kmp.feature"
            implementationClass = "KotlinMultiplatformFeatureConventionPlugin"
        }
        register("composeMultiplatform") {
            id = "vpnchain.compose.multiplatform"
            implementationClass = "ComposeMultiplatformConventionPlugin"
        }
        register("androidApplication") {
            id = "vpnchain.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
    }
}
