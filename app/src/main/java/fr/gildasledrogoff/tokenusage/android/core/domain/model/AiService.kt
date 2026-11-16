package fr.gildasledrogoff.tokenusage.core.domain.model

enum class AiService(
    val displayName: String,
    val brandColor: Long,
    val baseUrl: String,
    val requiresManualCredentials: Boolean
) {
    CLAUDE(
        displayName = "Claude",
        brandColor = 0xFFD4A574,
        baseUrl = "https://api.anthropic.com/",
        requiresManualCredentials = false
    ),
    CODEX(
        displayName = "Codex",
        brandColor = 0xFF10A37F,
        baseUrl = "https://chatgpt.com/",
        requiresManualCredentials = false
    ),
    GEMINI(
        displayName = "Gemini",
        brandColor = 0xFF4285F4,
        baseUrl = "https://cloudcode-pa.googleapis.com/",
        requiresManualCredentials = true
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        brandColor = 0xFF4D6BFE,
        baseUrl = "https://api.deepseek.com/",
        requiresManualCredentials = true
    ),
    ZAI(
        displayName = "Z.ai",
        brandColor = 0xFF3859FF,
        baseUrl = "https://api.z.ai/",
        requiresManualCredentials = true
    ),
    COPILOT(
        displayName = "Copilot",
        brandColor = 0xFF8250DF,
        baseUrl = "https://api.github.com/",
        requiresManualCredentials = true
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        brandColor = 0xFF6566F1,
        baseUrl = "https://openrouter.ai/api/v1/",
        requiresManualCredentials = true
    ),
    OPENAI(
        displayName = "OpenAI",
        brandColor = 0xFF10A37F,
        baseUrl = "https://api.openai.com/",
        requiresManualCredentials = true
    );

    
    val usesSingleApiKey: Boolean
        get() = this == COPILOT || this == OPENROUTER || this == OPENAI
}
