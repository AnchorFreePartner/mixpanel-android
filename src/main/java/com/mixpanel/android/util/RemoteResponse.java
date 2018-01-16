package com.mixpanel.android.util;

import android.support.annotation.NonNull;

public class RemoteResponse {
    private final int responseCode;
    @NonNull private final String responseMessage;
    @NonNull private final String responseBody;

    @SuppressWarnings("WeakerAccess")
    public RemoteResponse(final int responseCode, @NonNull final String responseMessage,
            @NonNull final String responseBody) {
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.responseBody = responseBody;
    }

    public int getResponseCode() {
        return responseCode;
    }

    @NonNull
    public String getResponseMessage() {
        return responseMessage;
    }

    @NonNull
    public String getResponseBody() {
        return responseBody;
    }

    @Override
    public String toString() {
        return "RemoteResponse{" +
                "responseCode=" + responseCode +
                ", responseMessage='" + responseMessage + '\'' +
                ", responseBody='" + responseBody + '\'' +
                '}';
    }
}
