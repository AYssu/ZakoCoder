package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.coroutines.EmptyCoroutineContext
import java.io.File

/** Reproducible request construction comparison. These are local estimates, never provider usage/cache hits. */
class AiSkillLoadingComparisonTest {
    @Test fun compareSameTaskWithBundledDefaultSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ids = linkedMapOf("lean-build" to "lean", "surgical-patch" to "patch", "investigate-first" to "investigate", "verify-and-stop" to "verify", "caveman" to "caveman")
        val selected = ids.map { (name, description) -> AiSkill("builtin:$name", name,
            context.getString(context.resources.getIdentifier("ai_builtin_$description", "string", context.packageName)), builtIn = true) }
        val bodies = ids.keys.associateWith { context.assets.open("skills/$it/SKILL.md").bufferedReader().use { reader -> reader.readText() } }
        val session = AiSkillSession(selected) { skill, _ -> bodies.getValue(skill.name) }
        fun messages(guidance: String) = JSONArray().put(JSONObject().put("role", "system").put("content", "You are a coding assistant; follow existing tool permissions."))
            .put(JSONObject().put("role", "user").put("content", guidance))
            .put(JSONObject().put("role", "user").put("content", "定位一个登录错误，先调查原因，不修改文件。"))
        fun estimate(messages: JSONArray, tools: JSONArray): Long = estimateAiTokens(JSONObject().put("messages", messages).put("tools", tools).toString()) + messages.length() * 8L + tools.length() * 16L
        val eager = selected.joinToString("\n\n") { "Skill: ${it.name}; source: ${it.path}\n${bodies.getValue(it.name)}" }
        val initialEager = estimate(messages(eager), AiController.schemas())
        val initialLazy = estimate(messages(session.catalog()), AiController.schemas(skills = true))
        val loaded = session.load("builtin:investigate-first", "SKILL.md", EmptyCoroutineContext)
        val next = messages(session.catalog())
            .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "load1").put("type", "function").put("function", JSONObject().put("name", "load_skill").put("arguments", "{\"skill\":\"builtin:investigate-first\"}")))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "load1").put("content", loaded.content))
        val afterOne = estimate(next, AiController.schemas(skills = true))
        File(context.cacheDir, "skill-loading-request-estimate.json").writeText(JSONObject()
            .put("kind", "local_request_estimate_only").put("eager_initial", initialEager)
            .put("on_demand_initial", initialLazy).put("on_demand_after_one_load", afterOne)
            .put("provider_usage_and_cache", "not_measured").toString(2))
        println("SKILL_REQUEST_ESTIMATE eager_initial=$initialEager on_demand_initial=$initialLazy on_demand_after_one_load=$afterOne; provider_usage_and_cache=not_measured")
        assertTrue(initialLazy < initialEager)
        assertTrue(afterOne > initialLazy)
        assertFalse(session.catalog().contains(bodies.getValue("surgical-patch")))
    }
}
