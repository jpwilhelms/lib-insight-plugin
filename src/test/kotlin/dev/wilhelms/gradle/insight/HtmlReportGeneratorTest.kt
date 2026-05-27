package dev.wilhelms.gradle.insight

import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File
import java.time.Instant

class HtmlReportGeneratorTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `html report escapes content and keeps mixed severity findings visible`() {
        val metric = LibMetric(
            id = """com.example:<script>alert(1)</script>""",
            version = """1.0.0 & beta""",
            gradleInsight = "findings",
            isDirect = true,
            suppressions = emptyList(),
            pom = PomInfo("https://example.com/pom.xml", null, "git@github.com:owner/repo.git"),
            mavenCentral = null,
            github = null,
            depsDev = null,
            librariesIo = null,
            cachedAt = Instant.parse("2026-05-26T00:00:00Z").toString()
        )

        val reportItems = listOf(
            ReportItem(
                metric,
                listOf(
                    Finding("warnAudit", "WARN", """Use <strong> safer """),
                    Finding("infoAudit", "INFO", """More details & guidance""")
                )
            )
        )

        val output = File(tempDir, "report.html")
        HtmlReportGenerator("0.1.0-SNAPSHOT").generate(reportItems, output)
        val html = output.readText()

        assertTrue(html.contains("com.example:&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("1.0.0 &amp; beta"))
        assertTrue(html.contains("Use &lt;strong&gt; safer"))
        assertTrue(html.contains("More details &amp; guidance"))
        assertTrue(html.contains("""href="https://example.com/pom.xml""""))
        assertTrue(html.contains("""href="https://github.com/owner/repo""""))
        assertTrue(html.contains("""href="https://deps.dev/maven/com.example%3A%3Cscript%3Ealert%281%29%3C%2Fscript%3E""""))
        assertTrue(html.contains("""href="https://libraries.io/maven/com.example%3A%3Cscript%3Ealert%281%29%3C%2Fscript%3E""""))
        assertTrue(html.contains("source-badge"))
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("POM"))
        assertTrue(html.contains("GitHub"))
        assertTrue(html.contains("deps.dev"))
        assertTrue(html.contains("Libraries.io"))
    }
}
