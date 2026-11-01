package com.example.musefloatx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 当前正在跑的那一次服务端请求。服务端同一时间只跑一个生成
    // （GEN_LOCK），App 发两三个请求只会让它们在服务端排队，
    // 最后一个要等前面全部跑完，体感就是 20 多秒。
    // 所以 App 侧必须一次只发一个：新请求开始前先取消旧的这一通，
    // 服务端收到断连会释放锁，而不是让旧请求继续在后台跑完。
    @Volatile private var activeCall: Call? = null
    fun cancelActive() {
        try { activeCall?.cancel() } catch (e: Exception) { }
    }

    /**
     * 速度优先的 token 上限：回复本来就短，400 个 token 要生成很久
     * 才算“已生成”，大部分时间都在等后面用不上的字。
     * 简短 80 个 token 足够 30 字内回复；自然/幽默 180 个 token
     * 足够一条正常回复，总时长大约比 400 档少一半，流式首字不变、
     * 完整回复出得更快。帖子输入仍取前 2000 字，不截短原文。
     */
    fun maxTokensForStyle(style: String): Int {
        return if (style == "简短") 80 else 180
    }

    fun styleInstruction(style: String): String {
        return when (style) {
            "幽默" -> "幽默地"
            "简短" -> "简短地"
            else -> "自然地"
        }
    }

    suspend fun generate(
        baseUrl: String,
        apiKey: String,
        model: String,
        style: String,
        postText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("请先在设置里填写 AI 服务地址"))
            if (apiKey.isBlank()) return@withContext Result.failure(Exception("请先在设置里填写 API 密钥"))
            // 前缀已按用户要求缩到一句：你是一个回复助手，根据内容，风格回复，尽量简洁，十几二十个字。
            // 服务端会忽略 max_tokens，字数仍靠这句短提示词控制。
            val system = "你是一个回复助手。根据这个内容，" + styleInstruction(style) + "回复，尽量简洁，十几二十个字。"
            val user = "X 帖子：\n" + postText.take(2000) + "\n回一条，别自动发送。"
            val body = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("temperature", 0.7)
                put("max_tokens", maxTokensForStyle(style))
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", system) })
                    put(JSONObject().apply { put("role", "user"); put("content", user) })
                })
            }
            val req = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(req)
            activeCall = call
            try {
                call.execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}：${raw.take(500)}"))
                }
                val content = JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .opt("content")
                val text = when (content) {
                    is String -> content
                    is JSONArray -> {
                        val sb = StringBuilder()
                        for (i in 0 until content.length()) {
                            sb.append(content.optJSONObject(i)?.optString("text") ?: "")
                        }
                        sb.toString()
                    }
                    else -> ""
                }.trim()
                if (text.isBlank()) Result.failure(Exception("服务返回了空回复"))
                else Result.success(text)
                }
            } finally {
                if (activeCall == call) activeCall = null
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "请求失败"))
        }
    }

    /**
     * 流式生成：服务端边生成边用 SSE 吐字，App 每收到一片就回调，
     * 面板上先出字、再出完整回复，体感上比等全文返回快很多。
     * muse2api 的 /v1/chat/completions 支持 stream=true，chunk 格式为
     * data: {"choices":[{"delta":{"content":"..."}}]}，以 data: [DONE] 结束。
     */
    suspend fun generateStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        style: String,
        postText: String,
        onDelta: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("请先在设置里填写 AI 服务地址"))
            if (apiKey.isBlank()) return@withContext Result.failure(Exception("请先在设置里填写 API 密钥"))
            // 前缀已按用户要求缩到一句：你是一个回复助手，根据内容，风格回复，尽量简洁，十几二十个字。
            // 服务端会忽略 max_tokens，字数仍靠这句短提示词控制。
            val system = "你是一个回复助手。根据这个内容，" + styleInstruction(style) + "回复，尽量简洁，十几二十个字。"
            val user = "X 帖子：\n" + postText.take(2000) + "\n回一条，别自动发送。"
            val body = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("temperature", 0.7)
                put("max_tokens", maxTokensForStyle(style))
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", system) })
                    put(JSONObject().apply { put("role", "user"); put("content", user) })
                })
            }
            val req = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(req)
            activeCall = call
            try {
                call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string() ?: ""
                    return@withContext Result.failure(Exception("HTTP ${resp.code}：${raw.take(500)}"))
                }
                val source = resp.body?.source()
                    ?: return@withContext Result.failure(Exception("服务返回为空"))
                val sb = StringBuilder()
                while (true) {
                    val line = try { source.readUtf8Line() } catch (e: Exception) { break } ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank()) continue
                    if (data == "[DONE]") break
                    try {
                        val json = JSONObject(data)
                        val err = json.optJSONObject("error")
                        if (err != null) {
                            return@withContext Result.failure(Exception(err.optString("message", "服务返回错误")))
                        }
                        val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val piece = delta?.optString("content", "") ?: ""
                        if (piece.isNotEmpty()) {
                            sb.append(piece)
                            onDelta(piece)
                        }
                    } catch (e: Exception) {
                        // 跳过个别畸形行，不中断整段流
                    }
                }
                val text = sb.toString().trim()
                if (text.isBlank()) Result.failure(Exception("服务返回了空回复"))
                else Result.success(text)
                }
            } finally {
                if (activeCall == call) activeCall = null
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "请求失败"))
        }
    }

    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String> {
        return try {
            if (baseUrl.isBlank() || apiKey.isBlank()) {
                return Result.failure(Exception("请先填写服务地址和密钥"))
            }
            val body = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "请只回复：连接成功") })
                })
            }
            val req = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) Result.failure(Exception("HTTP ${resp.code}：${raw.take(500)}"))
                    else {
                        val text = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "").trim()
                        Result.success(if (text.isBlank()) "连接成功（返回为空）" else text)
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "连接失败"))
        }
    }
}
