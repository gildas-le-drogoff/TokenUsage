package fr.gildasledrogoff.tokenusage.screenshots

import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale

/** Format d'écran du store : taille et densité forcées via `wm`. */
internal data class StoreFormat(val name: String, val width: Int, val height: Int, val density: Int) {
    companion object {
        private val ALL = listOf(
            StoreFormat("phone", 1080, 1920, 420),
            StoreFormat("sevenInch", 1080, 1920, 280),
            StoreFormat("tenInch", 2560, 1440, 320)
        ).associateBy { it.name }

        fun named(name: String): StoreFormat =
            ALL[name] ?: error("format inconnu : $name (${ALL.keys.joinToString()})")
    }
}

/** Réglages de l'appareil partagés par les captures et la vidéo de démo. */
internal object StoreDevice {
    private const val SYSUI_SETTLE_MS = 2_000L

    val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext

    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    /** Taille, densité, thème clair et barre d'état figée (mode démo de SystemUI). */
    fun applyDisplay(format: StoreFormat) {
        demo("exit")
        shell("wm size ${format.width}x${format.height}")
        shell("wm density ${format.density}")
        shell("cmd uimode night no")
        // SystemUI se reconstruit après le changement de densité : le mode
        // démo n'est pris en compte qu'une fois la barre d'état recréée.
        SystemClock.sleep(SYSUI_SETTLE_MS)
        shell("settings put global sysui_demo_allowed 1")
        demo("enter")
        demo("clock -e hhmm 1200")
        demo("battery -e level 100 -e plugged false")
        demo("network -e wifi show -e level 4 -e fully true")
        demo("notifications -e visible false")
    }

    fun setAppLocale(locale: String) {
        shell("cmd locale set-app-locales ${context.packageName} --user 0 --locales $locale")
    }

    /**
     * Libellé de l'app dans [locale]. Recherche par nom : reste valable si
     * l'app est réinstallée sans l'APK de test.
     */
    fun string(locale: String, name: String): String {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(locale))
        val localized = context.createConfigurationContext(config)
        val id = localized.resources.getIdentifier(name, "string", context.packageName)
        check(id != 0) { "chaîne introuvable : $name" }
        return localized.getString(id)
    }

    /** Ligne affichée en direct par `am instrument`. */
    fun progress(line: String) {
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$line\n") }
        )
    }

    private fun demo(command: String) = shell("am broadcast -a com.android.systemui.demo -e command $command")
}
