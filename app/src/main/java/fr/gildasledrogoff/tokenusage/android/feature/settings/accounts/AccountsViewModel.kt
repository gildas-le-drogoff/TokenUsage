package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.gildasledrogoff.tokenusage.core.data.CopilotDeviceFlow
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.provider.ProviderRegistry
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Key of the pseudo-field holding the account label in [AccountsViewModel.updateField]. */
const val FIELD_LABEL = "label"

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val copilotDeviceFlow: CopilotDeviceFlow,
    private val prefsManager: EncryptedPrefsManager,
    private val widgetQuotaPublisher: WidgetQuotaPublisher,
    private val widgetPrefs: WidgetPrefsManager,
    private val accountsSync: AccountsSync
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    /** Providers in display order: the screen builds one section per provider. */
    val providers: List<QuotaProvider> get() = registry.providers

    private val pendingChanges = MutableStateFlow<AiService?>(null)

    private val pendingEdits = object : AccountsSync.PendingEdits {
        override fun flush() {
            pendingChanges.value?.let(::persist)
        }

        override fun discard() {
            pendingChanges.value = null
        }
    }

    init {
        loadPersistedState()
        observePendingChanges()
        accountsSync.register(pendingEdits)
        viewModelScope.launch {
            accountsSync.externalChanges.collect { reloadAfterExternalChange() }
        }
    }

    private fun loadPersistedState() {
        _uiState.value = AccountsUiState(
            accounts = registry.providers.associate { provider ->
                provider.service to provider.accounts().map { it.toUi(provider) }
            },
            options = registry.providers.flatMap { provider ->
                provider.form.options.map { option ->
                    AccountsUiState.optionKey(provider.service, option.key) to provider.optionValue(option.key)
                }
            }.toMap()
        )
    }

    /** Stored accounts were replaced elsewhere (backup restore, wipe): start over from the prefs. */
    private fun reloadAfterExternalChange() {
        cancelDeviceLogin()
        pendingChanges.value = null
        loadPersistedState()
    }

    override fun onCleared() {
        pendingChanges.value?.let(::persist)
        accountsSync.unregister(pendingEdits)
        super.onCleared()
    }

    // --- Editing -------------------------------------------------------------------------

    fun addAccount(service: AiService) {
        updateAccounts(service) { it + AccountUi(id = UUID.randomUUID().toString()) }
    }

    fun removeAccount(service: AiService, accountId: String) {
        updateAccounts(service) { list -> list.filterNot { it.id == accountId } }
        prefsManager.deleteAccount(service, accountId)
        if (!prefsManager.hasCredential(service)) {
            widgetPrefs.removeServiceEverywhere(service)
            viewModelScope.launch { widgetQuotaPublisher.refreshWidgets() }
        }
    }

    /** [FIELD_LABEL] renames the account; any other key is a [CredentialField][fr.gildasledrogoff.tokenusage.core.provider.CredentialField]. */
    fun updateField(service: AiService, accountId: String, key: String, value: String) {
        updateAccount(service, accountId) { account ->
            if (key == FIELD_LABEL) {
                account.copy(label = value)
            } else {
                account.copy(values = account.values + (key to value), credentialEdited = true, validationResult = null)
            }
        }
        scheduleSave(service)
    }

    fun setOption(service: AiService, key: String, value: Boolean) {
        registry.provider(service).setOption(key, value)
        _uiState.update { it.copy(options = it.options + (AccountsUiState.optionKey(service, key) to value)) }
    }

    fun validate(service: AiService, accountId: String) {
        persist(service)
        if (pendingChanges.value == service) pendingChanges.value = null
        updateAccount(service, accountId) { it.copy(isValidating = true, validationResult = null) }

        viewModelScope.launch {
            val stored = prefsManager.getAccounts(service).find { it.id == accountId }
            val result = if (stored == null) {
                Result.Failure(AppError.CredentialNotFound(service))
            } else {
                registry.provider(service).validate(stored)
            }
            val validation = when (result) {
                is Result.Success -> ValidationResult.Success
                is Result.Failure -> ValidationResult.Failure(result.error)
            }
            updateAccount(service, accountId) { it.copy(isValidating = false, validationResult = validation) }
        }
    }

    /** A sign-in (web session, device flow) produced a credential value: store it and check it. */
    fun applyLogin(service: AiService, accountId: String, fieldKey: String, value: String) {
        updateField(service, accountId, fieldKey, value.trim())
        validate(service, accountId)
    }

    // --- Persistence -----------------------------------------------------------------------

    @OptIn(FlowPreview::class)
    private fun observePendingChanges() {
        viewModelScope.launch {
            pendingChanges
                .debounce(SAVE_DEBOUNCE_MS)
                .collect { service -> service?.let(::persist) }
        }
    }

    private fun scheduleSave(service: AiService) {
        pendingChanges.value?.let { pending -> if (pending != service) persist(pending) }
        pendingChanges.value = service
    }

    /** Writes the complete accounts of [service]; drafts without a usable credential stay in the editor only. */
    private fun persist(service: AiService) {
        val form = registry.provider(service).form
        val stored = prefsManager.getAccounts(service).associateBy { it.id }
        val saved = _uiState.value.accounts[service].orEmpty().mapNotNull { account ->
            val previous = stored[account.id]
            val credential = when {
                !account.credentialEdited && previous != null -> previous.credential
                else -> form.assemble(account.values, previous?.credential)
                    ?: previous?.credential
                    ?: return@mapNotNull null
            }
            ServiceAccount(id = account.id, label = account.label.trim(), credential = credential)
        }
        prefsManager.saveAccounts(service, saved)

        val byId = saved.associateBy { it.id }
        updateAccounts(service) { list ->
            list.map { account ->
                val savedAccount = byId[account.id] ?: return@map account
                account.copy(
                    values = account.values.filterKeys { it !in form.secretKeys } + form.prefill(savedAccount.credential),
                    hasStoredCredential = true,
                    credentialEdited = false
                )
            }
        }
    }

    // --- Device flow (GitHub Copilot) ----------------------------------------------------------

    private var deviceLoginJob: Job? = null

    /** Runs the OAuth device flow and stores the resulting token in [accountId]. */
    fun startDeviceLogin(service: AiService, accountId: String) {
        val login = registry.provider(service).form.login ?: return
        deviceLoginJob?.cancel()
        _uiState.update { it.copy(deviceLogin = DeviceLoginUi(service, accountId)) }
        deviceLoginJob = viewModelScope.launch {
            val code = when (val started = copilotDeviceFlow.start()) {
                is Result.Success -> started.value
                is Result.Failure -> {
                    _uiState.update { it.copy(deviceLogin = DeviceLoginUi(service, accountId, error = started.error)) }
                    return@launch
                }
            }
            _uiState.update {
                it.copy(
                    deviceLogin = DeviceLoginUi(
                        service, accountId, userCode = code.userCode, verificationUri = code.verificationUri
                    )
                )
            }
            when (val token = copilotDeviceFlow.awaitToken(code)) {
                is Result.Success -> {
                    _uiState.update { it.copy(deviceLogin = null) }
                    applyLogin(service, accountId, login.targetFieldKey, token.value)
                }
                is Result.Failure -> _uiState.update { state ->
                    state.copy(deviceLogin = state.deviceLogin?.copy(error = token.error))
                }
            }
        }
    }

    fun cancelDeviceLogin() {
        deviceLoginJob?.cancel()
        deviceLoginJob = null
        _uiState.update { it.copy(deviceLogin = null) }
    }

    /** Retry from the error state of the dialog. */
    fun retryDeviceLogin() {
        _uiState.value.deviceLogin?.let { startDeviceLogin(it.service, it.accountId) }
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun updateAccounts(service: AiService, transform: (List<AccountUi>) -> List<AccountUi>) {
        _uiState.update { state ->
            state.copy(accounts = state.accounts + (service to transform(state.accounts[service].orEmpty())))
        }
    }

    private fun updateAccount(service: AiService, accountId: String, transform: (AccountUi) -> AccountUi) {
        updateAccounts(service) { list -> list.map { if (it.id == accountId) transform(it) else it } }
    }

    private fun ServiceAccount.toUi(provider: QuotaProvider) = AccountUi(
        id = id,
        label = label,
        values = provider.form.prefill(credential),
        hasStoredCredential = true
    )

    private companion object {
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
