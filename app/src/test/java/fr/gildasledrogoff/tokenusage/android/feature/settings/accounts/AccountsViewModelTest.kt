package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import app.cash.turbine.test
import fr.gildasledrogoff.tokenusage.core.data.CopilotDeviceFlow
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotDto
import fr.gildasledrogoff.tokenusage.core.provider.CredentialField
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.KEY_API_KEY
import fr.gildasledrogoff.tokenusage.core.provider.LoginAction
import fr.gildasledrogoff.tokenusage.core.provider.LoginMethod
import fr.gildasledrogoff.tokenusage.core.provider.ProviderOption
import fr.gildasledrogoff.tokenusage.core.provider.ProviderRegistry
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {

    /** A provider whose accounts live in the real prefs; validation outcome is scripted. */
    private class FakeProvider(
        override val service: AiService,
        private val prefs: EncryptedPrefsManager,
        override val form: CredentialForm
    ) : QuotaProvider {
        var validation: Result<Unit, AppError> = Result.Success(Unit)
        val validated = mutableListOf<ServiceAccount>()
        private val options = mutableMapOf<String, Boolean>()

        override fun accounts() = prefs.getAccounts(service)

        override suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError> =
            Result.Success(QuotaInfo(service, emptyList()))

        override suspend fun validate(account: ServiceAccount): Result<Unit, AppError> {
            validated += account
            return validation
        }

        override fun optionValue(key: String) = options[key] ?: false
        override fun setOption(key: String, value: Boolean) {
            options[key] = value
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var prefs: EncryptedPrefsManager
    private lateinit var providers: Map<AiService, FakeProvider>
    private lateinit var widgetPrefs: WidgetPrefsManager
    private lateinit var widgetPublisher: WidgetQuotaPublisher
    private lateinit var deviceFlow: CopilotDeviceFlow
    private lateinit var sync: AccountsSync
    private lateinit var viewModel: AccountsViewModel

    private val apiKey: (String) -> Credential = { Credential.ApiKeyCredential(it) }

    private fun formFor(service: AiService): CredentialForm = when (service) {
        AiService.CODEX -> CredentialForm(
            fields = listOf(
                CredentialField("accessToken", 1),
                CredentialField("accountId", 2, secret = false)
            ),
            assemble = { values, previous ->
                val stored = previous as? Credential.CodexCredential
                val token = values["accessToken"].orEmpty().trim().ifBlank { stored?.accessToken.orEmpty() }
                if (token.isBlank()) null
                else Credential.CodexCredential(token, "rt", values["accountId"].orEmpty().ifBlank { null })
            },
            prefill = { mapOf("accountId" to ((it as? Credential.CodexCredential)?.accountId.orEmpty())) }
        )
        AiService.COPILOT -> apiKeyForm(
            labelRes = 1, fieldHintRes = 2,
            login = LoginAction(LoginMethod.DEVICE_FLOW, 3, KEY_API_KEY),
            toCredential = apiKey
        )
        AiService.ZAI -> apiKeyForm(
            labelRes = 1, fieldHintRes = 2,
            options = listOf(ProviderOption("hide", 4)),
            toCredential = apiKey
        )
        AiService.CLAUDE -> apiKeyForm(labelRes = 1, fieldHintRes = 2) { Credential.ClaudeCredential(it, null) }
        else -> apiKeyForm(labelRes = 1, fieldHintRes = 2, toCredential = apiKey)
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        prefs = inMemoryPrefsManager()
        providers = AiService.entries.associateWith { FakeProvider(it, prefs, formFor(it)) }
        widgetPrefs = mock(WidgetPrefsManager::class.java)
        widgetPublisher = mock(WidgetQuotaPublisher::class.java)
        deviceFlow = mock(CopilotDeviceFlow::class.java)
        sync = AccountsSync()
        viewModel = newViewModel()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel() = AccountsViewModel(
        ProviderRegistry(providers.values.toSet()), deviceFlow, prefs, widgetPublisher, widgetPrefs, sync
    )

    private fun accounts(service: AiService) = viewModel.uiState.value.accounts[service].orEmpty()

    // --- loading ---------------------------------------------------------------------------------

    @Test
    fun `every service starts with its stored accounts`() {
        prefs.addAccount(AiService.OPENAI, "Main", Credential.ApiKeyCredential("sk-1"))
        prefs.addAccount(AiService.OPENAI, "", Credential.ApiKeyCredential("sk-2"))
        prefs.addAccount(AiService.CODEX, "", Credential.CodexCredential("at", "rt", "acct-9"))

        viewModel = newViewModel()

        assertEquals(AiService.entries.toSet(), viewModel.uiState.value.accounts.keys)
        assertEquals(listOf("Main", ""), accounts(AiService.OPENAI).map { it.label })
        assertTrue(accounts(AiService.OPENAI).all { it.hasStoredCredential })
        assertTrue(accounts(AiService.GEMINI).isEmpty())
        assertEquals("acct-9", accounts(AiService.CODEX).single().values["accountId"])
    }

    @Test
    fun `stored secrets are never put in the editor`() {
        prefs.addAccount(AiService.OPENAI, "", Credential.ApiKeyCredential("sk-secret"))

        viewModel = newViewModel()

        assertTrue(accounts(AiService.OPENAI).single().values.isEmpty())
    }

    // --- add / edit / remove ------------------------------------------------------------------------

    @Test
    fun `any service can get several accounts`() = runTest(dispatcher) {
        for (service in AiService.entries) {
            viewModel.addAccount(service)
            viewModel.addAccount(service)
            val ids = accounts(service).map { it.id }
            viewModel.updateField(service, ids[0], KEY_API_KEY, "key-${service.name}-1")
            viewModel.updateField(service, ids[1], KEY_API_KEY, "key-${service.name}-2")
        }
        advanceTimeBy(1_000)

        for (service in AiService.entries) {
            if (service == AiService.CODEX) continue
            val stored = prefs.getAccounts(service)
            assertEquals("$service", listOf("key-${service.name}-1", "key-${service.name}-2"), stored.map { it.credential.accessToken })
        }
    }

    @Test
    fun `typing a key saves the account after the debounce and forgets the typed secret`() = runTest(dispatcher) {
        viewModel.addAccount(AiService.OPENROUTER)
        val id = accounts(AiService.OPENROUTER).single().id

        viewModel.updateField(AiService.OPENROUTER, id, KEY_API_KEY, "  sk-or  ")
        assertTrue("nothing written before the debounce", prefs.getAccounts(AiService.OPENROUTER).isEmpty())
        advanceTimeBy(600)

        assertEquals(Credential.ApiKeyCredential("sk-or"), prefs.getAccounts(AiService.OPENROUTER).single().credential)
        val ui = accounts(AiService.OPENROUTER).single()
        assertTrue(ui.hasStoredCredential)
        assertFalse(ui.credentialEdited)
        assertNull("the secret leaves the editor once saved", ui.values[KEY_API_KEY])
    }

    @Test
    fun `an account without a usable credential is not stored`() = runTest(dispatcher) {
        viewModel.addAccount(AiService.OPENAI)

        advanceTimeBy(1_000)
        viewModel.updateField(AiService.OPENAI, accounts(AiService.OPENAI).single().id, "label", "Draft")
        advanceTimeBy(1_000)

        assertTrue(prefs.getAccounts(AiService.OPENAI).isEmpty())
        assertEquals("the draft stays in the editor", 1, accounts(AiService.OPENAI).size)
    }

    @Test
    fun `renaming keeps the stored credential`() = runTest(dispatcher) {
        val stored = prefs.addAccount(AiService.OPENAI, "Old", Credential.ApiKeyCredential("sk-keep"))
        viewModel = newViewModel()

        viewModel.updateField(AiService.OPENAI, stored.id, "label", "  New name ")
        advanceTimeBy(600)

        val account = prefs.getAccounts(AiService.OPENAI).single()
        assertEquals("New name", account.label)
        assertEquals(Credential.ApiKeyCredential("sk-keep"), account.credential)
    }

    @Test
    fun `non secret fields come back from the stored credential after a save`() = runTest(dispatcher) {
        viewModel.addAccount(AiService.CODEX)
        val id = accounts(AiService.CODEX).single().id

        viewModel.updateField(AiService.CODEX, id, "accessToken", "at-1")
        viewModel.updateField(AiService.CODEX, id, "accountId", "acct-1")
        advanceTimeBy(600)

        assertEquals("acct-1", (prefs.getAccounts(AiService.CODEX).single().credential as Credential.CodexCredential).accountId)
        assertEquals("acct-1", accounts(AiService.CODEX).single().values["accountId"])
        assertNull(accounts(AiService.CODEX).single().values["accessToken"])
    }

    @Test
    fun `removing an account deletes only that one`() = runTest(dispatcher) {
        val a = prefs.addAccount(AiService.COPILOT, "a", Credential.ApiKeyCredential("c1"))
        val b = prefs.addAccount(AiService.COPILOT, "b", Credential.ApiKeyCredential("c2"))
        viewModel = newViewModel()

        viewModel.removeAccount(AiService.COPILOT, a.id)

        assertEquals(listOf(b.id), prefs.getAccounts(AiService.COPILOT).map { it.id })
        assertEquals(listOf(b.id), accounts(AiService.COPILOT).map { it.id })
        verify(widgetPrefs, never()).removeServiceEverywhere(AiService.COPILOT)
    }

    @Test
    fun `removing the last account clears the service from the widgets`() = runTest(dispatcher) {
        val only = prefs.addAccount(AiService.COPILOT, "", Credential.ApiKeyCredential("c1"))
        viewModel = newViewModel()

        viewModel.removeAccount(AiService.COPILOT, only.id)

        verify(widgetPrefs).removeServiceEverywhere(AiService.COPILOT)
        verify(widgetPublisher).refreshWidgets()
    }

    // --- validation ------------------------------------------------------------------------------------

    @Test
    fun `validate saves pending edits first and validates the stored account`() = runTest(dispatcher) {
        viewModel.addAccount(AiService.OPENAI)
        val id = accounts(AiService.OPENAI).single().id
        viewModel.updateField(AiService.OPENAI, id, KEY_API_KEY, "sk-new")

        viewModel.validate(AiService.OPENAI, id)

        val provider = providers.getValue(AiService.OPENAI)
        assertEquals(listOf("sk-new"), provider.validated.map { it.credential.accessToken })
        assertEquals(ValidationResult.Success, accounts(AiService.OPENAI).single().validationResult)
        assertFalse(accounts(AiService.OPENAI).single().isValidating)
    }

    @Test
    fun `a failed validation is shown on that account only`() = runTest(dispatcher) {
        val good = prefs.addAccount(AiService.OPENAI, "good", Credential.ApiKeyCredential("k1"))
        val bad = prefs.addAccount(AiService.OPENAI, "bad", Credential.ApiKeyCredential("k2"))
        viewModel = newViewModel()
        val error = AppError.AuthError(AiService.OPENAI, isTerminal = true)
        providers.getValue(AiService.OPENAI).validation = Result.Failure(error)

        viewModel.validate(AiService.OPENAI, bad.id)

        val byId = accounts(AiService.OPENAI).associateBy { it.id }
        assertEquals(ValidationResult.Failure(error), byId.getValue(bad.id).validationResult)
        assertNull(byId.getValue(good.id).validationResult)
    }

    @Test
    fun `editing a credential clears the previous validation result`() = runTest(dispatcher) {
        val stored = prefs.addAccount(AiService.OPENAI, "", Credential.ApiKeyCredential("k1"))
        viewModel = newViewModel()
        viewModel.validate(AiService.OPENAI, stored.id)
        assertEquals(ValidationResult.Success, accounts(AiService.OPENAI).single().validationResult)

        viewModel.updateField(AiService.OPENAI, stored.id, KEY_API_KEY, "k2")

        assertNull(accounts(AiService.OPENAI).single().validationResult)
    }

    @Test
    fun `validating a draft with no credential reports it missing`() = runTest(dispatcher) {
        viewModel.addAccount(AiService.OPENAI)
        val id = accounts(AiService.OPENAI).single().id

        viewModel.validate(AiService.OPENAI, id)

        val result = accounts(AiService.OPENAI).single().validationResult as ValidationResult.Failure
        assertEquals(AppError.CredentialNotFound(AiService.OPENAI), result.error)
    }

    // --- options -----------------------------------------------------------------------------------------

    @Test
    fun `service options are read from and written to the provider`() {
        providers.getValue(AiService.ZAI).setOption("hide", true)
        viewModel = newViewModel()
        assertTrue(viewModel.uiState.value.option(AiService.ZAI, "hide"))

        viewModel.setOption(AiService.ZAI, "hide", false)

        assertFalse(providers.getValue(AiService.ZAI).optionValue("hide"))
        assertFalse(viewModel.uiState.value.option(AiService.ZAI, "hide"))
    }

    // --- device flow ----------------------------------------------------------------------------------------

    @Test
    fun `device login stores the token in the chosen account and validates it`() = runTest(dispatcher) {
        val code = CopilotDto.DeviceCodeResponse(deviceCode = "dc", userCode = "ABCD-1234")
        `when`(deviceFlow.start()).thenReturn(Result.Success(code))
        `when`(deviceFlow.awaitToken(code)).thenReturn(Result.Success("gho_new"))
        viewModel.addAccount(AiService.COPILOT)
        val id = accounts(AiService.COPILOT).single().id

        viewModel.startDeviceLogin(AiService.COPILOT, id)

        assertNull("dialog closes on success", viewModel.uiState.value.deviceLogin)
        assertEquals(Credential.ApiKeyCredential("gho_new"), prefs.getAccounts(AiService.COPILOT).single().credential)
        assertEquals(ValidationResult.Success, accounts(AiService.COPILOT).single().validationResult)
    }

    @Test
    fun `device login can add a second account next to an existing one`() = runTest(dispatcher) {
        val existing = prefs.addAccount(AiService.COPILOT, "first", Credential.ApiKeyCredential("gho_old"))
        val code = CopilotDto.DeviceCodeResponse(deviceCode = "dc", userCode = "WXYZ-0000")
        `when`(deviceFlow.start()).thenReturn(Result.Success(code))
        `when`(deviceFlow.awaitToken(code)).thenReturn(Result.Success("gho_second"))
        viewModel = newViewModel()
        viewModel.addAccount(AiService.COPILOT)
        val newId = accounts(AiService.COPILOT).last().id

        viewModel.startDeviceLogin(AiService.COPILOT, newId)

        val stored = prefs.getAccounts(AiService.COPILOT)
        assertEquals(listOf(existing.id, newId), stored.map { it.id })
        assertEquals(listOf("gho_old", "gho_second"), stored.map { it.credential.accessToken })
    }

    @Test
    fun `device login failure keeps the dialog open with the error`() = runTest(dispatcher) {
        val error = AppError.NetworkError("offline")
        `when`(deviceFlow.start()).thenReturn(Result.Failure(error))
        viewModel.addAccount(AiService.COPILOT)

        viewModel.startDeviceLogin(AiService.COPILOT, accounts(AiService.COPILOT).single().id)

        assertEquals(error, viewModel.uiState.value.deviceLogin?.error)
        viewModel.cancelDeviceLogin()
        assertNull(viewModel.uiState.value.deviceLogin)
    }

    // --- links with the rest of the settings ---------------------------------------------------------------

    @Test
    fun `an import elsewhere reloads the accounts`() = runTest(dispatcher) {
        viewModel.uiState.test {
            awaitItem()
            prefs.addAccount(AiService.OPENAI, "Imported", Credential.ApiKeyCredential("sk-imported"))

            sync.notifyExternalChange()

            val reloaded = expectMostRecentItem()
            assertEquals(listOf("Imported"), reloaded.accounts.getValue(AiService.OPENAI).map { it.label })
        }
    }

    @Test
    fun `flush writes pending edits and discard drops them`() = runTest(dispatcher) {
        viewModel.addAccount(AiService.OPENAI)
        val id = accounts(AiService.OPENAI).single().id
        viewModel.updateField(AiService.OPENAI, id, KEY_API_KEY, "sk-flush")

        sync.flushPending()

        assertEquals("sk-flush", prefs.getAccounts(AiService.OPENAI).single().credential.accessToken)

        viewModel.updateField(AiService.OPENAI, id, KEY_API_KEY, "sk-discarded")
        sync.discardPending()
        advanceTimeBy(1_000)

        assertEquals("sk-flush", prefs.getAccounts(AiService.OPENAI).single().credential.accessToken)
    }
}
