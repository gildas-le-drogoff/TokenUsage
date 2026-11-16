package fr.gildasledrogoff.tokenusage.core.network.zai

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface ZaiApiService {

    
    @GET("api/monitor/usage/quota/limit")
    suspend fun getQuotaLimit(
        @Header("Authorization") authorization: String,
        @Header("Accept-Language") acceptLanguage: String = ZaiDto.ACCEPT_LANGUAGE
    ): Response<ZaiDto.QuotaResponse>
}
