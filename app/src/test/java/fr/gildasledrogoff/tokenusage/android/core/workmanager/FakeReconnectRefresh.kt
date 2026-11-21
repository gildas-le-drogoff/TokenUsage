package fr.gildasledrogoff.tokenusage.core.workmanager

class FakeReconnectRefresh(private var enabled: Boolean = false) : ReconnectRefresh {
    var offlineSignals = 0
        private set
    var onlineSignals = 0
        private set

    override fun isEnabled(): Boolean = enabled
    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun onOffline() {
        offlineSignals++
    }

    override fun onOnline() {
        onlineSignals++
    }
}
