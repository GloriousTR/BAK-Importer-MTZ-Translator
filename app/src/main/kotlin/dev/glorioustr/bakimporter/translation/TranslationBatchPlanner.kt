package dev.glorioustr.bakimporter.translation

/** Builds provider-friendly requests while minimizing network round trips. */
internal object TranslationBatchPlanner {
    private const val MAX_BATCH_ITEMS = 80
    private const val MAX_BATCH_CHARS = 20_000

    fun chunk(texts: List<String>): List<List<String>> {
        val result = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var chars = 0
        texts.forEach { text ->
            if (current.isNotEmpty() && (current.size >= MAX_BATCH_ITEMS || chars + text.length > MAX_BATCH_CHARS)) {
                result += current
                current = mutableListOf()
                chars = 0
            }
            current += text
            chars += text.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}
