package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiBrowserGuidanceTest {
    @Test fun disabledCapabilityGivesAnEnablementPathWithoutAdvertisingAvailableOperations() {
        val prompt = AiController.systemForPackages(browser = false)
        assertTrue(prompt.contains(AiBrowserGuidance.system(false)))
        assertTrue(prompt.contains("sidebar's 能力包"))
        assertTrue(prompt.contains("no browser tool is available"))
        assertFalse(prompt.contains(AiBrowserGuidance.system(true)))
    }

    @Test fun enabledGuidancePrecedesSupplementAndDoesNotTreatStatusOrPrintedUrlsAsNavigation() {
        val baseline = AiController.systemForPackages(browser = true)
        val supplemental = "BROWSER_PROVIDER_RULE_FIXTURE"
        val prompt = AiController.systemForPackages(systemPrompt = supplemental, browser = true)
        assertTrue(prompt.startsWith(baseline))
        assertTrue(prompt.endsWith(supplemental))
        assertTrue(prompt.contains(AiBrowserGuidance.system(true)))
        assertFalse(prompt.contains(AiBrowserGuidance.system(false)))
        assertTrue(prompt.contains("merely printing a URL or calling status does not open"))
        assertTrue(prompt.contains("In Full Access mode"))
        assertTrue(prompt.contains("Other modes still require the app's user confirmation"))
        assertTrue(prompt.contains("only the app's current permission policy grants it"))
        assertTrue(prompt.contains("If the user only asks for an address or written instructions, do not navigate automatically"))
    }
}
