package fr.gildasledrogoff.tokenusage.core.network.openai

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface OpenAiApiService {

    
    @GET("v1/organization/costs")
    suspend fun getCosts(
        @Header("Authorization") authorization: String,
        @Query("start_time") startTime: Long,
        @Query("end_time") endTime: Long,
        @Query("bucket_width") bucketWidth: String = OpenAiDto.BUCKET_DAILY,
        @Query("limit") limit: Int = OpenAiDto.HISTORY_DAYS,
        @Query("page") page: String? = null
    ): Response<OpenAiDto.CostsPage>
}
