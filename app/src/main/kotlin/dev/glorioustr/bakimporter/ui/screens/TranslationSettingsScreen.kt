package dev.glorioustr.bakimporter.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.glorioustr.bakimporter.R
import dev.glorioustr.bakimporter.translation.AiProvider
import dev.glorioustr.bakimporter.translation.AiProviderClient
import dev.glorioustr.bakimporter.translation.AiTranslationSettings
import dev.glorioustr.bakimporter.translation.AiTranslationSettingsStore
import dev.glorioustr.bakimporter.translation.ProfessionalThemeTranslator
import dev.glorioustr.bakimporter.ui.theme.HyperOSGreen
import dev.glorioustr.bakimporter.ui.theme.HyperOSOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TranslationSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AiTranslationSettingsStore(context) }
    val initial = remember { store.load() }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var provider by remember { mutableStateOf(initial.provider) }
    var customEndpoint by remember { mutableStateOf(initial.customEndpoint) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf("") }
    var hasStoredKey by remember { mutableStateOf(initial.apiKey.isNotBlank()) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }
    var userPrompt by remember { mutableStateOf(initial.userPrompt) }
    var useContext by remember { mutableStateOf(initial.useContext) }
    var status by remember { mutableStateOf<String?>(null) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var modelDialogOpen by remember { mutableStateOf(false) }
    var modelDraft by remember { mutableStateOf(model) }
    var modelOptions by remember { mutableStateOf(provider.suggestedModels) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelLoadError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun effectiveKey(): String = apiKey.trim().ifBlank { store.loadApiKey(provider) }

    fun currentSettings(forceEnabled: Boolean = enabled): AiTranslationSettings = AiTranslationSettings(
        enabled = forceEnabled,
        provider = provider,
        customEndpoint = customEndpoint,
        model = modelDraft.trim().ifBlank { model.trim() },
        apiKey = effectiveKey(),
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        useContext = useContext,
    )

    fun loadModels() {
        val settings = currentSettings(forceEnabled = true)
        modelOptions = provider.suggestedModels
        modelLoadError = null
        if (settings.apiKeys.isEmpty() && provider != AiProvider.GOOGLE_VERTEX) {
            modelLoadError = context.getString(R.string.ai_model_key_first)
            return
        }
        scope.launch {
            loadingModels = true
            runCatching { withContext(Dispatchers.IO) { AiProviderClient.fetchModels(settings) } }
                .onSuccess { fetched ->
                    modelOptions = (provider.suggestedModels + fetched).distinct()
                    if (modelOptions.isEmpty()) modelLoadError = context.getString(R.string.ai_models_empty)
                }
                .onFailure { modelLoadError = it.message ?: context.getString(R.string.ai_models_load_failed) }
            loadingModels = false
        }
    }

    if (modelDialogOpen) {
        AlertDialog(
            onDismissRequest = { if (!testing) modelDialogOpen = false },
            title = { Text(stringResource(R.string.ai_model_label)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = modelDraft,
                        onValueChange = { modelDraft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        loadingModels -> Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                        modelLoadError != null -> Text(
                            text = modelLoadError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        items(modelOptions) { option ->
                            Text(
                                text = option,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { modelDraft = option }
                                    .padding(vertical = 13.dp, horizontal = 8.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = modelDraft.isNotBlank() && !testing,
                    onClick = {
                        model = modelDraft.trim()
                        modelDialogOpen = false
                    },
                ) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !testing,
                        onClick = {
                            val settings = currentSettings(forceEnabled = true)
                            if (!settings.isReady) {
                                modelLoadError = context.getString(R.string.ai_api_key_required)
                            } else {
                                scope.launch {
                                    testing = true
                                    modelLoadError = null
                                    runCatching {
                                        withContext(Dispatchers.IO) { ProfessionalThemeTranslator(settings).testConnection() }
                                    }.onSuccess { translated ->
                                        modelLoadError = context.getString(R.string.ai_test_success, translated)
                                    }.onFailure { modelLoadError = it.message }
                                    testing = false
                                }
                            }
                        },
                    ) { Text(if (testing) stringResource(R.string.ai_testing) else stringResource(R.string.ai_test)) }
                    TextButton(enabled = !testing, onClick = { modelDialogOpen = false }) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            },
        )
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = HyperOSOrange)
                    Text(
                        text = stringResource(R.string.ai_settings_title),
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ai_settings_description), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ai_optional_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = HyperOSGreen,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.ai_translator_section),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.ai_provider_label),
                        modifier = Modifier.weight(1f).clickable { providerMenuOpen = true },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Box {
                        Text(
                            text = provider.title,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { providerMenuOpen = true }.padding(vertical = 4.dp),
                        )
                        DropdownMenu(
                            expanded = providerMenuOpen,
                            onDismissRequest = { providerMenuOpen = false },
                            modifier = Modifier.widthIn(min = 245.dp).heightIn(max = 420.dp),
                        ) {
                        PROVIDER_ORDER.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.title) },
                                onClick = {
                                    provider = option
                                    model = store.loadModel(option)
                                    modelDraft = model
                                    apiKey = ""
                                    hasStoredKey = store.loadApiKey(option).isNotBlank()
                                    providerMenuOpen = false
                                    status = null
                                },
                            )
                        }
                        }
                    }
                }
                HorizontalDivider()
                SettingsValueRow(
                    label = stringResource(R.string.ai_model_label),
                    value = model,
                    onClick = {
                        modelDraft = model
                        modelDialogOpen = true
                        loadModels()
                    },
                )
                HorizontalDivider()
                if (provider == AiProvider.CUSTOM) {
                    OutlinedTextField(
                        value = customEndpoint,
                        onValueChange = { customEndpoint = it },
                        label = { Text(stringResource(R.string.ai_endpoint_label)) },
                        supportingText = { Text(stringResource(R.string.ai_endpoint_help)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.ai_api_key_label)) },
                    placeholder = { Text(stringResource(if (hasStoredKey) R.string.ai_api_key_saved_placeholder else R.string.ai_api_key_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    supportingText = { Text(stringResource(R.string.ai_api_key_help)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.ai_system_prompt_label)) },
                    placeholder = { Text(stringResource(R.string.ai_default_value)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = userPrompt,
                    onValueChange = { userPrompt = it },
                    label = { Text(stringResource(R.string.ai_user_prompt_label)) },
                    placeholder = { Text(stringResource(R.string.ai_default_value)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_use_context_label), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.ai_use_context_help), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = useContext, onCheckedChange = { useContext = it })
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = HyperOSGreen.copy(alpha = 0.10f)),
        ) {
            Text(stringResource(R.string.ai_privacy_notice), modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                runCatching {
                    require(!enabled || hasStoredKey || apiKey.isNotBlank()) { context.getString(R.string.ai_api_key_required) }
                    model = modelDraft.trim().ifBlank { model }
                    store.save(currentSettings(), newApiKey = apiKey.takeIf(String::isNotBlank))
                }.onSuccess {
                    if (apiKey.isNotBlank()) hasStoredKey = true
                    apiKey = ""
                    status = context.getString(R.string.ai_settings_saved)
                    Toast.makeText(context, R.string.ai_settings_saved, Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    status = error.message
                    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) { Text(stringResource(R.string.ai_settings_save)) }

        if (hasStoredKey) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    store.clearApiKey(provider)
                    hasStoredKey = false
                    enabled = false
                    apiKey = ""
                    status = context.getString(R.string.ai_api_key_removed)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Text(stringResource(R.string.ai_api_key_remove), modifier = Modifier.padding(start = 8.dp))
            }
        }
        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = if (it == context.getString(R.string.ai_settings_saved)) HyperOSGreen else MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value.ifBlank { stringResource(R.string.ai_not_selected) }, color = MaterialTheme.colorScheme.primary)
    }
}

private val PROVIDER_ORDER = listOf(
    AiProvider.CUSTOM,
    AiProvider.OPENAI,
    AiProvider.GOOGLE_AI_STUDIO,
    AiProvider.GOOGLE_VERTEX,
    AiProvider.GROQ,
    AiProvider.DEEPSEEK,
    AiProvider.XAI,
    AiProvider.CEREBRAS,
    AiProvider.OLLAMA,
    AiProvider.OPENROUTER,
    AiProvider.VERCEL_AI_GATEWAY,
)
