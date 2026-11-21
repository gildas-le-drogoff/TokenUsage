package fr.gildasledrogoff.tokenusage.core.domain.model

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

enum class QuotaDisplayMode {
    REMAINING, USED;

    companion object {
        fun fromName(name: String?): QuotaDisplayMode =
            entries.firstOrNull { it.name == name } ?: REMAINING
    }
}

enum class LockScreenVisibility {
    SHOW_ALL, SYSTEM, HIDDEN;

    companion object {
        fun fromName(name: String?): LockScreenVisibility =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
