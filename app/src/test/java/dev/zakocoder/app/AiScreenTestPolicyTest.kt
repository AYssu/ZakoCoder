package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiScreenTestPolicyTest {
    @Test fun screenControlRequiresApprovalEvenWithPreviouslyUnrestrictedPolicy() {
        listOf(AiPolicy(), AiPolicy(AiPermission.Allow, emptyMap()),
            AiPolicy(overrides = mapOf(AiTool.AndroidTest to AiPermission.Allow))).forEach {
            assertEquals(AiPermission.Ask, it.permission(AiTool.AndroidTest))
            assertEquals(AiPermission.Ask, it.permission(AiTool.SshRemote))
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
