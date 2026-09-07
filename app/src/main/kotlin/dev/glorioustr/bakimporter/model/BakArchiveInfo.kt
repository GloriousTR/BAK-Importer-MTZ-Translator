package dev.glorioustr.bakimporter.model

import java.io.File

data class BakArchiveInfo(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val backupVersionCode: Long,
    val appDisplayName: String,
    val packageName: String,
    val tarOffset: Long,
    val entryCount: Int,
    val entryNames: List<String> = emptyList(),
    val rightsFileCount: Int = 0,
    val importedThemeRecordCount: Int = 0,
) {
    val hasApplyRights: Boolean
        get() = rightsFileCount > 0

    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format("%.2f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$sizeBytes B"
            }
        }
}
