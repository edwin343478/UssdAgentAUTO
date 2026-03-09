package com.example.ussdagent.data.api

import android.content.Context
import com.example.ussdagent.data.store.SecureStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "http://192.168.0.50:8000/"

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    @Volatile private var retrofit: Retrofit? = null

    fun retrofit(context: Context): Retrofit {
        return retrofit ?: synchronized(this) {
            retrofit ?: buildRetrofit(context.applicationContext).also { retrofit = it }
        }
    }

    private fun buildRetrofit(appContext: Context): Retrofit {
        val store = SecureStore(appContext)
        val authInterceptor = AuthInterceptor(store)

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)   // always attach token (if present)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }
}