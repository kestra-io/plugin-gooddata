package io.kestra.plugin.gooddata;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.gooddata.dataSources.Scan;
import io.kestra.plugin.gooddata.executions.Execute;
import io.kestra.plugin.gooddata.layouts.Export;
import io.kestra.plugin.gooddata.workspaces.WorkspaceList;
import io.kestra.plugin.gooddata.workspaces.Trigger;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
public class GooddataTasksTest {

    @Inject
    protected RunContextFactory runContextFactory;

    private WireMockServer wireMockServer;
    private String serverUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
        serverUrl = wireMockServer.baseUrl();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void scanDataSourceTaskBuilder() {
        Scan scan = Scan.builder()
            .id("scan-test")
            .url(Property.ofValue("https://acme.cloud.gooddata.com"))
            .apiToken(Property.ofValue("secret-token"))
            .dataSourceId(Property.ofValue("postgres-source"))
            .build();

        assertThat(scan.getId(), is("scan-test"));
        assertThat(scan.getUrl(), notNullValue());
        assertThat(scan.getApiToken(), notNullValue());
        assertThat(scan.getDataSourceId(), notNullValue());
    }

    @Test
    void executeAfmTaskBuilder() {
        Execute execute = Execute.builder()
            .id("exec-test")
            .url(Property.ofValue("https://acme.cloud.gooddata.com"))
            .apiToken(Property.ofValue("secret-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .build();

        assertThat(execute.getId(), is("exec-test"));
        assertThat(execute.getWorkspaceId(), notNullValue());
        assertThat(execute.getAfmRequest(), notNullValue());
    }

    @Test
    void exportWorkspaceLayoutTaskBuilder() {
        Export export = Export.builder()
            .id("export-test")
            .url(Property.ofValue("https://acme.cloud.gooddata.com"))
            .apiToken(Property.ofValue("secret-token"))
            .workspaceId(Property.ofValue("marketing-workspace"))
            .build();

        assertThat(export.getId(), is("export-test"));
        assertThat(export.getWorkspaceId(), notNullValue());
    }

    @Test
    void listWorkspacesTaskBuilder() {
        WorkspaceList list = WorkspaceList.builder()
            .id("list-test")
            .url(Property.ofValue("https://acme.cloud.gooddata.com"))
            .apiToken(Property.ofValue("secret-token"))
            .build();

        assertThat(list.getId(), is("list-test"));
        assertThat(list.getUrl(), notNullValue());
    }

    @Test
    void triggerBuilder() {
        Trigger trigger = Trigger.builder()
            .id("trigger-test")
            .url(Property.ofValue("https://acme.cloud.gooddata.com"))
            .apiToken(Property.ofValue("secret-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .interval(Duration.ofMinutes(10))
            .build();

        assertThat(trigger.getId(), is("trigger-test"));
        assertThat(trigger.getInterval(), is(Duration.ofMinutes(10)));
        assertThat(trigger.getWorkspaceId(), notNullValue());
    }

    @Test
    void testScanSuccess() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/dataSources/postgres-source/scan"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"pdm\": {\"tables\": [{\"id\": \"t1\"}, {\"id\": \"t2\"}]}}")));

        Scan scan = Scan.builder()
            .id("scan-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .dataSourceId(Property.ofValue("postgres-source"))
            .build();

        RunContext runContext = runContextFactory.of();
        Scan.Output output = scan.run(runContext);

        assertThat(output.getTablesFound(), is(2));
        assertThat(output.getUri(), notNullValue());
    }

    @Test
    void testScanFailureNon2xx() {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/dataSources/missing-source/scan"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Data source not found\"}")));

        Scan scan = Scan.builder()
            .id("scan-fail")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .dataSourceId(Property.ofValue("missing-source"))
            .build();

        RunContext runContext = runContextFactory.of();
        GooddataApiException exception = assertThrows(GooddataApiException.class, () -> scan.run(runContext));
        assertThat(exception.getStatusCode(), is(404));
        assertThat(exception.getMessage(), containsString("data source, or workspace ID exists"));
    }

    @Test
    void testExportSuccess() throws Exception {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/layout/workspaces/sales-workspace"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ldm\": {\"datasets\": [{\"id\": \"ds1\"}, {\"id\": \"ds2\"}, {\"id\": \"ds3\"}]}}")));

        Export export = Export.builder()
            .id("export-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .build();

        RunContext runContext = runContextFactory.of();
        Export.Output output = export.run(runContext);

        assertThat(output.getDatasetCount(), is(3));
        assertThat(output.getUri(), notNullValue());
    }

    @Test
    void testExportUnauthorized() {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/layout/workspaces/sales-workspace"))
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Unauthorized\"}")));

        Export export = Export.builder()
            .id("export-fail")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("bad-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .build();

        RunContext runContext = runContextFactory.of();
        GooddataApiException exception = assertThrows(GooddataApiException.class, () -> export.run(runContext));
        assertThat(exception.getStatusCode(), is(401));
        assertThat(exception.getMessage(), containsString("valid and not expired"));
    }

    @Test
    void testListWorkspacesSuccess() throws Exception {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?sort=id"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w1\"}, {\"id\": \"w2\"}]}")));

        WorkspaceList list = WorkspaceList.builder()
            .id("list-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        WorkspaceList.Output output = list.run(runContext);

        assertThat(output.getCount(), is(2));
        assertThat(output.getUri(), notNullValue());
    }

    @Test
    void testExecuteAfmWithPollingAndFetch() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res123\"}}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res123"))
            .inScenario("Polling")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\": \"RUNNING\"}"))
            .willSetStateTo("COMPLETED"));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res123"))
            .inScenario("Polling")
            .whenScenarioStateIs("COMPLETED")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [[\"100\"], [\"200\"]]}")));

        Execute execute = Execute.builder()
            .id("exec-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        RunContext runContext = runContextFactory.of();
        Execute.Output output = execute.run(runContext);

        assertThat(output.getExecutionResultId(), is("res123"));
        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), notNullValue());
        assertThat(output.getRows().size(), is(2));
    }

    @Test
    void testExecuteAfmStore() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res456\"}}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res456"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [[\"500\"]]}")));

        Execute execute = Execute.builder()
            .id("exec-store-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        RunContext runContext = runContextFactory.of();
        Execute.Output output = execute.run(runContext);

        assertThat(output.getExecutionResultId(), is("res456"));
        assertThat(output.getUri(), notNullValue());
    }

    @Test
    void testTriggerWatermarkFirstPollAndChange() throws Exception {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/layout/workspaces/sales-workspace"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ldm\": {\"datasets\": [{\"id\": \"d1\"}]}}")));

        String flowId = "flow-" + IdUtils.create();
        String triggerId = "trigger-" + IdUtils.create();

        Trigger trigger = Trigger.builder()
            .id(triggerId)
            .type(Trigger.class.getName())
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("secret-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .build();

        Flow flow = Flow.builder()
            .tenantId("main")
            .namespace("company.team")
            .id(flowId)
            .build();

        TriggerContext triggerContext = TriggerContext.builder()
            .tenantId("main")
            .namespace("company.team")
            .flowId(flowId)
            .triggerId(triggerId)
            .build();

        RunContext runContext = runContextFactory.of(flow, trigger);
        runContext = runContextFactory.initializer().forScheduler((DefaultRunContext) runContext, triggerContext, trigger);
        ConditionContext conditionContext = ConditionContext.builder().runContext(runContext).flow(flow).build();

        // 1. Initial poll seeds watermark and does not fire
        Optional<Execution> firstPoll = trigger.evaluate(conditionContext, triggerContext);
        assertThat(firstPoll.isPresent(), is(false));

        // 2. Second poll with unchanged content does not fire
        Optional<Execution> secondPoll = trigger.evaluate(conditionContext, triggerContext);
        assertThat(secondPoll.isPresent(), is(false));

        // 3. Changed layout fires execution
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/layout/workspaces/sales-workspace"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ldm\": {\"datasets\": [{\"id\": \"d1\"}, {\"id\": \"d2\"}]}}")));

        Optional<Execution> thirdPoll = trigger.evaluate(conditionContext, triggerContext);
        assertThat(thirdPoll.isPresent(), is(true));
        assertThat(thirdPoll.get().getTrigger(), notNullValue());

        // 4. Non-2xx response throws exception rather than being swallowed
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/layout/workspaces/sales-workspace"))
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Server failure\"}")));

        assertThrows(GooddataApiException.class, () -> trigger.evaluate(conditionContext, triggerContext));
    }

    @Test
    void testExecuteAfmPollingTimeout() {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-timeout\"}}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-timeout"))
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\": \"RUNNING\"}")));

        Execute execute = Execute.builder()
            .id("exec-timeout-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .maxPollDuration(Property.ofValue(Duration.ofMillis(300)))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        RunContext runContext = runContextFactory.of();
        TimeoutException exception = assertThrows(TimeoutException.class, () -> execute.run(runContext));
        assertThat(exception.getMessage(), containsString("AFM execution did not complete within timeout"));
    }

    @Test
    void testExecuteAfmAbsoluteLinkRejectedOnForeignHost() {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"https://evil.attacker.com/leak/result\"}}}")));

        Execute execute = Execute.builder()
            .id("exec-ssrf-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .build();

        RunContext runContext = runContextFactory.of();
        SecurityException exception = assertThrows(SecurityException.class, () -> execute.run(runContext));
        assertThat(exception.getMessage(), containsString("Untrusted URI in GoodData response"));
    }

    @Test
    void testExecuteAfmAbsoluteLinkAllowedOnSameHost() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"" + serverUrl + "/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-absolute\"}}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-absolute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [[\"val-absolute\"]]}")));

        Execute execute = Execute.builder()
            .id("exec-abs-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        Execute.Output output = execute.run(runContext);

        assertThat(output.getExecutionResultId(), is("res-absolute"));
        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), notNullValue());
    }

    @Test
    void testExecuteAfmFetchOne() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-fetch-one\"}}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-fetch-one"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [[\"first_row\"], [\"second_row\"]]}")));

        Execute execute = Execute.builder()
            .id("exec-fetch-one-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        RunContext runContext = runContextFactory.of();
        Execute.Output output = execute.run(runContext);

        assertThat(output.getExecutionResultId(), is("res-fetch-one"));
        assertThat(output.getSize(), is(2));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRows(), nullValue());
    }

    @Test
    void testExecuteAfmDirectResponse() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [[\"direct_val\"]]}")));

        Execute execute = Execute.builder()
            .id("exec-direct-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        Execute.Output output = execute.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), notNullValue());
        assertThat(output.getExecutionResultId(), is(""));
    }

    @Test
    void testExecuteAfmUnexpectedPollStatus() {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-unexpected\"}}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-unexpected"))
            .willReturn(aResponse()
                .withStatus(204)
                .withHeader("Content-Type", "application/json")
                .withBody("")));

        Execute execute = Execute.builder()
            .id("exec-unexpected-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        RunContext runContext = runContextFactory.of();
        GooddataApiException exception = assertThrows(GooddataApiException.class, () -> execute.run(runContext));
        assertThat(exception.getStatusCode(), is(204));
        assertThat(exception.getMessage(), containsString("Unexpected HTTP status during GoodData AFM result polling: 204"));
    }

    @Test
    void testTriggerWatermarkIgnoresKeyOrderingAndFormatting() {
        String json1 = "{\n  \"b\": 2,\n  \"a\": 1,\n  \"c\": [\"x\", \"y\"]\n}";
        String json2 = "{\"c\":[\"x\",\"y\"],\"a\":1,\"b\":2}";

        String canonical1 = Trigger.canonicalizeJson(json1);
        String canonical2 = Trigger.canonicalizeJson(json2);

        assertThat(canonical1, is(canonical2));
        assertThat(canonical1, is("{\"a\":1,\"b\":2,\"c\":[\"x\",\"y\"]}"));
    }

    @Test
    void testExecuteAfmWithBareResultId() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"res-bare-123\"}}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-bare-123"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [[\"bare_val_1\", \"bare_val_2\"]]}")));

        Execute execute = Execute.builder()
            .id("exec-bare-id-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        Execute.Output output = execute.run(runContext);

        assertThat(output.getExecutionResultId(), is("res-bare-123"));
        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), notNullValue());
        assertThat(output.getRows().size(), is(1));
    }

    @Test
    void testListWorkspacesPagination() throws Exception {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?sort=id"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w1\"}], \"links\": {\"next\": \"/api/v1/entities/workspaces?page=1\"}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?page=1"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w2\"}, {\"id\": \"w3\"}], \"links\": {}}")));

        WorkspaceList list = WorkspaceList.builder()
            .id("list-pagination-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        WorkspaceList.Output output = list.run(runContext);

        assertThat(output.getCount(), is(3));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getTruncated(), is(false));
    }

    @Test
    void testListWorkspacesForeignNextLinkRejected() {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?sort=id"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w1\"}], \"links\": {\"next\": \"https://evil.example.com/api/v1/entities/workspaces\"}}")));

        WorkspaceList list = WorkspaceList.builder()
            .id("list-ssrf-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        SecurityException ex = assertThrows(SecurityException.class, () -> list.run(runContext));
        assertThat(ex.getMessage(), containsString("Untrusted URI in GoodData response"));
    }

    @Test
    void testTriggerWatermarkIgnoresLinksAndVolatileTimestamps() {
        String json1 = "{\"ldm\": {\"datasets\": [{\"id\": \"d1\"}]}, \"links\": {\"self\": \"https://host1/layout?page=0\"}, \"generatedAt\": \"2026-10-16T10:00:00Z\"}";
        String json2 = "{\"links\": {\"self\": \"https://host2/layout?token=abc\"}, \"ldm\": {\"datasets\": [{\"id\": \"d1\"}]}, \"generatedAt\": \"2026-10-16T10:05:00Z\"}";

        String canonical1 = Trigger.canonicalizeJson(json1);
        String canonical2 = Trigger.canonicalizeJson(json2);

        assertThat(canonical1, is(canonical2));
        assertThat(canonical1, is("{\"ldm\":{\"datasets\":[{\"id\":\"d1\"}]}}"));
    }

    @Test
    void testExecuteAfmWithTrailingSlashInResultLink() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-slash/\"}}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/res-slash"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [[\"val_slash\"]]}")));

        Execute execute = Execute.builder()
            .id("exec-slash-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        Execute.Output output = execute.run(runContext);

        assertThat(output.getExecutionResultId(), is("res-slash"));
        assertThat(output.getSize(), is(1));
    }

    @Test
    void testExecuteAfmWithDotDotResultIdThrows() {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/actions/workspaces/sales-workspace/execution/afm/execute"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"executionResponse\": {\"links\": {\"executionResult\": \"/api/v1/actions/workspaces/sales-workspace/execution/afm/execute/result/..\"}}}")));

        Execute execute = Execute.builder()
            .id("exec-dotdot-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("sales-workspace"))
            .afmRequest(Property.ofValue("{\"execution\": {}}"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> execute.run(runContext));
        assertThat(ex.getMessage(), containsString("Invalid GoodData executionResult identifier"));
    }

    @Test
    void testListWorkspacesCircularPaginationHalts() throws Exception {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?sort=id"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w1\"}], \"links\": {\"next\": \"/api/v1/entities/workspaces?sort=id\"}}")));

        WorkspaceList list = WorkspaceList.builder()
            .id("list-circular-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        WorkspaceList.Output output = list.run(runContext);

        assertThat(output.getCount(), is(1));
        assertThat(output.getTruncated(), is(false));
    }

    @Test
    void testListWorkspacesTruncatedWhenMaxPagesReached() throws Exception {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?sort=id"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w1\"}], \"links\": {\"next\": \"/api/v1/entities/workspaces?page=1\"}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?page=1"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w2\"}], \"links\": {\"next\": \"/api/v1/entities/workspaces?page=2\"}}")));

        WorkspaceList list = WorkspaceList.builder()
            .id("list-truncation-test")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .maxPages(Property.ofValue(1))
            .build();

        RunContext runContext = runContextFactory.of();
        WorkspaceList.Output output = list.run(runContext);

        assertThat(output.getCount(), is(1));
        assertThat(output.getTruncated(), is(true));
    }

    @Test
    void testListWorkspacesMaxPagesZeroThrows() {
        WorkspaceList list = WorkspaceList.builder()
            .id("list-invalid-max-pages")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .maxPages(Property.ofValue(0))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> list.run(runContext));
        assertThat(ex.getMessage(), containsString("'maxPages' must be greater than or equal to 1"));
    }

    @Test
    void testTriggerWorkspaceListMultiPageWatermark() throws Exception {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?sort=id"))
            .withHeader("Authorization", equalTo("Bearer secret-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w1\"}], \"links\": {\"next\": \"/api/v1/entities/workspaces?page=1\"}}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?page=1"))
            .withHeader("Authorization", equalTo("Bearer secret-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w2\"}], \"links\": {}}")));

        String flowId = "flow-ws-list-" + IdUtils.create();
        String triggerId = "trigger-ws-list-" + IdUtils.create();

        Trigger trigger = Trigger.builder()
            .id(triggerId)
            .type(Trigger.class.getName())
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("secret-token"))
            .build();

        Flow flow = Flow.builder()
            .tenantId("main")
            .namespace("company.team")
            .id(flowId)
            .build();

        TriggerContext triggerContext = TriggerContext.builder()
            .tenantId("main")
            .namespace("company.team")
            .flowId(flowId)
            .triggerId(triggerId)
            .build();

        RunContext runContext = runContextFactory.of(flow, trigger);
        runContext = runContextFactory.initializer().forScheduler((DefaultRunContext) runContext, triggerContext, trigger);
        ConditionContext conditionContext = ConditionContext.builder().runContext(runContext).flow(flow).build();

        // 1. Initial poll seeds watermark
        Optional<Execution> firstPoll = trigger.evaluate(conditionContext, triggerContext);
        assertThat(firstPoll.isEmpty(), is(true));

        // 2. Second poll with same pages returns empty
        Optional<Execution> secondPoll = trigger.evaluate(conditionContext, triggerContext);
        assertThat(secondPoll.isEmpty(), is(true));

        // 3. Update page 2 by adding w3 - watermark must change and trigger must fire
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/entities/workspaces?page=1"))
            .withHeader("Authorization", equalTo("Bearer secret-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"id\": \"w2\"}, {\"id\": \"w3\"}], \"links\": {}}")));

        Optional<Execution> thirdPoll = trigger.evaluate(conditionContext, triggerContext);
        assertThat(thirdPoll.isPresent(), is(true));
        assertThat(thirdPoll.get().getTrigger(), notNullValue());
    }

    @Test
    void testResponseBodyExceedsHardCapThrows() {
        var largeBody = "x".repeat(AbstractGooddata.MAX_RESPONSE_BODY_CHARS + 1);
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/layout/workspaces/large-workspace"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(largeBody)));

        Export export = Export.builder()
            .id("export-large")
            .url(Property.ofValue(serverUrl))
            .apiToken(Property.ofValue("test-token"))
            .workspaceId(Property.ofValue("large-workspace"))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> export.run(runContext));
        assertThat(ex.getMessage(), containsString("exceeds the maximum permitted limit"));
    }
}
