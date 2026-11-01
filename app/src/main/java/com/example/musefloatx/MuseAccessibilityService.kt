package com.example.musefloatx

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MuseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: MuseAccessibilityService? = null
        const val X_PACKAGE = "com.twitter.android"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != X_PACKAGE) return
        captureNow()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun captureNow() {
        try {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != X_PACKAGE) return
            val lines = LinkedHashSet<String>()
            collect(root, lines, 0)
            val text = lines.joinToString("\n").take(4000)
            if (text.length >= 20) {
                ScreenSnapshotHub.update(text, X_PACKAGE)
            }
        } catch (e: Exception) {
            // 忽略单次读取失败，不中断服务
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: LinkedHashSet<String>, depth: Int) {
        if (node == null || depth > 30) return
        try {
            if (!node.isPassword) {
                val t = node.text?.toString()?.trim() ?: ""
                if (t.length >= 2 && t != "MuseFloatX") {
                    out.add(t)
                }
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                if (desc.length >= 2) out.add(desc)
            }
            for (i in 0 until node.childCount) {
                collect(node.getChild(i), out, depth + 1)
            }
        } catch (e: Exception) {
        }
    }
}
