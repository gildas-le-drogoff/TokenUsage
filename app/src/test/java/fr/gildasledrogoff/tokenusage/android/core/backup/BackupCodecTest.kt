package fr.gildasledrogoff.tokenusage.core.backup
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
class BackupCodecTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val payload = BackupPayload(
        exportedAtEpochMs = 1_700_000_000_000,
        credentials = mapOf(
            "CLAUDE" to ServiceCredentialBackup(authMode = "web_session", accessToken = "sk-ant-sid02-abc"),
            "CODEX" to ServiceCredentialBackup(accessToken = "at", refreshToken = "rt", accountId = "acc")
        ),
        deepseekAccounts = listOf(AccountBackup("d1", "Perso", "sk-ds")),
        refreshIntervalMinutes = 15
    )
    private fun qrText(p: BackupPayload, pin: String): String {
        val container = BackupCrypto.encrypt(BackupCrypto.deflate(json.encodeToString(p).toByteArray()), pin)
        return Base64.getEncoder().encodeToString(container)
    }
    private fun decode(text: String, pin: String): BackupPayload =
        BackupCodec.decodePayload(
            BackupCrypto.inflate(BackupCrypto.decrypt(BackupCodec.parseQrPayload(text), pin)),
            json
        )
    @Test
    fun `qr payload roundtrip restores the exported payload`() {
        assertEquals(payload, decode(qrText(payload, "4242"), "4242"))
    }
    private fun zipOf(entryName: String, content: ByteArray): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content)
                zip.closeEntry()
            }
            out.toByteArray()
        }
    @Test
    fun `zip export roundtrip restores the payload with its history`() {
        val withHistory = payload.copy(
            balanceHistory = listOf(BalanceSnapshotBackup("DEEPSEEK", "d1", "Perso", "CNY", 12.5, 1_700_000_000_000))
        )
        val container = BackupCrypto.encrypt(
            BackupCrypto.deflate(json.encodeToString(withHistory).toByteArray()), "4242"
        )
        val zip = zipOf(ConfigBackupManager.CONTAINED_ENTRY_NAME, container)
        val decoded = BackupCodec.decodePayload(
            BackupCrypto.inflate(BackupCrypto.decrypt(BackupCodec.parseZip(zip), "4242")),
            json
        )
        assertEquals(withHistory, decoded)
    }
    @Test
    fun `foreign files are rejected as zip backups`() {
        listOf(
            "not a zip at all".toByteArray(),
            zipOf("photo.jpg", byteArrayOf(1, 2, 3)),
            zipOf(ConfigBackupManager.CONTAINED_ENTRY_NAME, "CBX0garbage".toByteArray())
        ).forEach { bytes ->
            val e = assertThrows(InvalidBackupException::class.java) { BackupCodec.parseZip(bytes) }
            assertEquals(InvalidBackupException.Reason.NOT_A_BACKUP, e.reason)
        }
    }
    @Test
    fun `bounded read refuses oversized input`() {
        assertEquals(10, BackupCodec.readBounded(ByteArrayInputStream(ByteArray(10)), max = 10)?.size)
        assertNull(BackupCodec.readBounded(ByteArrayInputStream(ByteArray(11)), max = 10))
    }
    @Test
    fun `wrong pin is reported as a crypto error`() {
        val text = qrText(payload, "4242")
        assertThrows(BackupCryptoException::class.java) { decode(text, "0000") }
    }
    @Test
    fun `foreign qr codes are rejected before asking for the pin`() {
        listOf("https://example.com", "", "aGVsbG8gd29ybGQ=").forEach { text ->
            val e = assertThrows(InvalidBackupException::class.java) { BackupCodec.parseQrPayload(text) }
            assertEquals(InvalidBackupException.Reason.NOT_A_BACKUP, e.reason)
        }
    }
    @Test
    fun `newer backup versions are rejected`() {
        val future = json.encodeToString(payload.copy(version = BackupPayload.CURRENT_VERSION + 1)).toByteArray()
        val e = assertThrows(InvalidBackupException::class.java) { BackupCodec.decodePayload(future, json) }
        assertEquals(InvalidBackupException.Reason.UNSUPPORTED_VERSION, e.reason)
    }
    @Test
    fun `credentials map back to the right domain variants`() {
        assertEquals(
            Credential.ClaudeWebCredential("sk-ant-sid02-abc"),
            BackupCodec.toCredential(AiService.CLAUDE, payload.credentials.getValue("CLAUDE"))
        )
        assertEquals(
            Credential.ClaudeCredential(
                accessToken = "at",
                refreshToken = null,
                expiresAt = Instant.ofEpochSecond(1_800_000_000),
                scopes = "user:inference"
            ),
            BackupCodec.toCredential(
                AiService.CLAUDE,
                ServiceCredentialBackup(
                    authMode = "oauth",
                    accessToken = "at",
                    refreshToken = "",
                    expiresAtEpochSecond = 1_800_000_000,
                    scopes = "user:inference"
                )
            )
        )
        assertEquals(
            Credential.CodexCredential("at", "rt", "acc"),
            BackupCodec.toCredential(AiService.CODEX, payload.credentials.getValue("CODEX"))
        )
        assertNull(BackupCodec.toCredential(AiService.CODEX, ServiceCredentialBackup(accessToken = "at")))
        assertNull(
            BackupCodec.toCredential(
                AiService.GEMINI,
                ServiceCredentialBackup(accessToken = "at", refreshToken = "rt", oauthClientId = "id")
            )
        )
    }
    private fun apiKeyAccount(id: String, label: String, key: String) =
        ServiceAccount(id, label, Credential.ApiKeyCredential(key))
    @Test
    fun `account merge replaces same id or key and keeps the others`() {
        val existing = listOf(
            apiKeyAccount("a", "Old A", "key-a"),
            apiKeyAccount("b", "B", "key-b"),
            apiKeyAccount("c", "C", "key-c")
        )
        val imported = listOf(
            apiKeyAccount("a", "New A", "key-a2"),
            apiKeyAccount("x", "C again", "key-c")
        )
        val merged = BackupCodec.mergeAccounts(existing, imported)
        assertEquals(listOf("b", "a", "x"), merged.map { it.id })
        assertTrue(merged.any { it.label == "New A" })
    }
    @Test
    fun `claude account merge replaces same id or key and keeps the others`() {
        fun web(id: String, key: String) = ServiceAccount(id, id, Credential.ClaudeWebCredential(key))
        val existing = listOf(web("local-1", "sk-ant-sid02-a"), web("local-2", "sk-ant-sid02-b"), web("same-id", "old"))
        val imported = listOf(web("imported", "sk-ant-sid02-b"), web("same-id", "sk-ant-sid02-c"))
        val merged = BackupCodec.mergeAccounts(existing, imported)
        assertEquals(listOf("local-1", "imported", "same-id"), merged.map { it.id })
        assertEquals("sk-ant-sid02-c", merged.last().credential.accessToken)
    }
    @Test
    fun `v4 accounts of every service are read back with their credential type`() {
        val gemini = Credential.GeminiCredential("at", "rt", 5L, "client-id", "client-secret")
        val codex = Credential.CodexCredential("at", "rt", "acct")
        val payload = BackupPayload(
            exportedAtEpochMs = 1,
            accounts = mapOf(
                "CODEX" to listOf(
                    ServiceAccountBackup("c1", "Perso", BackupCodec.toBackup(codex)),
                    ServiceAccountBackup("c2", "Work", BackupCodec.toBackup(codex.copy(accessToken = "at2")))
                ),
                "GEMINI" to listOf(ServiceAccountBackup("g1", "", BackupCodec.toBackup(gemini))),
                "DEEPSEEK" to listOf(
                    ServiceAccountBackup("d1", "Main", BackupCodec.toBackup(Credential.DeepseekCredential("sk-1")))
                ),
                "CLAUDE" to listOf(
                    ServiceAccountBackup(
                        "k1", "Web", BackupCodec.toBackup(Credential.ClaudeWebCredential("sk-ant-sid02-x")), orgId = "org"
                    )
                )
            )
        )
        val imported = BackupCodec.importedAccounts(payload)
        assertEquals(listOf("c1", "c2"), imported.getValue(AiService.CODEX).map { it.account.id })
        assertEquals(codex, imported.getValue(AiService.CODEX).first().account.credential)
        assertEquals(gemini, imported.getValue(AiService.GEMINI).single().account.credential)
        assertEquals("sk-1", imported.getValue(AiService.DEEPSEEK).single().account.credential.accessToken)
        val claude = imported.getValue(AiService.CLAUDE).single()
        assertEquals(Credential.ClaudeWebCredential("sk-ant-sid02-x"), claude.account.credential)
        assertEquals("org", claude.orgId)
    }
    @Test
    fun `v3 payload sections still import as accounts`() {
        val legacy = BackupPayload(
            version = 3,
            exportedAtEpochMs = 1,
            credentials = mapOf(
                "CODEX" to ServiceCredentialBackup(accessToken = "at", refreshToken = "rt"),
                "OPENAI" to ServiceCredentialBackup(accessToken = "sk-admin")
            ),
            deepseekAccounts = listOf(AccountBackup("d1", "Main", "ds-key")),
            zaiAccounts = listOf(AccountBackup("z1", "Z", "zai-key")),
            claudeAccounts = listOf(
                ClaudeAccountBackup(
                    "k1", "Perso",
                    ServiceCredentialBackup(authMode = "web_session", accessToken = "sk-ant-sid02-y"),
                    orgId = "org-1"
                )
            )
        )
        val imported = BackupCodec.importedAccounts(legacy)
        assertEquals(setOf(AiService.CODEX, AiService.OPENAI, AiService.DEEPSEEK, AiService.ZAI, AiService.CLAUDE), imported.keys)
        assertTrue(imported.getValue(AiService.CODEX).single().account.credential is Credential.CodexCredential)
        assertEquals("ds-key", imported.getValue(AiService.DEEPSEEK).single().account.credential.accessToken)
        assertEquals("org-1", imported.getValue(AiService.CLAUDE).single().orgId)
    }
    @Test
    fun `current payload carries the accounts and decodes back`() {
        val written = json.encodeToString(
            BackupPayload(
                exportedAtEpochMs = 1,
                accounts = mapOf(
                    "OPENROUTER" to listOf(
                        ServiceAccountBackup("o1", "A", BackupCodec.toBackup(Credential.ApiKeyCredential("k1"))),
                        ServiceAccountBackup("o2", "B", BackupCodec.toBackup(Credential.ApiKeyCredential("k2")))
                    )
                )
            )
        )
        val decoded = BackupCodec.decodePayload(written.toByteArray(), json)
        assertEquals(BackupPayload.CURRENT_VERSION, decoded.version)
        assertEquals(listOf("o1", "o2"), decoded.accounts.getValue("OPENROUTER").map { it.id })
    }
    @Test
    fun `v2 payload with a single claude credential still decodes`() {
        val legacy = """{"version":2,"app":"tokenusage","exportedAtEpochMs":1,
            "credentials":{"CLAUDE":{"authMode":"web_session","accessToken":"sk-ant-sid02-x"}},
            "claudeOrgId":"org-x"}""".toByteArray()
        val decoded = BackupCodec.decodePayload(legacy, json)
        assertTrue(decoded.claudeAccounts.isEmpty())
        assertEquals("org-x", decoded.claudeOrgId)
        assertEquals(
            Credential.ClaudeWebCredential("sk-ant-sid02-x"),
            BackupCodec.toCredential(AiService.CLAUDE, decoded.credentials.getValue("CLAUDE"))
        )
    }
}
