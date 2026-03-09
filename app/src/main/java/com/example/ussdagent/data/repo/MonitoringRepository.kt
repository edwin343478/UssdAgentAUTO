package com.example.ussdagent.data.repo

import android.content.Context
import com.example.ussdagent.data.api.ApiClient
import com.example.ussdagent.data.api.MonitoringApi
import com.example.ussdagent.data.api.MonitoringSummary
import retrofit2.HttpException

class MonitoringRepository(context: Context) {
    private val api = ApiClient.retrofit(context).create(MonitoringApi::class.java)

    suspend fun fetchSummary(): Result<MonitoringSummary> {
        return try {
            Result.success(api.summary())
        } catch (e: HttpException) {
            // This is the key: detect 401
            if (e.code() == 401) {
                Result.failure(UnauthorizedException())
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class UnauthorizedException : Exception("Session expired. Please login again.")