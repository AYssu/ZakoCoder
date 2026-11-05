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
        cache.retain("loaded_skills", session.snapshot())
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
        val cache = AiContextCache(); cache.retain("loaded_skills", session.snapshot())
        val original = JSONObject().put("role", "tool").put("tool_call_id", "call1").put("content", JSONObject()
            .put("skill_path", skill.path).put("resource", "SKILL.md").put("content", "unique instructions").toString())
        val compacted = withoutPinnedSkillBodies(listOf(original), cache.snapshotMessage()).single()
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
    @Test fun schemaOnlyAdvertisesLoaderWhenModeIsEnabled() {
        fun names(enabled: Boolean) = AiController.schemas(skills = enabled).let { tools -> (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") } }
        assertFalse("load_skill" in names(false)); assertTrue("load_skill" in names(true))
    }
}
