package dev.zakocoder.app.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Own preferences only: never touches a device user's actual proxy or credentials. */
class ExternalProxySettingsDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "external-proxy-${UUID.randomUUID()}"
    private val preferences = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(
            "$prefix-$name".also { preferences += it }, mode)
    }
    private val repository = SettingsRepository(context)

    @After fun cleanUp() {
        preferences.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        NetworkProxy.configureSocksAuthentication(null)
    }

    private fun markLegacyPort(port: Int) {
        context.getSharedPreferences("proxy-kernel", Context.MODE_PRIVATE).edit().putInt("port", port).commit()
    }

    @Test fun removesOnlyMarkedRetiredBridgeAndItsCredentials() {
        repository.save(AppSettings(proxy = "socks5://fixture:fixture@127.0.0.1:19090"))
        markLegacyPort(19090)
        assertTrue(repository.migrateRemovedKernelProxy())
        assertEquals("", repository.load().proxy)
        assertEquals("", repository.proxyForRuntime())
        assertNull(repository.loadProxyCredentials())
        assertFalse(repository.migrateRemovedKernelProxy())
        assertFalse(context.getSharedPreferences("proxy-kernel", Context.MODE_PRIVATE).contains("port"))
    }

    @Test fun unmarkedManualLoopbackAndCredentialsSurviveAndCanBeDisabled() {
        repository.save(AppSettings(proxy = "socks5://fixture:fixture@127.0.0.1:19090"))
        assertFalse(repository.migrateRemovedKernelProxy())
        assertEquals("socks5://127.0.0.1:19090", repository.load().proxy)
        assertEquals(NetworkProxy.Credentials("fixture", "fixture"), repository.loadProxyCredentials())
        // Saving the visible credential-free endpoint retains its encrypted pair.
        repository.save(repository.load())
        assertEquals("socks5://fixture:fixture@127.0.0.1:19090", repository.proxyForRuntime())
        assertFalse(repository.proxyDisplay().contains(":fixture@"))
        repository.save(repository.load().copy(proxy = ""))
        assertEquals("", repository.proxyForRuntime())
        assertNull(repository.loadProxyCredentials())
    }

    @Test fun staleMarkerDoesNotClearDifferentManualEndpoints() {
        listOf("http://127.0.0.1:19090", "http://127.0.0.1:19091", "socks5://localhost:19090", "socks5://192.0.2.1:19090").forEach { endpoint ->
            repository.save(AppSettings(proxy = endpoint))
            markLegacyPort(19090)
            assertFalse(repository.migrateRemovedKernelProxy())
            assertEquals(endpoint, repository.load().proxy)
        }
    }
}
