package dev.glorioustr.bakimporter.backup

import dev.glorioustr.bakimporter.model.BakArchiveInfo
import java.io.File
import java.io.RandomAccessFile

object BakInspector {
    private const val PACKAGE_NAME = "com.android.thememanager"
    private const val MIUI_HEADER = "MIUI BACKUP\n2\n"
    private const val ANDROID_HEADER = "ANDROID BACKUP\n5\n0\nnone\n"
    private const val HEADER_SEARCH_BYTES = 4_096
    private const val TAR_BLOCK = 512
    private const val MAX_BAK_BYTES = 2L * 1024L * 1024L * 1024L // 2 GB
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_ENTRIES = 30_000

    fun inspect(file: File): BakArchiveInfo {
        require(file.exists() && file.isFile) { "Dosya bulunamadı: ${file.name}" }
        require(file.length() in 76..MAX_BAK_BYTES) { "Geçersiz dosya boyutu: ${file.length()} byte" }

        RandomAccessFile(file, "r").use { stream ->
            val header = ByteArray(HEADER_SEARCH_BYTES)
            val read = stream.read(header)
            require(read > 0) { "BAK başlığı okunamadı" }

            val prefix = String(header, 0, read, Charsets.UTF_8)
            require(prefix.startsWith(MIUI_HEADER)) {
                "Bu dosya geçerli bir MIUI Backup v2 arşivi değil"
            }

            // MIUI prepends five lines to an ordinary uncompressed Android Backup stream.
            val lines = prefix.lineSequence().take(6).toList()
            val packageLine = lines.getOrNull(2).orEmpty()
            val archivePackage = packageLine.substringBefore(' ').trim()
            val appDisplayName = packageLine.substringAfter(' ', "Themes").trim().ifBlank { "Themes" }
            require(archivePackage == PACKAGE_NAME) {
                "Bu BAK Xiaomi Temalar paketine ait değil: '$archivePackage'"
            }
            require(lines.getOrNull(3) == "-1" && lines.getOrNull(4) == "0") {
                "MIUI BAK başlığı eksik veya bozuk"
            }

            val markerIndex = prefix.indexOf(ANDROID_HEADER)
            require(markerIndex >= 0) { "BAK Android arşiv başlığı (ANDROID BACKUP) bulunamadı" }

            val offset = (markerIndex + ANDROID_HEADER.length).toLong()
            require(offset in 1 until HEADER_SEARCH_BYTES) { "BAK veri başlangıç noktası geçersiz: $offset" }

            stream.seek(offset)
            val firstHeader = ByteArray(TAR_BLOCK)
            require(stream.read(firstHeader) == TAR_BLOCK) { "BAK tar başlığı okunamadı" }

            val firstName = firstHeader.readTarPath()
            require(firstName == "apps/$archivePackage/_manifest") {
                "BAK beklenen manifest dosyasını içermiyor: '$firstName'"
            }

            val manifestSize = firstHeader.readOctal(124, 12)
            require(manifestSize in 1..16_384) { "Manifest boyutu geçersiz: $manifestSize" }

            val manifestBytes = ByteArray(manifestSize.toInt())
            require(stream.read(manifestBytes) == manifestBytes.size) { "Manifest verisi eksik" }

            val manifestLines = String(manifestBytes, Charsets.UTF_8).lineSequence().toList()
            require(manifestLines.getOrNull(0) == "1") { "BAK manifest sürümü desteklenmiyor" }
            val manifestPackage = manifestLines.getOrNull(1).orEmpty()
            require(manifestPackage == PACKAGE_NAME) { "BAK manifest paket kimliği uyuşmuyor" }
            val versionCode = manifestLines.getOrNull(2)?.trim()?.toLongOrNull()
                ?: error("BAK Temalar sürüm kodu okunamadı")
            require(versionCode > 0L) { "BAK Temalar sürüm kodu geçersiz" }
            val signatureCount = manifestLines.getOrNull(6)?.trim()?.toIntOrNull()
                ?: error("BAK imza bilgisi okunamadı")
            require(signatureCount >= 0 && manifestLines.size >= 7 + signatureCount) {
                "BAK imza bilgisi eksik"
            }

            val payload = collectEntries(stream, offset, archivePackage)
            require(payload.entryCount > 1) { "BAK arşivi Tema verisi içermiyor" }

            return BakArchiveInfo(
                file = file,
                displayName = file.name,
                sizeBytes = file.length(),
                backupVersionCode = versionCode,
                appDisplayName = appDisplayName,
                packageName = manifestPackage,
                tarOffset = offset,
                entryCount = payload.entryCount,
                entryNames = payload.sampleNames,
                rightsFileCount = payload.rightsFileCount,
                importedThemeRecordCount = payload.importedThemeRecordCount,
            )
        }
    }

    private data class PayloadSummary(
        val entryCount: Int,
        val sampleNames: List<String>,
        val rightsFileCount: Int,
        val importedThemeRecordCount: Int,
    )

    private fun collectEntries(
        stream: RandomAccessFile,
        offset: Long,
        packageName: String,
    ): PayloadSummary {
        stream.seek(offset)
        var count = 0
        var rightsFileCount = 0
        var importedThemeRecordCount = 0
        val sampleNames = mutableListOf<String>()
        val block = ByteArray(TAR_BLOCK)

        while (count < MAX_ENTRIES && stream.read(block) == TAR_BLOCK) {
            val name = block.readTarPath()
            if (name.isBlank()) break

            require(name.startsWith("apps/$packageName/") && !name.contains("..")) {
                "BAK güvenli olmayan bir yol içeriyor: '$name'"
            }
            if (sampleNames.size < 30) {
                sampleNames.add(name.removePrefix("apps/$packageName/"))
            }
            if (name.contains("/MIUI/theme/.data/rights/") && name.endsWith(".mra")) {
                rightsFileCount++
            }
            if (name.contains("/MIUI/theme/.data/import/theme/") && name.endsWith(".mrm")) {
                importedThemeRecordCount++
            }

            val size = block.readOctal(124, 12)
            require(size in 0..MAX_ENTRY_BYTES) { "BAK içerik boyutu sınırı aşıldı" }
            val blocks = (size + TAR_BLOCK - 1) / TAR_BLOCK
            val next = stream.filePointer + blocks * TAR_BLOCK
            require(next <= stream.length()) { "BAK girdisi dosya sınırını aşıyor" }
            stream.seek(next)
            count++
        }

        require(count < MAX_ENTRIES) { "BAK çok fazla girdi içeriyor" }

        return PayloadSummary(
            entryCount = count,
            sampleNames = sampleNames,
            rightsFileCount = rightsFileCount,
            importedThemeRecordCount = importedThemeRecordCount,
        )
    }

    private fun ByteArray.readText(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.UTF_8).trimEnd('\u0000')

    private fun ByteArray.readTarPath(): String {
        val name = readText(0, 100)
        val prefix = readText(345, 155)
        return if (prefix.isBlank()) name else "$prefix/$name"
    }

    private fun ByteArray.readOctal(offset: Int, length: Int): Long {
        val value = String(this, offset, length, Charsets.US_ASCII).trimEnd('\u0000').trim()
        return if (value.isBlank()) 0 else value.toLongOrNull(8)
            ?: error("Geçersiz TAR boyut alanı")
    }
}
