package dev.wilhelms.gradle.insight

import java.net.http.HttpClient
import java.time.Duration

/**
 * Shared resources for all data services to optimize performance.
 */
class ServiceContext(
    val githubToken: String? = null,
    val librariesIoToken: String? = null,
    connectTimeoutSeconds: Long = 10,
    requestTimeoutSeconds: Long = 30
) {
    val userAgent = "LibInsight-Gradle-Plugin (+https://github.com/jpwilhelms/lib-insight-plugin)"
    val requestTimeout = Duration.ofSeconds(requestTimeoutSeconds)
    
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .build()
}
