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
                api name: 'dummy-lib', group: 'com.github.tester', version: '1.0.0'
            }
            libInsight {
                cacheDir = file(".gradle/lib-insight-cache")
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

        val cacheDir = testProjectDir.resolve(".gradle/lib-insight-cache/v1/com.github.tester/dummy-lib/1.0.0")
        cacheDir.mkdirs()
        cacheDir.resolve("depsdev_pkg.json").writeText("""{"dependentCount": 1000}""")
        cacheDir.resolve("depsdev_ver.json").writeText("""{"advisoryKeys": ["CVE-123"]}""")
        cacheDir.resolve("github_repo.json").writeText("""{"full_name": "tester/dummy-lib", "stargazers_count": 10, "fork": false}""")
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
                api("com.github.tester:dummy-lib:1.0.0")
            }
            libInsight {
                cacheDir.set(file(".gradle/lib-insight-cache"))
                customAudits {
                    create("securityRisk") {
                        level.set("ERROR")
                        filter { (it.depsDev?.advisoriesCount ?: 0) > 0 }
                        format { "Security Risk: ${s}{it.depsDev?.advisoriesCount} advisories" }
                    }
                }
            }
        """.trimIndent())

        val cacheDir = testProjectDir.resolve(".gradle/lib-insight-cache/v1/com.github.tester/dummy-lib/1.0.0")
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
                api name: 'dummy-lib', group: 'com.github.tester', version: '1.0.0'
            }
            libInsight {
                cacheDir = file(".gradle/lib-insight-cache")
                customAudits {
                    create("outdated") {
                        level = "ERROR"
                        filter { it.mavenCentral?.isOlderThanLatest(365) ?: false }
                        format { "Outdated: ${s}{it.version} < ${s}{it.mavenCentral.latestVersion}" }
                    }
                }
            }
        """.trimIndent())

        val cacheDir = testProjectDir.resolve(".gradle/lib-insight-cache/v1/com.github.tester/dummy-lib/1.0.0")
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
