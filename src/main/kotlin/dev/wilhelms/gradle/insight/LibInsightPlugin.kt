package dev.wilhelms.gradle.insight

import org.gradle.api.Plugin
import org.gradle.api.Project
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
        extension.httpConnectTimeoutSeconds.convention(10)
        extension.httpRequestTimeoutSeconds.convention(30)

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
            connectTimeoutSeconds.set(extension.httpConnectTimeoutSeconds)
            requestTimeoutSeconds.set(extension.httpRequestTimeoutSeconds)
            dataDir.set(project.layout.buildDirectory.dir("lib-insight/data"))
            cacheDir.set(extension.cacheDir)
            cacheTtlDays.set(extension.cacheTtlDays.convention(1))
            suppressionFile.set(extension.suppressionFile)
            
            // Track build files for reliable incremental builds
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
                collectDependencyData(project)
            })
        }

        // Stage 2: Exception Reporting
        val reportTask = project.tasks.register("libInsightReport", LibInsightReportTask::class.java) {
            group = "insight"
            description = "Generates finding-based HTML and JSON reports."
            dataFile.set(collectTask.flatMap { it.dataDir.file("lib-insight.json") })
            reportDir.set(project.layout.buildDirectory.dir("reports/lib-insight"))
            htmlReport.set(extension.htmlReport)
            jsonReport.set(extension.jsonReport)

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

            outputs.upToDateWhen { false }
        }

        // Stage 3: CI/CD Gate
        project.tasks.register("libInsightCheck") {
            group = "insight"
            description = "Runs analysis and fails the build if any critical findings (ERROR) exist."
            dependsOn(collectTask, reportTask)

            val checkDataFile = collectTask.flatMap { it.dataDir.file("lib-insight.json") }
            val checkCustomAudits = project.provider {
                extension.customAudits.filter { it.enabled.getOrElse(true) }.map { config ->
                    CustomAuditInfo(config.name, config.level.getOrElse("ERROR"), config.console.getOrElse(true), config.filter.get(), config.formatter.get())
                }
            }

            doLast {
                val gson = com.google.gson.Gson()
                val type = object : com.google.gson.reflect.TypeToken<List<LibMetric>>() {}.type
                val metrics: List<LibMetric> = gson.fromJson(checkDataFile.get().asFile.readText(), type)
                val items = LibInsightReportEngine.evaluate(metrics, checkCustomAudits.getOrElse(emptyList()))

                if (items.any { item -> item.findings.any { f -> f.level == "ERROR" } }) {
                    throw org.gradle.api.GradleException("Library Insight found CRITICAL issues. Check the report at: ${project.layout.buildDirectory.dir("reports/lib-insight/index.html").get().asFile.absolutePath}")
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

private fun collectDependencyData(project: Project): Map<String, Boolean> {
    val dataMap = mutableMapOf<String, Boolean>()
    val scopeProjects = project.rootProject.allprojects

    for (scopeProject in scopeProjects) {
        for (configName in listOf("runtimeClasspath", "compileClasspath")) {
            val config = scopeProject.configurations.findByName(configName) ?: continue
            if (!config.isCanBeResolved) continue

            try {
                val result = config.incoming.resolutionResult
                val rootId = result.root.id
                result.allComponents.forEach { component ->
                    val id = component.id
                    if (id is ModuleComponentIdentifier) {
                        val gav = "${id.group}:${id.module}:${id.version}"
                        val isDirect = component.dependents.any { it.from.id == rootId }
                        dataMap[gav] = dataMap.getOrDefault(gav, false) || isDirect
                    }
                }
            } catch (_: Exception) {
                // Ignore unresolved configurations from optional or partial subprojects.
            }
        }
    }

    return dataMap
}
