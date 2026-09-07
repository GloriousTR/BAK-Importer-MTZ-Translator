package dev.glorioustr.bakimporter.translation

/**
 * Breaks long Chinese UI copy at semantic punctuation before it reaches the on-device model.
 * ML Kit becomes markedly less literal on theme descriptions and help text when each clause is
 * translated independently. Separators are kept outside the model so lists and line breaks survive.
 */
internal object ChineseTranslationSegmenter {
    fun translate(
        text: String,
        targetLanguage: String,
        translateClause: (String) -> String,
    ): String {
        ThemeGlossary.resolve(text, targetLanguage)?.let { return it }
        if (!ThemeGlossary.containsChinese(text)) return text

        val output = StringBuilder(text.length)
        val clause = StringBuilder()

        fun flush() {
            if (clause.isEmpty()) return
            val value = clause.toString()
            clause.setLength(0)
            val leading = value.takeWhile(Char::isWhitespace)
            val trailing = value.takeLastWhile(Char::isWhitespace)
            val coreEnd = value.length - trailing.length
            val core = value.substring(leading.length, coreEnd)
            output.append(leading)
            output.append(
                if (ThemeGlossary.containsChinese(core)) {
                    ThemeGlossary.resolve(core, targetLanguage) ?: translateClause(core)
                } else {
                    core
                },
            )
            output.append(trailing)
        }

        text.forEach { char ->
            val separator = localizedSeparator(char)
            if (separator == null) {
                clause.append(char)
            } else {
                flush()
                output.append(separator)
            }
        }
        flush()
        return output.toString().trim()
    }

    private fun localizedSeparator(char: Char): String? = when (char) {
        '\r' -> ""
        '\n' -> "\n"
        '。' -> ". "
        '！' -> "! "
        '？' -> "? "
        '；' -> "; "
        '，' -> ", "
        '：' -> ": "
        '丨' -> " | "
        '【' -> "["
        '】' -> "]"
        '（' -> " ("
        '）' -> ")"
        else -> null
    }
}
