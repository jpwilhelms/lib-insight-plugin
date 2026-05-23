package info.wilhelms.gradle.insight

import com.google.gson.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.time.Instant
import java.time.temporal.ChronoUnit

abstract class LibInsightAuditTask : DefaultTask() {

    @get:InputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun audit() {
        val json = JsonParser.parseString(reportFile.get().asFile.readText()).asJsonArray
        
        // Filter out suppressed items for THIS task
        val unsuppressed = json.filter { elem ->
            val metric = elem.asJsonObject
            val suppressions = metric.getAsJsonArray("suppressions") ?: return@filter true
            val taskName = name
            
            suppressions.none { s ->
                val sObj = s.asJsonObject
                val sTasks = sObj.getAsJsonArray("tasks")?.map { it.asString } ?: listOf("*")
                sTasks.contains("*") || sTasks.contains(taskName)
            }
        }
        
        performAudit(unsuppressed)
    }

    abstract fun performAudit(report: List<com.google.gson.JsonElement>)
}

abstract class LibInsightForkAuditTask : LibInsightAuditTask() {
    override fun performAudit(report: List<com.google.gson.JsonElement>) {
        println("\n==========================================================================================")
        println(" LIBRARY FORK AUDIT ")
        println("==========================================================================================")
        System.out.printf("%-45s | %-8s | %-8s | %-12s\n", "Library ID", "Behind", "Ahead", "Last Push")
        println("----------------------------------------------|----------|----------|-------------")

        report.filter { elem ->
            val gh = elem.asJsonObject.get("github")?.asJsonObject
            gh?.get("repo")?.asJsonObject?.get("fork")?.asBoolean == true 
        }.sortedByDescending { elem ->
            val gh = elem.asJsonObject.get("github")?.asJsonObject
            gh?.get("compare")?.asJsonObject?.get("behind_by")?.asInt ?: 0
        }.forEach { elem ->
            val metric = elem.asJsonObject
            val id = metric.get("id").asString
            val gh = metric.get("github").asJsonObject
            val comp = gh.get("compare")?.asJsonObject
            val repo = gh.get("repo").asJsonObject
            
            val behind = comp?.get("behind_by")?.asInt ?: 0
            val ahead = comp?.get("ahead_by")?.asInt ?: 0
            val push = repo.get("pushed_at")?.asString?.take(10) ?: "N/A"
            
            System.out.printf("%-45s | %-8s | %-8s | %-12s\n", id.take(45), behind, ahead, push)
        }
        println("==========================================================================================")
    }
}

abstract class LibInsightAbandonedAuditTask : LibInsightAuditTask() {
    
    @get:Input
    @get:Optional
    abstract val thresholdDays: Property<Int>

    override fun performAudit(report: List<com.google.gson.JsonElement>) {
        val days = thresholdDays.getOrElse(730).toLong()
        val threshold = Instant.now().minus(days, ChronoUnit.DAYS)
        
        println("\n==========================================================================================")
        println(" ABANDONED PROJECTS AUDIT (Inactive since $days days) ")
        println("==========================================================================================")
        System.out.printf("%-50s | %-12s | %-8s\n", "Library ID", "Last Activity", "Source")
        println("---------------------------------------------------|--------------|---------")

        report.mapNotNull { elem ->
            val metric = elem.asJsonObject
            val id = metric.get("id").asString
            val gh = metric.get("github")?.asJsonObject
            val pushed = gh?.get("repo")?.asJsonObject?.get("pushed_at")?.asString
            
            if (pushed != null) {
                val date = Instant.parse(pushed)
                if (date.isBefore(threshold)) {
                    Triple(id, date.toString().take(10), "GitHub")
                } else null
            } else null
        }.sortedBy { it.second }.forEach { item ->
            System.out.printf("%-50s | %-12s | %-8s\n", item.first.take(50), item.second, item.third)
        }
        println("==========================================================================================")
    }
}
