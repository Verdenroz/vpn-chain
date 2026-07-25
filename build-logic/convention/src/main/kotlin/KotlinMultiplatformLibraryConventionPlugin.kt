import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Shared config for a KMP library targeting Android + JVM desktop.
 * SDK levels are pinned to Android 16 (API 36) / minSdk 26.
 */
class KotlinMultiplatformLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("com.android.library")
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun lib(alias: String) = libs.findLibrary(alias).get()

        extensions.configure<KotlinMultiplatformExtension> {
            androidTarget {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
            jvm("desktop")

            // Every module gets a test source set; both targets are JVM, so the
            // common suite runs unchanged under `desktopTest` without an SDK.
            sourceSets.apply {
                commonTest.dependencies {
                    implementation(lib("kotlin-test"))
                    implementation(lib("kotlinx-coroutines-test"))
                }
            }
        }

        extensions.configure<LibraryExtension> {
            compileSdk = 36
            defaultConfig { minSdk = 26 }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
