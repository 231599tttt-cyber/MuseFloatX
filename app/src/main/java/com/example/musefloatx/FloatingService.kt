package com.example.musefloatx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private val lifecycleOwner = ServiceLifecycleOwner()
    private var ballView: ComposeView? = null
    private var panelView: ComposeView? = null
    private var selectionView: ComposeView? = null
    private var selectionStart by mutableStateOf(Offset.Zero)
    private var selectionCurrent by mutableStateOf(Offset.Zero)
    private var selecting by mutableStateOf(false)
    private var selectionBox by mutableStateOf<Rect?>(null)
    private var selectionReturnToPanel = false
    private var ballParams: WindowManager.LayoutParams? = null
    private var ballX = 0
    private var ballY = 0
    private var screenW = 0
    private var ballSizePx = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: SettingsStore

    private var postText by mutableStateOf("")
    private var replyText by mutableStateOf("")
    private var statusText by mutableStateOf("点悬浮球后自动识别")
    private var styleState by mutableStateOf("自然")

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        styleState = store.style
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        ballSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 64f, dm).toInt()
        startForegroundNotification()
        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()
    }

    private fun attachOwners(view: ComposeView) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    private fun startForegroundNotification() {
        val channelId = "musefloatx"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "悬浮助手", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val noti = Notification.Builder(this, channelId)
            .setContentTitle("MuseFloatX 正在运行")
            .setContentText("点 X 里的悬浮球生成回复建议")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .build()
        startForeground(1, noti)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                removeViews()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> showBall()
        }
        return START_STICKY
    }

    private fun showBall() {
        if (ballView != null) return
        ballX = screenW - ballSizePx - 16
        ballY = resources.displayMetrics.heightPixels / 3
        val params = WindowManager.LayoutParams(
            ballSizePx, ballSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
        }
        ballParams = params
        val view = ComposeView(this)
        attachOwners(view)
        view.setContent {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF7C4DFF))
                    .pointerInput(Unit) {
                        // 直接跟踪手指按下到抬起：24dp 内抬起都算点按，
                        // 超过才算拖动。上一版靠拖动识别的取消回调判断点按，
                        // 在真机上并不可靠，点球经常被当成移动。
                        // 只用 PointerInputChange 最基础的字段，避免接口版本差异。
                        val tapThreshold = 24.dp.toPx()
                        awaitPointerEventScope {
                            while (true) {
                                var downId: PointerId? = null
                                while (downId == null) {
                                    val downEvent = awaitPointerEvent()
                                    val downChange = downEvent.changes.firstOrNull { !it.previousPressed && it.pressed }
                                    if (downChange != null) downId = downChange.id
                                }
                                var moved = 0f
                                var dragging = false
                                var done = false
                                while (!done) {
                                    val event = awaitPointerEvent()
                                    for (change in event.changes) {
                                        if (change.id != downId) continue
                                        if (!change.pressed && change.previousPressed) {
                                            if (!dragging && moved < tapThreshold) onBallTap() else snapToEdge()
                                            done = true
                                        } else if (change.pressed) {
                                            val delta = change.position - change.previousPosition
                                            if (delta != Offset.Zero) {
                                                change.consume()
                                                moved += abs(delta.x) + abs(delta.y)
                                                if (moved >= tapThreshold) dragging = true
                                                if (dragging) {
                                                    ballX = (ballX + delta.x.toInt()).coerceIn(0, screenW - ballSizePx)
                                                    ballY = (ballY + delta.y.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - ballSizePx)
                                                    params.x = ballX
                                                    params.y = ballY
                                                    try { wm.updateViewLayout(view, params) } catch (e: Exception) {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("AI", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        ballView = view
        wm.addView(view, params)
    }

    private fun snapToEdge() {
        ballX = if (ballX + ballSizePx / 2 < screenW / 2) 16 else screenW - ballSizePx - 16
        ballParams?.x = ballX
        ballParams?.y = ballY
        try { ballView?.let { wm.updateViewLayout(it, ballParams) } } catch (e: Exception) {}
    }

    private fun onBallTap() {
        if (selectionView != null) {
            cancelSelection()
            return
        }
        if (panelView != null) {
            removePanel()
            return
        }
        // 用户要求：点球直接跳框选，不做自动识别。
        // 自动识别永远只读到评论区，所以点球不再先出面板、自动认全屏，
        // 而是直接进框选，用户框住主帖、点确认才认、才生成。
        showSelectionOverlay()
    }

    private fun showPanel() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL：面板外的触摸还给 X，面板开着时 X 也能滑，
            // 之前没加这个，面板一开就像把屏幕盖住了，滑不动也关不掉。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        val view = ComposeView(this)
        attachOwners(view)
        view.setContent {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // 顶部固定栏：关闭按钮永远在上面，不用滑到底才能关。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI 回复助手", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { removePanel() }) { Text("关闭") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("当前帖子", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        // 只显示前 300 字预览，全文仍用于生成，面板不再被长帖子撑得滑不动。
                        Text(
                            if (postText.isBlank()) "正在识别…"
                            else if (postText.length > 300) postText.take(300) + "…（全文已用于生成）"
                            else postText,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("风格", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row {
                            listOf("自然", "幽默", "简短").forEach { s ->
                                val selected = styleState == s
                                if (selected) {
                                    Button(onClick = { setStyle(s) }, modifier = Modifier.padding(end = 8.dp)) { Text(s) }
                                } else {
                                    OutlinedButton(onClick = { setStyle(s) }, modifier = Modifier.padding(end = 8.dp)) { Text(s) }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("AI 回复", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(statusText, fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(6.dp))
                        // 不再用长按选中容器：它会和面板滑动抢手势，是滑不动、卡顿的原因之一。
                        // 复制统一走下面的“复制”按钮。
                        Text(if (replyText.isBlank()) "" else replyText, fontSize = 15.sp)
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Button(onClick = { copyReply() }, modifier = Modifier.padding(end = 8.dp)) { Text("复制") }
                            // 重新生成只对当前框选文字再生成一次，不再自动重认全屏，
                            // 否则框选的主帖会被自动识别的评论区覆盖掉。
                            OutlinedButton(onClick = { generate() }, modifier = Modifier.padding(end = 8.dp)) { Text("重新生成") }
                            OutlinedButton(onClick = { ocrFlow() }) { Text("截图识别") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            OutlinedButton(onClick = { showSelectionOverlay() }) { Text("框选识别") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("只生成建议，不会自动发送，请你自己粘贴。点一下悬浮球也能关闭面板。", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }
        panelView = view
        wm.addView(view, params)
    }

    private fun setStyle(s: String) {
        styleState = s
        store.style = s
        if (postText.length >= 5) generate()
    }

    private fun generate() {
        val text = postText
        if (text.length < 5) return
        statusText = "生成中…"
        replyText = ""
        scope.launch {
            // 先走流式：字一出来面板上就先显示，不用等全文。
            // 流式失败且一字没出时，再用非流式兜底一次。
            val streamResult = ApiClient.generateStream(store.baseUrl, store.apiKey, store.model, styleState, text) { piece ->
                replyText += piece
            }
            if (streamResult.isSuccess) {
                replyText = streamResult.getOrNull()?.trim() ?: replyText
                statusText = "已生成"
                return@launch
            }
            if (replyText.isNotBlank()) {
                statusText = "已生成（部分）"
                return@launch
            }
            val fallback = ApiClient.generate(store.baseUrl, store.apiKey, store.model, styleState, text)
            fallback.onSuccess {
                replyText = it
                statusText = "已生成"
            }.onFailure {
                statusText = "生成失败：${it.message}"
            }
        }
    }

    private fun ocrFlow() {
        statusText = "正在截图识别（本地识别，不上传）…"
        val intent = Intent(this, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        // 超时保护：20 秒还没结果就提示原因，不再一直转。
        // 之前取消录屏、没点开始时 App 收不到任何结果，就会卡在这里。
        scope.launch {
            delay(20000)
            if (statusText.startsWith("正在截图识别")) {
                statusText = "截图识别超时：请确认刚才是否点了允许录屏，没点就重新点截图识别并点开始"
            }
        }
        scope.launch {
            CaptureHub.ocrText.collectLatest { ocr ->
                if (ocr == null) return@collectLatest
                if (ocr.isBlank()) {
                    statusText = "截图识别没有认出文字：可能点了取消录屏，或图片上没有文字，请重新点截图识别并点开始"
                } else {
                    postText = ocr.take(4000)
                    statusText = "截图识别完成"
                    generate()
                }
            }
        }
    }

    private fun copyReply() {
        if (replyText.isBlank()) {
            Toast.makeText(this, "还没有可复制的回复", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("reply", replyText))
        Toast.makeText(this, "已复制，去 X 粘贴即可", Toast.LENGTH_SHORT).show()
    }

    private fun removePanel() {
        try { panelView?.let { wm.removeView(it) } } catch (e: Exception) {}
        panelView = null
    }

    /**
     * 框选识别：先收起面板，铺一层全屏透明层让用户拖一个框，
     * 松手后只读框内中心点落入的无障碍文字，不读框外评论区。
     * 框太小算取消，回到面板。
     */
    private fun showSelectionOverlay() {
        selectionReturnToPanel = (panelView != null)
        removePanel()
        if (selectionView != null) return
        selecting = false
        selectionStart = Offset.Zero
        selectionCurrent = Offset.Zero
        selectionBox = null
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val view = ComposeView(this)
        attachOwners(view)
        view.setContent {
            // 框选 v2：松手不再直接结束，框留住，等用户点“确认识别”才认；
            // 框太小、拖动取消都不直接回面板，留在框选里重框；
            // 提示条改到底部，顶部不再挡住帖子左上角，框不用贴到屏幕边，
            // 只要盖住帖子文字就行。
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    selectionBox = null
                                    selectionStart = offset
                                    selectionCurrent = offset
                                    selecting = true
                                },
                                onDragEnd = {
                                    selecting = false
                                    val left = minOf(selectionStart.x, selectionCurrent.x).toInt()
                                    val top = minOf(selectionStart.y, selectionCurrent.y).toInt()
                                    val right = maxOf(selectionStart.x, selectionCurrent.x).toInt()
                                    val bottom = maxOf(selectionStart.y, selectionCurrent.y).toInt()
                                    if (right - left < 80 || bottom - top < 80) {
                                        selectionBox = null
                                        Toast.makeText(this@FloatingService, "框太小，请重新框选", Toast.LENGTH_SHORT).show()
                                    } else {
                                        selectionBox = Rect(left, top, right, bottom)
                                    }
                                },
                                onDragCancel = { selecting = false },
                                onDrag = { change, _ ->
                                    change.consume()
                                    selectionCurrent = change.position
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val box: Rect? = if (selecting) {
                            Rect(
                                minOf(selectionStart.x, selectionCurrent.x).toInt(),
                                minOf(selectionStart.y, selectionCurrent.y).toInt(),
                                maxOf(selectionStart.x, selectionCurrent.x).toInt(),
                                maxOf(selectionStart.y, selectionCurrent.y).toInt()
                            )
                        } else selectionBox
                        if (box != null) {
                            drawRect(
                                color = Color(0xFF7C4DFF).copy(alpha = 0.15f),
                                topLeft = Offset(box.left.toFloat(), box.top.toFloat()),
                                size = Size((box.right - box.left).toFloat(), (box.bottom - box.top).toFloat())
                            )
                            drawRect(
                                color = Color(0xFF7C4DFF),
                                topLeft = Offset(box.left.toFloat(), box.top.toFloat()),
                                size = Size((box.right - box.left).toFloat(), (box.bottom - box.top).toFloat()),
                                style = Stroke(width = 6f)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectionBox == null) {
                        Text(
                            "拖动框选帖子文字，松手后框会留住，不用贴到屏幕边",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { cancelSelection() }) { Text("取消") }
                    } else {
                        Button(
                            onClick = { selectionBox?.let { finishSelection(it) } },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("确认识别") }
                        OutlinedButton(
                            onClick = { selectionBox = null },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("重新框选") }
                        OutlinedButton(onClick = { cancelSelection() }) { Text("取消") }
                    }
                }
            }
        }
        selectionView = view
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            selectionView = null
            Toast.makeText(this, "框选打开失败：${e.message}", Toast.LENGTH_SHORT).show()
            showPanel()
        }
    }

    private fun cancelSelection() {
        removeSelection()
        // 从面板里进框选、取消了就回面板；从球直接进框选、取消了就直接关，不出面板。
        if (selectionReturnToPanel) {
            statusText = "已取消框选"
            showPanel()
        }
    }

    private fun finishSelection(rect: Rect) {
        // 先认、后撤框选层：框选层是不抢焦点的，认的时候前台窗口还是 X；
        // 以前是先撤层再认，撤层瞬间前台窗口可能还没切回 X，认出来就是空的，
        // 这就是框了帖子却认不到的原因之一。
        val text = MuseAccessibilityService.instance?.captureRegion(rect) ?: ""
        removeSelection()
        if (text.isBlank()) {
            postText = ""
            statusText = "框选区域内没有读到文字，请把框画大一点，框住帖子文字"
        } else if (text.length < 5) {
            postText = text
            statusText = "框选到的文字太短，请把框画大一点"
        } else {
            postText = text
            statusText = "框选识别完成"
        }
        showPanel()
        if (postText.length >= 5) generate()
    }

    private fun removeSelection() {
        try { selectionView?.let { wm.removeView(it) } } catch (e: Exception) {}
        selectionView = null
        selecting = false
        selectionBox = null
    }

    private fun removeViews() {
        removePanel()
        removeSelection()
        try { ballView?.let { wm.removeView(it) } } catch (e: Exception) {}
        ballView = null
    }

    override fun onDestroy() {
        removeViews()
        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Service 里没有 Activity 的 Lifecycle，ComposeView 必须手动挂一个，
 * 否则点悬浮球展开面板时可能因找不到 LifecycleOwner 而闪退。
 */
private class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
