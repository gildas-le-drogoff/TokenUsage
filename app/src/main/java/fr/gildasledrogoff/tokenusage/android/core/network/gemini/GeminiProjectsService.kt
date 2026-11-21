package fr.gildasledrogoff.tokenusage.core.network.gemini
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
interface GeminiProjectsService {
    @GET("v1/projects")
    suspend fun listProjects(
        @Header("Authorization") authorization: String
    ): Response<GeminiDto.ProjectsResponse>
    companion object {
        const val BASE_URL = "https://cloudresourcemanager.googleapis.com/"
    }
}
