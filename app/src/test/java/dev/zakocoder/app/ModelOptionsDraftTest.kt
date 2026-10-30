package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ModelOptionsDraft
import org.junit.Assert.*
import org.junit.Test

class ModelOptionsDraftTest {
    @Test fun defaultDraftRetainsHeadersWithoutReturningValues() {
        val profile = AiProfile("fixture", "Fixture", "https://example.invalid", "model", true, AiParameters(), headerNames = listOf("X-Api-Key"))
        val draft = ModelOptionsDraft(profile)
        assertTrue(draft.valid()); assertFalse(draft.dirty(profile)); assertNull(draft.headers())
        draft.replaceHeaders = true
        assertEquals(emptyMap<String, String>(), draft.headers()); assertTrue(draft.dirty(profile))
        draft.headersText = "X-Api-Key: test:with:colons\nHTTP-Referer: https://example.invalid"
        assertTrue(draft.valid()); assertEquals("test:with:colons", draft.headers()!!["X-Api-Key"])
        draft.headersText = "X-Api-Key: one\nx-api-key: two"
        assertFalse(draft.valid())
    }
    @Test fun invalidInputsAreNotSilentlyChangedIntoProviderDefaults() {
        val draft = ModelOptionsDraft(null)
        draft.frequency = "NaN"; assertFalse(draft.valid())
        draft.frequency = ""; draft.seed = "1.5"; assertFalse(draft.valid())
        draft.seed = ""; draft.contextChars = "100"; assertFalse(draft.valid())
        draft.contextChars = "96000"; assertTrue(draft.valid())
    }
}
