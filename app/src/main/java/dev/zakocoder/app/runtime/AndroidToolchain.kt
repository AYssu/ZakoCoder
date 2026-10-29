package dev.zakocoder.app.runtime

import dev.zakocoder.app.settings.NetworkProxy

/** Managed Linux ARM64 SDK; used by commands and PTYs, never by the Android host. */
object AndroidToolchain {
    const val HOME = "/opt/zakocoder/android"
    const val SDK = "$HOME/sdk"
    const val VERSION = "sdk36-gradle8.13-v1"
    const val MARKER = "$HOME/.ready"
    val packages = listOf("openjdk-17-jdk-headless", "curl", "ca-certificates", "unzip", "libstdc++6", "zlib1g", "util-linux")
    const val PATH = "$HOME/gradle-8.13/bin:$SDK/cmdline-tools/19.0/bin:$SDK/build-tools/36.0.0"
    const val PROBE = "test \"\$(cat $MARKER)\" = $VERSION && printf 'Android SDK 36 / Gradle 8.13 / ARM64\n' && test -s $SDK/platforms/android-36/android.jar && test -s $SDK/build-tools/36.0.0/lib/apksigner.jar && java -version && $SDK/build-tools/36.0.0/aapt2 version && $HOME/gradle-8.13/bin/gradle --version"

    fun environment(proxy: String): List<String> {
        val spec = NetworkProxy.parse(proxy)
        val proxyOptions = when {
            spec == null -> ""
            spec.isSocks -> " -DsocksProxyHost=${spec.host} -DsocksProxyPort=${spec.port}"
            else -> " -Dhttp.proxyHost=${spec.host} -Dhttp.proxyPort=${spec.port} -Dhttps.proxyHost=${spec.host} -Dhttps.proxyPort=${spec.port}"
        }
        return listOf("ANDROID_HOME=$SDK", "ANDROID_SDK_ROOT=$SDK", "JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64",
            // Dotted environment-variable names are dropped by guest shells; use Gradle's JVM property bridge.
            "GRADLE_OPTS=-Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.project.android.aapt2FromMavenOverride=$SDK/build-tools/36.0.0/aapt2$proxyOptions")
    }
}
