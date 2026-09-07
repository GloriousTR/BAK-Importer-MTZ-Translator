package dev.glorioustr.bakimporter.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TranslatedMtzExporter {
    data class SavedMtz(
        val uri: Uri,
        val displayName: String,
        val displayPath: String,
    )

    fun save(context: Context, translatedMtz: File, themeTitle: String): SavedMtz {
        require(translatedMtz.isFile && translatedMtz.length() > 0L) { "Çevrilmiş MTZ dosyası boş" }
        val displayName = buildFileName(themeTitle)
        val relativeFolder = "${Environment.DIRECTORY_DOWNLOADS}/BAK Importer"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Çevrilmiş MTZ için dosya oluşturulamadı")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    translatedMtz.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("Çevrilmiş MTZ kaydedilemedi")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
            return SavedMtz(uri, displayName, "$relativeFolder/$displayName")
        }

        @Suppress("DEPRECATION")
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BAK Importer",
        ).apply { check(exists() || mkdirs()) { "BAK Importer klasörü oluşturulamadı" } }
        val destination = uniqueFile(folder, displayName)
        translatedMtz.copyTo(destination)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", destination)
        return SavedMtz(uri, destination.name, destination.absolutePath)
    }

    fun share(context: Context, savedMtz: SavedMtz) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, savedMtz.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Çevrilmiş MTZ'yi paylaş"))
    }

    private fun buildFileName(themeTitle: String): String {
        val safeTitle = themeTitle
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .ifBlank { "translated_theme" }
            .take(60)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${safeTitle}_translated_$timestamp.mtz"
    }

    private fun uniqueFile(folder: File, requestedName: String): File {
        val initial = File(folder, requestedName)
        if (!initial.exists()) return initial
        val stem = requestedName.substringBeforeLast('.')
        val extension = requestedName.substringAfterLast('.', "mtz")
        var suffix = 2
        while (true) {
            val candidate = File(folder, "${stem}_$suffix.$extension")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }
}
