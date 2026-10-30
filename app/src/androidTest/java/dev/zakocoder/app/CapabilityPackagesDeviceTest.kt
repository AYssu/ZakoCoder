package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.capabilities.CapabilityPackages
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated persistence tests; never reads or changes the user's capability selection. */
class CapabilityPackagesDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(10) }
    private suspend fun isolated(block: suspend (android.content.Context, File) -> Unit) {
        val root = File(base.cacheDir, "capability-store-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        try { block(context, root) } finally { withContext(Dispatchers.IO) { root.deleteRecursively() } }
    }

    @Test(timeout = 60000) fun selectedPackagesPersistButLeasesRotateAcrossControllerRecreation() = runBlocking {
        isolated { context, _ ->
            lateinit var first: CapabilityPackages
            main { first = CapabilityPackages(context) }; await { first.state.value.loaded }
            assertTrue(first.snapshot("project-a").isEmpty()); assertTrue(first.snapshot("project-b").isEmpty())
            main { first.setEnabled("project-a", CapabilityPackage.Screen, true) }; await { !first.state.value.busy }
            main { first.setEnabled("project-b", CapabilityPackage.Ssh, true) }; await { !first.state.value.busy }
            val oldScreen = first.snapshot("project-a")[CapabilityPackage.Screen]
            val oldSsh = first.snapshot("project-b")[CapabilityPackage.Ssh]
            lateinit var recreated: CapabilityPackages
            main { recreated = CapabilityPackages(context) }; await { recreated.state.value.loaded }
            assertNull(recreated.state.value.error)
            assertEquals(setOf(CapabilityPackage.Screen), recreated.snapshot("project-a").keys)
            assertEquals(setOf(CapabilityPackage.Ssh), recreated.snapshot("project-b").keys)
            assertTrue(recreated.snapshot("project-c").isEmpty())
            assertNotEquals(oldScreen, recreated.snapshot("project-a")[CapabilityPackage.Screen])
            assertNotEquals(oldSsh, recreated.snapshot("project-b")[CapabilityPackage.Ssh])
        }
    }

    @Test(timeout = 60000) fun disableRevokesImmediatelyAndReenableNeverReusesItsLease() = runBlocking {
        isolated { context, _ ->
            val changes = mutableListOf<Pair<String, CapabilityPackage>>()
            lateinit var packages: CapabilityPackages
            main { packages = CapabilityPackages(context) { id, kind -> changes += id to kind } }; await { packages.state.value.loaded }
            main { packages.setEnabled("project-a", CapabilityPackage.Screen, true) }; await { !packages.state.value.busy }
            val first = packages.snapshot("project-a")[CapabilityPackage.Screen]
            main {
                packages.setEnabled("project-a", CapabilityPackage.Screen, false)
                assertFalse(packages.enabled("project-a", CapabilityPackage.Screen))
                assertTrue(packages.snapshot("project-a").isEmpty())
                assertEquals("project-a" to CapabilityPackage.Screen, changes.last())
            }
            await { !packages.state.value.busy }
            main { packages.setEnabled("project-a", CapabilityPackage.Screen, true) }; await { !packages.state.value.busy }
            assertNotEquals(first, packages.snapshot("project-a")[CapabilityPackage.Screen])
            assertTrue(packages.snapshot("project-b").isEmpty())
        }
    }

    @Test(timeout = 60000) fun corruptStorageFailsClosedWithoutOverwritingItDuringLoad() = runBlocking {
        isolated { context, root ->
            val file = File(root, "capability-packages.json")
            withContext(Dispatchers.IO) { file.writeText("not valid json") }
            lateinit var packages: CapabilityPackages
            main { packages = CapabilityPackages(context) }; await { packages.state.value.loaded }
            assertNotNull(packages.state.value.error)
            assertTrue(packages.snapshot("project-a").isEmpty())
            assertEquals("not valid json", withContext(Dispatchers.IO) { file.readText() })
        }
    }

    @Test(timeout = 60000) fun interruptedAtomicWriteRecoversItsExistingBackup() = runBlocking {
        isolated { context, root ->
            withContext(Dispatchers.IO) {
                File(root, "capability-packages.json.bak").writeText(
                    """{"version":1,"projects":{"project-a":["screen"],"project-b":["ssh"]}}""")
            }
            lateinit var packages: CapabilityPackages
            main { packages = CapabilityPackages(context) }; await { packages.state.value.loaded }
            assertNull(packages.state.value.error)
            assertEquals(setOf(CapabilityPackage.Screen), packages.snapshot("project-a").keys)
            assertEquals(setOf(CapabilityPackage.Ssh), packages.snapshot("project-b").keys)
        }
    }
}
