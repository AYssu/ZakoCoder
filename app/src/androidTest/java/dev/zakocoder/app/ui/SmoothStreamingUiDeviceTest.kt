package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.ai.AiEntry
import dev.zakocoder.app.ai.AiRole
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Synthetic render fixture: no provider requests or user conversation access. */
@SdkSuppress(minSdkVersion = 30)
class SmoothStreamingUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun foreground() = warmDeviceActivity()
    @Test fun day() = render(false)
    @Test fun night() = render(true)

    private fun render(dark: Boolean) {
        val source = mutableStateOf("")
        val mounted = mutableStateOf(false)
        val active = mutableStateOf(true)
        val observed = AtomicReference("")
        val answer = "## 流式回答\n\n正在逐步显示回复，保留完整 Markdown、代码与路径引用。😀\n\n" +
            "| 检查项 | 状态 |\n| --- | --- |\n| 项目文件 | 已读取 |\n\n```kotlin\nval message = \"Hello Android\"\n```\n\n修改位置：app/build.gradle.kts"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(Modifier.padding(16.dp)) {
                            val visible = if (mounted.value) smoothStreamingText("fixture-observer", source.value, active.value, animateOnMount = true) else ""
                            SideEffect { observed.set(visible) }
                            if (mounted.value) AiMessage(AiEntry("smooth-fixture", source.value, role = AiRole.Assistant),
                                null, {}, {}, responseStreaming = active.value, animateOnMount = true)
                        }
                    }
                }
            } }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { source.value = answer; mounted.value = true }
            Thread.sleep(160)
            val intermediate = observed.get()
            assertTrue("burst should reveal progressively", intermediate.isNotEmpty() && intermediate.length < answer.length)
            capture(dark, "streaming")
            instrumentation.runOnMainSync { active.value = false }
            val deadline = android.os.SystemClock.uptimeMillis() + 3000
            while (observed.get() != answer && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(30)
            assertEquals("finished burst must retain all content", answer, observed.get())
            Thread.sleep(160)
            capture(dark, "complete")
        }
    }

    private fun capture(dark: Boolean, name: String) {
        val root = File(instrumentation.targetContext.cacheDir, "ui-review/smooth-streaming").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(root, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
