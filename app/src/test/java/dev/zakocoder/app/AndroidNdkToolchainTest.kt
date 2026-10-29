package dev.zakocoder.app

import dev.zakocoder.app.runtime.*
import org.junit.Assert.*
import org.junit.Test

class AndroidNdkToolchainTest {
    @Test fun optionalBundleRequiresActualManagedCompilerCmakeAndNinja() {
        val bundle = ToolBundles.catalog.single { it.id == "android-ndk" }
        assertFalse(bundle.manual)
        assertEquals(BundleStatus.Unknown, ToolBundles.status(bundle, emptyList()))
        assertEquals(BundleStatus.Broken, ToolBundles.status(bundle, listOf(ToolCheck("android-ndk", ToolStatus.Broken, "missing CMake"))))
        assertEquals(BundleStatus.Ready, ToolBundles.status(bundle, listOf(ToolCheck("android-ndk", ToolStatus.Ready, "r28c"))))
        assertTrue(AndroidNdkToolchain.PROBE.contains("clang --version"))
        assertTrue(AndroidNdkToolchain.PROBE.contains("cmake --version"))
        assertTrue(AndroidNdkToolchain.PROBE.contains("ninja --version"))
        assertTrue(AndroidNdkToolchain.PROBE.contains(AndroidNdkToolchain.MARKER))
        assertTrue(AndroidNdkToolchain.PROBE.contains("readlink ${AndroidToolchain.SDK}/ndk/${AndroidNdkToolchain.NDK_VERSION}"))
        assertTrue(AndroidNdkToolchain.PROBE.contains("source.properties"))
        assertTrue(AndroidNdkToolchain.PROBE.contains("ld.lld --version"))
        val packages = ToolBundles.packages(setOf("android-ndk"), emptyList())
        assertTrue(packages.containsAll(listOf("cmake", "ninja-build", "python3", "xz-utils")))
        assertTrue(DevelopmentTools.installScript(packages).contains("--no-install-recommends"))
        assertFalse(packages.contains("openjdk-17-jdk-headless"))
    }

    @Test fun managedNdkProbeCannotMistakeUbuntuClangForAndroidCompiler() {
        val tool = DevelopmentTools.catalog.single { it.id == "android-ndk" }
        val script = DevelopmentTools.doctorScript(listOf(tool))
        assertTrue(script.contains("command -v ${AndroidNdkToolchain.PATH}/clang"))
        assertEquals(listOf("ANDROID_NDK_HOME=${AndroidNdkToolchain.HOME}", "ANDROID_NDK_ROOT=${AndroidNdkToolchain.HOME}"), AndroidNdkToolchain.environment())
        assertEquals("28.2.13676358", AndroidNdkToolchain.NDK_VERSION)
    }
}
