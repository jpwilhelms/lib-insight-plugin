package info.wilhelms.gradle.insight

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class GitHubService(private val token: String?) {

    private val client = HttpClient.newBuilder().build()

    fun fetchRaw(scmUrl: String?): GitHubRaw? {
        if (scmUrl == null) return null
        val (owner, repo) = extractOwnerRepo(scmUrl) ?: return null

        val repoJsonStr = fetchUrl("https://api.github.com/repos/$owner/$repo") ?: return null
        val repoJson = JsonParser.parseString(repoJsonStr).asJsonObject
        
        var compareJson: String? = null
        if (repoJson.get("fork")?.asBoolean == true) {
            val parent = repoJson.getAsJsonObject("parent")
            if (parent != null) {
                val parentOwner = parent.getAsJsonObject("owner").get("login").asString
                val parentBranch = parent.get("default_branch").asString
                val myBranch = repoJson.get("default_branch").asString
                compareJson = fetchUrl("https://api.github.com/repos/$owner/$repo/compare/$parentOwner:$parentBranch...$myBranch")
            }
        }

        val issuesJson = fetchUrl("https://api.github.com/search/issues?q=repo:$owner/$repo+type:issue")

        return GitHubRaw(repoJsonStr, issuesJson, compareJson)
    }

    private fun fetchUrl(url: String): String? {
        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github.v3+json")
            if (!token.isNullOrBlank()) requestBuilder.header("Authorization", "token $token")
            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            return if (response.statusCode() == 200) response.body() else null
        } catch (e: Exception) { return null }
    }

    fun parse(raw: GitHubRaw, scmUrl: String): GitHubInfo? {
        try {
            val json = JsonParser.parseString(raw.repoJson).asJsonObject
            
            var totalIssues = 0
            if (raw.issuesJson != null) {
                totalIssues = JsonParser.parseString(raw.issuesJson).asJsonObject.get("total_count")?.asInt ?: 0
            }

            var ahead: Int? = null
            var behind: Int? = null
            var parentName: String? = null
            
            if (raw.compareJson != null) {
                val comp = JsonParser.parseString(raw.compareJson).asJsonObject
                ahead = comp.get("ahead_by")?.asInt
                behind = comp.get("behind_by")?.asInt
            }
            
            if (json.get("fork")?.asBoolean == true) {
                parentName = json.getAsJsonObject("parent")?.get("full_name")?.asString
            }

            return GitHubInfo(
                scmUrl = scmUrl,
                stars = json.get("stargazers_count")?.asInt,
                forks = json.get("forks_count")?.asInt,
                lastCommit = json.get("pushed_at")?.asString,
                createdAt = json.get("created_at")?.asString,
                updatedAt = json.get("updated_at")?.asString,
                openIssues = json.get("open_issues_count")?.asInt,
                totalIssues = totalIssues,
                isFork = json.get("fork")?.asBoolean ?: false,
                parentRepo = parentName,
                aheadBy = ahead,
                behindBy = behind
            )
        } catch (e: Exception) { return null }
    }

    fun extractOwnerRepo(url: String): Pair<String, String>? {
        val normalizedUrl = url
            .replace("scm:git:", "")
            .replace("scm:svn:", "")
            .replace("git@github.com:", "github.com/")
            .replace("git://github.com/", "github.com/")
            .replace("https://github.com/", "github.com/")
            .replace("http://github.com/", "github.com/")
            .removeSuffix(".git")

        val githubRegex = Regex("github\\.com/([^/]+)/([^/\\s#]+)")
        val match = githubRegex.find(normalizedUrl)
        if (match != null) {
            return Pair(match.groupValues[1], match.groupValues[2])
        }

        if (normalizedUrl.contains("gitbox.apache.org")) {
            val apacheRegex = Regex("repos/asf/([^/\\s]+)")
            val aMatch = apacheRegex.find(normalizedUrl)
            if (aMatch != null) return Pair("apache", aMatch.groupValues[1])
        }
        return null
    }
}

data class GitHubRaw(val repoJson: String, val issuesJson: String?, val compareJson: String?)

data class GitHubInfo(
    val scmUrl: String, val stars: Int?, val forks: Int?, val lastCommit: String?,
    val createdAt: String?, val updatedAt: String?, val openIssues: Int?, val totalIssues: Int?,
    val isFork: Boolean = false,
    val parentRepo: String? = null,
    val aheadBy: Int? = null,
    val behindBy: Int? = null
)
