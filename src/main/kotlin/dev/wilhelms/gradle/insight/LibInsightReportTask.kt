package dev.wilhelms.gradle.insight

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*

abstract class LibInsightReportTask : DefaultTask() {

    @get:InputFile
    abstract val dataFile: RegularFileProperty

    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    @get:Internal
    abstract val customAudits: ListProperty<CustomAuditInfo>

    @get:Input
    @get:Optional
    abstract val htmlReport: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val jsonReport: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val auditFingerprint: Property<String>

    @TaskAction
    fun generate() {
        val gson = Gson()
        val metrics: List<LibMetric> = gson.fromJson(dataFile.get().asFile.readText(), object : TypeToken<List<LibMetric>>() {}.type)

        val reportItems = LibInsightReportEngine.evaluate(metrics, customAudits.getOrElse(emptyList()))

        // Console Output (Granular by level and 'console' flag)
        printConsoleSection("CRITICAL FINDINGS (ERROR)", "ERROR", reportItems)
        printConsoleSection("WARNINGS", "WARN", reportItems)
        printConsoleSection("INFORMATION", "INFO", reportItems)

        // Generate JSON Findings Report
        if (jsonReport.getOrElse(true)) {
            val reportFile = reportDir.file("report.json").get().asFile
            reportFile.parentFile.mkdirs()
            reportFile.writeText(gson.toJson(reportItems))
        }

        // Generate HTML Report
        if (htmlReport.getOrElse(true)) {
            val htmlFile = reportDir.file("index.html").get().asFile
            HtmlReportGenerator(project.version.toString()).generate(reportItems, htmlFile)
        }

        println("\nReports generated in: ${reportDir.get().asFile.absolutePath}")
        if (reportItems.isNotEmpty()) {
            val totalFindings = reportItems.sumOf { it.findings.size }
            println("Found ${reportItems.size} libraries with $totalFindings potential issues.")
        }
    }

    private fun printConsoleSection(title: String, level: String, items: List<ReportItem>) {
        val relevant = items.mapNotNull { item ->
            val levelFindings = item.findings.filter { it.level == level && it.console }
            if (levelFindings.isNotEmpty()) {
                val fullCoords = "${item.metric.id}:${item.metric.version}"
                fullCoords to levelFindings
            } else null
        }

        if (relevant.isNotEmpty()) {
            println("\n==========================================================================================")
            println(" $title ")
            println("==========================================================================================")
            relevant.forEach { (coords, findings) ->
                println(coords)
                findings.forEach { finding ->
                    println("  - [${finding.type}] ${finding.message}")
                }
            }
        }
    }
}
