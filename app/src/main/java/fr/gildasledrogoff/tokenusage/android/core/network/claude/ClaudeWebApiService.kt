package fr.gildasledrogoff.tokenusage.core.network.claude

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

interface ClaudeWebApiService {
    @GET("api/organizations")
    suspend fun getOrganizations(
        @Header("Cookie") cookie: String
    ): Response<List<ClaudeDto.WebOrganization>>

    @GET("api/organizations/{orgId}/usage")
    suspend fun getUsage(
        @Path("orgId") orgId: String,
        @Header("Cookie") cookie: String
    ): Response<ClaudeDto.OAuthUsageResponse>

    companion object {
        const val BASE_URL = "https://claude.ai/"
        fun sessionCookie(sessionKey: String): String = "sessionKey=$sessionKey"
    }
}
