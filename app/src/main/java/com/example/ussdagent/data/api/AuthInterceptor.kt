package com.example.ussdagent.data.api

import com.example.ussdagent.data.store.SecureStore
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val store: SecureStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val token = store.getAccessToken()
        if (token.isNullOrBlank()) {
            return chain.proceed(original)
        }

        val newReq = original.newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()

        return chain.proceed(newReq)
    }
}