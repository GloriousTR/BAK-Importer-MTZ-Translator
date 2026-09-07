package dev.glorioustr.bakimporter.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import dev.glorioustr.bakimporter.diagnostics.LiveDiagnosticsRecorder

object XiaomiIntentLauncher {
    private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"
    private const val BACKUP_PACKAGE = "com.miui.backup"

    /**
     * Tries to open Xiaomi's native Backup & Restore screen.
     * Tries specific MIUI/HyperOS activities first, then falls back to Settings.
     */
    fun openBackupAndRestore(context: Context): Boolean {
        val diagnostics = LiveDiagnosticsRecorder.get(context)
        val candidates = listOf(
            // Primary HyperOS & MIUI Local Backup activity (Direct AllBackup interface)
            Intent().apply {
                component = ComponentName(BACKUP_PACKAGE, "com.miui.backup.local.LocalHomeActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // Direct MoreSettings activity in com.miui.backup
            Intent().apply {
                component = ComponentName(BACKUP_PACKAGE, "com.miui.backup.settings.MoreSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent().apply {
                component = ComponentName(BACKUP_PACKAGE, "com.miui.backup.activity.BackupDetailActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent().apply {
                component = ComponentName(BACKUP_PACKAGE, "com.miui.backup.activity.ProgressPageActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent().apply {
                component = ComponentName(BACKUP_PACKAGE, "com.miui.backup.activity.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // Settings Backup & Reset shortcut
            Intent("miui.intent.action.BACKUP_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings\$BackupSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    diagnostics.record(
                        "external_activity_opened",
                        "MIUI Yedekle ve Geri Yükle açıldı",
                        mapOf("component" to (intent.component?.flattenToShortString() ?: intent.action)),
                    )
                    return true
                }
            } catch (error: Throwable) {
                diagnostics.record("external_activity_candidate_failed", "Yedekleme ekranı adayı açılamadı", mapOf("component" to intent.component?.flattenToShortString()), error)
            }
        }

        // Final fallback: standard Settings app
        return try {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            diagnostics.record("external_activity_fallback", "Yedekleme ekranı bulunamadı; Ayarlar açıldı", critical = true)
            Toast.makeText(
                context,
                "Ek Ayarlar > Yedekle ve Geri Yükle yolunu izleyin",
                Toast.LENGTH_LONG
            ).show()
            true
        } catch (e: Throwable) {
            diagnostics.record("external_activity_failed", "Ayarlar açılamadı", error = e, critical = true)
            false
        }
    }

    /**
     * Opens the Xiaomi Themes application.
     */
    fun openThemesApp(context: Context): Boolean {
        val diagnostics = LiveDiagnosticsRecorder.get(context)
        val candidates = listOf(
            Intent().apply {
                component = ComponentName(
                    THEME_MANAGER_PACKAGE,
                    "com.android.thememanager.mine.remote.view.activity.MineResourceTabActivity"
                )
                putExtra("REQUEST_RESOURCE_CODE", "theme")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent().apply {
                component = ComponentName(
                    THEME_MANAGER_PACKAGE,
                    "com.android.thememanager.ThemeResourceTabActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    diagnostics.record(
                        "external_activity_opened",
                        "Xiaomi Temalar açıldı",
                        mapOf("component" to intent.component?.flattenToShortString()),
                        critical = true,
                    )
                    return true
                }
            } catch (error: Throwable) {
                diagnostics.record("external_activity_candidate_failed", "Temalar ekranı adayı açılamadı", mapOf("component" to intent.component?.flattenToShortString()), error)
            }
        }

        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(THEME_MANAGER_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                diagnostics.record("external_activity_fallback", "Xiaomi Temalar ana ekranı açıldı", critical = true)
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            diagnostics.record("external_activity_failed", "Xiaomi Temalar açılamadı", error = e, critical = true)
            false
        }
    }
}
