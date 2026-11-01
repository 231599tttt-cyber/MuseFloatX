package com.example.musefloatx

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "musefloat_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("musefloat_prefs", Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs.getString("base_url", DefaultEndpoint.BASE_URL) ?: DefaultEndpoint.BASE_URL
        set(v) = prefs.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", DefaultEndpoint.MODEL) ?: DefaultEndpoint.MODEL
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    var style: String
        get() = prefs.getString("style", "自然") ?: "自然"
        set(v) = prefs.edit().putString("style", v).apply()
}
