package com.example.ussdagent.data.store

import android.content.Context
import android.content.SharedPreferences

data class WsDiagnosticsSnapshot(
    val status: String,
    val detail: String?,
    val updatedAtMs: Long
)

class WsDiagnosticsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("WsDiagnostics", Context.MODE_PRIVATE)

    fun saveConnected() {
        prefs.edit()
            .putString("status", "CONNECTED")
            .putString("detail", null)
            .putLong("updated_at_ms", System.currentTimeMillis())
            .commit()
    }

    fun saveReconnectScheduled(reason: String, delayMs: Long, forceRefresh: Boolean) {
        prefs.edit()
            .putString("status", "RECONNECT_SCHEDULED")
            .putString(
                "detail",
                "reason=$reason, delayMs=$delayMs, forceRefresh=$forceRefresh"
            )
            .putLong("updated_at_ms", System.currentTimeMillis())
            .commit()
    }

    fun saveClosed(code: Int, reason: String) {
        prefs.edit()
            .putString("status", "CLOSED")
            .putString("detail", "code=$code, reason=$reason")
            .putLong("updated_at_ms", System.currentTimeMillis())
            .commit()
    }

    fun saveFailed(message: String?) {
        prefs.edit()
            .putString("status", "FAILED")
            .putString("detail", message)
            .putLong("updated_at_ms", System.currentTimeMillis())
            .commit()
    }

    fun get(): WsDiagnosticsSnapshot? {
        val status = prefs.getString("status", null) ?: return null
        val detail = prefs.getString("detail", null)
        val updatedAtMs = prefs.getLong("updated_at_ms", 0L)
        return WsDiagnosticsSnapshot(
            status = status,
            detail = detail,
            updatedAtMs = updatedAtMs
        )
    }
}