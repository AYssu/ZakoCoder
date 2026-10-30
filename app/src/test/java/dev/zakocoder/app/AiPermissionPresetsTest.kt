package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiPermissionPresetsTest {
    @Test fun normalUsesExistingSafeDefaults() {
        val normal = AiPermissionPresets.apply(AiPolicy(AiPermission.Allow, emptyMap()), AiPermissionPreset.Normal)
        assertEquals(AiPolicy(), normal)
        assertEquals(AiPermissionPreset.Normal, AiPermissionPresets.matching(normal))
        assertEquals(AiPermission.Ask, normal.permission(AiTool.RunCommand))
        assertEquals(AiPermission.Ask, normal.permission(AiTool.ReadTerminal))
        assertEquals(AiPermission.Ask, normal.permission(AiTool.ReadCommandLog))
        assertEquals(AiPermission.Ask, normal.permission(AiTool.UseMcp))
    }

    @Test fun elevatedAllowsNativeToolsButAlwaysAsksForMcp() {
        val elevated = AiPermissionPresets.apply(AiPolicy(), AiPermissionPreset.Elevated)
        assertEquals(AiPermission.Allow, elevated.default)
        AiTool.entries.forEach { tool ->
            assertEquals(if (tool == AiTool.UseMcp) AiPermission.Ask else AiPermission.Allow, elevated.permission(tool))
        }
        assertEquals(AiPermissionPreset.Elevated, AiPermissionPresets.matching(elevated))
        assertEquals(AiPermission.Ask, AiPermissionPresets.apply(AiPolicy(AiPermission.Allow, emptyMap()), AiPermissionPreset.Elevated).permission(AiTool.UseMcp))
    }

    @Test fun bothPresetsPreserveEveryEffectiveProhibitionIncludingDefaultDeny() {
        AiPermission.entries.forEach { default ->
            AiTool.entries.forEach { exception ->
                AiPermission.entries.forEach { rule ->
                    val original = AiPolicy(default, mapOf(exception to rule))
                    AiPermissionPreset.entries.forEach { preset ->
                        val result = AiPermissionPresets.apply(original, preset)
                        AiTool.entries.filter { original.permission(it) == AiPermission.Deny }.forEach {
                            assertEquals("$default $exception $rule $preset $it", AiPermission.Deny, result.permission(it))
                        }
                        assertEquals(preset, AiPermissionPresets.matching(result))
                    }
                }
            }
        }
    }

    @Test fun mcpExplicitDenialSurvivesBothDirections() {
        val source = AiPolicy(overrides = mapOf(AiTool.UseMcp to AiPermission.Deny))
        val elevated = AiPermissionPresets.apply(source, AiPermissionPreset.Elevated)
        val normal = AiPermissionPresets.apply(elevated, AiPermissionPreset.Normal)
        assertEquals(AiPermission.Deny, elevated.permission(AiTool.UseMcp))
        assertEquals(AiPermission.Deny, normal.permission(AiTool.UseMcp))
    }

    @Test fun arbitraryRulesAndUnrestrictedMcpAreCustomRatherThanMislabelled() {
        assertNull(AiPermissionPresets.matching(AiPolicy().copy(overrides = AiPolicy().overrides + (AiTool.RunCommand to AiPermission.Allow))))
        assertNull(AiPermissionPresets.matching(AiPolicy(AiPermission.Allow, emptyMap())))
        assertNull(AiPermissionPresets.matching(AiPolicy(AiPermission.Deny, emptyMap())))
        assertEquals(AiPermissionPreset.Normal, AiPermissionPresets.matching(AiPolicy().copy(overrides = AiPolicy().overrides + (AiTool.WriteFile to AiPermission.Ask))))
    }

    @Test fun presetSwitchesDoNotMutateSourcePolicy() {
        val overrides = mutableMapOf(AiTool.WriteFile to AiPermission.Deny)
        val original = AiPolicy(overrides = overrides)
        val result = AiPermissionPresets.apply(original, AiPermissionPreset.Elevated)
        assertEquals(mapOf(AiTool.WriteFile to AiPermission.Deny), overrides)
        overrides[AiTool.RunCommand] = AiPermission.Deny
        assertEquals(AiPermission.Allow, result.permission(AiTool.RunCommand))
    }
}
