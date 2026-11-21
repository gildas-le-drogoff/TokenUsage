package fr.gildasledrogoff.tokenusage.core.network.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object OpenAiDto {
    @Serializable
    data class CostsPage(
        val data: List<CostBucket>? = null,
        @SerialName("has_more") val hasMore: Boolean? = null,
        @SerialName("next_page") val nextPage: String? = null
    )

    @Serializable
    data class CostBucket(
        @SerialName("start_time") val startTime: Long = 0,
        @SerialName("end_time") val endTime: Long = 0,
        val results: List<CostResult> = emptyList()
    )

    @Serializable
    data class CostResult(
        val amount: Amount? = null,
        @SerialName("line_item") val lineItem: String? = null
    )

    @Serializable
    data class Amount(
        val value: Double = 0.0,
        val currency: String = "usd"
    )

    const val BUCKET_DAILY = "1d"
    const val HISTORY_DAYS = 30
    const val MAX_PAGES = 100
}
