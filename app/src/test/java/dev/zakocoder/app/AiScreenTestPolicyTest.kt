package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiScreenTestPolicyTest {
    @Test fun ordinaryAndElevatedDefaultsStillAskForScreenAndSsh() {
        listOf(AiPolicy(), AiPermissionPresets.apply(AiPolicy(), AiPermissionPreset.Elevated)).forEach { policy ->
            listOf(AiTool.AndroidTest, AiTool.SshRemote).forEach { tool ->
                assertEquals(AiPermission.Ask, policy.permission(tool))
            }
        }
    }
    @Test fun explicitAdvancedAllowIsHonoredForScreenAndSsh() {
        listOf(AiTool.AndroidTest, AiTool.SshRemote).forEach { tool ->
            AiPermission.entries.forEach { default ->
                val policy = AiPolicy(default, mapOf(tool to AiPermission.Allow))
                assertEquals(AiPermission.Allow, policy.permission(tool))
                assertEquals(R.string.ai_permission_tool_rule, policy.source(tool))
            }
            assertEquals(AiPermission.Allow, AiPolicy(AiPermission.Allow, emptyMap()).permission(tool))
        }
    }
    @Test fun explicitDenyWinsOverAllowDefaultAndPresetsPreserveIt() {
        listOf(AiTool.AndroidTest, AiTool.SshRemote).forEach { tool ->
            val denied = AiPolicy(AiPermission.Allow, mapOf(tool to AiPermission.Deny))
            assertEquals(AiPermission.Deny, denied.permission(tool))
            AiPermissionPreset.entries.forEach { preset ->
                assertEquals(AiPermission.Deny, AiPermissionPresets.apply(denied, preset).permission(tool))
            }
        }
    }
    @Test fun elevatedPresetNeverConvertsScreenDenialToApprovalOrPermission() {
        AiPermissionPreset.entries.forEach { preset ->
            val denied = AiPermissionPresets.apply(AiPolicy(AiPermission.Deny, emptyMap()), preset)
            assertEquals(AiPermission.Deny, denied.permission(AiTool.AndroidTest))
            assertEquals(AiPermission.Ask, AiPermissionPresets.apply(AiPolicy(), preset).permission(AiTool.AndroidTest))
        }
    }
}
