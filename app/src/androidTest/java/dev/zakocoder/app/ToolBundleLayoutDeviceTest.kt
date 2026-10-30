package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiPermissionStore
import dev.zakocoder.app.runtime.ToolCheck
import dev.zakocoder.app.runtime.ToolStatus
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Isolated native component: 360dp / 1.3 font scale, both themes, no global display changes. */
class ToolBundleLayoutDeviceTest {
    @org.junit.Before fun foreground() = warmDeviceActivity()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(60) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(label) == true || node.contentDescription?.toString()?.lines()?.contains(label) == true)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private fun control(label: String): AccessibilityNodeInfo {
        var node = requireNotNull(find(label)) { "Missing visible control: $label" }
        // Compose exposes an already-selected Tab as selected, without an ACTION_CLICK.
        // Keep that node for its actual target bounds and selected-state assertions.
        while (!node.isClickable && !node.isSelected && node.parent != null) node = node.parent
        assertTrue("Clickable or selected control $label", node.isClickable || node.isSelected)
        return node
    }
    private fun visibleTextInRow(row: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val owner = bounds(row)
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            // Compose may join merged text with commas rather than newlines. A non-clickable
            // Surface can also expose its text separately from the row's description node.
            val rectangle = bounds(node)
            if (node.isVisibleToUser && !rectangle.isEmpty && owner.contains(rectangle) &&
                node.text?.toString()?.contains(label) == true) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private fun assertRecommendation(title: Int) {
        val label = context.getString(R.string.bundle_details, context.getString(title))
        val row = requireNotNull(find(label)) { "Missing tool row $label" }
        // Bind to the owning row's bounds: a different tool's badge cannot satisfy this check.
        val recommendation = requireNotNull(visibleTextInRow(row, context.getString(R.string.environment_bundle_recommended))) {
            "Missing visible recommendation in $label (row text: ${row.text})"
        }
        assertTrue("Recommendation belongs to its visible tool row", bounds(row).contains(bounds(recommendation)))
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private suspend fun click(label: String) {
        await("visible $label") { find(label) != null }
        val target = control(label)
        assertTrue("Switch target must expose a real click: $label", target.isClickable)
        assertTrue(label, target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private suspend fun screenshot(name: String) {
        delay(200)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "ui-review/mobile-0044/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        } finally { bitmap.recycle() }
    }

    @Test(timeout = 120000) fun splitHeaderFiltersAndManagementFitNarrowLargeTextWithoutInstalling() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context) }
        var ownedWorkbench: Workbench? = null
        try {
            val privateContext = object : ContextWrapper(context) {
                override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
                override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${fixture.token}-$name", mode)
                override fun stopService(service: android.content.Intent) = false
            }
            lateinit var workbench: Workbench
            withContext(Dispatchers.Main) {
                workbench = Workbench(privateContext, fixture.transport, fixture.config, AiPermissionStore(privateContext), fixture.history)
                ownedWorkbench = workbench
            }
            await("isolated workbench ready") { !workbench.state.value.loading }
            val uiState = WorkbenchState(loading = false, installed = true, bundleTools = listOf(
                ToolCheck("zsh", ToolStatus.Ready, "zsh fixture"), ToolCheck("git", ToolStatus.Missing, ""),
                ToolCheck("node", ToolStatus.Broken, "broken fixture"), ToolCheck("npm", ToolStatus.Missing, ""),
            ))
            val selected = mutableStateOf(emptyList<String>())
            val selections = AtomicInteger()
            val terminalOpens = AtomicInteger()
            val width = AtomicInteger()
            val density = context.resources.displayMetrics.density
            val filters = listOf(context.getString(R.string.filter_all), context.getString(R.string.filter_ready, 1), context.getString(R.string.filter_pending, 2))
            fun details(title: Int) = context.getString(R.string.bundle_details, context.getString(title))
            fun assertSelected(index: Int) {
                filters.forEachIndexed { position, title -> assertEquals("selected $title", position == index, control(title).isSelected) }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (dark in listOf(false, true)) {
                    withContext(Dispatchers.Main) { selected.value = emptyList() }
                    scenario.onActivity { activity -> activity.setContent {
                        val deviceDensity = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(deviceDensity, 1.3f), LocalWorkbenchDark provides dark,
                            LocalMinimumInteractiveComponentSize provides 48.dp) {
                            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                Surface(Modifier.fillMaxSize()) {
                                    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                                        key(dark) {
                                            Column(Modifier.width(360.dp).onGloballyPositioned { width.set(it.size.width) }
                                                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                                                ToolBundleList(workbench, uiState, selected.value, {
                                                    selected.value = it; selections.incrementAndGet()
                                                }, { terminalOpens.incrementAndGet() })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } }
                    await("header and filters") { find(context.getString(R.string.manage_tools)) != null && find(filters[2]) != null }
                    assertEquals("360dp fixture width", (360 * density).toDouble(), width.get().toDouble(), 1.0)
                    val heading = bounds(requireNotNull(find(context.getString(R.string.tool_bundles))))
                    val refresh = bounds(control(context.getString(R.string.refresh)))
                    val manage = bounds(control(context.getString(R.string.manage_tools)))
                    val headingLineHeight = WorkbenchTypography.titleSmall.lineHeight.value * 1.3f * density
                    assertTrue("Title remains a single text line", heading.height() <= headingLineHeight + 2)
                    assertFalse("Title and refresh do not overlap", Rect.intersects(heading, refresh))
                    assertFalse("Title and manage do not overlap", Rect.intersects(heading, manage))
                    assertFalse("Refresh and manage do not overlap", Rect.intersects(refresh, manage))
                    assertTrue("Title stays on refresh row", heading.centerY() in refresh.top..refresh.bottom)
                    assertTrue("Title stays on manage row", heading.centerY() in manage.top..manage.bottom)
                    assertTrue("Refresh and manage share header row", refresh.centerY() in manage.top..manage.bottom)
                    listOf("Refresh" to refresh, "Manage" to manage).forEach { (label, rect) ->
                        assertTrue("$label touch height", rect.height() + 1 >= 48 * density)
                        assertTrue("$label touch width", rect.width() + 1 >= 48 * density)
                    }
                    assertRecommendation(R.string.bundle_zsh)
                    assertRecommendation(R.string.bundle_git)
                    val tabBounds = filters.map { bounds(control(it)) }
                    tabBounds.forEach { rect ->
                        assertTrue("Filter row follows header", rect.top >= manage.bottom)
                        assertTrue("48dp filter touch height", rect.height() + 1 >= 48 * density)
                        assertTrue("48dp filter touch width", rect.width() + 1 >= 48 * density)
                    }
                    assertTrue("Equal filter widths", tabBounds.maxOf { it.width() } - tabBounds.minOf { it.width() } <= 1)
                    assertTrue("Distinct filter targets", tabBounds.zipWithNext().all { (a, b) -> a.right < b.left })
                    assertSelected(0)
                    val changesBefore = selections.get()
                    click(filters[1]); await("ready filter") { find(details(R.string.bundle_zsh)) != null && find(details(R.string.bundle_git)) == null }
                    assertSelected(1); assertNull(find(details(R.string.bundle_node)))
                    click(filters[2]); await("pending filter") { find(details(R.string.bundle_git)) != null && find(details(R.string.bundle_node)) != null }
                    assertSelected(2); assertNull(find(details(R.string.bundle_zsh)))
                    click(filters[0]); await("all filter") { find(details(R.string.bundle_zsh)) != null && find(details(R.string.bundle_git)) != null }
                    assertSelected(0)
                    assertEquals("Filters never select packages", changesBefore, selections.get())
                    assertTrue(selected.value.isEmpty())
                    screenshot("tool-bundles-${if (dark) "dark" else "light"}-360-font130")
                    // Management selects packages only; installation still requires its separate confirmation.
                    click(filters[2]); click(context.getString(R.string.manage_tools))
                    val gitSelection = context.getString(R.string.select_bundle, context.getString(R.string.bundle_git))
                    click(gitSelection)
                    await("Git checkbox selected") { selected.value == listOf("git") && control(gitSelection).isChecked }
                    assertEquals(changesBefore + 1, selections.get())
                    assertFalse(workbench.state.value.busy); assertFalse(workbench.state.value.installed)
                    assertEquals("", workbench.state.value.log); assertEquals("", workbench.state.value.message)
                    assertEquals(0, fixture.server.requestCount); assertEquals(0, terminalOpens.get())
                    screenshot("tool-bundles-${if (dark) "dark" else "light"}-managed-360-font130")
                    click(context.getString(R.string.done)); await("management clears selection") { selected.value.isEmpty() }
                    click(filters[0])
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { ownedWorkbench?.stopAll() }
            withContext(NonCancellable + Dispatchers.IO) {
                listOf("projects", "development-settings").forEach { context.deleteSharedPreferences("${fixture.token}-$it") }
                fixture.close()
            }
        }
    }
}
