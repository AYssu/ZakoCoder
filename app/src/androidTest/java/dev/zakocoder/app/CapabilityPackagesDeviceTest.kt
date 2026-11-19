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
            assertEquals(setOf(CapabilityPackage.Screen, CapabilityPackage.Ssh), recreated.snapshot("project-b").keys)
            assertEquals(setOf(CapabilityPackage.Screen), recreated.snapshot("project-c").keys)
            assertTrue(recreated.enabled(null, CapabilityPackage.Screen))
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
                assertEquals(CapabilityPackage.Screen, changes.last().second)
            }
            await { !packages.state.value.busy }
            main { packages.setEnabled("project-a", CapabilityPackage.Screen, true) }; await { !packages.state.value.busy }
            assertNotEquals(first, packages.snapshot("project-a")[CapabilityPackage.Screen])
            assertEquals(first != packages.snapshot("project-b")[CapabilityPackage.Screen], true)
            assertEquals(packages.snapshot("project-a")[CapabilityPackage.Screen], packages.snapshot("project-b")[CapabilityPackage.Screen])
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
            assertTrue("Legacy project screen switches must not become global consent", packages.snapshot("project-a").isEmpty())
            assertFalse(packages.enabled(null, CapabilityPackage.Screen))
            assertEquals(setOf(CapabilityPackage.Ssh), packages.snapshot("project-b").keys)
        }
    }

    @Test(timeout = 60000) fun browserIsGlobalWithoutWorkspaceAndItsLeaseRevokesBeforeDiskWrite() = runBlocking {
        isolated { context, _ ->
            val revoked = mutableListOf<CapabilityPackage>()
            lateinit var packages: CapabilityPackages
            main { packages = CapabilityPackages(context) { _, kind -> revoked += kind } }
            await { packages.state.value.loaded }
            assertFalse(packages.enabled(null, CapabilityPackage.Browser))
            main { packages.setBrowserEnabled(true) }; await { !packages.state.value.busy }
            val lease = packages.snapshot("one")[CapabilityPackage.Browser]
            assertNotNull(lease)
            assertTrue(packages.enabled(null, CapabilityPackage.Browser))
            assertEquals(lease, packages.snapshot("two")[CapabilityPackage.Browser])
            lateinit var reloaded: CapabilityPackages
            main { reloaded = CapabilityPackages(context) }; await { reloaded.state.value.loaded }
            assertTrue(reloaded.enabled(null, CapabilityPackage.Browser))
            assertNotEquals(lease, reloaded.snapshot("one")[CapabilityPackage.Browser])
            main {
                packages.setBrowserEnabled(false)
                assertFalse(packages.enabled(null, CapabilityPackage.Browser))
                assertFalse(packages.snapshot("two").containsKey(CapabilityPackage.Browser))
                assertEquals(CapabilityPackage.Browser, revoked.last())
            }
            await { !packages.state.value.busy }
            main { packages.setBrowserEnabled(true) }; await { !packages.state.value.busy }
            assertNotEquals(lease, packages.snapshot("one")[CapabilityPackage.Browser])
            assertFalse(packages.enabled(null, CapabilityPackage.Screen))
            assertFalse(packages.enabled(null, CapabilityPackage.Ssh))
        }
    }

    @Test(timeout = 60000) fun existingVersionTwoScreenAndBrowserConsentNeverEnablesDeviceShell() = runBlocking {
        isolated { context, root ->
            val file = File(root, "capability-packages.json")
            val previous = """{"version":2,"screen_enabled":true,"browser_enabled":true,"projects":{"project-a":["ssh"]}}"""
            withContext(Dispatchers.IO) { file.writeText(previous) }
            lateinit var packages: CapabilityPackages
            main { packages = CapabilityPackages(context) }; await { packages.state.value.loaded }

            assertNull(packages.state.value.error)
            assertTrue(packages.enabled(null, CapabilityPackage.Screen))
            assertTrue(packages.enabled(null, CapabilityPackage.Browser))
            assertTrue(packages.enabled("project-a", CapabilityPackage.Ssh))
            assertFalse(packages.state.value.deviceShellEnabled)
            assertFalse(packages.enabled(null, CapabilityPackage.DeviceShell))
            assertFalse(packages.enabled("project-a", CapabilityPackage.DeviceShell))
            assertFalse(packages.snapshot("project-a").containsKey(CapabilityPackage.DeviceShell))
            assertFalse(packages.snapshot("unconfigured-project").containsKey(CapabilityPackage.DeviceShell))
            assertEquals("Reading an older store must not rewrite its consent", previous,
                withContext(Dispatchers.IO) { file.readText() })
        }
    }

    @Test(timeout = 60000) fun deviceShellIsGlobalAndPersistsWithFreshRevocableLeases() = runBlocking {
        isolated { context, _ ->
            val changes = mutableListOf<CapabilityPackage>()
            lateinit var packages: CapabilityPackages
            main { packages = CapabilityPackages(context) { _, kind -> changes += kind } }
            await { packages.state.value.loaded }
            assertFalse(packages.enabled(null, CapabilityPackage.DeviceShell))

            // No workspace is created or supplied by the global settings operation.
            main { packages.setDeviceShellEnabled(true) }; await { !packages.state.value.busy }
            val originalLease = packages.snapshot("project-a")[CapabilityPackage.DeviceShell]
            assertNotNull(originalLease)
            assertTrue(packages.enabled(null, CapabilityPackage.DeviceShell))
            assertTrue(packages.state.value.enabled.isEmpty())
            assertEquals(originalLease, packages.snapshot("project-b")[CapabilityPackage.DeviceShell])
            assertFalse(packages.enabled(null, CapabilityPackage.Screen))
            assertFalse(packages.enabled(null, CapabilityPackage.Browser))

            lateinit var recreated: CapabilityPackages
            main { recreated = CapabilityPackages(context) { _, kind -> changes += kind } }
            await { recreated.state.value.loaded }
            assertNull(recreated.state.value.error)
            assertTrue(recreated.enabled(null, CapabilityPackage.DeviceShell))
            val recreatedLease = recreated.snapshot("project-a")[CapabilityPackage.DeviceShell]
            assertNotNull(recreatedLease)
            assertNotEquals(originalLease, recreatedLease)
            assertEquals(recreatedLease, recreated.snapshot("project-b")[CapabilityPackage.DeviceShell])

            main {
                recreated.setDeviceShellEnabled(false)
                assertTrue("Persistence has not completed at this assertion", recreated.state.value.busy)
                assertFalse(recreated.state.value.deviceShellEnabled)
                assertFalse(recreated.enabled(null, CapabilityPackage.DeviceShell))
                assertFalse(recreated.snapshot("project-a").containsKey(CapabilityPackage.DeviceShell))
                assertFalse(recreated.snapshot("project-b").containsKey(CapabilityPackage.DeviceShell))
                assertEquals(CapabilityPackage.DeviceShell, changes.last())
            }
            await { !recreated.state.value.busy }
            lateinit var disabled: CapabilityPackages
            main { disabled = CapabilityPackages(context) }; await { disabled.state.value.loaded }
            assertNull(disabled.state.value.error)
            assertFalse(disabled.enabled(null, CapabilityPackage.DeviceShell))
            assertTrue(disabled.snapshot("project-a").isEmpty())

            main { recreated.setDeviceShellEnabled(true) }; await { !recreated.state.value.busy }
            val nextLease = recreated.snapshot("project-a")[CapabilityPackage.DeviceShell]
            assertNotNull(nextLease)
            assertNotEquals(originalLease, nextLease)
            assertNotEquals(recreatedLease, nextLease)
        }
    }
}
