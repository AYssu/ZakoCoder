package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.mcp.McpController
import dev.zakocoder.app.mcp.McpServer
import dev.zakocoder.app.mcp.McpTransport
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.startMcpProcess
import dev.zakocoder.app.settings.AppSettings
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Requires an already installed Ubuntu/Python; never installs packages or uses personal configs. */
class McpRuntimeDeviceTest {
    @Test(timeout = 90000) fun realGuestStdioKeepsStructuredArgumentsEnvironmentAndOwnedLifetime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LinuxRuntime(context)
        assertTrue("Installed Ubuntu required for this device validation", withContext(Dispatchers.IO) { runtime.isInstalled() })
        val id = UUID.randomUUID().toString()
        val project = File(runtime.workspaces, id)
        val storage = File(context.cacheDir, "mcp-runtime-$id")
        val alias = "mcp-runtime-$id"
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = storage }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var owned: Process? = null
        withContext(Dispatchers.IO) {
            check(project.mkdirs()); check(storage.mkdirs())
            File(project, "fixture.py").writeText("""
                import json, os, sys
                print('fixture stderr is separate', file=sys.stderr, flush=True)
                for line in sys.stdin:
                    request = json.loads(line)
                    if 'id' not in request: continue
                    if request['method'] == 'initialize':
                        result = {'protocolVersion':'2025-06-18', 'capabilities':{'tools':{}}}
                    else:
                        result = {'content':[{'type':'text','text':json.dumps({'cwd':os.getcwd(),'argumentMatches':sys.argv[1] == "literal ; $(false) ' argument",'envPresent':os.environ.get('FIXTURE_ENV') == 'fixture environment','params':request['params']})}]}
                    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}), flush=True)
            """.trimIndent())
        }
        val controller = McpController(isolated, scope, { projectId, server ->
            assertEquals(id, projectId)
            runtime.startMcpProcess(context, projectId, AppSettings(), server).also { owned = it }
        }, keyAlias = alias)
        try {
            withTimeout(10000) { while (controller.state.value.loading || controller.state.value.busy) delay(20) }
            val server = McpServer(name = "isolated guest fixture", transport = McpTransport.Stdio,
                command = "python3", args = listOf("-u", "fixture.py", "literal ; $(false) ' argument"),
                env = mapOf("FIXTURE_ENV" to "fixture environment"), enabled = true)
            controller.save(server).join(); assertEquals("", controller.state.value.error)
            val params = JSONObject().put("name", "fixture").put("arguments", JSONObject().put("nested", JSONObject().put("number", 42)))
            val response = controller.request(id, server.id, "tools/call", params)
            val observed = JSONObject(response.getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals("/workspace/$id", observed.getString("cwd"))
            assertTrue(observed.getBoolean("argumentMatches"))
            assertTrue(observed.getBoolean("envPresent"))
            assertEquals(42, observed.getJSONObject("params").getJSONObject("arguments").getJSONObject("nested").getInt("number"))
            assertTrue(owned!!.isAlive)
            controller.closeTask(id)
            withTimeout(5000) { while (owned!!.isAlive) delay(20) }
        } finally {
            controller.closeAll().join(); scope.cancel()
            withContext(Dispatchers.IO) {
                owned?.destroyForcibly()
                project.deleteRecursively(); storage.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
            }
        }
    }
}
