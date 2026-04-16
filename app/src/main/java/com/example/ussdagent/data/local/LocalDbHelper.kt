package com.example.ussdagent.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalDbHelper(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_acks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT NOT NULL,
                lock_token TEXT NOT NULL,
                final_status TEXT NOT NULL,
                detail TEXT,
                created_at_ms INTEGER NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0,
                synced_at_ms INTEGER,
                UNIQUE(job_id, lock_token, final_status)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Phase 1: no upgrade logic needed yet
    }

    companion object {
        private const val DB_NAME = "ussd_local.db"
        private const val DB_VERSION = 1
    }
}