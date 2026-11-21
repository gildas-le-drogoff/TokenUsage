package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import fr.gildasledrogoff.tokenusage.core.domain.model.Credential


object ClaudeSessionCookie {

    
    const val LOGIN_URL: String = "https://claude.ai/login"

    
    const val COOKIE_URL: String = "https://claude.ai"

    
    fun extract(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("sessionKey=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { Credential.ClaudeWebCredential.isSessionKey(it) }
    }
}
