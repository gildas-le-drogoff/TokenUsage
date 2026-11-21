package fr.gildasledrogoff.tokenusage.core.domain.model

import java.util.UUID

/**
 * One configured account of a service: a stable [id], a user-given [label] (may be blank)
 * and the [credential] used to query the service. Every service holds N of them.
 */
data class ServiceAccount(
    val id: String,
    val label: String,
    val credential: Credential
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

fun ClaudeAccount.toServiceAccount() = ServiceAccount(id, label, credential)

fun DeepseekAccount.toServiceAccount() = ServiceAccount(id, label, Credential.DeepseekCredential(apiKey))

fun ZaiAccount.toServiceAccount() = ServiceAccount(id, label, Credential.ZaiCredential(apiKey))
