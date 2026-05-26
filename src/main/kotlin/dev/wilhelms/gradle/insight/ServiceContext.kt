package dev.wilhelms.gradle.insight

import java.net.http.HttpClient
import java.time.Duration
import java.util.Properties

/**
 * Shared resources for all data services to optimize performance.
 */
class ServiceContext(
    val githubToken: String? = null,
    val librariesIoToken: String? = null
) {
    val version: String = try {
        val props = Properties()
        ServiceContext::class.java.getResourceAsStream("/version.properties")?.use { props.load(it) }
        props.getProperty("version", "1.0.0")
    } catch (e: Exception) { "1.0.0" }

    val userAgent = "LibInsight-Gradle-Plugin/$version (+https://github.com/jpwilhelms/lib-insight-plugin)"
    
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
}
