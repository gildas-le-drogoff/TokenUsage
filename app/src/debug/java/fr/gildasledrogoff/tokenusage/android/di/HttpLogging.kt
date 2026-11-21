package fr.gildasledrogoff.tokenusage.di

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/** Debug builds only: full request/response logging with credentials redacted. */
internal fun OkHttpClient.Builder.addBodyLogging(): OkHttpClient.Builder =
    addInterceptor(
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
        }
    )

/** Debug builds only: one line per request. */
internal fun OkHttpClient.Builder.addBasicLogging(): OkHttpClient.Builder =
    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
