package dev.glorioustr.bakimporter.backup

import dev.glorioustr.bakimporter.translation.ThemeLanguageTool
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Rewrites only translatable theme resources inside an existing MIUI Theme Manager BAK. */
class BakThemeTranslator(
    private val languageTool: ThemeLanguageTool = ThemeLanguageTool(),
) {
    data class Result(
        val outputFile: File,
        val translatedNodes: Int,
        val changedFiles: List<String>,
        val scannedComponents: Int,
        val professionalTranslatedTexts: Int = 0,
        val professionalWarnings: List<String> = emptyList(),
    )

    private data class Candidate(
        val tarIndex: Int,
        val tarPath: String,
        val wrapperPath: String,
    )

    private data class Integrity(val sha1: String, val size: Long)

    fun translate(
        sourceBak: File,
        outputBak: File,
        locale: Locale = Locale.getDefault(),
        onTranslatedText: ((Int) -> Unit)? = null,
    ): Result {
        require(sourceBak.canonicalPath != outputBak.canonicalPath) { "Çeviri çıktısı kaynak BAK'tan farklı olmalı" }
        val archive = BakInspector.inspect(sourceBak)
        outputBak.parentFile?.mkdirs()
        val wrapperInput = File.createTempFile("bak-theme-input-", ".mtz", outputBak.parentFile)
        val wrapperOutput = File.createTempFile("bak-theme-output-", ".mtz", outputBak.parentFile)
        try {
            val candidates = buildTranslationWrapper(sourceBak, archive.tarOffset, wrapperInput)
            require(candidates.isNotEmpty()) { "BAK içinde çevrilebilir tema bileşeni bulunamadı" }

            val translated = languageTool.translateThemeTextToSystemLanguage(
                source = wrapperInput,
                output = wrapperOutput,
                locale = locale,
                onTranslatedText = onTranslatedText,
            )
            rewriteBak(sourceBak, outputBak, archive.tarOffset, wrapperOutput, candidates)
            BakInspector.inspect(outputBak)
            return Result(
                outputFile = outputBak,
                translatedNodes = translated.translatedNodes,
                changedFiles = translated.changedFiles,
                scannedComponents = candidates.size,
                professionalTranslatedTexts = translated.professionalTranslatedTexts,
                professionalWarnings = translated.professionalWarnings,
            )
        } catch (error: Throwable) {
            outputBak.delete()
            throw error
        } finally {
            wrapperInput.delete()
            wrapperOutput.delete()
        }
    }

    private fun buildTranslationWrapper(source: File, tarOffset: Long, wrapper: File): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        RandomAccessFile(source, "r").use { input ->
            ZipOutputStream(FileOutputStream(wrapper).buffered()).use { zip ->
                input.seek(tarOffset)
                val header = ByteArray(TAR_BLOCK)
                var tarIndex = 0
                var totalCandidateBytes = 0L
                while (tarIndex < MAX_ENTRIES && input.read(header) == TAR_BLOCK) {
                    val path = header.readTarPath()
                    if (path.isBlank()) break
                    val size = header.readOctal(124, 12)
                    require(size in 0..MAX_ENTRY_BYTES) { "BAK tema bileşeni çok büyük: $path" }
                    val dataOffset = input.filePointer
                    val type = header[156].toInt().toChar()
                    val wrapperSuffix = candidateSuffix(input, path, size, type)
                    if (wrapperSuffix != null) {
                        totalCandidateBytes += size
                        require(totalCandidateBytes <= MAX_TOTAL_CANDIDATE_BYTES) { "BAK tema içeriği işleme sınırını aşıyor" }
                        val wrapperPath = "entries/${tarIndex.toString().padStart(5, '0')}.$wrapperSuffix"
                        zip.putNextEntry(ZipEntry(wrapperPath))
                        input.seek(dataOffset)
                        copyExactly(input, zip, size)
                        zip.closeEntry()
                        candidates += Candidate(tarIndex, path, wrapperPath)
                    }
                    input.seek(dataOffset + padded(size))
                    tarIndex++
                }
                require(tarIndex < MAX_ENTRIES) { "BAK çok fazla girdi içeriyor" }
            }
        }
        return candidates
    }

    private fun candidateSuffix(input: RandomAccessFile, path: String, size: Long, type: Char): String? {
        if (type != '0' && type != '\u0000') return null
        if (size <= 0L || !path.startsWith(THEME_PACKAGE_PREFIX) || !path.contains("/MIUI/theme/")) return null
        val lower = path.lowercase(Locale.ROOT)
        if (lower.endsWith(".xml")) return "xml"
        if (lower.endsWith(".json") || lower.endsWith(".mrm")) return "json"
        val position = input.filePointer
        val magic = ByteArray(4)
        val read = input.read(magic)
        input.seek(position)
        return if (read == 4 && magic.contentEquals(ZIP_MAGIC)) "zip" else null
    }

    private fun rewriteBak(
        source: File,
        output: File,
        tarOffset: Long,
        translatedWrapper: File,
        candidates: List<Candidate>,
    ) {
        val byIndex = candidates.associateBy(Candidate::tarIndex)
        ZipFile(translatedWrapper).use { translatedZip ->
            val integrity = candidates.mapNotNull { candidate ->
                val key = contentKey(candidate.tarPath) ?: return@mapNotNull null
                val entry = translatedZip.getEntry(candidate.wrapperPath) ?: return@mapNotNull null
                key to Integrity(sha1(translatedZip.getInputStream(entry)), entry.size)
            }.toMap()

            RandomAccessFile(source, "r").use { input ->
                BufferedOutputStream(FileOutputStream(output), 64 * 1024).use { out ->
                    input.seek(0)
                    copyExactly(input, out, tarOffset)
                    val header = ByteArray(TAR_BLOCK)
                    var tarIndex = 0
                    while (input.read(header) == TAR_BLOCK) {
                        val path = header.readTarPath()
                        if (path.isBlank()) {
                            out.write(header)
                            copyExactly(input, out, input.length() - input.filePointer)
                            break
                        }
                        val oldSize = header.readOctal(124, 12)
                        val oldDataOffset = input.filePointer
                        val candidate = byIndex[tarIndex]
                        if (candidate == null) {
                            out.write(header)
                            copyExactly(input, out, padded(oldSize))
                        } else {
                            val entry = translatedZip.getEntry(candidate.wrapperPath)
                                ?: error("Çevrilen tema bileşeni bulunamadı: ${candidate.tarPath}")
                            val metadataBytes = metadataKey(candidate.tarPath)?.let { key ->
                                integrity[key]?.let { value ->
                                    translatedZip.getInputStream(entry).use { stream ->
                                        patchMetadata(stream.readBytesBounded(MAX_METADATA_BYTES), value)
                                    }
                                }
                            }
                            val newSize = metadataBytes?.size?.toLong() ?: entry.size
                            val updatedHeader = header.copyOf().also { it.updateSizeAndChecksum(newSize) }
                            out.write(updatedHeader)
                            if (metadataBytes != null) {
                                out.write(metadataBytes)
                            } else {
                                translatedZip.getInputStream(entry).use { stream -> stream.copyTo(out) }
                            }
                            writePadding(out, newSize)
                            input.seek(oldDataOffset + padded(oldSize))
                        }
                        tarIndex++
                    }
                }
            }
        }
    }

    private fun contentKey(path: String): String? = CONTENT_PATH.matchEntire(path)?.let {
        "${it.groupValues[1]}/${it.groupValues[2]}"
    }

    private fun metadataKey(path: String): String? = META_PATH.matchEntire(path)?.let {
        "${it.groupValues[1]}/${it.groupValues[2]}"
    }

    private fun patchMetadata(bytes: ByteArray, integrity: Integrity): ByteArray {
        val original = bytes.toString(Charsets.UTF_8)
        if (!original.trimStart().startsWith("{")) return bytes
        var updated = original
        HASH_FIELD.find(updated)?.let { match ->
            updated = updated.replaceRange(
                match.range,
                match.groupValues[1] + integrity.sha1 + match.groupValues[2],
            )
        }
        SIZE_FIELD.find(updated)?.let { match ->
            updated = updated.replaceRange(match.range, match.groupValues[1] + integrity.size)
        }
        return updated.toByteArray(Charsets.UTF_8)
    }

    private fun sha1(input: InputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val result = readBytes()
        require(result.size <= limit) { "Tema meta verisi çok büyük" }
        return result
    }

    private fun copyExactly(input: RandomAccessFile, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(32 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read > 0) { "BAK verisi beklenenden kısa" }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun padded(size: Long): Long = ((size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK

    private fun writePadding(output: OutputStream, size: Long) {
        val padding = padded(size) - size
        if (padding > 0) output.write(ByteArray(padding.toInt()))
    }

    private fun ByteArray.readText(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.UTF_8).trimEnd('\u0000')

    private fun ByteArray.readTarPath(): String {
        val name = readText(0, 100)
        val prefix = readText(345, 155)
        return if (prefix.isBlank()) name else "$prefix/$name"
    }

    private fun ByteArray.readOctal(offset: Int, length: Int): Long {
        val value = String(this, offset, length, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        return if (value.isBlank()) 0L else value.toLongOrNull(8) ?: error("Geçersiz TAR boyutu")
    }

    private fun ByteArray.updateSizeAndChecksum(size: Long) {
        writeOctal(124, 12, size)
        for (index in 148 until 156) this[index] = ' '.code.toByte()
        val checksum = sumOf { it.toInt() and 0xff }.toLong()
        writeOctal(148, 7, checksum)
        this[155] = ' '.code.toByte()
    }

    private fun ByteArray.writeOctal(offset: Int, length: Int, value: Long) {
        val encoded = value.toString(8)
        require(encoded.length < length) { "TAR sayısal alanı taştı" }
        for (index in offset until offset + length) this[index] = 0
        val start = offset + length - 1 - encoded.length
        for (index in offset until start) this[index] = '0'.code.toByte()
        encoded.toByteArray(Charsets.US_ASCII).copyInto(this, start)
    }

    companion object {
        private const val TAR_BLOCK = 512
        private const val MAX_ENTRIES = 30_000
        private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
        private const val MAX_TOTAL_CANDIDATE_BYTES = 1024L * 1024L * 1024L
        private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        private const val THEME_PACKAGE_PREFIX = "apps/com.android.thememanager/"
        private val ZIP_MAGIC = byteArrayOf(80, 75, 3, 4)
        private val CONTENT_PATH = Regex(".*/\\.data/content/(.+)/([^/]+)\\.mrc$")
        private val META_PATH = Regex(".*/\\.data/meta/(.+)/([^/]+)\\.mrm$")
        private val HASH_FIELD = Regex("(\\\"hash\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")")
        private val SIZE_FIELD = Regex("(\\\"size\\\"\\s*:\\s*)\\d+")
    }
}
