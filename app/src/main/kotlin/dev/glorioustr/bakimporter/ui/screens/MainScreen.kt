package dev.glorioustr.bakimporter.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.glorioustr.bakimporter.R
import dev.glorioustr.bakimporter.backup.BakInspector
import dev.glorioustr.bakimporter.backup.BakStager
import dev.glorioustr.bakimporter.backup.BakThemeTranslator
import dev.glorioustr.bakimporter.diagnostics.LiveDiagnosticsRecorder
import dev.glorioustr.bakimporter.model.BakArchiveInfo
import dev.glorioustr.bakimporter.model.ThemeManagerInfo
import dev.glorioustr.bakimporter.ui.components.BakDetailsCard
import dev.glorioustr.bakimporter.ui.components.StepGuideDialog
import dev.glorioustr.bakimporter.ui.theme.HyperOSBlue
import dev.glorioustr.bakimporter.ui.theme.HyperOSGreen
import dev.glorioustr.bakimporter.ui.theme.HyperOSOrange
import dev.glorioustr.bakimporter.translation.AiTranslationSettingsStore
import dev.glorioustr.bakimporter.translation.ProfessionalThemeTranslator
import dev.glorioustr.bakimporter.translation.ThemeLanguageTool
import dev.glorioustr.bakimporter.util.StoragePermissionHelper
import dev.glorioustr.bakimporter.util.ThemeManagerDetector
import dev.glorioustr.bakimporter.util.XiaomiIntentLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun MainScreen(
    initialUri: Uri? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics = remember { LiveDiagnosticsRecorder.get(context) }

    var hasPermission by remember { mutableStateOf(StoragePermissionHelper.hasStoragePermission(context)) }
    var themeManagerInfo by remember { mutableStateOf(ThemeManagerDetector.detect(context)) }
    var loadedArchive by remember { mutableStateOf<BakArchiveInfo?>(null) }
    var allowMismatch by remember { mutableStateOf(false) }

    var isInspecting by remember { mutableStateOf(false) }
    var isStaging by remember { mutableStateOf(false) }
    var stagingProgress by remember { mutableFloatStateOf(0f) }
    var translateBakBeforeImport by remember { mutableStateOf(true) }
    var isTranslatingBak by remember { mutableStateOf(false) }
    var translatedBakTextCount by remember { mutableStateOf(0) }
    var stagedFolderPath by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showGuideDialog by remember { mutableStateOf(false) }

    val bakStager = remember { BakStager() }
    val aiSettings = remember { AiTranslationSettingsStore(context).load() }
    val professionalTranslator = remember(aiSettings) { ProfessionalThemeTranslator.fromSettings(context, aiSettings) }
    val bakThemeTranslator = remember(professionalTranslator) {
        BakThemeTranslator(ThemeLanguageTool(professionalTranslator))
    }

    // Inspect archive helper
    fun inspectUri(uri: Uri) {
        diagnostics.record(
            "bak_selection_received",
            "BAK dosyası seçildi ve güvenli önbelleğe kopyalanıyor",
            mapOf("scheme" to uri.scheme, "authority" to uri.authority),
        )
        scope.launch {
            isInspecting = true
            statusMessage = null
            stagedFolderPath = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val cacheFile = File.createTempFile("bak-import-", ".bak", context.cacheDir)
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Seçilen BAK dosyası açılamadı")
                        input.use { inStream ->
                            FileOutputStream(cacheFile).use { outStream ->
                                inStream.copyTo(outStream)
                                outStream.fd.sync()
                            }
                        }
                        BakInspector.inspect(cacheFile)
                    } catch (error: Throwable) {
                        cacheFile.delete()
                        throw error
                    }
                }
            }.onSuccess { archive ->
                loadedArchive?.file?.takeIf { it != archive.file && it.parentFile == context.cacheDir }?.delete()
                loadedArchive = archive
                translateBakBeforeImport = true
                isInspecting = false
                diagnostics.record(
                    "bak_inspected",
                    "BAK doğrulandı",
                    mapOf(
                        "bytes" to archive.sizeBytes,
                        "entries" to archive.entryCount,
                        "package" to archive.packageName,
                        "versionCode" to archive.backupVersionCode,
                        "rightsFiles" to archive.rightsFileCount,
                        "applyAuthorized" to archive.hasApplyRights,
                    ),
                    critical = true,
                )
            }.onFailure { error ->
                isInspecting = false
                statusMessage = "Hata: ${error.message}"
                diagnostics.record("bak_inspection_failed", "BAK seçimi veya doğrulaması başarısız", error = error, critical = true)
                Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Handle initial incoming URI from intent
    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            inspectUri(initialUri)
        }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            inspectUri(uri)
        }
    }

    // Storage permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasPermission = StoragePermissionHelper.hasStoragePermission(context)
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted || StoragePermissionHelper.hasStoragePermission(context)
    }

    val backupTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            StoragePermissionHelper.persistBackupTreePermission(
                context,
                uri,
                result.data?.flags ?: 0,
            )
                .onSuccess {
                    hasPermission = true
                    Toast.makeText(context, R.string.permission_folder_granted, Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                }
        }
    }

    fun stageArchive(archive: BakArchiveInfo, openRestoreAfter: Boolean) {
        scope.launch {
            diagnostics.ensureSession()
            diagnostics.record(
                "bak_staging_started",
                "Hazır BAK için yerleştirme işlemi başlatıldı",
                mapOf("translate" to translateBakBeforeImport, "bytes" to archive.sizeBytes, "entries" to archive.entryCount),
                critical = true,
            )
            isStaging = true
            isTranslatingBak = false
            translatedBakTextCount = 0
            stagingProgress = 0f
            statusMessage = null
            var translatedBak: File? = null
            try {
                var archiveToStage = archive
                if (translateBakBeforeImport) {
                    isTranslatingBak = true
                    translatedBak = File.createTempFile("bak-translated-", ".bak", context.cacheDir)
                    val translated = withContext(Dispatchers.IO) {
                        bakThemeTranslator.translate(
                            sourceBak = archive.file,
                            outputBak = translatedBak,
                            onTranslatedText = { translatedBakTextCount = it },
                        )
                    }
                    archiveToStage = withContext(Dispatchers.IO) { BakInspector.inspect(translated.outputFile) }
                    statusMessage = context.getString(
                        R.string.bak_translate_success,
                        translated.translatedNodes,
                        translated.scannedComponents,
                    )
                    if (aiSettings.isReady) {
                        statusMessage += "\n" + context.getString(
                            if (translated.professionalWarnings.isEmpty()) R.string.professional_translation_used
                            else R.string.professional_translation_partial_fallback,
                            translated.professionalTranslatedTexts,
                        )
                    }
                    if (archive.hasApplyRights) {
                        statusMessage += "\n" + context.getString(R.string.bak_signed_translation_warning)
                    }
                    diagnostics.record(
                        "bak_translation_completed",
                        "BAK içindeki tema metinleri çevrildi",
                        mapOf(
                            "translatedNodes" to translated.translatedNodes,
                            "scannedComponents" to translated.scannedComponents,
                            "professionalTranslatedTexts" to translated.professionalTranslatedTexts,
                            "professionalWarningCount" to translated.professionalWarnings.size,
                        ),
                        critical = true,
                    )
                    isTranslatingBak = false
                }

                val folder = withContext(Dispatchers.IO) {
                    bakStager.stageNewBackup(
                        context = context,
                        archiveInfo = archiveToStage,
                        versionName = themeManagerInfo.versionName,
                        onProgress = { stagingProgress = it },
                    )
                }
                stagedFolderPath = folder.displayPath
                diagnostics.record(
                    "backup_staged",
                    "BAK AllBackup içine yerleştirildi",
                    mapOf("folder" to folder.folderName, "displayPath" to folder.displayPath),
                    critical = true,
                )
                if (statusMessage == null) {
                    statusMessage = context.getString(R.string.status_staged_success, folder.folderName)
                }
                Toast.makeText(context, statusMessage, Toast.LENGTH_SHORT).show()
                if (openRestoreAfter) {
                    val opened = XiaomiIntentLauncher.openBackupAndRestore(context)
                    diagnostics.record(
                        "restore_screen_launch",
                        if (opened) "Sistem Yedekle ve Geri Yükle ekranı açıldı" else "Sistem Yedekle ve Geri Yükle ekranı açılamadı",
                        mapOf("opened" to opened),
                        critical = true,
                    )
                }
            } catch (error: Throwable) {
                statusMessage = context.getString(R.string.status_error, error.message)
                diagnostics.record("bak_staging_failed", "BAK yerleştirme işlemi başarısız", error = error, critical = true)
                Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
            } finally {
                isTranslatingBak = false
                isStaging = false
                translatedBak?.delete()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // App Header with Logo
        Spacer(modifier = Modifier.height(4.dp))
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.bak_importer_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = HyperOSBlue,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.bak_importer_desc),
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
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Storage Permission Card
        if (!hasPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = HyperOSOrange.copy(alpha = 0.12f)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = HyperOSOrange,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.permission_storage_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = HyperOSOrange,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.permission_storage_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                permissionLauncher.launch(StoragePermissionHelper.createPermissionIntent(context))
                            } else {
                                legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = HyperOSOrange),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(stringResource(R.string.permission_storage_grant))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { backupTreeLauncher.launch(StoragePermissionHelper.createBackupTreeIntent()) },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(stringResource(R.string.permission_choose_folder))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // BAK import and help
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_pick_bak))
            }

            IconButton(
                onClick = { showGuideDialog = true },
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp)
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = stringResource(R.string.tab_guide),
                    tint = HyperOSBlue,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Loading indicator during inspect
        if (isInspecting) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = HyperOSBlue)
            }
        }

        // Archive Details
        loadedArchive?.let { archive ->
            BakDetailsCard(
                archive = archive,
                themeManagerInfo = themeManagerInfo,
                allowMismatch = allowMismatch,
                onAllowMismatchChange = { allowMismatch = it },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = HyperOSOrange.copy(alpha = 0.10f)),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = translateBakBeforeImport,
                        onCheckedChange = { translateBakBeforeImport = it },
                        enabled = !isStaging,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.translate_bak_before_import_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.translate_bak_before_import_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress bar during staging
            if (isStaging) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (isTranslatingBak) {
                            stringResource(R.string.bak_translating, translatedBakTextCount)
                        } else {
                            stringResource(R.string.status_staging)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = HyperOSBlue,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (isTranslatingBak) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = HyperOSOrange)
                    } else {
                        LinearProgressIndicator(
                            progress = { stagingProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = HyperOSBlue,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Staging Buttons
            val canStage = hasPermission && !isStaging &&
                    (themeManagerInfo.versionCode == 0L || archive.backupVersionCode == themeManagerInfo.versionCode || allowMismatch)

            Button(
                onClick = { stageArchive(archive, false) },
                enabled = canStage,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HyperOSBlue),
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (translateBakBeforeImport) R.string.action_translate_stage_backup
                        else R.string.action_stage_backup
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Stage & Open Shortcut
            OutlinedButton(
                onClick = { stageArchive(archive, true) },
                enabled = canStage,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (translateBakBeforeImport) R.string.action_translate_stage_and_open
                        else R.string.action_stage_and_open
                    )
                )
            }
        }

        // Staged Success Banner
        stagedFolderPath?.let { path ->
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
                            text = "Yedek Başarıyla Hazırlandı!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = HyperOSGreen,
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.folder_staged, path.substringAfterLast(File.separator)),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    statusMessage?.takeIf(String::isNotBlank)?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { XiaomiIntentLauncher.openBackupAndRestore(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = HyperOSGreen),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(stringResource(R.string.action_open_restore_screen))
                        }

                        OutlinedButton(
                            onClick = { XiaomiIntentLauncher.openThemesApp(context) },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(stringResource(R.string.action_open_themes))
                        }
                    }
                }
            }
        }

        // System Action Shortcuts
        Spacer(modifier = Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sistem Kısayolları",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { XiaomiIntentLauncher.openBackupAndRestore(context) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Yedekle / Yükle")
                    }

                    OutlinedButton(
                        onClick = { XiaomiIntentLauncher.openThemesApp(context) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Temalar")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showGuideDialog) {
        StepGuideDialog(
            onDismiss = { showGuideDialog = false },
            onOpenRestore = { XiaomiIntentLauncher.openBackupAndRestore(context) }
        )
    }
}
