package dev.wilhelms.gradle.insight

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
import java.time.Duration

class DepsDevService(private val ctx: ServiceContext) {

    private fun fetchUrl(url: String): String? {
        try {
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

    fun fetchRaw(group: String, name: String, version: String): DepsDevRaw? {
        try {
            val pkgJson = fetchUrl("https://api.deps.dev/v3alpha/systems/maven/packages/${URLEncoder.encode(group, StandardCharsets.UTF_8)}%3A${URLEncoder.encode(name, StandardCharsets.UTF_8)}") ?: return null
            
            val encodedVersion = URLEncoder.encode(version, StandardCharsets.UTF_8)
            val verJson = fetchUrl("https://api.deps.dev/v3alpha/systems/maven/packages/${URLEncoder.encode(group, StandardCharsets.UTF_8)}%3A${URLEncoder.encode(name, StandardCharsets.UTF_8)}/versions/$encodedVersion") ?: return null

            var projectJson: String? = null
            val vElement = JsonParser.parseString(verJson)
            if (vElement.isJsonObject) {
                val vJson = vElement.asJsonObject
                vJson.getAsJsonArray("relatedProjects")?.firstOrNull()?.let {
                    val projectKey = it.asJsonObject.get("projectKey")?.asJsonObject?.get("id")?.asString
                    if (projectKey != null) {
                        projectJson = fetchUrl("https://api.deps.dev/v3alpha/projects/${URLEncoder.encode(projectKey, StandardCharsets.UTF_8)}")
                    }
                }
            }

            return DepsDevRaw(pkgJson, verJson, projectJson)
        } catch (e: Exception) { return null }
    }

    fun parse(raw: DepsDevRaw): DepsDevData? {
        try {
            val pkgElement = JsonParser.parseString(raw.packageJson)
            if (!pkgElement.isJsonObject) return null
            val pkgJson = pkgElement.asJsonObject
            val dependentCount = pkgJson.get("dependentCount").safeInt() ?: 0
            
            val verElement = JsonParser.parseString(raw.versionJson)
            if (!verElement.isJsonObject) return null
            val verJson = verElement.asJsonObject
            val advisories = verJson.getAsJsonArray("advisoryKeys")?.size() ?: 0
            
            var scorecard: OpenSsfScorecard? = null
            raw.projectJson?.let {
                val pElement = JsonParser.parseString(it)
                if (pElement.isJsonObject) {
                    val pJson = pElement.asJsonObject
                    val sc = pJson.getAsJsonObject("scorecard")
                    if (sc != null) {
                        val checks = mutableMapOf<String, Int>()
                        sc.getAsJsonArray("checks")?.forEach { checkElem ->
                            if (checkElem.isJsonObject) {
                                val check = checkElem.asJsonObject
                                val name = check.get("name").safeString()
                                val score = check.get("score").safeInt()
                                if (name != null && score != null && score >= 0) {
                                    checks[name] = score
                                }
                            }
                        }
                        scorecard = OpenSsfScorecard(sc.get("overallScore").safeDouble() ?: 0.0, checks)
                    }
                }
            }

            return DepsDevData(
                dependentsCount = if (dependentCount > 0) dependentCount else null,
                advisoriesCount = advisories,
                scorecard = scorecard
            )
        } catch (e: Exception) { return null }
    }
}

data class DepsDevRaw(val packageJson: String, val versionJson: String, val projectJson: String?)
