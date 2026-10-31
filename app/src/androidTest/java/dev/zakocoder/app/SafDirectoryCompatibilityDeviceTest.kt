package dev.zakocoder.app

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.runtime.PrivateFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Uses the real system tree picker and persisted URI grants, not a guessed filesystem path. */
class SafDirectoryCompatibilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun await(label: String, condition: () -> Boolean) {
        val ready = withTimeoutOrNull(25_000) {
            while (!condition()) delay(75)
            true
        }
        if (ready != true) throw AssertionError("SAF stage timed out: $label")
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::walk)
    }
    private suspend fun click(label: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        await(label) { find(predicate) != null }
        var node = requireNotNull(find(predicate))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("SAF click failed: $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private suspend fun recordFailure(run: String, authority: String, phase: String, failure: Throwable) {
        val directory = File(context.cacheDir, "compatibility/saf/api-${Build.VERSION.SDK_INT}/$run")
        val lines = mutableListOf("phase=$phase", "failure=${failure.javaClass.name}: ${failure.message}")
        suspend fun saveLines() = withContext(Dispatchers.IO) {
            directory.mkdirs()
            File(directory, "failure.txt").writeText(lines.joinToString("\n"))
        }
        fun diagnosticFailure(part: String, error: Exception) {
            lines += "diagnosticFailure[$part]=${error.javaClass.name}: ${error.message}"
            android.util.Log.e("CompatibilitySaf", "SAF diagnostics failed: $part phase=$phase", error)
            println("SAF_DIAGNOSTIC_ERROR part=$part phase=$phase error=$error")
            failure.addSuppressed(error)
        }
        // Save the original phase before querying any UI, provider or screenshot APIs.
        saveLines()
        android.util.Log.e("CompatibilitySaf", "SAF failed phase=$phase artifacts=${directory.absolutePath}", failure)
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (lines.size >= 250) return
            if (node.isVisibleToUser) {
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                lines += "${" ".repeat(depth.coerceAtMost(12))}package=${node.packageName} id=${node.viewIdResourceName} text=${node.text} desc=${node.contentDescription} clickable=${node.isClickable} enabled=${node.isEnabled} bounds=$bounds"
            }
            repeat(node.childCount) { node.getChild(it)?.let { child -> walk(child, depth + 1) } }
        }
        try { instrumentation.uiAutomation.windows.forEach { window -> window.root?.let { walk(it, 0) } } }
        catch (error: Exception) { diagnosticFailure("visible nodes", error) }
        saveLines()
        val roots = JSONObject().put("authority", authority)
        try {
            val provider = context.packageManager.resolveContentProvider(authority, 0)
            roots.put("provider", provider?.name).put("readPermission", provider?.readPermission)
                .put("writePermission", provider?.writePermission).put("grantUriPermissions", provider?.grantUriPermissions)
                .put("providerEnabled", provider?.enabled).put("providerExported", provider?.exported).put("providerUid", provider?.applicationInfo?.uid)
                .put("providerApplicationFlags", provider?.applicationInfo?.flags)
                .put("targetPackage", context.packageName).put("testPackage", instrumentation.context.packageName)
                .put("processUid", android.os.Process.myUid())
                .put("uidPackages", JSONArray(context.packageManager.getPackagesForUid(android.os.Process.myUid()).orEmpty().toList()))
            val candidates = JSONArray()
            context.packageManager.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0).forEach {
                candidates.put(JSONObject().put("package", it.providerInfo.packageName).put("authority", it.providerInfo.authority)
                    .put("enabled", it.providerInfo.enabled).put("exported", it.providerInfo.exported))
            }
            roots.put("visibleDocumentsProviders", candidates)
                .put("targetRecognizesRootUri", DocumentsContract.isRootUri(context,
                    DocumentsContract.buildRootUri(authority, "compatibility-root")))
            // Diagnose only our test provider. Drop the temporary shell identity before normal access checks.
            try {
                instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
                context.contentResolver.query(DocumentsContract.buildRootsUri(authority), null, null, null, null)?.use { cursor ->
                    val rows = JSONArray()
                    while (cursor.moveToNext() && rows.length() < 10) {
                        rows.put(JSONObject().apply { cursor.columnNames.forEachIndexed { index, name -> put(name, cursor.getString(index)) } })
                    }
                    roots.put("roots", rows)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                roots.put("queryRootsError", "${error.javaClass.simpleName}: ${error.message}")
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { diagnosticFailure("provider metadata", error) }
        withContext(Dispatchers.IO) { File(directory, "provider.json").writeText(roots.toString(2)) }
        saveLines()
        val screenshot = try { instrumentation.uiAutomation.takeScreenshot() }
        catch (error: Exception) { diagnosticFailure("screenshot capture", error); null }
        try {
            withContext(Dispatchers.IO) {
                File(directory, "failure.txt").writeText(lines.joinToString("\n"))
                File(directory, "provider.json").writeText(roots.toString(2))
                screenshot?.let { bitmap -> File(directory, "failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                println("SAF_DIAGNOSTICS ${directory.absolutePath}")
            }
        } finally { screenshot?.recycle() }
        lines.forEach { println("SAF_DIAGNOSTIC $it") }
        println("SAF_PROVIDER_DIAGNOSTIC $roots")
    }

    @Test(timeout = 120_000) fun systemPickerGrantsTreeImportCopiesAndRevocationDeniesSource(): Unit = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val run = UUID.randomUUID().toString()
        val owned = File(context.cacheDir, "compatibility-saf-$run")
        val authority = "${instrumentation.context.packageName}.compatibility-documents"
        val document = DocumentsContract.buildDocumentUri(authority, "compatibility-root")
        val fixtureSource = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(authority, "compatibility-root"), "hello.py")
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(owned, "files").apply { mkdirs() }
            override fun getCacheDir() = File(owned, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("compatibility-saf-$run-$name", mode)
        }
        val resolver = context.contentResolver
        var granted: Uri? = null
        var launcher: ActivityResultLauncher<Intent>? = null
        var phase = "verify fixture inaccessible before grant"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                phase = "activate owned fixture provider and clear only its stale grants"
                withContext(Dispatchers.IO) {
                    val baseline = resolver.call("${instrumentation.context.packageName}.import-fixture", "reset-compatibility-saf-fixture", "compatibility-root", null)
                    assertTrue("Fixture provider must acknowledge its isolated revocation baseline", baseline?.getBoolean("revoked") == true)
                }
                phase = "verify fixture inaccessible before grant"
                // Only remove stale grants to this test APK's immutable fixture, never user providers.
                resolver.persistedUriPermissions.filter { it.uri.authority == authority }.forEach {
                    resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.IO) {
                    assertThrows(SecurityException::class.java) { resolver.query(document, null, null, null, null)?.close() }
                    assertThrows(SecurityException::class.java) { resolver.openInputStream(fixtureSource)?.close() }
                }
                val result = AtomicReference<ActivityResult>()
                phase = "launch system tree picker"
                scenario.onActivity { activity ->
                    launcher = activity.activityResultRegistry.register("compatibility-saf-$run", ActivityResultContracts.StartActivityForResult()) {
                        result.set(it)
                    }
                    launcher!!.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        // A plain document URI requires findDocumentPath; this fixture exposes a root directly.
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildRootUri(authority, "compatibility-root"))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    })
                }
                phase = "load Compatibility SAF Fixture system root"
                val fixtureShown = withTimeoutOrNull(2_000) {
                    while (find {
                        it.packageName?.toString()?.endsWith(".documentsui") == true &&
                            it.text?.toString() == "Compatibility SAF Fixture"
                    } == null) delay(75)
                    true
                }
                if (fixtureShown != true) {
                    phase = "open DocumentsUI root drawer after initial root fallback"
                    click(phase) {
                        it.packageName?.toString()?.endsWith(".documentsui") == true &&
                            it.contentDescription?.toString() in setOf("Show roots", "显示根目录", "显示根")
                    }
                    phase = "select only Compatibility SAF Fixture root in system drawer"
                    click(phase) {
                        it.packageName?.toString()?.endsWith(".documentsui") == true && it.isEnabled &&
                            it.text?.toString() == "Compatibility SAF Fixture"
                    }
                }
                await(phase) { find {
                    it.packageName?.toString()?.endsWith(".documentsui") == true &&
                        it.text?.toString() == "Compatibility SAF Fixture"
                } != null }
                phase = "select fixture folder in DocumentsUI"
                val selection: (AccessibilityNodeInfo) -> Boolean = {
                    it.packageName?.toString()?.endsWith(".documentsui") == true && it.isEnabled &&
                        (it.viewIdResourceName == "android:id/button1" || it.viewIdResourceName?.endsWith(":id/action_menu_select") == true)
                }
                await(phase) { find(selection) != null }
                val selectionText = find(selection)?.text?.toString()
                click(phase, selection)
                phase = "confirm system directory grant"
                click(phase) {
                    it.packageName?.toString()?.endsWith(".documentsui") == true && it.isEnabled &&
                        it.viewIdResourceName == "android:id/button1" && it.text?.toString() != selectionText
                }
                phase = "receive tree picker result"
                await(phase) { result.get() != null }
                assertEquals(Activity.RESULT_OK, result.get().resultCode)
                val data = requireNotNull(result.get().data)
                val tree = requireNotNull(data.data)
                assertEquals(authority, tree.authority)
                assertEquals("compatibility-root", DocumentsContract.getTreeDocumentId(tree))
                granted = tree
                phase = "persist tree URI, import copy, verify source, revoke access"
                val imported = withContext(Dispatchers.IO) {
                    phase = "persist granted tree read permission"
                    resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    assertTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
                    phase = "import granted fixture tree using ProjectRepository"
                    val repo = ProjectRepository(isolated, File(owned, "workspaces"))
                    val project = repo.import(tree) {}
                    phase = "verify imported bytes and independent source document"
                    val copied = File(repo.directory(project.id), "hello.py")
                    assertEquals("print('SAF compatibility fixture')\n", copied.readText())
                    copied.writeText("print('edited imported copy')\n")
                    val source = DocumentsContract.buildDocumentUriUsingTree(tree, "hello.py")
                    assertEquals("print('SAF compatibility fixture')\n", resolver.openInputStream(source)!!.bufferedReader().use { it.readText() })
                    phase = "revoke fixture read permissions"
                    resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    assertFalse(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
                    source to copied
                }
                phase = "finish receiving Activity before provider revocation"
                scenario.onActivity { launcher?.unregister(); launcher = null }
                scenario.moveToState(Lifecycle.State.DESTROYED)
                phase = "provider revokes every owned tree and document grant"
                withContext(Dispatchers.IO) {
                    // releasePersistable removes only the persisted mode. A receiving Activity's
                    // transient grant can outlive its callback/lifecycle, so test actual provider revocation.
                    val revoked = resolver.call("${instrumentation.context.packageName}.import-fixture", "reset-compatibility-saf-fixture", "compatibility-root", null)
                    assertTrue("The real document provider must acknowledge revocation", revoked?.getBoolean("revoked") == true)
                }
                phase = "verify source read denied after provider revocation"
                withContext(Dispatchers.IO) {
                    assertFalse(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
                    assertThrows(SecurityException::class.java) { resolver.openInputStream(imported.first)?.close() }
                    assertEquals("print('edited imported copy')\n", imported.second.readText())
                }
                println("SAF_COMPATIBILITY_OK system_picker=true persisted_read_released=true provider_revoked_all_grants=true source_denied=true imported_copy_preserved=true")
                granted = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                try { recordFailure(run, authority, phase, failure) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (diagnosticFailure: Exception) {
                    android.util.Log.e("CompatibilitySaf", "Could not finish SAF diagnostics for phase=$phase", diagnosticFailure)
                    println("SAF_DIAGNOSTIC_ERROR phase=$phase error=$diagnosticFailure")
                    failure.addSuppressed(diagnosticFailure)
                }
                throw failure
            } finally {
                withContext(NonCancellable) {
                    instrumentation.runOnMainSync { launcher?.unregister(); launcher = null }
                    withContext(Dispatchers.IO) {
                        granted?.let { tree ->
                            if (resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) {
                                resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            resolver.call("${instrumentation.context.packageName}.import-fixture", "reset-compatibility-saf-fixture", "compatibility-root", null)
                        }
                        PrivateFiles.deleteTree(owned)
                        context.deleteSharedPreferences("compatibility-saf-$run-projects")
                    }
                }
            }
        }
    }
}
