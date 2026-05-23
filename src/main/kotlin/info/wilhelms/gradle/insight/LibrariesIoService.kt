package info.wilhelms.gradle.insight

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class LibrariesIoService(private val apiKey: String?) {

    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()

    fun fetchRaw(group: String, name: String): LibrariesIoRaw? {
        if (apiKey.isNullOrBlank()) return null
        try {
            val packageName = URLEncoder.encode("$group:$name", StandardCharsets.UTF_8)
            val apiUrl = "https://libraries.io/api/maven/$packageName?api_key=$apiKey"
            val apiJson = fetchUrl(apiUrl) ?: return null
            val webUrl = "https://libraries.io/maven/$packageName/sourcerank"
            val webHtml = fetchUrl(webUrl)
            return LibrariesIoRaw(apiJson, webHtml)
        } catch (e: Exception) { return null }
    }

    private fun fetchUrl(url: String): String? {
        try {
            val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return if (response.statusCode() == 200) response.body() else null
        } catch (e: Exception) { return null }
    }

    fun parse(raw: LibrariesIoRaw): LibrariesIoInfo? {
        try {
            val json = JsonParser.parseString(raw.apiJson).asJsonObject
            val breakdown = mutableMapOf<String, Int>()
            raw.webHtml?.let { html ->
                val items = Regex("<span class=\"badge[^\"]*\">\\s*(\\d+)\\s*</span>\\s*([^<\\n]+)", RegexOption.MULTILINE)
                items.findAll(html).forEach { match ->
                    val value = match.groupValues[1].trim().toInt()
                    val name = match.groupValues[2].trim()
                    if (name.isNotBlank() && !name.startsWith("Explore")) {
                        breakdown[name] = value
                    }
                }
            }
            return LibrariesIoInfo(
                dependentsCount = json.get("dependents_count")?.asInt ?: 0,
                dependentReposCount = json.get("dependent_repos_count")?.asInt ?: 0,
                rank = json.get("rank")?.asInt ?: 0,
                sourcerankBreakdown = if (breakdown.isNotEmpty()) breakdown else null
            )
        } catch (e: Exception) { return null }
    }
}

data class LibrariesIoRaw(val apiJson: String, val webHtml: String?)

data class LibrariesIoInfo(
    val dependentsCount: Int,
    val dependentReposCount: Int,
    val rank: Int,
    val sourcerankBreakdown: Map<String, Int>? = null
)
