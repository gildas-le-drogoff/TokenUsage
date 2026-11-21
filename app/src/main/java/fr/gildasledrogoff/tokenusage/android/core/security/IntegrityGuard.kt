package fr.gildasledrogoff.tokenusage.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.gildasledrogoff.tokenusage.BuildConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class IntegrityThreat {
    ROOT,
    HOOKING_FRAMEWORK,
    DEBUGGER,
    TAMPERED_BUILD
}

@Singleton
class IntegrityGuard @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val isEnforced: Boolean = !BuildConfig.IS_DEBUG
    val threats: Set<IntegrityThreat> by lazy { scan() }
    val isDeviceTrusted: Boolean by lazy { resolveTrust() }
    val isVaultLocked: Boolean get() = isEnforced && !isDeviceTrusted
    fun establishBaseline() {
        isDeviceTrusted
    }

    private fun resolveTrust(): Boolean {
        val prefs = consentPrefs()
        if (prefs.getBoolean(KEY_RISK_ACCEPTED, false) || prefs.getBoolean(
                KEY_DEVICE_TRUSTED,
                false
            )
        ) {
            return true
        }
        if (threats.isEmpty() || hasExistingVault()) {
            prefs.edit().putBoolean(KEY_DEVICE_TRUSTED, true).apply()
            return true
        }
        return false
    }

    private fun hasExistingVault(): Boolean {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        return VAULT_PREFS_FILES.any { File(sharedPrefsDir, "$it.xml").exists() } ||
                context.getDatabasePath(DATABASE_FILE).exists()
    }

    fun acceptRisk() {
        consentPrefs().edit().putBoolean(KEY_RISK_ACCEPTED, true).commit()
    }

    private fun consentPrefs() =
        context.getSharedPreferences(CONSENT_PREFS, Context.MODE_PRIVATE)

    private fun scan(): Set<IntegrityThreat> = buildSet {
        if (isRooted()) add(IntegrityThreat.ROOT)
        if (isHookFrameworkLoaded()) add(IntegrityThreat.HOOKING_FRAMEWORK)
        if (isDebuggerOrTracerAttached()) add(IntegrityThreat.DEBUGGER)
        if (isEnforced && isRuntimeDebuggable()) add(IntegrityThreat.TAMPERED_BUILD)
    }

    private fun isDebuggerOrTracerAttached(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger() || tracerPid() != 0

    private fun tracerPid(): Int = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("TracerPid:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        }
    }.getOrDefault(0)

    private fun isRuntimeDebuggable(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun isRooted(): Boolean =
        Build.TAGS?.contains("test-keys") == true ||
                ROOT_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) } ||
                ROOT_PACKAGES.any(::isPackageInstalled)

    private fun isPackageInstalled(packageName: String): Boolean = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isHookFrameworkLoaded(): Boolean =
        HOOK_CLASSES.any { name ->
            runCatching {
                Class.forName(
                    name,
                    false,
                    IntegrityGuard::class.java.classLoader
                )
            }.isSuccess
        } ||
                runCatching {
                    File("/proc/self/maps").useLines { lines ->
                        lines.any { line ->
                            HOOK_MAPS_MARKERS.any {
                                line.contains(
                                    it,
                                    ignoreCase = true
                                )
                            }
                        }
                    }
                }.getOrDefault(false)

    private companion object {
        const val CONSENT_PREFS = "tokenusage_integrity_consent"
        const val KEY_RISK_ACCEPTED = "risk_accepted"
        const val KEY_DEVICE_TRUSTED = "device_trusted"
        val VAULT_PREFS_FILES = listOf(
            TinkKeysetVault.KEYSET_FILE,
            SecureStorageFactory.CREDENTIALS_STORE,
            SecureStorageFactory.DATABASE_KEY_STORE,
            LegacyEncryptedPrefsMigrator.LEGACY_FILE
        )
        const val DATABASE_FILE = "tokenusage.db"
        val ROOT_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su",
            "/vendor/bin/su", "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/system/app/Superuser.apk",
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap",
            "/cache/.disable_magisk", "/dev/.magisk.unblock"
        )
        val ROOT_PACKAGES = listOf(
            "com.topjohnwu.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu",
            "me.bmax.apatch", "eu.chainfire.supersu", "com.koushikdutta.superuser",
            "com.noshufou.android.su", "de.robv.android.xposed.installer", "org.lsposed.manager"
        )
        val HOOK_CLASSES = listOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "com.saurik.substrate.MS"
        )
        val HOOK_MAPS_MARKERS = listOf(
            "frida", "gum-js", "gadget", "xposed", "lspd", "lsposed", "edxp", "substrate", "riru"
        )
    }
}
