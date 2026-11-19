package dev.zakocoder.app

import android.app.Application

class ZakoApplication : Application() {
    // Create the workbench only when an application consumer needs it.
    private val workbenchDelegate = lazy { Workbench(this) }
    val workbench: Workbench get() = workbenchDelegate.value
}
