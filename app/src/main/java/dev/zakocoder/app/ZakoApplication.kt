package dev.zakocoder.app

import android.app.Application

class ZakoApplication : Application() {
    // Android creates Application in :kernel too. Only UI/main-process consumers need Workbench;
    // constructing it in the service process would rerun startup recovery against shared state.
    private val workbenchDelegate = lazy { Workbench(this) }
    val workbench: Workbench get() = workbenchDelegate.value
}
