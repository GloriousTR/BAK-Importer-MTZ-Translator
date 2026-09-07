package dev.glorioustr.bakimporter.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import dev.glorioustr.bakimporter.BuildConfig
import dev.glorioustr.bakimporter.R
import dev.glorioustr.bakimporter.diagnostics.LiveDiagnosticsRecorder
import dev.glorioustr.bakimporter.ui.theme.HyperOSBlue
import dev.glorioustr.bakimporter.ui.theme.HyperOSGreen
import dev.glorioustr.bakimporter.ui.theme.HyperOSOrange
import dev.glorioustr.bakimporter.util.XiaomiIntentLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AuxiliaryDestination { TRANSLATION_SETTINGS, DIAGNOSTICS, ABOUT }

@Composable
fun AppOverlayMenu(
    onDismiss: () -> Unit,
    onNavigate: (AuxiliaryDestination) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable(onClick = onDismiss)
                .padding(start = 12.dp, end = 24.dp, top = 48.dp, bottom = 36.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(0.9f).clickable(enabled = false) {},
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.menu_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.menu_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OverlayMenuItem(
                        icon = Icons.Default.Settings,
                        color = HyperOSOrange,
                        title = stringResource(R.string.ai_settings_title),
                        description = stringResource(R.string.ai_settings_menu_description),
                        onClick = { onNavigate(AuxiliaryDestination.TRANSLATION_SETTINGS) },
                    )
                    OverlayMenuItem(
                        icon = Icons.Default.MonitorHeart,
                        color = HyperOSGreen,
                        title = stringResource(R.string.diagnostics_title),
                        description = stringResource(R.string.diagnostics_menu_description),
                        onClick = { onNavigate(AuxiliaryDestination.DIAGNOSTICS) },
                    )
                    OverlayMenuItem(
                        icon = Icons.Default.Info,
                        color = Color(0xFF7E57C2),
                        title = stringResource(R.string.about_title),
                        description = stringResource(R.string.about_menu_description),
                        onClick = { onNavigate(AuxiliaryDestination.ABOUT) },
                    )
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.menu_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayMenuItem(
    icon: ImageVector,
    color: Color,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(14.dp)) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(11.dp).size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
fun LiveDiagnosticsScreen(
    recorder: LiveDiagnosticsRecorder,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by recorder.state.collectAsState()
    var note by remember { mutableStateOf("") }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    val accessibilityEnabled = recorder.isExternalCaptureServiceEnabled()
    val evidencePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { recorder.attachEvidence(uri) } }
                .onSuccess { actionStatus = context.getString(R.string.diagnostics_evidence_added, it) }
                .onFailure { actionStatus = context.getString(R.string.diagnostics_action_failed, it.message.orEmpty()) }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DiagnosticCard {
            Text(stringResource(R.string.diagnostics_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.diagnostics_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.diagnostics_session, state.sessionId), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.diagnostics_phase, state.phase), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                recorder.saveExportToDownloads(recorder.createExport())
                            }
                        }.onSuccess {
                            actionStatus = context.getString(R.string.diagnostics_saved_to_downloads)
                        }.onFailure {
                            actionStatus = context.getString(R.string.diagnostics_action_failed, it.message.orEmpty())
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text(stringResource(R.string.diagnostics_save_to_downloads), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = {
                    recorder.startNewSession()
                    actionStatus = context.getString(
                        if (recorder.isExternalCaptureServiceEnabled()) {
                            R.string.diagnostics_new_session_started
                        } else {
                            R.string.diagnostics_session_started_access_required
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text(stringResource(R.string.diagnostics_start_test), modifier = Modifier.padding(start = 8.dp))
            }
        }

        DiagnosticCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Security, contentDescription = null, tint = if (accessibilityEnabled) HyperOSGreen else HyperOSOrange)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.diagnostics_feedback_access), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(if (accessibilityEnabled) R.string.diagnostics_access_enabled else R.string.diagnostics_access_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(stringResource(R.string.diagnostics_access_scope), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    recorder.setExternalCaptureEnabled(true)
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Text(stringResource(R.string.diagnostics_open_accessibility), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = { recorder.setExternalCaptureEnabled(!state.externalCaptureEnabled) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(if (state.externalCaptureEnabled) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                Text(
                    stringResource(if (state.externalCaptureEnabled) R.string.diagnostics_stop_capture else R.string.diagnostics_resume_capture),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        DiagnosticCard {
            Text(stringResource(R.string.diagnostics_reproduce_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.diagnostics_reproduce_steps), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        recorder.record("restore_screen_open_requested", "Kullanıcı Yedekle ve Geri Yükle ekranını açtı", critical = true)
                        XiaomiIntentLauncher.openBackupAndRestore(context)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Backup, contentDescription = null)
                    Text(stringResource(R.string.diagnostics_open_restore), modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = {
                        recorder.record("themes_open_requested", "Kullanıcı Xiaomi Temalar'ı açtı", critical = true)
                        XiaomiIntentLauncher.openThemesApp(context)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Text(stringResource(R.string.diagnostics_open_themes), modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { recorder.markResult(false) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.diagnostics_mark_error), modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = { recorder.markResult(true) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = HyperOSGreen)
                    Text(stringResource(R.string.diagnostics_mark_success), modifier = Modifier.padding(start = 6.dp))
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(1000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.diagnostics_error_note)) },
                supportingText = { Text(stringResource(R.string.diagnostics_error_note_hint)) },
                minLines = 2,
            )
            OutlinedButton(
                onClick = {
                    runCatching { recorder.addUserNote(note) }
                        .onSuccess { note = ""; actionStatus = context.getString(R.string.diagnostics_note_saved) }
                        .onFailure { actionStatus = it.message }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.diagnostics_save_note)) }
        }

        DiagnosticCard {
            Text(stringResource(R.string.diagnostics_evidence_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.diagnostics_evidence_description), style = MaterialTheme.typography.bodySmall)
            state.attachedEvidenceName?.let { Text(it, color = HyperOSBlue, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(
                onClick = { evidencePicker.launch(arrayOf("image/*", "video/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Text(stringResource(R.string.diagnostics_add_evidence), modifier = Modifier.padding(start = 8.dp))
            }
            Button(
                onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { recorder.createExport() } }
                            .onSuccess { file ->
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "application/zip"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            putExtra(Intent.EXTRA_SUBJECT, "HyperOS BAK Importer Live Diagnostics ${state.sessionId}")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        context.getString(R.string.diagnostics_share),
                                    )
                                )
                            }
                            .onFailure { actionStatus = context.getString(R.string.diagnostics_action_failed, it.message.orEmpty()) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Text(stringResource(R.string.diagnostics_share), modifier = Modifier.padding(start = 8.dp))
            }
            actionStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        if (state.recentEvents.isNotEmpty()) {
            DiagnosticCard {
                Text(stringResource(R.string.diagnostics_recent_events), fontWeight = FontWeight.Bold)
                state.recentEvents.asReversed().take(30).forEachIndexed { index, event ->
                    if (index > 0) HorizontalDivider()
                    SelectionContainer { Text(event, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 7.dp)) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DiagnosticCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.fillMaxWidth().background(
                        Brush.linearGradient(listOf(Color(0xFF7138F4).copy(alpha = 0.34f), HyperOSBlue.copy(alpha = 0.28f)))
                    ).padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(112.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                        Text(
                            stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(stringResource(R.string.about_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DiagnosticCard {
            Text(stringResource(R.string.about_rootless_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.about_rootless_description), style = MaterialTheme.typography.bodySmall)
        }
        DiagnosticCard {
            Text(stringResource(R.string.about_privacy_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.about_privacy_description), style = MaterialTheme.typography.bodySmall)
        }
    }
}
