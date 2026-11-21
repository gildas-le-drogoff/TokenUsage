package fr.gildasledrogoff.tokenusage.core.diagnostics

/** Ce qui a déclenché un rafraîchissement (affiché dans l'écran de diagnostic). */
enum class RefreshOrigin {
    /** Rafraîchissement lancé par le tableau de bord. */
    DASHBOARD,

    /** Travail périodique WorkManager (`QuotaRefreshWorker`). */
    PERIODIC_WORKER,

    /** Appel direct de `QuotaRefreshService.refreshNow()` : action de notification, widget… */
    MANUAL_SERVICE,

    /** Tuile des réglages rapides. */
    TILE
}
