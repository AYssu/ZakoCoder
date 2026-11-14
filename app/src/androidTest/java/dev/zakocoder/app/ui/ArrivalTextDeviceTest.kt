package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class ArrivalTextDeviceTest {
    @Test fun appendedGlyphsFadeWhileExistingTextStaysStable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        warmDeviceActivity()
        val source = mutableStateOf("Stable text\n")
        val animated = mutableStateOf(false)
        val bounds = AtomicReference<androidx.compose.ui.geometry.Rect>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize(), color = Color.White) {
                        Box(Modifier.safeDrawingPadding().padding(20.dp)) {
                            ArrivalText(source.value, Modifier.width(300.dp).height(160.dp)
                                .onGloballyPositioned { bounds.set(it.boundsInWindow()) },
                                color = Color.Black, fontSize = 24.sp, lineHeight = 40.sp,
                                fontFamily = FontFamily.Monospace, animated = animated.value,
                                // Capture can outlast the normal 200ms on a software-rendered emulator.
                                // Keep the same drawing path, with a longer sampling window in this fixture.
                                durationMillis = 2000)
                        }
                    }
                }
            } }
            Thread.sleep(450)
            instrumentation.runOnMainSync { source.value += "New output"; animated.value = true }
            Thread.sleep(250)
            val early = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            Thread.sleep(2200)
            val complete = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val rect = bounds.get()
                val density = instrumentation.targetContext.resources.displayMetrics.scaledDensity
                val split = (rect.top + 40 * density).toInt()
                fun ink(bitmap: Bitmap, start: Int, end: Int): Long {
                    var total = 0L
                    for (y in start until end) for (x in rect.left.toInt() until rect.right.toInt()) {
                        val pixel = bitmap.getPixel(x, y)
                        total += 255 - android.graphics.Color.red(pixel)
                    }
                    return total
                }
                val oldEarly = ink(early, rect.top.toInt(), split)
                val oldComplete = ink(complete, rect.top.toInt(), split)
                assertTrue("Existing text must not fade again", kotlin.math.abs(oldEarly - oldComplete) < oldComplete / 50 + 1)
                val addedEarly = ink(early, split, rect.bottom.toInt())
                val addedComplete = ink(complete, split, rect.bottom.toInt())
                assertTrue("New glyphs must have an intermediate opacity: $addedEarly / $addedComplete", addedEarly > 0 && addedEarly < addedComplete * .98)
                val root = File(instrumentation.targetContext.cacheDir, "ui-review/presentation").apply { mkdirs() }
                for ((name, image) in listOf("glyph-early" to early, "glyph-complete" to complete)) {
                    File(root, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { early.recycle(); complete.recycle() }
        }
    }
}
