package com.example.ussdagent.data.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveAccessToken(token: String) {
        prefs.edit().putString("access_token", token).apply()
    }

    fun getAccessToken(): String? = prefs.getString("access_token", null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun saveDeviceId(deviceId: String) {
        prefs.edit().putString("device_id", deviceId).apply()
    }

    fun getDeviceId(): String? = prefs.getString("device_id", null)
    fun saveYasPin(pin: String) {
        prefs.edit().putString("yas_pin", pin).apply()
    }

    fun getYasPin(): String? = prefs.getString("yas_pin", null)

    fun saveVodacomPin(pin: String) {
        prefs.edit().putString("vodacom_pin", pin).apply()
    }

    fun getVodacomPin(): String? = prefs.getString("vodacom_pin", null)

    fun saveMpesaAssistantId(id: String) {
        prefs.edit().putString("mpesa_assistant_id", id).apply()
    }

    fun getMpesaAssistantId(): String? = prefs.getString("mpesa_assistant_id", null)

    fun hasSetup(): Boolean {
        val yasOk = !getYasPin().isNullOrBlank()
        val vodaOk = !getVodacomPin().isNullOrBlank()
        val aidOk = !getMpesaAssistantId().isNullOrBlank()
        return yasOk && vodaOk && aidOk
    }
}