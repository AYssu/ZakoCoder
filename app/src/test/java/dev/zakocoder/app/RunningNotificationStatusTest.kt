package dev.zakocoder.app

import dev.zakocoder.app.settings.RunningNotificationStatus
import dev.zakocoder.app.settings.runningNotificationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RunningNotificationStatusTest {
    @Test fun permissionAppChannelAndGroupMustAllAllowDelivery() {
        for (permission in listOf(false, true)) for (app in listOf(false, true))
            for (channel in listOf(false, true)) for (group in listOf(false, true)) {
                val expected = if (!permission) RunningNotificationStatus.PermissionRequired
                    else if (!app) RunningNotificationStatus.AppBlocked
                    else if (!channel || !group) RunningNotificationStatus.ChannelBlocked
                    else RunningNotificationStatus.Available
                assertEquals("permission=$permission app=$app channel=$channel group=$group", expected,
                    runningNotificationStatus(permission, app, channel, group))
            }
    }
}
