package com.mixpanel.android.util;

import android.support.annotation.NonNull;

public class HttpResponse {
    private final int responseCode;
    @NonNull private final String responseMessage;

    public HttpResponse(final int responseCode, @NonNull final String responseMessage) {
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
    }

    public int getResponseCode() {
        return responseCode;
    }

    @NonNull
    public String getResponseMessage() {
        return responseMessage;
    }
}
