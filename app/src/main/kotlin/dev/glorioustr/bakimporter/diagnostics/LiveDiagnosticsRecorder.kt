package dev.glorioustr.bakimporter.diagnostics

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContentValues
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.accessibility.AccessibilityManager
import dev.glorioustr.bakimporter.BuildConfig
import dev.glorioustr.bakimporter.util.ThemeManagerDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticUiState(
    val sessionId: String,
    val startedAt: String,
    val phase: String = "Hazır",
    val externalCaptureEnabled: Boolean = false,
    val attachedEvidenceName: String? = null,
    val recentEvents: List<String> = emptyList(),
)

/**
 * Always-on, rootless-safe event journal for BAK import, MTZ translation and restore flows.
 * It records this app's own decisions and public device/package state. Android does not allow a
 * normal app to read Xiaomi Themes' private logs, UI, account or toast contents.
 */
class LiveDiagnosticsRecorder private constructor(private val context: Context) {
    private val root = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val exports = File(root, "exports").apply { mkdirs() }
    private val journal = File(root, "events.jsonl")
    private val preferences = context.getSharedPreferences("live-diagnostics", Context.MODE_PRIVATE)

    private var sessionId = preferences.getString(KEY_SESSION_ID, null) ?: newSessionId()
    private var startedAt = preferences.getString(KEY_STARTED_AT, null) ?: Instant.now().toString()
    private val mutableState = MutableStateFlow(
        DiagnosticUiState(
            sessionId = sessionId,
            startedAt = startedAt,
            externalCaptureEnabled = preferences.getBoolean(KEY_EXTERNAL_CAPTURE, false),
            attachedEvidenceName = preferences.getString(KEY_EVIDENCE_DISPLAY_NAME, null),
            recentEvents = readRecentEvents(sessionId),
        )
    )
    val state: StateFlow<DiagnosticUiState> = mutableState.asStateFlow()

    init {
        persistSession()
        record("recorder_ready", "Live Diagnostics hazır", critical = true)
    }

    @Synchronized
    fun startNewSession(): String {
        val previousSessionId = sessionId
        sessionId = newSessionId()
        startedAt = Instant.now().toString()
        persistSession()
        preferences.edit()
            .putBoolean(KEY_EXTERNAL_CAPTURE, true)
            .remove(KEY_EVIDENCE_DISPLAY_NAME)
            .apply()
        evidenceFiles(previousSessionId).forEach(File::delete)
        automaticScreenshots(previousSessionId).forEach(File::delete)
        mutableState.value = DiagnosticUiState(
            sessionId = sessionId,
            startedAt = startedAt,
            externalCaptureEnabled = true,
        )
        record("session_started", "Yeni uçtan uca test oturumu başlatıldı", critical = true)
        recordSystemSnapshot()
        return sessionId
    }

    @Synchronized
    fun ensureSession(): String {
        if (sessionId.isBlank()) return startNewSession()
        return sessionId
    }

    @Synchronized
    fun record(
        event: String,
        message: String,
        details: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
        critical: Boolean = false,
    ) {
        rotateJournalIfNeeded()
        val json = JSONObject().apply {
            put("timestamp", Instant.now().toString())
            put("sessionId", sessionId)
            put("event", safeText(event, 80))
            put("message", safeText(message, 800))
            put("details", JSONObject(details.filterValues { it != null }.mapValues { safeText(it.value.toString(), 800) }))
            if (error != null) {
                put("errorType", error.javaClass.name)
                put("errorMessage", safeText(error.message.orEmpty(), 1200))
            }
        }
        FileOutputStream(journal, true).use { output ->
            output.write((json.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            if (critical) output.fd.sync()
        }
        mutableState.value = mutableState.value.copy(
            phase = message.take(120),
            recentEvents = (mutableState.value.recentEvents + renderEvent(json)).takeLast(MAX_VISIBLE_EVENTS),
        )
    }

    fun recordSystemSnapshot() {
        val themes = ThemeManagerDetector.detect(context)
        val backup = packageVersion("com.miui.backup")
        val network = networkSummary()
        val storage = Environment.getExternalStorageDirectory()
        record(
            event = "system_snapshot",
            message = "Cihaz ve sistem durumu kaydedildi",
            details = mapOf(
                "appVersion" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                "manufacturer" to Build.MANUFACTURER,
                "brand" to Build.BRAND,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "product" to Build.PRODUCT,
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "securityPatch" to Build.VERSION.SECURITY_PATCH,
                "locale" to Locale.getDefault().toLanguageTag(),
                "timezone" to TimeZone.getDefault().id,
                "themesVersion" to "${themes.versionName} (${themes.versionCode})",
                "backupVersion" to backup,
                "network" to network,
                "allFilesAccess" to if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true,
                "externalFreeBytes" to storage.usableSpace,
            ),
            critical = true,
        )
    }

    fun addUserNote(note: String) {
        val clean = note.trim()
        require(clean.isNotBlank()) { "Paylaşılacak hata notunu yazın" }
        record("user_note", "Kullanıcı gözlemi eklendi", mapOf("note" to clean), critical = true)
    }

    fun markResult(success: Boolean) {
        record(
            event = if (success) "user_result_success" else "user_result_failure",
            message = if (success) "Kullanıcı tema uygulamasının başarılı olduğunu bildirdi" else "Kullanıcı tema uygulama hatası gördüğünü bildirdi",
            critical = true,
        )
    }

    fun setExternalCaptureEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_EXTERNAL_CAPTURE, enabled).commit()
        mutableState.value = mutableState.value.copy(externalCaptureEnabled = enabled)
        record(
            event = if (enabled) "external_capture_enabled" else "external_capture_disabled",
            message = if (enabled) "Temalar/Yedekleme geri bildirim kaydı açıldı" else "Temalar/Yedekleme geri bildirim kaydı durduruldu",
            critical = true,
        )
    }

