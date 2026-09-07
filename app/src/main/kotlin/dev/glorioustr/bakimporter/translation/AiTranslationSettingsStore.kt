package dev.glorioustr.bakimporter.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiProvider(
    val title: String,
    val endpoint: String,
    val modelsEndpoint: String?,
    val defaultModel: String,
    val suggestedModels: List<String>,
) {
    GOOGLE_AI_STUDIO(
        title = "Google AI Studio",
        endpoint = "https://generativelanguage.googleapis.com/v1beta",
        modelsEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
        defaultModel = "gemini-flash-lite-latest",
        suggestedModels = listOf("gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.8-flash", "gemini-2.5-flash"),
    ),
    OPENAI(
        title = "OpenAI",
        endpoint = "https://api.openai.com/v1/chat/completions",
        modelsEndpoint = "https://api.openai.com/v1/models",
        defaultModel = "gpt-5.6-luna",
        suggestedModels = listOf("gpt-5.6-luna", "gpt-5.4-mini", "gpt-5.4", "gpt-5-mini"),
    ),
    GROQ(
        title = "Groq",
        endpoint = "https://api.groq.com/openai/v1/chat/completions",
        modelsEndpoint = "https://api.groq.com/openai/v1/models",
        defaultModel = "openai/gpt-oss-120b",
        suggestedModels = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile"),
    ),
    DEEPSEEK(
        title = "DeepSeek",
        endpoint = "https://api.deepseek.com/v1/chat/completions",
        modelsEndpoint = "https://api.deepseek.com/v1/models",
        defaultModel = "deepseek-v4-flash",
        suggestedModels = listOf("deepseek-v4-flash", "deepseek-chat", "deepseek-reasoner"),
    ),
    XAI(
        title = "SpaceXAI",
        endpoint = "https://api.x.ai/v1/chat/completions",
        modelsEndpoint = "https://api.x.ai/v1/models",
        defaultModel = "grok-4.1-fast",
        suggestedModels = listOf("grok-4.1-fast"),
    ),
    CEREBRAS(
        title = "Cerebras",
        endpoint = "https://api.cerebras.ai/v1/chat/completions",
        modelsEndpoint = "https://api.cerebras.ai/v1/models",
        defaultModel = "gpt-oss-120b",
        suggestedModels = listOf("gpt-oss-120b"),
    ),
    OLLAMA(
        title = "Ollama",
        endpoint = "https://ollama.com/v1/chat/completions",
        modelsEndpoint = "https://ollama.com/v1/models",
        defaultModel = "gemma4:31b",
        suggestedModels = listOf("gemma4:31b"),
    ),
    OPENROUTER(
        title = "OpenRouter",
        endpoint = "https://openrouter.ai/api/v1/chat/completions",
        modelsEndpoint = "https://openrouter.ai/api/v1/models",
        defaultModel = "openrouter/free",
        suggestedModels = listOf("openrouter/free", "google/gemini-2.5-flash", "openai/gpt-5-mini", "deepseek/deepseek-chat"),
    ),
    VERCEL_AI_GATEWAY(
        title = "Vercel AI Gateway",
        endpoint = "https://ai-gateway.vercel.sh/v1/chat/completions",
        modelsEndpoint = "https://ai-gateway.vercel.sh/v1/models",
        defaultModel = "google/gemma-4-31b-it",
        suggestedModels = listOf("google/gemma-4-31b-it"),
    ),
    GOOGLE_VERTEX(
        title = "Google Vertex",
        endpoint = "https://aiplatform.googleapis.com/v1",
        modelsEndpoint = null,
        defaultModel = "gemini-flash-lite-latest",
        suggestedModels = listOf("gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-2.5-flash"),
    ),
    CUSTOM(
        title = "Özel (OpenAI uyumlu)",
        endpoint = "",
        modelsEndpoint = null,
        defaultModel = "",
        suggestedModels = emptyList(),
    );

    companion object {
        fun fromStored(value: String?, legacyEndpoint: String?): AiProvider {
            value?.let { stored -> entries.firstOrNull { it.name == stored }?.let { return it } }
            return when {
                legacyEndpoint.orEmpty().contains("openai.com", ignoreCase = true) -> OPENAI
                legacyEndpoint.orEmpty().contains("generativelanguage.googleapis.com", ignoreCase = true) -> GOOGLE_AI_STUDIO
                else -> GOOGLE_AI_STUDIO
            }
        }
    }
}

data class AiTranslationSettings(
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.GOOGLE_AI_STUDIO,
    val customEndpoint: String = "",
    val model: String = AiProvider.GOOGLE_AI_STUDIO.defaultModel,
    val apiKey: String = "",
    val systemPrompt: String = "",
    val userPrompt: String = "",
    val useContext: Boolean = true,
) {
    val endpoint: String
        get() = if (provider == AiProvider.CUSTOM) customEndpoint.trim() else provider.endpoint

    val apiKeys: List<String>
        get() = apiKey.split(',').map(String::trim).filter(String::isNotBlank)

    val isReady: Boolean
        get() = enabled && apiKeys.isNotEmpty() && endpoint.startsWith("https://") && model.isNotBlank()
}

