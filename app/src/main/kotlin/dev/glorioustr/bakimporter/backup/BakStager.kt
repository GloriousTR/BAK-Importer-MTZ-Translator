package dev.glorioustr.bakimporter.backup

import android.content.Context
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import dev.glorioustr.bakimporter.model.BakArchiveInfo
import dev.glorioustr.bakimporter.model.DeviceBackupFolder
import dev.glorioustr.bakimporter.model.StagedBackup
import dev.glorioustr.bakimporter.util.StoragePermissionHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BakStager {

    companion object {
        const val DEFAULT_BAK_NAME = "Themes(com.android.thememanager).bak"
        private const val PACKAGE_NAME = "com.android.thememanager"

        fun getAllBackupDirectory(): File {
            val root = Environment.getExternalStorageDirectory()
            return File(root, "MIUI/backup/AllBackup")
        }
    }

    fun listExistingBackups(context: Context): List<DeviceBackupFolder> {
        return if (StoragePermissionHelper.hasDirectStoragePermission(context)) {
            listExistingFileBackups()
        } else {
            listExistingDocumentBackups(context)
        }
    }

    private fun listExistingFileBackups(): List<DeviceBackupFolder> {
        val allBackupDir = getAllBackupDirectory()
        if (!allBackupDir.exists() || !allBackupDir.isDirectory) return emptyList()

        return allBackupDir.listFiles { file -> file.isDirectory }.orEmpty().map { dir ->
            val files = dir.listFiles().orEmpty()
            DeviceBackupFolder(
                folderName = dir.name,
                hasDescriptXml = files.any { it.name.equals("descript.xml", ignoreCase = true) },
                bakFileNames = files.filter { it.extension.equals("bak", ignoreCase = true) }.map { it.name },
                totalSizeBytes = files.sumOf { it.length() },
                lastModified = dir.lastModified(),
                directory = dir,
            )
        }.sortedByDescending { it.lastModified }
    }

    private fun listExistingDocumentBackups(context: Context): List<DeviceBackupFolder> {
        val treeUri = StoragePermissionHelper.getBackupTreeUri(context) ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return root.listFiles().filter { it.isDirectory }.map { dir ->
            val files = dir.listFiles()
            DeviceBackupFolder(
                folderName = dir.name.orEmpty(),
                hasDescriptXml = files.any { it.name.equals("descript.xml", ignoreCase = true) },
                bakFileNames = files.filter { it.name?.endsWith(".bak", ignoreCase = true) == true }
                    .mapNotNull { it.name },
                totalSizeBytes = files.sumOf { it.length() },
                lastModified = dir.lastModified(),
                documentUri = dir.uri,
            )
        }.sortedByDescending { it.lastModified }
    }

    fun stageNewBackup(
        context: Context,
        archiveInfo: BakArchiveInfo,
        versionName: String = "V1.0.0",
        onProgress: ((Float) -> Unit)? = null,
    ): StagedBackup {
        validateArchive(archiveInfo)
        return if (StoragePermissionHelper.hasDirectStoragePermission(context)) {
            stageToFileStorage(archiveInfo, versionName, onProgress)
        } else {
            stageToDocumentTree(context, archiveInfo, versionName, onProgress)
        }
    }

    private fun stageToFileStorage(
        archiveInfo: BakArchiveInfo,
        versionName: String,
        onProgress: ((Float) -> Unit)?,
    ): StagedBackup {
        val allBackupDir = getAllBackupDirectory()
        require(allBackupDir.exists() || allBackupDir.mkdirs()) {
            "AllBackup klasörü oluşturulamadı: ${allBackupDir.absolutePath}"
        }
        require(allBackupDir.isDirectory && allBackupDir.canWrite()) {
            "AllBackup klasörüne yazılamıyor: ${allBackupDir.absolutePath}"
        }

        val targetFolder = uniqueFileFolder(allBackupDir)
        require(targetFolder.mkdirs()) { "Yedek klasörü oluşturulamadı" }

        try {
            val destinationBak = File(targetFolder, DEFAULT_BAK_NAME)
            copyFileWithProgress(archiveInfo.file, destinationBak, onProgress)
            require(destinationBak.length() == archiveInfo.sizeBytes) { "BAK kopyası eksik yazıldı" }

            DescriptXmlGenerator.writeToDirectory(
                directory = targetFolder,
                archiveInfo = archiveInfo,
                bakFileName = DEFAULT_BAK_NAME,
                appVersionName = versionName,
                storageLeftBytes = allBackupDir.usableSpace,
            )
            require(File(targetFolder, "descript.xml").length() > 0L) { "descript.xml yazılamadı" }

            return StagedBackup(targetFolder.name, targetFolder.absolutePath)
        } catch (error: Throwable) {
            targetFolder.deleteRecursively()
            throw error
        }
    }

    private fun stageToDocumentTree(
        context: Context,
        archiveInfo: BakArchiveInfo,
        versionName: String,
        onProgress: ((Float) -> Unit)?,
    ): StagedBackup {
        val treeUri = StoragePermissionHelper.getBackupTreeUri(context)
            ?: error("AllBackup klasörü seçilmedi")
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("AllBackup klasörüne erişilemiyor")
        require(root.exists() && root.isDirectory && root.canWrite()) {
            "AllBackup klasörünün yazma izni geçersiz"
        }

        val folderName = uniqueDocumentFolderName(root)
        val targetFolder = root.createDirectory(folderName)
            ?: error("Yedek klasörü oluşturulamadı")

        try {
            val bakDocument = targetFolder.createFile("application/octet-stream", DEFAULT_BAK_NAME)
                ?: error("BAK dosyası oluşturulamadı")
            val bakOutput = context.contentResolver.openOutputStream(bakDocument.uri, "w")
                ?: error("BAK dosyası açılamadı")
            bakOutput.use { output -> copyToWithProgress(archiveInfo.file, output, onProgress) }
            require(bakDocument.length() == 0L || bakDocument.length() == archiveInfo.sizeBytes) {
                "BAK kopyası eksik yazıldı"
            }

            val description = DescriptXmlGenerator.generate(
                archiveInfo = archiveInfo,
                bakFileName = DEFAULT_BAK_NAME,
                appVersionName = versionName,
            )
            val xmlDocument = targetFolder.createFile("text/xml", "descript.xml")
                ?: error("descript.xml oluşturulamadı")
            val xmlOutput = context.contentResolver.openOutputStream(xmlDocument.uri, "w")
                ?: error("descript.xml açılamadı")
            xmlOutput.bufferedWriter(Charsets.UTF_8).use { it.write(description) }

            return StagedBackup(folderName, "MIUI/backup/AllBackup/$folderName")
        } catch (error: Throwable) {
            targetFolder.delete()
            throw error
        }
    }

    fun deleteBackupFolder(context: Context, folder: DeviceBackupFolder): Boolean {
        folder.directory?.let { return it.exists() && it.deleteRecursively() }
        folder.documentUri?.let { uri ->
            return DocumentFile.fromSingleUri(context, uri)?.delete() == true
        }
        return false
    }

    private fun validateArchive(archiveInfo: BakArchiveInfo) {
        require(archiveInfo.packageName == PACKAGE_NAME) {
            "Yalnızca Xiaomi Temalar yedeği içe aktarılabilir"
        }
        require(archiveInfo.file.exists() && archiveInfo.file.length() == archiveInfo.sizeBytes) {
            "BAK kaynak dosyası artık kullanılamıyor"
        }
    }

    private fun uniqueFileFolder(root: File): File {
        val base = timestamp()
        var candidate = File(root, base)
        var suffix = 1
        while (candidate.exists()) candidate = File(root, "${base}_${suffix++}")
        return candidate
    }

    private fun uniqueDocumentFolderName(root: DocumentFile): String {
        val existing = root.listFiles().mapNotNull { it.name }.toHashSet()
        val base = timestamp()
        var candidate = base
        var suffix = 1
        while (candidate in existing) candidate = "${base}_${suffix++}"
        return candidate
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun copyFileWithProgress(
        source: File,
        destination: File,
        onProgress: ((Float) -> Unit)?,
    ) {
        FileOutputStream(destination).use { output ->
            copyToWithProgress(source, output, onProgress)
            output.fd.sync()
        }
    }

    private fun copyToWithProgress(
        source: File,
        output: OutputStream,
        onProgress: ((Float) -> Unit)?,
    ) {
        val totalBytes = source.length()
        var copiedBytes = 0L
        FileInputStream(source).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                copiedBytes += read
                if (totalBytes > 0L) onProgress?.invoke(copiedBytes.toFloat() / totalBytes)
            }
            output.flush()
        }
    }
}
