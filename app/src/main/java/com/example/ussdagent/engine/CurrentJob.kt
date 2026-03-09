package com.example.ussdagent.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CurrentJob(
    val jobId: String,
    val lockToken: String,
    val msisdn: String?,
    val amount: Int?,
    val network: String?,
    val simSlot: Int?
)

object CurrentJobState {
    private val _job = MutableStateFlow<CurrentJob?>(null)
    val job: StateFlow<CurrentJob?> = _job

    fun set(job: CurrentJob?) { _job.value = job }

    // ✅ allows EngineWsClient to read current job for RUNNING heartbeat
    fun get(): CurrentJob? = _job.value
}