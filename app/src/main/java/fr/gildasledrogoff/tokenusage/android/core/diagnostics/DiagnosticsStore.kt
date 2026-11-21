package fr.gildasledrogoff.tokenusage.core.diagnostics

import android.content.Context
import android.content.SharedPreferences
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Journal local des derniers rafraîchissements, pour l'écran de diagnostic.
 *
 * - Stockage : un fichier de préférences privé dédié, **aucun secret** : les
 *   messages d'erreur sont assainis par [DiagnosticsSanitizer] avant écriture.
 * - Rien ne quitte l'appareil : aucune télémétrie.
 * - Taille bornée : [MAX_RECORDS] comptes et [MAX_WORKER_RUNS] travaux.
 */
@Singleton
class DiagnosticsStore internal constructor(
    private val prefs: Lazy<SharedPreferences>,
    private val clock: Clock
) {

    @Inject
    constructor(@ApplicationContext context: Context, clock: Clock) : this(
        lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) },
        clock
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val lock = Any()

    /** Enregistre le résultat d'un compte pour un passage de rafraîchissement. */
    fun recordFetch(
        origin: RefreshOrigin,
        service: AiService,
        accountLabel: String?,
        accountNumber: Int?,
        result: Result<QuotaInfo, AppError>
    ) = recordFetches(origin, listOf(FetchRecord(service, accountLabel, accountNumber, result)))

    /** Variante groupée : une seule écriture pour tout un passage. */
    fun recordFetches(origin: RefreshOrigin, fetches: List<FetchRecord>) {
        if (fetches.isEmpty()) return
        val now = clock.millis()
        synchronized(lock) {
            val byKey = readRecords().associateByTo(LinkedHashMap()) { it.accountKey }
            for (fetch in fetches) {
                val key = DiagnosticRecord.keyOf(fetch.service, fetch.accountNumber)
                val previous = byKey[key]
                val error = (fetch.result as? Result.Failure)?.error?.let(DiagnosticError::from)
                byKey[key] = DiagnosticRecord(
                    accountKey = key,
                    service = fetch.service,
                    accountLabel = DiagnosticsSanitizer.sanitize(fetch.accountLabel).ifBlank { null },
                    status = if (error == null) DiagnosticStatus.OK else DiagnosticStatus.ERROR,
                    atEpochMs = now,
                    origin = origin,
                    error = error,
                    lastError = error ?: previous?.lastError,
                    lastErrorAtEpochMs = if (error != null) now else previous?.lastErrorAtEpochMs
                )
            }
            val bounded = byKey.values.sortedByDescending { it.atEpochMs }.take(MAX_RECORDS)
            prefs.value.edit().putString(KEY_RECORDS, json.encodeToString(recordsSerializer, bounded)).apply()
        }
    }

    /** Enregistre la fin d'un passage de travail WorkManager (dernier run). */
    fun recordWorkerRun(workName: String, result: String, attempt: Int = 0) {
        val now = clock.millis()
        synchronized(lock) {
            val runs = readWorkerRuns().filterNot { it.workName == workName } +
                WorkerRun(workName, now, result, attempt)
            val bounded = runs.sortedByDescending { it.atEpochMs }.take(MAX_WORKER_RUNS)
            prefs.value.edit().putString(KEY_WORKER_RUNS, json.encodeToString(runsSerializer, bounded)).apply()
        }
    }

    fun records(): List<DiagnosticRecord> = synchronized(lock) { readRecords() }

    fun workerRuns(): List<WorkerRun> = synchronized(lock) { readWorkerRuns() }

    fun clear() = synchronized(lock) { prefs.value.edit().clear().apply() }

    private fun readRecords(): List<DiagnosticRecord> = decode(KEY_RECORDS, recordsSerializer)

    private fun readWorkerRuns(): List<WorkerRun> = decode(KEY_WORKER_RUNS, runsSerializer)

    private fun <T> decode(key: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> {
        val raw = prefs.value.getString(key, null) ?: return emptyList()
        // Un fichier illisible (version future, corruption) ne doit jamais faire planter l'app.
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    /** Un résultat à enregistrer. */
    data class FetchRecord(
        val service: AiService,
        val accountLabel: String?,
        val accountNumber: Int?,
        val result: Result<QuotaInfo, AppError>
    )

    private companion object {
        const val PREFS_NAME = "diagnostics_store"
        const val KEY_RECORDS = "records"
        const val KEY_WORKER_RUNS = "worker_runs"
        const val MAX_RECORDS = 64
        const val MAX_WORKER_RUNS = 8
        val recordsSerializer = ListSerializer(DiagnosticRecord.serializer())
        val runsSerializer = ListSerializer(WorkerRun.serializer())
    }
}
