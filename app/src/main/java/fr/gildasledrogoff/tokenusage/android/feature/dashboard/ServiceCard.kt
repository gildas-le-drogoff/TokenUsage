package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.util.ServiceIcon
import fr.gildasledrogoff.tokenusage.core.util.iconRes
import fr.gildasledrogoff.tokenusage.ui.i18n.appErrorMessage
import fr.gildasledrogoff.tokenusage.ui.i18n.fxRateNote
import fr.gildasledrogoff.tokenusage.ui.i18n.spendPeriodLabelRes
import fr.gildasledrogoff.tokenusage.ui.theme.QuotaStatus
import fr.gildasledrogoff.tokenusage.ui.theme.colors
import fr.gildasledrogoff.tokenusage.ui.theme.dimens
import fr.gildasledrogoff.tokenusage.ui.theme.quotaStatusOf


@Composable
fun ServiceCard(
    cardData: ServiceCardData,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    expandedContent: @Composable ColumnScope.() -> Unit = {}
) {
    val dimens = MaterialTheme.dimens
    val shape = RoundedCornerShape(dimens.cardCorner)
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier
                .padding(dimens.cardPadding)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceM)
        ) {
            CardHeader(cardData, expandable = onToggleExpand != null, expanded = expanded)
            CardContent(cardData)
            if (onToggleExpand != null) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceS), content = expandedContent)
                }
            }
        }
    }

    if (onToggleExpand != null) {
        Card(
            onClick = onToggleExpand,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = colors,
            border = border,
            content = body
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = colors,
            border = border,
            content = body
        )
    }
}

@Composable
private fun CardHeader(cardData: ServiceCardData, expandable: Boolean, expanded: Boolean) {
    val dimens = MaterialTheme.dimens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
        modifier = Modifier.fillMaxWidth()
    ) {
        ServiceIcon(
            iconRes = cardData.service.iconRes,
            contentDescription = null,
            size = dimens.serviceIcon
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)
            ) {
                Text(
                    text = cardData.service.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                cardData.tier?.let { tier ->
                    Surface(
                        shape = RoundedCornerShape(dimens.spaceXs),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = tier,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = dimens.spaceS, vertical = dimens.spaceXs / 2),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            val accountName = cardData.accountLabel
                ?: cardData.accountNumber?.let { stringResource(R.string.account_n, it) }
            accountName?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        cardStatus(cardData)?.let { StatusPill(it) }

        if (expandable) {
            val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
            val stateText = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand)
            Icon(
                painter = painterResource(R.drawable.ic_expand_more_rounded),
                contentDescription = stateText,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .rotate(rotation)
                    .semantics { stateDescription = stateText }
            )
        }
    }
}


private fun cardStatus(cardData: ServiceCardData): QuotaStatus? {
    if (cardData.error != null) return QuotaStatus.CRITICAL
    if (cardData.windows.isEmpty()) return null
    return cardData.windows.maxOf { quotaStatusOf(1.0 - it.utilization, it.severity) }
}

@Composable
fun StatusPill(status: QuotaStatus, modifier: Modifier = Modifier) {
    val dimens = MaterialTheme.dimens
    val pair = status.colors
    Surface(shape = CircleShape, color = pair.container, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceXs),
            modifier = Modifier.padding(horizontal = dimens.spaceS, vertical = dimens.spaceXs)
        ) {
            StatusDot(status)
            Text(
                text = quotaStatusLabel(status),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = pair.onContainer
            )
        }
    }
}

@Composable
fun StatusDot(status: QuotaStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(MaterialTheme.dimens.spaceS)
            .clip(CircleShape)
            .background(status.colors.main)
    )
}

