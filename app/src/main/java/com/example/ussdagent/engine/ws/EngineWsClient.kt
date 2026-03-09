package com.example.ussdagent.engine.ws

import android.util.Log
import com.example.ussdagent.data.store.SecureStore
import com.example.ussdagent.engine.CurrentJob
import com.example.ussdagent.engine.CurrentJobState
import com.example.ussdagent.engine.EngineState
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EngineWsClient(
    private val store: SecureStore
) {
    private var ws: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // WS ping frames (nice-to-have)
        .build()

    private var stoppedByUser = false
    private var reconnectDelayMs = 1000L
    private var pingJob: Job? = null

    fun start() {
        stoppedByUser = false
        reconnectDelayMs = 1000L
        connect()
    }

    private fun connect() {
        val token = store.getAccessToken()
        if (token.isNullOrBlank()) {
            EngineState.set("No token: please login")
            return
        }

        val deviceId = store.getDeviceId() ?: run {
            EngineState.set("Missing device_id. Please login again.")
            return
        }

        val url = "ws://192.168.0.50:8000/ws/engine?token=$token&device_id=$deviceId"

        EngineState.set("Connecting WS...")
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, listener)
    }

    fun stop() {
        stoppedByUser = true
        pingJob?.cancel()
        pingJob = null

        try { ws?.cancel() } catch (_: Exception) {}
        ws = null

        CurrentJobState.set(null)
        EngineState.set("Engine stopped")
    }

    /**
     * Send a job-scoped event to server (stored in job_events).
     * Returns true if sent to WS.
     */
    fun sendEvent(jobId: String, eventType: String, payload: Map<String, Any?> = emptyMap()): Boolean {
        val obj = JSONObject()
            .put("type", "event")
            .put("job_id", jobId)
            .put("event_type", eventType)
            .put("payload", JSONObject(payload))

        return ws?.send(obj.toString()) ?: false
    }

    // ✅ send SUCCESS ack (returns true if sent)
    fun sendSuccess(jobId: String, lockToken: String): Boolean {
        val msg = JSONObject()
            .put("type", "ack")
            .put("job_id", jobId)
            .put("lock_token", lockToken)
            .put("status", "SUCCESS")

        val ok = ws?.send(msg.toString()) ?: false
        if (ok) {
            EngineState.set("Sent SUCCESS for $jobId ✅")
            CurrentJobState.set(null)
        } else {
            EngineState.set("Failed to send SUCCESS (WS not connected)")
        }
        return ok
    }

    // ✅ send FAILED ack (returns true if sent)
    fun sendFailed(jobId: String, lockToken: String, reason: String): Boolean {
        val msg = JSONObject()
            .put("type", "ack")
            .put("job_id", jobId)
            .put("lock_token", lockToken)
            .put("status", "FAILED")
            .put("detail", reason)

        val ok = ws?.send(msg.toString()) ?: false
        if (ok) {
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
                // Server expects TEXT JSON ping messages
                val ping = JSONObject().put("type", "ping").toString()
                try { webSocket.send(ping) } catch (_: Exception) {}

                // Keeps updated_at fresh while operator is working
                sendRunningHeartbeatIfNeeded(webSocket)

                delay(25_000)
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (stoppedByUser) return

        EngineState.set("WS reconnecting soon... ($reason)")
        scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
            connect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            EngineState.set("WS Connected ✅")
            Log.d("EngineWsClient", "WS open: $response")

            reconnectDelayMs = 1000L
            startTextPingLoop(webSocket)
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

                    // Telemetry
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
            pingJob?.cancel()
            pingJob = null
            EngineState.set("WS closed: $code $reason")
            scheduleReconnect("closed $code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            pingJob?.cancel()
            pingJob = null
            EngineState.set("WS failed: ${t.message}")
            Log.e("EngineWsClient", "WS failure", t)
            scheduleReconnect(t.message ?: "failure")
        }
    }
}