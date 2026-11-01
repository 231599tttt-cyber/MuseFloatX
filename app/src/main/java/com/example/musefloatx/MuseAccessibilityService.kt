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
        // 用户要求不做自动识别：以前每次 X 内容变化都自动全屏抓取，
        // 结果永远只读到评论区，所以这里不再自动抓取。
        // 识别只走手动框选：框选时直接调 captureRegion 读框内文字。
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

    /**
     * 帖子正文提取规则（结构过滤，不靠删关键词，也不动系统）：
     * - 只认 text，不认按钮、图标的 contentDescription。
     *   帖子正文在 TextView 的 text 里；“让 Grok 解释此帖子”“已验证”
     *   “发帖选项”这类是图标、按钮的 contentDescription，只在无障碍
     *   描述里出现，屏幕上并没有这行字，绝不能当成正文发给 AI。
     * - 按节点结构区分：Button / ImageButton / ImageView 等控件节点
     *   一律跳过，哪怕它上面有文字（那是按钮标签，不是正文）。
     * - 只收叶子节点：容器节点的 text 会和子节点重复，叶子 TextView
     *   才是真正的正文行。
     * - contentDescription 只有一种情况才收：节点本身就是 TextView、
     *   又是叶子、又没有 text——即自定义文本控件只能靠描述传文字时。
     *   ImageView 徽标、ImageButton 图标的描述永远不收。
     * 这样 X 以后换按钮文案也不会漏网，因为判断依据是“它是不是
     * 按钮、图标”，不是“它叫什么名字”。
     */
    private fun postTextOf(node: AccessibilityNodeInfo): String? {
        if (node.isPassword) return null
        val className = node.className?.toString() ?: ""
        val isControl = className.endsWith("Button") ||
            className.endsWith("ImageView") ||
            className.endsWith("CheckBox") ||
            className.endsWith("RadioButton") ||
            className.endsWith("Switch") ||
            className.endsWith("SeekBar") ||
            className.endsWith("Spinner")
        if (isControl) return null
        if (node.childCount != 0) return null
        val text = node.text?.toString()?.trim() ?: ""
        if (text.length >= 2 && text != "MuseFloatX") return text
        // 只有 TextView 叶子、且没有 text 时，才退而求其次收描述。
        if (className.endsWith("TextView")) {
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            if (desc.length >= 2) return desc
        }
        return null
    }

    private fun collectRegion(node: AccessibilityNodeInfo?, rect: Rect, out: MutableList<Triple<Int, Int, String>>, depth: Int) {
        if (node == null || depth > 30) return
        try {
            if (!node.isPassword) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                // 严格框选：只认中心点落在框内的节点，框边只擦到一点的
                // 评论区节点不再算进来，否则框住主帖也会带上评论区。
                // 裁剪框可以精确调到只盖主帖，所以严格一点反而更准。
                if (rect.contains(bounds.centerX(), bounds.centerY())) {
                    val t = postTextOf(node)
                    if (t != null) out.add(Triple(bounds.top, bounds.left, t))
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
            val t = postTextOf(node)
            if (t != null) out.add(t)
            for (i in 0 until node.childCount) {
                collect(node.getChild(i), out, depth + 1)
            }
        } catch (e: Exception) {
        }
    }
}
