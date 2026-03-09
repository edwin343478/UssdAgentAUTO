package com.example.ussdagent.ussd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DialRequest(
    val jobId: String,
    val ussd: String,
    val simSlot: Int,
    val createdAtMs: Long = System.currentTimeMillis(),
    val callClicked: Boolean = false,
    val simChosen: Boolean = false
)

object UssdDialBus {
    private val _req = MutableStateFlow<DialRequest?>(null)
    val req: StateFlow<DialRequest?> = _req

    fun request(jobId: String, ussd: String, simSlot: Int) {
        _req.value = DialRequest(jobId = jobId, ussd = ussd, simSlot = simSlot)
    }

    fun markCallClicked() {
        val r = _req.value ?: return
        _req.value = r.copy(callClicked = true)
    }

    fun markSimChosen() {
        val r = _req.value ?: return
        _req.value = r.copy(simChosen = true)
    }

    fun clear() {
        _req.value = null
    }
}