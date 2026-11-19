package dev.zakocoder.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchableDeviceAppsTest {
    @Test fun discoveryIsBoundedAndCanBeNarrowedByLabelOrPackage() {
        val apps = (1..130).map { LaunchableDeviceApp("dev.fixture.app$it", "Application $it") }
        val all = selectLaunchableDeviceApps(apps, "")
        assertEquals(100, all.apps.size)
        assertEquals(130, all.matchedCount)
        assertTrue(all.truncated)
        val label = selectLaunchableDeviceApps(apps, "APPLICATION 130")
        assertEquals(listOf(apps.last()), label.apps)
        assertFalse(label.truncated)
        assertEquals(listOf(apps.last()), selectLaunchableDeviceApps(apps, "dev.fixture.app130").apps)
    }

    @Test fun duplicatesAndLongUntrustedLabelsDoNotBreakTheResponseBound() {
        val page = selectLaunchableDeviceApps(listOf(
            LaunchableDeviceApp("dev.fixture.b", "\n\t"),
            LaunchableDeviceApp("dev.fixture.a", "x".repeat(2000)),
            LaunchableDeviceApp("dev.fixture.a", "zDuplicate"),
        ), "")
        assertEquals(2, page.matchedCount)
        assertTrue(page.apps.all { it.label.length <= 160 && it.label.none(Char::isISOControl) })
        assertEquals("dev.fixture.b", page.apps.single { it.packageName == "dev.fixture.b" }.label)
        assertEquals(1, selectLaunchableDeviceApps(page.apps, "", 1).apps.size)
        assertEquals(160, page.apps.single { it.packageName == "dev.fixture.a" }.label.length)
    }

    @Test fun aLabelLimitNeverSplitsAnEmojiSurrogatePair() {
        val beforeBoundary = "x".repeat(159)
        assertEquals(beforeBoundary, boundedDeviceAppLabel(beforeBoundary + "🐟"))
        assertEquals("x".repeat(158) + "🐟", boundedDeviceAppLabel("x".repeat(158) + "🐟long"))
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedFilterIsRejected() {
        selectLaunchableDeviceApps(emptyList(), "x".repeat(201))
    }

    @Test(expected = IllegalArgumentException::class) fun controlCharactersAreRejected() {
        selectLaunchableDeviceApps(emptyList(), "app\u0000")
    }
}
