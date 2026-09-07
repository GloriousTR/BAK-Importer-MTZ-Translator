package dev.glorioustr.bakimporter.translation

import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Context-aware BYOK translator. Requests go directly from the user's device to their provider. */
class ProfessionalThemeTranslator(
    private val settings: AiTranslationSettings,
    private val memory: TranslationMemory? = null,
) {
    data class Result(
        val translations: Map<String, String>,
        val warnings: List<String>,
    )

    private data class BatchResult(
        val translations: Map<String, String>,
        val warnings: List<String>,
    )

    fun translate(candidates: Collection<String>, targetLocale: Locale): Result {
        if (!settings.isReady) return Result(emptyMap(), emptyList())
        val unique = candidates.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (unique.isEmpty()) return Result(emptyMap(), emptyList())

        val engineId = "${settings.provider}|${settings.endpoint}|${settings.model}|${settings.systemPrompt.hashCode()}|${settings.userPrompt.hashCode()}|${settings.useContext}"
        val targetLanguage = targetLocale.toLanguageTag()
        val translated = linkedMapOf<String, String>()
        translated.putAll(memory?.get(unique, targetLanguage, engineId).orEmpty())
        val warnings = mutableListOf<String>()
        val missing = unique.filterNot(translated::containsKey)
        val batches = TranslationBatchPlanner.chunk(missing)
        val accepted = linkedMapOf<String, String>()
        if (batches.isNotEmpty()) {
            val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_REQUESTS, batches.size))
            try {
                val futures = batches.mapIndexed { index, texts ->
                    executor.submit(Callable { index to requestValidatedBatch(texts, targetLocale, index + 1) })
                }
                futures.forEach { future ->
                    val (_, result) = future.get()
                    accepted.putAll(result.translations)
                    warnings += result.warnings
                }
            } finally {
                executor.shutdownNow()
            }
        }
        translated.putAll(accepted)
        memory?.put(accepted, targetLanguage, engineId)
        return Result(translated, warnings.distinct())
    }

    /**
     * A provider may occasionally omit one item or return malformed JSON for a large request.
     * Keep every valid result, then retry only the unresolved part in progressively smaller
     * groups. This is both faster than falling back for the whole batch and more resilient to
     * provider-specific output limits.
     */
    private fun requestValidatedBatch(
        texts: List<String>,
        targetLocale: Locale,
        group: Int,
        splitDepth: Int = 0,
    ): BatchResult {
        val response = runCatching { requestBatch(texts, targetLocale) }
        if (response.isFailure) {
            if (texts.size > MIN_RETRY_BATCH_ITEMS && splitDepth < MAX_RETRY_SPLIT_DEPTH) {
                val midpoint = texts.size / 2
                return merge(
                    requestValidatedBatch(texts.subList(0, midpoint), targetLocale, group, splitDepth + 1),
                    requestValidatedBatch(texts.subList(midpoint, texts.size), targetLocale, group, splitDepth + 1),
                )
            }
            return BatchResult(
                emptyMap(),
                listOf("$group. gelişmiş API çeviri grubundaki ${texts.size} metin kullanılamadı: ${response.exceptionOrNull()?.message.orEmpty()}"),
            )
        }

        val raw = response.getOrThrow()
        val valid = linkedMapOf<String, String>()
        texts.forEach { original ->
            raw[original]?.trim()?.takeIf { TranslationIntegrityValidator.isValid(original, it) }?.let {
                valid[original] = it
            }
        }
        val unresolved = texts.filterNot(valid::containsKey)
        if (unresolved.isEmpty()) return BatchResult(valid, emptyList())
        if (unresolved.size == 1) {
            if (texts.size > 1) {
                return merge(
                    BatchResult(valid, emptyList()),
                    requestValidatedBatch(unresolved, targetLocale, group, splitDepth + 1),
                )
            }
            return BatchResult(valid, listOf("$group. gruptaki bir metin doğrulanamadı; cihaz içi motora bırakıldı"))
        }

        val midpoint = unresolved.size / 2
        return merge(
            BatchResult(valid, emptyList()),
            requestValidatedBatch(unresolved.subList(0, midpoint), targetLocale, group, splitDepth + 1),
            requestValidatedBatch(unresolved.subList(midpoint, unresolved.size), targetLocale, group, splitDepth + 1),
        )
    }

    private fun merge(vararg results: BatchResult): BatchResult = BatchResult(
        translations = buildMap { results.forEach { putAll(it.translations) } },
        warnings = results.flatMap { it.warnings },
    )

    private fun requestBatch(texts: List<String>, targetLocale: Locale): Map<String, String> {
        val indexed = texts.mapIndexed { index, text -> index.toString() to text }
        val inputArray = JSONArray().apply {
            indexed.forEach { (id, text) ->
                put(JSONObject().put("id", id).put("text", text))
            }
        }
        val targetName = targetLocale.getDisplayName(Locale.ENGLISH).ifBlank { targetLocale.language }
        val systemPrompt = buildString {
            append("You are a senior software localization editor translating a Xiaomi/HyperOS theme UI into ")
            append(targetName)
            append(". Translate naturally for a phone interface, ")
            if (settings.useContext) append("infer meaning from all sibling strings in this batch, ")
            else append("translate each item independently, ")
            append("prefer concise labels that fit compact widgets, and use consistent terminology. ")
            append("Never translate or alter variables, format specifiers, resource references, XML/MAML expressions, numbers, or punctuation structure. ")
            append("Return every id exactly once. Do not add commentary.")
            settings.systemPrompt.takeIf(String::isNotBlank)?.let {
                append(" Additional system instructions: ")
                append(it)
            }
        }
        val userPrompt = buildString {
            settings.userPrompt.takeIf(String::isNotBlank)?.let {
                append(it)
                append("\n\n")
            }
            append(JSONObject().put("target_language", targetName).put("strings", inputArray).toString())
        }
        val usesGeminiNative = settings.provider == AiProvider.GOOGLE_AI_STUDIO || settings.provider == AiProvider.GOOGLE_VERTEX
        val endpoint = if (usesGeminiNative) geminiEndpoint() else chatEndpoint(settings.endpoint)
        val request = if (usesGeminiNative) geminiRequest(systemPrompt, userPrompt) else chatCompletionRequest(systemPrompt, userPrompt)
        val response = postJson(endpoint, request, usesGeminiNative)
        val outputText = extractOutputText(response)
        val byId = parseTranslations(outputText, indexed)
        require(byId.isNotEmpty()) { "API geçerli bir çeviri döndürmedi" }
        return indexed.mapNotNull { (id, original) -> byId[id]?.let { original to it } }.toMap()
    }

    private fun parseTranslations(outputText: String, indexed: List<Pair<String, String>>): Map<String, String> {
        val clean = stripCodeFence(outputText)
        val byId = linkedMapOf<String, String>()

        fun readArray(array: JSONArray) {
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is JSONObject -> {
                        val id = value.optString("id", index.toString())
                        val text = value.optString("text").ifBlank { value.optString("translation") }
                        if (text.isNotBlank()) byId[id] = text
                    }
                    is String -> byId[index.toString()] = value
                }
            }
        }

        runCatching {
            if (clean.startsWith("[")) {
                readArray(JSONArray(clean))
            } else {
                val root = JSONObject(clean)
                root.optJSONArray("translations")?.let(::readArray)
                root.optJSONObject("translations")?.let { translations ->
                    translations.keys().forEach { id ->
                        translations.optString(id).takeIf(String::isNotBlank)?.let { byId[id] = it }
                    }
                }
                indexed.forEach { (id, original) ->
                    root.optString(id).takeIf(String::isNotBlank)?.let { byId[id] = it }
                    root.optString(original).takeIf(String::isNotBlank)?.let { byId[id] = it }
                }
                if (indexed.size == 1 && byId.isEmpty()) {
                    listOf("translation", "translated_text", "text", "output").firstNotNullOfOrNull { key ->
                        root.optString(key).takeIf(String::isNotBlank)
                    }?.let { byId[indexed.single().first] = it }
                }
            }
        }
        if (indexed.size == 1 && byId.isEmpty() && clean.isNotBlank() && !clean.startsWith("{") && !clean.startsWith("[")) {
            byId[indexed.single().first] = clean
        }
        return byId
    }

    private fun responsesRequest(systemPrompt: String, userPrompt: String): JSONObject = JSONObject()
        .put("model", settings.model.trim())
        .put(
            "input",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPrompt)),
        )
        .put("text", JSONObject().put("format", schemaFormat()))

    private fun chatCompletionRequest(systemPrompt: String, userPrompt: String): JSONObject = JSONObject()
        .put("model", settings.model.trim())
        .put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPrompt)),
        )

    private fun geminiRequest(systemPrompt: String, userPrompt: String): JSONObject = JSONObject()
        .put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
        )
        .put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userPrompt))),
            ),
        )
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

    private fun schemaFormat(): JSONObject = JSONObject()
        .put("type", "json_schema")
        .put("name", "theme_translations")
        .put("strict", true)
        .put("schema", translationSchema())

    private fun translationSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "translations",
                JSONObject()
                    .put("type", "array")
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("id", JSONObject().put("type", "string"))
                                    .put("text", JSONObject().put("type", "string")),
                            )
                            .put("required", JSONArray().put("id").put("text"))
                            .put("additionalProperties", false),
                    ),
            ),
        )
        .put("required", JSONArray().put("translations"))
        .put("additionalProperties", false)

    private fun postJson(endpoint: String, body: JSONObject, geminiAuth: Boolean = false): JSONObject {
        require(endpoint.startsWith("https://")) { "API adresi HTTPS olmalı" }
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 120_000
                    connection.doOutput = true
                    if (geminiAuth) connection.setRequestProperty("x-goog-api-key", nextApiKey())
                    else connection.setRequestProperty("Authorization", "Bearer ${nextApiKey()}")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status in 200..299) return JSONObject(responseText)
                    val message = runCatching {
                        JSONObject(responseText).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty().ifBlank { "HTTP $status" }
                    if (status !in RETRYABLE_STATUS) error(message)
                    lastError = IllegalStateException(message)
                } finally {
                    connection.disconnect()
                }
            } catch (error: Throwable) {
                lastError = error
            }
            if (attempt == 0) Thread.sleep(1_000)
        }
        throw IllegalStateException(lastError?.message ?: "API bağlantısı kurulamadı", lastError)
    }

    private fun extractOutputText(response: JSONObject): String {
        response.optString("output_text").takeIf(String::isNotBlank)?.let { return it }
        response.optJSONArray("output")?.let { output ->
            for (i in 0 until output.length()) {
                val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val item = content.optJSONObject(j) ?: continue
                    if (item.optString("type") == "output_text") return item.getString("text")
                }
            }
        }
        response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        response.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.let { parts ->
                val text = buildString {
                    for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index) ?: continue
                        if (!part.optBoolean("thought", false)) append(part.optString("text"))
                    }
                }
                if (text.isNotBlank()) return text
            }
        error("API yanıtında çeviri metni bulunamadı")
    }

    private fun chatEndpoint(endpoint: String): String {
        val clean = endpoint.trim().trimEnd('/')
        return if (clean.endsWith("/chat/completions", ignoreCase = true)) {
            clean
        } else if (clean.endsWith("/responses", ignoreCase = true)) {
            clean.removeSuffix("/responses") + "/chat/completions"
        } else {
            "$clean/chat/completions"
        }
    }

    private fun geminiEndpoint(): String {
        val base = settings.endpoint.trim().trimEnd('/')
        val model = settings.model.trim().removePrefix("models/").removePrefix("google/")
        val modelPath = if (settings.provider == AiProvider.GOOGLE_VERTEX) {
            "publishers/google/models/$model"
        } else {
            "models/$model"
        }
        return "$base/$modelPath:generateContent"
    }

    fun testConnection(targetLocale: Locale = Locale("tr")): String {
        val result = requestBatch(listOf("Settings"), targetLocale)["Settings"].orEmpty()
        require(result.isNotBlank()) { "Model boş yanıt döndürdü" }
        return result
    }

    private fun nextApiKey(): String {
        val keys = settings.apiKeys
        require(keys.isNotEmpty()) { "API anahtarı eksik" }
        return keys[Math.floorMod(keyIndex.getAndIncrement(), keys.size)]
    }

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.substringAfter('\n').substringBeforeLast("```").trim()
    }

    companion object {
        private const val MAX_PARALLEL_REQUESTS = 3
        private const val MIN_RETRY_BATCH_ITEMS = 8
        private const val MAX_RETRY_SPLIT_DEPTH = 3
        private val RETRYABLE_STATUS = setOf(408, 409, 429, 500, 502, 503, 504)
        private val keyIndex = AtomicInteger(0)
        fun fromSettings(context: Context, settings: AiTranslationSettings): ProfessionalThemeTranslator? =
            settings.takeIf(AiTranslationSettings::isReady)?.let {
                ProfessionalThemeTranslator(it, TranslationMemory(context))
            }

        fun collectCandidates(source: File, targetLanguage: String): Set<String> {
            return ThemeTextLocalizer(
                targetLanguage = targetLanguage,
                shouldTranslate = TranslationTextFilter::isCandidate,
            ).collectCandidates(source.toPath())
        }
    }
}
