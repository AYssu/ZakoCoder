package dev.zakocoder.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.screentest.ScreenTestAccess
import dev.zakocoder.app.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Render-only fixture: never installs apps, grants accessibility/root, or reads user projects. */
@SdkSuppress(minSdkVersion = 30)
class CapabilityPackagesUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()

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
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun has(label: String) = nodes { all -> all.any { text(it) == label } }
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= until) throw AssertionError(label)
            Thread.sleep(50)
        }
    }
    private fun click(label: String) {
        await("Control: $label") { has(label) }
        await("Enabled control: $label") {
            nodes { all ->
                var node = all.lastOrNull { text(it) == label } as SemanticsNode?
                while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
                node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
            }
        }
        nodes { all ->
            var node = all.last { text(it) == label } as SemanticsNode?
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            val control = requireNotNull(node)
            assertNull("Enabled: $label", control.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(control.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun edit(label: String, value: String) {
        await("Editable field: $label") { has(label) }
        nodes { all ->
            var node = all.last { text(it) == label } as SemanticsNode?
            while (node != null && node.config.getOrNull(SemanticsActions.SetText) == null) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.SetText].action
                ?.invoke(androidx.compose.ui.text.AnnotatedString(value)) == true)
        }
        await("Edited value committed: $label") {
            // Password semantics may contain transformed bullets rather than the raw draft.
            editableValue(label)?.length == value.length
        }
        instrumentation.waitForIdleSync()
    }
    private fun editableValue(label: String): String? = nodes { all ->
        var node = all.lastOrNull { text(it) == label } as SemanticsNode?
        while (node != null && node.config.getOrNull(SemanticsActions.SetText) == null) node = node.parent
        node?.config?.getOrNull(SemanticsProperties.EditableText)?.text
    }
    private fun reveal(label: String) {
        // Expansion is a recomposition, not an immediate mutation of the semantics tree.
        await("Reveal content composed: $label") { has(label) }
        var diagnostic = "No target geometry"
        repeat(30) {
            val visible = nodes { all ->
                val target = all.lastOrNull { text(it) == label } ?: return@nodes false
                var scroll: SemanticsNode? = target
                while (scroll != null && scroll.config.getOrNull(SemanticsActions.ScrollBy) == null) scroll = scroll.parent
                val viewport = scroll?.boundsInWindow
                val targetTop = target.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                val targetBottom = targetTop + target.layoutInfo.coordinates.size.height
                // Use the actual clipped scroll viewport, including this Sheet's system insets.
                // A fixed 100px bottom margin cannot be satisfied by some final list items.
                val visibleTop = (viewport?.top ?: 0f) + 4f
                val visibleBottom = (viewport?.bottom ?: context.resources.displayMetrics.heightPixels.toFloat()) - 4f
                val axis = scroll?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                diagnostic = "target=$targetTop..$targetBottom viewport=$visibleTop..$visibleBottom " +
                    "scroll=${axis?.value?.invoke()}/${axis?.maxValue?.invoke()}"
                if (targetTop >= visibleTop && targetBottom <= visibleBottom) return@nodes true
                val delta = when {
                    targetBottom > visibleBottom -> targetBottom - visibleBottom + 8f
                    targetTop < visibleTop -> targetTop - visibleTop - 8f
                    else -> 0f
                }
                scroll?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, delta)
                false
            }
            if (visible) return
            instrumentation.waitForIdleSync(); SystemClock.sleep(120)
        }
        throw AssertionError("Could not reveal: $label; $diagnostic")
    }
    @Test(timeout = 120000) fun lightCapabilityPackagesAndSsh() = review(false)
    @Test(timeout = 120000) fun darkCapabilityPackagesAndSsh() = review(true)

    private fun review(dark: Boolean) {
        val screen = AtomicBoolean()
        val ssh = AtomicBoolean()
        val details = AtomicBoolean()
        val saved = AtomicReference<SshConnectionDraft>()
        val tested = AtomicBoolean()
        val trusted = AtomicBoolean()
        val forgotten = AtomicBoolean()
        val disconnected = AtomicBoolean()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var fixtureGeneration = 0
            fun show(content: @Composable () -> Unit) {
                val generation = ++fixtureGeneration
                val committed = AtomicBoolean()
                scenario.onActivity { activity -> activity.setContent {
                    val current = LocalConfiguration.current
                    val widthPx = LocalWindowInfo.current.containerSize.width
                    val configuration = remember(current, dark) { Configuration(current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    } }
                    CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark,
                        LocalDensity provides Density(widthPx.coerceAtLeast(1) / 360f, 1.3f)) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            // Each unrelated fixture must have its own draft state. Revisions inside
                            // a fixture still recompose normally, exercising real successful-save cleanup.
                            key(generation) {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                            }
                        }
                    }
                    SideEffect { committed.set(true) }
                } }
                // setContent posts composition. Existing labels may still belong to the preceding
                // fixture, so waiting only for a shared label can edit a draft that is about to vanish.
                await("Fixture $generation committed") { committed.get() }
                instrumentation.waitForIdleSync()
            }
            fun packages(model: CapabilityPackagesUiModel) {
                show { CapabilityPackagesSheet(model, { screen.set(it) }, { ssh.set(it) }, { details.set(true) }, { details.set(true) }, {}) }
            }
            fun sshPanel(model: SshPackageUiModel) {
                show { SshPackageSheet(model, { saved.set(it) }, { tested.set(true) }, { disconnected.set(true) },
                    { trusted.set(true) }, { forgotten.set(true) }, {}) }
            }
            packages(CapabilityPackagesUiModel())
            await("No selected project") { has(context.getString(R.string.capability_packages_no_project)) }
            assertFalse(has(context.getString(R.string.capability_screen_title)))
            capture(dark, "no-project")
            packages(CapabilityPackagesUiModel(projectName = "Screen test fixture", hasProject = true))
            await("Both packages default off") { has(context.getString(R.string.capability_screen_title)) }
            nodes { all ->
                val switches = all.filter { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Switch }
                assertEquals(2, switches.size)
                switches.forEach { assertEquals(androidx.compose.ui.state.ToggleableState.Off,
                    it.config.getOrNull(SemanticsProperties.ToggleableState)) }
                assertTrue(switches.first().config[SemanticsActions.OnClick].action?.invoke() == true)
                assertTrue(switches.last().config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            assertTrue(screen.get()); assertTrue(ssh.get())
            capture(dark, "packages-default-off-360-font130")
            packages(CapabilityPackagesUiModel("Screen test fixture", true, screenEnabled = true, sshEnabled = true))
            val screenConfigure = context.getString(R.string.capability_screen_configure)
            reveal(screenConfigure); click(screenConfigure); assertTrue(details.get())
            capture(dark, "screen-enabled")
            val sshConfigure = context.getString(R.string.capability_ssh_configure)
            reveal(sshConfigure); capture(dark, "ssh-enabled")
            sshPanel(SshPackageUiModel())
            await("Connection form") { has(context.getString(R.string.ssh_package_host)) }
            assertFalse(has(context.getString(R.string.ssh_package_secret_saved)))
            capture(dark, "ssh-empty")
            reveal(context.getString(R.string.ssh_package_save)); click(context.getString(R.string.ssh_package_save))
            await("Invalid config shown") { has(context.getString(R.string.ssh_package_invalid)) }
            assertNull(saved.get())
            capture(dark, "ssh-validation")
            val configured = SshPackageUiModel(configured = true, host = "dev.example.invalid", user = "developer", hasSecret = true,
                tmuxEnabled = true, status = "连接前需要核对主机指纹")
            show {
                var current by remember { mutableStateOf(configured) }
                SshPackageSheet(current, { draft -> saved.set(draft); current = current.copy(configRevision = current.configRevision + 1) },
                    {}, {}, {}, {}, {})
            }
            edit(context.getString(R.string.ssh_package_password), "fixture-only-not-a-real-password")
            reveal(context.getString(R.string.ssh_package_save)); click(context.getString(R.string.ssh_package_save))
            await("Credential save callback") { saved.get() != null }
            assertEquals("fixture-only-not-a-real-password", saved.get().secret)
            assertEquals("/", saved.get().workingDirectory)
            assertFalse(saved.get().toString().contains(saved.get().secret))
            await("Successful revision clears credential draft") {
                editableValue(context.getString(R.string.ssh_package_password)) == ""
            }
            sshPanel(configured)
            await("Stored secret is not projected") { has(context.getString(R.string.ssh_package_secret_saved)) }
            capture(dark, "ssh-password-configured")
            reveal(context.getString(R.string.ssh_package_test)); click(context.getString(R.string.ssh_package_test)); assertTrue(tested.get())
            capture(dark, "ssh-connection-status")
            sshPanel(configured.copy(pendingAlgorithm = "ssh-ed25519", pendingFingerprint = "SHA256:Qm9vdHN0cmFwVGVzdEZpeHR1cmVGaW5nZXJwcmludAA"))
            reveal(context.getString(R.string.ssh_package_trust)); capture(dark, "ssh-first-host-confirmation")
            click(context.getString(R.string.ssh_package_trust)); assertTrue(trusted.get())
            sshPanel(configured.copy(trustedFingerprint = "SHA256:dHJ1c3RlZC1maXh0dXJl", pendingFingerprint = "SHA256:Y2hhbmdlZC1maXh0dXJl", fingerprintChanged = true))
            reveal(context.getString(R.string.ssh_package_fingerprint_changed))
            nodes { all ->
                var node = all.last { text(it) == context.getString(R.string.ssh_package_trust) } as SemanticsNode?
                while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
                assertNotNull(requireNotNull(node).config.getOrNull(SemanticsProperties.Disabled))
            }
            reveal(context.getString(R.string.ssh_package_forget_host))
            capture(dark, "ssh-host-key-changed")
            click(context.getString(R.string.ssh_package_forget_host)); assertTrue(forgotten.get())
            sshPanel(configured.copy(auth = SshAuthUi.PrivateKey))
            await("Private key form") { has(context.getString(R.string.ssh_package_private_key)) }
            capture(dark, "ssh-private-key")
            sshPanel(configured.copy(busy = true, status = "正在验证 SSH 连接"))
            reveal(context.getString(R.string.ssh_package_disconnect)); capture(dark, "ssh-connecting-cancel")
            click(context.getString(R.string.ssh_package_disconnect)); assertTrue(disconnected.get())
            disconnected.set(false)
            sshPanel(configured.copy(connected = true, tmuxAvailable = true, status = "连接成功；远端 tmux 可用"))
            reveal(context.getString(R.string.ssh_package_disconnect)); capture(dark, "ssh-connected-tmux")
            click(context.getString(R.string.ssh_package_disconnect)); assertTrue(disconnected.get())
            val licenseClicked = AtomicBoolean()
            show { WorkbenchSheet(context.getString(R.string.about_title), {}) { SshLicenseEntry { licenseClicked.set(true) } } }
            await("JSch license entry") { has(context.getString(R.string.ssh_license_entry)) }
            capture(dark, "about-ssh-license-entry")
            click(context.getString(R.string.ssh_license_entry)); assertTrue(licenseClicked.get())
            val license = context.assets.open("licenses/JSch-BSD.txt").bufferedReader().use { it.readText() }
            assertTrue(license.contains("Atsuhiko Yamanaka"))
            show { WorkbenchSheet(context.getString(R.string.ssh_license_title), {}) { SshLicensePanel(license) } }
            await("Real BSD license asset") { has(context.getString(R.string.ssh_license_component)) }
            capture(dark, "ssh-bsd-license")
        }
    }

    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        // A passing semantic callback can precede the Sheet expansion animation. Wait for
        // actual window-relative geometry to settle before treating the screenshot as evidence.
        SystemClock.sleep(400)
        var previous = emptyList<Pair<String, Int>>()
        var stable = 0
        val until = SystemClock.uptimeMillis() + 5000
        while (stable < 3 && SystemClock.uptimeMillis() < until) {
            val geometry = nodes { all -> all.filter { text(it).isNotBlank() }.map {
                text(it) to it.layoutInfo.coordinates.localToWindow(Offset.Zero).y.toInt()
            } }
            stable = if (geometry == previous) stable + 1 else 0
            previous = geometry
            SystemClock.sleep(80)
        }
        assertTrue("Sheet layout settled before $name", stable >= 3)
        val raw = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/capability-packages/${if (dark) "dark" else "light"}-$name.png")
                .apply { parentFile!!.mkdirs() }.outputStream().use { assertTrue(raw.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { raw.recycle() }
    }
}
