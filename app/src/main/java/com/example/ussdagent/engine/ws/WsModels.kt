package com.example.ussdagent.engine.ws

data class JobPayload(
    val job_id: String,
    val lock_token: String,
    val msisdn: String? = null,
    val amount: Int? = null,
    val network: String? = null,
    val sim_slot: Int? = null
)

data class JobMessage(
    val type: String,
    val job: JobPayload
)

data class AckMessage(
    val type: String = "ack",
    val job_id: String,
    val lock_token: String,
    val status: String,
    val detail: String? = null
)