package dev.zakocoder.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.permissions.AppPermission
import dev.zakocoder.app.permissions.AppPermissionStatus
import dev.zakocoder.app.settings.RunningNotificationStatus
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Isolated presentation only: no actual permission request or settings navigation. */
@SdkSuppress(minSdkVersion = 30)
class AppPermissionsUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 90000) fun day360Font130() = render(false)
    @Test(timeout = 90000) fun night360Font130() = render(true)
    private fun nodes(block: (List<SemanticsNode>) -> Unit) {
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            block(all)
        }
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun visible(label: Int): Boolean {
        val result = AtomicReference(false)
        nodes { all -> result.set(all.any { text(it) == context.getString(label) && it.boundsInWindow.height > 0 }) }
        return result.get()
    }
    private fun show(label: Int) {
        repeat(30) {
            if (visible(label)) return
            nodes { all ->
                val list = all.last { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                assertTrue(list.config[SemanticsActions.ScrollBy].action?.invoke(0f, 400f) == true)
            }
            Thread.sleep(100)
        }
        fail("Permission section not visible: ${context.getString(label)}")
    }
    private fun render(dark: Boolean) {
        var operations = 0
        val states = AppPermission.entries.map { permission ->
            AppPermissionStatus(permission, if (permission == AppPermission.Overlay) null else permission in setOf(AppPermission.Files, AppPermission.InstallPackages),
                if (permission == AppPermission.Notifications) RunningNotificationStatus.PermissionRequired else null)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = context.resources.displayMetrics.widthPixels / 360f
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density, 1.3f),
                    LocalWorkbenchDark provides dark, LocalMinimumInteractiveComponentSize provides 48.dp) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                            Column {
                                Text(context.getString(R.string.app_permissions_title), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                                AppPermissionsPanel(states, rootGranted = false, rootBusy = false, rootError = "",
                                    onManage = { operations++ }, onRequest = { operations++ }, onRoot = { operations++ }, onTools = { operations++ },
                                    backgroundServiceRunning = true)
                            }
                        }
                    }
                }
            } }
            val until = SystemClock.uptimeMillis() + 15000
            while (!visible(R.string.app_permissions_notifications)) { check(SystemClock.uptimeMillis() < until); Thread.sleep(50) }
            capture(dark, "system")
            show(R.string.app_permissions_root)
            capture(dark, "special")
            show(R.string.app_permissions_tools_hint)
            capture(dark, "background")
            assertEquals("Rendering never requests or changes authorization", 0, operations)
        }
    }
    private fun capture(dark: Boolean, section: String) {
        instrumentation.waitForIdleSync()
        val frames = CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        assertTrue(frames.await(3, TimeUnit.SECONDS)); Thread.sleep(250)
        val directory = File(context.cacheDir, "ui-review/app-permissions-after").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "${if (dark) "night" else "day"}-$section.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
