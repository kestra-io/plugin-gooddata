package io.kestra.plugin.gooddata.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.gooddata.AbstractGooddata;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Polling trigger that fires when a GoodData workspace layout or workspace list changes
 * relative to the persisted watermark in Kestra's namespace KV Store.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on GoodData workspace changes",
    description = "Polls GoodData for workspace or layout changes. Fires when the layout watermark "
        + "differs from the previous watermark stored in Kestra's namespace KV Store."
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger a flow when a GoodData workspace layout changes",
            full = true,
            code = """
                id: on_gooddata_change
                namespace: company.team

                triggers:
                  - id: watch
                    type: io.kestra.plugin.gooddata.workspaces.Trigger
                    url: "https://acme.cloud.gooddata.com"
                    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
                    interval: PT5M

                tasks:
                  - id: handle_event
                    type: io.kestra.plugin.core.log.Log
                    message: "GoodData change detected. New watermark: {{ trigger.watermark }}"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {

    @Schema(
        title = "Polling interval",
        description = "Interval between poll checks."
    )
    @Builder.Default
    private Duration interval = Duration.ofMinutes(5);

    @Schema(
        title = "GoodData host URL",
        description = "Base URL of the GoodData Cloud or GoodData.CN instance, e.g. https://acme.cloud.gooddata.com"
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> url;

    @Schema(
        title = "API token",
        description = "Personal API token for authentication."
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> apiToken;

    @Schema(
        title = "Workspace ID",
        description = "Optional workspace ID to monitor layout changes for. If omitted, monitors the workspace list."
    )
    @PluginProperty(group = "main")
    private Property<String> workspaceId;

    @Schema(
        title = "HTTP request timeout",
        description = "Timeout duration for polling HTTP requests."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<Duration> requestTimeout = Property.ofValue(Duration.ofSeconds(60));

    @Schema(
        title = "On startup trigger",
        description = "Whether to trigger an execution on initial startup when no prior watermark exists. Defaults to false."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> onStartup = Property.ofValue(false);

    @Override
    public Duration getInterval() {
        return interval;
    }

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext triggerContext) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        var rawUrl = runContext.render(url).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'url' must be provided"));
        var rUrl = AbstractGooddata.validateAndNormalizeUrl(rawUrl);
        var rToken = runContext.render(apiToken).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'apiToken' must be provided"));
        var rWsId = workspaceId != null ? runContext.render(workspaceId).as(String.class).orElse(null) : null;

        var rRequestTimeout = requestTimeout != null
            ? runContext.render(requestTimeout).as(Duration.class).orElse(Duration.ofSeconds(60))
            : Duration.ofSeconds(60);

        var rOnStartup = onStartup != null
            ? runContext.render(onStartup).as(Boolean.class).orElse(false)
            : false;

        String rawJson;
        String targetSuffix;

        if (rWsId != null && !rWsId.isBlank()) {
            var encodedWsId = AbstractGooddata.encodePath(rWsId);
            var endpoint = rUrl + "/api/v1/layout/workspaces/" + encodedWsId;
            targetSuffix = encodedWsId;

            logger.debug("Checking GoodData change at endpoint: {}", endpoint);

            var request = HttpRequest.builder()
                .uri(URI.create(endpoint))
                .method("GET")
                .addHeader("Authorization", "Bearer " + rToken)
                .addHeader("Accept", "application/json")
                .build();

            var response = AbstractGooddata.execute(runContext, request, rRequestTimeout);
            rawJson = response.getBody() != null ? response.getBody() : "{}";
        } else {
            targetSuffix = "entities";
            logger.debug("Checking GoodData change for workspace list at: {}", rUrl);

            var result = AbstractGooddata.fetchWorkspacePages(runContext, rUrl, rToken, rRequestTimeout, 50, logger);
            rawJson = result.getConsolidatedJson();

            if (result.isTruncated()) {
                logger.warn("GoodData workspace trigger reached maximum page limit of 50 with remaining pages available. "
                    + "The watermark is based on truncated results. Changes beyond page 50 may not trigger.");
            }
        }

        // Calculate watermark fingerprint via canonicalized SHA-256 (stable across key ordering and whitespace)
        var canonicalJson = canonicalizeJson(rawJson);
        var digest = MessageDigest.getInstance("SHA-256");
        var hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
        var currentWatermark = HexFormat.of().formatHex(hash);

        // Watermark KV key scoped per flow and trigger to prevent missed/duplicated events across flows
        var namespace = triggerContext.getNamespace();
        var flowId = triggerContext.getFlowId() != null ? triggerContext.getFlowId() : "default";
        var triggerId = triggerContext.getTriggerId() != null ? triggerContext.getTriggerId() : "default";
        var kvKey = "gooddata_watermark_" + flowId.length() + "_" + flowId + "_" + triggerId + "_" + targetSuffix;

        var kvStore = runContext.namespaceKv(namespace);
        var previousValue = kvStore.getValue(kvKey);

        if (previousValue.isEmpty()) {
            // First poll: seed watermark
            kvStore.put(kvKey, new KVValueAndMetadata(null, currentWatermark));
            if (!rOnStartup) {
                logger.info("Initialized GoodData watermark for {} to {}. Skipping initial execution.", kvKey, currentWatermark);
                return Optional.empty();
            }
            logger.info("Initialized GoodData watermark for {} to {} with onStartup=true. Triggering execution.", kvKey, currentWatermark);
        } else if (currentWatermark.equals(String.valueOf(previousValue.get().value()))) {
            logger.debug("No GoodData change detected for {}, watermark={}", kvKey, currentWatermark);
            return Optional.empty();
        } else {
            logger.info("GoodData change detected for {}. Updating watermark to {}", kvKey, currentWatermark);
            kvStore.put(kvKey, new KVValueAndMetadata(null, currentWatermark));
        }

        var storageUri = runContext.storage() != null
            ? runContext.storage().putFile(
                new ByteArrayInputStream(rawJson.getBytes(StandardCharsets.UTF_8)),
                "trigger_" + targetSuffix + ".json"
            )
            : null;

        var output = Output.builder()
            .watermark(currentWatermark)
            .workspaceId(rWsId)
            .uri(storageUri)
            .build();

        var execution = TriggerService.generateExecution(this, conditionContext, triggerContext, output);
        return Optional.of(execution);
    }

    /**
     * Produces a deterministic canonical string representation of a JSON document by recursively
     * sorting object keys alphabetically and removing insignificant whitespace.
     */
    public static String canonicalizeJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            StringBuilder sb = new StringBuilder();
            canonicalizeNode(root, sb);
            return sb.toString();
        } catch (Exception e) {
            return json.trim();
        }
    }

    private static final Set<String> VOLATILE_FIELDS = Set.of(
        "links", "link", "currenttime", "generatedat", "exportedat", "requesttimestamp"
    );

    public static boolean isVolatileField(String fieldName) {
        return fieldName != null && VOLATILE_FIELDS.contains(fieldName.toLowerCase());
    }

    private static void canonicalizeNode(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
        } else if (node.isBoolean()) {
            sb.append(node.asBoolean());
        } else if (node.isNumber()) {
            sb.append(node.asText());
        } else if (node.isTextual()) {
            try {
                sb.append(MAPPER.writeValueAsString(node.asText()));
            } catch (Exception e) {
                sb.append("\"").append(node.asText().replace("\"", "\\\"")).append("\"");
            }
        } else if (node.isArray()) {
            sb.append("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                canonicalizeNode(node.get(i), sb);
            }
            sb.append("]");
        } else if (node.isObject()) {
            sb.append("{");
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(key -> {
                if (!isVolatileField(key)) {
                    fieldNames.add(key);
                }
            });
            Collections.sort(fieldNames);
            for (int i = 0; i < fieldNames.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                String key = fieldNames.get(i);
                try {
                    sb.append(MAPPER.writeValueAsString(key));
                } catch (Exception e) {
                    sb.append("\"").append(key.replace("\"", "\\\"")).append("\"");
                }
                sb.append(":");
                canonicalizeNode(node.get(key), sb);
            }
            sb.append("}");
        } else {
            sb.append(node.toString());
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Watermark",
            description = "The updated SHA-256 fingerprint representing the new layout/entities state."
        )
        private final String watermark;

        @Schema(
            title = "Workspace ID",
            description = "The monitored workspace ID, if configured."
        )
        private final String workspaceId;

        @Schema(
            title = "State URI",
            description = "Kestra internal file URI pointing to the detected state JSON."
        )
        private final URI uri;
    }
}
