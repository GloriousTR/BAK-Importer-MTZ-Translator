package dev.glorioustr.bakimporter

import dev.glorioustr.bakimporter.translation.TranslationBatchPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchPlannerTest {
    @Test
    fun `packs typical theme strings into eighty item requests`() {
        val batches = TranslationBatchPlanner.chunk((1..161).map { "Metin $it" })

        assertEquals(listOf(80, 80, 1), batches.map(List<String>::size))
        assertEquals(161, batches.flatten().distinct().size)
    }

    @Test
    fun `respects request character budget without dropping long text`() {
        val texts = listOf("a".repeat(12_000), "b".repeat(9_000), "kısa")
        val batches = TranslationBatchPlanner.chunk(texts)

        assertEquals(texts, batches.flatten())
        assertEquals(2, batches.size)
        assertTrue(batches.all { batch -> batch.size == 1 || batch.sumOf(String::length) <= 20_000 })
    }
}
