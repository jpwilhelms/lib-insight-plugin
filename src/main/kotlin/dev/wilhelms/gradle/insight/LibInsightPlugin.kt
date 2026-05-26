package dev.wilhelms.gradle.insight

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

class LibInsightPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("libInsight", LibInsightExtension::class.java)
        
        // Set conventions from environment variables and defaults
        extension.gitHubToken.convention(project.providers.environmentVariable("GH_TOKEN"))
        extension.librariesIoToken.convention(project.providers.environmentVariable("LIBRARIES_IO_TOKEN"))
        extension.htmlReport.convention(true)
        extension.jsonReport.convention(true)
        extension.maxParallelDownloads.convention(10)
        extension.asyncTimeoutMinutes.convention(30)

        // Set cacheDir convention from environment variable or default to user home
        val envCacheDirVar = project.providers.environmentVariable("LIB_INSIGHT_CACHE_DIR")
        extension.cacheDir.convention(envCacheDirVar.map { project.layout.projectDirectory.dir(it) }
            .orElse(project.providers.provider {
                val home = System.getProperty("user.home")
                project.layout.projectDirectory.dir("$home/.gradle/lib-insight-cache")
            }))

        // Stage 1: Data Collection (Internal)
        val collectTask = project.tasks.register("libInsight", LibInsightTask::class.java) {
            description = "Analyzes all project dependencies and collects metrics."
            gitHubToken.set(extension.gitHubToken)
            librariesIoToken.set(extension.librariesIoToken)
            maxParallel.set(extension.maxParallelDownloads)
            timeoutMinutes.set(extension.asyncTimeoutMinutes)
            dataDir.set(project.layout.buildDirectory.dir("lib-insight/data"))
            cacheDir.set(extension.cacheDir)
            cacheTtlDays.set(extension.cacheTtlDays.convention(1))
            suppressionFile.set(extension.suppressionFile)
            
            // Track build files for reliable incremental builds ONLY if they exist
            val buildFile = project.buildFile
            if (buildFile.exists()) {
                inputs.file(buildFile).withPropertyName("buildScript").optional()
            }
            if (project.rootProject != project) {
                val rootBuildFile = project.rootProject.buildFile
                if (rootBuildFile.exists()) {
                    inputs.file(rootBuildFile).withPropertyName("rootBuildScript").optional()
                }
            }

            dependencyData.set(project.provider {
                val dataMap = mutableMapOf<String, Boolean>()
                project.configurations.findByName("runtimeClasspath")?.let { config ->
                    if (config.isCanBeResolved) {
                        try {
                            val result = config.incoming.resolutionResult
                            val rootId = result.root.id
                            result.allComponents.forEach { component ->
                                val id = component.id
                                if (id is ModuleComponentIdentifier) {
                                    val gav = "${id.group}:${id.module}:${id.version}"
                                    val isDirectDependency = component.dependents.any { it.from.id == rootId }
                                    dataMap[gav] = isDirectDependency
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore resolution errors
                        }
                    }
                }
                dataMap
            })
        }

        // Stage 2: Exception Reporting
        val reportTask = project.tasks.register("libInsightReport", LibInsightReportTask::class.java) {
            group = "insight"
            description = "Generates finding-based HTML and JSON reports."
            dataFile.set(collectTask.flatMap { it.dataDir.file("lib-insight.json") })
            reportDir.set(project.layout.buildDirectory.dir("reports/lib-insight"))

            customAudits.set(project.provider {
                extension.customAudits.filter { it.enabled.getOrElse(true) }.map { config ->
                    CustomAuditInfo(config.name, config.level.getOrElse("ERROR"), config.console.getOrElse(true), config.filter.get(), config.formatter.get())
                }
            })
            
            auditFingerprint.set(project.provider {
                val audits = extension.customAudits.filter { it.enabled.getOrElse(true) }
                audits.joinToString(";") { 
                    "${it.name}:${it.level.get()}:${it.console.get()}:${it.description.getOrElse("")}"
                }
            })
        }

        // Stage 3: CI/CD Gate
        project.tasks.register("libInsightCheck") {
            group = "insight"
            description = "Runs analysis and fails the build if any critical findings (ERROR) exist."
            dependsOn(collectTask, reportTask)
            
            doLast {
                val reportFile = project.layout.buildDirectory.dir("reports/lib-insight/report.json").get().asFile
                if (reportFile.exists()) {
                    val gson = com.google.gson.Gson()
                    val type = object : com.google.gson.reflect.TypeToken<List<ReportItem>>() {}.type
                    val items: List<ReportItem> = gson.fromJson(reportFile.readText(), type)
                    
                    if (items.isNotEmpty()) {
                        val hasCritical = items.any { item -> item.findings.any { f -> f.level == "ERROR" } }
                        if (hasCritical) {
                            throw org.gradle.api.GradleException("Library Insight found CRITICAL issues. Check the report at: ${reportFile.parentFile.absolutePath}/index.html")
                        }
                    }
                }
            }
        }

        // Initialize custom audits conventions
        extension.customAudits.all {
            enabled.convention(true)
            console.convention(true)
            level.convention("ERROR")
        }
    }
}
