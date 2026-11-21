package fr.gildasledrogoff.tokenusage.core.widget

import android.content.Context
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.ui.i18n.spendPeriodLabelRes
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class WidgetQuotaPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val widgetPrefsManager: WidgetPrefsManager,
    private val exchangeRates: ExchangeRateRepository
) {
    
    suspend fun publish(entries: List<WidgetAccountQuota>) {
        if (entries.isEmpty()) return

        for ((service, accounts) in entries.groupBy { it.service }) {
            val previous = widgetPrefsManager.getCachedAccounts(service)
            val cached = accounts.mapNotNull { entry ->
                val quota = entry.quota
                    ?: return@mapNotNull previous.findPrevious(entry, accounts.size)
                CachedAccount(
                    accountLabel = entry.accountLabel,
                    accountNumber = entry.accountNumber,
                    tier = quota.tier,
                    windows = quota.windows.map { window ->
                        CachedWindow(window.label, window.utilization.toFloat(), window.resetsAt?.epochSecond)
                    },
                    balanceText = formatBalanceText(quota.accountBalances).takeIf { it.isNotEmpty() }
                        ?: formatSpendText(quota.spending).takeIf { it.isNotEmpty() }
                )
            }
            if (cached.isNotEmpty()) widgetPrefsManager.cacheAccounts(service, cached)
        }

        WidgetRegistry.refreshAll(context)
    }

    
    private fun List<CachedAccount>.findPrevious(
        entry: WidgetAccountQuota,
        accountCount: Int
    ): CachedAccount? {
        firstOrNull { it.accountLabel == entry.accountLabel && it.accountNumber == entry.accountNumber }
            ?.let { return it }
        // Single account on both sides (e.g. legacy cache without identity): same account.
        return singleOrNull()?.takeIf { accountCount == 1 }
            ?.copy(accountLabel = entry.accountLabel, accountNumber = entry.accountNumber)
    }

    
    suspend fun refreshWidgets() {
        WidgetRegistry.refreshAll(context)
    }

    
    private suspend fun formatSpendText(spending: List<SpendAmount>): String {
        val rates = displayRates(spending.map { it.currency })
        return spending.joinToString(" | ") { spend ->
            val label = context.getString(spendPeriodLabelRes(spend.period))
            "$label: ${formatAmount(spend.amount, spend.currency, rates[spend.currency])}"
        }
    }

    private suspend fun formatBalanceText(balances: List<AccountBalance>): String {
        val rates = displayRates(balances.mapNotNull { it.balance?.currency })
        return balances.joinToString(" | ") { account ->
            val balance = account.balance
            val label = account.label.ifBlank { context.getString(R.string.account_default) }
            when {
                account.error != null -> context.getString(R.string.widget_balance_error, label)
                balance != null ->
                    "$label: ${formatAmount(balance.totalBalance, balance.currency, rates[balance.currency])}"
                else -> "$label: -"
            }
        }
    }

    private suspend fun displayRates(currencies: List<String>): Map<String, FxRate> =
        currencies.toSet()
            .mapNotNull { currency -> exchangeRates.displayRate(currency)?.let { currency to it } }
            .toMap()

    
    private fun formatAmount(amount: Double, currency: String, rate: FxRate?): String =
        if (rate != null) {
            context.getString(R.string.fx_converted_value, rate.convert(amount), rate.to)
        } else {
            context.getString(R.string.history_balance_value, amount, currency)
        }
}


data class WidgetAccountQuota(
    val service: AiService,
    val accountLabel: String? = null,
    val accountNumber: Int? = null,
    val quota: QuotaInfo?
)
