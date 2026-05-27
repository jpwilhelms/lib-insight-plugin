# Library Insight Gradle Plugin

The Library Insight plugin analyzes project dependencies and provides quality assessments by aggregating data from multiple repository and security sources.

> [!IMPORTANT]
> This plugin was developed with **AI assistance** to ensure high-fidelity data collection and modern architectural standards.

## Features
*   **Dependency Analysis:** Scans your dependency graph and identifies potential risks.
*   **Provider Integration:** Aggregates data from GitHub, Deps.dev (OpenSSF Scorecard), Libraries.io, and Maven Central.
*   **High Performance:** Broad asynchronous execution model for maximum API throughput.
*   **Structured Reporting:** HTML and JSON reports grouped by severity (Critical, Warning, Info).
*   **Custom Audits:** Flexible DSL to define your own quality gates.
*   **CI/CD Governance:** Build failure logic based on identified critical findings (ERROR level).

## Usage

### Applying the Plugin

```groovy
plugins {
    id "dev.wilhelms.gradle.lib-insight" version "<latest-published-version>"
}
```

### Configuration
The plugin comes with no hardcoded audits enabled by default. Use the following snippets in your `customAudits` block to get started.

<details open>
<summary>Example Configuration (build.gradle)</summary>

```groovy
libInsight {
    // Optional: Path to suppressions file
    suppressionFile = file("lib-insight-suppressions.json")

    customAudits {
        // 1. Stale Fork Detection
        create("forks") {
            description = "Flags forks that lag behind their upstream"
            level = "WARN"
            filter { it.github?.repo?.isFork && (it.github?.repo?.behindBy ?: 0) > 0 }
            format { "Fork is behind upstream by ${it.github.repo.behindBy} commits${it.github.repo.parentRepo != null ? \" (${it.github.repo.parentRepo})\" : \"\"}" }
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

        // 4. License Review
        create("licenseUnknown") {
            description = "Flags direct, low-adoption dependencies whose license could not be asserted"
            level = "WARN"
            filter {
                def license = it.github?.repo?.license
                it.isDirect &&
                (license == null || license == "NOASSERTION") &&
                it.librariesIo != null &&
                it.librariesIo.dependentReposCount < 5 &&
                it.librariesIo.dependentsCount < 25
            }
            format {
                def license = it.github?.repo?.license
                def repos = it.librariesIo?.dependentReposCount ?: 0
                def dependents = it.librariesIo?.dependentsCount ?: 0
                "License review needed: ${license ?: 'unknown'}; adoption ${repos} repos / ${dependents} dependents"
            }
        }

        // 5. Scorecard Maintenance Check
        create("maintainedLow") {
            description = "Highlights dependencies with poor OpenSSF Maintained scores"
            level = "WARN"
            filter { (it.depsDev?.scorecard?.checks?.get("Maintained") ?: 10) <= 2 }
            format {
                def maintained = it.depsDev?.scorecard?.checks?.get("Maintained") ?: 0
                "Maintained score is ${maintained}"
            }
        }

        // 6. Inactive Repository Warning
        create("staleRepo") {
            description = "Flags repositories without a push for more than three years"
            level = "WARN"
            filter {
                it.github?.repo?.isInactiveFor(1095) ?: false
            }
            format {
                def repo = it.github?.repo
                "Repository inactive since ${repo.pushedAt}" + (repo.stargazersCount != null ? " (${repo.stargazersCount} stars)" : "")
            }
        }

        // 7. Stale Unlicensed Repository
        create("staleUnlicensed") {
            description = "Flags stale repositories whose license cannot be asserted"
            level = "WARN"
            filter {
                def repo = it.github?.repo
                def license = repo?.license
                (repo?.isInactiveFor(1095) ?: false) &&
                (license == null || license == "NOASSERTION")
            }
            format {
                def repo = it.github?.repo
                def license = repo?.license
                "Stale repo with unknown license: ${license ?: 'unknown'}; last push ${repo.pushedAt}"
            }
        }

        // 8. Maintenance Check (Silent in console)
        create("maintenanceCheck") {
            level = "INFO"
            console = false // Only visible in HTML report
            filter { 
                def issues = it.github?.issues
                return issues != null && issues.totalIssues >= 20 && issues.healthRatio < 0.2
            }
            format { "Poor maintenance: Only ${(it.github.issues.healthRatio * 100).toInteger()}% of issues are closed" }
        }

        // 9. Niche Library Warning (using Libraries.io)
        create("nicheLibrary") {
            description = "Flags libraries with very low adoption across repositories and packages"
            level = "WARN"
            filter {
                it.librariesIo != null &&
                it.librariesIo.dependentReposCount < 5 &&
                it.librariesIo.dependentsCount < 25
            }
            format { "Niche Library: ${it.librariesIo.dependentReposCount} repos / ${it.librariesIo.dependentsCount} total dependents" }
        }
    }
}
```

