package dev.zakocoder.app

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.*
import dev.zakocoder.app.settings.DevelopmentNotifications
import dev.zakocoder.app.settings.RunningNotificationStatus
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RunningNotificationsDeviceTest {
    @Test(timeout = 90000) fun realTerminalKeepsOwnedForegroundNotificationUntilClosed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = (context.applicationContext as ZakoApplication).workbench
        val manager = context.getSystemService(NotificationManager::class.java)
        suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(50) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !w.state.value.loading && !w.state.value.busy }
            val previous = w.state.value.selectedId
            val originalSessions = w.state.value.sessions.map { it.id }.toSet()
            var owned: String? = null; var terminal: String? = null
            try {
                val name = "notification-owned-${UUID.randomUUID()}"
                withContext(Dispatchers.Main) { w.create(name) }
                await { !w.state.value.busy && w.state.value.project?.name == name }
                val id = w.state.value.selectedId!!; owned = id
                withContext(Dispatchers.Main) { w.startTerminal(id) }
                await { !w.state.value.busy && w.state.value.sessions.any { it.projectId == id } }
                val item = w.state.value.sessions.single { it.projectId == id }; terminal = item.id
                await { manager.activeNotifications.any { it.id == 7 } }
                val notification = manager.activeNotifications.single { it.id == 7 }.notification
                assertEquals(DevelopmentNotifications.CHANNEL, notification.channelId)
                assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
                assertNotNull(notification.contentIntent)
                assertTrue(notification.actions.any { it.title == context.getString(R.string.stop_all) })
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); delay(500)
                assertTrue(item.session.isRunning); assertTrue(manager.activeNotifications.any { it.id == 7 })
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                withContext(Dispatchers.Main) { w.closeTerminal(item.id) }; terminal = null
                await { w.state.value.sessions.none { it.id == item.id } }
                if (originalSessions.isEmpty()) await { manager.activeNotifications.none { it.id == 7 } }
                assertEquals(originalSessions, w.state.value.sessions.map { it.id }.toSet())
            } finally {
                terminal?.let { withContext(Dispatchers.Main) { w.closeTerminal(it) } }
                owned?.let { id -> withContext(Dispatchers.Main) { w.removeProject(id) }; await { !w.state.value.busy && w.state.value.projects.none { it.id == id } } }
                previous?.let { id -> withContext(Dispatchers.Main) { w.select(id) }; await { !w.state.value.busy } }
            }
        }
    }
    @Test fun queryDoesNotCreateChannelAndBlockedChannelCannotBeReportedAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = "owned-status-${UUID.randomUUID()}"
        val probe = DevelopmentNotifications(context, id)
        val before = probe.status()
        assertNull(manager.getNotificationChannel(id))
        try {
            manager.createNotificationChannel(NotificationChannel(id, "Owned device verification", NotificationManager.IMPORTANCE_NONE))
            val blocked = probe.status()
            assertNotEquals(RunningNotificationStatus.Available, blocked)
            assertNotEquals(RunningNotificationStatus.Unknown, blocked)
            if (before == RunningNotificationStatus.Available) assertEquals(RunningNotificationStatus.ChannelBlocked, blocked)
            else assertEquals(before, blocked)
            assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(id).importance)
        } finally { manager.deleteNotificationChannel(id) }
        assertNull(manager.getNotificationChannel(id))
    }
}
