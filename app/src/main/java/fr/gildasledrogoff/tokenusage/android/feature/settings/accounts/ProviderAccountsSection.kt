package fr.gildasledrogoff.tokenusage.feature.settings.accounts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.provider.CredentialField
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.LoginMethod
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.core.util.iconRes
import fr.gildasledrogoff.tokenusage.feature.settings.ConfirmDeleteIconButton
import fr.gildasledrogoff.tokenusage.feature.settings.SettingsFields
import fr.gildasledrogoff.tokenusage.ui.theme.dimens

@Composable
internal fun ProviderAccountsSection(
    provider: QuotaProvider,
    accounts: List<AccountUi>,
    optionValue: (String) -> Boolean,
    onAddAccount: () -> Unit,
    onRemoveAccount: (accountId: String) -> Unit,
    onFieldChange: (accountId: String, key: String, value: String) -> Unit,
    onValidate: (accountId: String) -> Unit,
    onOptionChange: (key: String, value: Boolean) -> Unit,
    onSignIn: (accountId: String) -> Unit
) {
    val service = provider.service
    val form = provider.form
    val dimens = MaterialTheme.dimens
    var expanded by rememberSaveable(service.name) { mutableStateOf(false) }
    var webLoginAccountId by rememberSaveable(service.name) { mutableStateOf<String?>(null) }
    var renameAccountId by rememberSaveable(service.name) { mutableStateOf<String?>(null) }
    val configuredCount = accounts.count { it.isConfigured(form) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(dimens.cardPadding)) {
            ExpandableSectionHeader(
                iconRes = service.iconRes,
                iconContentDescription = service.displayName,
                title = service.displayName,
                statusLabel = accountStatus(configuredCount),
                configured = configuredCount > 0,
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = dimens.spaceS),
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceM)
                ) {
                    val hintRes = form.options
                        .firstOrNull { optionValue(it.key) && it.hintWhenOnRes != null }?.hintWhenOnRes
                        ?: form.hintRes
                    val hintParts = listOfNotNull(hintRes, form.login?.hintRes)
                    val hintTexts = hintParts.map { stringResource(it) }
                    val hint = hintTexts.joinToString("\n\n")
                    if (hint.isNotEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    form.options.forEach { option ->
                        val checked = optionValue(option.key)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { onOptionChange(option.key, it) }
                                )
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(dimens.spaceS))
                            Text(
                                text = stringResource(option.labelRes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    if (accounts.isEmpty()) {
                        Text(
                            text = stringResource(form.emptyRes ?: R.string.accounts_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    accounts.forEachIndexed { index, account ->
                        if (index > 0) HorizontalDivider()
                        AccountEditor(
                            form = form,
                            number = index + 1,
                            account = account,
                            onRemove = { onRemoveAccount(account.id) },
                            onRename = { renameAccountId = account.id },
                            onFieldChange = { key, value -> onFieldChange(account.id, key, value) },
                            onValidate = { onValidate(account.id) },
                            onSignIn = {
                                if (form.login?.method == LoginMethod.WEB_SESSION) {
                                    webLoginAccountId = account.id
                                } else {
                                    onSignIn(account.id)
                                }
                            }
                        )
                    }
                    OutlinedButton(onClick = onAddAccount) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add_rounded),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_add_account))
                    }
                }
            }
        }
    }
    webLoginAccountId?.let { accountId ->
        ClaudeLoginDialog(
            onDismiss = { webLoginAccountId = null },
            onSessionKeyCaptured = { key ->
                webLoginAccountId = null
                form.login?.let { login -> onFieldChange(accountId, login.targetFieldKey, key.trim()) }
                onValidate(accountId)
            }
        )
    }
    renameAccountId?.let { accountId ->
        accounts.firstOrNull { it.id == accountId }?.let { account ->
            RenameAccountDialog(
                account = account,
                onDismiss = { renameAccountId = null },
                onRename = { newLabel ->
                    renameAccountId = null
                    onFieldChange(accountId, FIELD_LABEL, newLabel)
                }
            )
        }
    }
}
@Composable
private fun RenameAccountDialog(
    account: AccountUi,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var draft by remember(account.id) { mutableStateOf(account.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_account_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.field_label_optional)) },
                placeholder = { Text(stringResource(R.string.accounts_label_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(draft) }) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
@Composable
private fun AccountEditor(
    form: CredentialForm,
    number: Int,
    account: AccountUi,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onFieldChange: (key: String, value: String) -> Unit,
    onValidate: () -> Unit,
    onSignIn: () -> Unit
) {
    val dimens = MaterialTheme.dimens
    val (advancedFields, mainFields) = form.fields.partition { it.advanced }
    var showAdvanced by rememberSaveable(account.id) {
        mutableStateOf(advancedFields.any { !account.values[it.key].isNullOrBlank() })
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceS)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = account.label.trim().ifBlank { stringResource(R.string.account_n, number) },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRename) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit_rounded),
                    contentDescription = stringResource(R.string.cd_rename_account)
                )
            }
            ConfirmDeleteIconButton(
                contentDescription = stringResource(R.string.cd_remove_account),
                onConfirmed = onRemove
            )
        }
        FieldGroup(mainFields, account, onFieldChange)
        form.login?.let { login ->
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    painter = painterResource(R.drawable.ic_login_rounded),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(login.labelRes))
            }
        }
        if (advancedFields.isNotEmpty()) {
            val chevron by animateFloatAsState(if (showAdvanced) 180f else 0f, label = "advancedChevron")
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(stringResource(form.advancedToggleRes ?: R.string.accounts_advanced_toggle))
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more_rounded),
                    contentDescription = stringResource(if (showAdvanced) R.string.cd_collapse else R.string.cd_expand),
                    modifier = Modifier
                        .size(ButtonDefaults.IconSize)
                        .rotate(chevron)
                )
            }
            AnimatedVisibility(visible = showAdvanced) {
                FieldGroup(advancedFields, account, onFieldChange)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceM)
        ) {
            OutlinedButton(
                onClick = onValidate,
                enabled = !account.isValidating && account.isConfigured(form)
            ) {
                if (account.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(stringResource(R.string.action_validate))
            }
            ValidationResultLabel(account.validationResult, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FieldGroup(
    fields: List<CredentialField>,
    account: AccountUi,
    onFieldChange: (key: String, value: String) -> Unit
) {
    val visible = fields.filter { !it.readOnly || !account.values[it.key].isNullOrBlank() }
    if (visible.isEmpty()) return
    SettingsFields(
        *visible.map { field ->
            @Composable { modifier: Modifier ->
                CredentialTextField(field, account, modifier) { onFieldChange(field.key, it) }
            }
        }.toTypedArray()
    )
}
@Composable
private fun CredentialTextField(
    field: CredentialField,
    account: AccountUi,
    modifier: Modifier,
    onValueChange: (String) -> Unit
) {
    val value = account.values[field.key].orEmpty()
    val hintRes = when {
        field.secret && account.hasStoredCredential && value.isBlank() -> R.string.stored_credential_hint
        else -> field.hintRes
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(field.labelRes)) },
        supportingText = hintRes?.let { res -> { Text(stringResource(res)) } },
        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
        readOnly = field.readOnly,
        enabled = !field.readOnly,
        singleLine = true,
        modifier = modifier
    )
}
