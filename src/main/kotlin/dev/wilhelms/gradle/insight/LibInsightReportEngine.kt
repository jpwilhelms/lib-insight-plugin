package dev.wilhelms.gradle.insight

object LibInsightReportEngine {
    fun evaluate(metrics: List<LibMetric>, audits: List<CustomAuditInfo>): List<ReportItem> {
        return metrics.mapNotNull { metric ->
            val findings = mutableListOf<Finding>()

            audits.forEach { audit ->
                if (audit.filter.check(metric) == true) {
                    findings.add(
                        Finding(
                            audit.name,
                            audit.level,
                            audit.formatter.apply(metric).toString(),
                            audit.console
                        )
                    )
                }
            }

            val activeFindings = findings.filter { finding ->
                metric.suppressions.none { suppression ->
                    suppression.isActive() && suppression.matches(metric, finding.type)
                }
            }

            if (activeFindings.isNotEmpty()) ReportItem(metric, activeFindings) else null
        }.sortedBy { it.metric.id }
    }
}

private fun Suppression.matches(metric: LibMetric, findingType: String): Boolean {
    val idMatches = id == metric.id || id == "${metric.id}:${metric.version}"
    if (!idMatches) return false

    val suppressionTasks = tasks ?: listOf("*")
    return suppressionTasks.contains("*") || suppressionTasks.contains(findingType)
}
