package dev.zakocoder.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.zakocoder.app.ui.ZakoCoderApp
import dev.zakocoder.app.settings.BackgroundTaskNotifications


class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as ZakoApplication).workbench.activityVisibilityChanged(true)
    }

    override fun onPause() {
        (application as ZakoApplication).workbench.activityVisibilityChanged(false)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val workbench = (application as ZakoApplication).workbench
        openTaskNotification(intent)
        setContent { ZakoCoderApp(workbench) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTaskNotification(intent)
    }

    private fun openTaskNotification(intent: Intent?) {
        if (intent?.action != BackgroundTaskNotifications.ACTION_OPEN_TASK) return
        val project = intent.getStringExtra(BackgroundTaskNotifications.EXTRA_PROJECT_ID) ?: return
        val conversation = intent.getStringExtra(BackgroundTaskNotifications.EXTRA_CONVERSATION_ID) ?: return
        (application as ZakoApplication).workbench.openBackgroundConversation(project, conversation)
        // Configuration recreation must not reopen an already-consumed task notification.
        intent.action = null
        intent.removeExtra(BackgroundTaskNotifications.EXTRA_PROJECT_ID)
        intent.removeExtra(BackgroundTaskNotifications.EXTRA_CONVERSATION_ID)
    }
}
