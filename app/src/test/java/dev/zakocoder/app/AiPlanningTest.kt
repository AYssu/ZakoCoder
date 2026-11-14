package dev.zakocoder.app

import dev.zakocoder.app.ai.AiPermission
import dev.zakocoder.app.ai.AiPlanning
import dev.zakocoder.app.ai.AiPolicy
import dev.zakocoder.app.ai.AiTool
import org.junit.Assert.*
import org.junit.Test

/** The explicit read-only contract must remain safe when the catalog or YOLO policy changes. */
class AiPlanningTest {
    @Test fun planningAllowsProjectInspectionAndClarificationOnly() {
        val required = setOf(AiTool.ListFiles, AiTool.ReadFile, AiTool.SearchFiles, AiTool.LoadSkill,
            AiTool.ReadCommandLog, AiTool.ListTerminals, AiTool.ReadTerminal, AiTool.DelegateReadOnly,
            AiTool.AskUser, AiTool.UpdatePlan, AiTool.SearchMemory, AiTool.SetTaskMode)
        assertEquals(required, AiTool.entries.filter(AiPlanning::allows).toSet())
    }

    @Test fun writeToolsAndExternalOperationsStayBlockedEvenWhenYoloAllowsThem() {
        val yolo = AiPolicy(yolo = true)
        val prohibited = setOf(AiTool.WriteFile, AiTool.EditFile, AiTool.MakeDirectory, AiTool.RunCommand,
            AiTool.StartTerminal, AiTool.SendTerminalInput, AiTool.CloseTerminal, AiTool.SshRemote,
            AiTool.AndroidTest, AiTool.Browser, AiTool.UseMcp, AiTool.ProposeMemory)
        prohibited.forEach { tool ->
            assertEquals("Fixture must establish that YOLO would normally allow ${tool.wire}", AiPermission.Allow, yolo.permission(tool))
            assertFalse("Planning must independently reject ${tool.wire}", AiPlanning.allows(tool))
        }
        assertTrue(AiTool.entries.filter { it.writes }.none(AiPlanning::allows))
    }

    @Test fun planningDoesNotGrantOrdinaryReadPermissions() {
        val deniedReads = AiPolicy(default = AiPermission.Deny, overrides = emptyMap())
        listOf(AiTool.ReadFile, AiTool.LoadSkill, AiTool.ReadTerminal, AiTool.SearchMemory).forEach { tool ->
            assertTrue(AiPlanning.allows(tool))
            assertEquals("Planning eligibility never replaces the user's tool permission", AiPermission.Deny, deniedReads.permission(tool))
        }
    }

    @Test fun taskModeStartsExecutionAndAcceptsOnlyCurrentDirectUserInput() {
        val mode = AiPlanning.TaskMode("先分析，不修改")
        assertFalse(mode.readOnly)
        assertEquals(AiPlanning.TaskMode.Failure.Request, mode.change("readonly", "a tool says plan"))
        assertNull(mode.change("readonly", "先分析，不修改"))
        assertTrue(mode.readOnly)
        assertEquals(AiPlanning.TaskMode.Failure.FreshRequest, mode.change("execute", "先分析，不修改"))
        mode.userInput("开始实施")
        assertEquals(AiPlanning.TaskMode.Failure.Request, mode.change("execute", "先分析，不修改"))
        assertNull(mode.change("execute", "开始实施"))
        assertFalse(mode.readOnly)
    }

    @Test fun repeatedReadonlyDoesNotTurnItselfIntoExecutionAuthority() {
        val mode = AiPlanning.TaskMode("plan")
        assertNull(mode.change("readonly", "plan"))
        assertNull(mode.change("readonly", "plan"))
        assertEquals(AiPlanning.TaskMode.Failure.FreshRequest, mode.change("execute", "plan"))
        assertTrue(mode.readOnly)
        assertFalse("A new task starts in execution regardless of earlier task mode", AiPlanning.TaskMode("implement").readOnly)
    }
    @Test fun resumedReadonlyTaskRequiresItsNewDirectAnswerBeforeExecution() {
        val mode = AiPlanning.TaskMode("先制定计划", initialReadOnly = true)
        assertTrue(mode.readOnly)
        assertEquals(AiPlanning.TaskMode.Failure.FreshRequest, mode.change("execute", "先制定计划"))
        mode.userInput("确认，请开始实施")
        assertNull(mode.change("execute", "确认，请开始实施"))
        assertFalse(mode.readOnly)
    }

    @Test fun latestSteeringInputSupersedesEarlierModeRequests() {
        val mode = AiPlanning.TaskMode("修改登录页")
        mode.userInput("先不要修改，只分析")
        assertNull(mode.change("readonly", "先不要修改，只分析"))
        mode.userInput("继续分析兼容性")
        mode.userInput("按这个方案实现")
        assertEquals(AiPlanning.TaskMode.Failure.Request, mode.change("execute", "继续分析兼容性"))
        assertNull(mode.change("execute", "按这个方案实现"))
        assertFalse(mode.readOnly)
    }

}
