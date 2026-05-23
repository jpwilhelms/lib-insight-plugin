# Library Insight Gradle Plugin

The Library Insight plugin analyzes project dependencies and provides quality assessments by aggregating data from multiple repository and security sources.

## Features
*   **Dependency Analysis:** Evaluates runtime dependencies, including transitive relations.
*   **Data Integration:**
    *   **Maven Central:** Retrieves release history and POM metadata.
    *   **GitHub:** Collects repository metrics including stars, issue statistics, and activity status.
    *   **Libraries.io:** Provides popularity metrics and SourceRank assessments.
    *   **Deps.dev:** Integrates OpenSSF Scorecard data for security evaluations.
*   **Operational Capabilities:**
    *   Persistent disk caching to optimize repeat executions.
    *   API rate limiting and error handling for reliable data retrieval.
    *   Configurable cache TTL and storage locations.
*   **Reporting:** Generates structured JSON output for integration into audit workflows.

## Usage

### 1. Apply the Plugin
Add the following to your `build.gradle.kts`:

```kotlin
plugins {
    id("info.wilhelms.gradle.lib-insight") version "1.0.0"
}
```

### 2. Configuration
The plugin is configured via the `libInsight` extension. API tokens are recommended to ensure higher rate limits and access to detailed metrics.

```kotlin
libInsight {
    // API tokens for repository metadata
    gitHubToken.set(System.getenv("GITHUB_TOKEN"))
    librariesIoToken.set(System.getenv("LIBRARIES_IO_TOKEN"))
    
    // Optional: Configure cache duration (default: 1 day)
    cacheTtlDays.set(7)
    
    // Optional: Threshold for identifying abandoned projects (in days)
    abandonedThresholdDays.set(730)
}
```

### 3. Generate Reports
Execute the report generation task:
```bash
./gradlew generateLibQualityReport
```
The resulting JSON report is stored in `build/reports/lib-insight/report.json`.

### 4. Specialized Audits
The plugin includes pre-configured audit tasks for quick assessments:

```bash
# Identify projects with low activity
./gradlew auditAbandoned

# Identify forked repositories and track upstream status
./gradlew auditForks
```

## Metrics and Evaluation
The plugin provides objective data points from independent sources to support supply chain risk management and dependency auditing.

## Development & Attribution
This project is developed using a hybrid approach, leveraging AI-assisted software engineering (via Gemini) for implementation, documentation, and maintenance. All AI-generated contributions are subject to manual review and verification to ensure technical integrity and adherence to quality standards.

## License
Licensed under the [Apache License, Version 2.0](LICENSE).
