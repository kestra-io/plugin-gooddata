package io.kestra.plugin.gooddata;

import lombok.Getter;

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
