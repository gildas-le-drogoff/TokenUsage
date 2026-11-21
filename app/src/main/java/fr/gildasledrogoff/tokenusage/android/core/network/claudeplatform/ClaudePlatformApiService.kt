package fr.gildasledrogoff.tokenusage.core.network.claudeplatform

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

interface ClaudePlatformApiService {
    @GET("api/organizations")
    suspend fun getOrganizations(
        @Header("Cookie") cookie: String,
        @Header("Accept") accept: String = "application/json"
    ): Response<List<ClaudePlatformDto.Organization>>

    @GET("api/organizations/{orgId}/prepaid/credits")
    suspend fun getCredits(
        @Path("orgId") orgId: String,
        @Header("Cookie") cookie: String,
        @Header("Accept") accept: String = "application/json"
    ): Response<ClaudePlatformDto.Credits>

    @GET("api/organizations/{orgId}/current_spend")
    suspend fun getCurrentSpend(
        @Path("orgId") orgId: String,
        @Header("Cookie") cookie: String,
        @Header("Accept") accept: String = "application/json"
    ): Response<ClaudePlatformDto.Spend>

    companion object {
        const val BASE_URL = "https://platform.claude.com/"
    }
}
