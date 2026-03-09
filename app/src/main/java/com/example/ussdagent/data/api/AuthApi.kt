package com.example.ussdagent.data.api

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

data class LoginResponse(
    val access_token: String,
    val token_type: String,
    val refresh_token: String? = null,
    val username: String? = null,
    val role: String? = null
)

interface AuthApi {
    @FormUrlEncoded
    @POST("api/auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        // FastAPI OAuth2 form often expects grant_type=password if provided
        @Field("grant_type") grantType: String = "password"
    ): LoginResponse
}