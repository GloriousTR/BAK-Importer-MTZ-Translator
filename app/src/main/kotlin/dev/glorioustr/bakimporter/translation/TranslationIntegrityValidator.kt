package dev.glorioustr.bakimporter.translation

/** Rejects AI output that drops or mutates runtime placeholders and MAML references. */
internal object TranslationIntegrityValidator {
    fun isValid(original: String, translated: String): Boolean {
        if (translated.isBlank()) return false
        if (translated.length > maxOf(240, original.length * 5)) return false
        return protectedTokens(original) == protectedTokens(translated)
    }

    internal fun protectedTokens(text: String): List<String> = PROTECTED_TOKEN
        .findAll(text)
        .map { it.value }
        .sorted()
        .toList()

    private val PROTECTED_TOKEN = Regex(
        "%(?:\\d+\\$)?[a-zA-Z]|#[A-Za-z0-9_]+|@[A-Za-z0-9_./-]+|\\$\\{[^}]+\\}|\\{[A-Za-z0-9_]+\\}|\\\\[ntr]"
    )
}
