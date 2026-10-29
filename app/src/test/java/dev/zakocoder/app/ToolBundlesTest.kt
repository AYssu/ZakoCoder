package dev.zakocoder.app

import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ToolBundlesTest {
    @Test fun bundlesCoverKnownToolsAndHaveNoDuplicateIds() {
        assertEquals(ToolBundles.catalog.size, ToolBundles.catalog.map { it.id }.distinct().size)
        ToolBundles.catalog.forEach { bundle ->
            assertTrue(bundle.tools.isNotEmpty())
            assertTrue(bundle.tools.all { id -> DevelopmentTools.catalog.any { it.id == id } })
            if (!bundle.manual) assertTrue(ToolBundles.packages(setOf(bundle.id), emptyList()).isNotEmpty())
        }
    }
    @Test fun selectionWithoutProjectCreatesOnlySelectedFixedPackages() {
        val node = ToolBundles.catalog.single { it.id == "node" }
        assertFalse(ToolBundles.recommended(node, emptySet()))
        assertTrue(ToolBundles.recommended(node, setOf(ProjectKind.Node)))
        assertEquals(listOf("ca-certificates", "curl", "nodejs", "npm"), ToolBundles.packages(setOf("node"), emptyList()))
        val partial = listOf(ToolCheck("node", ToolStatus.Ready, "v18"), ToolCheck("npm", ToolStatus.Missing, "-"))
        assertEquals(BundleStatus.Missing, ToolBundles.status(node, partial))
        assertEquals(listOf("ca-certificates", "curl", "npm"), ToolBundles.packages(setOf("node"), partial))
        val ready = partial.map { it.copy(status = ToolStatus.Ready) }
        assertEquals(BundleStatus.Ready, ToolBundles.status(node, ready))
        assertTrue(ToolBundles.packages(setOf("node"), ready).isEmpty())
        assertEquals(BundleStatus.Unknown, ToolBundles.status(node, emptyList()))
        assertEquals(BundleStatus.Broken, ToolBundles.status(node, partial.map { it.copy(status = ToolStatus.Broken) }))
    }
    @Test fun manualBundlesAndUntrustedIdsCannotProduceCommands() {
        assertThrows(IllegalArgumentException::class.java) { ToolBundles.packages(setOf("flutter"), emptyList()) }
        assertThrows(NoSuchElementException::class.java) { ToolBundles.packages(setOf("node; touch /tmp/injected"), emptyList()) }
    }
}
