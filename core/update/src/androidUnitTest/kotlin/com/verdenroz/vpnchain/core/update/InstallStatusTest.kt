package com.verdenroz.vpnchain.core.update

import android.content.pm.PackageInstaller
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallStatusTest {
    private fun result(status: Int, message: String?, prompted: Boolean = true) =
        installResult(status, message, prompted, unprompted = "Blocked.", aborted = "Not installed.")

    @Test
    fun `a successful install starts the new build`() {
        assertEquals(InstallResult.Started, result(PackageInstaller.STATUS_SUCCESS, null))
    }

    @Test
    fun `an abort after the user was asked covers a declined prompt`() {
        assertEquals(
            InstallResult.Failed("Not installed."),
            result(PackageInstaller.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_ABORTED"),
        )
    }

    @Test
    fun `an abort the user was never asked about carries the system's message`() {
        assertEquals(
            InstallResult.Failed("Blocked. INSTALL_FAILED_ABORTED"),
            result(PackageInstaller.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_ABORTED", prompted = false),
        )
        assertEquals(InstallResult.Failed("Blocked."), result(PackageInstaller.STATUS_FAILURE_ABORTED, null, prompted = false))
    }

    @Test
    fun `every other failure carries the system's message`() {
        val failures = listOf(
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE,
        )
        for (status in failures) {
            assertEquals(InstallResult.Failed("no space"), result(status, "no space"))
        }
    }

    @Test
    fun `a failure with no message still says which status`() {
        assertEquals(InstallResult.Failed("Install failed, status 1"), result(PackageInstaller.STATUS_FAILURE, null))
    }
}
