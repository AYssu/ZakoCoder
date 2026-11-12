package dev.zakocoder.app.capabilities

import android.content.Context
import android.util.AtomicFile
import dev.zakocoder.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class CapabilityPackage(val wire: String) { Screen("screen"), Ssh("ssh") }
data class CapabilityPackagesState(
    val loaded: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val enabled: Map<String, Set<CapabilityPackage>> = emptyMap(),
    val screenEnabled: Boolean = false,
)

/** Screen availability is global; SSH remains project scoped. Neither switch grants device/host access. */
class CapabilityPackages(context: Context, private val changed: (String, CapabilityPackage) -> Unit = { _, _ -> }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val file = AtomicFile(File(context.filesDir, "capability-packages.json"))
    private val failure = context.getString(R.string.capability_packages_storage_error)
    private val mutable = MutableStateFlow(CapabilityPackagesState())
    val state = mutable.asStateFlow()
    private var leases = emptyMap<Pair<String, CapabilityPackage>, String>()
    private val deviceScope = "device-screen-global"
    init { scope.launch {
        try {
            val (loaded, screenEnabled) = withContext(Dispatchers.IO) {
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyMap<String, Set<CapabilityPackage>>() to false else {
                    val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                    val version = json.getInt("version")
                    require(version in 1..2)
                    val projects = json.getJSONObject("projects")
                    val configured = projects.keys().asSequence().associateWith { id ->
                        val names = projects.getJSONArray(id)
                        (0 until names.length()).mapNotNull { index ->
                            // Legacy per-project screen switches cannot become device-wide consent.
                            CapabilityPackage.entries.singleOrNull { it == CapabilityPackage.Ssh && it.wire == names.getString(index) }
                        }.toSet()
                    }
                    configured to (version == 2 && json.optBoolean("screen_enabled", false))
                }
            }
            leases = loaded.flatMap { (id, kinds) -> kinds.map { (id to it) to UUID.randomUUID().toString() } }.toMap()
            if (screenEnabled) leases = leases + ((deviceScope to CapabilityPackage.Screen) to UUID.randomUUID().toString())
            mutable.value = CapabilityPackagesState(loaded = true, enabled = loaded, screenEnabled = screenEnabled)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = CapabilityPackagesState(loaded = true, error = failure) }
    } }
    fun enabled(id: String?, kind: CapabilityPackage): Boolean =
        mutable.value.loaded && mutable.value.error == null && if (kind == CapabilityPackage.Screen) mutable.value.screenEnabled
        else id != null && snapshot(id).containsKey(kind)
    fun snapshot(id: String): Map<CapabilityPackage, String> =
        if (!mutable.value.loaded || mutable.value.error != null) emptyMap()
        else CapabilityPackage.entries.mapNotNull { kind -> leases[(if (kind == CapabilityPackage.Screen) deviceScope else id) to kind]?.let { kind to it } }.toMap()

    fun setScreenEnabled(value: Boolean) = setEnabled(deviceScope, CapabilityPackage.Screen, value)

    /** Main-thread UI owner. Revoke first; a failed save never revives the old grant. */
    fun setEnabled(id: String, kind: CapabilityPackage, value: Boolean) {
        if (!mutable.value.loaded || mutable.value.busy || id.isBlank()) return
        val key = (if (kind == CapabilityPackage.Screen) deviceScope else id) to kind
        leases = leases - key
        if (value) leases = leases + (key to UUID.randomUUID().toString())
        val kinds = mutable.value.enabled[id].orEmpty().let { if (value) it + kind else it - kind }
        val enabled = if (kind == CapabilityPackage.Screen) mutable.value.enabled else mutable.value.enabled + (id to kinds)
        val screenEnabled = if (kind == CapabilityPackage.Screen) value else mutable.value.screenEnabled
        mutable.value = mutable.value.copy(busy = true, enabled = enabled, screenEnabled = screenEnabled, error = null)
        changed(if (kind == CapabilityPackage.Screen) deviceScope else id, kind)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val projects = JSONObject()
                    enabled.forEach { (project, packages) -> projects.put(project, JSONArray(packages.map { it.wire })) }
                    val output = file.startWrite()
                    try {
                        output.write(JSONObject().put("version", 2).put("screen_enabled", screenEnabled).put("projects", projects).toString().toByteArray())
                        file.finishWrite(output)
                    } catch (error: Exception) { file.failWrite(output); throw error }
                }
                mutable.update { it.copy(busy = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                leases = emptyMap()
                changed(deviceScope, CapabilityPackage.Screen)
                enabled.forEach { (project, packages) -> packages.forEach { changed(project, it) } }
                mutable.update { it.copy(busy = false, enabled = emptyMap(), screenEnabled = false, error = failure) }
            }
        }
    }
}
