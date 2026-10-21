This plugin integrates [GoodData](https://www.gooddata.com/) with [Kestra](https://kestra.io/),
letting you automate data-source scanning, analytics query execution, declarative workspace layout
export, and workspace change monitoring as part of your data pipelines.

## Limitations

- **Response body size**: each GoodData API response is limited to 10,000,000 characters (approximately 10 MB). The limit applies to every task and to the trigger (`Scan`, `Execute`, `Export`, `WorkspaceList` and `Trigger`), and is checked per HTTP response, so for `WorkspaceList` and the tenant-wide `Trigger` it applies to each page. The response is read in full before the check, so the limit bounds the parsing and storage steps that follow, not the read itself. A response over the limit fails the task or trigger evaluation.
  To stay under it, narrow the AFM request in `Execute` (fewer attributes, measures, or tighter filters), or split a large workspace before running `Export`.

## Authentication

All GoodData tasks and triggers communicate with the GoodData REST API using Bearer-token authentication.

- **URL (`url`)**: The base URL of your GoodData instance (e.g., `https://acme.cloud.gooddata.com` for GoodData Cloud or `https://gooddata.mycompany.com` for self-hosted GoodData.CN).
- **API Token (`apiToken`)**: A personal API token generated in the GoodData administration console. Always store this value using Kestra secrets rather than hardcoding it:

```yaml
url: "https://acme.cloud.gooddata.com"
apiToken: "{{ secret('GOODDATA_TOKEN') }}"
```

- **Request Timeout (`requestTimeout`)**: Optional HTTP request timeout duration (default: `PT60S`).

## Tasks and Triggers

### `dataSources.Scan`

Triggers a schema scan on a GoodData data source via
`POST /api/v1/actions/dataSources/{dataSourceId}/scan`.

**Outputs**

| Field | Type | Description |
|---|---|---|
| `tablesFound` | `int` | Number of tables and views discovered by the scan |
| `uri` | `URI` | Kestra internal file URI pointing to the raw JSON scan response |

### `executions.Execute`

Executes an AFM (Analytical Framework Model) query against a GoodData workspace via
`POST /api/v1/actions/workspaces/{workspaceId}/execution/afm/execute`. Polls the execution result endpoint until computation finishes and processes results according to `fetchType`.

**Configuration Options**

- `afmRequest`: Complete AFM query specification in JSON format.
- `fetchType`: Processing mode for execution results:
  - `STORE` (default): Writes full result JSON to Kestra internal storage.
  - `FETCH`: Returns parsed data rows in `rows`.
  - `FETCH_ONE`: Returns the first data row in `row`.
  - `NONE`: Computes without storing or fetching row data.
- `pollInterval`: Polling interval between status checks (default: `PT1S`, minimum `PT0.1S`).
- `maxPollDuration`: Maximum duration to poll for AFM calculation completion (default: `PT60S`).
- `requestTimeout`: Optional HTTP request timeout duration for individual API calls (default: `PT60S`).

**Outputs**

| Field | Type | Description |
|---|---|---|
| `executionResultId` | `String` | GoodData execution result ID from `executionResponse.links.executionResult` |
| `uri` | `URI` | Kestra internal file URI pointing to the full AFM execute result JSON (when `fetchType` is `STORE`) |
| `rows` | `List<Object>` | List of result rows (when `fetchType` is `FETCH`) |
| `row` | `Object` | First result row (when `fetchType` is `FETCH_ONE`) |
| `size` | `Integer` | Number of data rows returned |

### `layouts.Export`

Exports the full declarative workspace layout (Logical Data Model + analytics model) via
`GET /api/v1/layout/workspaces/{workspaceId}`.

**Outputs**

| Field | Type | Description |
|---|---|---|
| `datasetCount` | `int` | Count of LDM datasets found in the exported layout |
| `uri` | `URI` | Kestra internal file URI containing the raw JSON workspace layout |

### `workspaces.WorkspaceList`

Lists all workspaces accessible to the API token via
`GET /api/v1/entities/workspaces`. Follows pagination links when multiple pages exist.

**Configuration Options**

- `maxPages`: Maximum number of pagination pages to fetch (minimum `1`, maximum `1000`, default: `50`).
- `requestTimeout`: Optional HTTP request timeout duration for individual API calls (default: `PT60S`).

**Outputs**

| Field | Type | Description |
|---|---|---|
| `count` | `int` | Number of workspaces returned |
| `uri` | `URI` | Kestra internal file URI containing the raw JSON workspaces list |
| `truncated` | `Boolean` | Whether results were truncated because maxPages was reached |

### `workspaces.Trigger`

Polling trigger that monitors GoodData for workspace or declarative layout changes. Computes a SHA-256 fingerprint of the remote state and compares it to a persisted watermark stored in Kestra's namespace KV Store. Watermark keys are scoped per flow and trigger to prevent cross-flow collisions.

**Configuration Options**

- `workspaceId`: Optional workspace ID to monitor. If omitted, monitors the tenant-wide workspace list.
- `interval`: Polling interval (e.g. `PT5M`).
- `onStartup`: Whether to trigger on initial poll when no prior watermark exists (default: `false` to seed watermark silently).

**Outputs**

| Field | Type | Description |
|---|---|---|
| `watermark` | `String` | Updated SHA-256 fingerprint representing the detected state |
| `workspaceId` | `String` | Monitored workspace ID, if configured |
| `uri` | `URI` | Kestra internal file URI pointing to the detected state JSON |

## Example Pipeline

```yaml
id: gooddata_pipeline
namespace: company.team

tasks:
  - id: scan
    type: io.kestra.plugin.gooddata.dataSources.Scan
    url: "https://acme.cloud.gooddata.com"
    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
    dataSourceId: "warehouse"

  - id: export_layout
    type: io.kestra.plugin.gooddata.layouts.Export
    url: "https://acme.cloud.gooddata.com"
    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
    workspaceId: "sales"

  - id: list_workspaces
    type: io.kestra.plugin.gooddata.workspaces.WorkspaceList
    url: "https://acme.cloud.gooddata.com"
    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
```
