package dev.glorioustr.bakimporter.backup

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import java.util.zip.ZipFile

object MtzToBakConverter {
    private const val PACKAGE_NAME = "com.android.thememanager"
    private const val TAR_BLOCK = 512
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L

    data class MtzMetadata(
        val title: String,
        val author: String,
        val version: String,
        val description: String = "",
        val sourceUiVersion: Int = 17,
        val sourceAdapterVersion: String = "3.3",
        val id: String = UUID.randomUUID().toString(),
    )

    data class BackupManifestMetadata(
        val platformSdk: Int = 35,
        val installerPackageName: String = "",
        val signatures: List<String> = emptyList(),
    )

    /**
     * Inspects an .mtz file and extracts metadata if available.
     */
    fun inspectMtz(mtzFile: File): MtzMetadata {
        var title = mtzFile.nameWithoutExtension
        var author = "Unknown"
        var version = "1.0"
        var description = ""
        var sourceUiVersion = 17
        var sourceAdapterVersion = "3.3"

        require(mtzFile.exists() && mtzFile.isFile && mtzFile.length() > 0L) { "MTZ dosyası boş veya bulunamadı" }
        ZipFile(mtzFile).use { zip ->
            require(zip.entries().asSequence().any { !it.isDirectory }) { "MTZ arşivi içerik taşımıyor" }
            val descEntry = zip.getEntry("description.xml")
            if (descEntry != null) {
                require(descEntry.size in 0..1_048_576L) { "MTZ açıklama dosyası çok büyük" }
                val xml = zip.getInputStream(descEntry).bufferedReader().readText()
                title = extractXmlTag(xml, "title") ?: title
                author = extractXmlTag(xml, "author") ?: author
                version = extractXmlTag(xml, "version") ?: version
                description = extractXmlTag(xml, "description") ?: description
                sourceUiVersion = extractXmlTag(xml, "uiVersion")
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..999 }
                    ?: sourceUiVersion
                sourceAdapterVersion = extractXmlTag(xml, "miuiAdapterVersion")
                    ?.take(32)
                    ?: sourceAdapterVersion
            }
        }

