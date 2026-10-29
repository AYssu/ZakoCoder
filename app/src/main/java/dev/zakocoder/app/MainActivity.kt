package dev.zakocoder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.zakocoder.app.ui.ZakoCoderApp


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val workbench = (application as ZakoApplication).workbench
        setContent { ZakoCoderApp(workbench) }
    }
}
