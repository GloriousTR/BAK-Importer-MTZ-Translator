package dev.glorioustr.bakimporter

import dev.glorioustr.bakimporter.translation.TranslationIntegrityValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationIntegrityValidatorTest {
    @Test
    fun acceptsNaturalTranslationWhenRuntimeTokensArePreserved() {
        assertTrue(
            TranslationIntegrityValidator.isValid(
                "Found %1\$d items for #user at @string/title",
                "#user için %1\$d öğe bulundu: @string/title",
            )
        )
    }

    @Test
    fun rejectsMissingOrChangedRuntimeTokens() {
        assertFalse(
            TranslationIntegrityValidator.isValid(
                "Hello %s, ${'$'}{name}",
                "Merhaba",
            )
        )
        assertFalse(
            TranslationIntegrityValidator.isValid(
                "Weather: #weather",
                "Hava durumu: #hava",
            )
        )
    }

    @Test
    fun rejectsBlankAndImplausiblyLongOutput() {
        assertFalse(TranslationIntegrityValidator.isValid("设置", ""))
        assertFalse(TranslationIntegrityValidator.isValid("设置", "A".repeat(241)))
    }
}
