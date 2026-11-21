package fr.gildasledrogoff.tokenusage.core.workmanager

object WorkRetryPolicy {
    
    const val MAX_ATTEMPTS = 4
    enum class Decision(val label: String) {
        SUCCESS("success"),
        RETRY("retry"),
        GAVE_UP("gave_up")
    }
    
    fun decide(allSucceeded: Boolean, runAttemptCount: Int): Decision = when {
        allSucceeded -> Decision.SUCCESS
        runAttemptCount + 1 >= MAX_ATTEMPTS -> Decision.GAVE_UP
        else -> Decision.RETRY
    }
}
