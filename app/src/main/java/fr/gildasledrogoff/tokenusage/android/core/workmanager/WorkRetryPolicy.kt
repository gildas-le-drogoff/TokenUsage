package fr.gildasledrogoff.tokenusage.core.workmanager

/**
 * Politique de retry commune aux travaux périodiques.
 *
 * Sans plafond, `Result.retry()` rejoue le travail avec un backoff exponentiel
 * (jusqu'à 5 h) tant que la cause persiste : jeton définitivement refusé,
 * service durablement indisponible… On abandonne après [MAX_ATTEMPTS] essais
 * et on laisse le prochain passage périodique réessayer à l'intervalle normal.
 */
object WorkRetryPolicy {

    /** Nombre maximal d'exécutions successives d'un même passage (1re exécution comprise). */
    const val MAX_ATTEMPTS = 4

    enum class Decision(val label: String) {
        SUCCESS("success"),
        RETRY("retry"),
        GAVE_UP("gave_up")
    }

    /** @param runAttemptCount valeur de `Worker.runAttemptCount` (0 pour la première exécution). */
    fun decide(allSucceeded: Boolean, runAttemptCount: Int): Decision = when {
        allSucceeded -> Decision.SUCCESS
        runAttemptCount + 1 >= MAX_ATTEMPTS -> Decision.GAVE_UP
        else -> Decision.RETRY
    }
}
