package dev.wilhelms.gradle.insight

import com.google.gson.JsonElement
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.Action
import java.io.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit

// --- JSON Helpers ---

fun JsonElement?.safeString(): String? = if (this == null || this.isJsonNull) null else this.asString
fun JsonElement?.safeInt(): Int? = if (this == null || this.isJsonNull) null else this.asInt
fun JsonElement?.safeLong(): Long? = if (this == null || this.isJsonNull) null else this.asLong
fun JsonElement?.safeDouble(): Double? = if (this == null || this.isJsonNull) null else this.asDouble
fun JsonElement?.safeBoolean(): Boolean? = if (this == null || this.isJsonNull) null else this.asBoolean

// --- Plugin Configuration DSL ---

abstract class LibInsightExtension {
    abstract val gitHubToken: Property<String>
    abstract val librariesIoToken: Property<String>
    abstract val cacheDir: DirectoryProperty
    abstract val cacheTtlDays: Property<Int>
    abstract val suppressionFile: RegularFileProperty
    
    // Global Report Settings
    abstract val htmlReport: Property<Boolean>
    abstract val jsonReport: Property<Boolean>

    abstract val maxParallelDownloads: Property<Int>
    abstract val asyncTimeoutMinutes: Property<Int>

    abstract val customAudits: NamedDomainObjectContainer<CustomAuditConfiguration>
    
    fun customAudits(action: Action<NamedDomainObjectContainer<CustomAuditConfiguration>>) {
        action.execute(customAudits)
    }
}

abstract class CustomAuditConfiguration(val name: String) {
    abstract val enabled: Property<Boolean>
    abstract val console: Property<Boolean>
    abstract val level: Property<String> // ERROR, WARN, INFO
    abstract val description: Property<String>
    abstract val filter: Property<SerializablePredicate<LibMetric>>
    abstract val formatter: Property<SerializableFunction<LibMetric, Any>>

    fun filter(predicate: SerializablePredicate<LibMetric>) {
        filter.set(predicate)
    }

    fun format(formatFunction: SerializableFunction<LibMetric, Any>) {
        formatter.set(formatFunction)
    }
}

fun interface SerializablePredicate<T> : Serializable {
    fun check(t: T): Boolean
}

fun interface SerializableFunction<T, R> : Serializable {
    fun apply(t: T): R
}

// --- Internal Task Structures ---

data class ReportItem(
    val metric: LibMetric,
    val findings: List<Finding>
) : Serializable

data class CustomAuditInfo(
    val name: String,
    val level: String,
    val console: Boolean,
    val filter: SerializablePredicate<LibMetric>,
    val formatter: SerializableFunction<LibMetric, Any>
) : Serializable

// --- The Core Data Model (The "Schema") ---

data class LibMetric(
    val id: String,
    val version: String,
    val gradleInsight: String,
    val isDirect: Boolean,
    val suppressions: List<Suppression>,
    val pom: PomInfo,
    val mavenCentral: MavenCentralData?,
    val github: GitHubData?,
    val depsDev: DepsDevData?,
    val librariesIo: LibrariesIoData?,
    val cachedAt: String
) : Serializable {
    val cacheTime: Instant? get() = try { Instant.parse(cachedAt) } catch(e: Exception) { null }
}

data class Suppression(
    val id: String,
    val reason: String,
    val until: String? = null,
    val tasks: List<String>? = null
) : Serializable

data class PomInfo(
    val url: String,
    val license: String?,
    val scmUrl: String?
) : Serializable

data class MavenCentralData(
    val currentVersionReleaseDate: String? = null,
    val latestVersion: String? = null,
    val latestVersionReleaseDate: String? = null,
    val releaseCount: Int = 0
) : Serializable {
    val released: Instant? get() = currentVersionReleaseDate?.let { try { Instant.parse(it) } catch(e: Exception) { null } }
    val latestRelease: Instant? get() = latestVersionReleaseDate?.let { try { Instant.parse(it) } catch(e: Exception) { null } }
    
    fun isOlderThanLatest(days: Int): Boolean {
        val r = released ?: return false
        val rLimit = try { r.plus(days.toLong(), ChronoUnit.DAYS) } catch(e: Exception) { return false }
        val l = latestRelease ?: return false
        return l.isAfter(rLimit)
    }
}

data class GitHubData(
    val repo: GitHubRepo?,
    val issues: GitHubIssuesStats?,
    val activity: GitHubActivity?
) : Serializable

data class GitHubRepo(
    val name: String,
    val description: String?,
    val stargazersCount: Int,
    val forksCount: Int,
    val openIssuesCount: Int,
    val isFork: Boolean,
    val isArchived: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
    val pushedAt: String?,
    val license: String?,
    val topics: List<String> = emptyList(),
    val parentRepo: String? = null,
    val aheadBy: Int? = null,
    val behindBy: Int? = null
) : Serializable {
    val created: Instant? get() = createdAt?.let { try { Instant.parse(it) } catch(e: Exception) { null } }
    val updated: Instant? get() = updatedAt?.let { try { Instant.parse(it) } catch(e: Exception) { null } }
    val pushed: Instant? get() = pushedAt?.let { try { Instant.parse(it) } catch(e: Exception) { null } }
    
    fun isInactiveFor(days: Int): Boolean {
        val p = pushed ?: return false
        val threshold = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        return p.isBefore(threshold)
    }
}

data class GitHubIssuesStats(
    val totalIssues: Int,
    val openIssues: Int,
    val closedIssues: Int,
    val healthRatio: Double
) : Serializable

data class GitHubActivity(
    val lastCommitDaysAgo: Long?,
    val commitsLastYear: Int?
) : Serializable

data class DepsDevData(
    val dependentsCount: Int? = null,
    val advisoriesCount: Int = 0,
    val systems: List<String> = emptyList(),
    val scorecard: OpenSsfScorecard? = null
) : Serializable

data class OpenSsfScorecard(
    val overallScore: Double,
    val checks: Map<String, Int> = emptyMap()
) : Serializable

data class LibrariesIoData(
    val sourcerank: Int,
    val dependentReposCount: Int,
    val dependentsCount: Int,
    val sourcerankBreakdown: Map<String, Int> = emptyMap()
) : Serializable

data class Finding(
    val type: String,
    val level: String,
    val message: String,
    val console: Boolean = true
) : Serializable
