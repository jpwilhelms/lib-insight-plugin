package dev.wilhelms.gradle.insight

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

class LibrariesIoService(private val ctx: ServiceContext) {

    private fun fetchUrlAsync(url: String, accept: String = "application/json"): CompletableFuture<String?> {
        val requestBuilder = HttpRequest.newBuilder().uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", accept)
            .header("User-Agent", ctx.userAgent)
        
        val finalUrl = if (!ctx.librariesIoToken.isNullOrBlank() && accept == "application/json") {
            "$url?api_key=${ctx.librariesIoToken}"
        } else url

        val request = requestBuilder.uri(URI.create(finalUrl)).build()
        
        return ctx.client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { if (it.statusCode() == 200) it.body() else null }
            .exceptionally { null }
    }

    fun fetchRaw(group: String, name: String): LibrariesIoRaw? {
        val encodedName = URLEncoder.encode("$group:$name", StandardCharsets.UTF_8)
        
        // Parallel fetch for JSON and HTML
        val apiFuture = fetchUrlAsync("https://libraries.io/api/maven/$encodedName")
        val webFuture = fetchUrlAsync("https://libraries.io/maven/$encodedName", "text/html")
        
        CompletableFuture.allOf(apiFuture, webFuture).join()
        
        val apiJson = apiFuture.get() ?: return null
        val webHtml = webFuture.get()
        
        return LibrariesIoRaw(apiJson, webHtml)
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
                averageReleaseFrequency = null,
                sourcerankBreakdown = breakdown
            )
        } catch (e: Exception) { return null }
    }
}

data class LibrariesIoRaw(val apiJson: String, val webHtml: String?)
