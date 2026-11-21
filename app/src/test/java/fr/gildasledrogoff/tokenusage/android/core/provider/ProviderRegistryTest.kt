package fr.gildasledrogoff.tokenusage.core.provider
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
class ProviderRegistryTest {
    private class FakeProvider(
        override val service: AiService,
        var configured: List<ServiceAccount> = emptyList()
    ) : QuotaProvider {
        override val form = CredentialForm(fields = emptyList(), assemble = { _, _ -> null })
        override fun accounts() = configured
        override suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError> =
            Result.Success(QuotaInfo(service, emptyList(), accountId = account.id))
    }
    private fun account(id: String, label: String = "") = ServiceAccount(id, label, Credential.ApiKeyCredential("k-$id"))
    private fun allProviders() = AiService.entries.map { FakeProvider(it) }
    @Test
    fun `providers come in AiService order whatever the set order`() {
        val registry = ProviderRegistry(allProviders().reversed().toSet())
        assertEquals(AiService.entries, registry.providers.map { it.service })
        AiService.entries.forEach { assertEquals(it, registry.provider(it).service) }
    }
    @Test
    fun `a service without provider fails fast`() {
        val missing = allProviders().filterNot { it.service == AiService.GEMINI }.toSet()
        val error = assertThrows(IllegalStateException::class.java) { ProviderRegistry(missing) }
        assertTrue(error.message!!.contains("GEMINI"))
    }
    @Test
    fun `targets list every account of every service`() {
        val providers = allProviders()
        providers.first { it.service == AiService.CODEX }.configured = listOf(account("c1"), account("c2"))
        providers.first { it.service == AiService.OPENAI }.configured = listOf(account("o1"))
        val registry = ProviderRegistry(providers.toSet())
        val targets = registry.targets()
        assertEquals(listOf("c1", "c2", "o1"), targets.map { it.account.id })
        assertEquals(listOf(AiService.CODEX, AiService.CODEX, AiService.OPENAI), targets.map { it.service })
        assertTrue(registry.hasAnyAccount())
    }
    @Test
    fun `accounts are named by label, else numbered when the service has several`() {
        val providers = allProviders()
        providers.first { it.service == AiService.CODEX }.configured =
            listOf(account("c1", "Work"), account("c2"), account("c3"))
        providers.first { it.service == AiService.COPILOT }.configured = listOf(account("p1"))
        val registry = ProviderRegistry(providers.toSet())
        val codex = registry.targets(AiService.CODEX)
        assertEquals(listOf<String?>("Work", null, null), codex.map { it.label })
        assertEquals(listOf<Int?>(null, 2, 3), codex.map { it.number })
        val copilot = registry.targets(AiService.COPILOT).single()
        assertNull("a lone account stays unlabelled", copilot.label)
        assertNull(copilot.number)
    }
    @Test
    fun `a target fetches its own account`() = runTest {
        val providers = allProviders()
        providers.first { it.service == AiService.OPENROUTER }.configured = listOf(account("a"), account("b"))
        val registry = ProviderRegistry(providers.toSet())
        val quotas = registry.targets(AiService.OPENROUTER).map { (it.fetchQuota() as Result.Success).value }
        assertEquals(listOf("a", "b"), quotas.map { it.accountId })
    }
    @Test
    fun `no account anywhere`() {
        val registry = ProviderRegistry(allProviders().toSet())
        assertFalse(registry.hasAnyAccount())
        assertTrue(registry.targets().isEmpty())
        assertSame(registry.provider(AiService.ZAI), registry.providers.first { it.service == AiService.ZAI })
    }
}
