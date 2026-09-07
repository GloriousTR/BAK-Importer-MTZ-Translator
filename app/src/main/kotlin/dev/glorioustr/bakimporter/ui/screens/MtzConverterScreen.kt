package dev.glorioustr.bakimporter.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import dev.glorioustr.bakimporter.R
import dev.glorioustr.bakimporter.backup.MtzToBakConverter
import dev.glorioustr.bakimporter.diagnostics.LiveDiagnosticsRecorder
import dev.glorioustr.bakimporter.translation.AiTranslationSettingsStore
import dev.glorioustr.bakimporter.translation.ProfessionalThemeTranslator
import dev.glorioustr.bakimporter.translation.ThemeLanguageTool
import dev.glorioustr.bakimporter.ui.theme.HyperOSBlue
import dev.glorioustr.bakimporter.ui.theme.HyperOSGreen
import dev.glorioustr.bakimporter.ui.theme.HyperOSOrange
import dev.glorioustr.bakimporter.util.TranslatedMtzExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun MtzConverterScreen(
    initialUri: Uri? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiSettings = remember { AiTranslationSettingsStore(context).load() }
    val professionalTranslator = remember(aiSettings) { ProfessionalThemeTranslator.fromSettings(context, aiSettings) }
    val languageTool = remember(professionalTranslator) { ThemeLanguageTool(professionalTranslator) }
    val diagnostics = remember { LiveDiagnosticsRecorder.get(context) }

    var selectedMtzFile by remember { mutableStateOf<File?>(null) }
    var mtzMetadata by remember { mutableStateOf<MtzToBakConverter.MtzMetadata?>(null) }
    var isTranslating by remember { mutableStateOf(false) }
    var translatedTextCount by remember { mutableStateOf(0) }
    var translationStatus by remember { mutableStateOf<String?>(null) }
    var savedTranslatedMtz by remember { mutableStateOf<TranslatedMtzExporter.SavedMtz?>(null) }

    fun inspectUri(uri: Uri) {
        diagnostics.record(
            "mtz_selection_received",
            "MTZ dosyası seçildi ve güvenli önbelleğe kopyalanıyor",
            mapOf("scheme" to uri.scheme, "authority" to uri.authority),
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val cacheMtz = File.createTempFile("mtz-source-", ".mtz", context.cacheDir)
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error(context.getString(R.string.mtz_file_open_failed))
                        input.use { inStream ->
                            FileOutputStream(cacheMtz).use { outStream ->
                                inStream.copyTo(outStream)
                                outStream.fd.sync()
                            }
                        }
                        cacheMtz to MtzToBakConverter.inspectMtz(cacheMtz)
                    } catch (error: Throwable) {
                        cacheMtz.delete()
                        throw error
                    }
                }
            }.onSuccess { (cacheMtz, metadata) ->
                selectedMtzFile?.takeIf { it.parentFile == context.cacheDir }?.delete()
                selectedMtzFile = cacheMtz
                mtzMetadata = metadata
                translationStatus = null
                savedTranslatedMtz = null
                diagnostics.record(
                    "mtz_inspected",
                    "MTZ doğrulandı",
                    mapOf(
                        "bytes" to cacheMtz.length(),
                        "title" to metadata.title,
                        "uiVersion" to metadata.sourceUiVersion,
                        "adapterVersion" to metadata.sourceAdapterVersion,
                    ),
                    critical = true,
                )
            }.onFailure { error ->
                diagnostics.record("mtz_selection_failed", "MTZ seçimi veya doğrulaması başarısız", error = error, critical = true)
                Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) inspectUri(initialUri)
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) inspectUri(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = HyperOSOrange,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.mtz_translator_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mtz_translator_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (aiSettings.isReady) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.professional_translation_active, aiSettings.model),
                        style = MaterialTheme.typography.labelMedium,
                        color = HyperOSGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    enabled = !isTranslating,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_pick_mtz))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        mtzMetadata?.let { meta ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.mtz_theme_info),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = HyperOSBlue,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    MetadataRow(stringResource(R.string.mtz_theme_name), meta.title)
                    Spacer(modifier = Modifier.height(4.dp))
                    MetadataRow(stringResource(R.string.mtz_theme_author), meta.author)
                    Spacer(modifier = Modifier.height(4.dp))
                    MetadataRow(stringResource(R.string.mtz_theme_version), meta.version)
                    Spacer(modifier = Modifier.height(4.dp))
                    MetadataRow(
                        stringResource(R.string.mtz_source_format),
                        stringResource(
                            R.string.mtz_source_format_value,
                            meta.sourceUiVersion,
                            meta.sourceAdapterVersion,
                        ),
                    )

                    if (isTranslating) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.mtz_translating, translatedTextCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HyperOSOrange,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = HyperOSOrange,
                        )
                    }

                    translationStatus?.let { status ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HyperOSGreen,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val sourceMtz = selectedMtzFile ?: return@Button
                            scope.launch {
                                diagnostics.ensureSession()
                                diagnostics.record(
                                    "mtz_translation_started",
                                    "MTZ çevirisi başlatıldı",
                                    mapOf(
                                        "sourceBytes" to sourceMtz.length(),
                                        "title" to (mtzMetadata?.title ?: sourceMtz.nameWithoutExtension),
                                    ),
                                    critical = true,
                                )
                                translatedTextCount = 0
                                translationStatus = null
                                savedTranslatedMtz = null
                                var translatedTemp: File? = null
                                try {
                                    isTranslating = true
                                    val translated = withContext(Dispatchers.IO) {
                                        val output = File.createTempFile("mtz-translated-", ".mtz", context.cacheDir)
                                        translatedTemp = output
                                        languageTool.translateThemeTextToSystemLanguage(
                                            source = sourceMtz,
                                            output = output,
                                            onTranslatedText = { translatedTextCount = it },
                                        )
                                    }
                                    val saved = withContext(Dispatchers.IO) {
                                        TranslatedMtzExporter.save(
                                            context = context,
                                            translatedMtz = translated.outputFile,
                                            themeTitle = mtzMetadata?.title ?: sourceMtz.nameWithoutExtension,
                                        )
                                    }
                                    savedTranslatedMtz = saved
                                    translationStatus = context.getString(
                                        R.string.mtz_translate_saved_success,
                                        translated.translatedNodes,
                                        translated.changedFiles.size,
                                        saved.displayPath,
                                    )
                                    if (aiSettings.isReady) {
                                        translationStatus += "\n" + context.getString(
                                            if (translated.professionalWarnings.isEmpty()) R.string.professional_translation_used
                                            else R.string.professional_translation_partial_fallback,
                                            translated.professionalTranslatedTexts,
                                        )
                                    }
                                    diagnostics.record(
                                        "mtz_translation_completed",
                                        "MTZ çevrildi ve İndirilenler klasörüne kaydedildi",
                                        mapOf(
                                            "translatedNodes" to translated.translatedNodes,
                                            "changedFiles" to translated.changedFiles.size,
                                            "skippedFiles" to translated.skippedFiles.size,
                                            "unresolvedTexts" to translated.unresolvedTexts.size,
                                            "detectedLanguages" to translated.detectedLanguages.entries.joinToString { "${it.key}:${it.value}" },
                                            "outputBytes" to translated.outputFile.length(),
                                            "savedPath" to saved.displayPath,
                                            "professionalTranslatedTexts" to translated.professionalTranslatedTexts,
                                            "professionalWarningCount" to translated.professionalWarnings.size,
                                        ),
                                        critical = true,
                                    )
                                    Toast.makeText(context, R.string.mtz_translation_saved_toast, Toast.LENGTH_LONG).show()
                                } catch (error: Throwable) {
                                    diagnostics.record("mtz_translation_failed", "MTZ çevirisi başarısız", error = error, critical = true)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.mtz_translate_failed, error.message),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } finally {
                                    isTranslating = false
                                    translatedTemp?.delete()
                                }
                            }
                        },
                        enabled = !isTranslating,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HyperOSOrange),
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_translate_and_save_mtz))
                    }
                }
            }
        }

        savedTranslatedMtz?.let { saved ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = HyperOSGreen.copy(alpha = 0.12f)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = HyperOSGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.mtz_translation_complete),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = HyperOSGreen,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.translated_mtz_saved_path, saved.displayPath),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { TranslatedMtzExporter.share(context, saved) },
                        colors = ButtonDefaults.buttonColors(containerColor = HyperOSBlue),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share_translated_mtz))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
