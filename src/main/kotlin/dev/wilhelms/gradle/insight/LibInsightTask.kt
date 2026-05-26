package dev.wilhelms.gradle.insight

import com.google.gson.*
import dev.wilhelms.gradle.progress.ProgressLogger
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

abstract class LibInsightTask : DefaultTask() {

    @get:OutputDirectory
    abstract val dataDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val gitHubToken: Property<String>

    @get:Input
    @get:Optional
    abstract val librariesIoToken: Property<String>

    @get:Input
    abstract val maxParallel: Property<Int>

    @get:Input
    abstract val timeoutMinutes: Property<Int>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:Input
    abstract val cacheTtlDays: Property<Int>

    @get:InputFile
    @get:Optional
    abstract val suppressionFile: RegularFileProperty

    @get:Input
    abstract val dependencyData: MapProperty<String, Boolean>

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @TaskAction
    fun action() {
        val dependencies = dependencyData.get()
        val envCacheDir = cacheDir.get().asFile
        
        // 1. Comprehensive Dirty Check: A lib is only fresh if ALL required files are fresh
        val status = dependencies.keys.associateWith { gav ->
            isLibraryFresh(envCacheDir, gav)
        }
        
        val dirtyGavs = status.filter { !it.value }.keys
        val cachedCount = dependencies.size - dirtyGavs.size

        if (dirtyGavs.isEmpty()) {
            println("All ${dependencies.size} dependencies are up-to-date in cache.")
        } else {
            println("Analysis Status: $cachedCount cached, ${dirtyGavs.size} to update (Parallelism: ${maxParallel.get()}, Timeout: ${timeoutMinutes.get()}m)")
            if (gitHubToken.getOrNull().isNullOrBlank()) {
                println("NOTE: No GH_TOKEN provided. GitHub API rate limits will be low.")
            }
            if (librariesIoToken.getOrNull().isNullOrBlank()) {
                println("NOTE: No LIBRARIES_IO_TOKEN provided. Libraries.io data will be unavailable.")
            }
        }

        // 2. Setup Progress and Services
        val progress = ProgressLogger(project, javaClass)
        progress.start("Analyzing dependencies", "lib-insight")

        val ctx = ServiceContext(gitHubToken.getOrNull(), librariesIoToken.getOrNull())
        val githubService = GitHubService(ctx)
        val depsDevService = DepsDevService(ctx)
        val mavenCentralService = MavenCentralService(ctx)
        val libsIoService = LibrariesIoService(ctx)

        val pomCache = java.util.concurrent.ConcurrentHashMap<String, File>()
        val activeSuppressions = loadSuppressions()
        val completedCount = AtomicInteger(0)

        // 3. Broad Async Execution
        val allMetricsFutures = dependencies.entries.map { (gav, isDirectDep) ->
            val parts = gav.split(":")
            val id = object : ModuleComponentIdentifier {
                override fun getGroup() = parts[0]
                override fun getModule() = parts[1]
                override fun getVersion() = parts[2]
                override fun getDisplayName() = gav
                override fun getModuleIdentifier() = object : ModuleIdentifier {
                    override fun getGroup() = parts[0]
                    override fun getName() = parts[1]
                }
            }

            analyzeWithCacheAsync(envCacheDir, id, pomCache, githubService, depsDevService, mavenCentralService, libsIoService, isDirectDep, activeSuppressions)
                .thenApply { metric ->
                    val current = completedCount.incrementAndGet()
                    progress.progress("[$current/${dependencies.size}] $gav")
                    metric
                }
        }

        // Wait with configurable timeout
        val metrics = mutableListOf<LibMetric>()
        allMetricsFutures.forEach { future ->
            try {
                future.get(timeoutMinutes.get().toLong(), TimeUnit.MINUTES)?.let { metrics.add(it) }
            } catch (e: Exception) {
                // Individual failures are handled/logged in analyzeWithCacheAsync
            }
        }
        
        progress.completed()

        val sortedMetrics = metrics.sortedBy { it.id }
        generateDataFile(sortedMetrics)
        
        if (dirtyGavs.isNotEmpty()) {
            println("Analysis complete. Total metadata records: ${sortedMetrics.size}")
        }
    }

