package com.example.musefloatx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 只放内存的屏幕快照，不写磁盘。 */
object ScreenSnapshotHub {
    @Volatile var lastText: String = ""
    @Volatile var lastPackage: String = ""
    @Volatile var lastTimeMs: Long = 0L

    fun update(text: String, pkg: String) {
        lastText = text
        lastPackage = pkg
        lastTimeMs = System.currentTimeMillis()
    }

    fun freshText(): String? {
        if (lastText.isBlank()) return null
        if (System.currentTimeMillis() - lastTimeMs > 30_000) return null
        return lastText
    }
}

/** 截图 OCR 结果回传通道，只放内存。 */
object CaptureHub {
    private val _ocrText = MutableStateFlow<String?>(null)
    val ocrText: StateFlow<String?> = _ocrText
    // 框选截图时的裁剪框（屏幕像素坐标），CaptureActivity 认完后清掉。
    @Volatile var cropRect: android.graphics.Rect? = null
    fun post(text: String) { _ocrText.value = text }
    fun clear() { _ocrText.value = null }
}
