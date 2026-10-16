package io.kestra.plugin.gooddata;

import lombok.Getter;

/**
 * Exception thrown when GoodData API returns an error HTTP status code.
 */
@Getter
public class GooddataApiException extends RuntimeException {
    private final int statusCode;
    private final String responseBodyExcerpt;

    public GooddataApiException(String message, int statusCode, String responseBodyExcerpt) {
        super(message);
        this.statusCode = statusCode;
        this.responseBodyExcerpt = responseBodyExcerpt;
    }
}
