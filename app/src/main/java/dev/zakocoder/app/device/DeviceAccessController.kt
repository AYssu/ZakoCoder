package dev.zakocoder.app.device

import androidx.core.content.edit
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Independent of AI tool approval and PRoot's guest UID. An old screen grant never enables Root. */
enum class DeviceMode { Basic, Root }

data class DevicePermissionGrants(
    val notifications: Boolean = false,
    val allFiles: Boolean = false,
    val installPackages: Boolean = false,
    val batteryUnrestricted: Boolean = false,
)

data class DeviceAccessState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val mode: DeviceMode = DeviceMode.Basic,
    val rootGranted: Boolean = false,
    /** Stable error code; presentation owns localization. */
    val error: String? = null,
    val grants: DevicePermissionGrants = DevicePermissionGrants(),
)

class DeviceAccessController(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("device_access", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val epoch = AtomicLong()
    private val selectionLock = Any()
    private val shell = DeviceShell(this.context)
    private val mutableState = MutableStateFlow(DeviceAccessState())
    val state: StateFlow<DeviceAccessState> = mutableState.asStateFlow()
    val generation: Long get() = epoch.get()

    init {
        scope.launch {
            mutex.withLock {
                synchronized(selectionLock) {
                    val mode = if (preferences.getString("mode", null) == "root") DeviceMode.Root else DeviceMode.Basic
                    mutableState.value = DeviceAccessState(loaded = true, mode = mode, grants = readGrants())
                }
            }
        }
    }

    /** The only entry point that opts the application into Root and requests initial access. */
    suspend fun enableRoot(): Boolean = mutex.withLock {
        val lease = generation
        mutableState.update { it.copy(busy = true, error = null) }
        try {
            val result = shell.execute(true, "/system/bin/id -u", 30, {}, { lease == generation })
            check(result.uid == 0 && result.exitCode == 0 && !result.timedOut) { "device_root_unavailable" }
            synchronized(selectionLock) {
                check(lease == generation) { "device_access_revoked" }
                preferences.edit { putString("mode", "root") }
                epoch.incrementAndGet()
                mutableState.update { it.copy(mode = DeviceMode.Root, rootGranted = true) }
            }
            // Optional grants are best effort. Unsupported platform/OEM operations cannot block Root mode.
            try {
                val grantLease = generation
                shell.execute(true, grantScript(), 30, {}, {
                    grantLease == generation && mutableState.value.mode == DeviceMode.Root
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Actual state below is authoritative; command success is never treated as a grant.
            }
            if (mutableState.value.mode == DeviceMode.Root) refreshPermissions()
            mutableState.value.mode == DeviceMode.Root && mutableState.value.rootGranted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(selectionLock) {
                if (lease == generation) mutableState.update { it.copy(rootGranted = false, error = "device_root_unavailable") }
            }
            false
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    /** Revokes this application's leases immediately; does not change any Root manager policy. */
    fun useBasic() {
        synchronized(selectionLock) {
            epoch.incrementAndGet()
            mutableState.update { it.copy(mode = DeviceMode.Basic, rootGranted = false, busy = false, error = null) }
            // apply changes memory synchronously; serialized with Root selection to preserve latest intent.
            preferences.edit { putString("mode", "basic") }
        }
        shell.stop()
    }

    /** Validates an already selected Root mode; never requests Root while Basic is selected. */
    suspend fun ensureRoot(): Boolean = mutex.withLock {
        if (!mutableState.value.loaded || mutableState.value.mode != DeviceMode.Root) return@withLock false
        val lease = generation
        try {
            val result = shell.execute(true, "/system/bin/id -u", 20, {}, {
                lease == generation && mutableState.value.mode == DeviceMode.Root
            })
            val valid = result.uid == 0 && result.exitCode == 0 && !result.timedOut && lease == generation &&
                mutableState.value.mode == DeviceMode.Root
            val grants = readGrants()
            synchronized(selectionLock) {
                if (lease == generation) mutableState.update { it.copy(rootGranted = valid,
                    error = if (valid) null else "device_root_unavailable", grants = grants) }
            }
            valid
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(selectionLock) {
                if (lease == generation) mutableState.update { it.copy(rootGranted = false, error = "device_root_unavailable") }
            }
            false
        }
    }

    suspend fun execute(command: String, timeoutSeconds: Int = 120, expectedGeneration: Long? = null,
        onOutput: (String) -> Unit = {}): DeviceShellResult {
        // Approval belongs to the identity generation it observed. Validate and capture the
        // selection atomically, so a revoke/re-enable between caller validation and this call
        // cannot silently grant the old command a fresh identity lease.
        val (selected, lease) = synchronized(selectionLock) {
            val current = mutableState.value
            check(current.loaded) { "device_access_loading" }
            val currentGeneration = generation
            check(expectedGeneration == null || expectedGeneration == currentGeneration) { "device_access_revoked" }
            current.mode to currentGeneration
        }
        try {
            // Each concrete session verifies its real UID before sending this command. Do not
            // open an extra Root probe first: that doubles su handshakes and optional prompts.
            val result = shell.execute(selected == DeviceMode.Root, command, timeoutSeconds, onOutput, {
                lease == generation && selected == mutableState.value.mode
            })
            synchronized(selectionLock) {
                check(lease == generation && selected == mutableState.value.mode) { "device_access_revoked" }
                if (selected == DeviceMode.Root) mutableState.update { it.copy(
                    rootGranted = result.uid == 0,
                    error = if (result.uid == 0) null else "device_root_unavailable",
                ) }
            }
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(selectionLock) {
                if (selected == DeviceMode.Root && lease == generation && mutableState.value.mode == DeviceMode.Root)
                    mutableState.update { it.copy(rootGranted = false, error = "device_root_unavailable") }
            }
            throw error
        }
    }

    fun stop() { shell.stop() }

    suspend fun refreshPermissions() = withContext(Dispatchers.IO) {
        val grants = readGrants()
        mutableState.update { it.copy(grants = grants) }
    }

    private fun readGrants() = DevicePermissionGrants(
        notifications = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
        allFiles = if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
            else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED,
        installPackages = context.packageManager.canRequestPackageInstalls(),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    )

    private fun grantScript(): String {
        // The package name is provided by Android, never by model text or project content.
        val name = "'" + context.packageName.replace("'", "'\\''") + "'"
        return buildString {
            if (Build.VERSION.SDK_INT >= 33) append("pm grant $name android.permission.POST_NOTIFICATIONS\n")
            if (Build.VERSION.SDK_INT >= 30) append("cmd appops set $name MANAGE_EXTERNAL_STORAGE allow\n")
            else append("pm grant $name android.permission.READ_EXTERNAL_STORAGE\npm grant $name android.permission.WRITE_EXTERNAL_STORAGE\n")
            append("cmd appops set $name REQUEST_INSTALL_PACKAGES allow\n")
            append("dumpsys deviceidle whitelist +$name\n")
        }
    }
}
