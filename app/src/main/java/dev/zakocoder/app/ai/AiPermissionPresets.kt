package dev.zakocoder.app.ai

/** Shortcuts for the existing project policy, not a second source of authorization. */
internal enum class AiPermissionPreset { Normal, Elevated }

internal object AiPermissionPresets {
    fun apply(current: AiPolicy, preset: AiPermissionPreset): AiPolicy {
        val baseline = when (preset) {
            AiPermissionPreset.Normal -> AiPolicy()
            AiPermissionPreset.Elevated -> AiPolicy(AiPermission.Allow, mapOf(AiTool.UseMcp to AiPermission.Ask))
        }
        // A default Deny is also an intentional prohibition. Materialize it before changing the default.
        val denied = AiTool.entries.filter { current.permission(it) == AiPermission.Deny }
            .associateWith { AiPermission.Deny }
        return baseline.copy(overrides = baseline.overrides + denied)
    }

    /** Semantic matching permits redundant overrides; arbitrary custom rules never claim a preset. */
    fun matching(policy: AiPolicy): AiPermissionPreset? = AiPermissionPreset.entries.firstOrNull { preset ->
        val expected = apply(policy, preset)
        policy.default == expected.default && AiTool.entries.all { policy.permission(it) == expected.permission(it) }
    }
}
