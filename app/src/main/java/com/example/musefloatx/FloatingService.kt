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
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.text.SelectionContainer
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private val lifecycleOwner = ServiceLifecycleOwner()
    private var ballView: ComposeView? = null
    private var panelView: ComposeView? = null
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
                        var moved = 0f
                        detectDragGestures(
                            onDragStart = { moved = 0f },
                            onDragEnd = {
                                if (moved < 10f) {
                                    onBallTap()
                                } else {
                                    snapToEdge()
                                }
                            },
                            onDragCancel = { snapToEdge() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                moved += abs(dragAmount.x) + abs(dragAmount.y)
                                ballX = (ballX + dragAmount.x.toInt())
                                    .coerceIn(0, screenW - ballSizePx)
                                ballY = (ballY + dragAmount.y.toInt())
                                    .coerceIn(0, resources.displayMetrics.heightPixels - ballSizePx)
                                params.x = ballX
                                params.y = ballY
                                try { wm.updateViewLayout(view, params) } catch (e: Exception) {}
                            }
                        )
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
        if (panelView != null) {
            removePanel()
            return
        }
        showPanel()
        refreshPostAndGenerate()
    }

    private fun showPanel() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
                    modifier = Modifier
                        .padding(16.dp)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("当前帖子", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(if (postText.isBlank()) "正在识别…" else postText.take(800), fontSize = 13.sp)
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
                    SelectionContainer {
                        Text(if (replyText.isBlank()) "" else replyText, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Button(onClick = { copyReply() }, modifier = Modifier.padding(end = 8.dp)) { Text("复制") }
                        OutlinedButton(onClick = { refreshPostAndGenerate() }, modifier = Modifier.padding(end = 8.dp)) { Text("重新生成") }
                        OutlinedButton(onClick = { ocrFlow() }) { Text("截图识别") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedButton(onClick = { refreshPostOnly() }, modifier = Modifier.padding(end = 8.dp)) { Text("重新识别") }
                        OutlinedButton(onClick = { removePanel() }) { Text("关闭") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("只生成建议，不会自动发送，请你自己粘贴。", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
        panelView = view
        wm.addView(view, params)
    }

    private fun setStyle(s: String) {
        styleState = s
        store.style = s
        if (postText.length >= 20) generate()
    }

    private fun refreshPostOnly() {
        MuseAccessibilityService.instance?.captureNow()
        val t = ScreenSnapshotHub.freshText()
        if (!t.isNullOrBlank()) {
            postText = t
            statusText = "已识别到帖子文字"
        } else {
            statusText = "没有读到帖子文字，请在 X 里打开一条帖子，或点截图识别"
        }
    }

    private fun refreshPostAndGenerate() {
        refreshPostOnly()
        if (postText.length >= 20) generate()
        else statusText = "没有读到帖子文字，请在 X 里打开一条帖子，或点截图识别"
    }

    private fun generate() {
        val text = postText
        if (text.length < 20) return
        statusText = "生成中…"
        replyText = ""
        scope.launch {
            val r = ApiClient.generate(store.baseUrl, store.apiKey, store.model, styleState, text)
            r.onSuccess {
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
        scope.launch {
            CaptureHub.ocrText.collectLatest { ocr ->
                if (!ocr.isNullOrBlank()) {
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

    private fun removeViews() {
        removePanel()
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
