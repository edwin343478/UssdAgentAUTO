package com.example.ussdagent.data.store

import android.content.Context
import android.content.SharedPreferences

data class ActiveDispatchHint(
    val jobId: String,
    val lockToken: String,
    val createdAtMs: Long
)

class ActiveDispatchHintStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ActiveDispatchHint", Context.MODE_PRIVATE)

    fun save(jobId: String, lockToken: String) {
        prefs.edit()
            .putString("job_id", jobId)
            .putString("lock_token", lockToken)
            .putLong("created_at_ms", System.currentTimeMillis())
            .commit()
    }

    fun get(): ActiveDispatchHint? {
        val jobId = prefs.getString("job_id", null) ?: return null
        val lockToken = prefs.getString("lock_token", null) ?: return null
        val createdAtMs = prefs.getLong("created_at_ms", 0L)
        return ActiveDispatchHint(
            jobId = jobId,
            lockToken = lockToken,
            createdAtMs = createdAtMs
        )
    }

    fun clear() {
        prefs.edit()
            .remove("job_id")
            .remove("lock_token")
            .remove("created_at_ms")
            .commit()
    }
}