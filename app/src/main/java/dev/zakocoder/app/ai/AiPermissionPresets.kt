package dev.zakocoder.app.ai

/** Persisted names preserve existing policy records while labels describe the four approval modes. */
enum class AiPermissionPreset { Normal, Elevated, Yolo, ReadOnly }

internal object AiPermissionPresets {
    fun apply(current: AiPolicy, preset: AiPermissionPreset): AiPolicy {
        // Full Access is an explicit, separately confirmed replacement of all app tool restrictions.
        if (preset == AiPermissionPreset.Yolo) return AiPolicy(AiPermission.Allow, emptyMap(), yolo = true, mode = preset)
        val reads = AiPolicy().overrides
        val workspaceWrites = listOf(AiTool.WriteFile, AiTool.EditFile, AiTool.MakeDirectory)
            .associateWith { AiPermission.Allow }
        val baseline = AiPolicy(overrides = reads + if (preset == AiPermissionPreset.ReadOnly) emptyMap() else workspaceWrites, mode = preset)
        // A previous default Deny is intentional too. Never silently discard a prohibition.
        val denied = AiTool.entries.filter { current.permission(it) == AiPermission.Deny }.associateWith { AiPermission.Deny }
        return baseline.copy(overrides = baseline.overrides + denied)
    }

    /** Old tool policies are custom, even if they happen to resemble a new mode. */
    fun matching(policy: AiPolicy): AiPermissionPreset? {
        if (policy.yolo) return AiPermissionPreset.Yolo
        val preset = policy.mode ?: return null
        if (preset == AiPermissionPreset.Yolo) return null
        val expected = apply(policy, preset)
        return preset.takeIf { policy.default == expected.default && AiTool.entries.all { policy.permission(it) == expected.permission(it) } }
    }
}
