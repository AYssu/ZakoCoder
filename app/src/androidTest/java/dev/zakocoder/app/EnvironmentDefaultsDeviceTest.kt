package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.settings.AptSource
import dev.zakocoder.app.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID

/** Isolated preferences only: never rewrites the installed Ubuntu or device networking. */
class EnvironmentDefaultsDeviceTest {
    @Test fun newDefaultsAreReadWithoutPersistingOrReplacingSavedChoices() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "environment-defaults-fixture-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            val repository = SettingsRepository(isolated)
            val preferences = base.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertEquals(AptSource.Tsinghua, AppSettings().aptSource)
            assertEquals("223.5.5.5,119.29.29.29", AppSettings().dns)
            assertEquals(AptSource.Tsinghua, repository.load().aptSource)
            assertEquals("223.5.5.5,119.29.29.29", repository.load().dns)
            assertFalse(preferences.contains("apt"))
            assertFalse(preferences.contains("dns"))
            preferences.edit().putString("apt", AptSource.Ubuntu.name).putString("dns", "1.1.1.1,8.8.8.8").commit()
            assertEquals(AptSource.Ubuntu, repository.load().aptSource)
            assertEquals("1.1.1.1,8.8.8.8", repository.load().dns)
        } finally {
            base.deleteSharedPreferences(name)
        }
    }
}
