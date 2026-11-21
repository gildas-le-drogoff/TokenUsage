package fr.gildasledrogoff.tokenusage.di

import okhttp3.OkHttpClient

// Release builds never log HTTP traffic; the logging library is not bundled.
internal fun OkHttpClient.Builder.addBodyLogging(): OkHttpClient.Builder = this

internal fun OkHttpClient.Builder.addBasicLogging(): OkHttpClient.Builder = this
