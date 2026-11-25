package dev.zakocoder.app

import android.app.Application
import dev.zakocoder.app.diagnostics.FatalMarker
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ZakoApplication : Application() {
    private val fatalHandlerInstalled = AtomicBoolean()

    // Create the workbench only when an application consumer needs it.
    private val workbenchDelegate = lazy { Workbench(this) }
    val workbench: Workbench get() = workbenchDelegate.value

    override fun onCreate() {
        super.onCreate()
        if (!fatalHandlerInstalled.compareAndSet(false, true)) return

        val marker = runCatching {
            val directory = File(filesDir, "diagnostics")
            check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
            FatalMarker(directory)
        }.getOrNull() ?: return

        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { marker.write(throwable) }
                // Preserve Android's original crash handling after the synchronous marker write.
                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}
