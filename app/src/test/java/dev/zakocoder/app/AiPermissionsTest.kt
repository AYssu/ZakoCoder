package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiPermissionsTest {
    @Test fun initialPolicyAsksForSideEffectsAndAllowsOnlyReadAndPlan() {
        val policy = AiPolicy()
        assertEquals(AiPermission.Ask, policy.default)
        listOf(AiTool.WriteFile, AiTool.MakeDirectory, AiTool.RunCommand, AiTool.StartTerminal, AiTool.SendTerminalInput, AiTool.CloseTerminal, AiTool.ReadTerminal, AiTool.ReadCommandLog).forEach { assertEquals(AiPermission.Ask, policy.permission(it)) }
        listOf(AiTool.ReadFile, AiTool.ListFiles, AiTool.SearchFiles, AiTool.UpdatePlan, AiTool.ListTerminals).forEach { assertEquals(AiPermission.Allow, policy.permission(it)) }
    }
    @Test fun explicitToolRuleOverridesEveryDefaultIncludingDenyAndAllow() {
        AiPermission.entries.forEach { default ->
            AiPermission.entries.forEach { override ->
                val policy = AiPolicy(default, mapOf(AiTool.RunCommand to override))
                assertEquals(override, policy.permission(AiTool.RunCommand))
                assertEquals(default, policy.permission(AiTool.WriteFile))
                assertEquals(R.string.ai_permission_tool_rule, policy.source(AiTool.RunCommand))
                assertEquals(R.string.ai_permission_default_rule, policy.source(AiTool.WriteFile))
            }
        }
    }
    @Test fun removingAnExceptionRestoresDefaultInsteadOfInitialReadAllowance() {
        val policy = AiPolicy(AiPermission.Deny, mapOf(AiTool.ReadFile to AiPermission.Allow))
        val changed = policy.copy(overrides = policy.overrides - AiTool.ReadFile)
        assertEquals(AiPermission.Deny, changed.permission(AiTool.ReadFile))
        assertEquals(AiPermission.Allow, policy.permission(AiTool.ReadFile))
    }
}
