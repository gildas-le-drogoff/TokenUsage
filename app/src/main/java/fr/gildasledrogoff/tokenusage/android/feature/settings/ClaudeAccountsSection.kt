package fr.gildasledrogoff.tokenusage.feature.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.util.iconRes
import fr.gildasledrogoff.tokenusage.ui.theme.dimens


@Composable
internal fun ClaudeAccountsSection(
    accounts: List<ClaudeAccountUi>,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onFieldChange: (accountId: String, field: String, value: String) -> Unit,
    onValidate: (String) -> Unit,
    onSessionKeyCaptured: (accountId: String, sessionKey: String) -> Unit
) {
    val dimens = MaterialTheme.dimens
    var expanded by rememberSaveable { mutableStateOf(false) }
    var signInAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    val configuredCount = accounts.count { it.isConfigured }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(dimens.cardPadding)) {
            ExpandableSectionHeader(
                iconRes = AiService.CLAUDE.iconRes,
                iconContentDescription = AiService.CLAUDE.displayName,
                title = AiService.CLAUDE.displayName,
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
                    Text(
                        text = stringResource(R.string.claude_accounts_hint) + "\n\n" +
                                stringResource(R.string.claude_sign_in_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (accounts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.claude_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    accounts.forEachIndexed { index, account ->
                        if (index > 0) HorizontalDivider()
                        ClaudeAccountEditor(
                            number = index + 1,
                            account = account,
                            onRemove = { onRemoveAccount(account.id) },
                            onRename = { renameAccountId = account.id },
                            onFieldChange = { field, value ->
                                onFieldChange(
                                    account.id,
                                    field,
                                    value
                                )
                            },
                            onValidate = { onValidate(account.id) },
                            onSignIn = { signInAccountId = account.id }
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

    signInAccountId?.let { accountId ->
        ClaudeLoginDialog(
            onDismiss = { signInAccountId = null },
            onSessionKeyCaptured = { key ->
                signInAccountId = null
                onSessionKeyCaptured(accountId, key)
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
                    onFieldChange(accountId, "label", newLabel)
                }
            )
        }
    }
}


@Composable
private fun RenameAccountDialog(
    account: ClaudeAccountUi,
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
                placeholder = { Text(stringResource(R.string.claude_label_placeholder)) },
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
private fun ClaudeAccountEditor(
    number: Int,
    account: ClaudeAccountUi,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onFieldChange: (field: String, value: String) -> Unit,
    onValidate: () -> Unit,
    onSignIn: () -> Unit
) {
    val dimens = MaterialTheme.dimens
    var showOAuth by rememberSaveable(account.id) { mutableStateOf(account.accessToken.isNotBlank()) }

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

        SettingsFields(
            { modifier ->
                OutlinedTextField(
                    value = account.sessionKey,
                    onValueChange = { onFieldChange("sessionKey", it) },
                    label = { Text(stringResource(R.string.field_session_key)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (account.hasStoredCredential && account.sessionKey.isBlank()) {
                                    R.string.stored_credential_hint
                                } else {
                                    R.string.field_session_key_hint
                                }
                            )
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = modifier,
                    singleLine = true
                )
            }
        )

        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
            Icon(
                painter = painterResource(R.drawable.ic_login_rounded),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.claude_sign_in))
        }

        val chevron by animateFloatAsState(if (showOAuth) 180f else 0f, label = "oauthChevron")
        TextButton(onClick = { showOAuth = !showOAuth }) {
            Text(stringResource(R.string.claude_oauth_toggle))
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Icon(
                painter = painterResource(R.drawable.ic_expand_more_rounded),
                contentDescription = stringResource(if (showOAuth) R.string.cd_collapse else R.string.cd_expand),
                modifier = Modifier
                    .size(ButtonDefaults.IconSize)
                    .rotate(chevron)
            )
        }
        AnimatedVisibility(visible = showOAuth) {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceS)) {
                SettingsFields(
                    { modifier ->
                        OutlinedTextField(
                            value = account.accessToken,
                            onValueChange = { onFieldChange("accessToken", it) },
                            label = { Text(stringResource(R.string.field_access_token)) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = modifier,
                            singleLine = true
                        )
                    },
                    { modifier ->
                        OutlinedTextField(
                            value = account.refreshToken,
                            onValueChange = { onFieldChange("refreshToken", it) },
                            label = { Text(stringResource(R.string.field_refresh_token)) },
                            supportingText = { Text(stringResource(R.string.field_refresh_token_hint)) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = modifier,
                            singleLine = true
                        )
                    }
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceM)
        ) {
            OutlinedButton(
                onClick = onValidate,
                enabled = !account.isValidating && account.isConfigured
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
