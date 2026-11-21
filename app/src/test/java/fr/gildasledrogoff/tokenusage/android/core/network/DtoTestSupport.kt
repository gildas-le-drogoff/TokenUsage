package fr.gildasledrogoff.tokenusage.core.network
import fr.gildasledrogoff.tokenusage.di.NetworkModule
import kotlinx.serialization.json.Json

object DtoTestSupport {
    val json: Json = NetworkModule.provideJson()
    
    fun fixture(path: String): String {
        val stream = DtoTestSupport::class.java.classLoader!!.getResourceAsStream("fixtures/$path")
            ?: error("Fixture introuvable : fixtures/$path")
        return stream.bufferedReader().use { it.readText() }
    }
    inline fun <reified T> parse(text: String): T = json.decodeFromString(text)
    inline fun <reified T> parseFixture(path: String): T = parse(fixture(path))
}
