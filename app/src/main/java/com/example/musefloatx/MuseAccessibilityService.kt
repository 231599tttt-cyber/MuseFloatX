package com.example.musefloatx

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MuseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: MuseAccessibilityService? = null
        const val X_PACKAGE = "com.twitter.android"
    }

    @Volatile private var lastCaptureMs = 0L

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

    fun captureNow(force: Boolean = false) {
        // 无障碍事件在滑动时来得非常密，每次都全树遍历会让 X 和手机都卡。
        // 自动抓取节流到 1 秒一次，手动点球、点重新识别时 force=true 不节流。
        val now = System.currentTimeMillis()
        if (!force && now - lastCaptureMs < 1000) return
        lastCaptureMs = now
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

    /**
     * 只读用户框选区域内的文字：取中心点落在框内的叶子节点，
     * 按从上到下、从左到右排序去重。主帖被评论区淹没时，
     * 用户框住主帖就能只认主帖，不读评论区。
     */
    fun captureRegion(rect: Rect): String {
        try {
            val root = rootInActiveWindow ?: return ""
            if (root.packageName?.toString() != X_PACKAGE) return ""
            val items = mutableListOf<Triple<Int, Int, String>>()
            collectRegion(root, rect, items, 0)
            val seen = LinkedHashSet<String>()
            val sb = StringBuilder()
            for ((_, _, t) in items.sortedWith(compareBy({ it.first }, { it.second }))) {
                if (seen.add(t)) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(t)
                }
            }
            return sb.toString().take(4000)
        } catch (e: Exception) {
            return ""
        }
    }

    private fun collectRegion(node: AccessibilityNodeInfo?, rect: Rect, out: MutableList<Triple<Int, Int, String>>, depth: Int) {
        if (node == null || depth > 30) return
        try {
            // 不再只收叶子节点：X 里有些帖子文字挂在带子节点的容器上，
            // 只收叶子会把主帖漏掉；全收、按位置排序去重，框内重复的片段
            // 最多让模型多看一遍，不会把主帖漏成空。
            if (!node.isPassword) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                // 容错：框和文字框相交就算，不要求中心点一定在框内，
                // 框稍微画偏一点、没贴到屏幕边也能认到主帖文字。
                if (Rect.intersects(rect, bounds) || rect.contains(bounds.centerX(), bounds.centerY())) {
                    val t = node.text?.toString()?.trim() ?: ""
                    if (t.length >= 2 && t != "MuseFloatX") out.add(Triple(bounds.top, bounds.left, t))
                    val desc = node.contentDescription?.toString()?.trim() ?: ""
                    if (desc.length >= 2) out.add(Triple(bounds.top, bounds.left, desc))
                }
            }
            for (i in 0 until node.childCount) {
                collectRegion(node.getChild(i), rect, out, depth + 1)
            }
        } catch (e: Exception) {
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
