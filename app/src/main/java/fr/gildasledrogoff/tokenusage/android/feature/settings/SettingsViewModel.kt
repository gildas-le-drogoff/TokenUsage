package fr.gildasledrogoff.tokenusage.feature.settings

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.gildasledrogoff.tokenusage.core.backup.BackupCrypto
import fr.gildasledrogoff.tokenusage.core.backup.BackupCryptoException
import fr.gildasledrogoff.tokenusage.core.backup.ConfigBackupManager
import fr.gildasledrogoff.tokenusage.core.backup.InvalidBackupException
import fr.gildasledrogoff.tokenusage.core.backup.QrChunkCodec
import fr.gildasledrogoff.tokenusage.core.backup.QrPayloadTooLargeException
import fr.gildasledrogoff.tokenusage.core.data.CopilotDeviceFlow
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ClaudeAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.Currencies
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ZaiAccount
import fr.gildasledrogoff.tokenusage.core.domain.repository.ClaudeQuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.MultiAccountQuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.QuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ZaiRepository
import fr.gildasledrogoff.tokenusage.core.notification.QuotaNotificationService
import fr.gildasledrogoff.tokenusage.core.security.AppLockManager
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.core.tile.QuotaTileService
import fr.gildasledrogoff.tokenusage.core.workmanager.RefreshScheduler
import fr.gildasledrogoff.tokenusage.di.ClaudeRepository
import fr.gildasledrogoff.tokenusage.di.CodexRepository
import fr.gildasledrogoff.tokenusage.di.CopilotRepository
import fr.gildasledrogoff.tokenusage.di.DeepseekRepository
import fr.gildasledrogoff.tokenusage.di.GeminiRepository
import fr.gildasledrogoff.tokenusage.di.OpenAiRepository
import fr.gildasledrogoff.tokenusage.di.OpenRouterRepository
import fr.gildasledrogoff.tokenusage.di.ZaiRepositoryQualifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ClaudeRepository private val claudeRepository: ClaudeQuotaRepository,
    @CodexRepository private val codexRepository: QuotaRepository,
    @GeminiRepository private val geminiRepository: QuotaRepository,
    @DeepseekRepository private val deepseekRepository: MultiAccountQuotaRepository,
    @ZaiRepositoryQualifier private val zaiRepository: ZaiRepository,
    @CopilotRepository private val copilotRepository: QuotaRepository,
    @OpenRouterRepository private val openRouterRepository: QuotaRepository,
    @OpenAiRepository private val openAiRepository: QuotaRepository,
    private val copilotDeviceFlow: CopilotDeviceFlow,
    private val prefsManager: EncryptedPrefsManager,
    private val backupManager: ConfigBackupManager,
    private val refreshScheduler: RefreshScheduler,
    private val demoData: DemoDataRepository,
    private val exchangeRates: ExchangeRateRepository,
    private val displayPreferences: DisplayPreferencesRepository,
    private val widgetQuotaPublisher: WidgetQuotaPublisher,
    private val widgetPrefs: WidgetPrefsManager,
    private val appLock: AppLockManager,
    private val notificationService: QuotaNotificationService,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val saveJobs = mutableMapOf<AiService, Job>()
    private val pendingChanges = MutableStateFlow<Pair<AiService, String>?>(null)

    init {
        loadPersistedState()
        observePendingChanges()
    }

    
    private fun loadPersistedState() {
        loadSavedCredentials()
        _uiState.update {
            it.copy(
                claudeAccounts = prefsManager.getClaudeAccounts().map { account -> account.toUi() },
                deepseekAccounts = prefsManager.getDeepseekAccounts().map { account ->
                    DeepseekAccountUi(id = account.id, label = account.label, apiKey = account.apiKey)
                },
                zaiAccounts = prefsManager.getZaiAccounts().map { account ->
                    ZaiAccountUi(id = account.id, label = account.label, apiKey = account.apiKey)
                },
                zaiHideMonthlyTools = prefsManager.isZaiMonthlyToolsHidden(),
                currencyConversion = loadCurrencyConversion(),
                refreshIntervalMinutes = prefsManager.getRefreshInterval(),
                themeMode = displayPreferences.themeMode.value,
                quotaDisplayMode = displayPreferences.quotaDisplayMode.value,
                lockScreenVisibility = displayPreferences.lockScreenVisibility.value,
                notificationsEnabled = prefsManager.isNotificationsEnabled(),
                appLockEnabled = appLock.isEnabled(),
                demoDataAvailable = demoData.isAvailable,
                demoDataEnabled = demoData.isEnabled()
            )
        }
    }

    override fun onCleared() {
        pendingChanges.value?.let { (service, _) -> saveCredential(service) }
        super.onCleared()
    }

    
    private fun loadSavedCredentials() {
        for (service in AiService.entries) {
            if (service == AiService.CLAUDE || service == AiService.DEEPSEEK || service == AiService.ZAI) continue
            val credential = prefsManager.loadCredential(service) ?: continue
            val state = when (credential) {
                is Credential.ClaudeCredential -> ServiceCredentialState(isStored = true)
                is Credential.ClaudeWebCredential -> ServiceCredentialState(isStored = true)
                is Credential.CodexCredential -> ServiceCredentialState(
                    isStored = true,
                    accountId = credential.accountId.orEmpty()
                )
                is Credential.GeminiCredential -> ServiceCredentialState(
                    isStored = true,
                    oauthClientId = credential.oauthClientId,
                    expiresAtDisplay = formatExpiryMs(credential.expiresAtMs)
                )
                is Credential.DeepseekCredential -> ServiceCredentialState(isStored = true)
                is Credential.ZaiCredential -> ServiceCredentialState(isStored = true)
                is Credential.ApiKeyCredential -> ServiceCredentialState(isStored = true)
            }
            _uiState.update {
                it.copy(serviceStates = it.serviceStates + (service to state))
            }
        }
    }

    fun updateField(service: AiService, field: String, value: String) {
        _uiState.update { state ->
            val current = state.serviceStates[service] ?: ServiceCredentialState()
            val updated = when (field) {
                "accessToken" -> current.copy(accessToken = value, validationResult = null)
                "refreshToken" -> current.copy(refreshToken = value, validationResult = null)
                "sessionKey" -> current.copy(sessionKey = value, validationResult = null)
                "accountId" -> current.copy(accountId = value, validationResult = null)
                "oauthClientId" -> current.copy(oauthClientId = value, validationResult = null)
                "oauthClientSecret" -> current.copy(oauthClientSecret = value, validationResult = null)
                else -> current
            }
            state.copy(serviceStates = state.serviceStates + (service to updated))
        }

        scheduleSave(service)
    }

    @OptIn(FlowPreview::class)
    private fun observePendingChanges() {
        viewModelScope.launch {
            pendingChanges
                .debounce(500)
                .collect { pair ->
                    pair?.let { (service, _) -> saveCredential(service) }
                }
        }
    }

    private fun scheduleSave(service: AiService) {
        pendingChanges.value?.let { (pending, _) -> if (pending != service) saveCredential(pending) }
        pendingChanges.value = service to System.currentTimeMillis().toString()
    }

    
    private fun saveCredential(service: AiService) {
        if (service == AiService.CLAUDE) {
            persistClaudeAccounts()
            return
        }
        if (service == AiService.DEEPSEEK) {
            persistDeepseekAccounts()
            return
        }
        if (service == AiService.ZAI) {
            persistZaiAccounts()
            return
        }

        val state = _uiState.value.serviceStates[service] ?: return
        val stored = prefsManager.loadCredential(service)

        if (service.usesSingleApiKey) {
            val key = state.accessToken.trim()
            if (key.isNotEmpty()) {
                prefsManager.saveCredential(service, Credential.ApiKeyCredential(accessToken = key))
                clearSecretDrafts(service)
            }
            return
        }

        val credential = when (service) {
            AiService.CLAUDE -> return
            AiService.CODEX -> {
                val storedCodex = stored as? Credential.CodexCredential
                val accessToken = state.accessToken.trim().ifBlank { storedCodex?.accessToken.orEmpty() }
                val refreshToken = state.refreshToken.trim().ifBlank { storedCodex?.refreshToken.orEmpty() }
                if (accessToken.isBlank() || refreshToken.isBlank()) return
                Credential.CodexCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accountId = state.accountId.trim().ifBlank { storedCodex?.accountId }
                )
            }
            AiService.GEMINI -> {
                val storedGemini = stored as? Credential.GeminiCredential
                val accessToken = state.accessToken.trim().ifBlank { storedGemini?.accessToken.orEmpty() }
                val refreshToken = state.refreshToken.trim().ifBlank { storedGemini?.refreshToken.orEmpty() }
                val clientId = state.oauthClientId.trim().ifBlank { storedGemini?.oauthClientId.orEmpty() }
                val clientSecret = state.oauthClientSecret.trim().ifBlank { storedGemini?.oauthClientSecret.orEmpty() }
                if (accessToken.isBlank() || refreshToken.isBlank() || clientId.isBlank() || clientSecret.isBlank()) return
                Credential.GeminiCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMs = System.currentTimeMillis() + 3600_000,
                    oauthClientId = clientId,
                    oauthClientSecret = clientSecret,
                    hostedDomain = storedGemini?.hostedDomain?.takeIf { refreshToken == storedGemini.refreshToken }
                )
            }
            AiService.DEEPSEEK -> return
            AiService.ZAI -> return
            AiService.COPILOT, AiService.OPENROUTER, AiService.OPENAI -> return
        }

        prefsManager.saveCredential(service, credential)
        clearSecretDrafts(service)
    }

    
    private fun clearSecretDrafts(service: AiService) {
        _uiState.update { state ->
            val current = state.serviceStates[service] ?: return@update state
            state.copy(
                serviceStates = state.serviceStates + (service to current.copy(
                    accessToken = "",
                    refreshToken = "",
                    sessionKey = "",
                    oauthClientSecret = "",
                    isStored = true
                ))
            )
        }
    }

    
    fun removeCredential(service: AiService) {
        prefsManager.deleteCredential(service)
        widgetPrefs.removeServiceEverywhere(service)
        _uiState.update { state ->
            state.copy(serviceStates = state.serviceStates + (service to ServiceCredentialState()))
        }
        viewModelScope.launch {
            widgetQuotaPublisher.refreshWidgets()
        }
    }


    fun addClaudeAccount() {
        _uiState.update { state ->
            state.copy(claudeAccounts = state.claudeAccounts + ClaudeAccountUi(id = UUID.randomUUID().toString()))
        }
    }

    fun removeClaudeAccount(accountId: String) {
        _uiState.update { state ->
            state.copy(claudeAccounts = state.claudeAccounts.filterNot { it.id == accountId })
        }
        prefsManager.deleteClaudeAccount(accountId)
        scheduleSave(AiService.CLAUDE)
    }

    fun updateClaudeAccount(accountId: String, field: String, value: String) {
        _uiState.update { state ->
            state.copy(
                claudeAccounts = state.claudeAccounts.map { account ->
                    if (account.id != accountId) account else when (field) {
                        "label" -> account.copy(label = value)
                        "sessionKey" -> account.copy(sessionKey = value, validationResult = null, credentialEdited = true)
                        "accessToken" -> account.copy(accessToken = value, validationResult = null, credentialEdited = true)
                        "refreshToken" -> account.copy(refreshToken = value, validationResult = null, credentialEdited = true)
                        else -> account
                    }
                }
            )
        }
        scheduleSave(AiService.CLAUDE)
    }

    
    fun applyCapturedSessionKey(accountId: String, sessionKey: String) {
        updateClaudeAccount(accountId, "sessionKey", sessionKey.trim())
        validateClaudeAccount(accountId)
    }

    fun validateClaudeAccount(accountId: String) {
        persistClaudeAccounts()
        if (pendingChanges.value?.first == AiService.CLAUDE) pendingChanges.value = null
        updateClaudeAccountState(accountId) { it.copy(isValidating = true, validationResult = null) }

        viewModelScope.launch {
            val validationResult = when (val result = claudeRepository.validateAccount(accountId)) {
                is Result.Success -> ValidationResult.Success
                is Result.Failure -> ValidationResult.Failure(result.error)
            }
            updateClaudeAccountState(accountId) { it.copy(isValidating = false, validationResult = validationResult) }
        }
    }

    private fun updateClaudeAccountState(accountId: String, transform: (ClaudeAccountUi) -> ClaudeAccountUi) {
        _uiState.update { state ->
            state.copy(claudeAccounts = state.claudeAccounts.map { if (it.id == accountId) transform(it) else it })
        }
    }

    
    private fun persistClaudeAccounts() {
        val stored = prefsManager.getClaudeAccounts().associateBy { it.id }
        val accounts = _uiState.value.claudeAccounts.mapNotNull { account ->
            val previous = stored[account.id]
            val credential = when {
                !account.credentialEdited && previous != null -> previous.credential
                else -> account.toCredential() ?: previous?.credential ?: return@mapNotNull null
            }
            ClaudeAccount(id = account.id, label = account.label.trim(), credential = credential)
        }
        prefsManager.saveClaudeAccounts(accounts)
        _uiState.update { state ->
            val savedIds = accounts.map { it.id }.toSet()
            state.copy(claudeAccounts = state.claudeAccounts.map {
                if (it.id in savedIds) {
                    it.copy(
                        sessionKey = "",
                        accessToken = "",
                        refreshToken = "",
                        hasStoredCredential = true,
                        credentialEdited = false
                    )
                } else {
                    it
                }
            })
        }
    }


    fun addDeepseekAccount() {
        _uiState.update { state ->
            state.copy(
                deepseekAccounts = state.deepseekAccounts + DeepseekAccountUi(id = UUID.randomUUID().toString())
            )
        }
        scheduleSave(AiService.DEEPSEEK)
    }

    fun removeDeepseekAccount(accountId: String) {
        _uiState.update { state ->
            state.copy(
                deepseekAccounts = state.deepseekAccounts.filterNot { it.id == accountId }
            )
        }
        scheduleSave(AiService.DEEPSEEK)
    }

    fun updateDeepseekAccount(accountId: String, field: String, value: String) {
        _uiState.update { state ->
            state.copy(
                deepseekAccounts = state.deepseekAccounts.map { account ->
                    if (account.id != accountId) account else when (field) {
                        "label" -> account.copy(label = value, validationResult = null)
                        "apiKey" -> account.copy(apiKey = value, validationResult = null)
                        else -> account
                    }
                }
            )
        }
        scheduleSave(AiService.DEEPSEEK)
    }

    fun validateDeepseekAccount(accountId: String) {
        persistDeepseekAccounts()

        _uiState.update { state ->
            state.copy(
                deepseekAccounts = state.deepseekAccounts.map { account ->
                    if (account.id == accountId) {
                        account.copy(isValidating = true, validationResult = null)
                    } else {
                        account
                    }
                }
            )
        }

        viewModelScope.launch {
            val result = deepseekRepository.validateAccount(accountId)
            val validationResult = when (result) {
                is Result.Success -> ValidationResult.Success
                is Result.Failure -> ValidationResult.Failure(result.error)
            }
            _uiState.update { state ->
                state.copy(
                    deepseekAccounts = state.deepseekAccounts.map { account ->
                        if (account.id == accountId) {
                            account.copy(isValidating = false, validationResult = validationResult)
                        } else {
                            account
                        }
                    }
                )
            }
        }
    }

    
    private fun persistDeepseekAccounts() {
        val stored = prefsManager.getDeepseekAccounts().associateBy { it.id }
        val accounts = _uiState.value.deepseekAccounts.mapNotNull { account ->
            val key = account.apiKey.trim().ifBlank { stored[account.id]?.apiKey.orEmpty() }
            if (key.isBlank()) return@mapNotNull null
            DeepseekAccount(id = account.id, label = account.label.trim(), apiKey = key)
        }
        prefsManager.saveDeepseekAccounts(accounts)
        _uiState.update { state ->
            val storedIds = accounts.map { it.id }.toSet()
            state.copy(deepseekAccounts = state.deepseekAccounts.map {
                it.copy(apiKey = "", hasStoredKey = it.id in storedIds)
            })
        }
    }


    fun addZaiAccount() {
        _uiState.update { state ->
            state.copy(
                zaiAccounts = state.zaiAccounts + ZaiAccountUi(id = UUID.randomUUID().toString())
            )
        }
        scheduleSave(AiService.ZAI)
    }

    fun removeZaiAccount(accountId: String) {
        _uiState.update { state ->
            state.copy(
                zaiAccounts = state.zaiAccounts.filterNot { it.id == accountId }
            )
        }
        prefsManager.deleteZaiAccount(accountId)
        scheduleSave(AiService.ZAI)
    }

    fun updateZaiAccount(accountId: String, field: String, value: String) {
        _uiState.update { state ->
            state.copy(
                zaiAccounts = state.zaiAccounts.map { account ->
                    if (account.id != accountId) account else when (field) {
                        "label" -> account.copy(label = value, validationResult = null)
                        "apiKey" -> account.copy(apiKey = value, validationResult = null)
                        else -> account
                    }
                }
            )
        }
        scheduleSave(AiService.ZAI)
    }

    fun validateZaiAccount(accountId: String) {
        persistZaiAccounts()

        _uiState.update { state ->
            state.copy(
                zaiAccounts = state.zaiAccounts.map { account ->
                    if (account.id == accountId) {
                        account.copy(isValidating = true, validationResult = null)
                    } else {
                        account
                    }
                }
            )
        }

        viewModelScope.launch {
            val result = zaiRepository.validateAccount(accountId)
            val validationResult = when (result) {
                is Result.Success -> ValidationResult.Success
                is Result.Failure -> ValidationResult.Failure(result.error)
            }
            _uiState.update { state ->
                state.copy(
                    zaiAccounts = state.zaiAccounts.map { account ->
                        if (account.id == accountId) {
                            account.copy(isValidating = false, validationResult = validationResult)
                        } else {
                            account
                        }
                    }
                )
            }
        }
    }

    
    fun setZaiHideMonthlyTools(hidden: Boolean) {
        prefsManager.setZaiMonthlyToolsHidden(hidden)
        _uiState.update { it.copy(zaiHideMonthlyTools = hidden) }
    }

    
    private fun persistZaiAccounts() {
        val stored = prefsManager.getZaiAccounts().associateBy { it.id }
        val accounts = _uiState.value.zaiAccounts.mapNotNull { account ->
            val key = account.apiKey.trim().ifBlank { stored[account.id]?.apiKey.orEmpty() }
            if (key.isBlank()) return@mapNotNull null
            ZaiAccount(id = account.id, label = account.label.trim(), apiKey = key)
        }
        prefsManager.saveZaiAccounts(accounts)
        _uiState.update { state ->
            val storedIds = accounts.map { it.id }.toSet()
            state.copy(zaiAccounts = state.zaiAccounts.map {
                it.copy(apiKey = "", hasStoredKey = it.id in storedIds)
            })
        }
    }

    fun validateCredential(service: AiService) {
        val repo = when (service) {
            AiService.CLAUDE -> claudeRepository
            AiService.CODEX -> codexRepository
            AiService.GEMINI -> geminiRepository
            AiService.DEEPSEEK -> deepseekRepository
            AiService.ZAI -> zaiRepository
            AiService.COPILOT -> copilotRepository
            AiService.OPENROUTER -> openRouterRepository
            AiService.OPENAI -> openAiRepository
        }

        saveCredential(service)

        _uiState.update { state ->
            val current = state.serviceStates[service] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (service to current.copy(isValidating = true, validationResult = null))
            )
        }

        viewModelScope.launch {
            val result = repo.validateCredential()
            val validationResult = when (result) {
                is Result.Success -> ValidationResult.Success
                is Result.Failure -> ValidationResult.Failure(result.error)
            }

            _uiState.update { state ->
                val current = state.serviceStates[service] ?: ServiceCredentialState()
                state.copy(
                    serviceStates = state.serviceStates + (service to current.copy(
                        isValidating = false,
                        validationResult = validationResult
                    ))
                )
            }
        }
    }


    private fun loadCurrencyConversion(): CurrencyConversionUi {
        val displayCurrency = prefsManager.getDisplayCurrency()
        val source = prefsManager.getFxRateSource()
        return CurrencyConversionUi(
            displayCurrency = displayCurrency,
            source = source,
            apiFetchAllowed = prefsManager.isFxApiFetchAllowed(),
            manualUsdEurText = prefsManager.getManualUsdEurRate()?.let(::formatRate) ?: "",
            manualUsdCnyText = prefsManager.getManualUsdCnyRate()?.let(::formatRate) ?: "",
            remoteRates = cachedRemoteRates(source, displayCurrency)
        )
    }

    
    private fun previewCurrencies(displayCurrency: DisplayCurrency): List<String> =
        if (displayCurrency.code == null) emptyList() else Currencies.PROVIDER.filter { it != displayCurrency.code }

    private fun cachedRemoteRates(source: FxRateSource, displayCurrency: DisplayCurrency): List<FxRate> {
        val target = displayCurrency.code ?: return emptyList()
        if (!source.isRemote) return emptyList()
        return previewCurrencies(displayCurrency).mapNotNull { exchangeRates.cachedRate(source, it, target) }
    }

    fun setDisplayCurrency(displayCurrency: DisplayCurrency) {
        prefsManager.setDisplayCurrency(displayCurrency)
        _uiState.update {
            it.copy(
                currencyConversion = it.currencyConversion.copy(
                    displayCurrency = displayCurrency,
                    remoteRates = cachedRemoteRates(it.currencyConversion.source, displayCurrency),
                    fetchError = null
                )
            )
        }
    }

    fun setFxRateSource(source: FxRateSource) {
        prefsManager.setFxRateSource(source)
        _uiState.update {
            it.copy(
                currencyConversion = it.currencyConversion.copy(
                    source = source,
                    remoteRates = cachedRemoteRates(source, it.currencyConversion.displayCurrency),
                    fetchError = null
                )
            )
        }
    }

    
    fun setFxApiFetchAllowed(allowed: Boolean) {
        prefsManager.setFxApiFetchAllowed(allowed)
        _uiState.update {
            it.copy(currencyConversion = it.currencyConversion.copy(apiFetchAllowed = allowed, fetchError = null))
        }
        if (allowed) refreshFxRates()
    }

    
    fun updateManualUsdEurRate(text: String) {
        val (parsed, invalid) = parseManualRate(text)
        if (!invalid) prefsManager.setManualUsdEurRate(parsed)
        _uiState.update {
            it.copy(
                currencyConversion = it.currencyConversion.copy(manualUsdEurText = text, manualUsdEurInvalid = invalid)
            )
        }
    }

    fun updateManualUsdCnyRate(text: String) {
        val (parsed, invalid) = parseManualRate(text)
        if (!invalid) prefsManager.setManualUsdCnyRate(parsed)
        _uiState.update {
            it.copy(
                currencyConversion = it.currencyConversion.copy(manualUsdCnyText = text, manualUsdCnyInvalid = invalid)
            )
        }
    }

    
    private fun parseManualRate(text: String): Pair<Double?, Boolean> {
        val trimmed = text.trim()
        val parsed = parseRate(trimmed)
        return parsed to (trimmed.isNotEmpty() && parsed == null)
    }

    
    fun refreshFxRates() {
        val requested = _uiState.value.currencyConversion
        val target = requested.displayCurrency.code ?: return
        if (!requested.source.isRemote || !requested.apiFetchAllowed || requested.isFetching) return
        _uiState.update {
            it.copy(currencyConversion = it.currencyConversion.copy(isFetching = true, fetchError = null))
        }
        viewModelScope.launch {
            val results = previewCurrencies(requested.displayCurrency).map { from ->
                exchangeRates.fetchLatest(requested.source, from, target)
            }
            val rates = results.mapNotNull { (it as? Result.Success)?.value }
            val error = results.firstNotNullOfOrNull { (it as? Result.Failure)?.error }
            val stale = _uiState.value.currencyConversion.let {
                it.source != requested.source || it.displayCurrency != requested.displayCurrency
            }
            _uiState.update { state ->
                val current = state.currencyConversion.copy(isFetching = false)
                state.copy(
                    currencyConversion = when {
                        stale -> current
                        error != null -> current.copy(
                            remoteRates = rates.ifEmpty { current.remoteRates },
                            fetchError = error
                        )
                        else -> current.copy(remoteRates = rates, fetchError = null)
                    }
                )
            }
            
            if (stale) refreshFxRates()
        }
    }

    private fun parseRate(text: String): Double? =
        text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it < MAX_MANUAL_RATE }

    private fun formatRate(rate: Double): String =
        BigDecimal(rate.toString()).stripTrailingZeros().toPlainString()


    
    fun setThemeMode(mode: ThemeMode) {
        displayPreferences.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    
    fun setQuotaDisplayMode(mode: QuotaDisplayMode) {
        displayPreferences.setQuotaDisplayMode(mode)
        _uiState.update { it.copy(quotaDisplayMode = mode) }
        viewModelScope.launch { runCatching { widgetQuotaPublisher.refreshWidgets() } }
    }

    
    fun setLockScreenVisibility(visibility: LockScreenVisibility) {
        displayPreferences.setLockScreenVisibility(visibility)
        _uiState.update { it.copy(lockScreenVisibility = visibility) }
        runCatching { notificationService.reapplyLockScreenVisibility() }
        runCatching {
            TileService.requestListeningState(appContext, ComponentName(appContext, QuotaTileService::class.java))
        }
    }

    
    fun onBatteryChoiceMade() {
        displayPreferences.markBatteryPromptAnswered()
    }

    fun setRefreshInterval(minutes: Long) {
        prefsManager.setRefreshInterval(minutes)
        refreshScheduler.apply(minutes)
        _uiState.update { it.copy(refreshIntervalMinutes = minutes) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefsManager.setNotificationsEnabled(enabled)
        _uiState.update { it.copy(notificationsEnabled = enabled) }
    }

    
    fun setAppLockEnabled(enabled: Boolean) {
        appLock.setEnabled(enabled)
        _uiState.update { it.copy(appLockEnabled = enabled) }
    }

    
    fun setDemoDataEnabled(enabled: Boolean) {
        demoData.setEnabled(enabled)
        _uiState.update { it.copy(demoDataEnabled = demoData.isEnabled()) }
    }

    fun showDeleteConfirmDialog() {
        _uiState.update { it.copy(showDeleteConfirmDialog = true) }
    }

    fun dismissDeleteConfirmDialog() {
        _uiState.update { it.copy(showDeleteConfirmDialog = false) }
    }


    private var copilotLoginJob: Job? = null

    
    fun startCopilotLogin() {
        copilotLoginJob?.cancel()
        _uiState.update { it.copy(copilotLogin = CopilotLoginUi()) }
        copilotLoginJob = viewModelScope.launch {
            val code = when (val started = copilotDeviceFlow.start()) {
                is Result.Success -> started.value
                is Result.Failure -> {
                    _uiState.update { it.copy(copilotLogin = CopilotLoginUi(error = started.error)) }
                    return@launch
                }
            }
            _uiState.update {
                it.copy(copilotLogin = CopilotLoginUi(userCode = code.userCode, verificationUri = code.verificationUri))
            }
            when (val token = copilotDeviceFlow.awaitToken(code)) {
                is Result.Success -> {
                    _uiState.update { it.copy(copilotLogin = null) }
                    updateField(AiService.COPILOT, "accessToken", token.value)
                    validateCredential(AiService.COPILOT)
                }
                is Result.Failure -> _uiState.update { state ->
                    state.copy(copilotLogin = state.copilotLogin?.copy(error = token.error))
                }
            }
        }
    }

    fun cancelCopilotLogin() {
        copilotLoginJob?.cancel()
        copilotLoginJob = null
        _uiState.update { it.copy(copilotLogin = null) }
    }

    fun deleteAllCredentials() {
        cancelCopilotLogin()
        prefsManager.deleteAllCredentials()
        _uiState.update {
            SettingsUiState(
                refreshIntervalMinutes = it.refreshIntervalMinutes,
                themeMode = it.themeMode,
                quotaDisplayMode = it.quotaDisplayMode,
                notificationsEnabled = it.notificationsEnabled,
                appLockEnabled = it.appLockEnabled,
                demoDataAvailable = it.demoDataAvailable,
                demoDataEnabled = it.demoDataEnabled
            )
        }
    }


    private var pendingZipBytes: ByteArray? = null

    fun updateExportPin(pin: String) {
        _uiState.update {
            it.copy(export = it.export.copy(pin = pin, error = null, zipReady = false))
        }
    }

    fun updateExportConfirmPin(pin: String) {
        _uiState.update {
            it.copy(export = it.export.copy(confirmPin = pin, error = null, zipReady = false))
        }
    }

    fun dismissQrExport() {
        _uiState.update { it.copy(export = it.export.copy(qrCodes = null, qrIndex = 0)) }
    }

    fun nextExportQr() {
        _uiState.update {
            val max = (it.export.qrCodes?.size ?: 1) - 1
            it.copy(export = it.export.copy(qrIndex = (it.export.qrIndex + 1).coerceAtMost(max)))
        }
    }

    fun previousExportQr() {
        _uiState.update {
            it.copy(export = it.export.copy(qrIndex = (it.export.qrIndex - 1).coerceAtLeast(0)))
        }
    }

    
    fun prepareZipExport() {
        if (!validateExportPin()) return
        flushPendingChanges()
        setExportBusy(true)
        val pin = _uiState.value.export.pin
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching { backupManager.createZipBytes(pin) }
            withContext(Dispatchers.Main) { setExportBusy(false) }
            result.fold(
                onSuccess = { bytes ->
                    pendingZipBytes = bytes
                    _uiState.update {
                        it.copy(
                            export = it.export.copy(
                                zipReady = true,
                                zipFileName = defaultZipFileName(),
                                error = null
                            )
                        )
                    }
                },
                onFailure = { exportFailed(it) }
            )
        }
    }

    
    fun consumeZipBytes(): ByteArray? {
        val bytes = pendingZipBytes
        pendingZipBytes = null
        return bytes
    }

    
    fun onZipHandled() {
        _uiState.update { it.copy(export = it.export.copy(zipReady = false)) }
    }

    
    fun prepareQrExport() {
        if (!validateExportPin()) return
        flushPendingChanges()
        setExportBusy(true)
        val pin = _uiState.value.export.pin
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching { backupManager.createQrCodes(pin) }
            withContext(Dispatchers.Main) { setExportBusy(false) }
            result.fold(
                onSuccess = { codes ->
                    _uiState.update { it.copy(export = it.export.copy(qrCodes = codes, qrIndex = 0, error = null)) }
                },
                onFailure = { exportFailed(it) }
            )
        }
    }

    
    fun isExportPinValid(): Boolean {
        val export = _uiState.value.export
        if (!BackupCrypto.isValidPassword(export.pin)) return false
        return export.confirmPin.isEmpty() || export.pin == export.confirmPin
    }

    private fun validateExportPin(): Boolean {
        val export = _uiState.value.export
        val error = when {
            !BackupCrypto.isValidPassword(export.pin) -> ExportError.PinTooShort
            export.confirmPin.isNotEmpty() && export.pin != export.confirmPin -> ExportError.PinMismatch
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(export = it.export.copy(error = error)) }
        }
        return error == null
    }

    private fun setExportBusy(busy: Boolean) {
        _uiState.update { it.copy(export = it.export.copy(isBusy = busy, error = null)) }
    }

    private fun exportFailed(error: Throwable) {
        val mapped = if (error is QrPayloadTooLargeException) ExportError.QrTooLarge else ExportError.Failed
        _uiState.update { it.copy(export = it.export.copy(error = mapped)) }
    }



    private var pendingImportContainer: ByteArray? = null

    private val qrScanChunks = mutableMapOf<Int, QrChunkCodec.Chunk>()
    private var qrSessionHash: String? = null


    fun onQrScanned(contents: String) {
        val text = contents.trim()
        if (QrChunkCodec.isMultiQr(text)) {
            onMultiQrChunkScanned(text)
            return
        }
        if (_uiState.value.importState.scanSession != null) {
            setImportError(ImportError.QrMismatch)
            return
        }
        onContainerLoaded(runCatching { backupManager.parseQrPayload(text) }.getOrNull())
    }

    private fun onMultiQrChunkScanned(text: String) {
        val chunk = runCatching { QrChunkCodec.parseChunk(text) }.getOrNull()
        if (chunk == null) {
            setImportError(ImportError.NotABackup)
            return
        }
        val session = _uiState.value.importState.scanSession
        if (session == null) {
            qrScanChunks.clear()
            qrSessionHash = chunk.fullHash
            _uiState.update {
                it.copy(importState = it.importState.copy(scanSession = QrScanProgress(chunk.total, emptySet())))
            }
        } else if (session.total != chunk.total || qrSessionHash != chunk.fullHash) {
            setImportError(ImportError.QrMismatch)
            return
        }
        qrScanChunks[chunk.index] = chunk
        if (qrScanChunks.size == chunk.total) {
            completeQrScanSession()
        } else {
            publishQrScanProgress(chunk.total)
        }
    }

    private fun completeQrScanSession() {
        val payload = runCatching { QrChunkCodec.reassemble(qrScanChunks.values) }.getOrNull()
        resetQrScanSession()
        if (payload == null) {
            setImportError(ImportError.QrMismatch)
            return
        }
        onContainerLoaded(runCatching { backupManager.parseQrPayload(payload) }.getOrNull())
    }

    private fun publishQrScanProgress(total: Int) {
        _uiState.update {
            it.copy(
                importState = it.importState.copy(
                    scanSession = QrScanProgress(total, qrScanChunks.keys.toSet()),
                    error = null
                )
            )
        }
    }

    private fun setImportError(error: ImportError) {
        _uiState.update { it.copy(importState = it.importState.copy(error = error)) }
    }

    private fun resetQrScanSession() {
        qrScanChunks.clear()
        qrSessionHash = null
        _uiState.update { it.copy(importState = it.importState.copy(scanSession = null)) }
    }

    fun restartQrScanSession() {
        resetQrScanSession()
    }

    
    fun onZipPicked(openStream: () -> InputStream?) {
        viewModelScope.launch(Dispatchers.IO) {
            val container = runCatching {
                openStream()?.use { backupManager.readBackupFile(it) }?.let(backupManager::parseZip)
            }.getOrNull()
            withContext(Dispatchers.Main) { onContainerLoaded(container) }
        }
    }

    private fun onContainerLoaded(container: ByteArray?) {
        pendingImportContainer = container
        _uiState.update {
            it.copy(
                importState = if (container == null) {
                    ImportUiState(error = ImportError.NotABackup)
                } else {
                    ImportUiState(awaitingPin = true)
                }
            )
        }
    }

    fun updateImportPin(pin: String) {
        _uiState.update { it.copy(importState = it.importState.copy(pin = pin, error = null)) }
    }

    fun dismissImport() {
        pendingImportContainer = null
        qrScanChunks.clear()
        qrSessionHash = null
        _uiState.update { it.copy(importState = ImportUiState()) }
    }

    
    fun confirmImport() {
        val container = pendingImportContainer ?: return
        val pin = _uiState.value.importState.pin
        if (!BackupCrypto.isUsableForImport(pin) || _uiState.value.importState.isBusy) return
        flushPendingChanges()
        pendingChanges.value = null
        _uiState.update { it.copy(importState = it.importState.copy(isBusy = true, error = null)) }

        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching {
                backupManager.restore(backupManager.decryptContainer(container, pin))
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { summary ->
                        pendingImportContainer = null
                        _uiState.update { it.copy(serviceStates = SettingsUiState().serviceStates) }
                        loadPersistedState()
                        refreshScheduler.applySaved()
                        _uiState.update {
                            it.copy(
                                importState = ImportUiState(
                                    importedCount = summary.credentialCount,
                                    importedHistoryEntries = summary.historyEntries
                                )
                            )
                        }
                    },
                    onFailure = { error ->
                        val mapped = when {
                            error is BackupCryptoException -> ImportError.WrongPin
                            error is InvalidBackupException &&
                                error.reason == InvalidBackupException.Reason.UNSUPPORTED_VERSION ->
                                ImportError.UnsupportedVersion
                            error is InvalidBackupException -> ImportError.NotABackup
                            else -> ImportError.Failed
                        }
                        _uiState.update {
                            it.copy(importState = it.importState.copy(isBusy = false, error = mapped, pin = ""))
                        }
                    }
                )
            }
        }
    }

    
    fun onImportResultShown() {
        _uiState.update { it.copy(importState = it.importState.copy(importedCount = null)) }
    }

    
    fun clearImportError() {
        _uiState.update { it.copy(importState = it.importState.copy(error = null)) }
    }

    
    private fun flushPendingChanges() {
        pendingChanges.value?.let { (service, _) -> saveCredential(service) }
    }

    private fun defaultZipFileName(): String =
        "tokenusage-config-" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) +
            ".zip"

    
    private fun ClaudeAccount.toUi(): ClaudeAccountUi =
        ClaudeAccountUi(id = id, label = label, hasStoredCredential = credential.accessToken.isNotBlank())

    
    private fun ClaudeAccountUi.toCredential(): Credential? = when {
        sessionKey.isNotBlank() -> Credential.ClaudeWebCredential(accessToken = sessionKey.trim())
        accessToken.isNotBlank() -> Credential.ClaudeCredential(
            accessToken = accessToken.trim(),
            refreshToken = refreshToken.trim().ifBlank { null }
        )
        else -> null
    }

    private companion object {
        
        const val MAX_MANUAL_RATE = 1000.0
    }

    private fun formatExpiryMs(expiresAtMs: Long): String {
        return try {
            val instant = Instant.ofEpochMilli(expiresAtMs)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        } catch (_: Exception) {
            "—"
        }
    }
}
