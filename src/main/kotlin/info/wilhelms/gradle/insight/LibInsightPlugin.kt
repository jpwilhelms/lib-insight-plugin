package info.wilhelms.gradle.insight

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Action
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

class LibInsightPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("libInsight", LibInsightExtension::class.java)

        val reportTask = project.tasks.register("generateLibQualityReport", LibInsightTask::class.java, object : Action<LibInsightTask> {
            override fun execute(task: LibInsightTask) {
                task.gitHubToken.set(extension.gitHubToken)
                task.librariesIoToken.set(extension.librariesIoToken)
                task.outputDir.set(project.layout.buildDirectory.dir("reports/lib-insight"))
                task.cacheDir.set(extension.cacheDir.convention(project.rootProject.layout.buildDirectory.dir("lib-insight-cache")))
                task.cacheTtlDays.set(extension.cacheTtlDays.convention(1))
                task.suppressionFile.set(extension.suppressionFile)
                
                task.dependencyData.set(project.provider {
                    val dataMap = mutableMapOf<String, DependencyProvenance>()
                    project.configurations.findByName("runtimeClasspath")?.let { config ->
                        if (config.isCanBeResolved) {
                            val rootId = config.incoming.resolutionResult.root.id
                            config.incoming.resolutionResult.allComponents.forEach { component ->
                                val id = component.id
                                if (id is ModuleComponentIdentifier) {
                                    val gav = "${id.group}:${id.module}:${id.version}"
                                    val parents = component.dependents.map { it.from.id.toString() }.toSet()
                                    val isDirect = component.dependents.any { it.from.id == rootId }
                                    dataMap[gav] = DependencyProvenance(isDirect, parents)
                                }
                            }
                        }
                    }
                    dataMap
                })
            }
        })

        project.tasks.register("auditForks", LibInsightForkAuditTask::class.java, object : Action<LibInsightForkAuditTask> {
            override fun execute(task: LibInsightForkAuditTask) {
                task.reportFile.set(reportTask.flatMap { t -> t.outputDir.file("report.json") })
                task.group = "verification"
                task.description = "Identifies forked libraries and their upstream status."
            }
        })

        project.tasks.register("auditAbandoned", LibInsightAbandonedAuditTask::class.java, object : Action<LibInsightAbandonedAuditTask> {
            override fun execute(task: LibInsightAbandonedAuditTask) {
                task.reportFile.set(reportTask.flatMap { t -> t.outputDir.file("report.json") })
                task.thresholdDays.set(extension.abandonedThresholdDays)
                task.group = "verification"
                task.description = "Identifies libraries that haven't seen activity in 2+ years."
            }
        })

        // Wire up custom audits from DSL
        extension.customAudits.all(object : Action<CustomAuditConfiguration> {
            override fun execute(config: CustomAuditConfiguration) {
                project.tasks.register(config.name, LibInsightCustomAuditTask::class.java, object : Action<LibInsightCustomAuditTask> {
                    override fun execute(task: LibInsightCustomAuditTask) {
                        task.auditName.set(config.name)
                        task.description = config.description.getOrElse("")
                        task.filterPredicate.set(config.filter)
                        task.outputFormatter.set(config.formatter)
                        task.reportFile.set(reportTask.flatMap { it.outputDir.file("report.json") })
                        task.group = "verification"
                    }
                })
            }
        })
    }
}
