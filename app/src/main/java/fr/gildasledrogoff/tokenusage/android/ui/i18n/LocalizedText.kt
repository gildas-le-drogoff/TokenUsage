package fr.gildasledrogoff.tokenusage.ui.i18n

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import java.time.Duration
import java.time.Instant





@StringRes
fun windowLabelRes(label: String): Int? = when (label) {
    "5-Hour" -> R.string.window_5h
    "7-Day" -> R.string.window_7d
    "Weekly scoped" -> R.string.window_weekly_scoped
    "Premium requests" -> R.string.window_copilot_premium
    "Completions" -> R.string.window_copilot_completions
    "API key limit" -> R.string.window_openrouter_key_limit
    else -> null
}


@Composable
fun localizedWindowLabel(label: String): String =
    windowLabelRes(label)?.let { stringResource(it) } ?: label


fun Context.localizedWindowLabel(label: String): String =
    windowLabelRes(label)?.let { getString(it) } ?: label


@StringRes
fun spendPeriodLabelRes(period: SpendPeriod): Int = when (period) {
    SpendPeriod.TODAY -> R.string.spend_today
    SpendPeriod.THIS_WEEK -> R.string.spend_this_week
    SpendPeriod.THIS_MONTH -> R.string.spend_this_month
    SpendPeriod.LAST_7_DAYS -> R.string.spend_last_7_days
    SpendPeriod.LAST_30_DAYS -> R.string.spend_last_30_days
}



@Composable
fun appErrorMessage(error: AppError): String = when (error) {
    is AppError.NetworkError ->
        if (error.message.isBlank()) stringResource(R.string.error_network)
        else stringResource(R.string.error_network_reason, error.message)
    is AppError.AuthError ->
        if (error.isTerminal) stringResource(R.string.error_reauth_required)
        else stringResource(R.string.error_auth)
    is AppError.RateLimited -> stringResource(R.string.error_rate_limited)
    is AppError.ParseError ->
        if (error.message.isBlank()) stringResource(R.string.error_parse)
        else stringResource(R.string.error_parse_reason, error.message)
    is AppError.CredentialNotFound -> stringResource(R.string.error_no_credentials)
    is AppError.TokenRevoked -> stringResource(R.string.error_token_revoked)
    is AppError.ServiceUnavailable -> stringResource(R.string.error_service_unavailable)
    is AppError.ClientUnsupported ->
        if (error.service == AiService.GEMINI) stringResource(R.string.error_gemini_consumer_unsupported)
        else stringResource(R.string.error_client_unsupported)
}



data class ResetDuration(val days: Long, val hours: Long, val minutes: Long)

fun resetDuration(until: Instant, now: Instant = Instant.now()): ResetDuration? {
    if (until.isBefore(now)) return null
    val duration = Duration.between(now, until)
    return ResetDuration(duration.toDays(), duration.toHours(), duration.toMinutes())
}


@Composable
fun durationLabel(duration: ResetDuration): String = when {
    duration.days >= 1 ->
        stringResource(R.string.duration_days_hours, duration.days, duration.hours % 24)
    duration.hours >= 1 ->
        stringResource(R.string.duration_hours_minutes, duration.hours, duration.minutes % 60)
    else -> stringResource(R.string.duration_minutes, duration.minutes)
}


@Composable
fun resetsInLabel(until: Instant?): String? {
    val duration = until?.let { resetDuration(it) } ?: return null
    return stringResource(R.string.resets_in, durationLabel(duration))
}


fun Context.resetsInText(until: Instant?): String? {
    val duration = until?.let { resetDuration(it) } ?: return null
    val parts = when {
        duration.days >= 1 ->
            getString(R.string.duration_days_hours, duration.days, duration.hours % 24)
        duration.hours >= 1 ->
            getString(R.string.duration_hours_minutes, duration.hours, duration.minutes % 60)
        else -> getString(R.string.duration_minutes, duration.minutes)
    }
    return getString(R.string.resets_in, parts)
}


@StringRes
fun fxRateSourceLabelRes(source: FxRateSource): Int = when (source) {
    FxRateSource.MANUAL -> R.string.fx_source_manual
    FxRateSource.FRANKFURTER -> R.string.fx_source_frankfurter
    FxRateSource.ECB -> R.string.fx_source_ecb
}


@Composable
fun fxRateNote(rate: FxRate): String {
    val source = stringResource(fxRateSourceLabelRes(rate.source))
    return if (rate.date != null) {
        stringResource(R.string.fx_rate_note_dated, rate.from, rate.rate, rate.to, source, rate.date)
    } else {
        stringResource(R.string.fx_rate_note, rate.from, rate.rate, rate.to, source)
    }
}
