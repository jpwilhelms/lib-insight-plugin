package info.wilhelms.gradle.insight

import com.google.gson.*
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@DisableCachingByDefault(because = "Fetches external metrics which change over time")
abstract class LibInsightTask : DefaultTask() {

    @get:Input @get:Optional abstract val gitHubToken: Property<String>
    @get:Input @get:Optional abstract val librariesIoToken: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:Input @get:Optional abstract val cacheTtlDays: Property<Int>
    @get:InputFile @get:Optional abstract val suppressionFile: RegularFileProperty
    
    @get:Input
    abstract val dependencyData: MapProperty<String, DependencyProvenance>

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val CACHE_VERSION = 1

    @TaskAction
    fun action() {
        val depData = dependencyData.get()
        val totalCount = depData.size
        if (totalCount == 0) {
            println("No dependencies found in runtimeClasspath.")
            return
        }

        println("Analyzing $totalCount dependencies...")

        val githubService = GitHubService(gitHubToken.orNull)
        val depsDevService = DepsDevService()
        val mavenCentralService = MavenCentralService()
        val libsIoService = LibrariesIoService(librariesIoToken.orNull)
        val pomCache = mutableMapOf<String, File>()

        val resolutionResult = project.configurations.findByName("runtimeClasspath")?.incoming?.resolutionResult
        
        val moduleIds = depData.keys.mapNotNull { 
            try {
                val parts = it.split(":")
                val d = project.dependencies.create("${parts[0]}:${parts[1]}:${parts[2]}")
                val conf = project.configurations.detachedConfiguration(d)
                conf.setTransitive(false)
                val res = conf.incoming.resolutionResult.root.dependencies.firstOrNull()
                (res as? ResolvedDependencyResult)?.selected?.id as? ModuleComponentIdentifier
            } catch (e: Exception) { null }
        }
        resolvePoms(moduleIds, pomCache)

        val envCacheDir = System.getenv("LIB_QUALITY_CACHE_DIR")?.let { File(it) } 
            ?: cacheDir.get().asFile

        val suppressions = loadSuppressions()

        val metrics = moduleIds.mapIndexed { index, id ->
            val gav = "${id.group}:${id.module}:${id.version}"
            if ((index + 1) % 10 == 0 || index + 1 == totalCount) {
                print("\r\u001b[KProgress: ${index + 1}/$totalCount ($gav)")
                System.`out`.flush()
            }
            
            val provenance = depData[gav] ?: DependencyProvenance(false, emptySet())
            val insight = generateInsight(gav, resolutionResult?.allComponents?.find { it.id == id })
            val activeSuppressions = findActiveSuppressions(gav, suppressions)
            
            analyzeWithCache(envCacheDir, id, pomCache, githubService, depsDevService, mavenCentralService, libsIoService, provenance, insight, activeSuppressions)
        }
        println("\nAnalysis complete.")
        generateReport(metrics)
    }

