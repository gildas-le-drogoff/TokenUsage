package fr.gildasledrogoff.tokenusage.core.network.copilot

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers

interface CopilotApiService {

    
    @Headers(
        "Accept: application/json",
        "Editor-Version: vscode/1.96.2",
        "Editor-Plugin-Version: copilot-chat/0.26.7",
        "User-Agent: GitHubCopilotChat/0.26.7",
        "X-Github-Api-Version: 2025-04-01"
    )
    @GET("copilot_internal/user")
    suspend fun getUser(
        @Header("Authorization") authorization: String
    ): Response<CopilotDto.UserResponse>
}
