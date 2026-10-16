# Kestra 2.0.x Runtime Testing Instructions

This document provides step-by-step instructions for testing the GoodData plugin on Kestra 2.0.x.

## Prerequisites

- Docker installed and running
- Built plugin JAR: `build/libs/plugin-gooddata-1.0.0-SNAPSHOT-all.jar`

## Testing Steps

### 1. Run Kestra 2.0.x with the Plugin

```bash
# Pull the latest Kestra 2.0.x image
docker pull kestra/kestra:2.0.6

# Run Kestra with the plugin mounted
docker run -d \
  --name kestra-2.0-test \
  -p 8080:8080 \
  -v $(pwd)/build/libs:/app/plugins \
  -e KESTRA_CONFIGURATION_TYPE=LOCAL \
  kestra/kestra:2.0.6 \
  server local
```

### 2. Verify Plugin Loading

Wait for Kestra to start (approximately 30-60 seconds), then check the logs:

```bash
docker logs kestra-2.0-test
```

Look for:
- ✅ `Registered N core plugins` message
- ✅ No `ServiceConfigurationError` or `NoSuchMethodError` related to the GoodData plugin
- ✅ GoodData plugin classes listed in the startup logs

### 3. Check Plugin Registration

Access the Kestra UI at http://localhost:8080 and navigate to:
- **Plugins** page (http://localhost:8080/ui/plugins)
- Verify "GoodData" plugin appears in the list
- Verify the following tasks are visible:
  - `io.kestra.plugin.gooddata.dataSources.Scan`
  - `io.kestra.plugin.gooddata.executions.Execute`
  - `io.kestra.plugin.gooddata.layouts.Export`
  - `io.kestra.plugin.gooddata.workspaces.WorkspaceList`
  - `io.kestra.plugin.gooddata.workspaces.Trigger`

### 4. Test a Simple Flow

Create a test flow in the Kestra UI:

```yaml
id: gooddata_test
namespace: test

tasks:
  - id: list_workspaces
    type: io.kestra.plugin.gooddata.workspaces.WorkspaceList
    url: "https://acme.cloud.gooddata.com"
    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
```

**Note:** This will fail with a 401/403 error if you don't have a real GoodData instance and token, but the important thing is that the plugin loads and the task type is recognized.

### 5. Expected Results

**Success Indicators:**
- Plugin loads without `NoSuchMethodError: Property.of`
- Plugin appears in the Plugins page
- Flow validation accepts the GoodData task type
- No `Invalid type` errors when creating flows

**Failure Indicators:**
- `ServiceConfigurationError: io.kestra.plugin.gooddata.* could not be instantiated`
- `NoSuchMethodError: Property.of(java.lang.Object)`
- `Invalid type: io.kestra.plugin.gooddata.workspaces.WorkspaceList` when creating flows
- Plugin does not appear in the Plugins page

### 6. Cleanup

```bash
# Stop and remove the container
docker stop kestra-2.0-test
docker rm kestra-2.0-test
```

## Troubleshooting

### Plugin fails to load

If you see `NoSuchMethodError`, check that:
- The build used `Property.ofValue()` instead of `Property.of()`
- The shadow JAR was rebuilt after code changes

### Plugin not visible in UI

Check the logs for:
- Plugin registration errors
- Class loading failures
- Version compatibility issues

## Static Analysis Confirmation

The plugin has been statically analyzed against Kestra core/model versions:
- 1.3.39
- 1.3.43
- 2.0.4
- 2.0.6

All referenced classes, methods, and fields exist in these versions. Runtime testing confirms the plugin actually loads and functions correctly.
