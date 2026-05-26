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

    fun fetchRawAsync(group: String, name: String): CompletableFuture<LibrariesIoRaw?> {
        val encodedName = URLEncoder.encode("$group:$name", StandardCharsets.UTF_8)
        val baseUrl = "https://libraries.io/api/maven/$encodedName"
        val webUrl = "https://libraries.io/maven/$encodedName"

        val finalApiUrl = if (!ctx.librariesIoToken.isNullOrBlank()) {
            "$baseUrl?api_key=${ctx.librariesIoToken}"
        } else baseUrl

        val apiRequest = HttpRequest.newBuilder().uri(URI.create(finalApiUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", ctx.userAgent)
            .build()

        val webRequest = HttpRequest.newBuilder().uri(URI.create(webUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "text/html")
            .header("User-Agent", ctx.userAgent)
            .build()

        val apiFuture = ctx.client.sendAsync(apiRequest, HttpResponse.BodyHandlers.ofString())
            .thenApply { if (it.statusCode() == 200) it.body() else null }
            .exceptionally { null }

        val webFuture = ctx.client.sendAsync(webRequest, HttpResponse.BodyHandlers.ofString())
            .thenApply { if (it.statusCode() == 200) it.body() else null }
            .exceptionally { null }

        return CompletableFuture.allOf(apiFuture, webFuture).thenApply {
            val apiJson = apiFuture.get()
            if (apiJson == null) null
            else LibrariesIoRaw(apiJson, webFuture.get())
        }
    }

    fun parse(raw: LibrariesIoRaw): LibrariesIoData? {
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
            sourcerankBreakdown = breakdown
        )
    }
}

data class LibrariesIoRaw(val apiJson: String, val webHtml: String?)
