package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.LogEntry
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.tunnel.tunnelString
import java.util.Locale
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ThemeQualifier
import org.jetbrains.compose.resources.getString

internal fun UiText.toWire(): WireText = when (this) {
    is UiText.Dynamic -> WireText.Plain(value)
    is UiText.Resource -> WireText.Resource(resource.key, args.map(Any::toString))
}

internal fun ChainStatus.toWire(profile: ProfileSummary?) = EngineStatus(
    state = state,
    profile = profile,
    entryHopActive = entryHopActive,
    killSwitch = killSwitch,
    exitIp = exitIp,
    detail = detail?.toWire(),
)

internal fun SessionStats.toWire() = EngineStats(
    connectedSinceMillis = connectedSinceMillis,
    uplinkBytes = uplinkBytes,
    downlinkBytes = downlinkBytes,
    uplinkBytesPerSecond = uplinkBytesPerSecond,
    downlinkBytesPerSecond = downlinkBytesPerSecond,
)

internal fun SavedProfile.toSummary(activeId: String?) = ProfileSummary(id, name, kind, active = id == activeId)

internal fun LogEntry.toWire() = LogLine(timestampMillis, message)

/** Resolves against this process's locale. A key this build doesn't know comes back as the key itself. */
suspend fun WireText.resolve(): String = when (this) {
    is WireText.Plain -> text
    is WireText.Resource -> tunnelString(key)?.let { getString(localeEnvironment, it, *args.toTypedArray()) } ?: key
}

// getSystemResourceEnvironment() starts AWT and skiko for screen DPI and theme, which strings never use.
// The constructor is Kotlin-internal but public on the JVM.
@OptIn(InternalResourceApi::class)
private val localeEnvironment: ResourceEnvironment by lazy {
    val locale = Locale.getDefault()
    ResourceEnvironment::class.java
        .getConstructor(
            LanguageQualifier::class.java,
            RegionQualifier::class.java,
            ThemeQualifier::class.java,
            DensityQualifier::class.java,
        )
        .newInstance(
            LanguageQualifier(locale.language),
            RegionQualifier(locale.country),
            ThemeQualifier.LIGHT,
            DensityQualifier.MDPI,
        )
}
