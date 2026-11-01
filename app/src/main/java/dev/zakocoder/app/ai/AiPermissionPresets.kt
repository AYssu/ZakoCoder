package dev.zakocoder.app.ai

/** Shortcuts for the existing project policy, not a second source of authorization. */
internal enum class AiPermissionPreset { Normal, Elevated, Yolo }

internal object AiPermissionPresets {
    fun apply(current: AiPolicy, preset: AiPermissionPreset): AiPolicy {
        // Entering YOLO explicitly replaces all tool restrictions after its dedicated confirmation.
        // Leaving it starts from the chosen preset; no hidden rules survive this all-access choice.
        if (preset == AiPermissionPreset.Yolo) return AiPolicy(AiPermission.Allow, emptyMap(), yolo = true)
        val baseline = when (preset) {
            AiPermissionPreset.Normal -> AiPolicy()
            AiPermissionPreset.Elevated -> AiPolicy(AiPermission.Allow, mapOf(AiTool.UseMcp to AiPermission.Ask, AiTool.AndroidTest to AiPermission.Ask, AiTool.SshRemote to AiPermission.Ask))
            AiPermissionPreset.Yolo -> error("Handled above")
        }
        // A default Deny is also an intentional prohibition. Materialize it before changing the default.
        val denied = AiTool.entries.filter { current.permission(it) == AiPermission.Deny }
            .associateWith { AiPermission.Deny }
        return baseline.copy(overrides = baseline.overrides + denied)
    }

    /** Semantic matching permits redundant overrides; arbitrary custom rules never claim a preset. */
    fun matching(policy: AiPolicy): AiPermissionPreset? = if (policy.yolo) AiPermissionPreset.Yolo else
        listOf(AiPermissionPreset.Normal, AiPermissionPreset.Elevated).firstOrNull { preset ->
        val expected = apply(policy, preset)
        policy.default == expected.default && AiTool.entries.all { policy.permission(it) == expected.permission(it) }
    }
}
