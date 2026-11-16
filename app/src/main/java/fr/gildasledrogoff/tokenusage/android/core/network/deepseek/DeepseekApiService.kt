package fr.gildasledrogoff.tokenusage.core.network.deepseek

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface DeepseekApiService {

    
    @GET("user/balance")
    suspend fun getUserBalance(
        @Header("Authorization") authorization: String
    ): Response<DeepseekDto.BalanceResponse>
}
