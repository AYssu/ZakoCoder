package dev.zakocoder.app

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.editor.lsp.EditorLspController
import dev.zakocoder.app.editor.lsp.LspPhase
import dev.zakocoder.app.editor.lsp.LspServer
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Ubuntu servers and Sora transport. Missing packages are skipped, never treated as validation. */
class EditorLspMultilanguageDeviceTest {
    @Before fun foregroundForDeviceUi() = warmDeviceActivity()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Fixture(
        val server: LspServer, val path: String, val text: String, val fixed: String,
        val completionLine: Int, val completionColumn: Int, val expected: String,
        val files: Map<String, String> = emptyMap(),
    )
    private fun detail(controller: EditorLspController) = controller.state.value.let {
        "phase=${it.phase}, diagnostics=${it.diagnosticCount}, code=${it.code}, stderr=${it.stderr.takeLast(3000)}"
    }
    private suspend fun await(stage: String, detail: () -> String, condition: () -> Boolean) {
        Log.i("ZakoLspMultiTest", "Starting $stage")
        try { withTimeout(210_000) { while (!condition()) delay(100) } }
        catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AssertionError("$stage timed out: ${detail()}", timeout)
        }
        Log.i("ZakoLspMultiTest", "Completed $stage: ${detail()}")
    }
    private fun exercise(fixture: Fixture) = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val app = (context.applicationContext as ZakoApplication).workbench
            await("workbench", { "loading=${app.state.value.loading}, busy=${app.state.value.busy}" }) {
                !app.state.value.loading && !app.state.value.busy
            }
            assumeTrue("Ubuntu is required; NOT VALIDATED without it", app.state.value.installed)
            val result = app.runtime.execute(fixture.server.detectScript, null, app.state.value.settings)
            assumeTrue("${fixture.server} package missing; NOT VALIDATED", result.exitCode == 0)
            val project = withContext(Dispatchers.IO) { app.projects.create("lsp-${fixture.server.name.lowercase()}-${UUID.randomUUID()}") }
            val directory = withContext(Dispatchers.IO) { app.projects.directory(project.id) }
            var controller: EditorLspController? = null
            var view: CodeEditor? = null
            try {
                val source = File(directory, fixture.path)
                // The baseline is valid on disk. Diagnostics and completion must use UNSAVED editor text.
                withContext(Dispatchers.IO) {
                    fixture.files.forEach { (path, text) -> File(directory, path).apply { parentFile!!.mkdirs(); writeText(text) } }
                    source.parentFile!!.mkdirs()
                    source.writeText(fixture.fixed)
                }
                withContext(Dispatchers.Main) {
                    scenario.onActivity { activity ->
                        val editor = CodeEditor(activity).apply { setText(fixture.text) }
                        view = editor
                        activity.setContentView(editor)
                        controller = EditorLspController(context, app.runtime, { app.state.value.settings }, { app.state.value.installed }).also {
                            it.attach(editor, project.id, fixture.path) { EmptyLanguage() }
                        }
                    }
                }
                val connected = requireNotNull(controller)
                await("${fixture.server}_initialize", { detail(connected) }) {
                    connected.state.value.phase in setOf(LspPhase.Ready, LspPhase.Failed, LspPhase.MissingServer)
                }
                assertEquals(detail(connected), LspPhase.Ready, connected.state.value.phase)
                await("${fixture.server}_unsaved_diagnostic", { detail(connected) }) { connected.state.value.diagnosticCount > 0 }
                val labels = withTimeout(40_000) { connected.completionLabels(fixture.completionLine, fixture.completionColumn) }
                Log.i("ZakoLspMultiTest", "${fixture.server} completion: ${labels.take(40)}")
                assertTrue("${fixture.server}: expected ${fixture.expected}; actual=$labels; ${detail(connected)}", labels.any { fixture.expected in it })
                withContext(Dispatchers.Main) { requireNotNull(view).setText(fixture.fixed) }
                await("${fixture.server}_fixed_diagnostics", { detail(connected) }) { connected.state.value.diagnosticCount == 0 }
                withContext(Dispatchers.IO) { assertEquals("LSP must not save the document", fixture.fixed, source.readText()) }
                withContext(Dispatchers.Main) { connected.detach() }
                assertEquals(LspPhase.Idle, connected.state.value.phase)
                withContext(Dispatchers.Main) { assertTrue(requireNotNull(view).editorLanguage is EmptyLanguage) }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { controller?.close(); view?.release() }
                withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
            }
        }
    }

    @Test(timeout = 720000) fun javaDiagnosticsAndCompletion() = exercise(Fixture(
        LspServer.Java, "Main.java",
        "class Main {\n void run() {\n  int count = \"wrong\";\n  \"fixture\".sub\n }\n}\n",
        "class Main {\n void run() {\n  System.out.println(\"fixture\".substring(1));\n }\n}\n", 3, 15, "substring",
    ))

    /** Official server requires an imported JVM workspace; this fixture is self-contained and offline. */
    @Test(timeout = 720000) fun kotlinWorkspaceDiagnosticsAndCompletion() = exercise(Fixture(
        LspServer.Kotlin, "src/Main.kt",
        "fun main() {\n val count: Int = \"wrong\"\n \"fixture\".sub\n}\n",
        "fun main() {\n println(\"fixture\".substring(1))\n}\n", 2, 14, "substring",
        mapOf("workspace.json" to """
{
  "modules": [
    {
      "name": "fixture",
      "dependencies": [
        {
          "type": "inheritedSdk"
        },
        {
          "type": "moduleSource"
        },
        {
          "type": "library",
          "name": "stdlib-fixture",
          "scope": "compile"
        }
      ],
      "contentRoots": [
        {
          "path": "<WORKSPACE>/",
          "sourceRoots": [
            {
              "path": "<WORKSPACE>/src",
              "type": "java-source"
            }
          ]
        }
      ]
    }
  ],
  "libraries": [
    {
      "name": "stdlib-fixture",
      "roots": [
        {
          "path": "/opt/zakocoder/lsp/kotlin/263.7084.0/lib/util-8.jar"
        }
      ],
      "type": null
    }
  ],
  "kotlinSettings": [
    {
      "name": "Kotlin",
      "sourceRoots": [
        "<WORKSPACE>/src"
      ],
      "configFileItems": [],
      "module": "fixture",
      "useProjectSettings": false,
      "implementedModuleNames": [],
      "dependsOnModuleNames": [],
      "additionalVisibleModuleNames": [],
      "productionOutputPath": null,
      "testOutputPath": null,
      "sourceSetNames": [],
      "isTestModule": false,
      "externalProjectId": "",
      "isHmppEnabled": false,
      "pureKotlinSourceFolders": [],
      "kind": "default",
      "compilerArguments": "J{\"jvmTarget\":\"17\",\"languageVersion\":\"2.2\",\"apiVersion\":\"2.2\"}",
      "additionalArguments": null,
      "scriptTemplates": null,
      "scriptTemplatesClasspath": null,
      "outputDirectoryForJsLibraryFiles": null,
      "targetPlatform": "JVM [17]",
      "externalSystemRunTasks": [],
      "version": 5,
      "flushNeeded": false
    }
  ]
}
        """.trimIndent()),
    ))

    @Test(timeout = 720000) fun cDiagnosticsAndCompletion() = exercise(Fixture(
        LspServer.Cpp, "main.c",
        "int fixture_function(void);\nint main(void) {\n unknown_fixture_symbol;\n fixt\n}\n",
        "int fixture_function(void) { return 0; }\nint main(void) { return fixture_function(); }\n", 3, 5, "fixture_function",
    ))

    @Test(timeout = 720000) fun cppDiagnosticsAndCompletion() = exercise(Fixture(
        LspServer.Cpp, "main.cpp",
        "struct Fixture { int member; };\nint main() {\n Fixture value;\n unknown_fixture_symbol;\n value.mem\n}\n",
        "struct Fixture { int member; };\nint main() { Fixture value{0}; return value.member; }\n", 4, 10, "member",
    ))

    @Test(timeout = 720000) fun goDiagnosticsAndCompletion() = exercise(Fixture(
        LspServer.Go, "main.go",
        "package main\nimport \"fmt\"\nfunc main() {\n var count int = \"wrong\"\n fmt.Pr\n}\n",
        "package main\nimport \"fmt\"\nfunc main() { fmt.Println(1) }\n", 4, 7, "Println",
        mapOf("go.mod" to "module example.test/lspfixture\n\ngo 1.22\n"),
    ))

    @Test(timeout = 720000) fun rustDiagnosticsAndCompletion() = exercise(Fixture(
        LspServer.Rust, "src/main.rs",
        "fn main() {\n let mut value = String::new();\n let count: i32 = \"wrong\";\n value.pu\n}\n",
        "fn main() {\n let mut value = String::new();\n value.push('x');\n println!(\"{}\", value);\n}\n", 3, 9, "push",
        mapOf("Cargo.toml" to "[package]\nname = \"lspfixture\"\nversion = \"0.1.0\"\nedition = \"2021\"\n"),
    ))

    @Test(timeout = 720000) fun dartDiagnosticsAndCompletion() = exercise(Fixture(
        LspServer.Dart, "bin/main.dart",
        "void main() {\n int count = 'wrong';\n 'fixture'.sub\n}\n",
        "void main() {\n print('fixture'.substring(1));\n}\n", 2, 14, "substring",
        mapOf("pubspec.yaml" to "name: lspfixture\nenvironment:\n  sdk: '>=3.0.0 <4.0.0'\n"),
    ))
}
