package dev.zakocoder.app

import android.content.ContextWrapper
import dev.zakocoder.app.runtime.FlutterToolchain
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.editor.EditorLanguages
import dev.zakocoder.app.projects.ProjectTemplate
import dev.zakocoder.app.projects.ProjectTemplates
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exports the actual product scaffold for the separate on-device Flutter build acceptance. */
class FlutterDevelopmentDeviceTest {
    @Test fun exportProductScaffoldAndTokenizeDart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "flutter-review/product-template-${java.util.UUID.randomUUID()}")
        check(root.mkdirs())
        android.util.Log.i("FlutterReview", "Product scaffold: ${root.absolutePath}")
        ProjectTemplates.files(ProjectTemplate.Flutter, "123 测试").forEach { (path, content) ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText(content) }
        }
        EditorLanguages(context).prepare()
        val grammar = requireNotNull(GrammarRegistry.getInstance().findGrammar("source.zako.dart"))
        val code = grammar.tokenizeLine("final count = 42; // counter")
        assertFalse(code.isStoppedEarly)
        assertTrue(code.tokens.any { token -> token.scopes.any { it.startsWith("keyword") } })
        assertTrue(code.tokens.any { token -> token.scopes.any { it.startsWith("constant.numeric") } })
        assertTrue(code.tokens.any { token -> token.scopes.any { it.startsWith("comment") } })
        val raw = grammar.tokenizeLine("final path = r'\u0024HOME';")
        assertFalse(raw.isStoppedEarly)
        assertTrue(raw.tokens.any { token -> token.scopes.any { it.startsWith("string") } })
        assertFalse(raw.tokens.any { token -> token.scopes.any { it.startsWith("variable.other") } })
    }
    @Test fun runtimeExportsFlutterOnlyAfterExactReadyMarker(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(base.cacheDir, "flutter-runtime-${java.util.UUID.randomUUID()}")
            val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
            val runtime = LinuxRuntime(context)
            try {
                check(runtime.root.mkdirs())
                val marker = File(runtime.root, FlutterToolchain.MARKER.removePrefix("/"))
                fun assertEnvironment(arguments: List<String>, ready: Boolean) {
                    val path = arguments.single { it.startsWith("PATH=") }.removePrefix("PATH=").split(':')
                    assertEquals(ready, FlutterToolchain.PATH in path)
                    FlutterToolchain.environment.forEach { assertEquals(ready, it in arguments) }
                    assertFalse(arguments.any { it == "CI=true" })
                }
                assertEnvironment(runtime.command(null, AppSettings(), "true").arguments, false)
                check(marker.parentFile!!.mkdirs())
                marker.writeText("wrong-version\n")
                assertEnvironment(runtime.command(null, AppSettings(), "true").arguments, false)
                marker.writeText(FlutterToolchain.VERSION + "\n")
                assertEnvironment(runtime.command(null, AppSettings(), "true").arguments,
                    runCatching { runtime.requireFlutterToolchainSupport() }.isSuccess)
            } finally {
                PrivateFiles.deleteTree(directory)
            }
        }
    }

}
