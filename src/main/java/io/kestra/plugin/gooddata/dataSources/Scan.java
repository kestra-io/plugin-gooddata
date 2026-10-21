package io.kestra.plugin.gooddata.dataSources;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.gooddata.AbstractGooddata;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Scan a GoodData data source",
    description = """
        Triggers a schema scan on the specified GoodData data source by calling \
        `POST /api/v1/actions/dataSources/{dataSourceId}/scan`. \
        Returns the number of tables/views discovered and stores the raw scan JSON. \
        Response bodies are limited to 10,000,000 characters (approximately 10 MB).
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Scan a GoodData data source",
            full = true,
            code = """
                id: datasources_scan
                namespace: company.team

                tasks:
                  - id: scan
                    type: io.kestra.plugin.gooddata.dataSources.Scan
                    url: "https://acme.cloud.gooddata.com"
                    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
                    dataSourceId: warehouse
                """
        )
    }
)
public class Scan extends AbstractGooddata implements RunnableTask<Scan.Output> {

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Schema(
        title = "Data source ID",
        description = "The identifier of the GoodData data source to scan."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> dataSourceId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rUrl = validateAndNormalizeUrl(runContext.render(url).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'url' must be provided")));
        var rToken = getRenderedApiToken(runContext);
        var rDsId = runContext.render(dataSourceId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'dataSourceId' must be provided"));

        var encodedDsId = encodePath(rDsId);
        var scanUrl = rUrl + "/api/v1/actions/dataSources/" + encodedDsId + "/scan";
        logger.info("Scanning GoodData data source '{}' at {}", rDsId, scanUrl);

        var request = HttpRequest.builder()
            .uri(URI.create(scanUrl))
            .method("POST")
            .addHeader("Authorization", "Bearer " + rToken)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .body(HttpRequest.StringRequestBody.builder()
                .content("{}")
                .contentType("application/json")
                .charset(StandardCharsets.UTF_8)
                .build())
            .build();

        var response = executeRequest(runContext, request);
        var rawJson = response.getBody() != null ? response.getBody() : "{}";

        // Count tables: canonical GoodData Cloud response shape is {"pdm":{"tables":[...]}}
        // Reference: GoodData Cloud OpenAPI - POST /api/v1/actions/dataSources/{dataSourceId}/scan
        // https://www.gooddata.com/docs/cloud/api-and-sdk/api/api-reference/#tag/Actions:-Data-Source-Operations/operation/scanDataSource
        // Schema: ScanResultPdm -> pdm.tables
        var root = MAPPER.readTree(rawJson);
        var tablesFound = root.path("pdm").path("tables").size();
        if (tablesFound == 0) {
            // Defensive fallbacks for self-hosted GoodData.CN (v2.x) and early declarative previews: pdm.datasets, tables, data
            tablesFound = root.path("pdm").path("datasets").size();
            if (tablesFound == 0) {
                tablesFound = root.path("tables").size();
                if (tablesFound == 0 && root.has("data") && root.path("data").isArray()) {
                    tablesFound = root.path("data").size();
                }
            }
        }
        logger.info("Data source scan found {} tables", tablesFound);

        var storageUri = runContext.storage().putFile(
            new ByteArrayInputStream(rawJson.getBytes(StandardCharsets.UTF_8)),
            "scan.json");

        return Output.builder()
            .tablesFound(tablesFound)
            .uri(storageUri)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Tables found",
            description = "The number of tables/views discovered by the data source scan."
        )
        private final int tablesFound;

        @Schema(
            title = "Raw scan output URI",
            description = "Kestra internal file URI pointing to the raw JSON scan response."
        )
        private final URI uri;
    }
}
