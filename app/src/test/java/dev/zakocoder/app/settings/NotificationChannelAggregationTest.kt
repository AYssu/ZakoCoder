package dev.zakocoder.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationChannelAggregationTest {
    private val running = NotificationChannelAvailability("development", true, true)
    private val result = NotificationChannelAvailability("assistant-results", true, true)

    @Test fun unopenedChannelsDoNotPreventNotificationsOrSelectAChannelSettingsPage() {
        assertEquals(RunningNotificationStatus.Available, runningNotificationStatus(true, true, emptyList()))
        assertNull(notificationSettingsChannel(null, emptyList()))
        assertNull(notificationSettingsChannel("uncreated", emptyList()))
    }

    @Test fun bothExistingChannelsMustAllowDelivery() {
        for (runningEnabled in listOf(true, false)) for (resultEnabled in listOf(true, false)) {
            val channels = listOf(running.copy(channelEnabled = runningEnabled), result.copy(channelEnabled = resultEnabled))
            assertEquals(if (runningEnabled && resultEnabled) RunningNotificationStatus.Available else RunningNotificationStatus.ChannelBlocked,
                runningNotificationStatus(true, true, channels))
        }
    }

    @Test fun blockedCompletionChannelIsReportedAndIsTheSettingsDestination() {
        val channels = listOf(running, result.copy(channelEnabled = false))
        assertEquals(RunningNotificationStatus.ChannelBlocked, runningNotificationStatus(true, true, channels))
        assertEquals(result.id, notificationSettingsChannel(null, channels))
    }

    @Test fun channelGroupBlockingHasTheSameEffectAsChannelBlocking() {
        val channels = listOf(running, result.copy(groupEnabled = false))
        assertEquals(RunningNotificationStatus.ChannelBlocked, runningNotificationStatus(true, true, channels))
        assertEquals(result.id, notificationSettingsChannel(null, channels))
    }

    @Test fun permissionAndApplicationBlockingTakePriorityOverChannels() {
        val channels = listOf(running.copy(channelEnabled = false), result.copy(groupEnabled = false))
        assertEquals(RunningNotificationStatus.PermissionRequired, runningNotificationStatus(false, false, channels))
        assertEquals(RunningNotificationStatus.AppBlocked, runningNotificationStatus(true, false, channels))
    }

    @Test fun explicitChannelSelectionKeepsItsOwnSettingsDestination() {
        val channels = listOf(running, result.copy(channelEnabled = false))
        assertEquals(running.id, notificationSettingsChannel(running.id, channels))
        assertEquals(RunningNotificationStatus.Available, runningNotificationStatus(true, true, channels.filter { it.id == running.id }))
        assertEquals(RunningNotificationStatus.ChannelBlocked, runningNotificationStatus(true, true, channels.filter { it.id == result.id }))
    }

    @Test fun healthyAggregateOpensApplicationSettingsToExposeBothChannels() {
        assertNull(notificationSettingsChannel(null, listOf(running, result)))
    }
}
