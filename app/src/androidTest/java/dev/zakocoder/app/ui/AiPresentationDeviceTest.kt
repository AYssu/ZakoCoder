package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Test
import java.io.File

/** Isolated presentation fixture; never executes tools or reads saved conversations. */
class AiPresentationDeviceTest {
    @Test fun dayAndNightHierarchy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        warmDeviceActivity()
        val dark = mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark.value) {
                    WorkbenchMiuixTheme(dark.value) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AiMessage(AiEntry("user", "修复登录页的校验，并运行测试。", role = AiRole.User), null, {}, {})
                                AiPlanPanel(listOf(AiPlanStep("检查表单与已有测试", "completed"),
                                    AiPlanStep("修复校验并补充边界测试", "in_progress"),
                                    AiPlanStep("运行测试并检查变更", "pending")), true, 0)
                                AiMessage(AiEntry("reasoning", reasoning = "先检查输入校验和提交事件，确认空值的处理方式，再修改对应的边界条件。", role = AiRole.Assistant),
                                    null, {}, {}, reasoningStreaming = true)
                                AiReadActivity(listOf(tool("read", AiTool.ReadFile, "src/LoginForm.tsx", AiToolStatus.Succeeded),
                                    tool("search", AiTool.SearchFiles, "validateEmail", AiToolStatus.Succeeded)))
                                AiToolCard(tool("write", AiTool.EditFile, "src/LoginForm.tsx", AiToolStatus.Succeeded))
                                AiToolCard(tool("test", AiTool.RunCommand, "npm test -- LoginForm", AiToolStatus.Running))
                                AiMessage(AiEntry("answer", "## 已修复输入校验\n\n空白邮箱不会再触发提交。错误提示保留在输入框下方，用户可以直接修改后重试。\n\n- 修改：`src/LoginForm.tsx`\n- 新增空值与格式错误测试\n- 测试仍在运行，完成后核对结果。", role = AiRole.Assistant), null, {}, {})
                            }
                        }
                    }
                }
            } }
            for (night in listOf(false, true)) {
                instrumentation.runOnMainSync { dark.value = night }
                Thread.sleep(700)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val file = File(instrumentation.targetContext.cacheDir,
                        "ui-review/presentation/${if (night) "night" else "day"}-hierarchy.png")
                    file.parentFile?.mkdirs()
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
    }

    private fun tool(id: String, tool: AiTool, target: String, status: AiToolStatus) =
        AiToolEvent(id, id, tool, tool.wire, "{}", "按任务检查与修改项目", target, status)
}
