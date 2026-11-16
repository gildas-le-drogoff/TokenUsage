package fr.gildasledrogoff.tokenusage.core.network.openrouter

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface OpenRouterApiService {

    
    @GET("key")
    suspend fun getKey(
        @Header("Authorization") authorization: String,
        @Header("X-Title") title: String = OpenRouterDto.APP_TITLE
    ): Response<OpenRouterDto.KeyResponse>

    
    @GET("credits")
    suspend fun getCredits(
        @Header("Authorization") authorization: String,
        @Header("X-Title") title: String = OpenRouterDto.APP_TITLE
    ): Response<OpenRouterDto.CreditsResponse>
}
