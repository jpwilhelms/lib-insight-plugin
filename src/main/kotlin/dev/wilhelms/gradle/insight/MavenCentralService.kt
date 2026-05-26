package dev.wilhelms.gradle.insight

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.Duration

class MavenCentralService(private val ctx: ServiceContext) {

    private val formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    fun fetchRaw(group: String, name: String): String? {
        try {
            val url = "https://search.maven.org/solrsearch/select?q=g:\"$group\"+AND+a:\"$name\"&core=gav&rows=100&wt=json"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", ctx.userAgent)
                .build()
            val response = ctx.client.send(request, HttpResponse.BodyHandlers.ofString())
            return if (response.statusCode() == 200) response.body() else null
        } catch (e: Exception) { return null }
    }

    fun parse(rawJson: String, version: String): MavenCentralData? {
        try {
            val element = JsonParser.parseString(rawJson)
            if (!element.isJsonObject) return null
            val json = element.asJsonObject
            val docs = json.getAsJsonObject("response").getAsJsonArray("docs")
            var currentVersionDate: String? = null
            var latestVersion: String? = null
            var latestDate: Long = 0

            docs.forEach { docElement ->
                if (docElement.isJsonObject) {
                    val doc = docElement.asJsonObject
                    val v = doc.get("v").safeString()
                    val timestamp = doc.get("timestamp").safeLong()
                    if (v != null && timestamp != null) {
                        if (v == version) currentVersionDate = formatter.format(Instant.ofEpochMilli(timestamp))
                        if (timestamp > latestDate) {
                            latestDate = timestamp
                            latestVersion = v
                        }
                    }
                }
            }
            return MavenCentralData(
                currentVersionReleaseDate = currentVersionDate,
                latestVersion = latestVersion,
                latestVersionReleaseDate = if (latestDate > 0) formatter.format(Instant.ofEpochMilli(latestDate)) else null,
                releaseCount = docs.size()
            )
        } catch (e: Exception) { return null }
    }
}
