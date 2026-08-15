package com.keyvoice.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesManagerModelMigrationTest {

    @Test
    fun incompleteGptOssIdIsMigratedToGroqModelId() {
        assertEquals(
            PreferencesManager.MODEL_GPT_OSS_20B,
            PreferencesManager.migrateLlmModel("gpt-oss-20b")
        )
    }

    @Test
    fun deprecatedLlamaModelsAreMigratedToRecommendedReplacements() {
        assertEquals(
            PreferencesManager.MODEL_GPT_OSS_20B,
            PreferencesManager.migrateLlmModel("llama-3.1-8b-instant")
        )
        assertEquals(
            PreferencesManager.MODEL_GPT_OSS_120B,
            PreferencesManager.migrateLlmModel(PreferencesManager.MODEL_LLAMA_70B)
        )
    }

    @Test
    fun unknownDynamicModelIsPreserved() {
        assertEquals(
            "provider/future-model",
            PreferencesManager.migrateLlmModel("provider/future-model")
        )
    }
}