    private fun isLibraryFresh(baseDir: File, gav: String): Boolean {
        val parts = gav.split(":")
        val dir = File(baseDir, "v$CACHE_VERSION/${parts[0]}/${parts[1]}/${parts[2]}")
        
        // We consider a library fresh only if at least the core metadata is there and fresh.
        // GitHub/LibrariesIO might be missing if no SCM was found, so we check the 'main' indicators.
        val coreFiles = listOf(File(dir, "maven.json"), File(dir, "depsdev_pkg.json"))
        return coreFiles.all { isFresh(it) }
    }

    private fun loadSuppressions(): List<Suppression> {
        val file = suppressionFile.getOrNull()?.asFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val listType = object : com.google.gson.reflect.TypeToken<List<Suppression>>() {}.type
            gson.fromJson(file.readText(), listType)
        } catch (e: Exception) {
            println("Warning: Failed to load suppressions: ${e.message}")
            emptyList()
        }
    }

    private fun analyzeWithCacheAsync(
        baseDir: File, id: ModuleComponentIdentifier, pomCache: MutableMap<String, File>,
        ghS: GitHubService, ddS: DepsDevService, mcS: MavenCentralService, liS: LibrariesIoService,
        isDirect: Boolean, activeSuppressions: List<Suppression>
    ): CompletableFuture<LibMetric?> {
        val dir = File(baseDir, "v$CACHE_VERSION/${id.group}/${id.module}/${id.version}")
        dir.mkdirs()

        val pomFile = findInheritedPom(id, pomCache)
        val scmUrl = extractScmUrl(pomFile, id)
        val license = pomFile?.let { extractLicense(it) }

        // Fetch each provider only if not fresh
        val mcFile = File(dir, "maven.json")
        val mcFuture = if (isFresh(mcFile)) CompletableFuture.completedFuture(mcFile.readText()) 
                       else mcS.fetchRawAsync(id.group, id.module).thenApply { it?.also { mcFile.writeText(it) } }

        val ghRepoFile = File(dir, "github_repo.json")
        val ghIssuesFile = File(dir, "github_issues.json")
        val ghCompareFile = File(dir, "github_compare.json")
        val ghFuture = if (isFresh(ghRepoFile)) {
            CompletableFuture.completedFuture(GitHubRaw(ghRepoFile.readText(), 
                if (ghIssuesFile.exists()) ghIssuesFile.readText() else null,
                if (ghCompareFile.exists()) ghCompareFile.readText() else null))
        } else if (scmUrl != null) {
            ghS.fetchRawAsync(scmUrl).thenApply { res ->
                res?.also {
                    ghRepoFile.writeText(it.repoJson)
                    it.issuesJson?.let { issues -> ghIssuesFile.writeText(issues) }
                    it.compareJson?.let { comp -> ghCompareFile.writeText(comp) }
                }
            }
        } else CompletableFuture.completedFuture(null)

        val ddPkgFile = File(dir, "depsdev_pkg.json")
        val ddVerFile = File(dir, "depsdev_ver.json")
        val ddProjFile = File(dir, "depsdev_proj.json")
        val ddFuture = if (isFresh(ddPkgFile)) {
            CompletableFuture.completedFuture(DepsDevRaw(ddPkgFile.readText(), ddVerFile.readText(), 
                if (ddProjFile.exists()) ddProjFile.readText() else null))
        } else {
            ddS.fetchRawAsync(id.group, id.module, id.version).thenApply { res ->
                res?.also {
                    ddPkgFile.writeText(it.packageJson)
                    ddVerFile.writeText(it.versionJson)
                    it.projectJson?.let { proj -> ddProjFile.writeText(proj) }
                }
            }
        }

        val liFile = File(dir, "libsio_api.json")
        val liWebFile = File(dir, "libsio_web.html")
        val liFuture = if (isFresh(liFile)) {
            CompletableFuture.completedFuture(LibrariesIoRaw(liFile.readText(), 
                if (liWebFile.exists()) liWebFile.readText() else null))
        } else {
            liS.fetchRawAsync(id.group, id.module).thenApply { res ->
                res?.also {
                    liFile.writeText(it.apiJson)
                    it.webHtml?.let { html -> liWebFile.writeText(html) }
                }
            }
        }

        return CompletableFuture.allOf(mcFuture, ghFuture, ddFuture, liFuture).thenApply {
            val mcData = mcFuture.get()?.let { mcS.parse(it, id.version) }
            val ghData = ghFuture.get()?.let { ghS.parse(it) }
            val ddData = ddFuture.get()?.let { ddS.parse(it) }
            val liData = liFuture.get()?.let { liS.parse(it) }

            val groupPath = id.group.replace('.', '/')
            val pomUrl = "https://repo1.maven.org/maven2/${groupPath}/${id.module}/${id.version}/${id.module}-${id.version}.pom"

            LibMetric(
                id = "${id.group}:${id.module}",
                version = id.version,
                gradleInsight = "findings",
                isDirect = isDirect,
                suppressions = activeSuppressions,
                pom = PomInfo(pomUrl, license, scmUrl),
                mavenCentral = mcData,
                github = ghData,
                depsDev = ddData,
                librariesIo = liData,
                cachedAt = Instant.now().toString()
            )
        }.exceptionally { null }
    }

    private fun findInheritedPom(id: ModuleComponentIdentifier, cache: MutableMap<String, File>): File? {
        val key = "${id.group}:${id.module}:${id.version}"
        if (cache.containsKey(key)) return cache[key]
        val groupPath = id.group.replace('.', '/')
        val url = "https://repo1.maven.org/maven2/${groupPath}/${id.module}/${id.version}/${id.module}-${id.version}.pom"
        val dir = File(cacheDir.get().asFile, "poms/${id.group}/${id.module}")
        dir.mkdirs()
        val file = File(dir, "${id.version}.pom")
        if (!file.exists()) {
            try {
                val client = java.net.http.HttpClient.newHttpClient()
                val request = java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(url)).build()
                val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() == 200) file.writeBytes(response.body())
                else return null
            } catch (e: Exception) { return null }
        }
        cache[key] = file
        return file
    }

    private fun extractScmUrl(pomFile: File?, id: ModuleComponentIdentifier): String? {
        if (pomFile != null) {
            try {
                val content = pomFile.readText()
                val scmMatch = Regex("<scm>(.*?)</scm>", RegexOption.DOT_MATCHES_ALL).find(content)
                if (scmMatch != null) {
                    val scmContent = scmMatch.groupValues[1]
                    val url = Regex("<url>(.*?)</url>").find(scmContent)?.groupValues?.get(1)
                    if (url != null && url.isNotBlank() && !url.contains("\${")) return url.trim()
                }
            } catch (e: Exception) {}
        }
        if (id.group.startsWith("com.github.")) return "https://github.com/${id.group.substringAfter("com.github.")}/${id.module}"
        return null
    }

    private fun extractLicense(pomFile: File): String? {
        try {
            val content = pomFile.readText()
            val match = Regex("<license>.*?<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(content)
            return match?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {}
        return null
    }

    private fun isFresh(file: File): Boolean {
        if (!file.exists()) return false
        val ttl = cacheTtlDays.getOrElse(1).toLong()
        val expiry = Instant.now().minus(ttl, java.time.temporal.ChronoUnit.DAYS)
        return Instant.ofEpochMilli(file.lastModified()).isAfter(expiry)
    }

    private fun generateDataFile(metrics: List<LibMetric>) {
        val dataFile = dataDir.file("lib-insight.json").get().asFile
        dataFile.parentFile.mkdirs()
        dataFile.writeText(gson.toJson(metrics))
    }

    companion object {
        const val CACHE_VERSION = 1
    }
}
