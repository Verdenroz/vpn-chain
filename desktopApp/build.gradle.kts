import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("vpnchain.compose.multiplatform")
}

dependencies {
    implementation(projects.appCommon)
    // The tray drives and reflects the tunnel from outside the Compose UI tree.
    implementation(projects.core.data)
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
}

compose.resources {
    packageOfResClass = "com.verdenroz.vpnchain.desktop.generated.resources"
}

// Local Linux convenience: put the app in wofi/rofi/GNOME by installing icons
// and a .desktop entry (user-level, no sudo) pointing at the distributable.
// Debian-family users don't need this — the CI-built .deb installs its own.
tasks.register("installDesktopEntry") {
    dependsOn("createDistributable")
    val launcher = layout.buildDirectory.file("compose/binaries/main/app/vpn-chain/bin/vpn-chain")
    val iconSvg = rootProject.file("assets/branding/icon.svg")
    val iconPng = project.file("icons/vpn-chain.png")
    doLast {
        require(System.getProperty("os.name").lowercase().contains("linux")) {
            "installDesktopEntry only makes sense on Linux"
        }
        val dataHome = File(
            System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
                ?: "${System.getProperty("user.home")}/.local/share"
        )
        fun install(src: File, dest: File) {
            dest.parentFile.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        install(iconSvg, File(dataHome, "icons/hicolor/scalable/apps/vpn-chain.svg"))
        install(iconPng, File(dataHome, "icons/hicolor/512x512/apps/vpn-chain.png"))

        val exec = launcher.get().asFile.absolutePath
        val entry = File(dataHome, "applications/vpn-chain.desktop")
        entry.parentFile.mkdirs()
        entry.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=VPN Chain
            GenericName=Multi-hop VPN
            Comment=Chain Proton entry, VLESS relay, and exit hops into one tunnel
            Exec=$exec
            Icon=vpn-chain
            Terminal=false
            Categories=Network;Security;
            Keywords=vpn;wireguard;vless;proxy;tunnel;
            """.trimIndent() + "\n"
        )
        println("Installed ${entry.path} -> $exec")
    }
}

compose.desktop {
    application {
        mainClass = "com.verdenroz.vpnchain.desktop.MainKt"
        // Release distributables would run ProGuard by default; koin + kotlinx
        // reflection make that a minefield, so ship unminified for now.
        buildTypes.release.proguard { isEnabled.set(false) }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            modules("jdk.unsupported")
            packageName = "vpn-chain"
            packageVersion = providers.gradleProperty("vpnchain.version").getOrElse("0.1.0")
            linux { iconFile.set(project.file("icons/vpn-chain.png")) }
            windows { iconFile.set(project.file("icons/vpn-chain.ico")) }
            macOS {
                iconFile.set(project.file("icons/vpn-chain.icns"))
                // Dmg refuses MAJOR=0 versions; placeholder until 1.0.
                packageVersion = "1.0.0"
            }
        }
    }
}
