package com.example.maxscraper

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    private val pool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    private val uaInterceptor = Interceptor { chain ->
        val ua = System.getProperty("http.agent") ?: "Mozilla/5.0 (Android)"
        val req = chain.request().newBuilder()
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .build()
        chain.proceed(req)
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionPool(pool)
            .addInterceptor(uaInterceptor)
            .build()
    }
}
