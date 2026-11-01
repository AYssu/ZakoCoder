package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.updates.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Before
import org.junit.Test
import java.io.File

/** Actual production update UI; explicit state fixtures do not perform downloads or claim installed results. */
class AppUpdateUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun foreground() = warmDeviceActivity()
    @Test fun updateReviewInDayAndNight() {
        for (dark in listOf(false, true)) {
            val release = AppRelease("v0.2.0-beta.1", "本次更新改进项目管理与开发体验。\n安装前请保存正在编辑的文件。", true,
                ReleaseAsset("app.apk", "", 12000000), ReleaseAsset("SHA256SUMS.txt", "", 100))
            for (phase in listOf(UpdatePhase.Available, UpdatePhase.Downloading, UpdatePhase.Ready, UpdatePhase.Failed)) {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                                    UpdateContent(UpdateState(initialized = true, channel = UpdateChannel.Beta, currentVersion = "0.1.0-beta.2",
                                        phase = phase, release = release, received = 4000000, total = 12000000,
                                        error = if (phase == UpdatePhase.Failed) R.string.update_download_failed else null),
                                        { _, _ -> }, {}, {}, {}, {})
                                }
                            }
                        }
                    } }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(300)
                    val target = File(instrumentation.targetContext.filesDir, "screen-review/updates-${if (dark) "night" else "day"}-${phase.name.lowercase()}.png")
                    target.parentFile!!.mkdirs()
                    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    bitmap.recycle()
                }
            }
        }
    }
}
