package dev.zakocoder.app

import android.app.Application

class ZakoApplication : Application() {
    lateinit var workbench: Workbench
        private set
    override fun onCreate() {
        super.onCreate()
        workbench = Workbench(this)
    }
}
