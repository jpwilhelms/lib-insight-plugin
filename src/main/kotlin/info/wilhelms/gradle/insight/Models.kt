package info.wilhelms.gradle.insight

import com.google.gson.JsonElement
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import java.io.Serializable

interface LibInsightExtension {
    val gitHubToken: Property<String>
    val librariesIoToken: Property<String>
    val cacheDir: DirectoryProperty
    val cacheTtlDays: Property<Int>
    val abandonedThresholdDays: Property<Int>
    val suppressionFile: RegularFileProperty
    val customAudits: NamedDomainObjectContainer<CustomAuditConfiguration>
}

abstract class CustomAuditConfiguration(val name: String) {
    abstract val description: Property<String>
    abstract val filter: Property<SerializablePredicate<LibMetric>>
    abstract val formatter: Property<SerializableFunction<LibMetric, String>>
}

interface SerializablePredicate<T> : (T) -> Boolean, Serializable
interface SerializableFunction<T, R> : (T) -> R, Serializable

data class LibMetric(
    val id: String,
    val version: String,
    val gradleInsight: String,
    val isDirect: Boolean,
    val requestedBy: List<String>,
    val suppressions: List<Suppression>,
    val pom: PomInfo,
    val mavenCentral: JsonElement?,
    val github: JsonElement?,
    val depsDev: JsonElement?,
    val librariesIo: JsonElement?,
    val cachedAt: String? = null
) : Serializable

data class PomInfo(val url: String, val license: String?, val scmUrl: String?) : Serializable
data class GitHubData(val repo: JsonElement?, val issues: JsonElement?, val compare: JsonElement?) : Serializable
data class DepsDevData(val `package`: JsonElement?, val version: JsonElement?, val project: JsonElement?) : Serializable
data class LibrariesIoData(val api: JsonElement?, val sourcerank: JsonElement?) : Serializable

data class DependencyProvenance(
    val isDirect: Boolean,
    val requestedBy: Set<String>
) : Serializable

data class Suppression(
    val id: String,
    val tasks: List<String>? = null,
    val until: String? = null,
    val reason: String
) : Serializable

data class Finding(val type: String, val level: String, val message: String) : Serializable
