package fr.gildasledrogoff.tokenusage.core.widget

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class WidgetEntrySourceTest {
    private val now = Instant.parse("2026-11-21T18:00:00Z")
    private val anHourAgo = now.minusSeconds(3600)

    private fun quota(at: Instant, utilization: Double = 0.4) = QuotaInfo(
        AiService.CODEX,
        listOf(UsageWindow("5h", utilization, null)),
        fetchedAt = at
    )

    private fun cachedAccount(at: Instant?, label: String? = "Work") = CachedAccount(
        accountLabel = label,
        accountNumber = 1,
        windows = listOf(CachedWindow("5h", 0.2f)),
        dataFetchedAtEpochMillis = at?.toEpochMilli()
    )

    private fun entry(
        quota: QuotaInfo? = null,
        error: AppError? = null,
        fallback: QuotaInfo? = null
    ) = WidgetAccountQuota(AiService.CODEX, "Work", 1, quota, error, fallback)

    @Test
    fun `a received quota is fresh`() {
        val received = quota(now)
        val source = entry(quota = received).resolveSource(emptyList(), 1, offline = false)
        assertEquals(WidgetEntrySource.Quota(received, WidgetDataStatus.FRESH), source)
    }

    @Test
    fun `a provider failure keeps the previous widget data, marked as failed`() {
        val source = entry(error = AppError.RateLimited)
            .resolveSource(listOf(cachedAccount(anHourAgo)), 1, offline = false)
        source as WidgetEntrySource.Previous
        assertEquals(WidgetDataStatus.FAILED, source.account.status)
        assertEquals("the data date is kept", anHourAgo.toEpochMilli(), source.account.dataFetchedAtEpochMillis)
        assertEquals(0.2f, source.account.windows.single().utilization)
    }

    @Test
    fun `offline marks the previous data as offline`() {
        val source = entry(error = AppError.NetworkError(""))
            .resolveSource(listOf(cachedAccount(anHourAgo)), 1, offline = true)
        assertEquals(WidgetDataStatus.OFFLINE, (source as WidgetEntrySource.Previous).account.status)
    }

    @Test
    fun `a connectivity failure counts as offline even without the device flag`() {
        val error = AppError.NetworkError("dns", UnknownHostException("chatgpt.com"))
        val source = entry(error = error).resolveSource(listOf(cachedAccount(anHourAgo)), 1, offline = false)
        assertEquals(WidgetDataStatus.OFFLINE, (source as WidgetEntrySource.Previous).account.status)
    }

    @Test
    fun `the dashboard last known quota is used when more recent than the widget cache`() {
        val known = quota(now.minusSeconds(60))
        val source = entry(error = AppError.RateLimited, fallback = known)
            .resolveSource(listOf(cachedAccount(anHourAgo)), 1, offline = false)
        assertEquals(WidgetEntrySource.Quota(known, WidgetDataStatus.FAILED), source)
    }

    @Test
    fun `the widget cache wins when more recent than the dashboard last known quota`() {
        val previous = cachedAccount(now.minusSeconds(60))
        val source = entry(error = AppError.RateLimited, fallback = quota(anHourAgo))
            .resolveSource(listOf(previous), 1, offline = false)
        source as WidgetEntrySource.Previous
        assertEquals(previous.dataFetchedAtEpochMillis, source.account.dataFetchedAtEpochMillis)
    }

    @Test
    fun `an undated legacy cache entry gives way to the last known quota`() {
        val known = quota(anHourAgo)
        val source = entry(error = AppError.RateLimited, fallback = known)
            .resolveSource(listOf(cachedAccount(at = null)), 1, offline = false)
        assertSame(known, (source as WidgetEntrySource.Quota).quota)
    }

    @Test
    fun `an account without any known data gets a placeholder instead of disappearing`() {
        val failed = entry(error = AppError.RateLimited).resolveSource(emptyList(), 1, offline = false)
        assertEquals(WidgetEntrySource.Placeholder(WidgetDataStatus.FAILED), failed)
        val offline = entry(error = AppError.NetworkError("")).resolveSource(emptyList(), 1, offline = true)
        assertEquals(WidgetEntrySource.Placeholder(WidgetDataStatus.OFFLINE), offline)
    }

    @Test
    fun `a single unlabelled previous account is reused for the single account`() {
        val previous = cachedAccount(anHourAgo, label = null).copy(accountNumber = null)
        val source = entry(error = AppError.RateLimited).resolveSource(listOf(previous), 1, offline = false)
        source as WidgetEntrySource.Previous
        assertEquals("Work", source.account.accountLabel)
        assertEquals(1, source.account.accountNumber)
    }

    @Test
    fun `data from today shows the time only, older data the date too`() {
        val today = LocalDate.of(2026, 11, 21)
        val sameDay = widgetDataDate(Instant.parse("2026-11-21T08:05:00Z"), ZoneOffset.UTC, today, Locale.FRANCE)
        assertEquals("08:05", sameDay)
        val older = widgetDataDate(Instant.parse("2026-11-20T08:05:00Z"), ZoneOffset.UTC, today, Locale.FRANCE)
        assertEquals("20/11/2026 08:05", older)
    }
}
