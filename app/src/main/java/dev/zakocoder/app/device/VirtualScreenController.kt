package dev.zakocoder.app.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.ipc.RootService
import dev.zakocoder.app.R
import dev.zakocoder.app.screentest.ScreenTestBackend
import dev.zakocoder.app.screentest.ScreenTestException
import dev.zakocoder.app.screentest.ScreenTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns one Root display, its Binder lease, preview buffers and permission generation. */
class VirtualScreenController(context: Context, private val access: DeviceAccessController) : ScreenTestBackend {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val epoch = AtomicLong()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(VirtualScreenState())
    val state: StateFlow<VirtualScreenState> = mutableState.asStateFlow()
    @Volatile private var remote: IVirtualScreen? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var accessLease = -1L
    private val owner = Binder()
    @Volatile private var disconnectJob: Job? = null

    init {
        scope.launch {
            access.state.collect { permissions ->
                if (accessLease >= 0 && (permissions.mode != DeviceMode.Root || accessLease != access.generation || !permissions.rootGranted)) stop()
            }
        }
    }

    suspend fun start(width: Int = 720, height: Int = 1600, densityDpi: Int = 320) {
        withContext(Dispatchers.IO) {
            operations.withLock {
                validateVirtualScreenSize(width, height, densityDpi)
                check(Build.VERSION.SDK_INT >= 30) { "virtual_display_android_11_required" }
                check(access.state.value.mode == DeviceMode.Root && access.ensureRoot()) { context.getString(R.string.virtual_screen_root_required) }
                val permissionGeneration = access.generation
                val requested = epoch.incrementAndGet()
                detach().join()
                synchronized(stateLock) {
                    check(epoch.get() == requested && access.generation == permissionGeneration && access.state.value.mode == DeviceMode.Root && access.state.value.rootGranted) { "virtual_display_permission_revoked" }
                    accessLease = permissionGeneration
                    mutableState.value = VirtualScreenState(busy = true, width = width, height = height, densityDpi = densityDpi)
                }
                try {
                    val service = bind(requested)
                    checkCurrent(requested)
                    val result = JSONObject(service.create(width, height, densityDpi, owner))
                    val id = requireVirtualDisplayId(result.getInt("display_id"))
                    synchronized(stateLock) {
                        checkCurrent(requested)
                        mutableState.value = mutableState.value.copy(active = true, busy = false, displayId = id, error = null)
                    }
                } catch (cancelled: CancellationException) { stop(); throw cancelled }
                catch (error: Exception) {
                    synchronized(stateLock) {
                        if (epoch.get() == requested) { detach(); mutableState.value = mutableState.value.copy(active = false, busy = false, displayId = null, error = failure(error)) }
                    }
                    throw ScreenTestException(failure(error), error)
                }
            }
        }
    }