    fun isExternalCaptureEnabled(): Boolean = preferences.getBoolean(KEY_EXTERNAL_CAPTURE, false)

    /**
     * The recording preference and Android's Accessibility permission are separate. System
     * updates or reinstalling the APK can revoke the service while the recording preference is
     * still on, so reports must never describe that state as a complete external capture.
     */
    fun isExternalCaptureServiceEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == DiagnosticsAccessibilityService::class.java.name
        }
    }

    fun isExternalCaptureOperational(): Boolean =
        isExternalCaptureEnabled() && isExternalCaptureServiceEnabled()

    fun recordExternalUiEvent(packageName: String, eventType: String, className: String?, text: List<String>) {
        if (!isExternalCaptureEnabled()) return
        val allowedPackage = packageName in setOf("com.android.thememanager", "com.miui.backup")
        if (!allowedPackage) return
        record(
            event = "external_ui_event",
            message = "Xiaomi sistem uygulamasından geri bildirim alındı",
            details = mapOf(
                "package" to packageName,
                "type" to eventType,
                "screen" to className,
                "text" to text.filter(String::isNotBlank).distinct().joinToString(" | ").take(1200),
            ),
            critical = eventType == "toast_or_notification",
        )
    }

    @Synchronized
    fun saveAutomaticScreenshot(bitmap: Bitmap, source: String) {
        if (!isExternalCaptureEnabled()) return
        val sessionScreenshots = automaticScreenshots(sessionId)
        if (sessionScreenshots.size >= MAX_AUTOMATIC_SCREENSHOTS) {
            sessionScreenshots.sortedBy(File::lastModified).firstOrNull()?.delete()
        }
        val safeSource = source.filter { it.isLetterOrDigit() || it == '-' }.take(60).ifBlank { "xiaomi" }
        val target = File(root, "screenshot-$sessionId-${System.currentTimeMillis()}-$safeSource.jpg")
        FileOutputStream(target).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)) { "Ekran görüntüsü sıkıştırılamadı" }
            output.fd.sync()
        }
        record(
            event = "diagnostic_screenshot_saved",
            message = "Xiaomi ekranı tanılama paketine eklendi",
            details = mapOf("source" to source, "bytes" to target.length()),
        )
    }

    fun attachEvidence(uri: Uri): String {
        val resolver = context.contentResolver
        var displayName = "ekran-kaydi"
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }
        require(declaredSize == null || declaredSize!! <= MAX_EVIDENCE_BYTES) {
            "Ekran kaydı en fazla 150 MB olabilir"
        }
        val extension = displayName.substringAfterLast('.', "bin").take(8).filter(Char::isLetterOrDigit).ifBlank { "bin" }
        val target = File(root, "evidence-$sessionId.$extension")
        val temporary = File(root, ".evidence-$sessionId.tmp")
        var copied = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_EVIDENCE_BYTES) { "Ekran kaydı en fazla 150 MB olabilir" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("Seçilen kanıt dosyası açılamadı")
            evidenceFiles(sessionId).forEach(File::delete)
            require(temporary.renameTo(target)) { "Kanıt dosyası kaydedilemedi" }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        mutableState.value = mutableState.value.copy(attachedEvidenceName = displayName.take(100))
        preferences.edit().putString(KEY_EVIDENCE_DISPLAY_NAME, displayName.take(100)).apply()
        record(
            "evidence_attached",
            "Ekran görüntüsü veya ekran kaydı tanılamaya eklendi",
            mapOf("displayName" to displayName.take(100), "bytes" to copied, "mime" to resolver.getType(uri)),
            critical = true,
        )
        return displayName
    }

    @Synchronized
    fun createExport(): File {
        if (isExternalCaptureEnabled() && !isExternalCaptureServiceEnabled()) {
            record(
                "external_capture_permission_missing",
                "Temalar/Yedekleme erişimi kapalı olduğu için sistem ekranlarının kaydı eksik olabilir",
                critical = true,
            )
        }
        record("diagnostics_export_started", "Tanılama paketi hazırlanıyor", critical = true)
        val target = File(exports, "bak-importer-diagnostics-${System.currentTimeMillis()}.zip")
        val events = sessionEventLines(sessionId)
        val report = buildReport(events)
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("report.txt"))
            zip.write(report.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("events.jsonl"))
            zip.write(events.joinToString("\n", postfix = if (events.isEmpty()) "" else "\n").toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            evidenceFile(sessionId)?.takeIf(File::isFile)?.let { evidence ->
                zip.putNextEntry(ZipEntry("evidence/${evidence.name}"))
                evidence.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            automaticScreenshots(sessionId).sortedBy(File::lastModified).forEach { screenshot ->
                zip.putNextEntry(ZipEntry("screenshots/${screenshot.name}"))
                screenshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }

    /** Writes a user-visible copy without relying on an external share target. */
    fun saveExportToDownloads(export: File): Uri {
        require(export.isFile) { "Tanılama paketi bulunamadı" }
        val displayName = export.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("İndirilenler konumu oluşturulamadı")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    export.inputStream().use { it.copyTo(output) }
                } ?: error("İndirilenler dosyası açılamadı")
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                record("diagnostics_saved_to_downloads", "Tanılama paketi İndirilenler'e kaydedildi", mapOf("name" to displayName), critical = true)
                return uri
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloads.mkdirs()
        val target = File(downloads, displayName)
        export.copyTo(target, overwrite = true)
        record("diagnostics_saved_to_downloads", "Tanılama paketi İndirilenler'e kaydedildi", mapOf("name" to displayName), critical = true)
        return Uri.fromFile(target)
    }

    private fun buildReport(events: List<String>): String = buildString {
        appendLine("HyperOS BAK Importer Live Diagnostics")
        appendLine("sessionId=$sessionId")
        appendLine("startedAt=$startedAt")
        appendLine("generatedAt=${Instant.now()}")
        appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine("locale=${Locale.getDefault().toLanguageTag()}")
        appendLine("rawUriRecorded=false")
        appendLine("privateThemeManagerLogsReadable=false")
        appendLine("externalCaptureRequested=${isExternalCaptureEnabled()}")
        appendLine("externalAccessibilityServiceEnabled=${isExternalCaptureServiceEnabled()}")
        appendLine("externalAccessibilityCaptureOperational=${isExternalCaptureOperational()}")
        appendLine("evidence=${mutableState.value.attachedEvidenceName ?: "none"}")
        appendLine("automaticScreenshots=${automaticScreenshots(sessionId).size}")
        appendLine()
        appendLine("[Network]")
        appendLine(networkSummary())
        appendLine()
        appendLine("[Packages]")
        appendLine("com.android.thememanager=${packageVersion("com.android.thememanager")}")
        appendLine("com.miui.backup=${packageVersion("com.miui.backup")}")
        appendLine()
        appendLine("[AllBackup inventory]")
        appendLine(backupInventory())
        appendLine()
        appendLine("[Session events]")
        events.forEach { line ->
            runCatching { JSONObject(line) }.getOrNull()?.let { appendLine(renderEvent(it)) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun networkSummary(): String = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@runCatching "bağlantı yok"
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching "bağlantı özellikleri okunamadı"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        }
        "transport=${transports.joinToString("+").ifBlank { "other" }} " +
            "internet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "captivePortal=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}"
    }.getOrElse { "okunamadı: ${it.javaClass.simpleName}" }

    private fun packageVersion(packageName: String): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        "${info.versionName ?: "unknown"} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
    }.getOrElse { "not-found" }

    @Suppress("DEPRECATION")
    private fun backupInventory(): String = runCatching {
        val allBackup = File(Environment.getExternalStorageDirectory(), "MIUI/backup/AllBackup")
        if (!allBackup.isDirectory) return@runCatching "AllBackup okunamadı veya bulunamadı"
        allBackup.listFiles().orEmpty().filter(File::isDirectory)
            .sortedByDescending(File::lastModified).take(12).joinToString("\n") { folder ->
                val children = folder.listFiles().orEmpty().filter(File::isFile)
                val bak = children.firstOrNull { it.extension.equals("bak", true) }
                "${folder.name}: descript=${children.any { it.name.equals("descript.xml", true) }} " +
                    "bak=${bak?.name ?: "none"} bytes=${bak?.length() ?: 0}"
            }.ifBlank { "AllBackup boş" }
    }.getOrElse { "AllBackup okunamadı: ${it.javaClass.simpleName}" }

    private fun renderEvent(json: JSONObject): String {
        val time = runCatching {
            Instant.parse(json.getString("timestamp")).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
        }.getOrDefault("--:--:--")
        val details = json.optJSONObject("details")
        val detailText = details?.keys()?.asSequence()?.joinToString(" · ") { key -> "$key=${details.optString(key)}" }.orEmpty()
        val error = json.optString("errorMessage").takeIf(String::isNotBlank)?.let { " · hata=$it" }.orEmpty()
        return "$time · ${json.optString("message")} [${json.optString("event")}]" +
            if (detailText.isBlank()) error else "\n$detailText$error"
    }

    private fun sessionEventLines(id: String): List<String> = if (!journal.isFile) emptyList() else {
        journal.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching { JSONObject(line) }.getOrNull()?.takeIf { it.optString("sessionId") == id }?.toString()
            }.toList()
        }
    }

    private fun readRecentEvents(id: String): List<String> = sessionEventLines(id).takeLast(MAX_VISIBLE_EVENTS).mapNotNull { line ->
        runCatching { renderEvent(JSONObject(line)) }.getOrNull()
    }

    private fun evidenceFiles(id: String): List<File> = root.listFiles().orEmpty().filter {
        it.isFile && it.name.startsWith("evidence-$id.")
    }

    private fun evidenceFile(id: String): File? = evidenceFiles(id).firstOrNull()

    private fun automaticScreenshots(id: String): List<File> = root.listFiles().orEmpty().filter {
        it.isFile && it.name.startsWith("screenshot-$id-") && it.extension.equals("jpg", true)
    }

    private fun rotateJournalIfNeeded() {
        if (!journal.isFile || journal.length() < MAX_JOURNAL_BYTES) return
        val archive = File(root, "events-${System.currentTimeMillis()}.jsonl")
        journal.renameTo(archive)
        root.listFiles().orEmpty().filter { it.name.startsWith("events-") && it.name.endsWith(".jsonl") }
            .sortedByDescending(File::lastModified).drop(3).forEach(File::delete)
    }

    private fun persistSession() {
        preferences.edit().putString(KEY_SESSION_ID, sessionId).putString(KEY_STARTED_AT, startedAt).commit()
    }

    private fun newSessionId(): String = UUID.randomUUID().toString().substring(0, 8)

    private fun safeText(value: String, limit: Int): String = value
        .replace(Regex("(?:content|file)://[^\\s\"<>]+"), "[URI]")
        .replace(Regex("[\\r\\n]+"), " ")
        .take(limit)

    companion object {
        private const val KEY_SESSION_ID = "session-id"
        private const val KEY_STARTED_AT = "started-at"
        private const val KEY_EVIDENCE_DISPLAY_NAME = "evidence-display-name"
        private const val KEY_EXTERNAL_CAPTURE = "external-capture"
        private const val MAX_JOURNAL_BYTES = 2L * 1024 * 1024
        private const val MAX_EVIDENCE_BYTES = 150L * 1024 * 1024
        private const val MAX_AUTOMATIC_SCREENSHOTS = 8
        private const val MAX_VISIBLE_EVENTS = 80
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

        @Volatile private var instance: LiveDiagnosticsRecorder? = null
        fun get(context: Context): LiveDiagnosticsRecorder = instance ?: synchronized(this) {
            instance ?: LiveDiagnosticsRecorder(context.applicationContext).also { instance = it }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
