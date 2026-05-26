package dev.wilhelms.gradle.insight

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

class LibrariesIoService(private val ctx: ServiceContext) {

    private fun fetchUrl(url: String): String? {
        try {
            val requestBuilder = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", ctx.userAgent)
            if (!ctx.librariesIoToken.isNullOrBlank()) {
                requestBuilder.uri(URI.create("$url?api_key=${ctx.librariesIoToken}"))
            }
            val response = ctx.client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            return if (response.statusCode() == 200) response.body() else null
        } catch (e: Exception) { return null }
    }

    private fun fetchHtml(url: String): String? {
        try {
            val request = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", ctx.userAgent)
                .build()
            val response = ctx.client.send(request, HttpResponse.BodyHandlers.ofString())
            return if (response.statusCode() == 200) response.body() else null
        } catch (e: Exception) { return null }
    }

    fun fetchRaw(group: String, name: String): LibrariesIoRaw? {
        try {
            val encodedName = URLEncoder.encode("$group:$name", StandardCharsets.UTF_8)
            val apiJson = fetchUrl("https://libraries.io/api/maven/$encodedName") ?: return null
            val webHtml = fetchHtml("https://libraries.io/maven/$encodedName")
            return LibrariesIoRaw(apiJson, webHtml)
        } catch (e: Exception) { return null }
    }

    fun parse(raw: LibrariesIoRaw): LibrariesIoData? {
        try {
            val element = JsonParser.parseString(raw.apiJson)
            if (!element.isJsonObject) return null
            val json = element.asJsonObject
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
            return LibrariesIoData(
                dependentsCount = json.get("dependents_count").safeInt() ?: 0,
                dependentReposCount = json.get("dependent_repos_count").safeInt() ?: 0,
                sourcerank = json.get("rank").safeInt() ?: 0,
                averageReleaseFrequency = null, // Future improvement
                sourcerankBreakdown = breakdown
            )
        } catch (e: Exception) { return null }
    }
}

data class LibrariesIoRaw(val apiJson: String, val webHtml: String?)
