package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import dev.zakocoder.app.device.DEVICE_APP_RESPONSE_MAX_BYTES
import dev.zakocoder.app.device.LaunchableDeviceApp
import dev.zakocoder.app.device.launchableDeviceAppsResponse
import dev.zakocoder.app.device.selectLaunchableDeviceApps
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Contract checks use Android's real JSON implementation; no model, files or device settings. */
class AiToolRegistryDeviceTest {
    @Test fun schemasHaveExactlyOneDefinitionPerAvailableToolAndFreshMutableObjects() {
        for (screen in listOf(false, true)) for (ssh in listOf(false, true)) for (skills in listOf(false, true)) for (delegation in listOf(false, true)) for (browser in listOf(false, true)) {
            val schema = AiController.schemas(screen, ssh, skills, delegation, browser)
            val names = (0 until schema.length()).map { schema.getJSONObject(it).getJSONObject("function").getString("name") }
            val expected = AiTool.entries.filter { (screen || it != AiTool.AndroidTest) && (ssh || it != AiTool.SshRemote) && (skills || it != AiTool.LoadSkill) && (delegation || it != AiTool.DelegateReadOnly) && (browser || it != AiTool.Browser) && it != AiTool.DeviceShell }.map { it.wire }
            assertEquals(expected.toSet(), names.toSet())
            assertEquals(expected.size, names.size)
            repeat(schema.length()) { index ->
                val parameters = schema.getJSONObject(index).getJSONObject("function").getJSONObject("parameters")
                assertFalse(parameters.getBoolean("additionalProperties"))
                assertTrue(parameters.getJSONObject("properties").has("reason"))
            }
            schema.getJSONObject(0).getJSONObject("function").put("name", "tampered")
            assertEquals(names.first(), AiController.schemas(screen, ssh, skills, delegation, browser).getJSONObject(0).getJSONObject("function").getString("name"))
        }
    }

    @Test fun descriptorsCoverMetadataPermissionTargetsAndUnknownNamesAreNotExecutable() {
        val all = AiToolRegistry.definitions(screen = true, ssh = true, skills = true, delegation = true, browser = true, deviceShell = true)
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
        assertTrue("launch" in actions("android_test"))
        assertTrue(actions("android_test").none { it in setOf("prepare", "install") })
        assertEquals(AiToolRegistry.SSH_ACTIONS, actions("ssh_remote"))
    }

    @Test fun screenWaitAndScrollSchemasDescribeBoundedObservationAndFreshSnapshots() {
        val schema = AiToolRegistry.definition(AiTool.AndroidTest).schema.getJSONObject("function")
        val properties = schema.getJSONObject("parameters").getJSONObject("properties")
        val actions = properties.getJSONObject("action").getJSONArray("enum")
        val values = (0 until actions.length()).map(actions::getString)
        assertTrue("wait_until" in values)
        assertTrue("scroll" in values)
        val timeout = properties.getJSONObject("timeout_ms")
        assertEquals("integer", timeout.getString("type"))
        assertEquals(1, timeout.getInt("minimum"))
        assertEquals(20000, timeout.getInt("maximum"))
        assertTrue(schema.getString("description").contains("without repeating side effects"))
        assertTrue(properties.getJSONObject("snapshot").getString("description").contains("returned observation"))
    }

    @Test fun screenAppDiscoveryRequiresNoApkAndAcceptsOnlyBoundedPlainFilters() {
        val schema = AiToolRegistry.definition(AiTool.AndroidTest).schema.getJSONObject("function")
        assertTrue(schema.getString("description").contains("list_apps"))
        assertTrue("list_apps" in AiToolRegistry.SCREEN_TEST_ACTIONS)
        fun validate(args: JSONObject) = AiToolRegistry.validateArguments(AiTool.AndroidTest, args, "list_apps")
        validate(JSONObject().put("action", "list_apps"))
        validate(JSONObject().put("action", "list_apps").put("text", "浏览器"))
        validate(JSONObject().put("action", "list_apps").put("text", "x".repeat(200)))
        for (text in listOf("x".repeat(201), "x\u0000", "x\n", 123, JSONObject.NULL)) {
            try { validate(JSONObject().put("action", "list_apps").put("text", text)); fail("Invalid discovery filter accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try { validate(JSONObject().put("action", "list_apps").put("apk", "old.apk")); fail("APK selector accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun screenAppDiscoveryReturnsCompleteJsonUnderTheUtf8Budget() {
        val page = selectLaunchableDeviceApps((1..120).map { index ->
            LaunchableDeviceApp("dev.fixture.app$index", "🐟".repeat(80))
        }, "")
        val encoded = launchableDeviceAppsResponse(page, 7)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= DEVICE_APP_RESPONSE_MAX_BYTES)
        val parsed = JSONObject(encoded)
        assertTrue(parsed.getBoolean("truncated"))
        assertEquals(120, parsed.getInt("matched_count"))
        assertEquals(7, parsed.getInt("display_id"))
        val apps = parsed.getJSONArray("apps")
        assertEquals(apps.length(), parsed.getInt("returned_count"))
        assertTrue(apps.length() in 1..99)
        repeat(apps.length()) { index -> assertEquals("🐟".repeat(80), apps.getJSONObject(index).getString("label")) }
    }

    @Test fun screenWaitAcceptsOnlyNonBlankTextAndExactBoundedIntegerTimeouts() {
        fun validate(args: JSONObject) = AiToolRegistry.validateArguments(AiTool.AndroidTest, args, "wait_until")
        fun wait(text: Any = "Counter: 1", timeout: Any? = null) = JSONObject().put("action", "wait_until").put("text", text).apply {
            if (timeout != null) put("timeout_ms", timeout)
        }
        validate(wait())
        validate(wait(timeout = 1))
        validate(wait(timeout = 20000L))
        for (timeout in listOf(0, -1, 20001, Long.MAX_VALUE, 1.5, 1000.0, "1000", true, JSONObject.NULL)) {
            try { validate(wait(timeout = timeout)); fail("Invalid wait timeout accepted: $timeout") }
            catch (_: IllegalArgumentException) { }
        }
        for (text in listOf("", "  \n", "x".repeat(1001), 123, JSONObject.NULL)) {
            try { validate(wait(text)); fail("Invalid expected text accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try { validate(JSONObject().put("action", "observe").put("timeout_ms", 1000)); fail("Wait-only timeout accepted for observe") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun screenScrollRequiresTheObservedNodeAndADefinedDirection() {
        val valid = JSONObject().put("action", "scroll").put("snapshot", "snapshot-token").put("node", "n1").put("direction", "up")
        listOf("up", "down", "left", "right").forEach { direction ->
            AiToolRegistry.validateArguments(AiTool.AndroidTest, JSONObject(valid.toString()).put("direction", direction), "scroll")
        }
        for (key in listOf("snapshot", "node", "direction")) {
            val missing = JSONObject(valid.toString()).apply { remove(key) }
            try { AiToolRegistry.validateArguments(AiTool.AndroidTest, missing, "scroll"); fail("Missing $key accepted") }
            catch (_: IllegalArgumentException) { }
        }
        for (direction in listOf("", "diagonal", 42, JSONObject.NULL)) {
            try { AiToolRegistry.validateArguments(AiTool.AndroidTest, JSONObject(valid.toString()).put("direction", direction), "scroll"); fail("Invalid scroll direction accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
