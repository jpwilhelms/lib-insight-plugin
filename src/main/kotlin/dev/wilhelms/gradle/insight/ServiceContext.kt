package dev.wilhelms.gradle.insight

import java.net.http.HttpClient
import java.time.Duration

/**
 * Shared resources for all data services to optimize performance.
 */
class ServiceContext(
    val githubToken: String? = null,
    val librariesIoToken: String? = null
) {
    val userAgent = "LibInsight-Gradle-Plugin/1.0.0 (+https://github.com/jpwilhelms/lib-insight-plugin)"
    
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
}
