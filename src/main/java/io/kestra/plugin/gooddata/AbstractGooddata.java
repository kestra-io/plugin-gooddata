package io.kestra.plugin.gooddata;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractGooddata extends Task {

    @Schema(
        title = "GoodData host URL",
        description = "Base URL of the GoodData Cloud or GoodData.CN instance, e.g. https://acme.cloud.gooddata.com"
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> url;

    @Schema(
        title = "API token",
        description = """
            Personal API token for authentication (sent as Authorization: Bearer <token>). \
            Use a Kestra secret expression such as {{ secret('GOODDATA_TOKEN') }}.
            """
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> apiToken;

    @Schema(
        title = "HTTP request timeout",
        description = "Timeout duration for individual HTTP requests to the GoodData API. Defaults to PT60S."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<Duration> requestTimeout = Property.ofValue(Duration.ofSeconds(60));

    public static String encodePath(String pathSegment) {
        if (pathSegment == null) {
            return "";
        }
        return URLEncoder.encode(pathSegment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String validateAndNormalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("'url' must be provided");
        }
        var trimmed = rawUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("GoodData URL must start with 'http://' or 'https://', got: " + trimmed);
        }
        return trimmed.replaceAll("/+$", "");
    }

    public static void assertSameOrigin(URI link, String baseUrl) {
        var baseUri = URI.create(baseUrl);
        var basePort = baseUri.getPort() != -1 ? baseUri.getPort() : ("https".equalsIgnoreCase(baseUri.getScheme()) ? 443 : 80);
        var linkPort = link.getPort() != -1 ? link.getPort() : ("https".equalsIgnoreCase(link.getScheme()) ? 443 : 80);

        if (link.getHost() == null
            || !link.getHost().equalsIgnoreCase(baseUri.getHost())
            || link.getScheme() == null
            || !link.getScheme().equalsIgnoreCase(baseUri.getScheme())
            || basePort != linkPort) {
            throw new SecurityException("Untrusted URI in GoodData response: '"
                + link + "' does not match configured base URL '" + baseUrl + "'");
        }
    }

    public static class WorkspacePagesResult {
        private final String consolidatedJson;
        private final boolean truncated;
        private final int count;

        public WorkspacePagesResult(String consolidatedJson, boolean truncated, int count) {
            this.consolidatedJson = consolidatedJson;
            this.truncated = truncated;
            this.count = count;
        }

        public String getConsolidatedJson() {
            return consolidatedJson;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public int getCount() {
            return count;
        }
    }

    public static WorkspacePagesResult fetchWorkspacePages(
        RunContext runContext,
        String baseUrl,
        String token,
        Duration timeout,
        int maxPages,
        Logger logger
    ) throws Exception {
        var mapper = JacksonMapper.ofJson();
        var currentUrl = baseUrl + "/api/v1/entities/workspaces?sort=id";
        var allWorkspaces = mapper.createArrayNode();
        String firstPageJson = null;
        var pageCount = 0;
        var isTruncated = false;

        var visitedUrls = new HashSet<String>();
        visitedUrls.add(currentUrl);

        while (currentUrl != null && !currentUrl.isBlank()) {
            if (pageCount >= maxPages) {
                isTruncated = true;
                break;
            }

            pageCount++;
            var request = HttpRequest.builder()
                .uri(URI.create(currentUrl))
                .method("GET")
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Accept", "application/json")
                .build();

            var response = execute(runContext, request, timeout);
            var pageJson = response.getBody() != null ? response.getBody() : "{}";
            if (firstPageJson == null) {
                firstPageJson = pageJson;
            }

            var pageRoot = mapper.readTree(pageJson);
            var dataNode = pageRoot.path("data");
            if (dataNode.isArray()) {
                for (var item : dataNode) {
                    allWorkspaces.add(item);
                }
            }

            var nextLink = pageRoot.path("links").path("next").asText("");
            if (!nextLink.isBlank()) {
                String resolvedNextUrl;
                if (nextLink.startsWith("http://") || nextLink.startsWith("https://")) {
                    var nextUri = URI.create(nextLink);
                    assertSameOrigin(nextUri, baseUrl);
                    resolvedNextUrl = nextLink;
                } else {
                    resolvedNextUrl = baseUrl + (nextLink.startsWith("/") ? nextLink : "/" + nextLink);
                }

                if (visitedUrls.contains(resolvedNextUrl)) {
                    logger.warn("Detected circular pagination loop in GoodData workspaces. Next URL '{}' already visited. Halting pagination.", resolvedNextUrl);
                    currentUrl = null;
                } else {
                    visitedUrls.add(resolvedNextUrl);
                    currentUrl = resolvedNextUrl;
                    logger.debug("Fetching next page of workspaces from: {}", currentUrl);
                }
            } else {
                currentUrl = null;
            }
        }

        String consolidatedJson;
        if (pageCount <= 1) {
            consolidatedJson = firstPageJson != null ? firstPageJson : "{}";
        } else {
            var consolidated = mapper.createObjectNode();
            consolidated.set("data", allWorkspaces);
            consolidatedJson = mapper.writeValueAsString(consolidated);
        }

        return new WorkspacePagesResult(consolidatedJson, isTruncated, allWorkspaces.size());
    }

    protected String getRenderedApiToken(RunContext runContext) throws IllegalVariableEvaluationException {
        var rToken = runContext.render(this.apiToken).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'apiToken' must be provided"));
        return rToken;
    }

    public static HttpClient createHttpClient(RunContext runContext, Duration timeout) throws IllegalVariableEvaluationException {
        var effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(60);
        var timeoutConfig = TimeoutConfiguration.builder()
            .connectTimeout(Property.ofValue(effectiveTimeout))
            .readIdleTimeout(Property.ofValue(effectiveTimeout))
            .build();

        var config = HttpConfiguration.builder()
            .timeout(timeoutConfig)
            .allowFailed(Property.ofValue(true))
            .followRedirects(Property.ofValue(false))
            .build();
        return new HttpClient(runContext, config);
    }

    protected HttpResponse<String> executeRequest(RunContext runContext, HttpRequest request) throws Exception {
        var rRequestTimeout = this.requestTimeout != null
            ? runContext.render(this.requestTimeout).as(Duration.class).orElse(Duration.ofSeconds(60))
            : Duration.ofSeconds(60);
        return execute(runContext, request, rRequestTimeout);
    }

    public static HttpResponse<String> execute(RunContext runContext, HttpRequest request, Duration timeout) throws Exception {
        try (var client = createHttpClient(runContext, timeout)) {
            var response = client.request(request, String.class);
            var statusCode = response.getStatus() != null ? response.getStatus().getCode() : 0;
            if (statusCode < 200 || statusCode >= 300) {
                var responseBody = response.getBody();
                var excerpt = responseBody != null
                    ? (responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody)
                    : "";
                var hint = switch (statusCode) {
                    case 401 -> "Check that your API token is valid and not expired.";
                    case 403 -> "Check that your API token has sufficient permissions for this operation.";
                    case 404 -> "Check that the requested resource, data source, or workspace ID exists.";
                    case 429 -> "Rate limit reached. Reduce polling frequency or retry later.";
                    default -> "Verify your GoodData endpoint URL and request parameters.";
                };
                throw new GooddataApiException(
                    "GoodData API error (HTTP " + statusCode + " "
                        + (response.getStatus() != null ? response.getStatus().getReason() : "") + "): "
                        + excerpt + ". Remediation: " + hint,
                    statusCode,
                    excerpt
                );
            }

            // Hard size cap after reading (KPS-08: prevent memory exhaustion from unbounded response bodies)
            var responseBody = response.getBody();
            if (responseBody != null && responseBody.length() > MAX_RESPONSE_BODY_CHARS) {
                throw new IllegalStateException(
                    "GoodData API response body exceeds the maximum permitted limit of "
                    + MAX_RESPONSE_BODY_CHARS + " characters (" + responseBody.length() + " chars). "
                    + "Request rejected to prevent memory exhaustion. "
                    + "Narrow the request (for example, fewer attributes or tighter filters in the AFM request) "
                    + "or reduce the size of the exported workspace."
                );
            }

            return response;
        }
    }

    /**
     * Maximum permitted response body length in characters (10,000,000 chars, ~10 MB).
     * Responses exceeding this limit are rejected to prevent memory exhaustion (KPS-08).
     */
    public static final int MAX_RESPONSE_BODY_CHARS = 10_000_000;
}
