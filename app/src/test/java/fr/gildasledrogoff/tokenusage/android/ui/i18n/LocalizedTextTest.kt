package fr.gildasledrogoff.tokenusage.ui.i18n

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en"
)
class LocalizedTextTest {
    private lateinit var context: Context
    private val now = Instant.parse("2026-11-21T12:00:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `known window labels have a resource`() {
        mapOf(
            "5-Hour" to R.string.window_5h,
            "7-Day" to R.string.window_7d,
            "Weekly scoped" to R.string.window_weekly_scoped,
            "Premium requests" to R.string.window_copilot_premium,
            "Completions" to R.string.window_copilot_completions,
            "API key limit" to R.string.window_openrouter_key_limit,
            "API credit" to R.string.window_claude_platform_api_credit,
            "Purchased credit" to R.string.window_claude_platform_purchased_credit
        ).forEach { (label, res) -> assertEquals(label, res, windowLabelRes(label)) }
    }

    @Test
    fun `unknown window labels have no resource and are shown as is`() {
        assertNull(windowLabelRes("Gemini Pro"))
        assertEquals("Gemini Pro", context.localizedWindowLabel("Gemini Pro"))
    }

    @Test
    fun `known window labels are translated`() {
        assertEquals(context.getString(R.string.window_7d), context.localizedWindowLabel("7-Day"))
    }

    @Test
    fun `every spend period has a distinct label`() {
        val labels = SpendPeriod.entries.map { context.getString(spendPeriodLabelRes(it)) }

        assertEquals(SpendPeriod.entries.size, labels.toSet().size)
    }

    @Test
    fun `every rate source has a distinct label`() {
        val labels = FxRateSource.entries.map { context.getString(fxRateSourceLabelRes(it)) }

        assertEquals(FxRateSource.entries.size, labels.toSet().size)
    }

    @Test
    fun `a past reset has no duration`() {
        assertNull(resetDuration(now.minusSeconds(1), now))
    }

    @Test
    fun `reset duration splits days hours and minutes cumulatively`() {
        val until = now.plus(Duration.ofDays(1).plusHours(2).plusMinutes(3))

        assertEquals(ResetDuration(days = 1, hours = 26, minutes = 26 * 60 + 3), resetDuration(until, now))
    }

    @Test
    fun `resets in text is null without a date or for the past`() {
        assertNull(context.resetsInText(null))
        assertNull(context.resetsInText(Instant.now().minusSeconds(60)))
    }

    @Test
    fun `resets in text picks the largest units`() {
        val base = Instant.now()

        assertEquals("Resets in 2d 3h", context.resetsInText(base.plus(Duration.ofHours(51)).plusSeconds(30)))
        assertEquals("Resets in 5h 15m", context.resetsInText(base.plus(Duration.ofMinutes(315)).plusSeconds(30)))
        assertEquals("Resets in 42m", context.resetsInText(base.plus(Duration.ofMinutes(42)).plusSeconds(30)))
    }
}
