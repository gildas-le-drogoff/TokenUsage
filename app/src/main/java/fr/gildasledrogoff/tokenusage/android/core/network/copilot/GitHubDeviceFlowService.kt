package fr.gildasledrogoff.tokenusage.core.network.copilot

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Headers
import retrofit2.http.POST

interface GitHubDeviceFlowService {
    @FormUrlEncoded
    @Headers("Accept: application/json")
    @POST("login/device/code")
    suspend fun requestDeviceCode(
        @Field("client_id") clientId: String = CopilotDto.CLIENT_ID,
        @Field("scope") scope: String = CopilotDto.SCOPE
    ): Response<CopilotDto.DeviceCodeResponse>

    @FormUrlEncoded
    @Headers("Accept: application/json")
    @POST("login/oauth/access_token")
    suspend fun pollAccessToken(
        @Field("device_code") deviceCode: String,
        @Field("client_id") clientId: String = CopilotDto.CLIENT_ID,
        @Field("grant_type") grantType: String = CopilotDto.DEVICE_GRANT_TYPE
    ): Response<CopilotDto.AccessTokenResponse>

    companion object {
        const val BASE_URL = "https://github.com/"
    }
}
