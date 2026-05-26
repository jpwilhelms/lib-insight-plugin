# Library Insight Gradle Plugin

The Library Insight plugin analyzes project dependencies and provides quality assessments by aggregating data from multiple repository and security sources. It focuses on **Exception Reporting**, highlighting only the libraries that require your attention.

> [!IMPORTANT]
> This plugin was developed with **AI assistance** to ensure high-fidelity data collection and modern architectural standards.

## Features
*   **Dependency Analysis:** Scans your dependency graph and identifies potential risks.
*   **Provider Integration:** Aggregates data from GitHub, Deps.dev (OpenSSF Scorecard), and Maven Central.
*   **Structured Reporting:** HTML and JSON reports grouped by severity (Critical, Warning, Info).
*   **Custom Audits:** Flexible DSL to define your own quality gates.
*   **CI/CD Governance:** Build failure logic based on identified critical findings.

## Usage

### Applying the Plugin

```groovy
plugins {
    id "dev.wilhelms.gradle.lib-insight" version "1.0.0"
}
```

### Configuration
The plugin comes with no hardcoded audits enabled by default. Use the following snippets in your `customAudits` block to get started.

<details open>
<summary>Example Configuration (build.gradle)</summary>

```groovy
libInsight {
    autoCheck = true
    
    // Path to optional suppressions file
    suppressionFile = file("lib-insight-suppressions.json")

    customAudits {
        // 1. Stale Fork Detection
        create("forks") {
            description = "Identifies forks that are significantly behind upstream"
            level = "ERROR"
            filter { it.github?.repo?.isFork && (it.github?.repo?.behindBy ?: 0) > 10 }
            format { "Fork is stale: ${it.github.repo.behindBy} commits behind upstream" }
        }
        
        // 2. Outdated Version Check
        create("outdated") {
            description = "Checks if the used version is significantly older than the latest"
            level = "WARN"
            filter { it.mavenCentral?.isOlderThanLatest(730) }
            format { "Update recommended: Latest is ${it.mavenCentral.latestVersion}" }
        }

        // 3. Security Quality Gate
        create("securityGate") {
            level = "ERROR"
            filter { (it.depsDev?.advisoriesCount ?: 0) > 0 }
            format { "CRITICAL: ${it.depsDev.advisoriesCount} known security advisories!" }
        }

        // 4. Maintenance Check (Silent in console)
        create("maintenanceCheck") {
            level = "INFO"
            console = false // Only visible in HTML report
            filter { 
                def issues = it.github?.issues
                return issues != null && issues.totalIssues >= 20 && issues.healthRatio < 0.2
            }
            format { "Poor maintenance: Only ${(it.github.issues.healthRatio * 100).toInteger()}% of issues are closed" }
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
| `cacheDir` | `LIB_INSIGHT_CACHE_DIR` | `~/.gradle/lib-insight-cache` | Shared metadata cache directory. |
| `suppressionFile` | - | - | JSON file containing findings to ignore. |
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

## Data Schema & Reports

Library Insight produces detailed reports in the build directory. For a full description of the available fields in `LibMetric` (to be used in your `filter` and `format` closures), please refer to the **[JSON Report Schema](docs/REPORT_SCHEMA.md)**.

### Suppressions
You can suppress specific findings by providing a JSON file (`suppressionFile` property):
```json
[
  {
    "id": "com.google.code.gson:gson",
    "reason": "Temporary exception for specific version",
    "tasks": ["outdated"]
  }
]
```

## Tasks

*   `libInsightReport`: **Reporting.** Evaluates all audits and generates findings-based HTML/JSON reports.
*   `libInsightCheck`: **Governance.** Runs analysis and fails the build if any `ERROR` level findings exist.

## License
Licensed under the [Apache License, Version 2.0](LICENSE).
