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
    const val BASE_URL = "http://10.47.12.157:18610"
    const val MODEL = "muse-spark"
}
