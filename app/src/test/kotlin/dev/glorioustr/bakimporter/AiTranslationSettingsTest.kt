package dev.glorioustr.bakimporter

import dev.glorioustr.bakimporter.translation.AiProvider
import dev.glorioustr.bakimporter.translation.AiTranslationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranslationSettingsTest {
    @Test
    fun `comma separated keys are trimmed and empty entries ignored`() {
        val settings = AiTranslationSettings(apiKey = " first, ,second ")

        assertEquals(listOf("first", "second"), settings.apiKeys)
    }

    @Test
    fun `provider endpoint is selected automatically`() {
        val settings = AiTranslationSettings(provider = AiProvider.GOOGLE_AI_STUDIO)

        assertEquals("https://generativelanguage.googleapis.com/v1beta", settings.endpoint)
    }

    @Test
    fun `professional translator readiness requires opt in key and model`() {
        assertFalse(AiTranslationSettings(apiKey = "key").isReady)
        assertTrue(AiTranslationSettings(enabled = true, apiKey = "key").isReady)
        assertFalse(AiTranslationSettings(enabled = true, apiKey = "key", model = "").isReady)
    }
}
