package fr.gildasledrogoff.tokenusage.feature.settings.backup
import fr.gildasledrogoff.tokenusage.core.backup.BackupCryptoException
import fr.gildasledrogoff.tokenusage.core.backup.BackupPayload
import fr.gildasledrogoff.tokenusage.core.backup.ConfigBackupManager
import fr.gildasledrogoff.tokenusage.core.backup.ImportSummary
import fr.gildasledrogoff.tokenusage.core.backup.InvalidBackupException
import fr.gildasledrogoff.tokenusage.core.backup.QrChunkCodec
import fr.gildasledrogoff.tokenusage.core.backup.QrPayloadTooLargeException
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.workmanager.RefreshScheduler
import fr.gildasledrogoff.tokenusage.feature.settings.accounts.AccountsSync
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val manager = mock(ConfigBackupManager::class.java)
    private val scheduler = mock(RefreshScheduler::class.java)
    private val accountsSync = AccountsSync()
    private lateinit var viewModel: BackupViewModel
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = BackupViewModel(manager, scheduler, accountsSync)
    }
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    private val state get() = viewModel.uiState.value
    @Test
    fun `export is refused with a too short password`() = runTest {
        viewModel.updateExportPin("12345")
        viewModel.prepareZipExport()
        assertEquals(ExportError.PinTooShort, state.export.error)
        viewModel.prepareQrExport()
        verify(manager, never()).createZipBytes("12345")
        verify(manager, never()).createQrCodes("12345")
        assertFalse(state.export.isBusy)
    }
    @Test
    fun `export is refused when the confirmation differs`() = runTest {
        viewModel.updateExportPin("correct horse")
        viewModel.updateExportConfirmPin("correct hors")
        viewModel.prepareZipExport()
        assertEquals(ExportError.PinMismatch, state.export.error)
        assertFalse(viewModel.isExportPinValid())
    }
    @Test
    fun `pin validity - empty confirmation is accepted until typed`() {
        viewModel.updateExportPin("correct horse")
        assertTrue(viewModel.isExportPinValid())
        viewModel.updateExportConfirmPin("correct horse")
        assertTrue(viewModel.isExportPinValid())
        viewModel.updateExportPin("short")
        assertFalse(viewModel.isExportPinValid())
    }
    @Test
    fun `editing the pin clears the error and a ready zip`() {
        viewModel.updateExportPin("123")
        viewModel.prepareZipExport()
        assertEquals(ExportError.PinTooShort, state.export.error)
        viewModel.updateExportPin("1234")
        assertNull(state.export.error)
        assertFalse(state.export.zipReady)
    }
    @Test
    fun `zip export hands the bytes over once`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        `when`(manager.createZipBytes("correct horse")).thenReturn(bytes)
        viewModel.updateExportPin("correct horse")
        viewModel.updateExportConfirmPin("correct horse")
        viewModel.prepareZipExport()
        val ready = viewModel.uiState.first { it.export.zipReady }
        assertTrue(ready.export.zipFileName.matches(Regex("tokenusage-config-\\d{8}-\\d{4}\\.zip")))
        assertFalse(ready.export.isBusy)
        assertArrayEquals(bytes, viewModel.consumeZipBytes())
        assertNull(viewModel.consumeZipBytes())
        viewModel.onZipHandled()
        assertFalse(state.export.zipReady)
    }
    @Test
    fun `zip export failure is reported`() = runTest {
        `when`(manager.createZipBytes("correct horse")).thenAnswer { throw IllegalStateException("boom") }
        viewModel.updateExportPin("correct horse")
        viewModel.prepareZipExport()
        val failed = viewModel.uiState.first { it.export.error != null }
        assertEquals(ExportError.Failed, failed.export.error)
        assertFalse(failed.export.zipReady)
        assertNull(viewModel.consumeZipBytes())
    }
    @Test
    fun `qr export shows the codes and navigates between them`() = runTest {
        `when`(manager.createQrCodes("correct horse")).thenReturn(listOf("a", "b", "c"))
        viewModel.updateExportPin("correct horse")
        viewModel.prepareQrExport()
        val ready = viewModel.uiState.first { it.export.qrCodes != null }
        assertEquals(listOf("a", "b", "c"), ready.export.qrCodes)
        assertEquals(0, ready.export.qrIndex)
        viewModel.previousExportQr()
        assertEquals(0, state.export.qrIndex)
        viewModel.nextExportQr()
        viewModel.nextExportQr()
        viewModel.nextExportQr()
        assertEquals(2, state.export.qrIndex)
        viewModel.previousExportQr()
        assertEquals(1, state.export.qrIndex)
        viewModel.dismissQrExport()
        assertNull(state.export.qrCodes)
        assertEquals(0, state.export.qrIndex)
    }
    @Test
    fun `qr export too large is reported as such`() = runTest {
        `when`(manager.createQrCodes("correct horse")).thenAnswer { throw QrPayloadTooLargeException() }
        viewModel.updateExportPin("correct horse")
        viewModel.prepareQrExport()
        val failed = viewModel.uiState.first { it.export.error != null }
        assertEquals(ExportError.QrTooLarge, failed.export.error)
        assertNull(failed.export.qrCodes)
    }
    private val container = byteArrayOf(9, 8, 7)
    @Test
    fun `a single qr code leads to the pin prompt`() {
        `when`(manager.parseQrPayload("SINGLE")).thenReturn(container)
        viewModel.onQrScanned("  SINGLE\n")
        assertTrue(state.importState.awaitingPin)
        assertNull(state.importState.error)
    }
    @Test
    fun `an unreadable qr code is not a backup`() {
        `when`(manager.parseQrPayload("JUNK")).thenAnswer {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "no")
        }
        viewModel.onQrScanned("JUNK")
        assertFalse(state.importState.awaitingPin)
        assertEquals(ImportError.NotABackup, state.importState.error)
    }
    private fun multiQrChunks(): List<String> {
        val payload = "QUJD".repeat(4000)
        val chunks = QrChunkCodec.encode(payload)
        assertTrue("test payload must need several qr codes", chunks.size > 1)
        return chunks
    }
    @Test
    fun `multi qr scan tracks the progress whatever the order`() {
        val chunks = multiQrChunks()
        val payload = "QUJD".repeat(4000)
        `when`(manager.parseQrPayload(payload)).thenReturn(container)
        viewModel.onQrScanned(chunks[1])
        assertEquals(chunks.size, state.importState.scanSession!!.total)
        assertEquals(setOf(2), state.importState.scanSession!!.received)
        assertEquals(1, state.importState.scanSession!!.nextIndex)
        assertFalse(state.importState.awaitingPin)
        viewModel.onQrScanned(chunks[1])
        assertEquals(setOf(2), state.importState.scanSession!!.received)
        chunks.indices.filter { it != 1 }.forEach { viewModel.onQrScanned(chunks[it]) }
        assertTrue(state.importState.awaitingPin)
        assertNull(state.importState.scanSession)
        assertNull(state.importState.error)
    }
    @Test
    fun `a chunk of another backup is rejected without losing the session`() {
        val chunks = multiQrChunks()
        val other = QrChunkCodec.encode("WFla".repeat(4000))
        viewModel.onQrScanned(chunks[0])
        viewModel.onQrScanned(other[1])
        assertEquals(ImportError.QrMismatch, state.importState.error)
        assertEquals(setOf(1), state.importState.scanSession!!.received)
    }
    @Test
    fun `a single qr code during a multi scan is a mismatch`() {
        viewModel.onQrScanned(multiQrChunks()[0])
        viewModel.onQrScanned("SINGLE")
        assertEquals(ImportError.QrMismatch, state.importState.error)
        assertFalse(state.importState.awaitingPin)
    }
    @Test
    fun `a corrupted chunk is not a backup`() {
        val broken = multiQrChunks()[0].dropLast(3) + "###"
        viewModel.onQrScanned(broken)
        assertEquals(ImportError.NotABackup, state.importState.error)
        assertNull(state.importState.scanSession)
    }
    @Test
    fun `restarting the scan forgets the received chunks`() {
        val chunks = multiQrChunks()
        viewModel.onQrScanned(chunks[0])
        viewModel.restartQrScanSession()
        assertNull(state.importState.scanSession)
        viewModel.onQrScanned(chunks[1])
        assertEquals(setOf(2), state.importState.scanSession!!.received)
    }
    @Test
    fun `a zip file leads to the pin prompt`() = runTest {
        val zip = byteArrayOf(5, 5)
        val input = ByteArrayInputStream(zip)
        `when`(manager.readBackupFile(input)).thenReturn(zip)
        `when`(manager.parseZip(zip)).thenReturn(container)
        viewModel.onZipPicked { input }
        val awaiting = viewModel.uiState.first { it.importState.awaitingPin || it.importState.error != null }
        assertTrue(awaiting.importState.awaitingPin)
    }
    @Test
    fun `an unreadable zip file is not a backup`() = runTest {
        viewModel.onZipPicked { null }
        val failed = viewModel.uiState.first { it.importState.error != null }
        assertEquals(ImportError.NotABackup, failed.importState.error)
        assertFalse(failed.importState.awaitingPin)
    }
    private fun awaitingPin() {
        `when`(manager.parseQrPayload("SINGLE")).thenReturn(container)
        viewModel.onQrScanned("SINGLE")
    }
    @Test
    fun `restore needs a pin`() = runTest {
        awaitingPin()
        viewModel.confirmImport()
        verify(manager, never()).decryptContainer(container, "")
        assertFalse(state.importState.isBusy)
    }
    @Test
    fun `restore success reports the counts and reschedules the refresh`() = runTest {
        val payload = mock(BackupPayload::class.java)
        `when`(manager.decryptContainer(container, "secret")).thenReturn(payload)
        `when`(manager.restore(payload)).thenReturn(ImportSummary(mapOf(AiService.CLAUDE to 2), historyEntries = 3))
        awaitingPin()
        viewModel.updateImportPin("secret")
        viewModel.confirmImport()
        val done = viewModel.uiState.first { it.importState.importedCount != null }
        assertEquals(2, done.importState.importedCount)
        assertEquals(3, done.importState.importedHistoryEntries)
        assertFalse(done.importState.awaitingPin)
        assertFalse(done.importState.isBusy)
        verify(scheduler).applySaved()
        viewModel.onImportResultShown()
        assertNull(state.importState.importedCount)
    }
    @Test
    fun `restore notifies the accounts screen and drops its pending edits`() = runTest {
        val edits = RecordingEdits()
        accountsSync.register(edits)
        val payload = mock(BackupPayload::class.java)
        `when`(manager.decryptContainer(container, "secret")).thenReturn(payload)
        `when`(manager.restore(payload)).thenReturn(ImportSummary(emptyMap(), 0))
        awaitingPin()
        viewModel.updateImportPin("secret")
        accountsSync.externalChanges.test {
            viewModel.confirmImport()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, edits.discarded)
    }
    @Test
    fun `pending account edits are saved before an export`() = runTest {
        val edits = RecordingEdits()
        accountsSync.register(edits)
        `when`(manager.createQrCodes("correct horse")).thenReturn(listOf("a"))
        viewModel.updateExportPin("correct horse")
        viewModel.prepareQrExport()
        viewModel.uiState.first { it.export.qrCodes != null }
        assertEquals(1, edits.flushed)
    }
    private class RecordingEdits : AccountsSync.PendingEdits {
        var flushed = 0
        var discarded = 0
        override fun flush() {
            flushed++
        }
        override fun discard() {
            discarded++
        }
    }
    @Test
    fun `wrong pin keeps the prompt open and clears the pin`() = runTest {
        `when`(manager.decryptContainer(container, "bad")).thenAnswer { throw BackupCryptoException("wrong") }
        awaitingPin()
        viewModel.updateImportPin("bad")
        viewModel.confirmImport()
        val failed = viewModel.uiState.first { it.importState.error != null }
        assertEquals(ImportError.WrongPin, failed.importState.error)
        assertTrue(failed.importState.awaitingPin)
        assertEquals("", failed.importState.pin)
        assertFalse(failed.importState.isBusy)
    }
    @Test
    fun `restore errors are told apart`() = runTest {
        awaitingPin()
        val cases = listOf(
            InvalidBackupException(InvalidBackupException.Reason.UNSUPPORTED_VERSION, "v") to ImportError.UnsupportedVersion,
            InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "x") to ImportError.NotABackup,
            IllegalStateException("io") to ImportError.Failed
        )
        for ((exception, expected) in cases) {
            doAnswer { throw exception }.`when`(manager).decryptContainer(container, "pin")
            viewModel.updateImportPin("pin")
            viewModel.confirmImport()
            val failed = viewModel.uiState.first { it.importState.error != null && !it.importState.isBusy }
            assertEquals(expected, failed.importState.error)
        }
    }
    @Test
    fun `dismissing the import forgets the container`() = runTest {
        awaitingPin()
        viewModel.updateImportPin("secret")
        viewModel.dismissImport()
        viewModel.confirmImport()
        assertEquals(ImportUiState(), state.importState)
        verify(manager, never()).decryptContainer(container, "secret")
    }
    @Test
    fun `typing a pin or clearing the error`() {
        viewModel.onQrScanned("")
        viewModel.clearImportError()
        assertNull(state.importState.error)
        awaitingPin()
        viewModel.updateImportPin("abc")
        assertEquals("abc", state.importState.pin)
    }
}
