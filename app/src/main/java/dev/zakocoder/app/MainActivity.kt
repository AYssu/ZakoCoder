package dev.zakocoder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.zakocoder.app.ui.ZakoCoderApp


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
        setContent { ZakoCoderApp(workbench) }
    }
}
