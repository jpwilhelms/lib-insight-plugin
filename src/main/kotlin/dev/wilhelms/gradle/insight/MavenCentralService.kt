package dev.wilhelms.gradle.insight

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.Duration
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class MavenCentralService(private val ctx: ServiceContext) {

    private val formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    fun fetchRawAsync(group: String, name: String): CompletableFuture<String?> {
        val query = "g:\"$group\" AND a:\"$name\""
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "https://search.maven.org/solrsearch/select?q=$encodedQuery&core=gav&rows=100&wt=json"
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", ctx.userAgent)
            .build()
        
        return ctx.client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { if (it.statusCode() == 200) it.body() else null }
            .exceptionally { null }
    }

    fun parse(jsonStr: String, currentVersion: String): MavenCentralData? {
        try {
            val element = JsonParser.parseString(jsonStr)
            if (!element.isJsonObject) return null
            val response = element.asJsonObject.getAsJsonObject("response")
            val docs = response.getAsJsonArray("docs")
            
            var currentVersionDate: Long = 0
            var latestDate: Long = 0
            var latestVersion: String? = null

            docs.forEach { docElem ->
                val doc = docElem.asJsonObject
                val v = doc.get("v").asString
                val t = doc.get("timestamp").asLong
                
                if (v == currentVersion) {
                    currentVersionDate = t
                }
                if (t > latestDate) {
                    latestDate = t
                    latestVersion = v
                }
            }

            return MavenCentralData(
                currentVersionReleaseDate = if (currentVersionDate > 0) formatter.format(Instant.ofEpochMilli(currentVersionDate)) else null,
                latestVersion = latestVersion,
                latestVersionReleaseDate = if (latestDate > 0) formatter.format(Instant.ofEpochMilli(latestDate)) else null,
                releaseCount = docs.size()
            )
        } catch (e: Exception) { return null }
    }
}
