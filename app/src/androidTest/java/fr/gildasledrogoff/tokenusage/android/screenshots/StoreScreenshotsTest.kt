package fr.gildasledrogoff.tokenusage.screenshots
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import fr.gildasledrogoff.tokenusage.MainActivity
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test

class StoreScreenshotsTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val instrumentation = StoreDevice.instrumentation
    private val outputRoot = File(StoreDevice.context.filesDir, OUTPUT_DIR)
    
    private val encoder = Executors.newFixedThreadPool(ENCODER_THREADS)
    private val pendingWrites = mutableListOf<Future<*>>()
    private var displayHeight = 0
    @Test
    fun captureStoreScreenshots() {
        val args = InstrumentationRegistry.getArguments()
        val locales = args.list("locales", DEFAULT_LOCALES)
        val formats = args.list("formats", DEFAULT_FORMATS).map(StoreFormat::named)
        outputRoot.deleteRecursively()
        try {
            for (format in formats) {
                progress("== ${format.name} (${format.width}x${format.height}, ${format.density} dpi)")
                StoreDevice.applyDisplay(format)
                displayHeight = format.height
                for (locale in locales) {
                    progress("-- $locale")
                    captureLocale(format, locale)
                }
            }
            pendingWrites.forEach { it.get() }
        } finally {
            encoder.shutdown()
        }
    }
    private fun captureLocale(format: StoreFormat, locale: String) {
        val out = File(outputRoot, "$locale/images/${format.name}Screenshots")
        val settings = StoreDevice.string(locale, "cd_settings")
        val back = StoreDevice.string(locale, "cd_back")
        val openHistory = StoreDevice.string(locale, "dashboard_open_history")
        val historyTitle = StoreDevice.string(locale, "screen_history_title")
        StoreDevice.setAppLocale(locale)
        shell("cmd uimode night no")
        withActivity { scenario ->
            waitFor(hasScrollToNodeAction())
            capture(File(out, "1_dashboard.png"))
            
            val grid = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
            grid.performScrollToNode(hasText(DEEPSEEK))
            compose.onAllNodes(hasText(DEEPSEEK), useUnmergedTree = true).onFirst().performClick()
            waitFor(hasText(openHistory), unmerged = true)
            scrollTo(hasText(DEEPSEEK), percent = 23)
            if (centerY(hasText(openHistory)) > displayHeight * 91 / 100) {
                scrollTo(hasText(openHistory), percent = 86)
            }
            capture(File(out, "2_consumption.png"))
            compose.onAllNodes(hasText(openHistory), useUnmergedTree = true).onFirst().performClick()
            waitFor(hasText(historyTitle), unmerged = true)
            capture(File(out, "3_history.png"))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(hasContentDescription(settings), unmerged = true)
            compose.onAllNodes(hasContentDescription(settings), useUnmergedTree = true).onFirst().performClick()
            waitFor(hasContentDescription(back), unmerged = true)
            capture(File(out, "4_settings.png"))
        }
        shell("cmd uimode night yes")
        withActivity {
            waitFor(hasScrollToNodeAction())
            capture(File(out, "5_dashboard_dark.png"))
        }
        shell("cmd uimode night no")
    }
    
    private fun withActivity(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                block(scenario)
            } catch (t: Throwable) {
                runCatching { writePng(screenshot(), File(outputRoot, FAILURE_FILE)) }
                throw t
            }
        }
    }
    
    private fun waitFor(matcher: SemanticsMatcher, unmerged: Boolean = false) {
        try {
            compose.waitUntil(WAIT_TIMEOUT_MS) {
                
                runCatching {
                    compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "« ${matcher.description} » introuvable à l'écran " +
                    "(verrou d'app ou données d'exemple désactivées ?)",
                t
            )
        }
    }
    
    private fun centerY(matcher: SemanticsMatcher): Float {
        val node = compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().fetchSemanticsNode()
        return node.positionInWindow.y + node.size.height / 2f
    }
    
    private fun scrollTo(matcher: SemanticsMatcher, percent: Int) {
        val target = displayHeight * percent / 100f
        val tolerance = displayHeight * 5 / 100f
        var last = Float.NaN
        repeat(MAX_SCROLL_STEPS) {
            val y = centerY(matcher)
            if (abs(y - target) <= tolerance || y == last) return
            last = y
            compose.onAllNodes(hasScrollToNodeAction()).onFirst()
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, y - target) }
            compose.waitForIdle()
        }
    }
    
    
    private fun screenshot(): Bitmap =
        instrumentation.uiAutomation.takeScreenshot() ?: error("capture d'écran impossible")
    
    private fun capture(file: File) {
        compose.waitForIdle()
        var previous = screenshot()
        var stable = false
        for (attempt in 1..MAX_STABLE_ATTEMPTS) {
            SystemClock.sleep(STABLE_FRAME_INTERVAL_MS)
            val current = screenshot()
            stable = current.sameAs(previous)
            previous.recycle()
            previous = current
            if (stable) break
        }
        val relative = file.relativeTo(outputRoot).path
        progress(if (stable) "  → $relative" else "  ! écran instable, dernière image conservée : $relative")
        val bitmap = previous
        pendingWrites += encoder.submit {
            writePng(bitmap, file)
            bitmap.recycle()
        }
    }
    private fun writePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }
    
    private fun shell(command: String) = StoreDevice.shell(command)
    private fun progress(line: String) = StoreDevice.progress(line)
    private fun Bundle.list(key: String, default: String): List<String> =
        (getString(key) ?: default).split(',', ' ').filter { it.isNotBlank() }
    private companion object {
        const val OUTPUT_DIR = "store-screenshots"
        const val FAILURE_FILE = "failure.png"
        const val DEEPSEEK = "DeepSeek"
        const val DEFAULT_LOCALES = "en-US,fr-FR,zh-CN"
        const val DEFAULT_FORMATS = "phone,sevenInch,tenInch"
        const val WAIT_TIMEOUT_MS = 15_000L
        const val STABLE_FRAME_INTERVAL_MS = 50L
        const val MAX_STABLE_ATTEMPTS = 40
        const val MAX_SCROLL_STEPS = 10
        const val ENCODER_THREADS = 4
        const val PNG_QUALITY = 100
    }
}
