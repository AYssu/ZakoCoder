package dev.zakocoder.app.ui

import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ZakoApplication
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real about content and clipboard callback; never opens QQ, joins a group or sends messages. */
@SdkSuppress(minSdkVersion = 30)
class CommunityAboutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 90000) fun dayAboutShowsOfficialCommunityAndCopiesNumber() = render(false)
    @Test(timeout = 90000) fun nightAboutShowsOfficialCommunityAndCopiesNumber() = render(true)

    private fun render(dark: Boolean) {
        warmDeviceActivity()
        org.junit.Assume.assumeTrue("Isolated test package only", context.packageName.endsWith(".runtimecheck"))
        val workbench = (context.applicationContext as ZakoApplication).workbench
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                activity.setContent {
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark) {
                        WorkbenchMiuixTheme(dark) {
                            Surface(Modifier.fillMaxSize()) {
                                SettingsPage(workbench, workbench.state.value, onModels = {})
                            }
                        }
                    }
                }
            }
            val entry = context.getString(R.string.settings_about_entry)
            await("About is a visible settings row") {
                if (!shown(entry)) nodes { all ->
                    all.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                        ?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 1600f)
                }
                shown(entry)
            }
            assertTrue("About row has an explicit action", hasClickAction(entry))
            settleAndCapture(dark, "settings")
            click(entry)
            val groupName = context.getString(R.string.community_group_name)
            val groupNumberLabel = context.getString(R.string.community_group_number_label, "1125542242")
            await("Official community name and group number are visible") {
                shown(groupName) && shown(groupNumberLabel)
            }
            val join = context.getString(R.string.community_join)
            await("Join invitation action is available") { shown(join) && hasClickAction(join) }
            settleAndCapture(dark)
            val copy = context.getString(R.string.community_copy_number)
            click(copy)
            await("Copy acknowledgement is visible") { shown(context.getString(R.string.community_copied)) }
            instrumentation.runOnMainSync {
                assertEquals("1125542242", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                clipboard.clearPrimaryClip()
            }
            click(context.getString(R.string.close))
            await("Bottom close returns to settings after the sheet exits") {
                !shown(context.getString(R.string.about_title)) && shown(entry)
            }
        }
    }

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(all))
        }
        return result.get()
    }

    private fun label(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()

    private fun visible(node: SemanticsNode): Boolean {
        val bounds = node.boundsInWindow
        val display = context.resources.displayMetrics
        return bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < display.widthPixels && bounds.top < display.heightPixels
    }

    private fun shown(text: String) = nodes { all -> all.any { label(it) == text && visible(it) } }

    private fun clickableNode(all: List<SemanticsNode>, text: String): SemanticsNode? {
        var node = all.lastOrNull { label(it) == text && visible(it) }
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        return node
    }

    private fun hasClickAction(text: String) = nodes { clickableNode(it, text) != null }

    private fun click(text: String) {
        await("Visible action: $text") { shown(text) }
        nodes { all ->
            val action = requireNotNull(clickableNode(all, text)).config[SemanticsActions.OnClick].action
            assertTrue(action?.invoke() == true)
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { description }
            Thread.sleep(50)
        }
    }

    private fun settleAndCapture(dark: Boolean, prefix: String = "about") {
        var previous = emptyList<Pair<String, Int>>()
        var stable = 0
        await("About sheet has settled for a screenshot") {
            val positions = nodes { all -> all.filter { label(it).isNotBlank() && visible(it) }
                .map { label(it) to it.boundsInWindow.top.toInt() } }
            stable = if (positions == previous) stable + 1 else 0
            previous = positions
            stable >= 5
        }
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/community/$prefix-${if (dark) "night" else "day"}.png")
                .apply { parentFile!!.mkdirs() }.outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
        } finally { bitmap.recycle() }
    }
}
