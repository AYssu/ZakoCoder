package dev.zakocoder.app.runtime

/** Optional official Flutter SDK inside managed Ubuntu; no Android host executables. */
object FlutterToolchain {
    const val HOME = "/opt/zakocoder/flutter"
    const val VERSION = "flutter3.47.9-arm64-v2"
    const val REVISION = "c197ca5199e9ee5661e865596c9c07294dfab51c"
    const val ENGINE = "89c39edfdfae2949f6806e4a315c0c08522238b7"
    const val QEMU_SHA256 = "90458b484b67436a47c480fbfaad909cda316020dfb514e3ee40726521605d38"
    const val MARKER = "$HOME/.ready"
    const val PATH = "$HOME/bin"
    val packages = listOf("git", "curl", "ca-certificates", "unzip", "xz-utils", "zip", "util-linux", "dpkg", "libc6-amd64-cross")
    const val PROBE = "test \"\$(cat $MARKER)\" = $VERSION && test \"\$(git -C $HOME rev-parse HEAD)\" = $REVISION && test \"\$(cat $HOME/bin/internal/engine.version)\" = $ENGINE && printf '%s  %s\\n' $QEMU_SHA256 $HOME/.zakocoder/qemu/qemu-x86_64 | sha256sum --status -c - && CI=true $HOME/bin/flutter --version && $HOME/bin/dart --version && $HOME/bin/cache/artifacts/engine/android-arm64-profile/linux-arm64/gen_snapshot --version && $HOME/bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot --version"
    val environment = listOf("FLUTTER_ROOT=$HOME", "FLUTTER_SUPPRESS_ANALYTICS=true", "DART_SUPPRESS_ANALYTICS=true")
}
