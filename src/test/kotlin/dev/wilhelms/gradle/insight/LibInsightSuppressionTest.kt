package dev.wilhelms.gradle.insight

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class LibInsightSuppressionTest {
    @TempDir
    lateinit var testProjectDir: File

    private val buildFileGroovy by lazy { testProjectDir.resolve("build.gradle") }
    private val settingsFile by lazy { testProjectDir.resolve("settings.gradle") }
    private val suppressionFile by lazy { testProjectDir.resolve("suppressions.json") }

    private fun setupDummyLib() {
        val libsDir = testProjectDir.resolve("libs")
        libsDir.mkdirs()
        File(libsDir, "dummy-lib-1.0.0.jar").writeBytes(byteArrayOf())
    }

    @Test
    fun `suppression silences findings correctly`() {
        setupDummyLib()
        settingsFile.writeText("rootProject.name = \"suppression-test\"")
        
        // Create an EMPTY suppression file to avoid Gradle validation error
        suppressionFile.writeText("[]")
        
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
                suppressionFile = file("suppressions.json")
                customAudits {
                    create("securityRisk") {
                        level = "ERROR"
                        filter { (it.depsDev?.advisoriesCount ?: 0) > 0 }
                        format { "Security Risk" }
                    }
                    create("outdated") {
                        level = "WARN"
                        filter { true }
                        format { "Outdated" }
                    }
                }
            }
        """.trimIndent())

        // Setup cache data to trigger findings
        val cacheDir = testProjectDir.resolve("test-cache/v1/com.github.tester/dummy-lib/1.0.0")
        cacheDir.mkdirs()
        cacheDir.resolve("depsdev_pkg.json").writeText("""{"dependentCount": 10}""")
        cacheDir.resolve("depsdev_ver.json").writeText("""{"advisoryKeys": ["CVE-123"]}""")
        cacheDir.resolve("maven.json").writeText("""{"response":{"docs":[{"v":"1.0.0","timestamp":123}]}}""")
        cacheDir.resolve("libsio_api.json").writeText("""{"rank": 10}""")
        cacheDir.resolve("github_repo.json").writeText("""{"full_name": "tester/dummy-lib", "stargazers_count": 10, "fork": false}""")
        cacheDir.resolve("github_issues_open.json").writeText("""{"total_count": 1}""")
        cacheDir.resolve("github_issues_closed.json").writeText("""{"total_count": 9}""")

        // Scenario 1: No active suppression -> Should FAIL
        val resultFail = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info")
            .withPluginClasspath()
            .forwardOutput()
            .buildAndFail()
        
        assertTrue(resultFail.output.contains("[securityRisk]"), "Should find securityRisk in output")

        // Scenario 2: Specific suppression
        suppressionFile.writeText("""
            [
                {
                    "id": "com.github.tester:dummy-lib:1.0.0",
                    "reason": "False positive",
                    "tasks": ["securityRisk"]
                }
            ]
        """.trimIndent())
        
        val resultPartial = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info")
            .withPluginClasspath()
            .forwardOutput()
            .build()
        
        assertFalse(resultPartial.output.contains("[securityRisk]"), "securityRisk should be suppressed")
        assertTrue(resultPartial.output.contains("[outdated]"), "outdated should still be there")

        // Scenario 3: Wildcard suppression
        suppressionFile.writeText("""
            [
                {
                    "id": "com.github.tester:dummy-lib",
                    "reason": "Internal tool",
                    "tasks": ["*"]
                }
            ]
        """.trimIndent())
        
        val resultFull = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("libInsightCheck", "--info")
            .withPluginClasspath()
            .forwardOutput()
            .build()
        
        assertFalse(resultFull.output.contains("[securityRisk]"), "securityRisk should be suppressed by wildcard")
        assertFalse(resultFull.output.contains("[outdated]"), "outdated should be suppressed by wildcard")
    }
}
