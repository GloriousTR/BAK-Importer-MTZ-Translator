package dev.glorioustr.bakimporter.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

object StoragePermissionHelper {
    private const val PREFS_NAME = "backup_storage"
    private const val KEY_BACKUP_TREE_URI = "backup_tree_uri"
    private const val ALL_BACKUP_DOCUMENT_ID = "primary:MIUI/backup/AllBackup"

    fun hasStoragePermission(context: Context): Boolean {
        return hasDirectStoragePermission(context) || hasBackupTreePermission(context)
    }

    fun hasDirectStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasBackupTreePermission(context: Context): Boolean {
        val uri = getBackupTreeUri(context) ?: return false
        val persisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!persisted) return false
        return DocumentFile.fromTreeUri(context, uri)?.let { it.exists() && it.canWrite() } == true
    }

    fun getBackupTreeUri(context: Context): Uri? {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BACKUP_TREE_URI, null)
            ?: return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    fun persistBackupTreePermission(context: Context, uri: Uri, grantedFlags: Int): Result<Unit> = runCatching {
        require(isAllBackupTree(context, uri)) {
            "Lütfen MIUI/backup/AllBackup klasörünü seçin"
        }
        val flags = grantedFlags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            "Seçilen klasör için yazma izni verilmedi"
        }
        context.contentResolver.takePersistableUriPermission(uri, flags)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKUP_TREE_URI, uri.toString())
            .apply()
    }

    fun createBackupTreeIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            val initialUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                ALL_BACKUP_DOCUMENT_ID,
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
    }

    private fun isAllBackupTree(context: Context, uri: Uri): Boolean {
        val nameMatches = DocumentFile.fromTreeUri(context, uri)?.name
            ?.equals("AllBackup", ignoreCase = true) == true
        val idMatches = runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull()
            ?.replace('\\', '/')
            ?.endsWith("MIUI/backup/AllBackup", ignoreCase = true) == true
        return nameMatches || idMatches
    }

    fun createPermissionIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
