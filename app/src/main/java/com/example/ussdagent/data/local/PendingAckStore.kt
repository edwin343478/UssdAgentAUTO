package com.example.ussdagent.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

data class PendingAck(
    val id: Long,
    val jobId: String,
    val lockToken: String,
    val finalStatus: String,
    val detail: String?,
    val createdAtMs: Long
)

class PendingAckStore(context: Context) {

    private val helper = LocalDbHelper(context.applicationContext)

    fun upsertPendingAck(
        jobId: String,
        lockToken: String,
        finalStatus: String,
        detail: String? = null
    ) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("job_id", jobId)
            put("lock_token", lockToken)
            put("final_status", finalStatus)
            put("detail", detail)
            put("created_at_ms", System.currentTimeMillis())
            put("synced", 0)
            putNull("synced_at_ms")
        }

        db.insertWithOnConflict(
            "pending_acks",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun markSynced(
        jobId: String,
        lockToken: String,
        finalStatus: String
    ) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("synced", 1)
            put("synced_at_ms", System.currentTimeMillis())
        }

        db.update(
            "pending_acks",
            values,
            "job_id=? AND lock_token=? AND final_status=?",
            arrayOf(jobId, lockToken, finalStatus)
        )
    }

    fun getUnsynced(limit: Int = 100): List<PendingAck> {
        val db = helper.readableDatabase
        val out = mutableListOf<PendingAck>()

        val cursor = db.query(
            "pending_acks",
            arrayOf("id", "job_id", "lock_token", "final_status", "detail", "created_at_ms"),
            "synced=0",
            null,
            null,
            null,
            "created_at_ms ASC",
            limit.toString()
        )

        cursor.use {
            while (it.moveToNext()) {
                out += PendingAck(
                    id = it.getLong(0),
                    jobId = it.getString(1),
                    lockToken = it.getString(2),
                    finalStatus = it.getString(3),
                    detail = it.getString(4),
                    createdAtMs = it.getLong(5)
                )
            }
        }

        return out
    }

    fun getUnsyncedCount(): Int {
        val db = helper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM pending_acks WHERE synced=0",
            null
        )

        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}