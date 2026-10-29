package dev.zakocoder.app

import dev.zakocoder.app.runtime.*
import org.junit.Assert.*
import org.junit.Test

class AndroidToolchainTest {
    @Test fun bundleRequiresCompilerAndActualArmSdkRatherThanOnlySdkmanager() {
        val android = ToolBundles.catalog.single { it.id == "android" }
        assertFalse(android.manual)
        val sdkOnly = listOf(ToolCheck("android-sdk", ToolStatus.Ready, "36"))
        assertEquals(BundleStatus.Unknown, ToolBundles.status(android, sdkOnly))
        assertEquals(BundleStatus.Ready, ToolBundles.status(android,
            sdkOnly + listOf(ToolCheck("java", ToolStatus.Ready, "17"), ToolCheck("javac", ToolStatus.Ready, "17"))))
        assertTrue(AndroidToolchain.PROBE.contains("android.jar"))
        assertTrue(AndroidToolchain.PROBE.contains("aapt2 version"))
        assertTrue(AndroidToolchain.PROBE.contains("gradle --version"))
        assertTrue(ToolBundles.packages(setOf("android"), emptyList()).contains("openjdk-17-jdk-headless"))
    }
    @Test fun commandAndWrapperEnvironmentUsesSdkAndPropagatesConfiguredProxyToJava() {
        val direct = AndroidToolchain.environment("")
        assertTrue(direct.contains("ANDROID_HOME=${AndroidToolchain.SDK}"))
        assertTrue(direct.any { it.startsWith("GRADLE_OPTS=") && it.contains("-Dorg.gradle.project.android.aapt2FromMavenOverride=${AndroidToolchain.SDK}/build-tools/36.0.0/aapt2") })
        assertFalse(direct.any { it.substringBefore('=').contains('.') })
        assertFalse(direct.any { it.contains("proxyHost") })
        assertTrue(AndroidToolchain.environment("http://127.0.0.1:7890").any { it.contains("-Dhttps.proxyHost=127.0.0.1") && it.contains("-Dhttps.proxyPort=7890") })
        assertTrue(AndroidToolchain.environment("socks5://127.0.0.1:1080").any { it.contains("-DsocksProxyHost=127.0.0.1") && !it.contains("-Dhttp.proxyHost") })
        assertThrows(IllegalArgumentException::class.java) { AndroidToolchain.environment("http://user:password@host:80") }
    }
}
