package dev.wilhelms.gradle.insight

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

class LibInsightPluginTest {
    @TempDir
    lateinit var testProjectDir: File

    private val buildFileGroovy by lazy { testProjectDir.resolve("build.gradle") }
    private val buildFileKotlin by lazy { testProjectDir.resolve("build.gradle.kts") }
    private val settingsFile by lazy { testProjectDir.resolve("settings.gradle") }

    private fun setupDummyLib() {
        val libsDir = testProjectDir.resolve("libs")
        libsDir.mkdirs()
        File(libsDir, "dummy-lib-1.0.0.jar").writeBytes(byteArrayOf())
    }

    private fun setupLocalMavenArtifact(group: String, artifact: String, version: String, dependency: String? = null) {
        val repoBase = testProjectDir.resolve("local-m2/${group.replace('.', '/')}/$artifact/$version")
        repoBase.mkdirs()
        File(repoBase, "$artifact-$version.jar").writeBytes(byteArrayOf())
        val dependencyXml = dependency?.let {
            val parts = it.split(":")
            """
              <dependencies>
                <dependency>
                  <groupId>${parts[0]}</groupId>
                  <artifactId>${parts[1]}</artifactId>
                  <version>${parts[2]}</version>
                </dependency>
              </dependencies>
            """.trimIndent()
        } ?: ""
        File(repoBase, "$artifact-$version.pom").writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              $dependencyXml
            </project>
        """.trimIndent())
    }

    private fun writeAnalysisCache(group: String = "com.github.tester", artifact: String = "dummy-lib", version: String = "1.0.0") {
        val cacheDir = testProjectDir.resolve("test-cache/v1/$group/$artifact/$version")
        cacheDir.mkdirs()
        cacheDir.resolve("depsdev_pkg.json").writeText("""{"dependentCount": 1}""")
        cacheDir.resolve("depsdev_ver.json").writeText("""{"advisoryKeys": []}""")
        cacheDir.resolve("libsio_api.json").writeText("""{"dependents_count": 1, "dependent_repos_count": 1, "rank": 1}""")
        cacheDir.resolve("maven.json").writeText("""{"response":{"docs":[{"v":"1.0.0","timestamp":123}]}}""")
        val pomCache = testProjectDir.resolve("test-cache/poms/$group/$artifact/$version.pom")
        pomCache.parentFile.mkdirs()
        pomCache.writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
            </project>
        """.trimIndent())
    }

    @Test
    fun `plugin can be applied in Kotlin`() {
        settingsFile.writeText("rootProject.name = \"test-project\"")
        buildFileKotlin.writeText("""
            plugins {
                id("dev.wilhelms.gradle.lib-insight")
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("tasks", "--stacktrace", "--rerun-tasks")
            .withPluginClasspath()
            .forwardOutput()
            .build()

        assertTrue(result.output.contains("libInsightCheck"), "Output should contain task 'libInsightCheck'")
    }

    @Test
    fun `advanced audits work in Groovy DSL`() {
        val s = "$"
        setupDummyLib()
        settingsFile.writeText("rootProject.name = \"groovy-advanced-test\"")
        buildFileGroovy.writeText("""
            plugins {
                id "java-library"
                id "dev.wilhelms.gradle.lib-insight"
            }
            repositories {
                flatDir { dirs 'libs' }
                mavenCentral()
            }
            dependencies {
                implementation group: 'com.github.tester', name: 'dummy-lib', version: '1.0.0'
            }
            libInsight {
                cacheDir = file("test-cache")
                customAudits {
                    create("securityRisk") {
                        level = "ERROR"
                        filter { (it.depsDev?.advisoriesCount ?: 0) > 0 }
                        format { "Security Risk: ${s}{it.depsDev.advisoriesCount} advisories" }
                    }
                    create("lowTrust") {
                        level = "WARN"
                        filter { it.isDirect && it.github?.repo?.stargazersCount != null && it.github.repo.stargazersCount < 50 }
                        format { "Niche Library: Only ${s}{it.github.repo.stargazersCount} stars" }
                    }
                }
            }
        """.trimIndent())

        val cacheDir = testProjectDir.resolve("test-cache/v1/com.github.tester/dummy-lib/1.0.0")
        cacheDir.mkdirs()
        cacheDir.resolve("depsdev_pkg.json").writeText("""{"dependentCount": 1000}""")
        cacheDir.resolve("depsdev_ver.json").writeText("""{"advisoryKeys": ["CVE-123"]}""")
        cacheDir.resolve("github_repo.json").writeText("""{"full_name": "tester/dummy-lib", "stargazers_count": 10, "fork": false}""")
        cacheDir.resolve("github_issues_open.json").writeText("""{"total_count": 1}""")
        cacheDir.resolve("github_issues_closed.json").writeText("""{"total_count": 9}""")
        cacheDir.resolve("maven.json").writeText("""{"response":{"docs":[{"v":"1.0.0","timestamp":123}]}}""")

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info", "--stacktrace")
            .withPluginClasspath()
            .forwardOutput()
            .buildAndFail()
        
        assertTrue(result.output.contains("Library Insight found CRITICAL issues"), "Should fail due to securityRisk (ERROR level)")
        assertTrue(result.output.contains("[securityRisk]"), "Should show securityRisk finding")
        assertTrue(result.output.contains("[lowTrust]"), "Should show lowTrust finding")
    }

    @Test
    fun `runtime dependencies from subprojects are discovered and direct`() {
        val s = "$"
        setupLocalMavenArtifact("com.github.tester", "dummy-lib", "1.0.0")
        writeAnalysisCache()
        settingsFile.writeText("""
            rootProject.name = "multi-module-direct-test"
            include("lib")
        """.trimIndent())
        buildFileGroovy.writeText("""
            plugins {
                id "java-library"
                id "dev.wilhelms.gradle.lib-insight"
            }
            allprojects {
                repositories {
                    maven { url = uri(rootProject.file("local-m2")) }
                    mavenCentral()
                }
            }
            dependencies {
                implementation project(":lib")
            }
            libInsight {
                cacheDir = file("test-cache")
                customAudits {
                    create("directInSubproject") {
                        level = "WARN"
                        filter { it.isDirect && it.id == "com.github.tester:dummy-lib" }
                        format { "Direct dependency found: ${s}{it.id}" }
                    }
                }
            }
        """.trimIndent())
        testProjectDir.resolve("lib").mkdirs()
        testProjectDir.resolve("lib/build.gradle").writeText("""
            plugins {
                id "java-library"
            }
            dependencies {
                implementation "com.github.tester:dummy-lib:1.0.0"
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info", "--stacktrace")
            .withPluginClasspath()
            .forwardOutput()
            .build()

        assertTrue(result.output.contains("[directInSubproject]"), "Subproject runtime dependency must be treated as direct")
        assertTrue(result.output.contains("Direct dependency found: com.github.tester:dummy-lib"), "Should show the direct finding message")
    }

    @Test
    fun `transitive runtime dependencies from subprojects are discovered but not direct`() {
        val s = "$"
        setupLocalMavenArtifact("com.github.tester", "transitive-lib", "1.0.0")
        setupLocalMavenArtifact("com.github.tester", "dummy-lib", "1.0.0", "com.github.tester:transitive-lib:1.0.0")
        writeAnalysisCache("com.github.tester", "dummy-lib", "1.0.0")
        writeAnalysisCache("com.github.tester", "transitive-lib", "1.0.0")
        settingsFile.writeText("""
            rootProject.name = "multi-module-transitive-test"
            include("lib")
        """.trimIndent())
        buildFileGroovy.writeText("""
            plugins {
                id "java-library"
                id "dev.wilhelms.gradle.lib-insight"
            }
            allprojects {
                repositories {
                    maven { url = uri(rootProject.file("local-m2")) }
                    mavenCentral()
                }
            }
            dependencies {
                implementation project(":lib")
            }
            libInsight {
                cacheDir = file("test-cache")
                customAudits {
                    create("transitiveOnly") {
                        level = "WARN"
                        filter { !it.isDirect && it.id == "com.github.tester:transitive-lib" }
                        format { "Transitive runtime dependency found: ${s}{it.id}" }
                    }
                }
            }
        """.trimIndent())
        testProjectDir.resolve("lib").mkdirs()
        testProjectDir.resolve("lib/build.gradle").writeText("""
            plugins {
                id "java-library"
            }
            dependencies {
                implementation "com.github.tester:dummy-lib:1.0.0"
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info", "--stacktrace")
            .withPluginClasspath()
            .forwardOutput()
            .build()

        assertTrue(result.output.contains("[transitiveOnly]"), "Transitive runtime dependency must be analyzed")
        assertTrue(result.output.contains("Transitive runtime dependency found: com.github.tester:transitive-lib"), "Should show the transitive runtime message")
    }

    @Test
    fun `compile only dependencies are ignored`() {
        val s = "$"
        setupLocalMavenArtifact("com.github.tester", "compile-only-lib", "1.0.0")
        writeAnalysisCache("com.github.tester", "compile-only-lib", "1.0.0")
        settingsFile.writeText("""
            rootProject.name = "multi-module-compile-only-test"
            include("lib")
        """.trimIndent())
        buildFileGroovy.writeText("""
            plugins {
                id "java-library"
                id "dev.wilhelms.gradle.lib-insight"
            }
            allprojects {
                repositories {
                    maven { url = uri(rootProject.file("local-m2")) }
                    mavenCentral()
                }
            }
            dependencies {
                implementation project(":lib")
            }
            libInsight {
                cacheDir = file("test-cache")
                customAudits {
                    create("compileOnlyLeak") {
                        level = "ERROR"
                        filter { it.isDirect && it.id == "com.github.tester:compile-only-lib" }
                        format { "Unexpected compile-only dependency found: ${s}{it.id}" }
                    }
                }
            }
        """.trimIndent())
        testProjectDir.resolve("lib").mkdirs()
        testProjectDir.resolve("lib/build.gradle").writeText("""
            plugins {
                id "java-library"
            }
            dependencies {
                compileOnly "com.github.tester:compile-only-lib:1.0.0"
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info", "--stacktrace")
            .withPluginClasspath()
            .forwardOutput()
            .build()

        assertTrue(!result.output.contains("[compileOnlyLeak]"), "compileOnly dependency must not be analyzed")
    }

    @Test
    fun `unrelated projects are ignored`() {
        val s = "$"
        setupLocalMavenArtifact("com.github.tester", "wanted-lib", "1.0.0")
        setupLocalMavenArtifact("com.github.tester", "unrelated-lib", "1.0.0")
        writeAnalysisCache("com.github.tester", "wanted-lib", "1.0.0")
        writeAnalysisCache("com.github.tester", "unrelated-lib", "1.0.0")
        settingsFile.writeText("""
            rootProject.name = "multi-module-unrelated-test"
            include("lib", "other")
        """.trimIndent())
        buildFileGroovy.writeText("""
            plugins {
                id "java-library"
                id "dev.wilhelms.gradle.lib-insight"
            }
            allprojects {
                repositories {
                    maven { url = uri(rootProject.file("local-m2")) }
                    mavenCentral()
                }
            }
            dependencies {
                implementation project(":lib")
            }
            libInsight {
                cacheDir = file("test-cache")
                customAudits {
                    create("unrelatedLeak") {
                        level = "ERROR"
                        filter { it.isDirect && it.id == "com.github.tester:unrelated-lib" }
                        format { "Unexpected unrelated dependency found: ${s}{it.id}" }
                    }
                    create("wantedLib") {
                        level = "WARN"
                        filter { it.id == "com.github.tester:wanted-lib" }
                        format { "Expected dependency found: ${s}{it.id}" }
                    }
                }
            }
        """.trimIndent())
        testProjectDir.resolve("lib").mkdirs()
        testProjectDir.resolve("lib/build.gradle").writeText("""
            plugins {
                id "java-library"
            }
            dependencies {
                implementation "com.github.tester:wanted-lib:1.0.0"
            }
        """.trimIndent())
        testProjectDir.resolve("other").mkdirs()
        testProjectDir.resolve("other/build.gradle").writeText("""
            plugins {
                id "java-library"
            }
            dependencies {
                implementation "com.github.tester:unrelated-lib:1.0.0"
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info", "--stacktrace")
            .withPluginClasspath()
            .forwardOutput()
            .build()

        assertTrue(result.output.contains("[wantedLib]"), "Dependent project dependency must be analyzed")
        assertTrue(!result.output.contains("[unrelatedLeak]"), "Unrelated project dependency must not be analyzed")
    }

    @Test
    fun `advanced audits work in Kotlin DSL`() {
        val s = "$"
        setupDummyLib()
        settingsFile.writeText("rootProject.name = \"kotlin-advanced-test\"")
        buildFileKotlin.writeText("""
            plugins {
                `java-library`
                id("dev.wilhelms.gradle.lib-insight")
            }
            repositories {
                flatDir { dirs("libs") }
                mavenCentral()
            }
            dependencies {
                implementation("com.github.tester:dummy-lib:1.0.0")
            }
            libInsight {
                cacheDir.set(file("test-cache"))
                customAudits {
                    create("securityRisk") {
                        level.set("ERROR")
                        filter { (it.depsDev?.advisoriesCount ?: 0) > 0 }
                        format { "Security Risk: ${s}{it.depsDev?.advisoriesCount} advisories" }
                    }
                }
            }
        """.trimIndent())

        val cacheDir = testProjectDir.resolve("test-cache/v1/com.github.tester/dummy-lib/1.0.0")
        cacheDir.mkdirs()
        cacheDir.resolve("depsdev_pkg.json").writeText("""{"dependentCount": 1000}""")
        cacheDir.resolve("depsdev_ver.json").writeText("""{"advisoryKeys": ["CVE-123"]}""")
        cacheDir.resolve("maven.json").writeText("""{"response":{"docs":[{"v":"1.0.0","timestamp":123}]}}""")

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info", "--stacktrace")
            .withPluginClasspath()
            .forwardOutput()
            .buildAndFail()
        
        assertTrue(result.output.contains("Library Insight found CRITICAL issues"), "Should fail due to securityRisk (ERROR level)")
        assertTrue(result.output.contains("[securityRisk]"), "Should show securityRisk finding")
    }

    @Test
    fun `date-based outdated audit works in Groovy`() {
        val s = "$"
        setupDummyLib()
        settingsFile.writeText("rootProject.name = \"date-test\"")
        buildFileGroovy.writeText("""
            plugins {
                id "java-library"
                id "dev.wilhelms.gradle.lib-insight"
            }
            repositories {
                flatDir { dirs 'libs' }
                mavenCentral()
            }
            dependencies {
                implementation group: 'com.github.tester', name: 'dummy-lib', version: '1.0.0'
            }
            libInsight {
                cacheDir = file("test-cache")
                customAudits {
                    create("outdated") {
                        level = "ERROR"
                        filter { it.mavenCentral?.isOlderThanLatest(365) ?: false }
                        format { "Outdated: ${s}{it.version} < ${s}{it.mavenCentral.latestVersion}" }
                    }
                }
            }
        """.trimIndent())

        val cacheDir = testProjectDir.resolve("test-cache/v1/com.github.tester/dummy-lib/1.0.0")
        cacheDir.mkdirs()
        cacheDir.resolve("maven.json").writeText("""
            {
                "response": {
                    "docs": [
                        { "v": "2.0.0", "timestamp": 1700000000000 },
                        { "v": "1.0.0", "timestamp": 1500000000000 }
                    ]
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info", "--stacktrace")
            .withPluginClasspath()
            .forwardOutput()
            .buildAndFail()
        
        assertTrue(result.output.contains("[outdated]"), "Should find outdated version")
        assertTrue(result.output.contains("Outdated: 1.0.0 < 2.0.0"), "Should show correct format")
    }

}
