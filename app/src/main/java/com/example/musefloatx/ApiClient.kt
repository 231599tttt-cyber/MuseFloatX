package com.example.musefloatx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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

    fun styleInstruction(style: String): String {
        return when (style) {
            "幽默" -> "风格：轻松幽默，带一点俏皮，不要过分，不要冒犯。"
            "简短" -> "风格：简短，尽量不超过 30 字。"
            else -> "风格：自然得体，口语化，不过度正式。"
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
            val system = "你是一个回复助手。只输出回复正文，不要解释、不要加引号、不要加前后缀。" +
                "回复语言与帖子语言保持一致：中文帖子用中文，英文帖子用英文，其他语言用该语言。" +
                styleInstruction(style)
            val user = "当前应用：X\n帖子内容：\n" + postText.take(4000) + "\n\n请生成一条合适的回复。用户会自己复制粘贴，不要自动发送。"
            val body = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("temperature", 0.7)
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
            client.newCall(req).execute().use { resp ->
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
