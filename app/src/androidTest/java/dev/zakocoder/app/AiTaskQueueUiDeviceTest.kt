package dev.zakocoder.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual Android Compose rendering with synthetic content; controller behavior has separate TLS tests. */
class AiTaskQueueUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test(timeout = 60000) fun dayQueueAndSupplement() = runBlocking { render(false) }
    @Test(timeout = 60000) fun nightQueueAndSupplement() = runBlocking { render(true) }
    private suspend fun render(dark: Boolean) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".runtimecheck"))
        withContext(Dispatchers.IO) { warmDeviceActivity() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                                Text("助手 · 移动项目", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(24.dp))
                                Text("先实现文件编辑，再运行相关测试。")
                                Spacer(Modifier.height(16.dp))
                                Text("Thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                AiTaskQueuePanel(listOf(AiQueuedMessage(text = "完成后运行单元测试并报告结果", kind = AiQueuedKind.FollowUp, turn = 1)),
                                    running = true, enabled = true, onCancel = {}, onStart = {})
                                AiRunningInputActions(true, {}, {})
                                AiComposerDock("先不要修改配置文件，检查现有实现", {}, true, false, false, true, true, {}, {}) {
                                    Text("测试模型 · 默认 · 128K", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            } }
            delay(800)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.cacheDir, "ui-review/ai-task-queue/${if (dark) "night" else "day"}-queue.png").apply { parentFile!!.mkdirs() }
                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }
}
