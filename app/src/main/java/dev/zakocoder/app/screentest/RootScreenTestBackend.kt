package dev.zakocoder.app.screentest

import android.content.Context
import android.graphics.Rect
import dev.zakocoder.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Root is an execution backend, never a model-provided shell. Every command has fixed argv. */
class RootScreenTestBackend(private val context: Context) : ScreenTestBackend {
    @Volatile var granted = false
        private set
    @Volatile private var process: Process? = null
    private val epoch = AtomicLong()
    private val operations = Mutex()
    private val processLock = Any()
    private var operationGeneration = 0L
    private var snapshot: Snapshot? = null

    suspend fun requestAccess(): Boolean {
        val requestedGeneration = epoch.get()
        return withContext(Dispatchers.IO) {
        operations.withLock {
            currentCheck(requestedGeneration)
            operationGeneration = requestedGeneration
            granted = false
            try {
                val root = command(listOf("id", "-u"), 45, "access").trim() == "0"
                synchronized(processLock) {
                    currentCheck(operationGeneration)
                    granted = root
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { granted = false }
            granted
        }
        }
    }

    override fun stop() {
        synchronized(processLock) {
            epoch.incrementAndGet()
            // A completed su capability grant is not a task-control grant. The controller owns
            // selected-project/task authorization; retain capability while stopping operations.
            snapshot = null
            process?.destroyForcibly()
        }
    }

    override suspend fun execute(packageName: String, args: JSONObject): ScreenTestResult {
        val requestedGeneration = epoch.get()
        return withContext(Dispatchers.IO) {
        operations.withLock {
        if (!granted) fail(R.string.screen_test_root_access_required)
        currentCheck(requestedGeneration)
        val generation = requestedGeneration
        operationGeneration = generation
        fun valid() {
            currentCheck(generation)
        }
        valid()
        foreground(packageName)
        val action = args.getString("action")
        val coordinateAction = action in setOf("click", "long_click", "input_text", "swipe")
        // Coordinate actions build fixed argv from the last approved observation. A single
        // fresh hierarchy is compared immediately before input, rather than dumping twice.
        val nodes = if (coordinateAction) snapshot?.nodes.orEmpty() else readNodes(packageName)
        valid()
        if (!coordinateAction) foreground(packageName)
        when (action) {
            "observe" -> {
                val token = UUID.randomUUID().toString()
                val bounded = boundedScreenObservation(packageName, token,
                    nodes.mapIndexed { index, node -> node.json("n${index + 1}") })
                synchronized(processLock) {
                    valid()
                    snapshot = Snapshot(token, android.os.SystemClock.elapsedRealtime(), nodes, bounded.exposedCount)
                }
                ScreenTestResult(bounded.output)
            }
            "assert_text" -> {
                val expected = args.getString("text")
                require(expected.isNotBlank() && expected.length <= 1000)
                if (nodes.none { !it.sensitive && (expected in it.text || expected in it.description) }) fail(R.string.screen_test_assert_failed)
                ScreenTestResult("{\"assertion\":\"passed\",\"source\":\"visible_uiautomator_nodes\"}")
            }
            // Display-wide root screencap cannot establish window isolation or FLAG_SECURE safety.
            // The controller may compose this backend with protected accessibility capture.
            "screenshot" -> fail(R.string.screen_test_root_capture_requires_accessibility)
            "back" -> {
                snapshot = null
                valid()
                foreground(packageName)
                // BACK is dispatched to the focused window, not a screen coordinate. System
                // bars outside the app must not prevent it; coordinate actions keep overlay checks.
                command(listOf("input", "keyevent", "4"), stage = "input")
                requested(action)
            }
            "click", "long_click", "input_text", "swipe" -> {
                val old = snapshot ?: fail(R.string.screen_test_stale_snapshot)
                if (old.token != args.optString("snapshot") || android.os.SystemClock.elapsedRealtime() - old.time > 60_000 || old.nodes != nodes)
                    fail(R.string.screen_test_stale_snapshot)
                snapshot = null
                val index = parseNodeIndex(args.getString("node"))
                val node = index?.takeIf { it < old.exposedCount }?.let(nodes::getOrNull) ?: fail(R.string.screen_test_stale_snapshot)
                if (node.sensitive) fail(R.string.screen_test_sensitive)
                if (!node.enabled || node.bounds.isEmpty) fail(R.string.screen_test_action_failed)
                val argv = if (action == "swipe") {
                    val bounds = node.bounds
                    val x = bounds.centerX(); val y = bounds.centerY()
                    val dx = bounds.width() / 4; val dy = bounds.height() / 4
                    val points = when (args.getString("direction")) {
                        "up" -> listOf(x, y + dy, x, y - dy)
                        "down" -> listOf(x, y - dy, x, y + dy)
                        "left" -> listOf(x + dx, y, x - dx, y)
                        "right" -> listOf(x - dx, y, x + dx, y)
                        else -> fail(R.string.screen_test_action_failed)
                    }
                    listOf("input", "swipe") + points.map(Int::toString) + "350"
                } else {
                    val x = node.bounds.centerX().toString(); val y = node.bounds.centerY().toString()
                    when (action) {
                        "input_text" -> {
                            // Android shell input cannot reliably type arbitrary Unicode or literal %s.
                            // Do not replace the user's clipboard to work around this limitation.
                            val text = args.getString("text")
                            if (!node.editable || !node.focused || nodes.count { it.focused && it.editable } != 1 || text.length > 2000 ||
                                text.any { it.code !in 32..126 } || "%s" in text) fail(R.string.screen_test_root_text)
                            listOf("input", "text", text.replace(" ", "%s"))
                        }
                        "long_click" -> listOf("input", "swipe", x, y, x, y, "700")
                        else -> listOf("input", "tap", x, y)
                    }
                }
                valid()
                if (readNodes(packageName) != old.nodes || android.os.SystemClock.elapsedRealtime() - old.time > 60_000)
                    fail(R.string.screen_test_stale_snapshot)
                valid()
                requireUncovered(packageName, node.bounds)
                valid()
                if (android.os.SystemClock.elapsedRealtime() - old.time > 60_000)
                    fail(R.string.screen_test_stale_snapshot)
                command(argv, stage = "input")
                valid()
                requested(action)
            }
            else -> fail(R.string.screen_test_action_failed)
        }
        }
        }
    }

    private fun currentCheck(generation: Long) {
        if (generation != epoch.get()) fail(R.string.screen_test_stopped)
    }

    private suspend fun foreground(packageName: String) {
        val windows = command(listOf("dumpsys", "window", "-a"), stage = "foreground")
        val focus = focusedWindow(windows)?.second
        if (focus != packageName) fail(R.string.screen_test_wrong_foreground)
    }

    private suspend fun requireUncovered(packageName: String, bounds: Rect) {
        val dump = command(listOf("dumpsys", "window", "-a"), stage = "overlay")
        // This one fresh window dump verifies both current focus and coordinate isolation.
        if (focusedWindow(dump)?.second != packageName) fail(R.string.screen_test_wrong_foreground)
        if (!isUncoveredWindowRegion(dump, packageName, bounds.left, bounds.top, bounds.right, bounds.bottom))
            fail(R.string.screen_test_root_window_not_isolated)
    }

    private suspend fun readNodes(packageName: String): List<Node> {
        val directory = File(context.cacheDir, "screen-test-root").apply { mkdirs() }
        val file = File.createTempFile("hierarchy-", ".xml", directory)
        try {
            command(listOf("uiautomator", "dump", "--compressed", file.absolutePath), 12, "hierarchy_dump")
            if (file.length() !in 1..1_048_576) diagnostic("hierarchy_size")
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            val result = mutableListOf<Node>()
            file.inputStream().use { input ->
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                val sensitive = mutableListOf<Boolean>()
                while (event != XmlPullParser.END_DOCUMENT) {
                    currentCoroutineContext().ensureActive()
                    if (event == XmlPullParser.DOCDECL) diagnostic("hierarchy_doctype")
                    if (event == XmlPullParser.START_TAG && parser.name == "node") {
                        fun attr(name: String) = parser.getAttributeValue(null, name).orEmpty()
                        val hidden = sensitive.lastOrNull() == true || attr("password") == "true"
                        sensitive += hidden
                        if (attr("package") == packageName) {
                            if (result.size >= 300) diagnostic("hierarchy_node_limit")
                            val coordinates = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matchEntire(attr("bounds"))
                                ?.groupValues?.drop(1)?.map(String::toInt) ?: diagnostic("hierarchy_bounds")
                            val bounds = Rect(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
                            if (coordinates.any { it > 32768 } || bounds.isEmpty) diagnostic("hierarchy_bounds")
                            result += Node(if (hidden) "" else attr("text").take(160),
                                if (hidden) "" else attr("content-desc").take(160), attr("class").take(120),
                                attr("resource-id").take(200), bounds, hidden, attr("enabled") == "true",
                                attr("clickable") == "true", attr("long-clickable") == "true",
                                attr("class").endsWith("EditText"), attr("focused") == "true")
                        }
                    } else if (event == XmlPullParser.END_TAG && parser.name == "node") sensitive.removeAt(sensitive.lastIndex)
                    event = parser.nextToken()
                }
            }
            if (result.isEmpty()) fail(R.string.screen_test_wrong_foreground)
            return result
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (known: ScreenTestException) { throw known }
        catch (_: Exception) { diagnostic("hierarchy_parse") }
        finally { file.delete() }
    }

    private suspend fun command(argv: List<String>, timeoutSeconds: Long = 8, stage: String): String = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(timeoutSeconds in 1..60)
        currentCheck(operationGeneration)
        val log = File.createTempFile("root-command-", ".log", context.cacheDir)
        val command = "exec /system/bin/timeout $timeoutSeconds " + argv.joinToString(" ", transform = ::shellQuote)
        var child: Process? = null
        val started = System.nanoTime()
        try {
            val running = ProcessBuilder("su", "-c", command).redirectErrorStream(true).redirectOutput(log).start()
            child = running
            synchronized(processLock) {
                if (epoch.get() != operationGeneration) {
                    running.destroyForcibly()
                    fail(R.string.screen_test_stopped)
                }
                process = running
            }
            while (!running.waitFor(50, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                currentCheck(operationGeneration)
                if (deadlineExceeded(started, System.nanoTime(), timeoutSeconds)) throw ScreenTestException(context.getString(R.string.screen_test_root_stage_timeout,
                    screenTestStageLabel(context, stage), timeoutSeconds), ScreenTestTimeoutDiagnostic(stage))
                if (log.length() > 2_097_152) diagnostic("${stage}_output_limit")
            }
            currentCoroutineContext().ensureActive()
            currentCheck(operationGeneration)
            val exit = running.exitValue()
            if (isTimeoutExit(exit)) throw ScreenTestException(context.getString(R.string.screen_test_root_stage_timeout,
                    screenTestStageLabel(context, stage), timeoutSeconds), ScreenTestTimeoutDiagnostic(stage))
            if (exit != 0) throw ScreenTestException(context.getString(R.string.screen_test_root_command_exit, stage, exit))
            if (log.length() > 2_097_152) diagnostic("${stage}_output_limit")
            log.readText()
        } finally {
            child?.destroyForcibly()
            synchronized(processLock) { if (process === child) process = null }
            log.delete()
        }
    }

    private fun diagnostic(stage: String): Nothing =
        throw ScreenTestException(context.getString(R.string.screen_test_root_diagnostic, stage))

    private fun requested(action: String) = ScreenTestResult(JSONObject().put("requested", action).put("verified", false).put("next", "observe").toString())
    private fun fail(id: Int): Nothing = throw ScreenTestException(context.getString(id))
    private data class Snapshot(val token: String, val time: Long, val nodes: List<Node>, val exposedCount: Int)
    private data class Node(val text: String, val description: String, val className: String, val resource: String,
        val bounds: Rect, val sensitive: Boolean, val enabled: Boolean, val clickable: Boolean,
        val longClickable: Boolean, val editable: Boolean, val focused: Boolean) {
        fun json(id: String) = JSONObject().put("id", id).put("text", if (sensitive) "[redacted]" else text.take(80))
            .put("description", if (sensitive) "[redacted]" else description.take(80)).put("class", className.take(80))
            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("sensitive", sensitive).put("enabled", enabled).put("clickable", clickable)
            .put("longClickable", longClickable).put("editable", editable).put("focused", focused)
    }
    companion object {
        internal fun isTimeoutExit(exit: Int): Boolean = exit == 124

        /** Window-manager order is top to bottom. Never return foreign-window data to the model. */
        internal fun isUncoveredWindowRegion(dump: String, packageName: String, left: Int, top: Int, right: Int, bottom: Int): Boolean {
            if (left < 0 || top < 0 || left >= right || top >= bottom) return false
            val focus = focusedWindow(dump) ?: return false
            if (focus.second != packageName) return false
            val headers = Regex("(?m)^([ \t]*)Window #(\\d+) Window\\{([^\\s}]+)[^\\n]*\\}:").findAll(dump).toList()
            if (headers.isEmpty() || headers.size > 300 || headers.map { it.groupValues[3] }.distinct().size != headers.size) return false
            val order = headers.map { it.groupValues[2].toIntOrNull() ?: return false }
            if (order.zipWithNext().any { (a, b) -> a >= b }) return false
            val focused = headers.indexOfFirst { it.groupValues[3] == focus.first }
            if (focused < 0) return false
            fun block(index: Int): String? {
                // WindowState fields are nested below their header, but OEM LayoutParams
                // strings contain multiline attributes at shallower indentation. Only that
                // explicit balanced mAttrs value may cross the indentation boundary.
                val header = headers[index]
                val indent = header.groupValues[1].length
                val tail = dump.substring(header.range.last + 1,
                    headers.getOrNull(index + 1)?.range?.first ?: dump.length)
                val lines = mutableListOf<String>()
                var attributeDepth = 0
                for (line in tail.lineSequence()) {
                    if (line.isNotBlank() && attributeDepth == 0 &&
                        line.takeWhile { it == ' ' || it == '\t' }.length <= indent) break
                    if (attributeDepth > 0 || line.trimStart().startsWith("mAttrs={")) {
                        attributeDepth += line.count { it == '{' } - line.count { it == '}' }
                        if (attributeDepth < 0) return null
                    }
                    lines += line
                }
                if (attributeDepth != 0) return null
                return lines.joinToString("\n")
            }
            fun flag(block: String, key: String): Boolean? {
                val values = Regex("\\b" + key + "=(true|false)\\b").findAll(block).map { it.groupValues[1] }.toList()
                return values.singleOrNull()?.let { it == "true" }
            }
            fun frame(block: String): List<Int>? {
                val value = Regex("(?:mFrame|\\bframe)=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").findAll(block).toList().singleOrNull() ?: return null
                return value.groupValues.drop(1).map { it.toIntOrNull() ?: return null }.takeIf { it[0] < it[2] && it[1] < it[3] }
            }
            val target = block(focused) ?: return false
            if (flag(target, "mHasSurface") != true || flag(target, "isOnScreen") != true || flag(target, "isVisible") != true) return false
            val targetFrame = frame(target) ?: return false
            if (left < targetFrame[0] || top < targetFrame[1] || right > targetFrame[2] || bottom > targetFrame[3]) return false
            for (index in 0 until focused) {
                val other = block(index) ?: return false
                val surface = flag(other, "mHasSurface") ?: return false
                if (!surface) continue
                val onScreen = flag(other, "isOnScreen") ?: return false
                val visible = flag(other, "isVisible") ?: return false
                if (!onScreen || !visible) continue
                val bounds = frame(other) ?: return false
                if (left < bounds[2] && right > bounds[0] && top < bounds[3] && bottom > bounds[1]) return false
            }
            return true
        }
        /** Full (-a) dump supplies both current focus and detailed window frames on OEM Android. */
        internal fun focusedWindow(dump: String): Pair<String, String>? {
            val focus = Regex("(?m)^[ \t]*mCurrentFocus=Window\\{([^\\s}]+)[^\\n]*?[ \t]([^\\s/}]+)/")
                .findAll(dump).toList().singleOrNull() ?: return null
            return focus.groupValues[1] to focus.groupValues[2]
        }
        internal fun parseNodeIndex(value: String): Int? = if (Regex("n[1-9][0-9]{0,2}").matches(value))
            value.drop(1).toInt().minus(1) else null
        internal fun deadlineExceeded(startNanos: Long, nowNanos: Long, timeoutSeconds: Long): Boolean =
            nowNanos - startNanos >= TimeUnit.SECONDS.toNanos(timeoutSeconds)
        internal fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
