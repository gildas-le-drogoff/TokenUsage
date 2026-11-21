package fr.gildasledrogoff.tokenusage.core.workmanager

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Verrou unique pour tout rafraîchissement d'un token OAuth.
 *
 * Les refresh tokens à usage unique (Claude, Codex, Gemini) sont invalidés dès
 * qu'ils sont utilisés : si `TokenRefreshWorker` et un `fetchQuota()` (sur 401
 * ou expiration) échangent le même refresh token en parallèle, le second
 * échange est rejeté (`refresh_token_reused`) et le compte est déconnecté.
 *
 * Règle d'usage : prendre le verrou, **relire le credential stocké**, ne
 * rafraîchir que s'il est toujours expiré, puis sauvegarder avant de relâcher.
 * Le verrou n'est pas réentrant : ne jamais appeler [withLock] depuis le bloc
 * d'un autre [withLock].
 */
object TokenRefreshLock {

    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    /** Pour les tests et le diagnostic uniquement. */
    val isLocked: Boolean get() = mutex.isLocked
}
