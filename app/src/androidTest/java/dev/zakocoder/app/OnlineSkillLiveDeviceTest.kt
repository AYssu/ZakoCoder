package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiOnlineSkills
import dev.zakocoder.app.ai.AiSkillArchive
import dev.zakocoder.app.ai.GithubSkillTransport
import okhttp3.OkHttpClient
import java.net.Proxy
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before

/** Explicit opt-in public-network check. Downloads to bounded memory; never installs a skill. */
class OnlineSkillLiveDeviceTest {
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 180000) fun publicRepositoryDiscoveryPinsCommitAndDownloadsOnlySelectedSkill() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("onlineSkillsLive") == "true")
        val proxyPort = InstrumentationRegistry.getArguments().getString("onlineSkillsProxyPort")?.toInt()
        val client = OkHttpClient.Builder().apply {
            if (proxyPort != null) {
                require(proxyPort in 1024..65535)
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
            }
        }.build()
        val service = AiOnlineSkills(GithubSkillTransport(client))
        val catalog = service.discover("https://github.com/anthropics/skills/tree/main/skills/frontend-design")
        assertTrue(catalog.commit.matches(Regex("[0-9a-f]{40}")))
        assertEquals(1, catalog.candidates.size)
        val prepared = service.prepare(catalog, setOf(catalog.candidates.single().directory))
        val skill = AiSkillArchive.parse(prepared.bytes).single()
        assertEquals(listOf("frontend-design"), prepared.names)
        assertTrue(skill.document.contains("name: frontend-design"))
        assertTrue(skill.files.containsKey("ZAKOCODER_ORIGIN.json"))
        assertTrue(skill.files.containsKey("LICENSE.txt"))
        if (catalog.licenseBytes != null) assertTrue(skill.files.containsKey("UPSTREAM_LICENSE.txt"))
    }
}