    suspend fun launch(packageName: String) = operation { service, requested ->
        service.launch(packageName)
        synchronized(stateLock) {
            checkCurrent(requested)
            mutableState.value = mutableState.value.copy(packageName = packageName)
        }
        // Launch request acceptance does not prove that the requested app is on this display.
        val deadline = android.os.SystemClock.elapsedRealtime() + 8000
        var last: Exception? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            checkCurrent(requested)
            try {
                service.execute(packageName, JSONObject().put("action", "observe").toString())
                return@operation
            } catch (error: Exception) { last = error }
            delay(200)
        }
        throw ScreenTestException("virtual_display_launch_not_verified", last)
    }

    /** A private PNG for the visible preview, never an implicit model screenshot permission. */
    suspend fun capture(): ByteArray = operation { service, requested -> readCapture(service, requested) }

    override suspend fun execute(packageName: String, args: JSONObject): ScreenTestResult {
        if (args.optString("action") == "list_apps") {
            // Discovery belongs to the current task's display lease; it never creates or
            // reopens a display, and changing Root identity discards the query result.
            return operation { _, requested ->
                val page = selectLaunchableDeviceApps(launchableDeviceApps(context), args.optString("text"))
                currentCoroutineContext().ensureActive()
                checkCurrent(requested)
                ScreenTestResult(launchableDeviceAppsResponse(page, checkNotNull(state.value.displayId)), packageName = state.value.packageName)
            }
        }
        if (args.optString("action") == "launch") {
            val target = args.optString("package", packageName).ifBlank { args.optString("package_name") }
            require(target.isNotBlank())
            if (!state.value.active) start()
            launch(target)
            return ScreenTestResult(JSONObject().put("launched", target).put("display_id", state.value.displayId).put("verified", true).toString(), packageName = target)
        }
        return operation { service, requested ->
            if (args.optString("action") == "screenshot") {
                val bytes = readCapture(service, requested)
                val directory = File(context.filesDir, "screen-test/evidence").apply { check(mkdirs() || isDirectory) }
                // Bounded cache, not a permanent recording. Existing FileProvider scope is reused.
                directory.listFiles { file -> file.name.startsWith("virtual-") && file.extension == "png" }
                    ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach(File::delete)
                val file = File(directory, "virtual-${UUID.randomUUID()}.png")
                try {
                    file.outputStream().use { it.write(bytes) }
                    checkCurrent(requested)
                    ScreenTestResult(JSONObject().put("captured", true).put("display_id", state.value.displayId).toString(),
                        FileProvider.getUriForFile(context, "${context.packageName}.apk-files", file), state.value.packageName)
                } catch (error: Exception) { file.delete(); throw error }
            } else {
                val output = service.execute(packageName, args.toString())
                checkCurrent(requested)
                ScreenTestResult(output, packageName = state.value.packageName)
            }
        }
    }

    override fun stop() {
        synchronized(stateLock) {
            epoch.incrementAndGet()
            accessLease = -1
            mutableState.value = mutableState.value.copy(active = false, busy = false, displayId = null, packageName = null)
            detach()
        }
    }

    fun close() { stop(); disconnectJob?.invokeOnCompletion { scope.cancel() } ?: scope.cancel() }

    /** Display disappearance precedes automation disconnect; owners must await both on disposal. */
    internal suspend fun awaitStopped() { disconnectJob?.join() }

    private suspend fun <T> operation(block: suspend (IVirtualScreen, Long) -> T): T = withContext(Dispatchers.IO) {
        operations.withLock {
            val requested = epoch.get()
            val service = synchronized(stateLock) {
                checkCurrent(requested)
                val current = remote ?: throw ScreenTestException("virtual_display_not_started")
                mutableState.value = mutableState.value.copy(busy = true, error = null)
                current
            }
            try {
                val result = block(service, requested)
                checkCurrent(requested)
                result
            } catch (cancelled: CancellationException) { stop(); throw cancelled }
            catch (error: Exception) {
                synchronized(stateLock) { if (requested == epoch.get()) mutableState.value = mutableState.value.copy(error = failure(error)) }
                throw ScreenTestException(failure(error), error)
            } finally {
                synchronized(stateLock) { if (requested == epoch.get()) mutableState.value = mutableState.value.copy(busy = false) }
            }
        }
    }

    private suspend fun readCapture(service: IVirtualScreen, requested: Long): ByteArray {
        val descriptor = service.capture()
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    checkCurrent(requested)
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 12_582_912) { "virtual_display_capture_too_large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().also { check(it.isNotEmpty()) { "virtual_display_capture_failed" } }
            }
        } finally { descriptor.close() }
    }

    private suspend fun bind(requested: Long): IVirtualScreen = withTimeout(30_000) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        val service = IVirtualScreen.Stub.asInterface(binder)
                        try {
                            val accepted = synchronized(stateLock) {
                                if (requested != epoch.get() || connection !== this || !continuation.isActive) false
                                else {
                                    checkCurrent(requested)
                                    binder.linkToDeath({
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            failed(ScreenTestException("virtual_display_service_disconnected"))
                                        }
                                    }, 0)
                                    remote = service
                                    continuation.resume(service)
                                    true
                                }
                            }
                            if (!accepted) runCatching { RootService.unbind(this) }
                        } catch (error: Exception) { failed(error) }
                    }
                    private fun failed(error: Exception) {
                        synchronized(stateLock) {
                            if (requested == epoch.get() && connection === this) {
                                stop()
                                mutableState.value = mutableState.value.copy(error = failure(error))
                            }
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                        runCatching { RootService.unbind(this) }
                    }
                    override fun onServiceDisconnected(name: ComponentName) = failed(ScreenTestException("virtual_display_service_disconnected"))
                    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                    override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                }
                continuation.invokeOnCancellation {
                    synchronized(stateLock) { if (requested == epoch.get() && connection === listener) stop() }
                    android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { RootService.unbind(listener) } }
                }
                try {
                    synchronized(stateLock) {
                        checkCurrent(requested)
                        check(continuation.isActive) { "virtual_display_permission_revoked" }
                        connection = listener
                        RootService.bind(Intent(context, VirtualScreenRootService::class.java), listener)
                    }
                }
                catch (error: Exception) {
                    runCatching { RootService.unbind(listener) }
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }

    private fun detach(): Job = synchronized(stateLock) {
        val old = remote; remote = null
        val previous = connection; connection = null
        val preceding = disconnectJob
        scope.launch {
            preceding?.join()
            runCatching { old?.cancel() }
            runCatching { old?.close() }
            withContext(Dispatchers.Main.immediate) { previous?.let { runCatching { RootService.unbind(it) } } }
        }.also { disconnectJob = it }
    }

    private fun checkCurrent(requested: Long) {
        check(requested == epoch.get() && accessLease == access.generation && access.state.value.mode == DeviceMode.Root && access.state.value.rootGranted) { "virtual_display_permission_revoked" }
    }

    private fun failure(error: Exception): String {
        val code = generateSequence(error as Throwable?) { it.cause }.mapNotNull { it.message }
            .firstOrNull { it.matches(Regex("virtual_display_[a-z0-9_]+")) } ?: "virtual_display_backend_unavailable"
        return context.getString(R.string.virtual_screen_failure, code)
    }
}
