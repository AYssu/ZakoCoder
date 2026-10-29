package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

/** Small startup matrix used when changing native runtime artifacts. */
class RuntimeCompatibilityTest {
    @Test fun startupMatrix() = runBlocking(Dispatchers.IO) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZakoApplication
        val workbench = app.workbench
        withTimeout(10000) { while (workbench.state.value.loading) delay(50) }
        val report = StringBuilder()
        for (seccomp in listOf("1", "0", "unset")) {
            for (canonical in listOf(false, true)) {
                val root = if (canonical) workbench.runtime.root.canonicalFile else workbench.runtime.root
                val cmd = workbench.runtime.command(null, workbench.state.value.settings,
                    "printf 'CWD='; /bin/pwd; id; python3 -c 'import os; print(\"UIDS\", os.getuid(), os.geteuid()); os.setresuid(0,0,0); print(\"SETUID_OK\")'", root)
                val builder = ProcessBuilder(listOf(cmd.executable) + cmd.arguments).redirectErrorStream(true)
                builder.environment().clear(); builder.environment().putAll(cmd.environment)
                if (seccomp == "unset") builder.environment().remove("PROOT_NO_SECCOMP") else builder.environment()["PROOT_NO_SECCOMP"] = seccomp
                val process = builder.start()
                val output = process.inputStream.bufferedReader().readText()
                val code = process.waitFor()
                if (seccomp == "unset") {
                    assertEquals(output, 0, code)
                    assertTrue(output, output.contains("CWD=/root"))
                    assertFalse(output, output.contains("error retrieving current directory"))
                }
                report.append("SECCOMP=$seccomp CANONICAL=$canonical EXIT=$code\n$output\n")
            }
        }
        android.util.Log.i("RuntimeMatrix", report.toString())
        assertTrue(report.toString(), report.contains("SETUID_OK"))
    }
}
