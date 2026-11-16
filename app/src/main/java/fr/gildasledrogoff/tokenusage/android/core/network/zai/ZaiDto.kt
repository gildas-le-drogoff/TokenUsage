package fr.gildasledrogoff.tokenusage.core.network.zai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


object ZaiDto {

    @Serializable
    data class QuotaResponse(
        val code: Int = 0,
        val msg: String = "",
        val success: Boolean = false,
        val data: QuotaData? = null
    )

    @Serializable
    data class QuotaData(
        val limits: List<QuotaLimit> = emptyList(),
        
        val level: String = ""
    )

    @Serializable
    data class QuotaLimit(
        
        val type: String = "",
        
        val unit: Int = 0,
        
        val number: Int = 0,
        
        val percentage: Int = 0,
        
        @SerialName("nextResetTime") val nextResetTime: Long = 0,
        val usage: Int = 0,
        @SerialName("currentValue") val currentValue: Int = 0,
        val remaining: Int = 0,
        @SerialName("usageDetails") val usageDetails: List<UsageDetail> = emptyList()
    )

    @Serializable
    data class UsageDetail(
        @SerialName("modelCode") val modelCode: String = "",
        val usage: Int = 0
    )

    
    const val ACCEPT_LANGUAGE = "en-US,en"

    
    const val ERROR_CODE_INVALID_TOKEN = 1001

    const val TYPE_TOKENS_LIMIT = "TOKENS_LIMIT"
    const val TYPE_TIME_LIMIT = "TIME_LIMIT"

    const val UNIT_HOURS = 3
    const val UNIT_DAYS = 4
    const val UNIT_MONTHS = 5

    
    val QuotaLimit.displayName: String
        get() = when (type) {
            TYPE_TOKENS_LIMIT ->
                if (unit == UNIT_HOURS) "Session (${number}h)" else periodLabel("Quota")
            TYPE_TIME_LIMIT -> periodLabel("Tools")
            else -> type
        }

    
    val QuotaLimit.isMonthlyTools: Boolean
        get() = type == TYPE_TIME_LIMIT && unit == UNIT_MONTHS

    private fun QuotaLimit.periodLabel(suffix: String): String = when (unit) {
        UNIT_DAYS ->
            if (number == 1) "Daily $suffix" else "$number Day $suffix"
        UNIT_MONTHS ->
            if (number == 1) "Monthly $suffix" else "$number Month $suffix"
        else -> suffix
    }

    
    fun planName(level: String): String? = when (level) {
        "lite" -> "Lite"
        "pro" -> "Pro"
        "max" -> "Max"
        "" -> null
        else -> level
    }
}
