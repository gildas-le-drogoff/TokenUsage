package fr.gildasledrogoff.tokenusage.core.diagnostics

/** État d'un travail WorkManager unique. */
data class WorkSnapshot(
    val name: String,
    /** `ENQUEUED`, `RUNNING`, `BLOCKED`, `SUCCEEDED`, `FAILED`, `CANCELLED` ou `NOT_SCHEDULED`. */
    val state: String,
    val nextRunAtEpochMs: Long?,
    val runAttemptCount: Int,
    /** Dernier passage connu (journal local), `null` s'il n'a jamais tourné depuis l'installation. */
    val lastRun: WorkerRun?
)

/** Ce que le système impose à l'application en arrière-plan. Valeurs `null` = indisponible. */
data class BackgroundSnapshot(
    val works: List<WorkSnapshot>,
    val ignoringBatteryOptimizations: Boolean?,
    val standbyBucket: Int?,
    val backgroundRestricted: Boolean?,
    val deviceIdle: Boolean?,
    val powerSaveMode: Boolean?,
    val manufacturer: String,
    val manufacturerGroup: ManufacturerGroup?,
    val sdkInt: Int
) {
    companion object {
        /** Nom lisible d'un bucket App Standby (`UsageStatsManager.STANDBY_BUCKET_*`). */
        fun standbyBucketName(bucket: Int?): String? = when (bucket) {
            null -> null
            10 -> "ACTIVE"
            20 -> "WORKING_SET"
            30 -> "FREQUENT"
            40 -> "RARE"
            45 -> "RESTRICTED"
            50 -> "NEVER"
            5 -> "EXEMPTED"
            else -> "UNKNOWN($bucket)"
        }

        /** Les buckets RARE, RESTRICTED et NEVER retardent fortement les travaux périodiques. */
        fun isStandbyBucketRestrictive(bucket: Int?): Boolean = bucket != null && bucket >= 40
    }
}

/** Source de l'état d'arrière-plan : abstraite pour pouvoir tester le ViewModel. */
fun interface BackgroundStateSource {
    suspend fun read(): BackgroundSnapshot
}

/** Services configurés par l'utilisateur : abstrait pour les tests. */
fun interface ConfiguredServicesSource {
    fun isConfigured(service: fr.gildasledrogoff.tokenusage.core.domain.model.AiService): Boolean
}
