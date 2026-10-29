package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.projects.*
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.settings.AppSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real device acceptance: preinstalled managed Android toolchain, no personal project or model data. */
class AndroidBuildDeviceTest {
    @Test(timeout = 2400000) fun javaAndKotlinProduceDebugApksInsideUbuntuAndInstallerGetsOnlyCacheUri() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings(proxy = InstrumentationRegistry.getArguments().getString("androidBuildProxy").orEmpty())
        val runtime = LinuxRuntime(context)
        val repository = ProjectRepository(context, runtime.workspaces)
        val logDir = File(context.cacheDir, "android-build-validation")
        withContext(Dispatchers.IO) { logDir.mkdirs() }
        for (template in listOf(ProjectTemplate.AndroidJava, ProjectTemplate.AndroidKotlin)) {
            val id = UUID.randomUUID().toString()
            val root = File(runtime.workspaces, id)
            try {
                withContext(Dispatchers.IO) {
                    check(root.mkdirs())
                    ProjectTemplateWriter.write(root, ProjectTemplates.files(template, "Zako Android ${template.name}"))
                    File(root, "app/build.gradle.kts").appendText("""

                        tasks.register("verifyManagedAapt") {
                            doLast {
                                check(providers.gradleProperty("android.aapt2FromMavenOverride").get() == "${dev.zakocoder.app.runtime.AndroidToolchain.SDK}/build-tools/36.0.0/aapt2")
                            }
                        }
                    """.trimIndent())
                }
                val result = runtime.execute("gradle --no-daemon :app:verifyManagedAapt :app:assembleDebug --stacktrace --console=plain", id,
                    settings, timeoutMillis = 19 * 60 * 1000L, output = { chunk -> File(logDir, "${template.name}.log").appendText(chunk) })
                withContext(Dispatchers.IO) { File(logDir, "${template.name}.log").writeText(result.output) }
                assertEquals(result.output.takeLast(5000), 0, result.exitCode)
                assertTrue(result.output.contains("BUILD SUCCESSFUL"))
                val relative = "app/build/outputs/apk/debug/app-debug.apk"
                assertTrue(withContext(Dispatchers.IO) { File(root, relative).length() > 0 })
                val intent = ProjectApkInstaller(context, repository).prepare(id, relative)
                assertEquals("content", intent.data!!.scheme)
                assertEquals("${context.packageName}.apk-files", intent.data!!.authority)
                assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals(0x50, it.read()); assertEquals(0x4b, it.read()) }
                    File(logDir, "${template.name}.apk").apply { File(root, relative).copyTo(this, overwrite = true) }
                }
            } finally { withContext(NonCancellable + Dispatchers.IO) { dev.zakocoder.app.runtime.PrivateFiles.deleteTree(root) } }
        }
    }
}
