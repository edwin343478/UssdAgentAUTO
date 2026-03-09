package com.example.ussdagent.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object EngineController {
    fun start(context: Context) {
        val intent = Intent(context, EngineService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, EngineService::class.java)
        context.stopService(intent)
    }
}