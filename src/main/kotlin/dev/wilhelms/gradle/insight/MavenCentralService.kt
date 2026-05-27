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
import java.math.BigInteger

class MavenCentralService(private val ctx: ServiceContext) {

    private val formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    fun fetchRawAsync(group: String, name: String): CompletableFuture<String?> {
        val query = "g:\"$group\" AND a:\"$name\""
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "https://search.maven.org/solrsearch/select?q=$encodedQuery&core=gav&rows=100&wt=json"
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(ctx.requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", ctx.userAgent)
            .build()
        
        return ctx.client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { if (it.statusCode() == 200) it.body() else null }
            .exceptionally { null }
    }

    fun parse(jsonStr: String, currentVersion: String): MavenCentralData? {
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
            if (latestVersion == null || compareVersions(v, latestVersion!!) > 0) {
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
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftTokens = tokenizeVersion(left)
        val rightTokens = tokenizeVersion(right)
        val maxSize = maxOf(leftTokens.size, rightTokens.size)

        for (index in 0 until maxSize) {
            val leftToken = leftTokens.getOrNull(index)
            val rightToken = rightTokens.getOrNull(index)

            when {
                leftToken == null && rightToken == null -> return 0
                leftToken == null -> return if (rightToken!!.isQualifier) 1 else -1
                rightToken == null -> return if (leftToken.isQualifier) -1 else 1
                leftToken.isNumber && rightToken.isNumber -> {
                    val numberCompare = leftToken.number!!.compareTo(rightToken.number!!)
                    if (numberCompare != 0) return numberCompare
                }
                leftToken.isNumber != rightToken.isNumber -> {
                    return if (leftToken.isNumber) 1 else -1
                }
                else -> {
                    val textCompare = leftToken.text.compareTo(rightToken.text, ignoreCase = true)
                    if (textCompare != 0) return textCompare
                }
            }
        }

        return 0
    }

    private fun tokenizeVersion(version: String): List<VersionToken> {
        return Regex("[A-Za-z]+|\\d+").findAll(version).map { match ->
            val value = match.value
            if (value.all(Char::isDigit)) {
                VersionToken(number = BigInteger(value), text = value, isNumber = true, isQualifier = false)
            } else {
                VersionToken(number = null, text = value, isNumber = false, isQualifier = true)
            }
        }.toList()
    }

    private data class VersionToken(
        val number: BigInteger?,
        val text: String,
        val isNumber: Boolean,
        val isQualifier: Boolean
    )
}
