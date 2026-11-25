package dev.zakocoder.app.ai

import org.junit.Assert.assertThrows
import org.junit.Test

class AiConfigStoreOriginTest {
    @Test fun resolveRejectsReplacementKeyAcrossOrigin() {
        val draft = AiModelDraft(null, "name", "https://new.example.invalid/v1", "model", "replacement",
            replacementKeyOrigin = "https://old.example.invalid/v1")

        assertThrows(AiOriginChanged::class.java) {
            AiConfigStore.requireReplacementKeyOrigin(
                draft.replacementKey,
                draft.replacementKeyOrigin,
                AiConfigStore.endpoint(draft.endpoint),
            )
        }
    }

    @Test fun resolveAllowsReplacementKeyOnSameOrigin() {
        val draft = AiModelDraft(null, "name", "https://api.example.invalid/v2", "model", "replacement",
            replacementKeyOrigin = "https://API.example.invalid/v1")

        AiConfigStore.requireReplacementKeyOrigin(
            draft.replacementKey,
            draft.replacementKeyOrigin,
            AiConfigStore.endpoint(draft.endpoint),
        )
    }
}
