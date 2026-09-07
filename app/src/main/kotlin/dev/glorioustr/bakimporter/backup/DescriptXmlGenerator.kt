package dev.glorioustr.bakimporter.backup

import dev.glorioustr.bakimporter.model.BakArchiveInfo
import java.io.File
import kotlin.math.max

object DescriptXmlGenerator {

    /**
     * Generates a standard descript.xml manifest for MIUI / HyperOS Backup & Restore.
     */
    fun generate(
        archiveInfo: BakArchiveInfo,
        bakFileName: String = "Themes(com.android.thememanager).bak",
        appVersionName: String = "V1.0.0",
        storageLeftBytes: Long = 0L,
    ): String {
        val packageName = archiveInfo.packageName.ifBlank { "com.android.thememanager" }
        val fileSize = archiveInfo.sizeBytes
        val nowMs = System.currentTimeMillis()
        val deviceMod = try {
            android.os.Build.DEVICE?.ifBlank { "klee" } ?: "klee"
        } catch (_: Throwable) {
            "klee"
        }
        val miuiVersion = try {
            android.os.Build.VERSION.INCREMENTAL?.ifBlank { appVersionName } ?: appVersionName
        } catch (_: Throwable) {
            appVersionName
        }
        val transferredPayload = max(0L, fileSize - archiveInfo.tarOffset - 1_024L)

        return buildString {
            // Xiaomi's restore UI is strict about this element-oriented schema. In particular,
            // package must be nested under packages; attributes used by the old implementation
            // made otherwise valid backups invisible on rootless devices.
            append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
            append("<MIUI-backup>")
            append("<jsonMsg></jsonMsg>")
            append("<bakVersion>2</bakVersion>")
            append("<brState>3</brState>")
            append("<autoBackup>false</autoBackup>")
            append("<device>${escapeXml(deviceMod)}</device>")
            append("<miuiVersion>${escapeXml(miuiVersion)}</miuiVersion>")
            append("<date>$nowMs</date>")
            append("<size>$fileSize</size>")
            append("<storageLeft>${storageLeftBytes.coerceAtLeast(0L)}</storageLeft>")
            append("<supportReconnect>true</supportReconnect>")
            append("<autoRetransferCnt>0</autoRetransferCnt>")
            append("<transRealCompletedSize>0</transRealCompletedSize>")
            append("<packages><package>")
            append("<packageName>${escapeXml(packageName)}</packageName>")
            append("<feature>-1</feature>")
            append("<bakFile>${escapeXml(bakFileName)}</bakFile>")
            append("<bakType>1</bakType>")
            append("<pkgSize>$fileSize</pkgSize>")
            append("<sdSize>0</sdSize>")
            append("<state>1</state>")
            append("<completedSize>$fileSize</completedSize>")
            append("<error>0</error>")
            append("<progType>0</progType>")
            append("<bakFileSize>$fileSize</bakFileSize>")
            append("<transingCompletedSize>0</transingCompletedSize>")
            append("<transingTotalSize>$transferredPayload</transingTotalSize>")
            append("<transingSdCompletedSize>0</transingSdCompletedSize>")
            append("<sectionSize>0</sectionSize>")
            append("<sendingIndex>0</sendingIndex>")
            append("</package></packages>")
            append("<filesModifyTime />")
            append("</MIUI-backup>")
        }
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> char
                }
            )
        }
    }

    /**
     * Writes or overwrites descript.xml in the given backup directory.
     */
    fun writeToDirectory(
        directory: File,
        archiveInfo: BakArchiveInfo,
        bakFileName: String = "Themes(com.android.thememanager).bak",
        appVersionName: String = "V1.0.0",
        storageLeftBytes: Long = 0L,
    ): File {
        require(directory.exists() && directory.isDirectory) {
            "Hedef klasör mevcut değil: ${directory.absolutePath}"
        }
        val descriptFile = File(directory, "descript.xml")
        val content = generate(archiveInfo, bakFileName, appVersionName, storageLeftBytes)
        descriptFile.writeText(content, Charsets.UTF_8)
        return descriptFile
    }
}
