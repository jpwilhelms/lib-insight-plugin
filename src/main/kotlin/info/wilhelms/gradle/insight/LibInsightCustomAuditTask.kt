package info.wilhelms.gradle.insight

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class LibInsightCustomAuditTask : DefaultTask() {

    @get:InputFile
    abstract val reportFile: RegularFileProperty

    @get:Input
    abstract val filterPredicate: Property<SerializablePredicate<LibMetric>>

    @get:Input
    abstract val outputFormatter: Property<SerializableFunction<LibMetric, String>>

    @get:Input
    abstract val auditName: Property<String>

    @TaskAction
    fun runAudit() {
        val gson = Gson()
        val content = reportFile.get().asFile.readText()
        val listType = object : TypeToken<List<LibMetric>>() {}.type
        val metrics: List<LibMetric> = gson.fromJson(content, listType)

        println("\n==========================================================================================")
        println(" CUSTOM AUDIT: ${auditName.get().uppercase()} ")
        println("==========================================================================================")

        val predicate = filterPredicate.get()
        val formatter = outputFormatter.get()

        val taskName = name
        
        metrics.filter { metric ->
            // Apply suppressions first
            val isSuppressed = metric.suppressions.any { s ->
                val sTasks = s.tasks ?: listOf("*")
                sTasks.contains("*") || sTasks.contains(taskName)
            }
            if (isSuppressed) return@filter false
            
            // Apply custom filter
            predicate(metric)
        }.forEach { metric ->
            println(formatter(metric))
        }

        println("==========================================================================================")
    }
}
