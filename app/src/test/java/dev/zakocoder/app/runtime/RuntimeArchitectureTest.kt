package dev.zakocoder.app.runtime

import dev.zakocoder.app.settings.AptSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeArchitectureTest {
    @Test fun installedNativeIsaSelectsMatchingRuntime() {
        assertEquals(RuntimeArchitecture.Arm64, RuntimeArchitecture.forNativeLibraryDirectory("/data/app/package/lib/arm64", true))
        assertEquals(RuntimeArchitecture.X86_64, RuntimeArchitecture.forNativeLibraryDirectory("/data/app/package/lib/x86_64", true))
    }

    @Test fun unsupportedAnd32BitProcessesDoNotGuessAnotherAbi() {
        for (directory in listOf("", "/lib/arm", "/lib/x86", "/lib/riscv64", "/lib/arm64-v8a", "/lib/arm64-extra")) {
            assertNull(RuntimeArchitecture.forNativeLibraryDirectory(directory, true))
        }
        assertNull(RuntimeArchitecture.forNativeLibraryDirectory("/lib/arm64", false))
        assertNull(RuntimeArchitecture.forNativeLibraryDirectory("/lib/x86_64", false))
    }

    @Test fun existingArmSourcesRemainUnchangedAndX86UsesUbuntuArchive() {
        for (source in AptSource.entries) assertEquals(source.baseUrl, RuntimeArchitecture.Arm64.aptBaseUrl(source))
        assertEquals("https://archive.ubuntu.com/ubuntu", RuntimeArchitecture.X86_64.aptBaseUrl(AptSource.Ubuntu))
        assertEquals("https://mirrors.tuna.tsinghua.edu.cn/ubuntu", RuntimeArchitecture.X86_64.aptBaseUrl(AptSource.Tsinghua))
    }
}
