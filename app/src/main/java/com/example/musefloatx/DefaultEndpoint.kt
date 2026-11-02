package com.example.musefloatx

/**
 * 编译期默认服务端点。
 *
 * Windows 电脑 + 同 WiFi 路线：把电脑的局域网 IP 填进来后重新编译，
 * App 首次打开就会默认用这个地址和模型，用户只需要在 App 里填一次密钥。
 *
 * 填法示例：http://192.168.1.10:18610（不要带末尾斜杠，不要填成 localhost，
 * localhost 在手机上指的是手机自己，不是电脑）。
 *
 * 留空表示没有默认地址，App 里需要手动填服务地址。
 */
object DefaultEndpoint {
    // 公开版占位符：请改成你自己服务器的地址再编译，或直接在 App 里填。
    // muse2api 默认端口 18610，Ollama 默认端口 11434，不要填 localhost。
    const val BASE_URL = "http://YOUR_SERVER_IP:18610"
    const val MODEL = "muse-spark"
}