/** Stores the user's API key encrypted by an Android Keystore key that never leaves the device. */
class AiTranslationSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AiTranslationSettings {
        val legacyEndpoint = preferences.getString(KEY_ENDPOINT_LEGACY, null)
        val provider = AiProvider.fromStored(preferences.getString(KEY_PROVIDER, null), legacyEndpoint)
        return AiTranslationSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            provider = provider,
            customEndpoint = preferences.getString(KEY_CUSTOM_ENDPOINT, "").orEmpty(),
            model = loadModel(provider),
            apiKey = loadApiKey(provider),
            systemPrompt = preferences.getString(KEY_SYSTEM_PROMPT, preferences.getString(KEY_INSTRUCTIONS_LEGACY, "")).orEmpty(),
            userPrompt = preferences.getString(KEY_USER_PROMPT, "").orEmpty(),
            useContext = preferences.getBoolean(KEY_USE_CONTEXT, true),
        )
    }

    fun save(settings: AiTranslationSettings, newApiKey: String? = null) {
        if (settings.enabled) {
            require(settings.endpoint.startsWith("https://")) { "API adresi HTTPS ile başlamalı" }
            require(settings.model.isNotBlank()) { "Model adı boş olamaz" }
        }
        val editor = preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_PROVIDER, settings.provider.name)
            .putString(KEY_CUSTOM_ENDPOINT, settings.customEndpoint.trim())
            .putString(modelName(settings.provider), settings.model.trim())
            .putString(KEY_SYSTEM_PROMPT, settings.systemPrompt.trim())
            .putString(KEY_USER_PROMPT, settings.userPrompt.trim())
            .putBoolean(KEY_USE_CONTEXT, settings.useContext)
            .remove(KEY_ENDPOINT_LEGACY)
            .remove(KEY_INSTRUCTIONS_LEGACY)
            .remove(KEY_MODEL)
        newApiKey?.trim()?.takeIf(String::isNotBlank)?.let { apiKey ->
            val encrypted = encrypt(apiKey)
            editor.putString(apiKeyDataName(settings.provider), encrypted.data)
            editor.putString(apiKeyIvName(settings.provider), encrypted.iv)
            editor.remove(KEY_API_KEY_LEGACY).remove(KEY_API_IV_LEGACY)
        }
        editor.apply()
    }

    fun clearApiKey(provider: AiProvider = load().provider) {
        preferences.edit()
            .remove(apiKeyDataName(provider))
            .remove(apiKeyIvName(provider))
            .remove(KEY_API_KEY_LEGACY)
            .remove(KEY_API_IV_LEGACY)
            .putBoolean(KEY_ENABLED, false)
            .apply()
    }

    fun loadApiKey(provider: AiProvider): String = decryptStoredKey(provider)

    fun loadModel(provider: AiProvider): String = preferences
        .getString(modelName(provider), preferences.getString(KEY_MODEL, null))
        .orEmpty()
        .ifBlank { provider.defaultModel }

    private fun decryptStoredKey(provider: AiProvider): String {
        val data = preferences.getString(apiKeyDataName(provider), null)
            ?: preferences.getString(KEY_API_KEY_LEGACY, null)
            ?: return ""
        val iv = preferences.getString(apiKeyIvName(provider), null)
            ?: preferences.getString(KEY_API_IV_LEGACY, null)
            ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            preferences.edit().remove(apiKeyDataName(provider)).remove(apiKeyIvName(provider)).apply()
            ""
        }
    }

    private fun apiKeyDataName(provider: AiProvider) = "api_key_${provider.name.lowercase()}_encrypted"
    private fun apiKeyIvName(provider: AiProvider) = "api_key_${provider.name.lowercase()}_iv"
    private fun modelName(provider: AiProvider) = "model_${provider.name.lowercase()}"

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedValue(
            data = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private data class EncryptedValue(val data: String, val iv: String)

    companion object {
        private const val PREFERENCES = "ai_translation_settings"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "bak_importer_translation_api_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_CUSTOM_ENDPOINT = "custom_endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_USER_PROMPT = "user_prompt"
        private const val KEY_USE_CONTEXT = "use_context"
        private const val KEY_ENDPOINT_LEGACY = "endpoint"
        private const val KEY_INSTRUCTIONS_LEGACY = "instructions"
        private const val KEY_API_KEY_LEGACY = "api_key_encrypted"
        private const val KEY_API_IV_LEGACY = "api_key_iv"
    }
}
