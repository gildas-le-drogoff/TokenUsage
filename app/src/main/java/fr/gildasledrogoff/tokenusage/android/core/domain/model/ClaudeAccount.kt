package fr.gildasledrogoff.tokenusage.core.domain.model


data class ClaudeAccount(
    val id: String,
    val label: String,
    val credential: Credential
) {
    init {
        require(credential is Credential.ClaudeCredential || credential is Credential.ClaudeWebCredential) {
            "A Claude account needs a Claude credential"
        }
    }
}
