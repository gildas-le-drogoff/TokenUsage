package fr.gildasledrogoff.tokenusage.ui.i18n

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.UsagePace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
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
class LocalizedComposeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private var probe by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var contentSet = false

    private fun renderNullable(block: @Composable () -> String?): String? {
        if (!contentSet) {
            compose.setContent { probe?.invoke() }
            contentSet = true
        }
        var result: String? = "unset"
        compose.runOnIdle { probe = { result = block() } }
        compose.waitForIdle()
        return result
    }

    private fun render(block: @Composable () -> String): String = renderNullable(block)!!


    @Test
    fun `network error without message uses the generic text`() {
        assertEquals(s(R.string.error_network), render { appErrorMessage(AppError.NetworkError("")) })
        assertEquals(s(R.string.error_network), render { appErrorMessage(AppError.NetworkError("   ")) })
    }

    @Test
    fun `network error with message includes the reason`() {
        assertEquals(
            s(R.string.error_network_reason, "timeout"),
            render { appErrorMessage(AppError.NetworkError("timeout")) }
        )
    }

    @Test
    fun `terminal auth error asks to sign in again`() {
        assertEquals(
            s(R.string.error_reauth_required),
            render { appErrorMessage(AppError.AuthError(AiService.CLAUDE, isTerminal = true)) }
        )
    }

    @Test
    fun `transient auth error uses the plain auth text`() {
        assertEquals(
            s(R.string.error_auth),
            render { appErrorMessage(AppError.AuthError(AiService.CLAUDE, isTerminal = false)) }
        )
    }

    @Test
    fun `rate limit service unavailable revoked and missing credentials`() {
        assertEquals(s(R.string.error_rate_limited), render { appErrorMessage(AppError.RateLimited) })
        assertEquals(s(R.string.error_service_unavailable), render { appErrorMessage(AppError.ServiceUnavailable) })
        assertEquals(
            s(R.string.error_token_revoked),
            render { appErrorMessage(AppError.TokenRevoked(AiService.CODEX)) }
        )
        assertEquals(
            s(R.string.error_no_credentials),
            render { appErrorMessage(AppError.CredentialNotFound(AiService.CODEX)) }
        )
    }

    @Test
    fun `parse error with and without message`() {
        assertEquals(s(R.string.error_parse), render { appErrorMessage(AppError.ParseError("")) })
        assertEquals(
            s(R.string.error_parse_reason, "bad json"),
            render { appErrorMessage(AppError.ParseError("bad json")) }
        )
    }

    @Test
    fun `unsupported client has a dedicated text for gemini`() {
        assertEquals(
            s(R.string.error_gemini_consumer_unsupported),
            render { appErrorMessage(AppError.ClientUnsupported(AiService.GEMINI)) }
        )
        assertEquals(
            s(R.string.error_client_unsupported),
            render { appErrorMessage(AppError.ClientUnsupported(AiService.CLAUDE)) }
        )
    }


    @Test
    fun `composable window label translates known labels and keeps unknown ones`() {
        assertEquals(s(R.string.window_5h), render { localizedWindowLabel("5-Hour") })
        assertEquals("Custom window", render { localizedWindowLabel("Custom window") })
    }


    @Test
    fun `duration label uses days then hours then minutes`() {
        assertEquals(
            s(R.string.duration_days_hours, 2L, 3L),
            render { durationLabel(ResetDuration(days = 2, hours = 51, minutes = 51 * 60)) }
        )
        assertEquals(
            s(R.string.duration_hours_minutes, 5L, 15L),
            render { durationLabel(ResetDuration(days = 0, hours = 5, minutes = 315)) }
        )
        assertEquals(
            s(R.string.duration_minutes, 42L),
            render { durationLabel(ResetDuration(days = 0, hours = 0, minutes = 42)) }
        )
    }

    @Test
    fun `duration label switches unit exactly at one day and one hour`() {
        assertEquals(
            s(R.string.duration_days_hours, 1L, 0L),
            render { durationLabel(ResetDuration(1, 24, 24 * 60)) }
        )
        assertEquals(
            s(R.string.duration_hours_minutes, 1L, 0L),
            render { durationLabel(ResetDuration(0, 1, 60)) }
        )
        assertEquals(
            s(R.string.duration_minutes, 59L),
            render { durationLabel(ResetDuration(0, 0, 59)) }
        )
    }

    @Test
    fun `resets in label is null without a date or for a past date`() {
        assertNull(renderNullable { resetsInLabel(null) })
        assertNull(renderNullable { resetsInLabel(Instant.now().minusSeconds(120)) })
    }

    @Test
    fun `resets in label wraps the duration`() {
        val label = renderNullable { resetsInLabel(Instant.now().plus(Duration.ofMinutes(42)).plusSeconds(30)) }

        assertEquals(s(R.string.resets_in, s(R.string.duration_minutes, 42L)), label)
    }

    @Test
    fun `reset duration is zero when it resets right now`() {
        val now = Instant.parse("2026-11-21T12:00:00Z")

        assertEquals(ResetDuration(0, 0, 0), resetDuration(now, now))
    }


    @Test
    fun `fx note without date`() {
        val rate = FxRate("EUR", "USD", 1.1, FxRateSource.MANUAL)

        assertEquals(
            s(R.string.fx_rate_note, "EUR", 1.1, "USD", s(R.string.fx_source_manual)),
            render { fxRateNote(rate) }
        )
    }

    @Test
    fun `fx note with date`() {
        val rate = FxRate("EUR", "USD", 1.1, FxRateSource.ECB, date = "2026-11-20")

        assertEquals(
            s(R.string.fx_rate_note_dated, "EUR", 1.1, "USD", s(R.string.fx_source_ecb), "2026-11-20"),
            render { fxRateNote(rate) }
        )
    }


    private fun pace(
        stage: UsagePace.Stage,
        delta: Double,
        eta: Duration? = null,
        lasts: Boolean = false
    ) = UsagePace(
        stage = stage,
        deltaPercent = delta,
        expectedUsedPercent = 50.0,
        actualUsedPercent = 50.0 + delta,
        etaToExhaustion = eta,
        willLastToReset = lasts
    )

    @Test
    fun `on track pace`() {
        assertEquals(
            s(R.string.pace_label, s(R.string.pace_on_track)),
            render { paceLabel(pace(UsagePace.Stage.ON_TRACK, 0.0)) }
        )
    }

    @Test
    fun `a zero rounded delta is on track whatever the stage`() {
        assertEquals(
            s(R.string.pace_label, s(R.string.pace_on_track)),
            render { paceLabel(pace(UsagePace.Stage.AHEAD, 0.4)) }
        )
    }

    @Test
    fun `deficit pace shows the rounded delta and the eta`() {
        val label = render {
            paceLabel(pace(UsagePace.Stage.AHEAD, 12.6, eta = Duration.ofMinutes(125)))
        }
        val state = s(R.string.pace_deficit, 13)
        val eta = s(R.string.pace_runs_out_in, s(R.string.duration_hours_minutes, 2L, 5L))

        assertEquals(s(R.string.pace_label, "$state · $eta"), label)
    }

    @Test
    fun `reserve pace that lasts to the reset`() {
        val label = render {
            paceLabel(pace(UsagePace.Stage.BEHIND, -20.0, lasts = true))
        }
        val state = s(R.string.pace_reserve, 20)

        assertEquals(s(R.string.pace_label, "$state · ${s(R.string.pace_lasts_to_reset)}"), label)
    }

    @Test
    fun `reserve pace without eta shows only the state`() {
        val label = render { paceLabel(pace(UsagePace.Stage.SLIGHTLY_BEHIND, -5.0)) }

        assertEquals(s(R.string.pace_label, s(R.string.pace_reserve, 5)), label)
    }

    @Test
    fun `lasting to the reset wins over a computed eta`() {
        val label = render {
            paceLabel(pace(UsagePace.Stage.AHEAD, 10.0, eta = Duration.ofHours(3), lasts = true))
        }

        assertTrue(label.contains(s(R.string.pace_lasts_to_reset)))
        assertNotEquals(-1, label.indexOf("·"))
        assertNotNull(label)
    }
}
