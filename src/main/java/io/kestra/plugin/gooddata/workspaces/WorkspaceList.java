package io.kestra.plugin.gooddata.workspaces;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.gooddata.AbstractGooddata;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Kestra task that lists all GoodData workspaces accessible by the API token.
 *
 * <p>Calls {@code GET /api/v1/entities/workspaces} with Bearer-token auth, follows
 * pagination links ({@code links.next}) when present, stores the consolidated JSON response
 * as a Kestra internal file, and returns the workspace count.</p>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "List GoodData workspaces",
    description = "Retrieves all workspaces accessible to the configured API token by calling "
        + "`GET /api/v1/entities/workspaces`. Follows pagination links when multiple pages exist. "
        + "The raw or aggregated JSON response is stored as a Kestra internal file."
)
@Plugin(
    examples = {
        @Example(
            title = "List all GoodData workspaces",
            full = true,
            code = """
                id: workspaces_list
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.gooddata.workspaces.WorkspaceList
                    url: "https://acme.cloud.gooddata.com"
                    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
                """
        )
    }
)
public class WorkspaceList extends AbstractGooddata implements RunnableTask<WorkspaceList.Output> {

    @Schema(
        title = "Maximum pages",
        description = "Maximum number of pagination pages to fetch. Must be at least 1. Defaults to 50."
    )
    @NotNull
    @Min(1)
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> maxPages = Property.ofValue(50);

    @Override
    public WorkspaceList.Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rUrl = validateAndNormalizeUrl(runContext.render(url).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'url' must be provided")));
        var rToken = getRenderedApiToken(runContext);

        var rMaxPages = this.maxPages != null
            ? runContext.render(this.maxPages).as(Integer.class).orElse(50)
            : 50;

        if (rMaxPages < 1) {
            throw new IllegalArgumentException("'maxPages' must be greater than or equal to 1, got: " + rMaxPages);
        }

        var rRequestTimeout = this.requestTimeout != null
            ? runContext.render(this.requestTimeout).as(Duration.class).orElse(Duration.ofSeconds(60))
            : Duration.ofSeconds(60);

        logger.info("Listing GoodData workspaces from {}", rUrl);

        var result = fetchWorkspacePages(runContext, rUrl, rToken, rRequestTimeout, rMaxPages, logger);

        var bytes = result.getConsolidatedJson().getBytes(StandardCharsets.UTF_8);
        var storageUri = runContext.storage().putFile(new ByteArrayInputStream(bytes), "workspaces.json");

        if (result.isTruncated()) {
            logger.warn("GoodData workspace list reached maximum page limit of {} with remaining pages available. "
                + "The results are truncated. Consider increasing 'maxPages'.", rMaxPages);
        }

        logger.info("Found {} workspaces", result.getCount());

        return Output.builder()
            .count(result.getCount())
            .uri(storageUri)
            .truncated(result.isTruncated())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Workspace count",
            description = "The number of workspaces returned by the GoodData API."
        )
        private final int count;

        @Schema(
            title = "Raw response URI",
            description = "Kestra internal file URI pointing to the raw or consolidated JSON workspaces response."
        )
        private final URI uri;

        @Schema(
            title = "Truncated flag",
            description = "Whether the workspace list was truncated due to reaching maxPages while more pages were available."
        )
        private final Boolean truncated;
    }
}
