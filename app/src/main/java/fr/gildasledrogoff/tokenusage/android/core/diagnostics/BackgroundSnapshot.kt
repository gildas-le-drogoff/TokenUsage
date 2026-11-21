package fr.gildasledrogoff.tokenusage.core.diagnostics

data class WorkSnapshot(
    val name: String,

    val state: String,
    val nextRunAtEpochMs: Long?,
    val runAttemptCount: Int,

    val lastRun: WorkerRun?
)

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

        fun isStandbyBucketRestrictive(bucket: Int?): Boolean = bucket != null && bucket >= 40
    }
}

fun interface BackgroundStateSource {
    suspend fun read(): BackgroundSnapshot
}

fun interface ConfiguredServicesSource {
    fun isConfigured(service: fr.gildasledrogoff.tokenusage.core.domain.model.AiService): Boolean
}
