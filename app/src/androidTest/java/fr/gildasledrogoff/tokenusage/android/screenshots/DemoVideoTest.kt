package fr.gildasledrogoff.tokenusage.screenshots

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import fr.gildasledrogoff.tokenusage.MainActivity
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import kotlin.concurrent.thread
import kotlin.math.abs
import org.junit.Test

/**
 * Vidéo de démo de l'app, pilotée par `generate-screenshots.sh`.
 *
 * Contrairement aux captures, rien n'est accéléré : pas de règle de test
 * Compose (son horloge virtuelle sauterait les animations), mais de vrais
 * gestes injectés en temps réel et des pauses de lecture. L'écran est
 * enregistré par `screenrecord` dans [REMOTE_FILE], que le script récupère
 * puis convertit en mp4 et gif.
 *
 * Arguments d'instrumentation : `locale` et `format`.
 */
class DemoVideoTest {

    private val uiAutomation = StoreDevice.instrumentation.uiAutomation
    private lateinit var format: StoreFormat

    @Test
    fun recordDemo() {
        val args = InstrumentationRegistry.getArguments()
        val locale = args.getString("locale") ?: DEFAULT_LOCALE
        format = StoreFormat.named(args.getString("format") ?: DEFAULT_FORMAT)

        val settings = StoreDevice.string(locale, "cd_settings")
        val toggleTheme = StoreDevice.string(locale, "cd_toggle_theme")
        val openHistory = StoreDevice.string(locale, "dashboard_open_history")
        val historyTitle = StoreDevice.string(locale, "screen_history_title")
        val monthly = StoreDevice.string(locale, "granularity_monthly")
        val back = StoreDevice.string(locale, "cd_back")

        StoreDevice.applyDisplay(format)
        StoreDevice.setAppLocale(locale)
        StoreDevice.shell("rm -f $REMOTE_FILE")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Le bouton de thème écrit une préférence : remise en état à la fin.
            var savedTheme: ThemeMode? = null
            scenario.onActivity { savedTheme = it.displayPreferences.themeMode.value }
            try {
                waitFor(settings)
                waitFor(FIRST_CARD)
                val recorder = startRecording()
                try {
                    playScenario(settings, toggleTheme, openHistory, historyTitle, monthly, back)
                } finally {
                    StoreDevice.shell("pkill -INT screenrecord")
                    recorder.join(RECORDER_STOP_TIMEOUT_MS)
                }
            } finally {
                scenario.onActivity { activity -> savedTheme?.let(activity.displayPreferences::setThemeMode) }
            }
        }
    }

    private fun playScenario(
        settings: String,
        toggleTheme: String,
        openHistory: String,
        historyTitle: String,
        monthly: String,
        back: String
    ) {
        step("Tableau de bord")
        pause(READ_MS)
        scrollTo(DEEPSEEK, percent = 30)
        pause(SHORT_MS)

        step("Consommation DeepSeek")
        tap(waitFor(DEEPSEEK))
        // Dépliage puis chargement du graphique ; le bouton d'historique est
        // encore sous l'écran, donc absent de l'arbre d'accessibilité.
        pause(READ_MS)
        scrollTo(openHistory, percent = 85, seekPercent = NEARBY_SEEK_PERCENT)
        pause(READ_MS)
        find(SECOND_ACCOUNT, lowest = true)?.let {
            tap(it)
            pause(READ_MS)
        }

        step("Historique")
        tap(waitFor(openHistory))
        waitFor(historyTitle)
        pause(READ_MS)
        tap(waitFor(monthly))
        pause(READ_MS)
        uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        waitFor(settings)
        pause(SHORT_MS)

        step("Thème sombre")
        tap(waitFor(toggleTheme))
        pause(READ_MS)
        repeat(SCROLL_BACK_SWIPES) { drag(dy = -format.height * SWIPE_PERCENT / 100) }
        pause(READ_MS)

        step("Réglages")
        tap(waitFor(settings))
        waitFor(back)
        pause(READ_MS)
        repeat(SETTINGS_SWIPES) {
            drag(dy = format.height * SWIPE_PERCENT / 100)
            pause(SHORT_MS)
        }
        pause(READ_MS)
    }

    // region Enregistrement

    private fun startRecording(): Thread {
        val recorder = thread(name = "screenrecord") {
            StoreDevice.shell("screenrecord --bit-rate $BIT_RATE --time-limit $TIME_LIMIT_S $REMOTE_FILE")
        }
        // Laisse l'encodeur démarrer : les premières frames seraient perdues.
        SystemClock.sleep(RECORDER_START_MS)
        return recorder
    }

    // endregion

    // region Recherche à l'écran (arbre d'accessibilité, dans le processus)

    /**
     * Zone du nœud portant [label] (texte ou description). Correspondance
     * exacte de préférence, sinon partielle (texte fusionné d'une carte) ; le
     * plus petit nœud l'emporte, ou le plus bas si [lowest].
     */
    private fun find(label: String, lowest: Boolean = false, mustBeVisible: Boolean = true): Rect? {
        // Sans cela, le cache d'accessibilité rend des positions d'avant le
        // dernier défilement et les ajustements partent dans le mauvais sens.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) uiAutomation.clearCache()
        val root = uiAutomation.rootInActiveWindow ?: return null
        val exact = mutableListOf<Rect>()
        val partial = mutableListOf<Rect>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            if (mustBeVisible && !node.isVisibleToUser) continue
            val texts = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.isEmpty) continue
            when {
                texts.any { it == label } -> exact += bounds
                texts.any { label in it } -> partial += bounds
            }
        }
        val candidates = exact.ifEmpty { partial }
        return if (lowest) candidates.maxByOrNull { it.top } else candidates.minByOrNull { it.width() * it.height() }
    }

    private fun waitFor(label: String, mustBeVisible: Boolean = true): Rect {
        val deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            find(label, mustBeVisible = mustBeVisible)?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("« $label » introuvable à l'écran (verrou d'app ou données d'exemple désactivées ?)")
    }

    // endregion

    // region Gestes en temps réel

    /**
     * Fait défiler jusqu'à amener le haut de [label] à [percent] % de la
     * hauteur d'écran. Tant qu'il n'est pas à l'écran, avance par pas de
     * [seekPercent] % ; s'arrête dès que la liste ne bouge plus (fin atteinte).
     */
    private fun scrollTo(label: String, percent: Int, seekPercent: Int = SEEK_PERCENT) {
        val target = format.height * percent / 100
        val tolerance = format.height * SCROLL_TOLERANCE_PERCENT / 100
        val stuck = format.height * STUCK_PERCENT / 100
        var last: Int? = null
        repeat(MAX_SCROLL_STEPS) {
            val top = find(label, mustBeVisible = false)?.top
            if (top == null) {
                // Pas encore à l'écran : survol rapide de la liste.
                drag(dy = format.height * seekPercent / 100, max = seekPercent)
                SystemClock.sleep(SEEK_SETTLE_MS)
            } else {
                if (abs(top - target) <= tolerance) return
                if (last?.let { abs(top - it) <= stuck } == true) return
                last = top
                drag(dy = top - target)
                SystemClock.sleep(SETTLE_MS)
            }
        }
    }

    /** Glisse le contenu de [dy] px vers le haut (négatif : vers le bas), sans élan final. */
    private fun drag(dy: Int, max: Int = SWIPE_PERCENT) {
        val limit = format.height * max / 100
        val distance = dy.coerceIn(-limit, limit)
        val x = format.width / 2f
        val fromY = format.height * (if (distance >= 0) DRAG_START_UP_PERCENT else DRAG_START_DOWN_PERCENT) / 100f
        val toY = fromY - distance
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, x, fromY)
        for (i in 1..DRAG_STEPS) {
            SystemClock.sleep(DRAG_DURATION_MS / DRAG_STEPS)
            // Accélération puis freinage : mouvement lisible à l'image.
            val t = i / DRAG_STEPS.toFloat()
            val eased = t * t * (3 - 2 * t)
            inject(downTime, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * eased)
        }
        SystemClock.sleep(DRAG_HOLD_MS)
        inject(downTime, MotionEvent.ACTION_UP, x, toY)
    }

    private fun tap(target: Rect) {
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, target.exactCenterX(), target.exactCenterY())
        SystemClock.sleep(TAP_MS)
        inject(downTime, MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY())
        SystemClock.sleep(SETTLE_MS)
    }

    private fun inject(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun pause(ms: Long) = SystemClock.sleep(ms)

    // endregion

    private fun step(name: String) = StoreDevice.progress("  ▶ $name")

    private companion object {
        const val REMOTE_FILE = "/data/local/tmp/tokenusage-demo.mp4"
        const val DEFAULT_LOCALE = "en-US"
        const val DEFAULT_FORMAT = "phone"
        const val DEEPSEEK = "DeepSeek"
        const val FIRST_CARD = "Gemini"
        const val SECOND_ACCOUNT = "Pro (USD)"

        const val BIT_RATE = 12_000_000
        const val TIME_LIMIT_S = 90
        const val RECORDER_START_MS = 1_500L
        const val RECORDER_STOP_TIMEOUT_MS = 10_000L

        const val WAIT_TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
        const val READ_MS = 1_300L
        const val SHORT_MS = 700L
        const val SETTLE_MS = 400L
        const val TAP_MS = 70L

        const val DRAG_DURATION_MS = 650L
        const val DRAG_STEPS = 40
        const val DRAG_HOLD_MS = 120L
        const val DRAG_START_UP_PERCENT = 82
        const val DRAG_START_DOWN_PERCENT = 18
        const val SWIPE_PERCENT = 42
        const val SEEK_PERCENT = 62
        const val SEEK_SETTLE_MS = 120L
        const val NEARBY_SEEK_PERCENT = 22
        const val STUCK_PERCENT = 1
        const val SCROLL_TOLERANCE_PERCENT = 6
        const val MAX_SCROLL_STEPS = 16
        const val SCROLL_BACK_SWIPES = 2
        const val SETTINGS_SWIPES = 2
    }
}
