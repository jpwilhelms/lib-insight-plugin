# Library Insight Gradle Plugin

The Library Insight plugin analyzes project dependencies and provides quality assessments by aggregating data from multiple repository and security sources. It focuses on **Exception Reporting**, highlighting only the libraries that require your attention.

## Features
*   **Dependency Analysis:** Scans your dependency graph and identifies potential risks.
*   **Provider Integration:** Aggregates data from GitHub, Deps.dev (OpenSSF Scorecard), and Maven Central.
*   **Structured Reporting:** HTML and JSON reports grouped by severity (Critical, Warning, Info).
*   **Custom Audits:** Flexible DSL to define your own quality gates.
*   **CI/CD Governance:** Build failure logic based on identified critical findings.

## Usage

### Applying the Plugin

<details open>
<summary>Groovy</summary>

```groovy
plugins {
    id "dev.wilhelms.gradle.lib-insight" version "1.0.0-SNAPSHOT"
}
```
</details>

<details>
<summary>Kotlin</summary>

```kotlin
plugins {
    id("dev.wilhelms.gradle.lib-insight") version "1.0.0-SNAPSHOT"
}
```
</details>

### Configuration
The plugin comes with no hardcoded audits enabled by default. Use the following snippets in your `customAudits` block to get started.

<details open>
<summary>Groovy (build.gradle)</summary>

```groovy
libInsight {
    autoCheck = true
    
    customAudits {
        create("forks") {
            description = "Identifies forks that are significantly behind upstream"
            level = "ERROR" // Errors fail the build
            filter { it.github?.repo?.isFork && (it.github?.repo?.behindBy ?: 0) > 10 }
            format { "Fork is stale: ${it.github.repo.behindBy} commits behind upstream" }
        }
        
        create("outdated") {
            description = "Checks if the used version is significantly older than the latest"
            level = "WARN" // Warnings are visible but do not fail the build
            filter { it.mavenCentral?.isOlderThanLatest(730) }
            format { "Update recommended: Latest is ${it.mavenCentral.latestVersion}" }
        }
    }
}
```
</details>

<details>
<summary>Kotlin (build.gradle.kts)</summary>

```kotlin
libInsight {
    autoCheck.set(true)
    
    customAudits {
        create("forks") {
            level.set("ERROR")
            filter { it.github?.repo?.isFork == true && (it.github?.repo?.behindBy ?: 0) > 10 }
            format { "Fork is stale: ${it.github?.repo?.behindBy} commits behind upstream" }
        }
    }
}
```
</details>

### Global Configuration & Environment Variables

| Property | Env Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `gitHubToken` | `GH_TOKEN` | - | GitHub Personal Access Token for higher rate limits. |
| `librariesIoToken` | `LIBRARIES_IO_TOKEN` | - | API key for libraries.io integration. |
| `maxParallelDownloads` | - | `10` | Number of concurrent API requests for data collection. |
| `cacheDir` | `LIB_INSIGHT_CACHE_DIR` | `.gradle/lib-insight-cache` | Directory for raw API response metadata. |
| `autoCheck` | - | `false` | If `true`, hooks `libInsightCheck` into the standard `check` task. |

---

## Audit Configuration

Each custom audit supports the following properties:

| Property | Default | Description |
| :--- | :--- | :--- |
| `level` | `"ERROR"` | Severity of the finding. <br>• **`ERROR`**: Findings appear in "Critical Issues" and **fail the build**. <br>• **`WARN`**: Findings appear in "Warnings", visible but non-blocking. <br>• **`INFO`**: Findings appear in "Information" section. |
| `console`| `true` | If `true`, findings are printed to the terminal during build. |
| `description` | - | Optional description of the audit's purpose. |
| `enabled` | `true` | If `false`, the audit is skipped. |

---

## Advanced Custom Audit Examples (Groovy)

### 1. Security Quality Gate
Fails the build if there are known vulnerabilities.
```groovy
create("securityGate") {
    level = "ERROR"
    filter { 
        def advisories = it.depsDev?.advisoriesCount ?: 0
        return advisories > 0
    }
    format { "CRITICAL: ${it.depsDev.advisoriesCount} known security advisories!" }
}
```

### 2. Maintenance Information (Silent)
Gather info about poor maintenance without cluttering the console or failing the build.
```groovy
create("maintenanceCheck") {
    level = "INFO"
    console = false // Only visible in HTML report
    filter { 
        def issues = it.github?.issues
        return issues != null && issues.totalIssues >= 20 && issues.healthRatio < 0.2
    }
    format { "Poor maintenance: Only ${(it.github.issues.healthRatio * 100).toInteger()}% of issues are closed" }
}
```

## Tasks

*   `libInsightReport`: **Reporting.** Evaluates all audits and generates findings-based reports. (Triggers data collection automatically).
*   `libInsightCheck`: **Governance.** Runs analysis and fails the build if any `ERROR` level findings exist.

## License
Licensed under the [Apache License, Version 2.0](LICENSE).
