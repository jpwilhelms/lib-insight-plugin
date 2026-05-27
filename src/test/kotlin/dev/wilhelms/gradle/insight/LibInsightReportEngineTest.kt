package dev.wilhelms.gradle.insight

import kotlin.test.Test
import kotlin.test.assertTrue

class LibInsightReportEngineTest {
    @Test
    fun `null-returning filter is treated as false`() {
        val metric = LibMetric(
            id = "com.example:demo",
            version = "1.0.0",
            gradleInsight = "{}",
            isDirect = true,
            suppressions = emptyList(),
            pom = PomInfo(url = "", license = null, scmUrl = null),
            mavenCentral = null,
            github = null,
            depsDev = null,
            librariesIo = null,
            cachedAt = "2024-01-01T00:00:00Z"
        )

        val audit = CustomAuditInfo(
            name = "outdated",
            level = "WARN",
            console = true,
            filter = SerializablePredicate { it.mavenCentral?.isOlderThanLatest(365) },
            formatter = SerializableFunction { "should not be emitted" }
        )

        val reportItems = LibInsightReportEngine.evaluate(listOf(metric), listOf(audit))

        assertTrue(reportItems.isEmpty(), "Null filter results should not create findings")
    }
}
