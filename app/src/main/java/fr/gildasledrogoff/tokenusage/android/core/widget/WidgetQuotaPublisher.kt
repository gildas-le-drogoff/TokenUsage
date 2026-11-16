package fr.gildasledrogoff.tokenusage.core.widget

import android.content.Context
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
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
    
    suspend fun publish(quotas: List<QuotaInfo>) {
        val perService = quotas.distinctBy { it.service }
        if (perService.isEmpty()) return

        for (quota in perService) {
            val windows = quota.windows.map { window ->
                Triple(window.label, window.utilization, window.resetsAt?.epochSecond)
            }
            widgetPrefsManager.cacheAllQuotaData(quota.service, windows)
            widgetPrefsManager.cacheTier(quota.service, quota.tier)
            val balanceText = formatBalanceText(quota.accountBalances).takeIf { it.isNotEmpty() }
                ?: formatSpendText(quota.spending).takeIf { it.isNotEmpty() }
            widgetPrefsManager.cacheBalanceText(quota.service, balanceText)
        }

        WidgetRegistry.refreshAll(context)
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
