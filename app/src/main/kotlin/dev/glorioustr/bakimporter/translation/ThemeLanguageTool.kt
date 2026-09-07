package dev.glorioustr.bakimporter.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Auto-detects visible theme text while keeping curated glossary rules authoritative. */
class ThemeLanguageTool(
    private val professionalTranslator: ProfessionalThemeTranslator? = null,
) {
    data class Result(
        val outputFile: File,
        val translatedNodes: Int,
        val changedFiles: List<String>,
        val skippedFiles: List<String>,
        val unresolvedTexts: List<String>,
        val detectedLanguages: Map<String, Int>,
        val professionalTranslatedTexts: Int = 0,
        val professionalWarnings: List<String> = emptyList(),
    )

    fun translateThemeTextToSystemLanguage(
        source: File,
        output: File,
        locale: Locale = Locale.getDefault(),
        onTranslatedText: ((Int) -> Unit)? = null,
    ): Result {
        require(source.exists() && source.isFile) { "MTZ dosyası bulunamadı" }
        require(source.canonicalPath != output.canonicalPath) { "Çeviri çıktısı kaynak dosyadan farklı olmalı" }

        val target = TranslateLanguage.fromLanguageTag(locale.language) ?: TranslateLanguage.ENGLISH
        output.parentFile?.mkdirs()
        val identifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.45f).build()
        )
        val translators = mutableMapOf<String, Translator>()
        val downloadedModels = hashSetOf<String>()
        val detectedLanguages = linkedMapOf<String, Int>()

        try {
            val professionalResult = professionalTranslator?.let { translator ->
                val candidates = ProfessionalThemeTranslator.collectCandidates(source, target)
                translator.translate(candidates, locale)
            } ?: ProfessionalThemeTranslator.Result(emptyMap(), emptyList())
            val professionalTranslations = professionalResult.translations
            var professionalTranslatedTexts = 0

            fun translateWithModel(input: String, from: String, to: String): String {
                val route = "$from>$to"
                val translator = translators.getOrPut(route) {
                    Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(from)
                            .setTargetLanguage(to)
                            .build()
                    )
                }
                if (downloadedModels.add(route)) {
                    Tasks.await(
                        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()),
                        5,
                        TimeUnit.MINUTES,
                    )
                }
                return Tasks.await(translator.translate(input), 30, TimeUnit.SECONDS).trim()
            }

            var translatedTexts = 0
            val rewrite = ThemeTextLocalizer(
                targetLanguage = target,
                shouldTranslate = TranslationTextFilter::isCandidate,
            ).rewrite(source.toPath(), output.toPath()) { original ->
                val text = original.trim()
                val sourceLanguage: String
                val glossaryTranslation: String?

                if (ThemeGlossary.containsChinese(text)) {
                    // Bypass detection so the proven Chinese glossary and date rules remain authoritative.
                    sourceLanguage = TranslateLanguage.CHINESE
                    glossaryTranslation = ThemeGlossary.resolve(text, target)
                } else {
                    val conversational = ConversationalThemeGlossary.resolve(text, target)
                    if (conversational != null) {
                        sourceLanguage = conversational.sourceLanguage
                        glossaryTranslation = conversational.translation
                    } else {
                        val detected = Tasks.await(identifier.identifyLanguage(text), 30, TimeUnit.SECONDS)
                        if (detected == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) return@rewrite original
                        sourceLanguage = TranslateLanguage.fromLanguageTag(detected)
                            ?: TranslateLanguage.fromLanguageTag(Locale.forLanguageTag(detected).language)
                            ?: return@rewrite original
                        glossaryTranslation = null
                    }
                }

                detectedLanguages[sourceLanguage] = (detectedLanguages[sourceLanguage] ?: 0) + 1
                if (sourceLanguage == target) return@rewrite original

                fun translateChineseClause(clause: String): String {
                    ThemeGlossary.resolve(clause, target)?.let { return it }
                    if (target != TranslateLanguage.TURKISH) {
                        return translateWithModel(clause, sourceLanguage, target)
                    }

                    // Theme prose is clearer through English, but only when the result is complete.
                    // A direct Chinese -> Turkish retry prevents mixed Turkish/Chinese output.
                    val englishSource = ThemeGlossary.prepareChineseForEnglishPivot(clause)
                    val english = translateWithModel(englishSource, sourceLanguage, TranslateLanguage.ENGLISH)
                    val pivot = if (english.isBlank() || ThemeGlossary.containsChinese(english)) {
                        ""
                    } else {
                        translateWithModel(english, TranslateLanguage.ENGLISH, target)
                    }
                    if (pivot.isNotBlank() && !ThemeGlossary.containsChinese(pivot)) return pivot

                    val direct = translateWithModel(clause, sourceLanguage, target)
                    return when {
                        !ThemeGlossary.containsChinese(direct) -> direct
                        pivot.isNotBlank() && chineseCharacterCount(pivot) < chineseCharacterCount(direct) -> pivot
                        else -> direct
                    }
                }

                val professionalTranslation = professionalTranslations[text]
                val translated = glossaryTranslation ?: professionalTranslation ?: if (sourceLanguage == TranslateLanguage.CHINESE) {
                    ChineseTranslationSegmenter.translate(text, target, ::translateChineseClause)
                } else {
                    translateWithModel(text, sourceLanguage, target)
                }
                require(translated.isNotBlank()) { "Çeviri modeli boş sonuç döndürdü" }
                val polished = ThemeGlossary.postProcessTranslation(translated, target)
                val result = original.takeWhile(Char::isWhitespace) + polished + original.takeLastWhile(Char::isWhitespace)
                if (result != original) {
                    if (professionalTranslation != null && glossaryTranslation == null) professionalTranslatedTexts++
                    translatedTexts++
                    onTranslatedText?.invoke(translatedTexts)
                }
                result
            }

            return Result(
                outputFile = output,
                translatedNodes = rewrite.translatedNodes,
                changedFiles = rewrite.changedFiles,
                skippedFiles = rewrite.skippedFiles,
                unresolvedTexts = rewrite.unresolvedTexts,
                detectedLanguages = detectedLanguages.toMap(),
                professionalTranslatedTexts = professionalTranslatedTexts,
                professionalWarnings = professionalResult.warnings,
            )
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            identifier.close()
            translators.values.forEach(Translator::close)
        }
    }

    @Deprecated("Use automatic language detection")
    fun translateChineseTextToSystemLanguage(
        source: File,
        output: File,
        locale: Locale = Locale.getDefault(),
        onTranslatedText: ((Int) -> Unit)? = null,
    ): Result = translateThemeTextToSystemLanguage(source, output, locale, onTranslatedText)

    private fun chineseCharacterCount(text: String): Int = text.count { char ->
        Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN
    }
}
