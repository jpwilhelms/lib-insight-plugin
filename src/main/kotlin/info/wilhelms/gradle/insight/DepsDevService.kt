package info.wilhelms.gradle.insight

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DepsDevService {

    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()

    fun fetchRaw(group: String, name: String, version: String): DepsDevRaw? {
        try {
            val packageName = URLEncoder.encode("$group:$name", StandardCharsets.UTF_8)
            val pkgJson = fetchUrl("https://api.deps.dev/v3alpha/systems/maven/packages/$packageName") ?: return null
            
            val encodedVersion = URLEncoder.encode(version, StandardCharsets.UTF_8)
            val verJson = fetchUrl("https://api.deps.dev/v3alpha/systems/maven/packages/$packageName/versions/$encodedVersion") ?: return null
            
            var projectJson: String? = null
            val vJson = JsonParser.parseString(verJson).asJsonObject
            vJson.getAsJsonArray("relatedProjects")?.firstOrNull()?.let {
                val projectKey = it.asJsonObject.get("projectKey")?.asJsonObject?.get("id")?.asString
                if (projectKey != null) {
                    projectJson = fetchUrl("https://api.deps.dev/v3alpha/projects/${URLEncoder.encode(projectKey, StandardCharsets.UTF_8)}")
                }
            }

            return DepsDevRaw(pkgJson, verJson, projectJson)
        } catch (e: Exception) { return null }
    }

    private fun fetchUrl(url: String): String? {
        try {
            val request = HttpRequest.newBuilder().uri(URI.create(url)).header("Accept", "application/json").build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return if (response.statusCode() == 200) response.body() else null
        } catch (e: Exception) { return null }
    }

    fun parse(raw: DepsDevRaw): DepsDevInfo? {
        try {
            val pkgJson = JsonParser.parseString(raw.packageJson).asJsonObject
            val dependentCount = pkgJson.get("dependentCount")?.asInt ?: 0
            
            val verJson = JsonParser.parseString(raw.versionJson).asJsonObject
            val advisories = verJson.getAsJsonArray("advisoryKeys")?.size() ?: 0
            
            var scorecard: Map<String, Int>? = null
            raw.projectJson?.let {
                val pJson = JsonParser.parseString(it).asJsonObject
                val sc = pJson.getAsJsonObject("scorecard")
                if (sc != null) {
                    scorecard = mutableMapOf()
                    sc.getAsJsonArray("checks")?.forEach { checkElem ->
                        val check = checkElem.asJsonObject
                        val name = check.get("name")?.asString
                        val score = check.get("score")?.asInt
                        if (name != null && score != null && score >= 0) {
                            scorecard!![name] = score
                        }
                    }
                }
            }

            return DepsDevInfo(
                dependentsCount = if (dependentCount > 0) dependentCount else null,
                advisoriesCount = advisories,
                scorecard = scorecard
            )
        } catch (e: Exception) { return null }
    }
}

data class DepsDevRaw(val packageJson: String, val versionJson: String, val projectJson: String?)

data class DepsDevInfo(
    val dependentsCount: Int?, val advisoriesCount: Int, val scorecard: Map<String, Int>? = null
)
