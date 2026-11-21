package fr.gildasledrogoff.tokenusage.core.workmanager
import fr.gildasledrogoff.tokenusage.core.workmanager.WorkRetryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test
class WorkRetryPolicyTest {
    @Test
    fun `success is final whatever the attempt`() {
        for (attempt in 0..10) {
            assertEquals(Decision.SUCCESS, WorkRetryPolicy.decide(allSucceeded = true, runAttemptCount = attempt))
        }
    }
    @Test
    fun `failures are retried until the attempt budget is spent`() {
        for (attempt in 0 until WorkRetryPolicy.MAX_ATTEMPTS - 1) {
            assertEquals(Decision.RETRY, WorkRetryPolicy.decide(false, attempt))
        }
        assertEquals(Decision.GAVE_UP, WorkRetryPolicy.decide(false, WorkRetryPolicy.MAX_ATTEMPTS - 1))
        assertEquals(Decision.GAVE_UP, WorkRetryPolicy.decide(false, 99))
    }
    @Test
    fun `labels are stable because they are persisted by the diagnostics journal`() {
        assertEquals("success", Decision.SUCCESS.label)
        assertEquals("retry", Decision.RETRY.label)
        assertEquals("gave_up", Decision.GAVE_UP.label)
    }
}
