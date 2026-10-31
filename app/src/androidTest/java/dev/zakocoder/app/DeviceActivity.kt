package dev.zakocoder.app

import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Test-only warm-up for devices that block an app's initial background Activity launch. */
internal fun warmDeviceActivity() {
    check(Looper.myLooper() != Looper.getMainLooper())
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
        "am start -W -n ${instrumentation.targetContext.packageName}/dev.zakocoder.app.MainActivity"
    )).use { stream ->
        val buffer = ByteArray(4096)
        while (stream.read(buffer) >= 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
        }
    }
}
