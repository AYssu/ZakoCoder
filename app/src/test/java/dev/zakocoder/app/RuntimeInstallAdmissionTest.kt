package dev.zakocoder.app

import dev.zakocoder.app.runtime.InstallAdmission
import dev.zakocoder.app.runtime.installAdmission
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeInstallAdmissionTest {
    @Test fun installedRuntimeIsBlocked() {
        assertEquals(InstallAdmission.Blocked, installAdmission(installed = true, directoryPresent = true))
    }

    @Test fun uninstalledPresentDirectoryNeedsRepair() {
        assertEquals(InstallAdmission.Repair, installAdmission(installed = false, directoryPresent = true))
    }

    @Test fun absentDirectoryIsReadyForNormalInstall() {
        assertEquals(InstallAdmission.Ready, installAdmission(installed = false, directoryPresent = false))
    }
}
