package com.example.musefloatx

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var baseUrl by remember { mutableStateOf(store.baseUrl) }
                var apiKey by remember { mutableStateOf(store.apiKey) }
                var model by remember { mutableStateOf(store.model) }
                var style by remember { mutableStateOf(store.style) }
                var testResult by remember { mutableStateOf("") }
                var tick by remember { mutableStateOf(0) }
                @Suppress("UNUSED_VARIABLE")
                val unusedTick = tick

                val overlayOk = Settings.canDrawOverlays(this)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("MuseFloatX", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("版本：第 36 版，装好后这里必须显示第 36 版才算新版。", fontSize = 13.sp)
                    Text("只在 X 里用的悬浮回复助手：你在 X 里复制帖子，点悬浮球，面板读剪贴板生成回复建议，你自己复制粘贴，不自动发送。不读屏、不截图、不需要无障碍。", fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))

                    Text("1. 权限", fontWeight = FontWeight.Bold)
                    Text("悬浮窗：" + if (overlayOk) "已开启" else "未开启")
                    // 简化后不再需要无障碍：主流程是剪贴板，之前那个
                    // “开无障碍”按钮是旧框选读屏流程留下的，已经去掉，
                    // 无障碍服务也不再注册，系统设置里不会再列出它。
                    Row {
                        Button(
                            onClick = {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("开悬浮窗") }
                    }
                    OutlinedButton(onClick = { tick += 1 }) { Text("刷新权限状态") }
                    Spacer(Modifier.height(16.dp))

                    Text("2. AI 服务（只需填一次）", fontWeight = FontWeight.Bold)
                    Text("这里填你自己的 OpenAI 兼容服务地址和密钥。Muse 没有公开官方 API，所以 App 里没有“Muse 一键登录”；如果你自己部署了 muse2api，就把它的地址填在这里。不要把密钥发给任何人。", fontSize = 13.sp)
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it; store.baseUrl = it },
                        label = { Text("服务地址，例如 http://你的服务器IP:18610") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it; store.apiKey = it },
                        label = { Text("API 密钥") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it; store.model = it },
                        label = { Text("模型，例如 muse-spark") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row {
                        listOf("自然", "幽默", "简短").forEach { s ->
                            if (style == s) {
                                Button(onClick = { style = s; store.style = s }, modifier = Modifier.padding(end = 8.dp)) { Text(s) }
                            } else {
                                OutlinedButton(onClick = { style = s; store.style = s }, modifier = Modifier.padding(end = 8.dp)) { Text(s) }
                            }
                        }
                    }
                    Button(onClick = {
                        testResult = "测试中…"
                        scope.launchSafe {
                            val r = ApiClient.testConnection(store.baseUrl, store.apiKey, store.model)
                            testResult = r.fold({ "测试成功：$it" }, { "测试失败：${it.message}" })
                        }
                    }) { Text("测试连接") }
                    if (testResult.isNotBlank()) Text(testResult, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))

                    Text("3. 悬浮球", fontWeight = FontWeight.Bold)
                    Row {
                        Button(onClick = {
                            // 没开悬浮窗时以前是直接启动服务然后静默闪退，点启动像没反应。
                            // 现在先检查，没开就提示并跳到设置，不再静默失败。
                            if (!Settings.canDrawOverlays(this@MainActivity)) {
                                Toast.makeText(this@MainActivity, "请先开悬浮窗权限", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                return@Button
                            }
                            val intent = Intent(this@MainActivity, FloatingService::class.java).apply { action = "START" }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                        }, modifier = Modifier.padding(end = 8.dp)) { Text("启动悬浮球") }
                        OutlinedButton(onClick = {
                            val intent = Intent(this@MainActivity, FloatingService::class.java).apply { action = "STOP" }
                            startService(intent)
                        }) { Text("停止") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("使用方法：先在 X 里复制帖子，再点悬浮球，就按上次选的风格生成一次；想换风格，点另一个风格就切换一次；同风格想换一句，点重新生成。回复都是一句话、十几二十个字，点复制后去 X 粘贴，不自动发送。没复制就点球，面板会是空的；自动读不到时，关面板、重新复制、再点球。不需要无障碍，不截图，不 OCR。", fontSize = 13.sp)
                }
            }
        }
    }
}

private fun CoroutineScope.launchSafe(block: suspend () -> Unit) {
    launch { block() }
}
