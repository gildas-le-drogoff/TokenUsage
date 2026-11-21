package fr.gildasledrogoff.tokenusage.core.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderStatusRepositoryTest {
    @Test
    fun `parses statuspage payload`() {
        val body = """{"page":{"id":"x","updated_at":"2026-11-21T09:00:00.000Z"},
            "status":{"indicator":"major","description":"Partial System Outage"}}"""
        val status = ProviderStatusRepository.parse(body, "https://status.example.com/")!!
        assertEquals(StatusIndicator.MAJOR, status.indicator)
        assertEquals("Partial System Outage", status.description)
        assertTrue(status.indicator.hasIssue)
        assertEquals(1795251600L, status.updatedAt!!.epochSecond)
    }

    @Test
    fun `unknown indicator and garbage`() {
        val s = ProviderStatusRepository.parse("""{"status":{"indicator":"weird"}}""", "u")!!
        assertEquals(StatusIndicator.UNKNOWN, s.indicator)
        assertNull(ProviderStatusRepository.parse("not json", "u"))
        assertNull(ProviderStatusRepository.parse("{}", "u"))
    }
}
