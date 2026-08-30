import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** Shared config for the Android application module (API 36 / minSdk 26). */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.android")
        }

        val version = providers.gradleProperty("vpnchain.version").getOrElse("0.1.0")

        extensions.configure<ApplicationExtension> {
            compileSdk = 36
            defaultConfig {
                minSdk = 26
                targetSdk = 36
                versionCode = versionCodeOf(version)
                versionName = version
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
}

/**
 * Android compares builds by version code alone, so it has to rise with the
 * version name. Allows up to 99 minor and 99 patch releases per major.
 */
private fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.split(".").map(String::toInt)
    return major * 10_000 + minor * 100 + patch
}
