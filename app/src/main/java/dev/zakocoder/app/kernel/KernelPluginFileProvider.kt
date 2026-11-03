package dev.zakocoder.app.kernel

import androidx.core.content.FileProvider

/** A distinct component keeps plugin APK grants separate from project APK previews. */
class KernelPluginFileProvider : FileProvider()
