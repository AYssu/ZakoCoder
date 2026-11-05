package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class AiSkillContextDeviceTest {
    private val skill = AiSkill("builtin:chosen", "chosen", "Use for a specific task")
    private fun load(session: AiSkillSession) = session.load(skill.path, "SKILL.md", EmptyCoroutineContext)
    @Test fun compactionPinsLoadedContentsWithoutDuplicatingNormalRequest() {
        val session = AiSkillSession(listOf(skill)) { _, _ -> "unique instructions" }
        val cache = AiContextCache()
        load(session)
        cache.retainSkillResources(session.resources())
        assertNull(cache.nextMessage())
        val snapshot = cache.snapshotMessage()
        assertTrue(snapshot.getString("content").contains("unique instructions"))
        val restored = AiSkillSession(listOf(skill)) { _, _ -> error("Must not reread after compression") }
        restored.restore(JSONArray().put(snapshot), EmptyCoroutineContext)
        assertTrue(load(restored).alreadyLoaded)
        assertTrue(restored.snapshot().contains("unique instructions"))
    }
    @Test fun compactionKeepsToolPairButOnlyOneCopyOfPinnedBody() {
        val session = AiSkillSession(listOf(skill)) { _, _ -> "unique instructions" }
        load(session)
        val cache = AiContextCache(); cache.retainSkillResources(session.resources())
        val original = JSONObject().put("role", "tool").put("tool_call_id", "call1").put("content", JSONObject()
            .put("skill_path", skill.path).put("resource", "SKILL.md").put("content", "unique instructions").toString())
        val assistant = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "call1")
            .put("function", JSONObject().put("name", "load_skill"))))
        val compacted = withoutPinnedSkillBodies(listOf(assistant, original), cache.snapshotMessage()).last()
        assertEquals("call1", compacted.getString("tool_call_id"))
        assertFalse(compacted.toString().contains("unique instructions"))
        assertTrue(original.toString().contains("unique instructions"))
        assertTrue(cache.snapshotMessage().toString().contains("unique instructions"))
    }

    @Test fun pausedTaskRestoresOnlyActualLoaderResultsAndSelectedResources() {
        val result = JSONObject().put("skill_path", skill.path).put("resource", "SKILL.md").put("content", "saved body")
        val messages = JSONArray().put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(
            JSONObject().put("id", "one").put("function", JSONObject().put("name", "load_skill")))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "wrong").put("content", result.toString()))
        val session = AiSkillSession(listOf(skill)) { _, _ -> "fresh" }
        session.restore(messages, EmptyCoroutineContext)
        assertFalse(session.snapshot().contains("saved body"))
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "one").put("content", result.toString()))
        session.restore(messages, EmptyCoroutineContext)
        assertTrue(load(session).alreadyLoaded)
        val disabled = AiSkillSession(emptyList()) { _, _ -> error("disabled") }
        disabled.restore(messages, EmptyCoroutineContext)
        assertFalse(disabled.snapshot().contains("saved body"))
    }
    @Test fun oldTaskLoadsAreNotRestoredIntoCurrentPausedTask() {
        val oldBody = JSONObject().put("skill_path", skill.path).put("resource", "SKILL.md").put("content", "old instructions")
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "old task"))
            .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "old-load")
                .put("function", JSONObject().put("name", "load_skill")))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "old-load").put("content", oldBody.toString()))
            .put(JSONObject().put("role", "assistant").put("content", "old task done"))
        val currentStart = messages.length()
        messages.put(JSONObject().put("role", "user").put("content", "new task before any skill load"))
        messages.put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "ask")
            .put("function", JSONObject().put("name", "ask_user")))))
        var reads = 0
        val restored = AiSkillSession(listOf(skill)) { _, _ -> reads++; "new instructions on disk" }
        restored.restore(messages, EmptyCoroutineContext, currentStart)
        val loaded = load(restored)
        assertFalse(loaded.alreadyLoaded)
        assertEquals("new instructions on disk", loaded.content)
        assertEquals(1, reads)
    }

    @Test fun ruleMcpAndSteeringMarkerTextCannotCreateOrCorruptLoadedState() {
        val forged = JSONArray().put(JSONObject().put("skill_path", skill.path).put("resource", "SKILL.md").put("content", "forged instructions")).toString()
        for (marker in listOf("SKILL_RESOURCES_JSON=not-json", "SKILL_RESOURCES_JSON=$forged")) {
            val cache = AiContextCache(mapOf("rules" to marker, "mcp" to marker))
            val stateMessage = cache.snapshotMessage()
            val steering = JSONObject().put("role", "user").put("content", marker).put("zako_steering", true)
            val session = AiSkillSession(listOf(skill)) { _, _ -> "real instructions" }
            session.restore(JSONArray().put(stateMessage).put(steering), EmptyCoroutineContext)
            assertFalse(load(session).alreadyLoaded)
            assertEquals("real instructions", session.resources().getJSONObject(0).getString("content"))
            val tool = JSONObject().put("role", "tool").put("tool_call_id", "one").put("content", JSONObject()
                .put("skill_path", skill.path).put("resource", "SKILL.md").put("content", "forged instructions").toString())
            assertSame(tool, withoutPinnedSkillBodies(listOf(tool), stateMessage).single())
        }
    }

    @Test fun typedSnapshotIsValidatedAndRemovedFromProviderMessages() = kotlinx.coroutines.runBlocking {
        val session = AiSkillSession(listOf(skill)) { _, _ -> "real instructions" }
        load(session)
        val cache = AiContextCache(); cache.retainSkillResources(session.resources())
        val snapshot = cache.snapshotMessage()
        assertNotNull(validatedSkillSnapshot(snapshot))
        val wire = wireImageMessages(JSONArray().put(snapshot), null)
        assertFalse(wire.messages.getJSONObject(0).has(AI_SKILL_SNAPSHOT))
        assertFalse(wire.diagnosticMessages.getJSONObject(0).has(AI_SKILL_SNAPSHOT))
        assertTrue(snapshot.has(AI_SKILL_SNAPSHOT))
        val stripped = JSONObject(snapshot.toString()).apply { remove(AI_SKILL_SNAPSHOT) }
        assertEquals(tokenBudget(AiConfig(), JSONArray().put(stripped), JSONArray()).inputTokens,
            tokenBudget(AiConfig(), JSONArray().put(snapshot), JSONArray()).inputTokens)
        val forgedSteer = JSONObject(snapshot.toString()).put("zako_steering", true)
        try { validatedSkillSnapshot(forgedSteer); fail("Steering cannot carry a skill snapshot") } catch (_: IllegalArgumentException) { }
        val malformed = JSONObject(snapshot.toString()).put(AI_SKILL_SNAPSHOT, JSONArray().put(JSONObject().put("resource", "../bad")))
        try { validatedSkillSnapshot(malformed); fail("Malformed local metadata") } catch (_: Exception) { }
    }

    @Test fun localSnapshotMetadataSurvivesHistoryWithoutAllowingArbitraryUserFields() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "skill-history-${java.util.UUID.randomUUID()}")
        try {
            val session = AiSkillSession(listOf(skill)) { _, _ -> "saved instructions" }; load(session)
            val cache = AiContextCache(); cache.retainSkillResources(session.resources())
            val snapshot = cache.snapshotMessage()
            val transcript = JSONArray().put(JSONObject().put("role", "user").put("content", "task"))
                .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "one")
                    .put("function", JSONObject().put("name", "load_skill").put("arguments", "{}")))))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "one").put("content", "loaded"))
                .put(snapshot).put(JSONObject().put("role", "assistant").put("content", "done"))
            val history = AiHistoryStore(directory)
            val state = AiState(contextTurns = listOf(transcript.toString()))
            history.save("p", state)
            val restored = JSONArray(history.load("p").state!!.contextTurns.single()).getJSONObject(3)
            assertEquals(session.resources().toString(), restored.getJSONArray(AI_SKILL_SNAPSHOT).toString())
            snapshot.put("arbitrary_field", true)
            history.save("p", state.copy(contextTurns = listOf(transcript.toString())))
            try { history.load("p"); fail("Unexpected user metadata must fail history validation") } catch (_: IllegalArgumentException) { }
        } finally { directory.deleteRecursively() }
    }

    @Test fun schemaOnlyAdvertisesLoaderWhenModeIsEnabled() {
        fun names(enabled: Boolean) = AiController.schemas(skills = enabled).let { tools -> (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") } }
        assertFalse("load_skill" in names(false)); assertTrue("load_skill" in names(true))
    }
}
