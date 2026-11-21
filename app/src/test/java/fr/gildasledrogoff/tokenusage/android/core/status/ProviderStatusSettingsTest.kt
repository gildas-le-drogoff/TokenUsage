package fr.gildasledrogoff.tokenusage.core.status

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ProviderStatusSettingsTest {
    private val prefs = inMemoryPrefsManager()
    private val clock = MutableClock(Instant.parse("2026-11-21T10:00:00Z"))
    private val fetched = mutableListOf<StatusSource>()
    private val repository = ProviderStatusRepository(clock, prefs).apply {
        fetcher = { source, _ -> fetched += source; status(source) }
    }

    private fun status(source: StatusSource) =
        ProviderStatus(StatusIndicator.MINOR, "Incident", null, source.pageUrl)

    @Test
    fun `nothing is polled by default`() = runTest {
        repository.refresh(force = true)
        assertTrue(fetched.isEmpty())
        assertTrue(repository.statuses.value.isEmpty())
    }

    @Test
    fun `only enabled providers are polled and published`() = runTest {
        repository.updateSettings(StatusCheckSettings().withProvider(AiService.COPILOT, true))
        repository.refresh()
        assertEquals(listOf(AiService.COPILOT.statusSource()), fetched)
        assertEquals(setOf(AiService.COPILOT), repository.statuses.value.keys)
    }

    @Test
    fun `all providers covers every supported page once`() = runTest {
        repository.updateSettings(StatusCheckSettings(allProviders = true))
        repository.refresh()
        assertEquals(StatusCheckSettings.supportedServices.mapNotNull { it.statusSource() }.toSet().size, fetched.size)
        assertEquals(StatusCheckSettings.supportedServices.toSet(), repository.statuses.value.keys)
    }

    @Test
    fun `disabling a provider hides its status at once`() = runTest {
        repository.updateSettings(StatusCheckSettings(allProviders = true))
        repository.refresh()
        repository.updateSettings(StatusCheckSettings().withProvider(AiService.CLAUDE, true))
        assertEquals(setOf(AiService.CLAUDE), repository.statuses.value.keys)
    }

    @Test
    fun `a provider sharing an already polled page is shown without a new call`() = runTest {
        repository.updateSettings(StatusCheckSettings().withProvider(AiService.CLAUDE, true))
        repository.refresh()
        repository.updateSettings(repository.settings.value.withProvider(AiService.CLAUDE_PLATFORM, true))
        repository.refresh()
        assertEquals(1, fetched.size)
        assertEquals(setOf(AiService.CLAUDE, AiService.CLAUDE_PLATFORM), repository.statuses.value.keys)
    }

    @Test
    fun `a newly enabled provider is polled despite the throttle`() = runTest {
        repository.updateSettings(StatusCheckSettings().withProvider(AiService.CLAUDE, true))
        repository.refresh()
        repository.updateSettings(repository.settings.value.withProvider(AiService.COPILOT, true))
        repository.refresh()
        assertEquals(listOf(AiService.CLAUDE.statusSource(), AiService.COPILOT.statusSource()), fetched)
        clock.now = clock.now.plusSeconds(60)
        repository.refresh()
        assertEquals(2, fetched.size)
    }

    @Test
    fun `settings survive a restart`() {
        repository.updateSettings(StatusCheckSettings().withProvider(AiService.DEEPSEEK, true))
        val restored = ProviderStatusRepository(clock, prefs).settings.value
        assertFalse(restored.allProviders)
        assertEquals(setOf(AiService.DEEPSEEK), restored.providers)
    }

    @Test
    fun `providers without a status page are never enabled`() {
        val settings = StatusCheckSettings(allProviders = true)
        assertFalse(settings.isEnabled(AiService.GEMINI))
        assertFalse(settings.isEnabled(AiService.ZAI))
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
}
