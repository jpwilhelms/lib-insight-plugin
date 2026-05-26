# Library Insight JSON Schema

The Library Insight plugin produces two types of machine-readable reports. This document describes their structure and the available fields for use in custom audits.

## 1. Exhaustive Metadata (`lib-insight.json`)
Located at: `build/lib-insight/data/lib-insight.json`

This file contains the complete set of metadata collected for every dependency.

### Top-Level Structure (`LibMetric`)

| Property | Type | Description |
| :--- | :--- | :--- |
| `id` | `String` | GAV coordinates without version (`group:artifact`) |
| `version` | `String` | The used version string |
| `gradleInsight` | `String` | Internal marker used by the plugin (`findings`) |
| `isDirect` | `Boolean` | True if resolved from any project classpath in the build (aggregate multi-project semantics) |
| `suppressions` | `List<Suppression>` | Suppression rules attached to this dependency |
| `pom` | `Object` | Maven POM metadata used for source and license discovery |
| `mavenCentral`| `Object?` | Data from Maven Central (see below) |
| `github` | `Object?` | Data from GitHub (see below) |
| `depsDev` | `Object?` | Data from Google deps.dev (see below) |
| `librariesIo` | `Object?` | Data from Libraries.io (see below) |
| `cachedAt` | `String` | ISO-8601 timestamp of when the entry was collected |

**Helper Methods:**
*   `cacheTime`: Parsed `Instant?` derived from `cachedAt`.

---

### Suppressions (`suppressions[]`)

| Property | Type | Description |
| :--- | :--- | :--- |
| `id` | `String` | Dependency identifier (`group:artifact` or `group:artifact:version`) |
| `reason` | `String` | Human-readable justification |
| `until` | `String?` | Optional expiry date/time in ISO-8601 date or instant format |
| `tasks` | `List<String>?` | Audit names to suppress, or `["*"]` for all audits |

---

### POM Metadata (`pom`)

| Property | Type | Description |
| :--- | :--- | :--- |
| `url` | `String` | POM URL used by the analyzer |
| `license` | `String?` | License name extracted from the POM |
| `scmUrl` | `String?` | SCM URL extracted from the POM |

---

### Maven Central (`mavenCentral`)

| Property | Type | Description |
| :--- | :--- | :--- |
| `currentVersionReleaseDate` | `String?` | Release date of the currently used version |
| `latestVersionReleaseDate` | `String?` | Release date of the newest available version by version ordering |
| `latestVersion` | `String?` | The highest version string found in Maven Central |
| `releaseCount` | `Int` | Total number of versions released |

**Helper Methods:**
*   `isOlderThanLatest(days: Int)`: Returns true if the used version is older than the latest version by at least X days.

---

### GitHub (`github.repo`)

| Property | Type | Description |
| :--- | :--- | :--- |
| `stargazersCount`| `Int` | Number of GitHub stars |
| `pushed` | `Instant?` | Date of the last commit |
| `updated` | `Instant?` | Date the repository metadata was last changed |
| `created` | `Instant?` | Date the repository was created |
| `isFork` | `Boolean` | True if the project is a fork |
| `isArchived` | `Boolean` | True if the repository is archived (read-only) |
| `behindBy` | `Int?` | Commits behind upstream (only for forks) |
| `aheadBy` | `Int?` | Commits ahead of upstream (only for forks) |
| `license` | `String?` | SPDX identifier of the license |

**Helper Methods:**
*   `isInactiveFor(days: Int)`: Returns true if the last commit was more than X days ago.

The parsed properties `created`, `updated`, and `pushed` are available as `Instant?`, so custom audits can compare against them directly without manual parsing.

---

### GitHub Issues (`github.issues`)

| Property | Type | Description |
| :--- | :--- | :--- |
| `totalIssues` | `Int` | Total number of issues (open + closed) |
| `openIssues` | `Int` | Currently open issues |
| `closedIssues` | `Int` | Currently closed issues |
| `healthRatio` | `Double` | Ratio of closed issues to total issues (0.0 to 1.0) |

### GitHub Activity (`github.activity`)

Currently present in the schema as `null`.

---

### Deps.dev (`depsDev`)

| Property | Type | Description |
| :--- | :--- | :--- |
| `scorecard.overallScore`| `Double` | OpenSSF Scorecard (0.0 to 10.0) |
| `scorecard.checks` | `Map<String, Int>`| Individual check scores (e.g., "Maintained": 10) |
| `dependentsCount` | `Int?` | Number of other projects depending on this one |
| `advisoriesCount` | `Int` | Number of security advisories associated with this version |
| `systems` | `List<String>` | Known dependency systems (currently usually empty) |

---

### Libraries.io (`librariesIo`)

| Property | Type | Description |
| :--- | :--- | :--- |
| `sourcerank` | `Int` | Combined popularity and quality score |
| `dependentReposCount` | `Int` | Number of repositories depending on this project |
| `dependentsCount` | `Int` | Total number of dependents (including packages) |
| `sourcerankBreakdown` | `Map<String, Int>` | Detailed components of the SourceRank score |

---

## 2. Findings Report (`report.json`)
Located at: `build/reports/lib-insight/report.json`

This file is a filtered view of the metadata, containing only items with identified findings.

### Structure
```json
[
  {
    "metric": { ... exhaustive LibMetric object ... },
    "findings": [
      {
        "type": "AUDIT:customName",
        "level": "ERROR", 
        "message": "Custom formatted message",
        "console": true
      }
    ]
  }
]
```

**Level values:**
*   `ERROR`: Critical finding, will trigger build failure in `libInsightCheck`.
*   `WARN`: Warning, visible in reports but non-blocking.
*   `INFO`: Informational finding, grouped in the lowest priority section.
