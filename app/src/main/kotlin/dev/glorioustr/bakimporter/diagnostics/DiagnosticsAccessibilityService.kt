package dev.glorioustr.bakimporter.diagnostics

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Captures only Xiaomi Themes/Backup UI feedback during a user-enabled diagnostic session. */
class DiagnosticsAccessibilityService : AccessibilityService() {
    private var lastSignature = ""
    private var lastRecordedAt = 0L
    private var lastScreenshotAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName !in ALLOWED_PACKAGES) return
        val eventType = when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "toast_or_notification"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_changed"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content_changed"
            else -> return
        }
        val eventText = buildList {
            event.text.mapNotNullTo(this) { it?.toString() }
            event.contentDescription?.toString()?.let(::add)
        }.filter { it.isNotBlank() }.distinct()
        val visible = collectVisibleSnapshot(event.source ?: rootInActiveWindow)
        val text = (eventText + visible.text).filter(String::isNotBlank).distinct().take(MAX_TEXT_ITEMS)
        if (visible.sensitive || text.any(::isSensitiveText)) {
            mainHandler.removeCallbacksAndMessages(null)
            LiveDiagnosticsRecorder.get(this).record(
                event = "sensitive_screen_skipped",
                message = "Kilit veya parola ekranı tanılama kaydına alınmadı",
                details = mapOf("package" to packageName),
            )
            return
        }
        if (eventType == "content_changed" && text.isEmpty()) return
        val signature = "$packageName|$eventType|${event.className}|${text.joinToString()}"
        val now = android.os.SystemClock.elapsedRealtime()
        if (signature == lastSignature && now - lastRecordedAt < 1500L) return
        lastSignature = signature
        lastRecordedAt = now
        LiveDiagnosticsRecorder.get(this).recordExternalUiEvent(
            packageName = packageName,
            eventType = eventType,
            className = event.className?.toString(),
            text = text,
        )
        if (eventType != "content_changed") scheduleScreenshot(packageName, eventType)
    }

    private data class VisibleSnapshot(val text: List<String>, val sensitive: Boolean)

    private fun collectVisibleSnapshot(root: AccessibilityNodeInfo?): VisibleSnapshot {
        root ?: return VisibleSnapshot(emptyList(), false)
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var sensitive = false
        while (queue.isNotEmpty() && visited++ < MAX_NODES && result.size < MAX_TEXT_ITEMS) {
            val node = queue.removeFirst()
            if (node.isPassword) sensitive = true
            node.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(result::add)
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(result::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return VisibleSnapshot(result.toList(), sensitive)
    }

    private fun isSensitiveText(text: String): Boolean {
        val normalized = text.lowercase(java.util.Locale.ROOT)
        return SENSITIVE_MARKERS.any(normalized::contains)
    }

    private fun scheduleScreenshot(packageName: String, eventType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScreenshotAt < SCREENSHOT_INTERVAL_MS) return
        lastScreenshotAt = now
        val delay = if (eventType == "toast_or_notification") TOAST_SCREENSHOT_DELAY_MS else WINDOW_SCREENSHOT_DELAY_MS
        mainHandler.postDelayed({ captureScreenshot(packageName, eventType) }, delay)
    }

    private fun captureScreenshot(packageName: String, eventType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!LiveDiagnosticsRecorder.get(this).isExternalCaptureEnabled()) return
        val current = collectVisibleSnapshot(rootInActiveWindow)
        if (current.sensitive || current.text.any(::isSensitiveText)) {
            LiveDiagnosticsRecorder.get(this).record(
                event = "sensitive_screenshot_skipped",
                message = "Kilit veya parola ekranının görüntüsü alınmadı",
                details = mapOf("package" to packageName),
            )
            return
        }
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()
                    if (bitmap == null) return
                    try {
                        runCatching {
                            LiveDiagnosticsRecorder.get(this@DiagnosticsAccessibilityService)
                                .saveAutomaticScreenshot(bitmap, "$packageName-$eventType")
                        }.onFailure { error ->
                            LiveDiagnosticsRecorder.get(this@DiagnosticsAccessibilityService).record(
                                event = "diagnostic_screenshot_failed",
                                message = "Tanılama ekran görüntüsü kaydedilemedi",
                                error = error,
                            )
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    LiveDiagnosticsRecorder.get(this@DiagnosticsAccessibilityService).record(
                        event = "diagnostic_screenshot_failed",
                        message = "Tanılama ekran görüntüsü alınamadı",
                        details = mapOf("code" to errorCode, "package" to packageName),
                    )
                }
            },
        )
    }

    override fun onInterrupt() = Unit

    companion object {
        private val ALLOWED_PACKAGES = setOf("com.android.thememanager", "com.miui.backup")
        private const val MAX_NODES = 180
        private const val MAX_TEXT_ITEMS = 40
        private const val SCREENSHOT_INTERVAL_MS = 2200L
        private const val WINDOW_SCREENSHOT_DELAY_MS = 1800L
        private const val TOAST_SCREENSHOT_DELAY_MS = 250L
        private val SENSITIVE_MARKERS = listOf(
            "parola", "şifre", "sifre", "password", "passcode", "pin kod", "6-haneli pin",
            "ekran kilidinizi", "doğrulama kodu", "verification code", "one-time code", "验证码", "密码",
        )
    }
}
