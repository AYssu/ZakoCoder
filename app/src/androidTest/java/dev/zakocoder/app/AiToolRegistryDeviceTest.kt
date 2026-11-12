package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Contract checks use Android's real JSON implementation; no model, files or device settings. */
class AiToolRegistryDeviceTest {
    @Test fun schemasHaveExactlyOneDefinitionPerAvailableToolAndFreshMutableObjects() {
        for (screen in listOf(false, true)) for (ssh in listOf(false, true)) for (skills in listOf(false, true)) for (delegation in listOf(false, true)) {
            val schema = AiController.schemas(screen, ssh, skills, delegation)
            val names = (0 until schema.length()).map { schema.getJSONObject(it).getJSONObject("function").getString("name") }
            val expected = AiTool.entries.filter { (screen || it != AiTool.AndroidTest) && (ssh || it != AiTool.SshRemote) && (skills || it != AiTool.LoadSkill) && (delegation || it != AiTool.DelegateReadOnly) }.map { it.wire }
            assertEquals(expected.toSet(), names.toSet())
            assertEquals(expected.size, names.size)
            repeat(schema.length()) { index ->
                val parameters = schema.getJSONObject(index).getJSONObject("function").getJSONObject("parameters")
                assertFalse(parameters.getBoolean("additionalProperties"))
                assertTrue(parameters.getJSONObject("properties").has("reason"))
            }
            schema.getJSONObject(0).getJSONObject("function").put("name", "tampered")
            assertEquals("update_plan", AiController.schemas(screen, ssh, skills, delegation).getJSONObject(0).getJSONObject("function").getString("name"))
        }
    }

    @Test fun descriptorsCoverMetadataPermissionTargetsAndUnknownNamesAreNotExecutable() {
        val all = AiToolRegistry.definitions(screen = true, ssh = true, skills = true, delegation = true)
        assertEquals(AiTool.entries.toSet(), all.map { it.tool }.toSet())
        assertEquals(AiTool.entries.size, all.size)
        assertNull(AiToolRegistry.named("unregistered_tool"))
        all.forEach { definition ->
            assertSame(definition, AiToolRegistry.named(definition.wire))
            assertSame(definition, AiToolRegistry.definition(definition.tool))
            assertEquals(definition.wire, definition.schema.getJSONObject("function").getString("name"))
            assertEquals(definition.tool.writes, definition.writes)
            AiPermission.entries.forEach { permission ->
                val policy = AiPolicy(permission, emptyMap())
                assertEquals(permission, definition.permission(policy))
                assertEquals(policy.source(definition.tool), definition.permissionSource(policy))
            }
        }
    }

    @Test fun invalidParametersAreRejectedBeforePlatformPreviewOrExecution() {
        fun rejects(tool: AiTool, args: JSONObject, target: String = "") {
            try { AiToolRegistry.validateArguments(tool, args, target); fail("Expected rejection: ${tool.wire}") }
            catch (_: IllegalArgumentException) { }
        }
        rejects(AiTool.RunCommand, JSONObject().put("timeout_seconds", 86401), "echo hello")
        rejects(AiTool.RunCommand, JSONObject(), "")
        rejects(AiTool.StartTerminal, JSONObject().put("command", "echo\u0000hello"))
        rejects(AiTool.SendTerminalInput, JSONObject().put("terminal_id", "a").put("key", "invalid"))
        rejects(AiTool.SendTerminalInput, JSONObject().put("terminal_id", "a").put("text", "\u0001"))
        rejects(AiTool.AndroidTest, JSONObject().put("action", "unsupported"))
        rejects(AiTool.AndroidTest, JSONObject().put("action", "prepare").put("path", ""))
        rejects(AiTool.AndroidTest, JSONObject().put("action", "observe").put("package", "unapproved"))
        rejects(AiTool.AndroidTest, JSONObject().put("action", "observe").put("path", "old.apk"))
        rejects(AiTool.SshRemote, JSONObject().put("action", "exec").put("password", "must-not-be-model-input"))
        rejects(AiTool.UseMcp, JSONObject().put("server_id", "x").put("method", "tools/list").put("params", JSONArray()))
        rejects(AiTool.ReadCommandLog, JSONObject().put("run_id", "not-a-uuid"))
        rejects(AiTool.LoadSkill, JSONObject().put("skill", 12))
        rejects(AiTool.LoadSkill, JSONObject().put("skill", "builtin:chosen").put("resource", "../outside"))
        rejects(AiTool.LoadSkill, JSONObject().put("skill", "builtin:chosen").put("resource", JSONObject.NULL))
        rejects(AiTool.LoadSkill, JSONObject().put("skill", "builtin:chosen").put("authorization", "not-allowed"))
    }

    @Test fun editDescriptorPreservesStructuredArgumentsForPreviewWithoutReparsing() {
        val args = JSONObject().put("path", "src/Main.kt").put("edits", JSONArray().put(
            JSONObject().put("old_text", "before").put("new_text", "after")))
        val definition = AiToolRegistry.definition(AiTool.EditFile)
        val edits = checkNotNull(definition.validate(args, "src/Main.kt"))
        assertEquals(listOf(AiTextEdit("before", "after")), edits)
        val properties = definition.schema.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertEquals(AiTextEdits.MAX_EDITS, properties.getJSONObject("edits").getInt("maxItems"))
        try { definition.validate(JSONObject().put("path", "src/Main.kt").put("edits", JSONArray()), "src/Main.kt"); fail("Empty edits") }
        catch (_: AiFileFailure) { }
    }

    @Test fun targetAndSharedActionVocabularyMatchWireContracts() {
        assertEquals("builtin:chosen · SKILL.md", AiToolRegistry.target(AiTool.LoadSkill, JSONObject().put("skill", "builtin:chosen"), "Plan"))
        assertEquals("echo hello", AiToolRegistry.target(AiTool.RunCommand, JSONObject().put("command", "echo hello"), "Plan"))
        assertEquals("Plan", AiToolRegistry.target(AiTool.UpdatePlan, JSONObject(), "Plan"))
        assertEquals("server · tools/call · search", AiToolRegistry.target(AiTool.UseMcp,
            JSONObject().put("server_id", "server").put("method", "tools/call").put("params", JSONObject().put("name", "search")), "Plan"))
        val all = AiController.schemas(screen = true, ssh = true)
        fun actions(name: String): List<String> {
            val schema = (0 until all.length()).map { all.getJSONObject(it).getJSONObject("function") }.single { it.getString("name") == name }
            val values = schema.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
            return (0 until values.length()).map { values.getString(it) }
        }
        assertEquals(AiToolRegistry.SCREEN_TEST_ACTIONS, actions("android_test"))
        assertTrue("authorize" in actions("android_test"))
        assertTrue(actions("android_test").none { it in setOf("prepare", "install", "launch") })
        assertEquals(AiToolRegistry.SSH_ACTIONS, actions("ssh_remote"))
    }
}
