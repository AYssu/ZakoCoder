package dev.zakocoder.app.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceShellContractTest {
    @Test fun hostShellIsOptInAndUnavailableToReadOnlyPlanning() {
        assertFalse(AiToolRegistry.definitions().any { it.tool == AiTool.DeviceShell })
        assertTrue(AiToolRegistry.definitions(deviceShell = true).any { it.tool == AiTool.DeviceShell })
        assertFalse(AiPlanning.allows(AiTool.DeviceShell))
        assertEquals(AiPermission.Ask, AiPolicy().permission(AiTool.DeviceShell))
        assertTrue(AiTool.DeviceShell.writes)
        assertTrue(AiController.systemForPackages(deviceShell = true).contains("Android HOST"))
        assertFalse(AiController.systemForPackages().contains("The enabled device_shell"))
    }
    @Test fun identityCannotBeSelectedByModelAndTimeoutIsBounded() {
        fun validate(json: JSONObject) = AiToolRegistry.validateArguments(AiTool.DeviceShell, json, json.optString("command"))
        validate(JSONObject().put("command", "id").put("timeout_seconds", 15))
        listOf(JSONObject().put("command", "id").put("root", true),
            JSONObject().put("command", "id").put("timeout_seconds", 601),
            JSONObject().put("command", "id").put("timeout_seconds", 1.5),
            JSONObject().put("command", ""), JSONObject().put("command", "id\u0000" )).forEach {
            try { validate(it); fail("Unexpected admission") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun launchRequiresAnExplicitAndroidPackage() {
        val args = JSONObject().put("action", "launch").put("package_name", "dev.example.fixture")
        AiToolRegistry.validateArguments(AiTool.AndroidTest, args, "launch")
        args.put("package_name", "dev.example.fixture; input tap 0 0")
        try { AiToolRegistry.validateArguments(AiTool.AndroidTest, args, "launch"); fail("Invalid package admitted") }
        catch (_: IllegalArgumentException) { }
    }
}
