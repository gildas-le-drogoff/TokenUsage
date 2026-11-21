package fr.gildasledrogoff.tokenusage.core.network

import fr.gildasledrogoff.tokenusage.di.NetworkModule
import kotlinx.serialization.json.Json

/**
 * Outils communs aux tests de parsing des DTO.
 *
 * Utilise la configuration [Json] réelle de l'application
 * ([NetworkModule.provideJson]) pour que les tests suivent toute évolution
 * de ses options (`ignoreUnknownKeys`, `coerceInputValues`, `isLenient`).
 */
object DtoTestSupport {

    val json: Json = NetworkModule.provideJson()

    /** Charge `app/src/test/resources/fixtures/<path>`. */
    fun fixture(path: String): String {
        val stream = DtoTestSupport::class.java.classLoader!!.getResourceAsStream("fixtures/$path")
            ?: error("Fixture introuvable : fixtures/$path")
        return stream.bufferedReader().use { it.readText() }
    }

    inline fun <reified T> parse(text: String): T = json.decodeFromString(text)

    inline fun <reified T> parseFixture(path: String): T = parse(fixture(path))
}