        return MtzMetadata(
            title = title,
            author = author,
            version = version,
            description = description,
            sourceUiVersion = sourceUiVersion,
            sourceAdapterVersion = sourceAdapterVersion,
        )
    }

    /**
     * Converts an .mtz theme file into a valid MIUI Backup v2 .bak archive.
     */
    fun convert(
        mtzFile: File,
        outputBakFile: File,
        targetVersionCode: Long = 200420L,
        targetVersionName: String = "V2.0.0",
        manifestMetadata: BackupManifestMetadata = BackupManifestMetadata(),
        onProgress: ((Float) -> Unit)? = null,
    ): File {
        require(targetVersionCode > 0L) { "Temalar sürüm kodu geçersiz" }
        require(manifestMetadata.signatures.isNotEmpty()) {
            "Cihazdaki Xiaomi Temalar imzası okunamadı; güvenli bir BAK üretilemiyor"
        }
        val metadata = inspectMtz(mtzFile)
        val assemblyId = UUID.randomUUID().toString()

        outputBakFile.parentFile?.mkdirs()

        FileOutputStream(outputBakFile).use { fos ->
            BufferedOutputStream(fos, 64 * 1024).use { bos ->
                // 1. Write MIUI BACKUP v2 Header
                val miuiHeader = "MIUI BACKUP\n2\n$PACKAGE_NAME Themes\n-1\n0\n"
                bos.write(miuiHeader.toByteArray(Charsets.UTF_8))

                // 2. Write ANDROID BACKUP Header (uncompressed, no encryption)
                val androidHeader = "ANDROID BACKUP\n5\n0\nnone\n"
                bos.write(androidHeader.toByteArray(Charsets.UTF_8))

                // 3. TAR Payload
                val tarWriter = TarOutputStream(bos)

                // 3a. Manifest
                val manifestContent = buildString {
                    append("1\n")
                    append("$PACKAGE_NAME\n")
                    append("$targetVersionCode\n")
                    append("${manifestMetadata.platformSdk}\n")
                    append("${manifestMetadata.installerPackageName}\n")
                    append("0\n")
                    append("${manifestMetadata.signatures.size}\n")
                    manifestMetadata.signatures.forEach { append("$it\n") }
                }.toByteArray(Charsets.UTF_8)

                tarWriter.putTarEntry("apps/$PACKAGE_NAME/_manifest", manifestContent.size.toLong())
                bos.write(manifestContent)
                tarWriter.padEntry(manifestContent.size.toLong())

                // 3b. Mirror HyperOS Theme Manager's real component layout. Each MTZ component
                // remains an independent ZIP-backed .mrc with a matching JSON .mrm descriptor.
                ZipFile(mtzFile).use { zip ->
                    val fileEntries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
                    val usedResourceCodes = mutableSetOf<String>()
                    val components = fileEntries.mapNotNull { entry ->
                        val safeName = validateEntryName(entry.name)
                        val sourceResourceCode = when {
                            safeName.substringBeforeLast('.', safeName) == "wallpaper/default_wallpaper" -> "wallpaper"
                            safeName.substringBeforeLast('.', safeName) == "wallpaper/default_lock_wallpaper" -> "lockscreen"
                            safeName == "boots/bootanimation.zip" && isZipEntry(zip, entry.name) -> "bootanimation"
                            '/' !in safeName && safeName != "description.xml" && isZipEntry(zip, entry.name) ->
                                normalizeResourceCode(safeName)
                            else -> null
                        } ?: return@mapNotNull null
                        // Android's backup restore path decoder corrupts non-ASCII TAR path
                        // segments (for example, "kapsül" becomes "kaps��l"). The root
                        // descriptor then points at a path that does not exist and ThemeManager
                        // rejects the complete theme. Keep the descriptor and restored directory
                        // on the same portable, collision-free ASCII resource code.
                        val resourceCode = uniqueBackupResourceCode(sourceResourceCode, usedResourceCodes)
                        require(entry.size in 0..MAX_ENTRY_BYTES) { "MTZ bileşeni çok büyük: $safeName" }
                        ThemeComponent(
                            sourceEntry = entry.name,
                            resourceCode = resourceCode,
                            localId = UUID.randomUUID().toString(),
                            size = entry.size,
                            sha1 = zip.getInputStream(entry).use(::sha1),
                        )
                    }
                    val previewEntries = fileEntries.filter { it.name.startsWith("preview/") && '/' in it.name }
                    val totalEntries = components.size + previewEntries.size + 2
                    var processed = 0

                    val rootMeta = buildThemeMetadata(
                        metadata = metadata,
                        localId = metadata.id,
                        assemblyId = assemblyId,
                        resourceCode = "theme",
                        hash = sha1(mtzFile),
                        size = mtzFile.length(),
                        children = components,
                    )
                    writeBytesEntry(
                        tarWriter,
                        bos,
                        "apps/$PACKAGE_NAME/ef/MIUI/theme/.data/meta/theme/${metadata.id}.mrm",
                        rootMeta,
                    )
                    tarWriter.putTarEntry(
                        "apps/$PACKAGE_NAME/ef/MIUI/theme/.data/content/theme/${metadata.id}.mrc",
                        0,
                    )
                    processed += 2
                    onProgress?.invoke(processed.toFloat() / totalEntries.coerceAtLeast(1))

                    for (component in components) {
                        val componentMeta = buildThemeMetadata(
                            metadata = metadata,
                            localId = component.localId,
                            assemblyId = assemblyId,
                            resourceCode = component.resourceCode,
                            hash = component.sha1,
                            size = component.size,
                            parentThemeId = metadata.id,
                        )
                        writeBytesEntry(
                            tarWriter,
                            bos,
                            "apps/$PACKAGE_NAME/ef/MIUI/theme/.data/meta/${component.resourceCode}/${component.localId}.mrm",
                            componentMeta,
                        )
                        val contentPath = "apps/$PACKAGE_NAME/ef/MIUI/theme/.data/content/${component.resourceCode}/${component.localId}.mrc"
                        val entry = zip.getEntry(component.sourceEntry) ?: error("MTZ bileşeni kayboldu")
                        tarWriter.putTarEntry(contentPath, component.size)
                        zip.getInputStream(entry).use { copyChecked(it, bos, component.size, component.sourceEntry) }
                        tarWriter.padEntry(component.size)
                        processed++
                        onProgress?.invoke(processed.toFloat() / totalEntries.coerceAtLeast(1))
                    }

                    for (preview in previewEntries) {
                        val previewName = validateEntryName(preview.name).removePrefix("preview/")
                        if (previewName.isBlank()) continue
                        val previewPath = "apps/$PACKAGE_NAME/ef/MIUI/theme/.data/preview/theme/${metadata.id}/$previewName"
                        tarWriter.putTarEntry(previewPath, preview.size)
                        zip.getInputStream(preview).use { copyChecked(it, bos, preview.size, preview.name) }
                        tarWriter.padEntry(preview.size)
                        processed++
                        onProgress?.invoke(processed.toFloat() / totalEntries.coerceAtLeast(1))
                    }
                }

                // Also store the entire .mtz in ef/ (external files: /storage/emulated/0/Android/data/...)
                val rawMtzSize = mtzFile.length()
                val rawMtzPath = "apps/$PACKAGE_NAME/ef/MIUI/theme/${safeThemeFileName(metadata.title)}"
                tarWriter.putTarEntry(rawMtzPath, rawMtzSize)
                FileInputStream(mtzFile).use { fis ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        bos.write(buffer, 0, read)
                    }
                }
                tarWriter.padEntry(rawMtzSize)

                // 3c. Two 512-byte zero blocks to terminate TAR
                bos.write(ByteArray(TAR_BLOCK * 2))
                bos.flush()
            }
        }

        return outputBakFile
    }

    private data class ThemeComponent(
        val sourceEntry: String,
        val resourceCode: String,
        val localId: String,
        val size: Long,
        val sha1: String,
    )

    private fun normalizeResourceCode(entryName: String): String = when (entryName) {
        "lockscreen" -> "lockstyle"
        "com.android.systemui" -> "statusbar"
        "com.miui.home" -> "launcher"
        "com.android.contacts" -> "contact"
        "com.android.mms" -> "mms"
        else -> entryName
    }

    internal fun backupSafeResourceCode(resourceCode: String): String {
        val decomposed = Normalizer.normalize(resourceCode, Normalizer.Form.NFKD)
        val ascii = buildString(decomposed.length) {
            decomposed.forEach { char ->
                when {
                    Character.getType(char) == Character.NON_SPACING_MARK.toInt() -> Unit
                    char.code in 0x21..0x7e && (char.isLetterOrDigit() || char in "._-" ) -> append(char)
                    else -> append('_')
                }
            }
        }.replace(Regex("_+"), "_").trim('_', '.')

        val fallback = ascii.ifBlank { "custom_${sha1(resourceCode.byteInputStream()).take(12)}" }
        return if (fallback.length <= 64) {
            fallback
        } else {
            "${fallback.take(48).trimEnd('_', '.')}_${sha1(resourceCode.byteInputStream()).take(12)}"
        }
    }

    private fun uniqueBackupResourceCode(resourceCode: String, used: MutableSet<String>): String {
        val base = backupSafeResourceCode(resourceCode)
        if (used.add(base)) return base

        val hash = sha1(resourceCode.byteInputStream()).take(12)
        var candidate = "${base.take(48).trimEnd('_', '.')}_$hash"
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "${base.take(44).trimEnd('_', '.')}_${hash}_$suffix"
            suffix++
        }
        return candidate
    }

    private fun isZipEntry(zip: ZipFile, name: String): Boolean {
        val entry = zip.getEntry(name) ?: return false
        return zip.getInputStream(entry).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == magic.size && magic.contentEquals(byteArrayOf(80, 75, 3, 4))
        }
    }

    private fun buildThemeMetadata(
        metadata: MtzMetadata,
        localId: String,
        assemblyId: String,
        resourceCode: String,
        hash: String,
        size: Long,
        parentThemeId: String? = null,
        children: List<ThemeComponent> = emptyList(),
    ): ByteArray = buildString {
        append("{\n")
        append("  \"localId\": ${json(localId)},\n")
        append("  \"onlineId\": null,\n")
        append("  \"assemblyId\": ${json(assemblyId)},\n")
        append("  \"productId\": null,\n")
        append("  \"hash\": ${json(hash)},\n")
        // Xiaomi's own HyperOS backup metadata normalizes imported MTZ versions to
        // platform 17 / adapter 3.3, including themes whose source uiVersion is 120.
        append("  \"platform\": 17,\n")
        append("  \"size\": $size,\n")
        append("  \"updatedTime\": 0,\n")
        append("  \"version\": ${json(metadata.version)},\n")
        append("  \"authors\": { \"fallback\": ${json(metadata.author)} },\n")
        append("  \"designers\": { \"fallback\": ${json(metadata.author)} },\n")
        append("  \"titles\": { \"fallback\": ${json(metadata.title)} },\n")
        append("  \"descriptions\": { \"fallback\": ${json(metadata.description)} },\n")
        append("  \"builtInThumbnails\": { \"fallback\": [] },\n")
        append("  \"builtInPreviews\": { \"fallback\": [] },\n")
        append("  \"thumbnails\": [],\n")
        append("  \"previews\": [],\n")
        append("  \"parentResources\": [")
        if (parentThemeId != null) {
            append("{ \"localId\": ${json(parentThemeId)}, \"resourceCode\": \"theme\", \"extraMeta\": {} }")
        }
        append("],\n")
        append("  \"subResources\": [")
        children.forEachIndexed { index, child ->
            if (index > 0) append(',')
            append("{ \"localId\": ${json(child.localId)}, \"resourceCode\": ${json(child.resourceCode)}, \"extraMeta\": {} }")
        }
        append("],\n")
        append("  \"extraMeta\": {},\n")
        append("  \"resourceCode\": ${json(resourceCode)},\n")
        append("  \"price\": 0,\n")
        append("  \"isBackUpVersion\": true,\n")
        append("  \"themeType\": 0,\n")
        append("  \"miuiAdapterVersion\": \"3.3\"\n")
        append('}')
    }.toByteArray(Charsets.UTF_8)

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"', '\\' -> { append('\\'); append(char) }
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001f' -> append("\\u${char.code.toString(16).padStart(4, '0')}")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun writeBytesEntry(tar: TarOutputStream, output: OutputStream, path: String, bytes: ByteArray) {
        tar.putTarEntry(path, bytes.size.toLong())
        output.write(bytes)
        tar.padEntry(bytes.size.toLong())
    }

    private fun copyChecked(input: java.io.InputStream, output: OutputStream, expected: Long, name: String) {
        val buffer = ByteArray(32 * 1024)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            written += read
            require(written <= expected) { "MTZ bileşeni bildirilen boyutu aşıyor: $name" }
            output.write(buffer, 0, read)
        }
        require(written == expected) { "MTZ bileşeni eksik okundu: $name" }
    }

    private fun sha1(file: File): String = FileInputStream(file).use(::sha1)

    private fun sha1(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateEntryName(name: String): String {
        val normalized = name.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && '\u0000' !in normalized) {
            "MTZ geçersiz dosya yolu içeriyor"
        }
        require(normalized.split('/').none { it == ".." || it.isBlank() }) {
            "MTZ güvenli olmayan dosya yolu içeriyor: $name"
        }
        return normalized
    }

    private fun safeThemeFileName(title: String): String {
        val stem = title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .ifBlank { "translated_theme" }
            .take(60)
        return "$stem.mtz"
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start == -1) return null
        val end = xml.indexOf(close, start + open.length)
        if (end == -1) return null
        return xml.substring(start + open.length, end)
            .trim()
            .removeSurrounding("<![CDATA[", "]]>")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /**
     * Minimal helper for writing standard POSIX USTAR TAR headers.
     */
    private class TarOutputStream(private val out: OutputStream) {
        fun putTarEntry(name: String, size: Long, mode: Int = 0b110100100) {
            val header = ByteArray(TAR_BLOCK)

            writePath(header, name)

            // Mode (8 bytes)
            writeOctal(header, 100, 8, mode.toLong())
            // Xiaomi's system-backup TAR entries are emitted as the Android system user.
            writeOctal(header, 108, 8, 1000L)
            writeOctal(header, 116, 8, 1000L)
            // Size (12 bytes)
            writeOctal(header, 124, 12, size)
            // Mod time (12 bytes)
            writeOctal(header, 136, 12, System.currentTimeMillis() / 1000L)
            // Typeflag ('0' for normal file)
            header[156] = '0'.code.toByte()
            // Magic "ustar\0" (6 bytes)
            System.arraycopy("ustar\u0000".toByteArray(Charsets.US_ASCII), 0, header, 257, 6)
            // Version "00" (2 bytes)
            System.arraycopy("00".toByteArray(Charsets.US_ASCII), 0, header, 263, 2)

            // Checksum (8 bytes at 148): sum of all header bytes with checksum space as spaces
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var sum = 0L
            for (b in header) sum += (b.toInt() and 0xFF)
            writeOctal(header, 148, 7, sum)
            header[155] = ' '.code.toByte()

            out.write(header)
        }

        private fun writePath(header: ByteArray, name: String) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            if (nameBytes.size <= 100) {
                System.arraycopy(nameBytes, 0, header, 0, nameBytes.size)
                return
            }

            val split = name.indices.reversed().firstOrNull { index ->
                name[index] == '/' &&
                    name.substring(0, index).toByteArray(Charsets.UTF_8).size <= 155 &&
                    name.substring(index + 1).toByteArray(Charsets.UTF_8).size <= 100
            } ?: error("TAR yolu USTAR sınırını aşıyor: $name")
            val prefix = name.substring(0, split).toByteArray(Charsets.UTF_8)
            val baseName = name.substring(split + 1).toByteArray(Charsets.UTF_8)
            System.arraycopy(prefix, 0, header, 345, prefix.size)
            System.arraycopy(baseName, 0, header, 0, baseName.size)
        }

        fun padEntry(size: Long) {
            val remainder = (size % TAR_BLOCK).toInt()
            if (remainder > 0) {
                out.write(ByteArray(TAR_BLOCK - remainder))
            }
        }

        private fun writeOctal(buf: ByteArray, offset: Int, length: Int, value: Long) {
            val s = java.lang.Long.toOctalString(value)
            val pad = length - 1 - s.length
            var pos = offset
            for (i in 0 until pad) {
                buf[pos++] = '0'.code.toByte()
            }
            for (i in s.indices) {
                buf[pos++] = s[i].code.toByte()
            }
            if (pos < offset + length) {
                buf[pos] = 0.toByte()
            }
        }
    }
}
