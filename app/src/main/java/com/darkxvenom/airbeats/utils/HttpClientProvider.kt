package com.darkxvenom.airbeats.utils

import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

object HttpClientProvider {

    private const val DEFAULT_CONNECT_TIMEOUT = 15L
    private const val DEFAULT_READ_TIMEOUT = 15L
    private const val DEFAULT_WRITE_TIMEOUT = 15L
    private const val DEFAULT_CALL_TIMEOUT = 30L
    private const val MAX_IDLE_CONNECTIONS = 5
    private const val KEEP_ALIVE_DURATION = 30L

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(DEFAULT_CALL_TIMEOUT, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION, TimeUnit.SECONDS))
            .build()
    }

    fun provide(): OkHttpClient = defaultClient

    fun provide(
        connectTimeout: Long = DEFAULT_CONNECT_TIMEOUT,
        readTimeout: Long = DEFAULT_READ_TIMEOUT,
        writeTimeout: Long = DEFAULT_WRITE_TIMEOUT,
        callTimeout: Long = DEFAULT_CALL_TIMEOUT,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .callTimeout(callTimeout, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION, TimeUnit.SECONDS))
            .build()
    }

    fun provide(
        configure: OkHttpClient.Builder.() -> Unit
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(DEFAULT_CALL_TIMEOUT, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION, TimeUnit.SECONDS))
            .apply(configure)
            .build()
    }
}
