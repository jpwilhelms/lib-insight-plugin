package dev.wilhelms.gradle.insight

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.io.File

class LibInsightPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("libInsight", LibInsightExtension::class.java)
        
        // Set conventions from environment variables and defaults
        extension.gitHubToken.convention(project.providers.environmentVariable("GH_TOKEN"))
        extension.librariesIoToken.convention(project.providers.environmentVariable("LIBRARIES_IO_TOKEN"))
        extension.htmlReport.convention(true)
        extension.jsonReport.convention(true)
        extension.autoCheck.convention(false)
        extension.maxParallelDownloads.convention(10)

        // Set cacheDir convention from environment variable or default to user home
        val envCacheDirVar = project.providers.environmentVariable("LIB_INSIGHT_CACHE_DIR")
        extension.cacheDir.convention(envCacheDirVar.map { project.layout.projectDirectory.dir(it) }
            .orElse(project.providers.provider {
                val home = System.getProperty("user.home")
                project.layout.projectDirectory.dir("$home/.gradle/lib-insight-cache")
            }))

        // Stage 1: Data Collection (Internal)
        val collectTask = project.tasks.register("libInsight", LibInsightTask::class.java, object : Action<LibInsightTask> {
            override fun execute(task: LibInsightTask) {
                task.description = "Analyzes all project dependencies and collects metrics."
                task.gitHubToken.set(extension.gitHubToken)
                task.librariesIoToken.set(extension.librariesIoToken)
                task.maxParallel.set(extension.maxParallelDownloads)
                task.dataDir.set(project.layout.buildDirectory.dir("lib-insight/data"))
                task.cacheDir.set(extension.cacheDir)
                task.cacheTtlDays.set(extension.cacheTtlDays.convention(1))
                task.suppressionFile.set(extension.suppressionFile)
                task.forceNativeProgress.set(project.providers.systemProperty("progress.force").map { it.toBoolean() }.orElse(false))

                task.dependencyData.set(project.provider {
                    val dataMap = mutableMapOf<String, DependencyProvenance>()
                    project.configurations.findByName("runtimeClasspath")?.let { config ->
                        if (config.isCanBeResolved) {
                            val rootId = config.incoming.resolutionResult.root.id
                            config.incoming.resolutionResult.allComponents.forEach { component ->
                                val id = component.id
                                if (id is ModuleComponentIdentifier) {
                                    val gav = "${id.group}:${id.module}:${id.version}"
                                    val isDirect = component.dependents.any { it.from.id == rootId }
                                    dataMap[gav] = DependencyProvenance(isDirect)
                                }
                            }
                        }
                    }
                    dataMap
                })
            }
        })

        // Stage 2: Exception Reporting
        val reportTask = project.tasks.register("libInsightReport", LibInsightReportTask::class.java, object : Action<LibInsightReportTask> {
            override fun execute(task: LibInsightReportTask) {
                task.group = "insight"
                task.description = "Generates finding-based HTML and JSON reports."
                task.dataFile.set(collectTask.flatMap { it.dataDir.file("lib-insight.json") })
                task.reportDir.set(project.layout.buildDirectory.dir("reports/lib-insight"))

                task.customAudits.set(project.provider {
                    extension.customAudits.filter { it.enabled.getOrElse(true) }.map { config ->
                        CustomAuditInfo(config.name, config.level.getOrElse("ERROR"), config.console.getOrElse(true), config.filter.get(), config.formatter.get())
                    }
                })
                
                task.auditFingerprint.set(project.provider {
                    val audits = extension.customAudits.filter { it.enabled.getOrElse(true) }
                    audits.joinToString(";") { 
                        "${it.name}:${it.level.get()}:${it.console.get()}:${it.description.getOrElse("")}"
                    }
                })
            }
        })

        // Lifecycle task (Governance)
        project.tasks.register("libInsightCheck", object : Action<Task> {
            override fun execute(task: Task) {
                task.group = "insight"
                task.description = "Runs analysis and checks findings."
                task.dependsOn(collectTask)
                task.dependsOn(reportTask)
                
                task.doLast {
                    val reportFile = project.layout.buildDirectory.dir("reports/lib-insight/report.json").get().asFile
                    if (reportFile.exists()) {
                        val gson = com.google.gson.Gson()
                        val type = object : com.google.gson.reflect.TypeToken<List<ReportItem>>() {}.type
                        val items: List<ReportItem> = gson.fromJson(reportFile.readText(), type)
                        
                        if (items.isNotEmpty()) {
                            // FAIL ONLY if any finding has level == "ERROR"
                            val hasCritical = items.any { item -> item.findings.any { f -> f.level == "ERROR" } }
                            if (hasCritical) {
                                throw org.gradle.api.GradleException("Library Insight found CRITICAL issues. Check the report at: ${reportFile.parentFile.absolutePath}/index.html")
                            }
                        }
                    }
                }
            }
        })

        // Initialize custom audits conventions
        extension.customAudits.all(object : Action<CustomAuditConfiguration> {
            override fun execute(config: CustomAuditConfiguration) {
                config.enabled.convention(true)
                config.console.convention(true)
                config.level.convention("ERROR")
            }
        })
    }
}
