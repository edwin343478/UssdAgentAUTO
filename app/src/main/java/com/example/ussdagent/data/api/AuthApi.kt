package com.example.ussdagent.data.api

import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

data class LoginResponse(
    val access_token: String,
    val token_type: String,
    val refresh_token: String? = null,
    val username: String? = null,
    val role: String? = null,
    val device_id: String? = null
)

data class RefreshRequest(
    val refresh_token: String,
    val device_id: String
)

data class RefreshResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val expires_in_seconds: Int? = null
)

interface AuthApi {
    @FormUrlEncoded
    @POST("api/auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("grant_type") grantType: String = "password",
        @Field("device_id") deviceId: String,
        @Field("device_name") deviceName: String = "Unknown Device"
    ): LoginResponse

    @POST("api/auth/refresh")
    suspend fun refresh(
        @Body body: RefreshRequest
    ): RefreshResponse
}