@Composable
private fun CardContent(cardData: ServiceCardData) {
    val dimens = MaterialTheme.dimens

    if (cardData.error != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_error_rounded),
                contentDescription = stringResource(R.string.cd_error),
                tint = QuotaStatus.CRITICAL.colors.main,
                modifier = Modifier.size(dimens.inlineIcon + dimens.spaceXs)
            )
            Text(
                text = appErrorMessage(cardData.error),
                style = MaterialTheme.typography.bodyMedium,
                color = QuotaStatus.CRITICAL.colors.main
            )
        }
        return
    }

    if (cardData.windows.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceM)) {
            cardData.windows.forEach { window ->
                QuotaGaugeBar(
                    utilization = window.utilization,
                    label = window.label,
                    resetsAt = window.resetsAt,
                    severity = window.severity
                )
            }
        }
    }

    cardData.extraUsage?.let { extra ->
        QuotaGaugeBar(
            utilization = extra.utilization,
            label = stringResource(R.string.card_extra_credits),
            detail = extra.rate?.let { rate ->
                stringResource(
                    R.string.card_credits_converted,
                    rate.convert(extra.usedCredits),
                    rate.convert(extra.monthlyLimit),
                    rate.to
                )
            } ?: stringResource(R.string.card_credits, extra.usedCredits, extra.monthlyLimit, extra.currency)
        )
    }

    val breakdownRows = cardData.weeklyBreakdown.filter { it.percent >= 1.0 }
    if (breakdownRows.isNotEmpty()) {
        WeeklyBreakdown(breakdownRows)
    }

    if (cardData.accountBalances.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
            Text(
                text = stringResource(R.string.card_balance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceM)) {
                cardData.accountBalances.forEach { account ->
                    AccountBalanceRow(account)
                }
            }
        }
    }

    if (cardData.spending.isNotEmpty()) {
        SpendSection(
            rows = cardData.spending,
            showRateNote = cardData.accountBalances.none { it.error == null && it.rate != null }
        )
    }
}


@Composable
private fun SpendSection(rows: List<SpendRowUi>, showRateNote: Boolean) {
    val dimens = MaterialTheme.dimens
    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
        Text(
            text = stringResource(R.string.card_spend),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        rows.forEach { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)
            ) {
                Text(
                    text = stringResource(spendPeriodLabelRes(row.period)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = row.rate?.let { rate ->
                            stringResource(R.string.fx_converted_value, rate.convert(row.amount), rate.to)
                        } ?: stringResource(R.string.history_balance_value, row.amount, row.currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (row.rate != null) {
                        Text(
                            text = stringResource(R.string.history_balance_value, row.amount, row.currency),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        val rate = rows.firstNotNullOfOrNull { it.rate }
        if (showRateNote && rate != null) {
            Text(
                text = fxRateNote(rate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun WeeklyBreakdown(rows: List<UsageBreakdownRowUi>) {
    val dimens = MaterialTheme.dimens
    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
        Text(
            text = stringResource(R.string.card_usage_this_week),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        rows.forEach { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(BREAKDOWN_LABEL_WEIGHT)
                )
                Box(
                    modifier = Modifier
                        .weight(1f - BREAKDOWN_LABEL_WEIGHT)
                        .height(dimens.spaceXs)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((row.percent / 100.0).toFloat().coerceIn(0f, 1f))
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                }
                Text(
                    text = stringResource(R.string.gauge_percent_value, row.percent.toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


@Composable
private fun AccountBalanceRow(account: AccountBalanceUi) {
    val dimens = MaterialTheme.dimens
    val status = if (account.error != null || !account.isAvailable || account.totalBalance <= 0.0) {
        QuotaStatus.CRITICAL
    } else {
        QuotaStatus.HEALTHY
    }
    val statusColor = status.colors.main

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)
        ) {
            StatusDot(status)
            Text(
                text = account.label.ifBlank { stringResource(R.string.account_default) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = when {
                    account.error != null -> accountErrorText(account.error)
                    account.rate != null -> stringResource(
                        R.string.fx_converted_value,
                        account.rate.convert(account.totalBalance),
                        account.rate.to
                    )
                    else -> stringResource(R.string.history_balance_value, account.totalBalance, account.currency)
                },
                style = if (account.error != null) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }

        val captions = buildList {
            if (account.error == null && account.rate != null) {
                add(
                    stringResource(R.string.history_balance_value, account.totalBalance, account.currency) +
                        " · " + fxRateNote(account.rate)
                )
            }
            if (account.error == null && account.grantedBalance != null && account.grantedBalance > 0.0) {
                add(
                    if (account.rate != null) {
                        stringResource(R.string.card_granted, account.rate.convert(account.grantedBalance), account.rate.to)
                    } else {
                        stringResource(R.string.card_granted, account.grantedBalance, account.currency)
                    }
                )
            }
        }
        captions.forEach { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = dimens.spaceS + dimens.spaceS)
            )
        }
        if (account.error == null && !account.isAvailable) {
            Text(
                text = stringResource(R.string.card_insufficient_balance),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                modifier = Modifier.padding(start = dimens.spaceS + dimens.spaceS)
            )
        }
    }
}


@Composable
private fun accountErrorText(error: AppError): String = when (error) {
    is AppError.AuthError ->
        if (error.isTerminal) stringResource(R.string.error_invalid_api_key)
        else stringResource(R.string.error_auth)
    else -> appErrorMessage(error)
}


private const val BREAKDOWN_LABEL_WEIGHT = 0.4f