In multi-project aggregate builds, `it.isDirect` is true for any dependency resolved from any project classpath in the build, not only for root-project declarations.

For convenience, date-heavy fields already expose parsed helpers, for example `it.github?.repo?.isInactiveFor(365)` and `it.mavenCentral?.isOlderThanLatest(730)`, so custom audits can stay readable without manual date parsing.

If you want a copy-paste starting point for direct governance plus transitive hygiene checks, this single configuration covers both.

```groovy
libInsight {
    customAudits {
        create("staleRepo") {
            description = "Flags repositories that have not been updated for a long time"
            level = "WARN"
            filter { it.github?.repo?.isInactiveFor(365) ?: false }
            format {
                def repo = it.github?.repo
                "No push for more than a year: ${repo?.pushed}${repo?.stargazersCount != null ? \" (${repo.stargazersCount} stars)\" : \"\"}"
            }
        }

        create("outdated") {
            description = "Highlights releases that lag behind the latest version"
            level = "WARN"
            filter { it.mavenCentral?.isOlderThanLatest(730) ?: false }
            format { "Update recommended: latest is ${it.mavenCentral.latestVersion}" }
        }

        create("staleUnlicensed") {
            description = "Flags stale repositories whose license is unclear"
            level = "WARN"
            filter {
                def repo = it.github?.repo
                (repo?.isInactiveFor(365) ?: false) && (repo?.license == null || repo.license == "NOASSERTION")
            }
            format {
                def repo = it.github?.repo
                "Stale repo with unknown license: ${repo?.license ?: 'unknown'}; last push ${repo?.pushed}"
            }
        }

        create("licenseUnknownDirect") {
            description = "Direct, low-adoption dependencies whose license could not be asserted"
            level = "WARN"
            filter {
                def license = it.github?.repo?.license
                it.isDirect &&
                (license == null || license == "NOASSERTION") &&
                it.librariesIo != null &&
                it.librariesIo.dependentReposCount < 5 &&
                it.librariesIo.dependentsCount < 25
            }
            format {
                def license = it.github?.repo?.license
                def repos = it.librariesIo?.dependentReposCount ?: 0
                def dependents = it.librariesIo?.dependentsCount ?: 0
                "License review needed: ${license ?: 'unknown'}; adoption ${repos} repos / ${dependents} dependents"
            }
        }

        create("maintainedLowDirect") {
            description = "Direct dependencies with poor OpenSSF Maintained scores"
            level = "WARN"
            filter { it.isDirect && ((it.depsDev?.scorecard?.checks?.get("Maintained") ?: 10) <= 2) }
            format {
                def maintained = it.depsDev?.scorecard?.checks?.get("Maintained") ?: 0
                "Maintained score is ${maintained}"
            }
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
| `cacheDir` | `LIB_INSIGHT_CACHE_DIR` | `~/.gradle/lib-insight-cache` | Shared metadata cache directory. Accepts an absolute path. |
| `cacheTtlDays` | - | `1` | Cache validity in days. |
| `asyncTimeoutMinutes` | - | `30` | Maximum time to wait for all asynchronous API requests. |
| `htmlReport` | - | `true` | Writes `build/reports/lib-insight/index.html`. |
| `jsonReport` | - | `true` | Writes `build/reports/lib-insight/report.json`. |
| `suppressionFile` | - | - | JSON file containing findings to ignore. |

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
You can suppress specific findings by providing a JSON file (`suppressionFile` property).

**Example: Wildcard suppression (ignore all audits for a lib)**
```json
[
  {
    "id": "com.google.code.gson:gson",
    "reason": "Internal exception",
    "tasks": ["*"]
  }
]
```

**Example: Specific version suppression**
```json
[
  {
    "id": "org.apache.logging.log4j:log4j-core:2.17.1",
    "reason": "Verified safe in our context",
    "tasks": ["securityGate"]
  }
]
```

**Example: Time-bounded suppression**
```json
[
  {
    "id": "com.example:legacy-lib",
    "reason": "Temporary exception",
    "until": "2026-12-31",
    "tasks": ["*"]
  }
]
```

## Tasks

*   `libInsightReport`: **Reporting.** Evaluates all audits and generates findings-based HTML/JSON reports.
*   `libInsightCheck`: **Governance.** Runs analysis and fails the build if any `ERROR` level findings exist.

## License
Licensed under the [Apache License, Version 2.0](LICENSE).
