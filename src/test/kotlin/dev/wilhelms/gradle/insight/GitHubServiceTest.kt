package dev.wilhelms.gradle.insight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitHubServiceTest {
    @Test
    fun `parse uses open and closed issue counts correctly`() {
        val service = GitHubService(ServiceContext())
        val raw = GitHubRaw(
            repoJson = """
                {
                  "full_name": "owner/repo",
                  "description": "demo",
                  "stargazers_count": 12,
                  "forks_count": 3,
                  "open_issues_count": 99,
                  "fork": false,
                  "archived": false,
                  "created_at": "2024-01-01T00:00:00Z",
                  "updated_at": "2024-01-02T00:00:00Z",
                  "pushed_at": "2024-01-03T00:00:00Z"
                }
            """.trimIndent(),
            openIssuesJson = """{"total_count": 4}""",
            closedIssuesJson = """{"total_count": 6}""",
            compareJson = null
        )

        val data = service.parse(raw)
        assertNotNull(data)
        assertEquals(10, data.issues?.totalIssues)
        assertEquals(4, data.issues?.openIssues)
        assertEquals(6, data.issues?.closedIssues)
        assertEquals(0.6, data.issues?.healthRatio)
        assertEquals(4, data.repo?.openIssuesCount)
    }

    @Test
    fun `issue search urls use canonical issue qualifiers`() {
        val service = GitHubService(ServiceContext())

        assertTrue(service.issueSearchUrl("owner", "repo", "open").contains("repo:owner/repo+is:issue+is:open"))
        assertTrue(service.issueSearchUrl("owner", "repo", "closed").contains("repo:owner/repo+is:issue+is:closed"))
    }
}
