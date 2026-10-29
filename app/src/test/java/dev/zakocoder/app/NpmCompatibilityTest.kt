package dev.zakocoder.app

import dev.zakocoder.app.runtime.*
import org.junit.Assert.*
import org.junit.Test

class NpmCompatibilityTest {
    @Test fun npm9CannotMakeTheNodeBundleReadyButCompatibleNpmCan() {
        val tools = DevelopmentTools.catalog.filter { it.id in setOf("node", "npm") }
        fun checks(version: String) = DevelopmentTools.parse("ZAKO_TOOL\tnode\tReady\tv18.19.1\nZAKO_TOOL\tnpm\tReady\t$version", tools)
        val bundle = ToolBundles.catalog.single { it.id == "node" }
        assertEquals(BundleStatus.Broken, ToolBundles.status(bundle, checks("9.2.0")))
        assertTrue("npm" in ToolBundles.packages(setOf("node"), checks("9.2.0")))
        assertEquals(BundleStatus.Ready, ToolBundles.status(bundle, checks("10.9.4")))
        assertTrue(ToolBundles.packages(setOf("node"), checks("10.9.4")).isEmpty())
        assertFalse(NpmCompatibility.supported("unexpected output"))
    }
}
