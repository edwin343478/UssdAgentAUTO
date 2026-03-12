package com.example.ussdagent.engine

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.ussdagent.R
import com.example.ussdagent.data.store.SecureStore
import com.example.ussdagent.engine.ws.EngineWsClient
import com.example.ussdagent.engine.ws.EngineWsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class EngineService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wsClient: EngineWsClient
    private lateinit var nm: NotificationManager

    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lastWakeJobId: String? = null
    private var lastWakeAttemptMs: Long = 0L
    private var batteryWarningShown = false

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels()

        startForeground(ENGINE_NOTIF_ID, buildEngineNotification())

        ensureCpuWakeLock()
        ensureWifiLock()

        wsClient = EngineWsClient(SecureStore(applicationContext))
        EngineWsManager.client = wsClient
        wsClient.start()

        serviceScope.launch {
            CurrentJobState.job.collectLatest { job: CurrentJob? ->
                if (job == null) {
                    lastWakeJobId = null
                    lastWakeAttemptMs = 0L
                    return@collectLatest
                }

                val pm = getSystemService(POWER_SERVICE) as PowerManager
                Log.w("WakeDebug", "Job arrived jobId=${job.jobId} interactive=${pm.isInteractive}")

                if (!pm.isInteractive) {
                    postFullScreenWake(job)
                } else {
                    launchJobWakerDirect(job)
                }
            }
        }
    }

    private fun launchJobWakerDirect(job: CurrentJob) {
        val simSlot = job.simSlot ?: run {
            Log.w("WakeDebug", "Abort wake: simSlot is null for jobId=${job.jobId}")
            return
        }

        val ussd = ussdCodeForNetwork(job.network)

        val intent = Intent(this, JobWakerActivity::class.java).apply {
            putExtra(JobWakerActivity.EXTRA_JOB_ID, job.jobId)
            putExtra(JobWakerActivity.EXTRA_USSD, ussd)
            putExtra(JobWakerActivity.EXTRA_SIM_SLOT, simSlot)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            postFullScreenWake(job)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureCpuWakeLock()
        ensureWifiLock()
        return START_STICKY
    }

    override fun onDestroy() {
        if (::wsClient.isInitialized) {
            try { wsClient.stop() } catch (_: Exception) {}
        }
        EngineWsManager.client = null
        releaseWifiLock()
        releaseCpuWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun postFullScreenWake(job: CurrentJob) {
        val now = SystemClock.elapsedRealtime()
        if (lastWakeJobId == job.jobId && (now - lastWakeAttemptMs) < 5_000L) {
            Log.w("WakeDebug", "Dedup (cooldown): recent wake attempt for jobId=${job.jobId}")
            return
        }
        lastWakeJobId = job.jobId
        lastWakeAttemptMs = now

        val simSlot = job.simSlot ?: run {
            Log.w("WakeDebug", "Abort wake: simSlot is null for jobId=${job.jobId}")
            return
        }
        val ussd = ussdCodeForNetwork(job.network)

        ensureWakeChannelHighImportance()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val canFsi = if (Build.VERSION.SDK_INT >= 34) {
            try { nm.canUseFullScreenIntent() } catch (_: Throwable) { false }
        } else true

        Log.w(
            "WakeDebug",
            "About to post FSI. canUseFullScreenIntent=$canFsi interactive=${pm.isInteractive} jobId=${job.jobId} ussd=$ussd sim=$simSlot"
        )

        if (!canFsi) {
            Log.e("WakeDebug", "Full-screen intents are not allowed. User may need to grant permission.")
            showFullScreenPermissionNotification()
            return
        }

        val intent = Intent(this, JobWakerActivity::class.java).apply {
            putExtra(JobWakerActivity.EXTRA_JOB_ID, job.jobId)
            putExtra(JobWakerActivity.EXTRA_USSD, ussd)
            putExtra(JobWakerActivity.EXTRA_SIM_SLOT, simSlot)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val uniqueCode = (SystemClock.elapsedRealtime() and 0x7fffffff).toInt()
        val pi = PendingIntent.getActivity(
            this,
            uniqueCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, WAKE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("USSD Job Ready")
            .setContentText("Waking device to process job")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()

        nm.notify(uniqueCode, notif)
        Log.w("WakeDebug", "FSI notify() called. notifId=$uniqueCode requestCode=$uniqueCode jobId=${job.jobId}")

        serviceScope.launch {
            delay(20_000)
            try { nm.cancel(uniqueCode) } catch (_: Exception) {}
        }

        Log.i("EngineService", "Full-screen wake posted for job=${job.jobId} ussd=$ussd sim=$simSlot")
    }

    private fun ussdCodeForNetwork(network: String?): String {
        return when (network?.trim()?.uppercase()) {
            "TIGO" -> "*150*01#"
            "VODACOM", "MPESA" -> "*150*00#"
            else -> "*150#"
        }
    }

    private fun ensureChannels() {
        val engine = NotificationChannel(
            ENGINE_CHANNEL_ID,
            "USSD Engine",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(engine)

        val wake = NotificationChannel(
            WAKE_CHANNEL_ID,
            "USSD Wake",
            NotificationManager.IMPORTANCE_HIGH
        )
        nm.createNotificationChannel(wake)
    }

    private fun ensureWakeChannelHighImportance() {
        val channel = nm.getNotificationChannel(WAKE_CHANNEL_ID)
        if (channel == null) {
            val newChannel = NotificationChannel(
                WAKE_CHANNEL_ID,
                "USSD Wake",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(newChannel)
            Log.w("EngineService", "Wake channel was missing; recreated with HIGH importance")
        } else if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
            nm.deleteNotificationChannel(WAKE_CHANNEL_ID)
            val newChannel = NotificationChannel(
                WAKE_CHANNEL_ID,
                "USSD Wake",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(newChannel)
            Log.w("EngineService", "Wake channel importance was too low; recreated with HIGH")
        }
    }

    private fun showFullScreenPermissionNotification() {
        if (batteryWarningShown) return
        batteryWarningShown = true
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, ENGINE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Full-screen intent permission")
            .setContentText("Tap to allow full-screen alerts for reliable wake-up")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1002, notif)
    }

    @SuppressLint("WakelockTimeout")
    private fun ensureCpuWakeLock() {
        try {
            if (cpuWakeLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            cpuWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UssdAgent:EngineCpu"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e("EngineService", "CPU WakeLock failed: ${e.message}")
        }
    }

    private fun ensureWifiLock() {
        try {
            if (wifiLock?.isHeld == true) return
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "UssdAgent:EngineWifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e("EngineService", "WiFiLock failed: ${e.message}")
        }
    }

    private fun releaseCpuWakeLock() {
        try { cpuWakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        cpuWakeLock = null
    }

    private fun releaseWifiLock() {
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wifiLock = null
    }

    private fun buildEngineNotification(): Notification {
        return NotificationCompat.Builder(this, ENGINE_CHANNEL_ID)
            .setContentTitle("USSD Agent Engine")
            .setContentText("USSD Engine Running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ENGINE_CHANNEL_ID = "ussd_engine_channel"
        private const val WAKE_CHANNEL_ID = "ussd_wake_channel"
        private const val ENGINE_NOTIF_ID = 1001
    }
}