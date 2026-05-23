package info.wilhelms.gradle.insight

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MavenCentralService {

    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    fun fetchRaw(group: String, name: String): String? {
        try {
            val url = "https://search.maven.org/solrsearch/select?q=g:\"$group\"+AND+a:\"$name\"&core=gav&rows=100&wt=json"
            val request = HttpRequest.newBuilder().uri(URI.create(url)).header("Accept", "application/json").build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return if (response.statusCode() == 200) response.body() else null
        } catch (e: Exception) { return null }
    }

    fun parse(rawJson: String, version: String): MavenMetadata? {
        try {
            val json = JsonParser.parseString(rawJson).asJsonObject
            val docs = json.getAsJsonObject("response").getAsJsonArray("docs")
            var currentVersionDate: String? = null
            var latestVersion: String? = null
            var latestDate: Long = 0

            docs.forEach { element ->
                val doc = element.asJsonObject
                val v = doc.get("v").asString
                val timestamp = doc.get("timestamp").asLong
                if (v == version) currentVersionDate = formatter.format(Instant.ofEpochMilli(timestamp))
                if (timestamp > latestDate) {
                    latestDate = timestamp
                    latestVersion = v
                }
            }
            return MavenMetadata(currentVersionDate, latestVersion, formatter.format(Instant.ofEpochMilli(latestDate)))
        } catch (e: Exception) { return null }
    }
}

data class MavenMetadata(
    val releaseDate: String?,
    val latestVersion: String?,
    val latestReleaseDate: String?
)
