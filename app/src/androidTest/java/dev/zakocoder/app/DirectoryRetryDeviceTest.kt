package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.editor.EditorController
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Real directory failures and real Compose entry points; only UUID-owned storage is touched. */
class DirectoryRetryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private suspend fun click(label: String) {
        await("visible $label") { find(label) != null }
        var node = find(label)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("click $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private suspend fun screenshot(aiPicker: Boolean, state: String) {
        delay(300) // Wait for sheet/layout animations before capturing the actual composed state.
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            withContext(Dispatchers.IO) {
                val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
                val theme = if (dark) "dark" else "light"
                val entry = if (aiPicker) "ai" else "editor"
                val destination = File(context.cacheDir, "ui-review/mobile-directory-$theme/$entry-$state.png")
                destination.parentFile!!.mkdirs()
                destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }

    @Test(timeout = 120000) fun editorPickerRetriesTheSameDirectoryWithoutClosing() = runBlocking { exercise(false) }
    @Test(timeout = 120000) fun aiReferencePickerRetriesTheSameDirectoryWithoutClosing() = runBlocking { exercise(true) }

    private suspend fun exercise(aiPicker: Boolean) {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val own = withContext(Dispatchers.IO) { File(context.cacheDir, "directory-retry-${UUID.randomUUID()}").apply { check(mkdirs()) } }
        val preferenceNames = java.util.concurrent.CopyOnWriteArraySet<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(own, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(own, "no-backup").apply { mkdirs() }
            override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val ownedName = "${own.name}-$name"
                preferenceNames += ownedName
                return context.getSharedPreferences(ownedName, mode)
            }
            override fun startService(service: Intent): android.content.ComponentName? = error("No service in directory fixture")
            override fun startForegroundService(service: Intent): android.content.ComponentName? = error("No service in directory fixture")
            override fun stopService(service: Intent) = false
        }
        val id = UUID.randomUUID().toString()
        val project = File(own, "projects/$id")
        val child = File(project, "retry-dir")
        val parked = File(own, "parked")
        val sheetOpen = mutableStateOf(true)
        var editor: EditorController? = null
        var ai: AiController? = null
        try {
            withContext(Dispatchers.IO) {
                check(child.mkdirs())
                check(File(project, "empty-dir").mkdir())
                File(project, "open.py").writeText("print('fixture')\n")
                File(child, "result.py").writeText("RESULT_FIXTURE\n")
            }
            if (aiPicker) {
                main {
                    ai = AiController(isolated, { check(it == id); project }, { _, _ -> false },
                        { _, _ -> error("No commands in directory fixture") }, {}, {},
                        config = AiConfigStore(isolated, "empty-models", "unused-${UUID.randomUUID()}"))
                    ai!!.select(id)
                }
                await("AI ready") { ai!!.state.value.projectId == id && !ai!!.state.value.permissionsLoading && !ai!!.state.value.skillsLoading }
            } else {
                main { editor = EditorController(isolated, ProjectRepository(isolated, File(own, "projects")), {}) }
                await("editor initialized") { !editor!!.state.value.loading }
                main { editor!!.open(id, "open.py") }
                await("editor opened") { !editor!!.state.value.loading && editor!!.state.value.document != null }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalWorkbenchDark provides dark,
                        LocalDensity provides Density(density.density, 1.3f)) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface {
                                if (aiPicker) {
                                    val state by ai!!.state.collectAsState()
                                    if (sheetOpen.value) AiContextSheet(ai!!, state) { sheetOpen.value = false }
                                } else CodeEditorPage(editor!!)
                            }
                        }
                    }
                } }
                if (!aiPicker) click(context.getString(R.string.editor_open_file))
                await("initial child directory listed") { find("retry-dir") != null }
                // Replace a listed directory with an out-of-project link. Both existing path
                // protections reject it deterministically, without chmod or production hooks.
                withContext(Dispatchers.IO) {
                    check(child.renameTo(parked))
                    Files.createSymbolicLink(child.toPath(), parked.toPath())
                }
                click("retry-dir")
                val failure = context.getString(R.string.directory_read_error)
                val retry = context.getString(R.string.directory_retry)
                val empty = context.getString(R.string.directory_empty)
                await("directory read failed") { find(failure) != null && find(retry) != null }
                assertNull(find(empty))
                assertNull(find("result.py"))
                assertNotNull(find("retry-dir")) // current path remains visible inside the sheet
                screenshot(aiPicker, "failed")
                withContext(Dispatchers.IO) {
                    Files.delete(child.toPath())
                    check(parked.renameTo(child))
                }
                click(retry)
                await("same directory recovered") { find("result.py") != null && find(retry) == null && find(failure) == null }
                assertNull(find(empty))
                assertNotNull(find("retry-dir"))
                screenshot(aiPicker, "recovered")
                // Empty is a successful read, distinct from the preceding failure.
                val parent = context.getString(if (aiPicker) R.string.editor_back else R.string.parent_directory)
                click(parent)
                click("empty-dir")
                await("successful empty directory") { find(empty) != null }
                assertNull(find(retry))
                assertNull(find(failure))
                assertNull(find("result.py"))
                screenshot(aiPicker, "empty")
                click(parent)
                click("retry-dir")
                click("result.py")
                if (aiPicker) await("actual file attached") { ai!!.state.value.references.any { it.path == "retry-dir/result.py" && it.text == "RESULT_FIXTURE\n" } }
                else await("actual file opened") { editor!!.state.value.path == "retry-dir/result.py" && !editor!!.state.value.loading }
            }
        } finally {
            val hadDocument = editor?.state?.value?.document != null
            try {
                main { ai?.stop(); editor?.close() }
                // close() queues an immediate private draft flush; observe its atomic completion.
                if (hadDocument) withContext(Dispatchers.IO) {
                    withTimeout(10000) {
                        while (!File(own, "no-backup/editor-drafts/drafts-v1.json").isFile) delay(25)
                    }
                }
            } finally {
                withContext(Dispatchers.IO) {
                    if (Files.isSymbolicLink(child.toPath())) Files.delete(child.toPath())
                    preferenceNames.forEach { context.deleteSharedPreferences(it) }
                    own.deleteRecursively()
                }
            }
        }
    }
}
