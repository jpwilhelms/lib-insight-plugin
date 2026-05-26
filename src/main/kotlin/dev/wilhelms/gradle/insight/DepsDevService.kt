package dev.wilhelms.gradle.insight

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
import java.time.Duration
import java.util.concurrent.CompletableFuture

class DepsDevService(private val ctx: ServiceContext) {

    private fun fetchUrlAsync(url: String): CompletableFuture<String?> {
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

    fun fetchRawAsync(group: String, name: String, version: String): CompletableFuture<DepsDevRaw?> {
        val encodedPkg = "${URLEncoder.encode(group, StandardCharsets.UTF_8)}%3A${URLEncoder.encode(name, StandardCharsets.UTF_8)}"
        val encodedVer = URLEncoder.encode(version, StandardCharsets.UTF_8)
        
        val pkgFuture = fetchUrlAsync("https://api.deps.dev/v3alpha/systems/maven/packages/$encodedPkg")
        val verFuture = fetchUrlAsync("https://api.deps.dev/v3alpha/systems/maven/packages/$encodedPkg/versions/$encodedVer")
        
        return CompletableFuture.allOf(pkgFuture, verFuture).thenCompose {
            val pkgJson = pkgFuture.get()
            val verJson = verFuture.get()
            
            if (pkgJson == null || verJson == null) {
                CompletableFuture.completedFuture(null)
            } else {
                // Secondary fetch for project info if available
                var projectFuture = CompletableFuture.completedFuture<String?>(null)
                val vElement = JsonParser.parseString(verJson)
                if (vElement.isJsonObject) {
                    vElement.asJsonObject.getAsJsonArray("relatedProjects")?.firstOrNull()?.let {
                        val projectKey = it.asJsonObject.get("projectKey")?.asJsonObject?.get("id")?.asString
                        if (projectKey != null) {
                            projectFuture = fetchUrlAsync("https://api.deps.dev/v3alpha/projects/${URLEncoder.encode(projectKey, StandardCharsets.UTF_8)}")
                        }
                    }
                }
                projectFuture.thenApply { projJson -> DepsDevRaw(pkgJson, verJson, projJson) }
            }
        }
    }

    fun parse(raw: DepsDevRaw): DepsDevData? {
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
    }
}

data class DepsDevRaw(val packageJson: String, val versionJson: String, val projectJson: String?)
