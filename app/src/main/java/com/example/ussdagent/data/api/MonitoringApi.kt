package com.example.ussdagent.data.api

import retrofit2.http.GET

// We keep it flexible because the server returns dynamic "stats" keys by status.
data class MonitoringSummary(
    val stats: Map<String, Int> = emptyMap(),
    val needs_review: List<Map<String, Any?>> = emptyList(),
    val recent_alerts: List<Map<String, Any?>> = emptyList()
)

interface MonitoringApi {
    @GET("api/monitoring/summary")
    suspend fun summary(): MonitoringSummary
}