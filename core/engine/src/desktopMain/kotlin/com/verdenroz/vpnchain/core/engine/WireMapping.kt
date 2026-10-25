package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.model.ChainStatus
import com.verdenroz.vpnchain.core.model.LogEntry
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.SessionStats
import com.verdenroz.vpnchain.core.model.UiText
import com.verdenroz.vpnchain.core.tunnel.tunnelString
import com.verdenroz.vpnchain.core.logging.createPlatformLogger
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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

internal fun ChainStatus.toWire(profile: ProfileSummary?, willRetry: Boolean) = EngineStatus(
    state = state,
    profile = profile,
    entryHopActive = entryHopActive,
    killSwitch = killSwitch,
    exitIp = exitIp,
    detail = detail?.toWire(),
    willRetry = willRetry,
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

/** Resolves against this process's locale. A key this build can't resolve comes back as the key itself. */
suspend fun WireText.resolve(): String = resolveWith { localeEnvironment }

internal suspend fun WireText.resolveWith(environment: () -> ResourceEnvironment): String = when (this) {
    is WireText.Plain -> text
    is WireText.Resource -> tunnelString(key)?.let { resource ->
        try {
            getString(environment(), resource, *args.toTypedArray())
        } catch (e: ReflectiveOperationException) {
            null.also { warnUnresolvable(e) }
        } catch (e: LinkageError) {
            null.also { warnUnresolvable(e) }
        }
    } ?: key
}

private val warnedUnresolvable = AtomicBoolean(false)

private fun warnUnresolvable(e: Throwable) {
    if (warnedUnresolvable.compareAndSet(false, true)) {
        createPlatformLogger().w("WireText", "cannot build a resource environment, showing keys instead: $e")
    }
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
