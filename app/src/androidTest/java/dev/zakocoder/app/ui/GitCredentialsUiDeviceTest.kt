package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.git.GitCredentialStore
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real production clone/authentication sheets; isolated fixture store; no network or user credentials. */
@SdkSuppress(minSdkVersion = 30)
class GitCredentialsUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test fun day360Font130WithActualKeyboard() = render(false)
    @Test fun night360Font130WithActualKeyboard() = render(true)

    private fun render(dark: Boolean) {
        val directory = File(context.cacheDir, "git-credentials-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val wrapper = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val store = GitCredentialStore(wrapper)
        val project = UUID.randomUUID().toString()
        val remote = "https://example.invalid/team/mobile-game.git"
        val cloned = AtomicReference<List<String>>()
        val showing = mutableStateOf("clone")
        // Only this generated store is accessed. Never inspect the application's existing catalog.
        store.save(project, remote, "fixture-user", "fixture-old-token-placeholder")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { Theme(dark) {
                    when (showing.value) {
                        "clone" -> GitCloneSheet(true, { showing.value = "" }) { name, url, username, token ->
                            cloned.set(listOf(name, url, username, token)); showing.value = "credentials"
                        }
                        "credentials" -> GitCredentialsSheet(project, remote, store, { showing.value = "" })
                    }
                } } }
                awaitVisible(R.string.git_clone_private)
                setField(R.string.project_name, "Mobile 2048")
                setField(R.string.git_url, remote)
                hideKeyboard(scenario)
                capture(dark, "clone-public")
                nodes { all ->
                    val switch = all.single { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Switch }
                    assertTrue(switch.config[SemanticsActions.OnClick].action?.invoke() == true)
                }
                settle()
                scrollTo(R.string.git_credentials_token)
                setField(R.string.git_credentials_username, "fixture-user")
                setField(R.string.git_credentials_token, "fixture-clone-token-placeholder")
                hideKeyboard(scenario)
                assertPasswordField(R.string.git_credentials_token)
                capture(dark, "clone-private")
                focusField(R.string.git_credentials_token)
                awaitKeyboard(scenario)
                scrollButtonAboveKeyboard(scenario, R.string.git_clone)
                capture(dark, "clone-private-keyboard")
                hideKeyboard(scenario)
                scrollTo(R.string.git_clone)
                click(R.string.git_clone, requireButton = true)
                assertEquals(listOf("Mobile 2048", remote, "fixture-user", "fixture-clone-token-placeholder"), cloned.get())

                awaitVisible(R.string.git_credentials_saved)
                nodes { all -> assertFalse(all.any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "fixture-old-token-placeholder" }) }
                capture(dark, "credential-saved")
                scrollTo(R.string.git_credentials_replace_token)
                setField(R.string.git_credentials_replace_token, "fixture-replacement-token-placeholder")
                assertPasswordField(R.string.git_credentials_replace_token)
                focusField(R.string.git_credentials_replace_token)
                awaitKeyboard(scenario)
                scrollButtonAboveKeyboard(scenario, R.string.git_credentials_save)
                capture(dark, "credential-keyboard")
                hideKeyboard(scenario)
                scrollTo(R.string.git_credentials_remove)
                click(R.string.git_credentials_remove)
                awaitVisible(R.string.git_credentials_remove_help)
                capture(dark, "credential-remove-confirm")
                click(R.string.cancel)
                assertEquals("fixture-user", store.summary(project, remote)?.username)
            }
        } finally { directory.deleteRecursively() }
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
    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun visible(resource: Int) = nodes { all ->
        val text = context.getString(resource)
        all.any { candidate ->
            var actionable: SemanticsNode? = candidate
            if (resource == R.string.git_clone) while (actionable != null && actionable.config.getOrNull(SemanticsActions.OnClick) == null) actionable = actionable.parent
            label(candidate) == text && candidate.boundsInWindow.width > 0 && candidate.boundsInWindow.height >= candidate.size.height * .9f &&
                (resource != R.string.git_clone || actionable?.config?.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button)
        }
    }
    private fun awaitVisible(resource: Int) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!visible(resource)) { check(SystemClock.uptimeMillis() < deadline) { "Expected visible resource $resource" }; Thread.sleep(50) }
    }
    private fun field(all: List<SemanticsNode>, resource: Int): SemanticsNode {
        val text = context.getString(resource)
        var node = all.last { label(it) == text } as SemanticsNode?
        while (node != null && node.config.getOrNull(SemanticsActions.SetText) == null) node = node.parent
        return requireNotNull(node) { "Editable field not found: $resource" }
    }
    private fun setField(resource: Int, value: String) {
        nodes { all -> assertTrue(field(all, resource).config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true) }
        settle()
    }
    private fun focusField(resource: Int) {
        nodes { all -> assertTrue(field(all, resource).config[SemanticsActions.OnClick].action?.invoke() == true) }
        settle()
    }
    private fun assertPasswordField(resource: Int) {
        nodes { all -> assertTrue(field(all, resource).config.contains(SemanticsProperties.Password)) }
    }
    private fun click(resource: Int, requireButton: Boolean = false) {
        nodes { all ->
            val text = context.getString(resource)
            val candidates = all.filter { label(it) == text || text in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            val clickable = candidates.mapNotNull { original ->
                var node: SemanticsNode? = original
                while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
                node
            }.last { !requireButton || it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button }
            assertTrue(clickable.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        settle()
    }
    private fun scrollTo(resource: Int) {
        if (visible(resource)) return
        repeat(10) {
            nodes { all -> all.lastOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 350f) }
            settle()
            if (visible(resource)) return
        }
        assertTrue("Expected visible resource $resource after scroll", visible(resource))
    }
    /** Compare the whole clickable target with the actual IME, not just its text semantics. */
    private fun scrollButtonAboveKeyboard(scenario: ActivityScenario<MainActivity>, resource: Int) {
        var geometry = "not measured"
        fun aboveIme(): Boolean {
            var imeTop = Int.MAX_VALUE
            scenario.onActivity {
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { root ->
                    val insets = root.rootWindowInsets
                    if (insets?.isVisible(WindowInsets.Type.ime()) == true) {
                        val location = IntArray(2)
                        root.getLocationInWindow(location)
                        imeTop = minOf(imeTop, location[1] + root.height - insets.getInsets(WindowInsets.Type.ime()).bottom)
                    }
                }
            }
            check(imeTop != Int.MAX_VALUE) { "IME must remain open for keyboard CTA evidence" }
            return nodes { all ->
                all.filter { label(it) == context.getString(resource) }.any { candidate ->
                    var button: SemanticsNode? = candidate
                    while (button != null && button.config.getOrNull(SemanticsActions.OnClick) == null) button = button.parent
                    val target = button
                    geometry = "imeTop=$imeTop target=${target?.boundsInWindow} size=${target?.size}"
                    target != null && target.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button &&
                        target.boundsInWindow.width >= target.size.width * .99f &&
                        target.boundsInWindow.height >= target.size.height * .99f &&
                        target.boundsInWindow.top >= 0 && target.boundsInWindow.bottom <= imeTop
                }
            }
        }
        repeat(12) {
            if (aboveIme()) return
            nodes { all -> all.lastOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                ?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 220f) }
            settle()
        }
        assertTrue("Complete CTA must be scrollable above the open IME: $resource; $geometry", aboveIme())
    }
    private fun hideKeyboard(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val manager = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { manager.hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
        }
        settle()
    }
    private fun awaitKeyboard(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var visible = false
        while (!visible) {
            scenario.onActivity {
                visible = android.view.inspector.WindowInspector.getGlobalWindowViews().any { root -> root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            }
            check(SystemClock.uptimeMillis() < deadline) { "Actual IME did not become visible; do not label screenshot as keyboard coverage" }
            if (!visible) Thread.sleep(100)
        }
    }
    @Composable private fun Theme(dark: Boolean, content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
        val width = LocalWindowInfo.current.containerSize.width
        CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark, LocalDensity provides Density(width.coerceAtLeast(1) / 360f, 1.3f)) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
            }
        }
    }
    private fun settle() { Thread.sleep(650); instrumentation.waitForIdleSync() }
    private fun capture(dark: Boolean, name: String) {
        settle()
        val directory = File(context.cacheDir, "ui-review/git-credentials").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
