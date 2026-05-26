package dev.wilhelms.gradle.insight

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GitHubService(private val ctx: ServiceContext) {

    private fun fetchUrl(url: String): String? {
        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", ctx.userAgent)
            if (!ctx.githubToken.isNullOrBlank()) {
                requestBuilder.header("Authorization", "token ${ctx.githubToken}")
            }
            val response = ctx.client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            return if (response.statusCode() == 200) response.body() else null
        } catch (e: Exception) { 
            return null 
        }
    }

    fun parse(raw: GitHubRaw): GitHubData? {
        try {
            val element = JsonParser.parseString(raw.repoJson)
            if (!element.isJsonObject) return null
            val json = element.asJsonObject
            
            var totalIssues = 0
            if (raw.issuesJson != null) {
                val issuesElement = JsonParser.parseString(raw.issuesJson)
                if (issuesElement.isJsonObject) {
                    totalIssues = issuesElement.asJsonObject.get("total_count").safeInt() ?: 0
                }
            }

            var ahead: Int? = null
            var behind: Int? = null
            var parentName: String? = null
            if (raw.compareJson != null) {
                val compElement = JsonParser.parseString(raw.compareJson)
                if (compElement.isJsonObject) {
                    val comp = compElement.asJsonObject
                    ahead = comp.get("ahead_by").safeInt()
                    behind = comp.get("behind_by").safeInt()
                }
            }

            if (json.get("fork").safeBoolean() == true) {
                parentName = json.getAsJsonObject("parent")?.get("full_name").safeString()
            }

            val repo = GitHubRepo(
                name = json.get("full_name").safeString() ?: "",
                description = json.get("description").safeString(),
                stargazersCount = json.get("stargazers_count").safeInt() ?: 0,
                forksCount = json.get("forks_count").safeInt() ?: 0,
                openIssuesCount = json.get("open_issues_count").safeInt() ?: 0,
                isFork = json.get("fork").safeBoolean() ?: false,
                isArchived = json.get("archived").safeBoolean() ?: false,
                createdAt = json.get("created_at").safeString(),
                updatedAt = json.get("updated_at").safeString(),
                pushedAt = json.get("pushed_at").safeString(),
                license = json.get("license")?.let { if (it.isJsonObject) it.asJsonObject else null }?.get("spdx_id").safeString(),
                topics = json.get("topics")?.asJsonArray?.map { it.safeString() ?: "" } ?: emptyList(),
                parentRepo = parentName,
                aheadBy = ahead,
                behindBy = behind
            )

            val issues = GitHubIssuesStats(
                totalIssues = totalIssues,
                openIssues = repo.openIssuesCount,
                closedIssues = totalIssues - repo.openIssuesCount,
                healthRatio = if (totalIssues > 0) (totalIssues - repo.openIssuesCount).toDouble() / totalIssues else 1.0
            )

            return GitHubData(repo, issues, null)
        } catch (e: Exception) { return null }
    }

    fun fetchRaw(scmUrl: String?): GitHubRaw? {
        if (scmUrl == null) return null
        val pair = extractOwnerRepo(scmUrl) ?: return null
        val owner = pair.first
        val repo = pair.second

        val repoJsonStr = fetchUrl("https://api.github.com/repos/$owner/$repo") ?: return null
        val repoElement = JsonParser.parseString(repoJsonStr)
        if (!repoElement.isJsonObject) return null
        val repoJson = repoElement.asJsonObject
        
        var compareJson: String? = null
        if (repoJson.get("fork").safeBoolean() == true) {
            val parent = repoJson.getAsJsonObject("parent")
            if (parent != null) {
                val parentOwner = parent.getAsJsonObject("owner")?.get("login").safeString()
                val parentBranch = parent.get("default_branch").safeString()
                val myBranch = repoJson.get("default_branch").safeString()
                if (parentOwner != null && parentBranch != null && myBranch != null) {
                    compareJson = fetchUrl("https://api.github.com/repos/$owner/$repo/compare/$parentOwner:$parentBranch...$myBranch")
                }
            }
        }

        val issuesJson = fetchUrl("https://api.github.com/search/issues?q=repo:$owner/$repo+type:issue")

        return GitHubRaw(repoJsonStr, issuesJson, compareJson)
    }

    fun extractOwnerRepo(url: String): Pair<String, String>? {
        val normalizedUrl = url.trim()
            .replace("scm:git:", "")
            .replace("scm:svn:", "")
            .replace("git@github.com:", "github.com/")
            .replace("git://github.com/", "github.com/")
            .replace("https://github.com/", "github.com/")
            .replace("http://github.com/", "github.com/")
            .replace("git+https://github.com/", "github.com/")
            .replace("git+http://github.com/", "github.com/")
            .removeSuffix(".git")
            .removeSuffix("/")

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
