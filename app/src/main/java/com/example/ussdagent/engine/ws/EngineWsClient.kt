package com.example.ussdagent.engine.ws

import android.util.Log
import android.content.Context
import com.example.ussdagent.data.local.PendingAckStore
import com.example.ussdagent.data.store.ActiveDispatchHintStore
import com.example.ussdagent.data.store.SecureStore
import com.example.ussdagent.engine.CurrentJob
import com.example.ussdagent.engine.CurrentJobState
import com.example.ussdagent.engine.EngineState
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EngineWsClient(
    private val appContext: Context,
    private val store: SecureStore
) {
    private var ws: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val pendingAckStore = PendingAckStore(appContext)
    private val activeDispatchHintStore = ActiveDispatchHintStore(appContext)

    private var stoppedByUser = false
    private var reconnectDelayMs = 1000L
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    private var pendingAckSyncJob: Job? = null

    @Volatile
    private var isConnecting = false

    fun start() {
        stoppedByUser = false
        reconnectDelayMs = 1000L
        reconnectJob?.cancel()
        reconnectJob = null
        connect()
    }

    private fun connect() {
        if (stoppedByUser) return
        if (isConnecting) return

        val deviceId = store.getDeviceId()
        if (deviceId.isNullOrBlank()) {
            EngineState.set("Missing device_id. Please login again.")
            return
        }

        scope.launch {
            if (stoppedByUser || isConnecting) return@launch
            isConnecting = true

            try {
                var token = store.getAccessToken()

                if (token.isNullOrBlank()) {
                    val refreshed = refreshAccessToken()
                    token = store.getAccessToken()

                    if (!refreshed || token.isNullOrBlank()) {
                        scheduleReconnect("missing token", forceRefresh = true)
                        return@launch
                    }
                }

                val url = "ws://192.168.0.50:8000/ws/engine?token=$token&device_id=$deviceId"

                EngineState.set("Connecting WS...")
                val request = Request.Builder().url(url).build()
                ws = client.newWebSocket(request, listener)
            } finally {
                isConnecting = false
            }
        }
    }

    fun stop() {
        stoppedByUser = true

        reconnectJob?.cancel()
        reconnectJob = null

        pingJob?.cancel()
        pingJob = null

        pendingAckSyncJob?.cancel()
        pendingAckSyncJob = null

        try { ws?.cancel() } catch (_: Exception) {}
        ws = null

        CurrentJobState.set(null)
        EngineState.set("Engine stopped")
    }

    fun sendEvent(jobId: String, eventType: String, payload: Map<String, Any?> = emptyMap()): Boolean {
        val obj = JSONObject()
            .put("type", "event")
            .put("job_id", jobId)
            .put("event_type", eventType)
            .put("payload", JSONObject(payload))

        return ws?.send(obj.toString()) ?: false
    }

    fun sendSuccess(jobId: String, lockToken: String): Boolean {
        pendingAckStore.upsertPendingAck(
            jobId = jobId,
            lockToken = lockToken,
            finalStatus = "SUCCESS",
            detail = null
        )

        val msg = JSONObject()
            .put("type", "ack")
            .put("job_id", jobId)
            .put("lock_token", lockToken)
            .put("status", "SUCCESS")

        val ok = ws?.send(msg.toString()) ?: false
        if (ok) {
            pendingAckStore.markSynced(
                jobId = jobId,
                lockToken = lockToken,
                finalStatus = "SUCCESS"
            )
            activeDispatchHintStore.clear()
            EngineState.set("Sent SUCCESS for $jobId ✅")
            CurrentJobState.set(null)
        } else {
            EngineState.set("Failed to send SUCCESS (WS not connected)")
        }
        return ok
    }

    fun sendFailed(jobId: String, lockToken: String, reason: String): Boolean {
        pendingAckStore.upsertPendingAck(
            jobId = jobId,
            lockToken = lockToken,
            finalStatus = "FAILED",
            detail = reason
        )

        val msg = JSONObject()
            .put("type", "ack")
            .put("job_id", jobId)
            .put("lock_token", lockToken)
            .put("status", "FAILED")
            .put("detail", reason)

        val ok = ws?.send(msg.toString()) ?: false
        if (ok) {
            pendingAckStore.markSynced(
                jobId = jobId,
                lockToken = lockToken,
                finalStatus = "FAILED"
            )
            activeDispatchHintStore.clear()
            EngineState.set("Sent FAILED for $jobId ❌")
            CurrentJobState.set(null)
        } else {
            EngineState.set("Failed to send FAILED (WS not connected)")
        }
        return ok
    }

    private fun sendRunningHeartbeatIfNeeded(webSocket: WebSocket) {
        val job = CurrentJobState.job.value ?: return
        val msg = JSONObject()
            .put("type", "ack")
            .put("job_id", job.jobId)
            .put("lock_token", job.lockToken)
            .put("status", "RUNNING")

        try { webSocket.send(msg.toString()) } catch (_: Exception) {}
    }

    private fun startTextPingLoop(webSocket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && !stoppedByUser) {
                val ping = JSONObject().put("type", "ping").toString()
                try { webSocket.send(ping) } catch (_: Exception) {}

                sendRunningHeartbeatIfNeeded(webSocket)

                delay(25_000)
            }
        }
    }

    private fun scheduleReconnect(reason: String, forceRefresh: Boolean = false) {
        if (stoppedByUser) return

        reconnectJob?.cancel()
        EngineState.set("WS reconnecting soon... ($reason)")

        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)

            if (forceRefresh || store.getAccessToken().isNullOrBlank()) {
                refreshAccessToken()
            }

            connect()
        }
    }


    private fun flushPendingAcks(webSocket: WebSocket) {
        pendingAckSyncJob?.cancel()

        pendingAckSyncJob = scope.launch {
            val pending = pendingAckStore.getUnsynced(limit = 100)
            if (pending.isEmpty()) return@launch

            for (item in pending) {
                if (!isActive || stoppedByUser) break

                val msg = JSONObject()
                    .put("type", "ack")
                    .put("job_id", item.jobId)
                    .put("lock_token", item.lockToken)
                    .put("status", item.finalStatus)

                if (item.finalStatus == "FAILED" && !item.detail.isNullOrBlank()) {
                    msg.put("detail", item.detail)
                }

                val sent = try {
                    webSocket.send(msg.toString())
                } catch (_: Exception) {
                    false
                }

                if (sent) {
                    pendingAckStore.markSynced(
                        jobId = item.jobId,
                        lockToken = item.lockToken,
                        finalStatus = item.finalStatus
                    )
                } else {
                    break
                }
            }
        }
    }


    private suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = store.getRefreshToken() ?: return@withContext false
        val deviceId = store.getDeviceId() ?: return@withContext false

        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .put("device_id", deviceId)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("http://192.168.0.50:8000/api/auth/refresh")
            .post(body)
            .build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    false
                } else {
                    val raw = response.body?.string().orEmpty()
                    val obj = JSONObject(raw)
                    val newAccessToken = obj.optString("access_token")
                    if (newAccessToken.isBlank()) {
                        false
                    } else {
                        store.saveAccessToken(newAccessToken)
                        true
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isAuthRelatedClose(code: Int, reason: String): Boolean {
        return code == 1008 ||
                reason.contains("auth", ignoreCase = true) ||
                reason.contains("token", ignoreCase = true)
    }

    private fun isAuthRelatedFailure(t: Throwable, response: Response?): Boolean {
        val rc = response?.code
        val msg = t.message.orEmpty()
        return rc == 401 ||
                rc == 403 ||
                msg.contains("auth", ignoreCase = true) ||
                msg.contains("token", ignoreCase = true)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectJob?.cancel()
            reconnectJob = null

            EngineState.set("WS Connected ✅")
            Log.d("EngineWsClient", "WS open: $response")

            reconnectDelayMs = 1000L
            startTextPingLoop(webSocket)
            flushPendingAcks(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("EngineWsClient", "WS message: $text")

            try {
                val obj = JSONObject(text)
                val type = obj.optString("type")

                if (type == "job") {
                    val jobObj = obj.getJSONObject("job")

                    val jobId = jobObj.getString("job_id")
                    val lockToken = jobObj.getString("lock_token")

                    val msisdn = jobObj.optString("msisdn", null)
                    val network = jobObj.optString("network", null)

                    val amount = jobObj.opt("amount")?.toString()?.toIntOrNull()
                    val simSlot = jobObj.opt("sim_slot")?.toString()?.toIntOrNull()

                    CurrentJobState.set(
                        CurrentJob(
                            jobId = jobId,
                            lockToken = lockToken,
                            msisdn = msisdn,
                            amount = amount,
                            network = network,
                            simSlot = simSlot
                        )
                    )
                    activeDispatchHintStore.save(
                        jobId = jobId,
                        lockToken = lockToken
                    )
                    sendEvent(
                        jobId = jobId,
                        eventType = "JOB_RECEIVED",
                        payload = mapOf(
                            "network" to network,
                            "sim_slot" to simSlot,
                            "amount" to amount,
                            "msisdn" to msisdn
                        )
                    )

                    EngineState.set("Job received: $jobId → ACK RUNNING")

                    val ack = JSONObject()
                        .put("type", "ack")
                        .put("job_id", jobId)
                        .put("lock_token", lockToken)
                        .put("status", "RUNNING")

                    webSocket.send(ack.toString())
                    sendEvent(jobId, "ACK_RUNNING_SENT")
                } else if (type == "pong") {
                    // Keepalive ok
                }
            } catch (e: Exception) {
                EngineState.set("WS parse error: ${e.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { /* ignore */ }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ws = null
            pingJob?.cancel()
            pingJob = null

            pendingAckSyncJob?.cancel()
            pendingAckSyncJob = null

            EngineState.set("WS closed: $code $reason")

            scheduleReconnect(
                reason = "closed $code",
                forceRefresh = isAuthRelatedClose(code, reason)
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ws = null
            pingJob?.cancel()
            pingJob = null

            pendingAckSyncJob?.cancel()
            pendingAckSyncJob = null


            EngineState.set("WS failed: ${t.message}")
            Log.e("EngineWsClient", "WS failure", t)

            scheduleReconnect(
                reason = t.message ?: "failure",
                forceRefresh = isAuthRelatedFailure(t, response)
            )
        }
    }
}