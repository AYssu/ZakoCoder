package dev.zakocoder.app.runtime

/** Optional GNU/Linux ARM64 NDK; all native commands execute inside Ubuntu. */
object AndroidNdkToolchain {
    const val HOME = "/opt/zakocoder/android-ndk-r28c"
    const val VERSION = "ndk28c-arm64-v1"
    const val MARKER = "$HOME/.ready"
    const val NDK_VERSION = "28.2.13676358"
    const val PATH = "$HOME/toolchains/llvm/prebuilt/linux-arm64/bin"
    val packages = listOf("cmake", "ninja-build", "python3", "xz-utils", "curl", "ca-certificates", "libstdc++6", "zlib1g", "util-linux")
    const val PROBE = "test \"\$(cat $MARKER)\" = $VERSION && printf 'Android NDK r28c / ARM64\\n' && " +
        "test ! -L ${AndroidToolchain.SDK}/ndk && test -L ${AndroidToolchain.SDK}/ndk/$NDK_VERSION && " +
        "test \"\$(readlink ${AndroidToolchain.SDK}/ndk/$NDK_VERSION)\" = $HOME && " +
        "grep -qx 'Pkg.Revision = $NDK_VERSION' $HOME/source.properties && " +
        "test -s $HOME/build/cmake/android.toolchain.cmake && $PATH/clang --version && $PATH/ld.lld --version && cmake --version && ninja --version"
    fun environment(): List<String> = listOf("ANDROID_NDK_HOME=$HOME", "ANDROID_NDK_ROOT=$HOME")
}
