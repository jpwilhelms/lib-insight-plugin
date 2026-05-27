package dev.wilhelms.gradle.insight

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class HtmlReportGenerator(private val pluginVersion: String) {

    fun generate(reportItems: List<ReportItem>, outputFile: File) {
        val criticalItems = reportItems.filter { item -> item.findings.any { it.level == "ERROR" } }
        val warningItems = reportItems.filter { item -> item.findings.none { it.level == "ERROR" } && item.findings.any { it.level == "WARN" } }
        val infoItems = reportItems.filter { item -> item.findings.none { it.level == "ERROR" } && item.findings.none { it.level == "WARN" } && item.findings.any { it.level == "INFO" } }

        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Library Insight Report</title>
                <style>
                    :root {
                        --primary-color: #2c3e50;
                        --error-color: #e74c3c;
                        --warning-color: #f39c12;
                        --info-color: #3498db;
                        --bg-color: #f4f7f6;
                        --card-bg: #ffffff;
                    }
                    body {
                        font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                        line-height: 1.6;
                        color: var(--primary-color);
                        background-color: var(--bg-color);
                        margin: 0;
                        padding: 20px;
                        scroll-behavior: smooth;
                    }
                    .container { max-width: 1200px; margin: 0 auto; }
                    header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 30px;
                        border-bottom: 2px solid #ddd;
                        padding-bottom: 10px;
                    }
                    nav {
                        background: var(--primary-color);
                        padding: 10px 20px;
                        border-radius: 8px;
                        margin-bottom: 30px;
                        display: flex;
                        gap: 20px;
                    }
                    nav a { color: white; text-decoration: none; font-weight: bold; font-size: 0.9em; }
                    nav a:hover { text-decoration: underline; }

                    .section-header {
                        margin: 40px 0 20px;
                        padding: 10px 15px;
                        border-radius: 6px;
                        color: white;
                    }
                    .bg-error { background-color: var(--error-color); }
                    .bg-warning { background-color: var(--warning-color); }
                    .bg-info { background-color: var(--info-color); }

                    .dependency-card {
                        background: var(--card-bg);
                        border-radius: 8px;
                        margin-bottom: 15px;
                        box-shadow: 0 2px 5px rgba(0,0,0,0.05);
                        border-left: 5px solid #bdc3c7;
                    }
                    .dependency-card.level-ERROR { border-left-color: var(--error-color); }
                    .dependency-card.level-WARN { border-left-color: var(--warning-color); }
                    .dependency-card.level-INFO { border-left-color: var(--info-color); }

                    .card-header {
                        padding: 12px 20px;
                        background: #f8f9fa;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }
                    .card-header h3 { margin: 0; font-size: 1.1em; }
                    .card-body { padding: 15px 20px; }
                    
                    .finding {
                        margin-top: 8px;
                        padding: 8px 12px;
                        border-radius: 4px;
                        font-size: 0.9em;
                        display: flex;
                        gap: 10px;
                    }
                    .finding-type { font-weight: bold; min-width: 120px; }
                    .finding-ERROR { background: #fdeaea; color: #8e1c1c; border-left: 3px solid var(--error-color); }
                    .finding-WARN { background: #fff4e5; color: #855d00; border-left: 3px solid var(--warning-color); }
                    .finding-INFO { background: #eef2f7; color: #2c3e50; border-left: 3px solid var(--info-color); }

                    .metadata { display: flex; gap: 15px; font-size: 0.8em; color: #7f8c8d; }
                    .metadata { flex-wrap: wrap; align-items: center; justify-content: flex-end; }
                    .tag { padding: 2px 6px; border-radius: 10px; background: #eee; }
                    .source-links { display: inline-flex; gap: 8px; flex-wrap: wrap; align-items: center; }
                    .source-badge {
                        display: inline-flex;
                        align-items: center;
                        gap: 6px;
                        padding: 4px 10px;
                        border-radius: 999px;
                        font-size: 0.76em;
                        font-weight: 700;
                        text-decoration: none;
                        color: #2c3e50;
                        background: linear-gradient(180deg, #ffffff 0%, #eef3f8 100%);
                        border: 1px solid #d7dfe7;
                        box-shadow: 0 1px 2px rgba(15, 23, 42, 0.05);
                        transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease;
                    }
                    .source-badge:hover {
                        transform: translateY(-1px);
                        box-shadow: 0 3px 8px rgba(15, 23, 42, 0.09);
                        border-color: #c7d0da;
                    }
                    .source-badge__icon {
                        width: 16px;
                        height: 16px;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                    }
                    .source-badge__icon svg {
                        width: 16px;
                        height: 16px;
                        display: block;
                        fill: none;
                        stroke: currentColor;
                        stroke-width: 1.8;
                        stroke-linecap: round;
                        stroke-linejoin: round;
                    }
                    .source-badge__text { white-space: nowrap; }
                    .source-central { color: #8e44ad; }
                    .source-github { color: #24292f; }
                    .source-depsdev { color: #0b6efd; }
                    .source-librariesio { color: #0f7b6c; }
                    footer { text-align: center; margin-top: 50px; font-size: 0.8em; color: #95a5a6; }
                </style>
            </head>
            <body>
                <div class="container">
                    <header>
                        <h1>Library Insight Report</h1>
                        <div>Generated: ${java.time.Instant.now().toString().substring(0, 19).replace('T', ' ')} UTC</div>
                    </header>

                    <nav>
                        ${if (criticalItems.isNotEmpty()) "<a href='#errors'>Critical Issues (${criticalItems.size})</a>" else ""}
                        ${if (warningItems.isNotEmpty()) "<a href='#warnings'>Warnings (${warningItems.size})</a>" else ""}
                        ${if (infoItems.isNotEmpty()) "<a href='#info'>Information (${infoItems.size})</a>" else ""}
                    </nav>

                    ${renderSection("Critical Issues", "errors", "bg-error", "ERROR", criticalItems)}
                    ${renderSection("Warnings", "warnings", "bg-warning", "WARN", warningItems)}
                    ${renderSection("Information", "info", "bg-info", "INFO", infoItems)}

                    <footer>
                        Analyzed by Library Insight Gradle Plugin v${escapeHtml(pluginVersion)}<br>
                        <a href="https://github.com/jpwilhelms/lib-insight-plugin" style="color: inherit;">GitHub Repository</a>
                    </footer>
                </div>
            </body>
            </html>
        """.trimIndent()

        outputFile.writeText(html)
    }

    private fun renderSection(title: String, anchor: String, cssClass: String, level: String, items: List<ReportItem>): String {
        if (items.isEmpty()) return ""
        
        return """
            <h2 id="${escapeHtml(anchor)}" class="section-header ${escapeHtml(cssClass)}">${escapeHtml(title)}</h2>
            ${items.joinToString("\n") { item ->
                """
                <div class="dependency-card level-${escapeHtml(level)}">
                    <div class="card-header">
                        <h3>${escapeHtml(item.metric.id)}</h3>
                        <div class="metadata">
                            <span>Version: <strong>${escapeHtml(item.metric.version)}</strong></span>
                            ${if (item.metric.isDirect) "<span class='tag'>Direct</span>" else "<span class='tag'>Transitive</span>"}
                            ${renderSourceLinks(item.metric)}
                        </div>
                    </div>
                    <div class="card-body">
                        ${item.findings.joinToString("\n") { finding ->
                            """
                            <div class="finding finding-${escapeHtml(finding.level)}">
                                <span class="finding-type">[${escapeHtml(finding.type)}]</span>
                                <span class="finding-message">${escapeHtml(finding.message)}</span>
                            </div>
                            """
                        }}
                    </div>
                </div>
                """
            }}
        """
    }

    private fun renderSourceLinks(metric: LibMetric): String {
        val links = buildList {
            add(sourceLink("Maven Central", "Maven Central POM", metric.pom.url, "source-badge source-central", centralIconSvg()))
            metric.pom.scmUrl?.let { scmUrl ->
                normalizeGithubUrl(scmUrl)?.let { add(sourceLink("GitHub", "GitHub", it, "source-badge source-github", githubIconSvg())) }
            }
            add(sourceLink("deps.dev", "deps.dev", depsDevUrl(metric), "source-badge source-depsdev", depsDevIconSvg()))
            add(sourceLink("Libraries.io", "Libraries.io", librariesIoUrl(metric), "source-badge source-librariesio", librariesIoIconSvg()))
        }.filterNotNull()

        if (links.isEmpty()) return ""
        return """<span class="source-links">${links.joinToString("")}</span>"""
    }

    private fun sourceLink(label: String, title: String, url: String?, cssClass: String, iconSvg: String): String? {
        if (url.isNullOrBlank()) return null
        return """<a class="${escapeHtml(cssClass)}" href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer" title="${escapeHtml(title)}" aria-label="${escapeHtml(title)}"><span class="source-badge__icon">$iconSvg</span><span class="source-badge__text">${escapeHtml(label)}</span></a>"""
    }

    private fun depsDevUrl(metric: LibMetric): String {
        val encoded = URLEncoder.encode(metric.id, StandardCharsets.UTF_8)
        return "https://deps.dev/maven/$encoded"
    }

    private fun librariesIoUrl(metric: LibMetric): String {
        val encoded = URLEncoder.encode(metric.id, StandardCharsets.UTF_8)
        return "https://libraries.io/maven/$encoded"
    }

    private fun normalizeGithubUrl(scmUrl: String): String? {
        var url = scmUrl.trim()
        if (url.isBlank()) return null

        url = url
            .replace("git@github.com:", "https://github.com/")
            .replace("git://github.com/", "https://github.com/")
            .replace("https://github.com/", "https://github.com/")
            .replace("http://github.com/", "https://github.com/")
            .replace("git+https://github.com/", "https://github.com/")
            .replace("git+http://github.com/", "https://github.com/")

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        return url.removeSuffix(".git")
    }

    private fun centralIconSvg(): String = """
        <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 3 4.5 7v10L12 21l7.5-4V7L12 3z" />
            <path d="M4.5 7 12 11l7.5-4" />
            <path d="M12 11v10" />
        </svg>
    """.trimIndent()

    private fun githubIconSvg(): String = """
        <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M8 7v10" />
            <path d="M8 7a2 2 0 1 1 0-4 2 2 0 0 1 0 4z" />
            <path d="M8 17a2 2 0 1 1 0 4 2 2 0 0 1 0-4z" />
            <path d="M8 9c6 0 7 5 10 8" />
            <path d="M18 17a2 2 0 1 1 0 4 2 2 0 0 1 0-4z" />
        </svg>
    """.trimIndent()

    private fun depsDevIconSvg(): String = """
        <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="6" cy="6" r="2" />
            <circle cx="18" cy="6" r="2" />
            <circle cx="12" cy="18" r="2" />
            <path d="M7.7 7.3 10.5 10" />
            <path d="M16.3 7.3 13.5 10" />
            <path d="M12 16V10" />
        </svg>
    """.trimIndent()

    private fun librariesIoIconSvg(): String = """
        <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 19V9" />
            <path d="M10 19V5" />
            <path d="M15 19v-8" />
            <path d="M20 19V11" />
        </svg>
    """.trimIndent()

    private fun escapeHtml(input: String): String {
        return buildString(input.length) {
            input.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }
    }
}
