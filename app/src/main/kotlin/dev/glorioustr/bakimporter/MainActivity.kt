package dev.glorioustr.bakimporter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.glorioustr.bakimporter.ui.screens.BackupListScreen
import dev.glorioustr.bakimporter.ui.screens.AboutScreen
import dev.glorioustr.bakimporter.ui.screens.AppOverlayMenu
import dev.glorioustr.bakimporter.ui.screens.AuxiliaryDestination
import dev.glorioustr.bakimporter.ui.screens.LiveDiagnosticsScreen
import dev.glorioustr.bakimporter.ui.screens.MainScreen
import dev.glorioustr.bakimporter.ui.screens.MtzConverterScreen
import dev.glorioustr.bakimporter.ui.screens.TranslationSettingsScreen
import dev.glorioustr.bakimporter.ui.theme.HyperOSBAKImporterTheme
import dev.glorioustr.bakimporter.diagnostics.LiveDiagnosticsRecorder

class MainActivity : ComponentActivity() {

    private val incomingUri = mutableStateOf<Uri?>(null)
    private lateinit var diagnostics: LiveDiagnosticsRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        diagnostics = LiveDiagnosticsRecorder.get(applicationContext)
        diagnostics.record("app_created", "Uygulama başlatıldı", mapOf("intentAction" to intent?.action))

        handleIntent(intent)

        setContent {
            HyperOSBAKImporterTheme {
                AppRoot(initialUri = incomingUri.value, diagnostics = diagnostics)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::diagnostics.isInitialized) diagnostics.record("app_resumed", "BAK Importer ön plana döndü")
    }

    override fun onPause() {
        if (::diagnostics.isInitialized) diagnostics.record("app_paused", "BAK Importer arka plana geçti")
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        diagnostics.record("new_intent", "Uygulama yeni bir dosya isteği aldı", mapOf("action" to intent.action, "type" to intent.type))
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            incomingUri.value = intent.data
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    initialUri: Uri? = null,
    diagnostics: LiveDiagnosticsRecorder,
) {
    val incomingMtz = initialUri?.lastPathSegment?.substringBefore('?')?.endsWith(".mtz", ignoreCase = true) == true
    var selectedTab by remember { mutableIntStateOf(if (incomingMtz) 1 else 0) }
    var auxiliaryDestination by remember { mutableStateOf<AuxiliaryDestination?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(initialUri) {
        if (incomingMtz) selectedTab = 1
    }

    BackHandler(enabled = auxiliaryDestination != null) { auxiliaryDestination = null }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (auxiliaryDestination == null) showMenu = true else auxiliaryDestination = null
                        }
                    ) {
                        Icon(
                            if (auxiliaryDestination == null) Icons.Default.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                if (auxiliaryDestination == null) R.string.menu_title else R.string.dialog_cancel
                            ),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = when (auxiliaryDestination) {
                                AuxiliaryDestination.TRANSLATION_SETTINGS -> stringResource(R.string.ai_settings_title)
                                AuxiliaryDestination.DIAGNOSTICS -> stringResource(R.string.diagnostics_title)
                                AuxiliaryDestination.ABOUT -> stringResource(R.string.about_title)
                                null -> stringResource(R.string.app_name)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (auxiliaryDestination == null) {
                            Text(
                                text = stringResource(R.string.app_subtitle),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (auxiliaryDestination == null) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_import)) },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_mtz)) },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_backups)) },
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> if (auxiliaryDestination == null) MainScreen(
                    initialUri = initialUri.takeUnless { incomingMtz },
                )
                1 -> if (auxiliaryDestination == null) MtzConverterScreen(
                    initialUri = initialUri.takeIf { incomingMtz },
                )
                2 -> if (auxiliaryDestination == null) BackupListScreen()
            }
            when (auxiliaryDestination) {
                AuxiliaryDestination.TRANSLATION_SETTINGS -> TranslationSettingsScreen()
                AuxiliaryDestination.DIAGNOSTICS -> LiveDiagnosticsScreen(diagnostics)
                AuxiliaryDestination.ABOUT -> AboutScreen()
                null -> Unit
            }
        }
    }

    if (showMenu) {
        AppOverlayMenu(
            onDismiss = { showMenu = false },
            onNavigate = {
                auxiliaryDestination = it
                showMenu = false
                diagnostics.record("navigation", "${it.name} ekranı açıldı")
            },
        )
    }
}
