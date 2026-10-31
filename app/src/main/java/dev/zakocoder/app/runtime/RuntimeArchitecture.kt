package dev.zakocoder.app.runtime

import dev.zakocoder.app.settings.AptSource
import java.io.File

/** The installed native-library ISA, not the host kernel or the device's list of possible ABIs. */
internal enum class RuntimeArchitecture {
    Arm64, X86_64;

    fun aptBaseUrl(source: AptSource): String = when (this) {
        Arm64 -> source.baseUrl
        X86_64 -> when (source) {
            AptSource.Ubuntu -> "https://archive.ubuntu.com/ubuntu"
            AptSource.Tsinghua -> "https://mirrors.tuna.tsinghua.edu.cn/ubuntu"
        }
    }

    companion object {
        /** PackageManager selects this ISA directory for the current installed application's ABI. */
        fun forNativeLibraryDirectory(directory: String, is64Bit: Boolean): RuntimeArchitecture? {
            if (!is64Bit) return null
            return when (File(directory).name) {
                "arm64" -> Arm64
                "x86_64" -> X86_64
                else -> null
            }
        }
    }
}
