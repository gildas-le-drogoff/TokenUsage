package fr.gildasledrogoff.tokenusage.core.domain.model
import java.time.Instant
sealed class Credential {
    abstract val accessToken: String
    abstract val refreshToken: String?
    data class ClaudeCredential(
        override val accessToken: String,
        override val refreshToken: String?,
        val expiresAt: Instant? = null,
        val scopes: String? = null,
        val rateLimitTier: String? = null
    ) : Credential()
    data class ClaudeWebCredential(
        override val accessToken: String
    ) : Credential() {
        override val refreshToken: String? = null
        val isValidSessionKey: Boolean
            get() = isSessionKey(accessToken)
        companion object {
            private val SESSION_KEY_REGEX = Regex("""sk-ant-sid\d+-\S+""")
            fun isSessionKey(token: String): Boolean = SESSION_KEY_REGEX.matches(token)
        }
    }
    data class CodexCredential(
        override val accessToken: String,
        override val refreshToken: String,
        val accountId: String? = null
    ) : Credential()
    data class GeminiCredential(
        override val accessToken: String,
        override val refreshToken: String,
        val expiresAtMs: Long,
        val oauthClientId: String,
        val oauthClientSecret: String,
        val hostedDomain: String? = null
    ) : Credential()
    data class DeepseekCredential(
        override val accessToken: String
    ) : Credential() {
        override val refreshToken: String? = null
    }
    data class ZaiCredential(
        override val accessToken: String
    ) : Credential() {
        override val refreshToken: String? = null
    }
    data class ApiKeyCredential(
        override val accessToken: String
    ) : Credential() {
        override val refreshToken: String? = null
    }
}
data class DeepseekAccount(
    val id: String,
    val label: String,
    val apiKey: String
)
