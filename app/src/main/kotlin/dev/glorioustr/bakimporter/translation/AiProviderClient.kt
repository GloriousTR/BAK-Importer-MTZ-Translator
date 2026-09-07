package dev.glorioustr.bakimporter.translation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AiProviderClient {
    fun fetchModels(settings: AiTranslationSettings): List<String> {
        if (settings.provider == AiProvider.GOOGLE_VERTEX) return settings.provider.suggestedModels
        val endpoint = settings.provider.modelsEndpoint ?: inferModelsEndpoint(settings.endpoint)
        val key = settings.apiKeys.firstOrNull() ?: error("Önce API anahtarını girin")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            if (settings.provider == AiProvider.GOOGLE_AI_STUDIO) {
                connection.setRequestProperty("x-goog-api-key", key)
            } else {
                connection.setRequestProperty("Authorization", "Bearer $key")
            }
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { "HTTP $status" }
                error(message)
            }
            val root = JSONObject(responseText)
            val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
            return buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index)
                    val id = item?.optString("id").orEmpty().ifBlank { item?.optString("name").orEmpty() }
                    id.removePrefix("models/").takeIf(String::isNotBlank)?.let(::add)
                }
            }.distinct().sorted()
        } finally {
            connection.disconnect()
        }
    }

    private fun inferModelsEndpoint(endpoint: String): String {
        val base = endpoint
            .replace(Regex("/(chat/completions|responses)/?$", RegexOption.IGNORE_CASE), "")
            .trimEnd('/')
        require(base.startsWith("https://")) { "Özel API adresi HTTPS ile başlamalı" }
        return "$base/models"
    }
}
