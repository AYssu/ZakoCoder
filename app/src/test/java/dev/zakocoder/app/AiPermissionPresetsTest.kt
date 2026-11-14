package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiPermissionPresetsTest {
    @Test fun approvalModeAllowsNativeWorkspaceEditsAndAsksForUnknownCommands() {
        val normal = AiPermissionPresets.apply(AiPolicy(), AiPermissionPreset.Normal)
        assertEquals(AiPermissionPreset.Normal, normal.mode)
        assertEquals(AiPermissionPreset.Normal, AiPermissionPresets.matching(normal))
        assertEquals(R.string.ai_permission_mode_rule, normal.source(AiTool.RunCommand))
        assertEquals(AiPermission.Allow, normal.permission(AiTool.WriteFile, AiPermissionRisk.WorkspaceWrite))
        assertEquals(AiPermission.Allow, normal.permission(AiTool.RunCommand, AiPermissionRisk.Read))
        assertEquals(AiPermission.Ask, normal.permission(AiTool.RunCommand, AiPermissionRisk.Unknown))
        assertEquals(AiPermission.Ask, normal.permission(AiTool.Browser, AiPermissionRisk.NetworkRead))
    }

    @Test fun approveForMeAllowsReadOnlyNetworkButUnknownEffectsStillAsk() {
        val elevated = AiPermissionPresets.apply(AiPolicy(), AiPermissionPreset.Elevated)
        assertEquals(AiPermissionPreset.Elevated, AiPermissionPresets.matching(elevated))
        assertEquals(AiPermission.Allow, elevated.permission(AiTool.Browser, AiPermissionRisk.NetworkRead))
        listOf(AiTool.UseMcp, AiTool.AndroidTest, AiTool.SshRemote, AiTool.RunCommand).forEach {
            assertEquals(AiPermission.Ask, elevated.permission(it, AiPermissionRisk.Unknown))
        }
    }

    @Test fun readOnlyAllowsReadsButAsksInsteadOfPermanentlyDenyingEdits() {
        val readonly = AiPermissionPresets.apply(AiPolicy(), AiPermissionPreset.ReadOnly)
        assertEquals(AiPermissionPreset.ReadOnly, AiPermissionPresets.matching(readonly))
        assertEquals(AiPermission.Allow, readonly.permission(AiTool.ReadFile, AiPermissionRisk.Read))
        assertEquals(AiPermission.Ask, readonly.permission(AiTool.EditFile, AiPermissionRisk.WorkspaceWrite))
        assertEquals(AiPermission.Ask, readonly.permission(AiTool.Browser, AiPermissionRisk.NetworkRead))
        assertTrue(readonly.skipsToolApproval(AiTool.EditFile, taskGranted = true, forceConfirmation = false, risk = AiPermissionRisk.WorkspaceWrite))
    }

    @Test fun everyNonFullPresetPreservesAllEffectiveProhibitions() {
        AiPermission.entries.forEach { default ->
            AiTool.entries.forEach { exception ->
                AiPermission.entries.forEach { rule ->
                    val original = AiPolicy(default, mapOf(exception to rule))
                    listOf(AiPermissionPreset.Normal, AiPermissionPreset.Elevated, AiPermissionPreset.ReadOnly).forEach { preset ->
                        val result = AiPermissionPresets.apply(original, preset)
                        AiTool.entries.filter { original.permission(it) == AiPermission.Deny }.forEach { tool ->
                            assertNotEquals(R.string.ai_permission_mode_rule, result.source(tool))
                            AiPermissionRisk.entries.forEach { risk ->
                                assertEquals("$default $exception $rule $preset $tool $risk", AiPermission.Deny, result.permission(tool, risk))
                            }
                        }
                        assertEquals(preset, AiPermissionPresets.matching(result))
                    }
                }
            }
        }
    }

    @Test fun fullAccessExplicitlyReplacesProhibitionsButLeavingItRestoresChosenMode() {
        val full = AiPermissionPresets.apply(AiPolicy(AiPermission.Deny, emptyMap()), AiPermissionPreset.Yolo)
        assertTrue(full.yolo)
        assertTrue(full.overrides.isEmpty())
        AiTool.entries.forEach { tool ->
            AiPermissionRisk.entries.forEach { risk -> assertEquals(AiPermission.Allow, full.permission(tool, risk)) }
            assertEquals(R.string.ai_permission_yolo_rule, full.source(tool))
        }
        assertEquals(AiPermissionPreset.Yolo, AiPermissionPresets.matching(full))
        val normal = AiPermissionPresets.apply(full, AiPermissionPreset.Normal)
        assertFalse(normal.yolo)
        assertEquals(AiPermission.Ask, normal.permission(AiTool.UseMcp, AiPermissionRisk.Unknown))
        assertEquals(AiPermission.Allow, normal.permission(AiTool.EditFile, AiPermissionRisk.WorkspaceWrite))
    }

    @Test fun oldPoliciesAreNotSilentlyRelabelledOrGrantedNewPowers() {
        listOf(AiPolicy(), AiPolicy(AiPermission.Allow, emptyMap()), AiPolicy(AiPermission.Deny, emptyMap())).forEach {
            assertNull(AiPermissionPresets.matching(it))
            assertEquals(it.permission(AiTool.WriteFile), it.permission(AiTool.WriteFile, AiPermissionRisk.WorkspaceWrite))
            assertEquals(it.permission(AiTool.RunCommand), it.permission(AiTool.RunCommand, AiPermissionRisk.Read))
        }
        assertEquals(AiPermissionPreset.Yolo, AiPermissionPresets.matching(AiPolicy(yolo = true)))
    }

    @Test fun presetSwitchesDoNotMutateSourcePolicy() {
        val overrides = mutableMapOf(AiTool.WriteFile to AiPermission.Deny)
        val original = AiPolicy(overrides = overrides)
        val result = AiPermissionPresets.apply(original, AiPermissionPreset.Elevated)
        assertEquals(mapOf(AiTool.WriteFile to AiPermission.Deny), overrides)
        overrides[AiTool.RunCommand] = AiPermission.Deny
        assertEquals(AiPermission.Ask, result.permission(AiTool.RunCommand))
    }
}
