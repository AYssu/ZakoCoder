package dev.zakocoder.app.kernel

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** No network, node data or real plugin process; exercise Android binding failure contracts. */
class KernelPluginBindingDeviceTest {
    private class BindingContext(
        base: Context,
        private val behavior: (ServiceConnection) -> Boolean,
    ) : ContextWrapper(base) {
        @Volatile var unbinds = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean = behavior(connection)
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }

    private fun checkFailure(expected: KernelBindingFailure, behavior: (ServiceConnection) -> Boolean) = runBlocking {
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext, behavior)
        val manager = KernelPluginManager(context)
        val result = withContext(Dispatchers.IO) { manager.bind(timeoutMillis = 25) }
        assertNull(result)
        assertEquals(expected, manager.bindFailure.value)
        val report = manager.diagnostics()
        assertTrue(report.contains("binding_failure=${expected.name}"))
        assertFalse(report.contains("sensitive.exception.example"))
        manager.unbind()
        context
    }

    @Test fun refusedBindingHasStableCode() {
        val context = checkFailure(KernelBindingFailure.Rejected) { false }
        assertEquals(1, context.unbinds)
    }

    @Test fun securityExceptionDoesNotLeakItsMessage() {
        val context = checkFailure(KernelBindingFailure.SecurityDenied) { throw SecurityException("sensitive.exception.example") }
        assertEquals(1, context.unbinds)
    }

    @Test fun missingCallbackTimesOutAndUnbinds() {
        val context = checkFailure(KernelBindingFailure.Timeout) { true }
        assertEquals(1, context.unbinds)
    }

    @Test fun nullBindingIsDifferentFromTimeoutAndUnbinds() {
        val context = checkFailure(KernelBindingFailure.NullBinding) {
            it.onNullBinding(ComponentName("test", "test.Service"))
            true
        }
        assertEquals(1, context.unbinds)
    }

    @Test fun bindingDeathIsDifferentFromTimeout() {
        checkFailure(KernelBindingFailure.BindingDied) {
            it.onBindingDied(ComponentName("test", "test.Service"))
            true
        }
    }

    private fun localService(snapshot: String = "{}"): IKernelService = object : IKernelService.Stub() {
        override fun start(config: String?, port: Int, user: String?, password: String?, id: String?, name: String?): String = "{}"
        override fun stop() = Unit
        override fun status(): String = snapshot
        override fun contractVersion(): Int = 1
    }

    @Test fun runtimeDiagnosticsWhitelistRemoteFields() = runBlocking {
        val service = localService("""{"state":"running","processAlive":true,"exitCode":null,"errorCategories":"reality_auth_failed,secret-token@example.com","secret":"private-node"}""")
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext) {
            it.onServiceConnected(ComponentName("test", "test.Service"), service.asBinder())
            true
        }
        val manager = KernelPluginManager(context)
        try {
            assertNotNull(withContext(Dispatchers.IO) { manager.bind(500) })
            val report = withContext(Dispatchers.IO) { manager.diagnostics() }
            assertTrue(report.contains("kernel_state=running"))
            assertTrue(report.contains("kernel_process_alive=true"))
            assertTrue(report.contains("kernel_error_categories=reality_auth_failed"))
            assertFalse(report.contains("secret-token"))
            assertFalse(report.contains("private-node"))
        } finally { manager.unbind() }
    }

    @Test fun timeoutIgnoresLateCallbacksAndRepeatedUnbind() = runBlocking {
        lateinit var callback: ServiceConnection
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext) { callback = it; true }
        val manager = KernelPluginManager(context)
        assertNull(withContext(Dispatchers.IO) { manager.bind(25) })
        callback.onServiceConnected(ComponentName("test", "test.Service"), localService().asBinder())
        callback.onBindingDied(ComponentName("test", "test.Service"))
        manager.unbind()
        manager.unbind()
        assertEquals(KernelBindingFailure.Timeout, manager.bindFailure.value)
        assertEquals(1, context.unbinds)
    }

    @Test fun previousBindingCannotDisconnectReplacement() = runBlocking {
        val callbacks = mutableListOf<ServiceConnection>()
        val service = localService()
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext) {
            callbacks += it
            it.onServiceConnected(ComponentName("test", "test.Service"), service.asBinder())
            true
        }
        val manager = KernelPluginManager(context)
        assertNotNull(withContext(Dispatchers.IO) { manager.bind(500) })
        manager.unbind()
        assertNotNull(withContext(Dispatchers.IO) { manager.bind(500) })
        callbacks.first().onServiceDisconnected(ComponentName("test", "test.Service"))
        callbacks.first().onBindingDied(ComponentName("test", "test.Service"))
        assertTrue(manager.isCurrentService(service))
        assertNull(manager.bindFailure.value)
        assertEquals(1, context.unbinds)
        manager.unbind()
        manager.unbind()
        assertEquals(2, context.unbinds)
    }

    @Test fun unbindReleasesWaitingWorker() {
        val entered = CountDownLatch(1)
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext) { entered.countDown(); true }
        val manager = KernelPluginManager(context)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<IKernelService?> { manager.bind(10_000) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            manager.unbind()
            assertNull(result.get(2, TimeUnit.SECONDS))
            manager.unbind()
            assertEquals(1, context.unbinds)
            assertNull(manager.bindFailure.value)
        } finally {
            manager.unbind()
            worker.shutdownNow()
        }
    }

    @Test fun interruptedWaitReleasesConnectionAndKeepsInterruptFlag() {
        val entered = CountDownLatch(1)
        val result = AtomicReference<IKernelService?>()
        val preserved = AtomicReference(false)
        val context = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext) { entered.countDown(); true }
        val manager = KernelPluginManager(context)
        val worker = Thread {
            result.set(manager.bind(10_000))
            preserved.set(Thread.currentThread().isInterrupted)
        }
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertNull(result.get())
            assertTrue(preserved.get())
            assertEquals(KernelBindingFailure.Interrupted, manager.bindFailure.value)
            assertEquals(1, context.unbinds)
        } finally {
            manager.unbind()
            worker.interrupt()
        }
    }
    @Test fun delayedOldDeathCallbackRetainsItsOwnerGeneration() = runBlocking {
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val callbacks = mutableListOf<ServiceConnection>()
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            var releases = 0
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                callbacks += connection
                connection.onServiceConnected(ComponentName("test", "test.Service"), localService().asBinder())
                return true
            }
            override fun unbindService(connection: ServiceConnection) {
                releases++
                if (releases == 1) {
                    releaseEntered.countDown()
                    check(allowRelease.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val manager = KernelPluginManager(context)
        val currentGeneration = java.util.concurrent.atomic.AtomicInteger(1)
        val invalidations = java.util.concurrent.atomic.AtomicInteger(0)
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertNotNull(withContext(Dispatchers.IO) {
                manager.bind(500, onLost = {
                    if (currentGeneration.compareAndSet(1, -1)) invalidations.incrementAndGet()
                })
            })
            val lost = worker.submit { callbacks.first().onBindingDied(ComponentName("test", "test.Service")) }
            assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))
            currentGeneration.set(2)
            val replacement = withContext(Dispatchers.IO) {
                manager.bind(500, onLost = {
                    if (currentGeneration.compareAndSet(2, -1)) invalidations.incrementAndGet()
                })
            }
            assertNotNull(replacement)
            allowRelease.countDown()
            lost.get(2, TimeUnit.SECONDS)
            assertEquals(2, currentGeneration.get())
            assertEquals(0, invalidations.get())
            assertTrue(manager.isCurrentService(requireNotNull(replacement)))
            callbacks.last().onBindingDied(ComponentName("test", "test.Service"))
            assertEquals(-1, currentGeneration.get())
            assertEquals(1, invalidations.get())
        } finally {
            allowRelease.countDown()
            manager.unbind()
            worker.shutdownNow()
        }
    }

}
