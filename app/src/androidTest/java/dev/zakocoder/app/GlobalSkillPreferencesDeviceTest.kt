package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiBuiltinSkills
import dev.zakocoder.app.ai.AiGlobalSkills
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Exercises on-device AtomicFile behavior without project files, network or model requests. */
class GlobalSkillPreferencesDeviceTest {
    @Test fun emptySelectionAndOnDemandPersistTogetherAndIgnoreLegacyPreferences() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "global-skill-store-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val prefix = "global-skill-test-${java.util.UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        try {
            context.getSharedPreferences("shared-skills", 0).edit().putStringSet("enabled", setOf(".zakocoder/skills/old/SKILL.md")).apply()
            val store = AiGlobalSkills(context)
            assertEquals(AiBuiltinSkills.defaults.sorted(), store.load().enabled.sorted())
            store.save(AiGlobalSkills.Selection(emptyList(), true))
            assertEquals(AiGlobalSkills.Selection(emptyList(), true), AiGlobalSkills(context).load())
            val zipPath = ".zakocoder/skills/bundle-123/skill-0/SKILL.md"
            val selected = AiGlobalSkills.Selection(listOf("builtin:frontend-design", zipPath), false)
            store.save(selected)
            assertEquals(selected, AiGlobalSkills(context).load())
            try {
                store.save(AiGlobalSkills.Selection(listOf(".zakocoder/skills/../outside/SKILL.md")))
                fail("Traversal must not replace the valid selection")
            } catch (_: IllegalArgumentException) { /* Expected; previous atomic selection remains intact. */ }
            assertEquals(selected, AiGlobalSkills(context).load())
        } finally {
            context.getSharedPreferences("shared-skills", 0).edit().clear().apply()
            context.getSharedPreferences("first-run-setup", 0).edit().clear().apply()
            directory.deleteRecursively()
        }
    }
    @Test fun corruptOrUnsupportedSelectionIsReportedWithoutOverwritingItWithDefaults() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "global-skill-corrupt-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        val file = File(directory, "shared-skills-selection.json")
        try {
            val store = AiGlobalSkills(context)
            val invalid = listOf(
                "{broken", "{\"version\":2,\"enabled\":[],\"onDemand\":false}",
                "{\"version\":1,\"enabled\":[123],\"onDemand\":false}",
                "{\"version\":1,\"enabled\":[],\"onDemand\":\"false\"}",
                "{\"version\":\"1\",\"enabled\":[],\"onDemand\":false}",
                JSONObject().put("version", 1).put("enabled", org.json.JSONArray(List(17) { "builtin:lean-build" })).put("onDemand", false).toString(),
            )
            invalid.forEach { document ->
                file.writeText(document)
                assertTrue("Invalid configuration must be observable", runCatching { store.load() }.isFailure)
                assertEquals("Failed reads do not rewrite configuration", document, file.readText())
            }
            store.save(AiGlobalSkills.Selection(emptyList()))
            assertEquals(AiGlobalSkills.Selection(emptyList(), false), store.load())
        } finally { directory.deleteRecursively() }
    }

}
