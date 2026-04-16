package com.example.ussdagent.data.repo

import android.content.Context
import android.os.Build
import com.example.ussdagent.data.api.ApiClient
import com.example.ussdagent.data.api.AuthApi
import com.example.ussdagent.data.api.RefreshRequest
import com.example.ussdagent.data.store.SecureStore
import java.util.UUID

class AuthRepository(
    private val context: Context,
    private val store: SecureStore
) {
    private val api: AuthApi =
        ApiClient.retrofit(context).create(AuthApi::class.java)

    suspend fun login(username: String, password: String): Result<Unit> {
        return try {
            val candidateDeviceId = store.getDeviceId()
                ?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()

            val resp = api.login(
                username = username,
                password = password,
                deviceId = candidateDeviceId,
                deviceName = buildDeviceName()
            )

            store.saveAccessToken(resp.access_token)
            resp.refresh_token?.let { store.saveRefreshToken(it) }

            val resolvedDeviceId = resp.device_id
                ?.takeIf { it.isNotBlank() }
                ?: candidateDeviceId
            store.saveDeviceId(resolvedDeviceId)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshSession(): Result<Unit> {
        return try {
            val refreshToken = store.getRefreshToken()
                ?: return Result.failure(IllegalStateException("Missing refresh token"))

            val deviceId = store.getDeviceId()
                ?: return Result.failure(IllegalStateException("Missing device_id"))

            val resp = api.refresh(
                RefreshRequest(
                    refresh_token = refreshToken,
                    device_id = deviceId
                )
            )

            store.saveAccessToken(resp.access_token)
            resp.refresh_token?.let { store.saveRefreshToken(it) }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isLoggedIn(): Boolean = store.hasAuthSession()

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()

        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android Device" }
    }
}