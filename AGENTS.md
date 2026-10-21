# Kestra GoodData Plugin

## What

- Provides integration components under `io.kestra.plugin.gooddata` to connect Kestra flows with GoodData Cloud and GoodData.CN.
- Includes tasks and triggers across four sub-packages:
  - `dataSources.Scan`: Scan data sources to discover new warehouse tables and update physical data models.
  - `executions.Execute`: Run Analytical Framework Model (AFM) queries with polling and flexible fetch/store modes.
  - `layouts.Export`: Export workspace layouts (logical data models and analytical dashboards) as declarative JSON.
  - `workspaces.WorkspaceList`: List all GoodData workspaces.
  - `workspaces.Trigger`: Polling trigger detecting workspace and layout changes using deterministic canonical JSON hashing and Kestra's KV store.

## Why

- **What user problem does this solve?** Teams need to keep GoodData semantic layers, physical data models, and analytical assets in sync with warehouse data pipelines without manual operations.
- **Why would a team adopt this plugin in a workflow?** It integrates GoodData directly into data ingestion and transformation DAGs (e.g. scanning a data source right after dbt/Airbyte runs, validating metrics, or versioning workspace layouts in Git).
- **What operational/business outcome does it enable?** Automates semantic-layer updates, guarantees fresh analytics after data loads, prevents broken dashboards, and treats analytics infrastructure as code.

## How

### Architecture

Single-module plugin structured into four `DATA` sub-groups under `io.kestra.plugin.gooddata`:

- `io.kestra.plugin.gooddata.dataSources`
- `io.kestra.plugin.gooddata.executions`
- `io.kestra.plugin.gooddata.layouts`
- `io.kestra.plugin.gooddata.workspaces`

All tasks inherit from `AbstractGooddata` for shared URL normalization, Bearer token handling, path encoding, timeout management, and unified HTTP execution via Kestra's `io.kestra.core.http.client.HttpClient`.

### Key Plugin Classes

- `io.kestra.plugin.gooddata.AbstractGooddata`: Base class providing connection properties, path encoding, URL validation, and Kestra HTTP client execution.
- `io.kestra.plugin.gooddata.GooddataApiException`: Typed exception with HTTP status codes and remediation hints.
- `io.kestra.plugin.gooddata.dataSources.Scan`: Scans a data source (`POST /api/v1/actions/dataSources/{id}/scan`).
- `io.kestra.plugin.gooddata.executions.Execute`: Executes AFM analytics queries (`POST /api/v1/actions/workspaces/{id}/execution/afm/execute`), polls execution results, and supports `FETCH`, `FETCH_ONE`, or `STORE`.
- `io.kestra.plugin.gooddata.layouts.Export`: Exports declarative workspace layout JSON (`GET /api/v1/layout/workspaces/{id}`).
- `io.kestra.plugin.gooddata.workspaces.WorkspaceList`: Retrieves available workspaces (`GET /api/v1/entities/workspaces`).
- `io.kestra.plugin.gooddata.workspaces.Trigger`: Polling trigger that detects layout/workspace changes using canonicalized JSON SHA-256 watermarks stored in Kestra's namespace KV Store.

### Project Structure

```
plugin-gooddata/
├── src/main/java/io/kestra/plugin/gooddata/
│   ├── AbstractGooddata.java
│   ├── GooddataApiException.java
│   ├── dataSources/
│   │   ├── package-info.java
│   │   └── Scan.java
│   ├── executions/
│   │   ├── package-info.java
│   │   └── Execute.java
│   ├── layouts/
│   │   ├── package-info.java
│   │   └── Export.java
│   └── workspaces/
│       ├── package-info.java
│       ├── WorkspaceList.java
│       └── Trigger.java
├── src/main/resources/
│   ├── doc/io.kestra.plugin.gooddata.md
│   ├── icons/
│   └── metadata/index.yaml
├── src/test/java/io/kestra/plugin/gooddata/
│   └── GooddataTasksTest.java
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Use Kestra's internal `HttpClient` with connection timeouts and redirect protection.
- Ensure all task properties use Kestra's dynamic `Property<T>` wrapper with property groups and Jakarta validation annotations.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- https://www.gooddata.com/docs/cloud/api-and-sdk/api/
