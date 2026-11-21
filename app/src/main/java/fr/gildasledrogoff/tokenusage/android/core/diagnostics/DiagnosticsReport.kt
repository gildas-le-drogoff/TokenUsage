package fr.gildasledrogoff.tokenusage.core.diagnostics

import java.time.Duration
import java.time.Instant

object DiagnosticsReport {
    data class AppInfo(
        val versionName: String,
        val versionCode: Long,
        val gitHash: String? = null,
        val gitDate: String? = null
    )

    fun build(
        now: Instant,
        app: AppInfo,
        accounts: List<AccountDiagnostic>,
        background: BackgroundSnapshot?
    ): String {
        val text = buildString {
            appendLine("TokenUsage diagnostics")
            appendLine("Generated: $now")
            append("App: ${app.versionName} (${app.versionCode})")
            if (app.gitHash != null) append(" @ ${app.gitHash}" + (app.gitDate?.let { " ($it)" }
                ?: ""))
            if (background != null) append(" | Android SDK ${background.sdkInt} | Manufacturer: ${background.manufacturer.ifBlank { "?" }}")
            appendLine()
            appendLine()
            appendLine("== Accounts ==")
            accounts.forEach { appendAccount(it, now) }
            if (background != null) {
                appendLine()
                appendLine("== Background ==")
                appendBackground(background, now)
            }
        }
        return DiagnosticsSanitizer.sanitize(text, maxLength = Int.MAX_VALUE).trimEnd()
    }

    private fun StringBuilder.appendAccount(a: AccountDiagnostic, now: Instant) {
        val name = a.service.displayName + (a.accountLabel?.let { " ($it)" } ?: "")
        val status = when (a.status) {
            DiagnosticStatus.OK -> "OK"
            DiagnosticStatus.ERROR -> "ERROR"
            DiagnosticStatus.NOT_CONFIGURED -> "not configured"
            DiagnosticStatus.NEVER_REFRESHED -> "never refreshed"
        }
        append("- $name: $status")
        a.at?.let { append(" | ${it} (age ${formatAge(a.age(now))})") }
        a.origin?.let { append(" | via ${it.name.lowercase()}") }
        appendLine()
        a.error?.let {
            appendLine(
                "    error: ${it.type}${
                    it.message.takeIf(String::isNotBlank)?.let { m -> " - $m" } ?: ""
                }")
        }
        if (a.error == null) {
            a.lastError?.let { e ->
                appendLine(
                    "    last error: ${e.type}${
                        e.message.takeIf(String::isNotBlank)?.let { m -> " - $m" } ?: ""
                    }" +
                            (a.lastErrorAt?.let { " at $it" } ?: "")
                )
            }
        }
    }

    private fun StringBuilder.appendBackground(b: BackgroundSnapshot, now: Instant) {
        appendLine("Battery optimizations ignored: ${yesNo(b.ignoringBatteryOptimizations)}")
        appendLine("App standby bucket: ${BackgroundSnapshot.standbyBucketName(b.standbyBucket) ?: "?"}")
        appendLine("Background restricted: ${yesNo(b.backgroundRestricted)}")
        appendLine("Doze (device idle): ${yesNo(b.deviceIdle)}")
        appendLine("Battery saver: ${yesNo(b.powerSaveMode)}")
        b.manufacturerGroup?.let { appendLine("Known aggressive vendor: ${it.name.lowercase()} - ${it.instructionsUrl}") }
        b.works.forEach { w ->
            append("Work ${w.name}: ${w.state}")
            if (w.runAttemptCount > 0) append(" | attempt ${w.runAttemptCount}")
            w.nextRunAtEpochMs?.let { append(" | next ${Instant.ofEpochMilli(it)}") }
            w.lastRun?.let {
                val at = Instant.ofEpochMilli(it.atEpochMs)
                append(
                    " | last run $at (${
                        formatAge(
                            Duration.between(at, now).coerceAtLeast(Duration.ZERO)
                        )
                    } ago): ${it.result}"
                )
            }
            appendLine()
        }
    }

    private fun yesNo(value: Boolean?): String = when (value) {
        true -> "yes"
        false -> "no"
        null -> "?"
    }

    fun formatAge(age: Duration?): String {
        age ?: return "-"
        val seconds = age.seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3_600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3_600}h${"%02d".format((seconds % 3_600) / 60)}"
            else -> "${seconds / 86_400}d"
        }
    }
}