    private fun loadSuppressions(): List<Suppression> {
        val file = suppressionFile.orNull?.asFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val listType = object : com.google.gson.reflect.TypeToken<List<Suppression>>() {}.type
            gson.fromJson(file.readText(), listType)
        } catch (e: Exception) {
            println("\nWarning: Failed to parse suppression file: ${e.message}")
            emptyList()
        }
    }

    private fun findActiveSuppressions(gav: String, all: List<Suppression>): List<Suppression> {
        val parts = gav.split(":")
        val group = parts[0]
        val name = parts[1]
        val version = parts[2]
        return all.filter { s ->
            val sParts = s.id.split(":")
            if (sParts.size < 2) return@filter false
            val groupMatch = sParts[0] == "*" || sParts[0] == group
            val nameMatch = sParts[1] == "*" || sParts[1] == name
            val verMatch = sParts.size < 3 || sParts[2] == "*" || sParts[2] == version
            if (groupMatch && nameMatch && verMatch) {
                if (s.until != null) {
                    try {
                        val untilDate = LocalDate.parse(s.until)
                        if (untilDate.isBefore(LocalDate.now())) {
                            println("\nWarning: Suppression for $gav expired on ${s.until}")
                            return@filter false
                        }
                    } catch (e: Exception) { return@filter false }
                }
                true
            } else false
        }
    }

    private fun generateInsight(gav: String, component: ResolvedComponentResult?): String {
        if (component == null) return "Not found in graph"
        val sb = StringBuilder()
        sb.append(gav).append("\n")
        component.dependents.forEach { edge ->
            val from = edge.from.id
            val fromStr = when(from) {
                is ProjectComponentIdentifier -> "project ${from.projectPath}"
                is ModuleComponentIdentifier -> "${from.group}:${from.module}:${from.version}"
                else -> from.toString()
            }
            sb.append("  <- ").append(fromStr).append("\n")
        }
        return sb.toString()
    }

    private fun analyzeWithCache(
        baseDir: File, id: ModuleComponentIdentifier, pomCache: MutableMap<String, File>,
        ghS: GitHubService, ddS: DepsDevService, mcS: MavenCentralService, liS: LibrariesIoService,
        provenance: DependencyProvenance, insight: String, activeSuppressions: List<Suppression>
    ): LibMetric {
        val dir = File(baseDir, "v$CACHE_VERSION/${id.group}/${id.module}/${id.version}")
        dir.mkdirs()

        val pomFile = pomCache["${id.group}:${id.module}:${id.version}"]
        val scmUrl = pomFile?.let { extractScmUrl(it) } ?: findInheritedScm(id, pomCache, ghS, ddS, mcS, liS)
        val license = pomFile?.let { extractLicense(it) }

        val mcFile = File(dir, "maven.json")
        val mcRaw = if (isFresh(mcFile)) mcFile.readText() else mcS.fetchRaw(id.group, id.module)?.also { mcFile.writeText(it) }
        val mavenCentralJson = mcRaw?.let { JsonParser.parseString(it) }

        var githubJson: JsonElement? = null
        if (scmUrl != null) {
            val ghRepoFile = File(dir, "github_repo.json")
            val ghIssuesFile = File(dir, "github_issues.json")
            val ghCompareFile = File(dir, "github_compare.json")
            if (!isFresh(ghRepoFile)) {
                ghS.fetchRaw(scmUrl)?.also { 
                    ghRepoFile.writeText(it.repoJson)
                    it.issuesJson?.let { issues -> ghIssuesFile.writeText(issues) }
                    it.compareJson?.let { comp -> ghCompareFile.writeText(comp) }
                }
            }
            val ghData = GitHubData(
                repo = if (ghRepoFile.exists()) JsonParser.parseString(ghRepoFile.readText()) else null,
                issues = if (ghIssuesFile.exists()) JsonParser.parseString(ghIssuesFile.readText()) else null,
                compare = if (ghCompareFile.exists()) JsonParser.parseString(ghCompareFile.readText()) else null
            )
            githubJson = JsonParser.parseString(gson.toJson(ghData))
        }

        val ddPkgFile = File(dir, "depsdev_pkg.json")
        val ddVerFile = File(dir, "depsdev_ver.json")
        val ddProjFile = File(dir, "depsdev_proj.json")
        if (!isFresh(ddPkgFile)) {
            ddS.fetchRaw(id.group, id.module, id.version)?.also {
                ddPkgFile.writeText(it.packageJson)
                ddVerFile.writeText(it.versionJson)
                it.projectJson?.let { proj -> ddProjFile.writeText(proj) }
            }
        }
        val depsDevData = DepsDevData(
            `package` = if (ddPkgFile.exists()) JsonParser.parseString(ddPkgFile.readText()) else null,
            version = if (ddVerFile.exists()) JsonParser.parseString(ddVerFile.readText()) else null,
            project = if (ddProjFile.exists()) JsonParser.parseString(ddProjFile.readText()) else null
        )

        val liFile = File(dir, "libsio_api.json")
        val liWebFile = File(dir, "libsio_web.html")
        if (!isFresh(liFile) || !isFresh(liWebFile)) {
            liS.fetchRaw(id.group, id.module)?.also {
                liFile.writeText(it.apiJson)
                it.webHtml?.let { html -> liWebFile.writeText(html) }
            }
        }
        val liRaw = if (liFile.exists() && liWebFile.exists()) LibrariesIoRaw(liFile.readText(), liWebFile.readText()) else null
        val liParsed = liRaw?.let { liS.parse(it) }
        val librariesIoJson = liParsed?.let {
            val data = LibrariesIoData(api = if (liFile.exists()) JsonParser.parseString(liFile.readText()) else null, sourcerank = it.sourcerankBreakdown?.let { b -> gson.toJsonTree(b) })
            JsonParser.parseString(gson.toJson(data))
        }

        val groupPath = id.group.replace('.', '/')
        val pomUrl = "https://repo1.maven.org/maven2/${groupPath}/${id.module}/${id.version}/${id.module}-${id.version}.pom"

        return LibMetric(
            id = "${id.group}:${id.module}:${id.version}",
            version = id.version,
            gradleInsight = insight,
            isDirect = provenance.isDirect,
            requestedBy = provenance.requestedBy.toList(),
            suppressions = activeSuppressions,
            pom = PomInfo(pomUrl, license, scmUrl),
            mavenCentral = mavenCentralJson,
            github = githubJson,
            depsDev = JsonParser.parseString(gson.toJson(depsDevData)),
            librariesIo = librariesIoJson
        )
    }

    private fun isFresh(file: File): Boolean {
        if (!file.exists()) return false
        val ttl = cacheTtlDays.getOrElse(1).toLong()
        if (ttl < 0) return true
        val lastModified = Instant.ofEpochMilli(file.lastModified())
        return lastModified.plus(ttl, ChronoUnit.DAYS).isAfter(Instant.now())
    }

    private fun findInheritedScm(id: ModuleComponentIdentifier, pomCache: MutableMap<String, File>, ghS: GitHubService, ddS: DepsDevService, mcS: MavenCentralService, liS: LibrariesIoService): String? {
        val pomFile = pomCache["${id.group}:${id.module}:${id.version}"] ?: return null
        val parentId = extractParentId(pomFile) ?: return null
        resolvePoms(listOf(parentId), pomCache)
        val parentPom = pomCache["${parentId.group}:${parentId.module}:${parentId.version}"] ?: return null
        return extractScmUrl(parentPom) ?: findInheritedScm(parentId, pomCache, ghS, ddS, mcS, liS)
    }

    private fun resolvePoms(ids: List<ModuleComponentIdentifier>, cache: MutableMap<String, File>) {
        val missingIds = ids.filter { "${it.group}:${it.module}:${it.version}" !in cache }
        if (missingIds.isEmpty()) return
        val result = project.dependencies.createArtifactResolutionQuery()
            .forComponents(missingIds).withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
        result.resolvedComponents.forEach { componentResult ->
            val id = componentResult.id as ModuleComponentIdentifier
            componentResult.getArtifacts(MavenPomArtifact::class.java).forEach { artifact ->
                if (artifact is org.gradle.api.artifacts.result.ResolvedArtifactResult) {
                    cache["${id.group}:${id.module}:${id.version}"] = artifact.file
                }
            }
        }
    }

    private fun extractParentId(pomFile: File): ModuleComponentIdentifier? {
        try {
            val content = pomFile.readText()
            val parentMatch = Regex("<parent>(.*?)</parent>", RegexOption.DOT_MATCHES_ALL).find(content) ?: return null
            val parentContent = parentMatch.groupValues[1]
            val group = Regex("<groupId>(.*?)</groupId>").find(parentContent)?.groupValues?.get(1) ?: return null
            val artifact = Regex("<artifactId>(.*?)</artifactId>").find(parentContent)?.groupValues?.get(1) ?: return null
            val version = Regex("<version>(.*?)</version>").find(parentContent)?.groupValues?.get(1) ?: return null
            val dep = project.dependencies.create("$group:$artifact:$version")
            val configuration = project.configurations.detachedConfiguration(dep)
            val resolvedComponent = configuration.incoming.resolutionResult.root.dependencies.firstOrNull() as? ResolvedDependencyResult
            return resolvedComponent?.selected?.id as? ModuleComponentIdentifier
        } catch (e: Exception) { return null }
    }

    private fun extractScmUrl(pomFile: File): String? {
        try {
            val content = pomFile.readText()
            val scmMatch = Regex("<scm>(.*?)</scm>", RegexOption.DOT_MATCHES_ALL).find(content)
            if (scmMatch != null) {
                val scmContent = scmMatch.groupValues[1]
                val url = Regex("<url>(.*?)</url>").find(scmContent)?.groupValues?.get(1)
                if (url != null && url.isNotBlank() && !url.contains("\${")) return url.trim()
                val conn = Regex("<connection>(.*?)</connection>").find(scmContent)?.groupValues?.get(1)
                if (conn != null && conn.isNotBlank()) return conn.trim()
            }
            val topUrl = Regex("<url>(.*?)</url>").find(content)?.groupValues?.get(1)
            if (topUrl != null && topUrl.isNotBlank() && !topUrl.contains("maven.apache.org")) return topUrl.trim()
        } catch (e: Exception) {}
        return null
    }

    private fun extractLicense(pomFile: File): String? {
        try {
            val content = pomFile.readText()
            val licenseMatch = Regex("<license>(.*?)</license>", RegexOption.DOT_MATCHES_ALL).find(content) ?: return null
            return Regex("<name>(.*?)</name>").find(licenseMatch.groupValues[1])?.groupValues?.get(1)
        } catch (e: Exception) { return null }
    }

    private fun generateReport(metrics: List<LibMetric>) {
        val reportFile = outputDir.file("report.json").get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(gson.toJson(metrics))
        println("Exhaustive report generated at: ${reportFile.absolutePath}")
    }
}
