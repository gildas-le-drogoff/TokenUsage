package fr.gildasledrogoff.tokenusage.core.domain.model

import androidx.annotation.DrawableRes
import fr.gildasledrogoff.tokenusage.R

enum class AiService(
    val displayName: String,
    val brandColor: Long,
    val baseUrl: String,
    @DrawableRes val icon: Int
) {
    CLAUDE(
        displayName = "Claude",
        brandColor = 0xFFD4A574,
        baseUrl = "https://api.anthropic.com/",
        icon = R.drawable.ic_service_claude
    ),
    CODEX(
        displayName = "Codex",
        brandColor = 0xFF10A37F,
        baseUrl = "https://chatgpt.com/",
        icon = R.drawable.ic_service_codex
    ),
    GEMINI(
        displayName = "Gemini",
        brandColor = 0xFF4285F4,
        baseUrl = "https://cloudcode-pa.googleapis.com/",
        icon = R.drawable.ic_service_gemini
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        brandColor = 0xFF4D6BFE,
        baseUrl = "https://api.deepseek.com/",
        icon = R.drawable.ic_service_deepseek
    ),
    ZAI(
        displayName = "Z.ai",
        brandColor = 0xFF3859FF,
        baseUrl = "https://api.z.ai/",
        icon = R.drawable.ic_service_zai
    ),
    COPILOT(
        displayName = "Copilot",
        brandColor = 0xFF8250DF,
        baseUrl = "https://api.github.com/",
        icon = R.drawable.ic_service_copilot
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        brandColor = 0xFF6566F1,
        baseUrl = "https://openrouter.ai/api/v1/",
        icon = R.drawable.ic_service_openrouter
    ),
    OPENAI(
        displayName = "OpenAI",
        brandColor = 0xFF10A37F,
        baseUrl = "https://api.openai.com/",
        icon = R.drawable.ic_service_openai
    ),
    CLAUDE_PLATFORM(
        displayName = "Claude Platform",
        brandColor = 0xFFCC7C5E,
        baseUrl = "https://platform.claude.com/",
        icon = R.drawable.ic_service_claude
    );
}
