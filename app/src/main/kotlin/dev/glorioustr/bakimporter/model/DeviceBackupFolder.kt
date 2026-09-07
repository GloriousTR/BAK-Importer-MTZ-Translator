package dev.glorioustr.bakimporter.model

import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceBackupFolder(
    val folderName: String,
    val hasDescriptXml: Boolean,
    val bakFileNames: List<String>,
    val totalSizeBytes: Long,
    val lastModified: Long,
    val directory: File? = null,
    val documentUri: Uri? = null,
) {
    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(lastModified))

    val formattedSize: String
        get() {
            val mb = totalSizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format("%.1f MB", mb) else "${totalSizeBytes / 1024} KB"
        }

    val containsThemeBackup: Boolean
        get() = bakFileNames.any {
            it.contains("thememanager", ignoreCase = true) ||
                    it.contains("Theme", ignoreCase = true)
        }
}
